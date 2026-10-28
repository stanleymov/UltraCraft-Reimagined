package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.ViewModel;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * BulletSpark (a shotgun pellet hitting a surface): a burst of 5 'spark' billboards from a 0.1 u sphere, speed 1-10 u/s,
 * gravity 1, size 0.25 u, life 0.5 s, colour white to (1, 0.85, 0) while the alpha falls 1 to 0. Its AudioSource
 * plays "Ricochet" at volume 0.1.
 */
public final class SparkFx {
	private static final Identifier SPRITE = new Identifier(UltraCraft.MOD_ID, "textures/uk/spark.png");

	private static final class Spark {
		Vec3d pos, vel;
		float age, life, size;
	}

	private static final List<Spark> sparks = new ArrayList<>();
	private static final Random rnd = new Random();
	private static long lastNanos;

	static {
		Tune.def("fx.bulletSparkCount", 5, "sparks per pellet hit (BulletSpark: a burst of 5)");
		Tune.def("fx.bulletSparkSize", 0.25, "spark size, u (BulletSpark startSize 0.25)");
	}

	private SparkFx() {}

	/** A BulletSpark at `pos`. */
	public static void burst(Vec3d pos) {
		int n = (int) Tune.get("fx.bulletSparkCount");
		double s = Damage.SCALE_BLOCKS;
		for (int i = 0; i < n; i++) {
			Spark p = new Spark();
			// a random direction (sphere shape), speed 1..10 u/s
			double z = rnd.nextDouble() * 2 - 1, a = rnd.nextDouble() * Math.PI * 2, rr = Math.sqrt(1 - z * z);
			Vec3d dir = new Vec3d(rr * Math.cos(a), z, rr * Math.sin(a));
			p.pos = pos.add(dir.multiply(0.1 * s * rnd.nextDouble()));
			p.vel = dir.multiply((1 + rnd.nextDouble() * 9) * s);
			p.life = 0.5f;
			p.size = (float) (Tune.get("fx.bulletSparkSize") * s);
			sparks.add(p);
		}
		if (sparks.size() > 600) sparks.subList(0, sparks.size() - 600).clear();
	}

	public static void clear() {
		sparks.clear();
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null || sparks.isEmpty()) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (ViewModel.paused()) dt = 0;
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.emissiveDepth(SPRITE));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		org.joml.Vector3f r = new org.joml.Vector3f(cam.getHorizontalPlane()).cross(cam.getVerticalPlane()).normalize();
		org.joml.Vector3f u = cam.getVerticalPlane();
		double g = 9.81 * Damage.SCALE_BLOCKS;
		for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
			Spark p = it.next();
			if (dt > 0) {
				p.age += dt;
				p.vel = p.vel.add(0, -g * dt, 0);
				p.pos = p.pos.add(p.vel.multiply(dt));
			}
			if (p.age >= p.life) { it.remove(); continue; }
			float k = p.age / p.life;
			float cr = 1f, cg = 1f - 0.15f * k, cb = 1f - k, a = 1f - k;
			float h = p.size / 2;
			v(vc, m, p.pos.x - r.x * h - u.x * h, p.pos.y - r.y * h - u.y * h, p.pos.z - r.z * h - u.z * h, 0, 1, cr, cg, cb, a);
			v(vc, m, p.pos.x + r.x * h - u.x * h, p.pos.y + r.y * h - u.y * h, p.pos.z + r.z * h - u.z * h, 1, 1, cr, cg, cb, a);
			v(vc, m, p.pos.x + r.x * h + u.x * h, p.pos.y + r.y * h + u.y * h, p.pos.z + r.z * h + u.z * h, 1, 0, cr, cg, cb, a);
			v(vc, m, p.pos.x - r.x * h + u.x * h, p.pos.y - r.y * h + u.y * h, p.pos.z - r.z * h + u.z * h, 0, 0, cr, cg, cb, a);
		}
		ms.pop();
		buffers.draw();
	}

	private static void v(VertexConsumer vc, Matrix4f m, double x, double y, double z, float u, float w, float r, float g, float b, float a) {
		vc.vertex(m, (float) x, (float) y, (float) z).color(r, g, b, a).texture(u, w).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
