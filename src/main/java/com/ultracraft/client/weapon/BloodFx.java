package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * ULTRAKILL's blood on hit (BloodsplatterManager "BS Body Small" / "BS Splatter": a cone of blooddrop particles
 * from the wound, drops that land become Bloodstains). Drops are camera-facing quads with the game's
 * `blooddrop` sprite; a drop that hits a surface leaves a flat stain that fades after a while.
 */
public final class BloodFx {
	private static final Identifier DROP = new Identifier(UltraCraft.MOD_ID, "textures/uk/blooddrop.png");
	private static final double COUNT = Tune.def("fx.bloodDrops", 70, "blood drops per hit (BS Body Small emits a 25° cone burst; x5 the old 14 by the user)");
	private static final double SPEED = Tune.def("fx.bloodSpeed", 9, "blood drop start speed, u/s");
	private static final double SIZE = Tune.def("fx.bloodSize", 0.22, "blood drop quad size, u");
	private static final double STAIN_LIFE = Tune.def("fx.bloodStainLife", 40, "seconds a blood stain stays");
	private static final double ON = Tune.def("fx.blood", 1, "0 = no blood");
	private static final double TOWARD_YOU = Tune.def("fx.bloodTowardYou", 0.5, "share of a hit's blood that sprays back at V1 (kills keep it all; user: half)");

	private static final class Drop {
		Vec3d pos, vel;
		float life, size;
	}

	private static final class Stain {
		Vec3d pos, normal;
		float life, size, rot;
	}

	private static final List<Drop> drops = new ArrayList<>();
	private static final List<Stain> stains = new ArrayList<>();
	private static final Random rnd = new Random();
	private static long lastNanos;

	private BloodFx() {}

	/** A wound at `pos`; `dir` is the direction the hit travelled (blood sprays back out along -dir and sideways). */
	public static void spawn(Vec3d pos, Vec3d dir, float amount) {
		spawn(pos, dir, amount, true);
	}

	/** `kill` false: a hit on a mob that lives on (or a body already down) - only fx.bloodTowardYou of its spray back at V1. */
	public static void spawn(Vec3d pos, Vec3d dir, float amount, boolean kill) {
		if (!Tune.on("fx.blood")) return;
		int n = Math.max(3, Math.round((float) (Tune.get("fx.bloodDrops") * amount)));
		Vec3d back = dir.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : dir.normalize().multiply(-1);
		double s = Damage.SCALE_BLOCKS;
		double towardYou = kill ? 1 : Tune.get("fx.bloodTowardYou");
		for (int i = 0; i < n; i++) {
			Drop d = new Drop();
			d.pos = pos.add((rnd.nextDouble() - 0.5) * 0.15, (rnd.nextDouble() - 0.5) * 0.15, (rnd.nextDouble() - 0.5) * 0.15);
			// 25° cone around -dir, plus a little of the hit's own momentum carrying through
			Vec3d jitter = new Vec3d(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).multiply(0.45);
			Vec3d v = back.add(jitter).normalize().multiply(Tune.get("fx.bloodSpeed") * (0.4 + rnd.nextDouble() * 0.8) * s);
			boolean forward = rnd.nextInt(4) == 0;
			if (!forward && rnd.nextDouble() >= towardYou) continue;
			if (forward) v = dir.normalize().multiply(Tune.get("fx.bloodSpeed") * 0.6 * s).add(jitter.multiply(2 * s));
			d.vel = v.add(0, 2 * s, 0);
			d.life = 0.6f + rnd.nextFloat() * 0.8f;
			d.size = (float) (Tune.get("fx.bloodSize") * s * (0.6 + rnd.nextDouble() * 0.8));
			drops.add(d);
		}
		if (drops.size() > 3000) drops.subList(0, drops.size() - 3000).clear();
	}

