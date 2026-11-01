package com.ultracraft.client.v1;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Clash mode lava (PlatformerMovement.Burn, from a Burn HurtZone such as the level lava): V1 dies on the spot. The
 * defaultBurnEffect (HotSand: 10 smokesprite puffs and the HotSand sound at 0.3) goes off, the model is left standing as
 * a still copy in its pose with every material swapped for Charcoal (SandboxUtils.StripForPreview), and after 1 s it
 * BecomeAsh: the CrashAshes sound and an AshParticle at every bone, the copy gone; DeathOver 1 s later. Here V1 is held
 * where it burnt, the server kills it after the same 2 s (Minecraft's own death and respawn then).
 */
public final class V1Burn {
	private static final Identifier SMOKE = new Identifier(UltraCraft.MOD_ID, "textures/uk/smokesprite.png");
	private static final Identifier ASH = new Identifier(UltraCraft.MOD_ID, "textures/uk/blooddrop.png");

	static {
		Tune.def("clash.lavaBurn", 1, "1 = in clash mode (third person) ULTRAKILL lava burns V1 to ash and kills it (PlatformerMovement.Burn)");
	}

	private V1Burn() {}

	private static boolean burning, burnt;
	private static long startNanos;
	private static RigInstance copy;
	private static Vec3d pos;
	private static float yaw;

	private static final class Bit {
		Vec3d pos, vel;
		float age, life, size, grey, alpha;
		/** Up (smoke) or down (ash) acceleration, u/s². */
		double accel;
		boolean landed;
	}

	private static final List<Bit> smoke = new ArrayList<>(), ash = new ArrayList<>();
	private static final Random rnd = new Random();
	private static long lastNanos;

	/** Lava in clash mode burns instead of bouncing (survival / adventure: creative V1 cannot die). */
	public static boolean wants(ClientPlayerEntity p) {
		return Tune.on("clash.lavaBurn") && !MinecraftClient.getInstance().options.getPerspective().isFirstPerson() && !p.isCreative();
	}

	/** V1 is burning: it stands where it burnt (no movement until it dies). */
	public static boolean holding() {
		return burning;
	}

	/** The live model is not drawn while its charcoal copy stands or after it turned to ash, until V1 lives again. */
	public static boolean hidesLive(PlayerEntity p) {
		if (!burnt) return false;
		if (!burning && p.isAlive()) burnt = false;
		return burnt;
	}

	public static void start(ClientPlayerEntity p) {
		if (burning || !p.isAlive()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		float td = c.getTickDelta();
		burning = burnt = true;
		startNanos = System.nanoTime();
		pos = p.getLerpedPos(td);
		yaw = MathHelper.lerp(td, p.prevBodyYaw, p.bodyYaw);
		copy = V1Renderer.charcoalCopy(p);
		V1.INSTANCE.vel = Vec3d.ZERO;
		p.setVelocity(Vec3d.ZERO);
		hotSand(pos);
		UKSound.play("uk.environment.hotsand", 0.3f, 1f);
		com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.LAVA_BURN, 0, 0, p.getEyePos(), new Vec3d(0, 1, 0)));
	}

	/**
	 * HotSand: a burst of 10 at once from a 0.1 u cone, still (speed 0), 2-3 u, 1 s, colour (0.74, 0.74, 0.74, 0.21)
	 * fading to nothing over its life, drifting up at gravity x 0-0.5 (gravityModifier -0.5..0, UK gravity 40 u/s²).
	 */
	private static void hotSand(Vec3d at) {
		double s = Damage.SCALE_BLOCKS;
		for (int i = 0; i < 10; i++) {
			Bit b = new Bit();
			b.pos = at.add((rnd.nextDouble() - 0.5) * 0.2 * s, 0, (rnd.nextDouble() - 0.5) * 0.2 * s);
			b.vel = Vec3d.ZERO;
			b.life = 1;
			b.size = (float) ((2 + rnd.nextDouble()) * s);
			b.grey = 0.736f;
			b.alpha = 0.21f;
			b.accel = rnd.nextDouble() * 0.5 * 40 * s;
			smoke.add(b);
		}
	}

	/**
	 * BecomeAsh: an AshParticle at every bone of the copy - 5 at once from a flat 0.5 u circle, outward at 0-5 u/s,
	 * 0.1-0.5 u, 2-4 s, between grey 0.12 and black, falling at gravity x 0.5-1; they stop dead where they land
	 * (collision dampen 1, bounce 0). The blooddrop sprite (the BloodDrop material).
	 */
	private static void becomeAsh() {
		if (copy == null) return;
		double s = Damage.SCALE_BLOCKS;
		Matrix4f model = V1Renderer.copyMatrix(yaw);
		copy.pose(new Matrix4f());
		List<Vec3d> points = new ArrayList<>();
		for (int i = 0; i < copy.rigBoneCount(); i++) {
			Vector3f t = copy.boneWorldAt(i).getTranslation(new Vector3f());
			Vector3f w = model.transformPosition(new Vector3f(t.x, t.y, -t.z));
			points.add(pos.add(w.x, w.y, w.z));
		}
		for (Vec3d at : points) {
			for (int k = 0; k < 5; k++) {
				Bit b = new Bit();
				double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * 0.5;
				Vec3d out = new Vec3d(Math.cos(a), 0, Math.sin(a));
				b.pos = at.add(out.multiply(r * s));
				b.vel = out.multiply(rnd.nextDouble() * 5 * s);
				b.life = 2 + rnd.nextFloat() * 2;
				b.size = (float) ((0.1 + rnd.nextDouble() * 0.4) * s);
				b.grey = rnd.nextFloat() * 0.122f;
				b.alpha = 1;
				b.accel = -(0.5 + rnd.nextDouble() * 0.5) * 40 * s;
				ash.add(b);
			}
		}
		if (ash.size() > 2000) ash.subList(0, ash.size() - 2000).clear();
		UKSound.play("uk.environment.crashashes", 1f, 1f);
		copy = null;
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (c.isPaused() || com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		if (burning) {
			double t = (now - startNanos) / 1e9;
			if (copy != null && t >= 1) becomeAsh();
			// the server's kill comes 2 s in; a totem (or a lost packet) leaves V1 standing: let it go after 3 s
			if ((c.player != null && !c.player.isAlive()) || t >= 3) burning = false;
		}
		// (never a charcoal copy left standing once the burn is over)
		if (!burning && copy != null) becomeAsh();
		if (copy == null && smoke.isEmpty() && ash.isEmpty()) return;
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		if (copy != null) {
			ms.push();
			ms.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
			V1Renderer.renderCopy(copy, ms, buffers, WorldRenderer.getLightmapCoordinates(c.world, BlockPos.ofFloored(pos.add(0, 1, 0))), yaw);
			ms.pop();
			buffers.draw();
		}
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		org.joml.Vector3f r = new org.joml.Vector3f(cam.getHorizontalPlane()).cross(cam.getVerticalPlane()).normalize();
		org.joml.Vector3f u = cam.getVerticalPlane();
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(ASH));
		for (Iterator<Bit> it = ash.iterator(); it.hasNext(); ) {
			Bit b = it.next();
			if (dt > 0) {
				b.life -= dt;
				if (!b.landed) {
					Vec3d next = b.pos.add(b.vel.multiply(dt));
					var hit = c.world.raycast(new RaycastContext(b.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, c.player));
					if (hit.getType() != HitResult.Type.MISS) { b.pos = hit.getPos(); b.vel = Vec3d.ZERO; b.landed = true; }
					else { b.pos = next; b.vel = b.vel.add(0, b.accel * dt, 0); }
				}
			}
			if (b.life <= 0) { it.remove(); continue; }
			quad(vc, m, r, u, b.pos, b.size, b.grey, 1);
		}
		buffers.draw();
		vc = buffers.getBuffer(RenderLayer.getEntityTranslucentEmissive(SMOKE));
		for (Iterator<Bit> it = smoke.iterator(); it.hasNext(); ) {
			Bit b = it.next();
			if (dt > 0) {
				b.age += dt;
				b.pos = b.pos.add(b.vel.multiply(dt));
				b.vel = b.vel.add(0, b.accel * dt, 0);
			}
			if (b.age >= b.life) { it.remove(); continue; }
			quad(vc, m, r, u, b.pos, b.size, b.grey, b.alpha * (1 - b.age / b.life));
		}
		ms.pop();
		buffers.draw();
	}

	private static void quad(VertexConsumer vc, Matrix4f m, Vector3f r, Vector3f u, Vec3d p, float size, float grey, float a) {
		float h = size / 2;
		v(vc, m, p.x - r.x * h - u.x * h, p.y - r.y * h - u.y * h, p.z - r.z * h - u.z * h, 0, 1, grey, a);
		v(vc, m, p.x + r.x * h - u.x * h, p.y + r.y * h - u.y * h, p.z + r.z * h - u.z * h, 1, 1, grey, a);
		v(vc, m, p.x + r.x * h + u.x * h, p.y + r.y * h + u.y * h, p.z + r.z * h + u.z * h, 1, 0, grey, a);
		v(vc, m, p.x - r.x * h + u.x * h, p.y - r.y * h + u.y * h, p.z - r.z * h + u.z * h, 0, 0, grey, a);
	}

	private static void v(VertexConsumer vc, Matrix4f m, double x, double y, double z, float u, float w, float grey, float a) {
		vc.vertex(m, (float) x, (float) y, (float) z).color(grey, grey, grey, a).texture(u, w).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	public static void clear() {
		smoke.clear();
		ash.clear();
		copy = null;
		burning = burnt = false;
	}
}
