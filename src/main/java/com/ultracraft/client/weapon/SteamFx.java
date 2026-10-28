package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
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
 * Shotgun.ReleaseHeat steam in the world (the game's Smoke2 particle systems simulate in world space, so the cloud
 * stays where the sinks vented it while the gun reloads): a 1 s burst of smokesprite billboards, speed 2–5 u/s in a
 * 25° cone, size 0.3–0.5 u, life 0.5 s, white fading out.
 */
public final class SteamFx {
	private static final Identifier SPRITE = new Identifier(UltraCraft.MOD_ID, "textures/uk/smokesprite.png");

	private static final class Puff {
		Vec3d pos, vel;
		float age, life, size, rot;
		/** Colour, drag per second and upward drift (u/s²): steam's by default. */
		float grey = 0.93f, drag = 2.5f, rise = 0.4f, alpha = -1;
	}

	private static final List<Puff> puffs = new ArrayList<>();
	private static final Random rnd = new Random();
	private static long lastNanos;

	static {
		Tune.def("fx.steamCount", 40, "steam puffs per heat sink (Smoke2: rate 100 → 0 over 1 s)");
		Tune.def("fx.steamSpeed", 3.5, "steam speed, u/s (Smoke2 startSpeed 2–5)");
		Tune.def("fx.steamSize", 0.4, "steam start size, u (Smoke2 startSize 0.3–0.5)");
		Tune.def("fx.steamLife", 0.6, "steam life, s (Smoke2 startLifetime 0.5)");
		Tune.def("fx.steamAlpha", 0.55, "steam opacity");
		Tune.def("fx.metalSmoke", 50, "magnet break smoke puffs (BreakParticleMetal burst 50)");
		Tune.def("fx.metalSmokeSize", 0.35, "magnet break smoke size x the game's 5-15 u (the puffs also grow over their life here)");
		Tune.def("fx.metalSmokeAlpha", 0.35, "magnet break smoke opacity");
		Tune.def("fx.metalDrag", 1.5, "magnet break smoke slowing per second");
	}

	private SteamFx() {}

