package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.render.UkLayers;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * HammerHitLight / Medium / Heavy (ShotgunHammer.hitImpactParticle): two RageEffectWhite rings at the camera's turn
 * under a 0.1 root - one grey (0.5) growing 1200/s, one white growing 600/s (ScaleNFade from scale 1), both fading 4/s
 * - and, on Medium / Heavy, a point light (range 30, intensity 30, its range down 160/s), drawn as a flash on the gun.
 */
public final class HammerFx {
	private static final Identifier RING = new Identifier(UltraCraft.MOD_ID, "textures/uk/rageeffectwhite.png");

	private record Ring(Vec3d pos, float grow, float shade, float start) {}

	private static final class Burst {
		Vec3d pos;
		float age;
		/** the grey and the white ring's scale at the start (HammerHit: 1 and 1; RocketLauncherFire: 8 and 4) */
		float startGrey = 1, startWhite = 1, grow = 1;
		/** times drawn over itself (more solid) */
		int passes = 1;
		/** fade per second (ScaleNFade fadeSpeed 4) and its curve: the opacity is (1 - fade x age) ^ fadePow */
		float fade = 4, fadePow = 1;
	}

	static {
		com.ultracraft.client.Tune.def("rocket.ringStart", 0.5, "rocket launcher shot rings: start size, x the game's 8 / 4 (by eye: the user found them starting too far out)");
		com.ultracraft.client.Tune.def("rocket.ringGrow", 1, "rocket launcher shot rings: growth, x the game's 1200 / 600 a second");
		com.ultracraft.client.Tune.def("rocket.ringFade", 4, "rocket launcher shot rings: fade per second (from the game: 4)");
		com.ultracraft.client.Tune.def("rocket.ringFadePow", 2, "rocket launcher shot rings: fade curve, 1 = the game's straight fade; higher drops from the first frame (by eye: drawn twice, a straight fade looked solid until they were wide)");
		com.ultracraft.client.Tune.def("rocket.ringPasses", 2, "rocket launcher shot rings: times drawn over themselves, 1 = the game's (by eye: the user found them too faint)");
	}

	private static final List<Burst> bursts = new ArrayList<>();
	private static long lastNanos;

	private HammerFx() {}

	public static void impact(Vec3d at, int tier) {
		Burst b = new Burst();
		b.pos = at;
		bursts.add(b);
		if (tier >= 1) GunDisplays.muzzleLight(1f, 1f, 1f, tier == 2 ? 1f : 0.6f);
	}

	/**
	 * RocketLauncherFire's rings at the shoot point: the same two RageEffectWhite sprites under a 0.1 root, the grey one
	 * from scale 8 and the white one from 4, growing 1200 / 600 a second and fading 4 a second.
	 */
	public static void rocketRings(Vec3d at) {
		Burst b = new Burst();
		b.pos = at;
		float k = (float) com.ultracraft.client.Tune.get("rocket.ringStart");
		b.startGrey = 8 * k;
		b.startWhite = 4 * k;
		b.grow = (float) com.ultracraft.client.Tune.get("rocket.ringGrow");
		b.passes = Math.max(1, (int) Math.round(com.ultracraft.client.Tune.get("rocket.ringPasses")));
		b.fade = (float) com.ultracraft.client.Tune.get("rocket.ringFade");
		b.fadePow = (float) com.ultracraft.client.Tune.get("rocket.ringFadePow");
		bursts.add(b);
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != com.ultracraft.client.render.UkLayers.depthlessStage() || bursts.isEmpty()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (c.isPaused() || com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		// the camera's right and up in the world: the rings lie in its plane
		Vector3f right = new Vector3f(1, 0, 0).rotate(cam.getRotation()), up = new Vector3f(0, 1, 0).rotate(cam.getRotation());
		MatrixStack ms = com.ultracraft.client.render.UkLayers.worldStack(e);
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = buffers.getBuffer(UkLayers.solidSmooth(RING));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		float unit = (float) Damage.SCALE_BLOCKS;
		for (Iterator<Burst> it = bursts.iterator(); it.hasNext(); ) {
			Burst b = it.next();
			b.age += dt;
			float alpha = 1 - b.fade * b.age;
			if (alpha <= 0) { it.remove(); continue; }
			if (b.fadePow != 1) alpha = (float) Math.pow(alpha, b.fadePow);
			// RageEffectWhite is 64 px at 100 px a unit: 0.64 u, x the root's 0.1 x the growing scale
			for (Ring r : new Ring[] {new Ring(b.pos, 1200, 0.5f, b.startGrey), new Ring(b.pos, 600, 1f, b.startWhite)}) {
				float half = 0.64f * 0.1f * (r.start() + r.grow() * b.grow * b.age) / 2 * unit;
				for (int i = 0; i < b.passes; i++) quad(vc, m, r.pos(), right, up, half, r.shade(), alpha);
			}
		}
		ms.pop();
		buffers.draw();
	}

	private static void quad(VertexConsumer vc, Matrix4f m, Vec3d c, Vector3f right, Vector3f up, float h, float shade, float a) {
		float[][] k = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		for (float[] q : k) {
			float x = (float) c.x + (right.x * q[0] + up.x * q[1]) * h, y = (float) c.y + (right.y * q[0] + up.y * q[1]) * h, z = (float) c.z + (right.z * q[0] + up.z * q[1]) * h;
			vc.vertex(m, x, y, z).color(shade, shade, shade, a).texture(q[2], q[3]).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		}
	}
}