	public static void clear() {
		drops.clear();
		stains.clear();
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null || (drops.isEmpty() && stains.isEmpty())) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		lastNanos = now;
		if (c.isPaused()) dt = 0;
		double g = 9.81 * 3 * Damage.SCALE_BLOCKS; // ULTRAKILL gravity ≈ 3 g on the rigidbodies
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(DROP));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		// drops
		for (Iterator<Drop> it = drops.iterator(); it.hasNext(); ) {
			Drop d = it.next();
			if (dt > 0) {
				d.life -= dt;
				Vec3d next = d.pos.add(d.vel.multiply(dt));
				BlockHitResult hit = c.world.raycast(new RaycastContext(d.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, c.player));
				if (hit.getType() != HitResult.Type.MISS) {
					Stain st = new Stain();
					st.pos = hit.getPos();
					st.normal = Vec3d.of(hit.getSide().getVector());
					st.life = (float) Tune.get("fx.bloodStainLife");
					st.size = d.size * (2.2f + rnd.nextFloat() * 1.5f);
					st.rot = rnd.nextFloat() * 6.2832f;
					stains.add(st);
					if (stains.size() > 400) stains.remove(0);
					it.remove();
					continue;
				}
				d.pos = next;
				d.vel = d.vel.add(0, -g * dt, 0);
			}
			if (d.life <= 0) { it.remove(); continue; }
			billboard(vc, m, cam, d.pos, d.size, 1f);
		}
		// stains: flat on their surface
		for (Iterator<Stain> it = stains.iterator(); it.hasNext(); ) {
			Stain st = it.next();
			st.life -= dt;
			if (st.life <= 0) { it.remove(); continue; }
			float a = Math.min(1f, st.life / 4f);
			flat(vc, m, st.pos.add(st.normal.multiply(0.01)), st.normal, st.size, st.rot, a);
		}
		ms.pop();
		buffers.draw();
	}

	private static void billboard(VertexConsumer vc, Matrix4f m, Camera cam, Vec3d p, float size, float a) {
		org.joml.Vector3f r = new org.joml.Vector3f(cam.getHorizontalPlane()).cross(cam.getVerticalPlane()).normalize();
		org.joml.Vector3f u = cam.getVerticalPlane();
		float h = size / 2;
		v(vc, m, p.x - r.x * h - u.x * h, p.y - r.y * h - u.y * h, p.z - r.z * h - u.z * h, 0, 1, a);
		v(vc, m, p.x + r.x * h - u.x * h, p.y + r.y * h - u.y * h, p.z + r.z * h - u.z * h, 1, 1, a);
		v(vc, m, p.x + r.x * h + u.x * h, p.y + r.y * h + u.y * h, p.z + r.z * h + u.z * h, 1, 0, a);
		v(vc, m, p.x - r.x * h + u.x * h, p.y - r.y * h + u.y * h, p.z - r.z * h + u.z * h, 0, 0, a);
	}

	private static void flat(VertexConsumer vc, Matrix4f m, Vec3d p, Vec3d n, float size, float rot, float a) {
		Vec3d t = Math.abs(n.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d x = n.crossProduct(t).normalize();
		Vec3d y = n.crossProduct(x).normalize();
		double cs = Math.cos(rot), sn = Math.sin(rot);
		Vec3d xr = x.multiply(cs).add(y.multiply(sn)).multiply(size / 2), yr = y.multiply(cs).subtract(x.multiply(sn)).multiply(size / 2);
		Vec3d a1 = p.subtract(xr).subtract(yr), b1 = p.add(xr).subtract(yr), c1 = p.add(xr).add(yr), d1 = p.subtract(xr).add(yr);
		v(vc, m, a1.x, a1.y, a1.z, 0, 1, a); v(vc, m, b1.x, b1.y, b1.z, 1, 1, a); v(vc, m, c1.x, c1.y, c1.z, 1, 0, a); v(vc, m, d1.x, d1.y, d1.z, 0, 0, a);
		// both faces
		v(vc, m, d1.x, d1.y, d1.z, 0, 0, a); v(vc, m, c1.x, c1.y, c1.z, 1, 0, a); v(vc, m, b1.x, b1.y, b1.z, 1, 1, a); v(vc, m, a1.x, a1.y, a1.z, 0, 1, a);
	}

	private static void v(VertexConsumer vc, Matrix4f m, double x, double y, double z, float u, float w, float a) {
		vc.vertex(m, (float) x, (float) y, (float) z).color(0.55f, 0f, 0f, a).texture(u, w).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