	/** A vent at `pos` blowing along `dir` (world units; the cone is 25°). */
	public static void burst(Vec3d pos, Vec3d dir) {
		if (!Tune.on("fx.blood") && false) return;
		int n = (int) Tune.get("fx.steamCount");
		Vec3d d = dir.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : dir.normalize();
		Vec3d t = Math.abs(d.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d a = d.crossProduct(t).normalize(), b = d.crossProduct(a).normalize();
		double s = Damage.SCALE_BLOCKS;
		for (int i = 0; i < n; i++) {
			Puff p = new Puff();
			double ang = Math.toRadians(25) * Math.sqrt(rnd.nextDouble()), rot = rnd.nextDouble() * Math.PI * 2;
			Vec3d v = d.multiply(Math.cos(ang)).add(a.multiply(Math.sin(ang) * Math.cos(rot))).add(b.multiply(Math.sin(ang) * Math.sin(rot)));
			double speed = Tune.get("fx.steamSpeed") * (0.6 + rnd.nextDouble() * 0.8) * s;
			p.pos = pos.add(a.multiply((rnd.nextDouble() - 0.5) * 0.1 * s)).add(b.multiply((rnd.nextDouble() - 0.5) * 0.1 * s));
			p.vel = v.multiply(speed);
			// the emission rate ramps 100 → 0 over the first second: more puffs early
			p.age = -(float) (Math.pow(rnd.nextDouble(), 2) * 1.0);
			p.life = (float) (Tune.get("fx.steamLife") * (0.8 + rnd.nextDouble() * 0.4));
			p.size = (float) (Tune.get("fx.steamSize") * (0.75 + rnd.nextDouble() * 0.5) * s);
			p.rot = rnd.nextFloat() * 6.2832f;
			puffs.add(p);
		}
		if (puffs.size() > 400) puffs.subList(0, puffs.size() - 400).clear();
	}

	/** A few puffs right now at `pos` along `dir` (steam trailing something that moves, e.g. the flung cores). */
	public static void trail(Vec3d pos, Vec3d dir, int n) {
		Vec3d d = dir.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : dir.normalize();
		Vec3d t = Math.abs(d.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d a = d.crossProduct(t).normalize(), b = d.crossProduct(a).normalize();
		double s = Damage.SCALE_BLOCKS;
		for (int i = 0; i < n; i++) {
			Puff p = new Puff();
			double ang = Math.toRadians(25) * Math.sqrt(rnd.nextDouble()), rot = rnd.nextDouble() * Math.PI * 2;
			Vec3d v = d.multiply(Math.cos(ang)).add(a.multiply(Math.sin(ang) * Math.cos(rot))).add(b.multiply(Math.sin(ang) * Math.sin(rot)));
			double speed = Tune.get("fx.steamSpeed") * (0.3 + rnd.nextDouble() * 0.4) * s;
			p.pos = pos.add(a.multiply((rnd.nextDouble() - 0.5) * 0.06 * s)).add(b.multiply((rnd.nextDouble() - 0.5) * 0.06 * s));
			p.vel = v.multiply(speed);
			p.age = -(float) (rnd.nextDouble() * 0.05);
			p.life = (float) (Tune.get("fx.steamLife") * (0.8 + rnd.nextDouble() * 0.4));
			p.size = (float) (Tune.get("fx.steamSize") * (0.6 + rnd.nextDouble() * 0.4) * s);
			p.rot = rnd.nextFloat() * 6.2832f;
			puffs.add(p);
		}
		if (puffs.size() > 400) puffs.subList(0, puffs.size() - 400).clear();
	}

	/**
	 * BreakParticleMetal's Smoke2 cloud: 50 at once from a 1 u sphere, 5–25 u/s, size 5–15 u, 5–6 s, grey 0.5, no
	 * gravity (the slowing and fading are by eye: fx.metalDrag, fx.metalSmokeAlpha).
	 */
	public static void smoke(Vec3d pos) {
		double s = Damage.SCALE_BLOCKS;
		int n = (int) Tune.get("fx.metalSmoke");
		for (int i = 0; i < n; i++) {
			Puff p = new Puff();
			Vec3d d = new Vec3d(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
			p.pos = pos.add(d.multiply(rnd.nextDouble() * s));
			p.vel = d.multiply((5 + rnd.nextDouble() * 20) * s);
			p.life = 5 + rnd.nextFloat();
			p.size = (float) ((5 + rnd.nextDouble() * 10) * s * Tune.get("fx.metalSmokeSize"));
			p.rot = rnd.nextFloat() * 6.2832f;
			p.grey = 0.5f;
			p.drag = (float) Tune.get("fx.metalDrag");
			p.rise = 0;
			p.alpha = (float) Tune.get("fx.metalSmokeAlpha");
			puffs.add(p);
		}
		if (puffs.size() > 400) puffs.subList(0, puffs.size() - 400).clear();
	}

	public static void clear() {
		puffs.clear();
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != com.ultracraft.client.render.UkLayers.depthlessStage()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null || puffs.isEmpty()) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (ViewModel.paused()) dt = 0;
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		MatrixStack ms = com.ultracraft.client.render.UkLayers.worldStack(e);
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentEmissive(SPRITE));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		org.joml.Vector3f r = new org.joml.Vector3f(cam.getHorizontalPlane()).cross(cam.getVerticalPlane()).normalize();
		org.joml.Vector3f u = cam.getVerticalPlane();
		float alpha = (float) Tune.get("fx.steamAlpha");
		for (Iterator<Puff> it = puffs.iterator(); it.hasNext(); ) {
			Puff p = it.next();
			if (dt > 0) {
				p.age += dt;
				if (p.age > 0) {
					p.pos = p.pos.add(p.vel.multiply(dt));
					p.vel = p.vel.multiply(Math.max(0, 1 - p.drag * dt)); // drag: the puffs slow and hang
					p.vel = p.vel.add(0, p.rise * dt, 0); // and drift up like steam
				}
			}
			if (p.age >= p.life) { it.remove(); continue; }
			if (p.age < 0) continue;
			float k = p.age / p.life;
			float size = p.size * (1 + 1.5f * k);
			float a = (p.alpha >= 0 ? p.alpha : alpha) * (1 - k * k);
			float cs = (float) Math.cos(p.rot), sn = (float) Math.sin(p.rot);
			float h = size / 2;
			// rotated camera-facing quad
			float rx = r.x * cs + u.x * sn, ry = r.y * cs + u.y * sn, rz = r.z * cs + u.z * sn;
			float ux = u.x * cs - r.x * sn, uy = u.y * cs - r.y * sn, uz = u.z * cs - r.z * sn;
			v(vc, m, p.pos.x - rx * h - ux * h, p.pos.y - ry * h - uy * h, p.pos.z - rz * h - uz * h, 0, 1, a, p.grey);
			v(vc, m, p.pos.x + rx * h - ux * h, p.pos.y + ry * h - uy * h, p.pos.z + rz * h - uz * h, 1, 1, a, p.grey);
			v(vc, m, p.pos.x + rx * h + ux * h, p.pos.y + ry * h + uy * h, p.pos.z + rz * h + uz * h, 1, 0, a, p.grey);
			v(vc, m, p.pos.x - rx * h + ux * h, p.pos.y - ry * h + uy * h, p.pos.z - rz * h + uz * h, 0, 0, a, p.grey);
		}
		ms.pop();
		buffers.draw();
	}

	private static void v(VertexConsumer vc, Matrix4f m, double x, double y, double z, float u, float w, float a, float grey) {
		vc.vertex(m, (float) x, (float) y, (float) z).color(grey, grey, grey, a).texture(u, w).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
