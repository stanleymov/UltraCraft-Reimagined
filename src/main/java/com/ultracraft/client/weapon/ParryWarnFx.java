package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.net.ParryWarnPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The parry warning star (ParryWarn): ULTRAKILL's parryableFlash, V2Flash - the 'muzzleflash' star tinted
 * (1, 0.936, 0.665), facing the camera at the attacker's head, its sound ComputerSFX_alerts-004 - here a short flash
 * that shrinks away (parry.warnSize / parry.warnTime by eye).
 */
public final class ParryWarnFx {
	private static final Identifier STAR = new Identifier(UltraCraft.MOD_ID, "textures/uk/muzzleflash.png");

	static {
		Tune.def("parry.warnSize", 1.2, "parry warning star size, blocks (by eye)");
		Tune.def("parry.warnTime", 0.3, "parry warning star life, s (by eye; the shot comes parry.warnLead ticks after it appears)");
		Tune.def("parry.warnMinView", 0.06, "parry warning star: at least this many blocks wide per block of distance (keeps a far one visible)");
		Tune.def("parry.warnMaxView", 0.9, "parry warning star: at most this many blocks wide per block of distance (a creeper's star up close)");
		Tune.def("parry.warnVolume", 0.8, "parry warning sound volume (V2Flash's AudioSource: 1)");
	}

	private record Warn(int id, int head, long nanos, float spin) {}

	private static final List<Warn> warns = new ArrayList<>();

	private ParryWarnFx() {}

	public static void add(ParryWarnPayload m) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) return;
		warns.add(new Warn(m.entityId(), m.head(), System.nanoTime(), (float) (Math.random() * Math.PI * 2)));
		Entity e = c.world.getEntityById(m.entityId());
		// (a warning to V1: heard at full volume however far the shooter is - a ghast fires from 40 blocks and more)
		if (e != null) UKSound.play("uk.ui.computersfx_alerts-004", (float) Tune.get("parry.warnVolume"), 1f);
	}

	/** The head the shot leaves from (the Wither's side heads sit out to its sides). */
	private static Vec3d headPos(Entity e, int head, float pt) {
		Vec3d eye = e.getLerpedPos(pt).add(0, e.getStandingEyeHeight(), 0);
		if (head == 0 || !(e instanceof WitherEntity w)) return eye;
		// WitherEntity.getHeadX/Y/Z: 1.3 out to the side at body yaw, 0.2 lower
		double a = Math.toRadians(w.bodyYaw + 180 * (head - 1));
		return new Vec3d(e.getLerpedPos(pt).x + Math.cos(a) * 1.3, e.getLerpedPos(pt).y + 2.2, e.getLerpedPos(pt).z + Math.sin(a) * 1.3);
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || warns.isEmpty()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) { warns.clear(); return; }
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		double life = Math.max(0.05, Tune.get("parry.warnTime"));
		long now = System.nanoTime();
		var ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.emissiveDepth(STAR));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		for (var it = warns.iterator(); it.hasNext(); ) {
			Warn w = it.next();
			double t = (now - w.nanos) / 1e9 / life;
			Entity en = c.world.getEntityById(w.id);
			if (t >= 1 || en == null || !en.isAlive()) { it.remove(); continue; }
			Vec3d at = headPos(en, w.head, e.getPartialTick());
			// in front of the head toward the camera, so the head does not hide it
			Vec3d toCam = camPos.subtract(at).normalize();
			// (out past the body's side: a ghast is 4 wide, and the star sat inside it; but never more than halfway to the
			// camera - a creeper hissing right in front of V1 had its star behind the eyes: user)
			at = at.add(toCam.multiply(Math.min(en.getWidth() * 0.8 + 0.5, camPos.distanceTo(at) * 0.5)));
			// (never smaller on screen than parry.warnMinView blocks per block away: a far ghast's star was a dot; nor
			// bigger than parry.warnMaxView, so one that close does not fill the screen)
			double dist = at.distanceTo(camPos);
			double size = Math.min(Math.max(Tune.get("parry.warnSize"), dist * Tune.get("parry.warnMinView")), dist * Tune.get("parry.warnMaxView"));
			float half = (float) (size * 0.5 * MathHelper.lerp((float) t, 1f, 0.5f));
			float alpha = (float) MathHelper.lerp(t, 1, 0.3);
			Vec3d right = toCam.crossProduct(new Vec3d(0, 1, 0));
			if (right.lengthSquared() < 1e-6) right = new Vec3d(1, 0, 0);
			right = right.normalize();
			Vec3d up = right.crossProduct(toCam).normalize();
			double cs = Math.cos(w.spin), sn = Math.sin(w.spin);
			Vec3d r = right.multiply(cs).add(up.multiply(sn)).multiply(half), u = up.multiply(cs).subtract(right.multiply(sn)).multiply(half);
			v(vc, m, at.subtract(r).subtract(u), 0, 1, alpha);
			v(vc, m, at.add(r).subtract(u), 1, 1, alpha);
			v(vc, m, at.add(r).add(u), 1, 0, alpha);
			v(vc, m, at.subtract(r).add(u), 0, 0, alpha);
		}
		ms.pop();
		buffers.draw();
	}

	private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float u, float vv, float a) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(1f, 0.936f, 0.665f, a).texture(u, vv)
			.overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
