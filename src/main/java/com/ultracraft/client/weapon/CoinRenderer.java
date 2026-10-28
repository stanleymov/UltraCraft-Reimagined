package com.ultracraft.client.weapon;

import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.entity.CoinEntity;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * The Coin: the game's Coin.prefab `Model` mesh (texture coin01_2, scaled 0.1 × 0.1 × 0.01 u by its node), unlit,
 * spinning while it is in the air; it flashes white in the split-shot windows. Falls back to a gold disc until
 * the rig is available.
 */
public final class CoinRenderer extends EntityRenderer<CoinEntity> {
	private static final double SIZE = Tune.def("coin.size", 1.75, "multiplier on the coin model (1.75 = 0.2 u wide, measured against ULTRAKILL side by side)");
	private static final double SPIN = Tune.def("coin.spin", 0, "coin spin while airborne, deg/s (the game: none; the coin keeps the camera rotation it was thrown with)");
	private static final double TRAIL_TIME = Tune.def("coin.trailTime", 0.5, "coin TrailRenderer time, s (prefab: 0.5)");
	private static final double TRAIL_WIDTH = Tune.def("coin.trailWidth", 0.12, "coin trail width at the coin, u (prefab: 0.2, tapering to 0)");
	private static final double TRAIL_ALPHA = Tune.def("coin.trailAlpha", 1.0, "coin trail opacity at the coin, fading to 0 at the tail (prefab gradient: white 0.49 -> 0)");
	private static final double FLASH_TIME = Tune.def("coin.flashTime", 0.8, "coin spark fade-out time, s (the game removes it at 0.5 s, still half visible)");
	private static final double GLOW = Tune.def("coin.glow", 0.6, "extra light on the flying coin (an additive pass; 0 = the plain unlit coin)");
	private static final double FLASH_LAYER = Tune.def("coin.flashLayer", 1, "coin spark: 1 = added light only, 2 = blended yellow + a little added light, 0 = blended only");
	private static final double FLASH_GLOW = Tune.def("coin.flashGlow", 0.35, "coin spark: strength of the added light on top of the blended star (flashLayer 2)");
	private static final double FLASH_R = Tune.def("coin.flashR", 1, "coin spark tint, red");
	private static final double FLASH_G = Tune.def("coin.flashG", 0.85, "coin spark tint, green (lower = more yellow)");
	private static final double FLASH_B = Tune.def("coin.flashB", 0.15, "coin spark tint, blue (lower = more yellow)");
	private static final double FLASH_BRIGHT = Tune.def("coin.flashBright", 2, "coin spark strength: the sprite is drawn this many times over (1 = once)");
	private static final double FLASH_SIZE = Tune.def("coin.flashSize", 1.35, "multiplier on the CoinFlash sprite (0.32 u sprite x 4; the game: 1)");
	private static final double TRAIL_LAYER = Tune.def("coin.trailLayer", 1, "coin trail layer: 1 unlit + depth (bright, in front of shader clouds), 5 particle (lit by shaders: dimmer), 0 unlit, 2 additive, 3 lit translucent, 4 eyes");
	private static final double TRAIL_STAGE = Tune.def("coin.trailStage", 0, "coin trail/flash pass: 0 after particles, 1 after translucent blocks, 2 after entities, 3 after weather");
	private static final Identifier MUZZLE = new Identifier(com.ultracraft.UltraCraft.MOD_ID, "textures/uk/muzzleflash.png");
	private static final Identifier WHITE_TEX = new Identifier("minecraft", "textures/misc/white.png");
	private RigInstance rig;
	private boolean rigTried, logged;

	public CoinRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
	}

	private RigInstance rig() {
		if (!rigTried) {
			rigTried = true;
			UkRig r = UkAssets.rig("proj_coin");
			if (r != null) rig = new RigInstance("proj_coin", r);
			else com.ultracraft.UltraCraft.LOG.warn("[coin] proj_coin rig missing: drawing the fallback disc");
		}
		return rig;
	}

	@Override
	public void render(CoinEntity coin, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
		float t = coin.ageSeconds() + tickDelta * 0.05f;
		boolean flash = coin.isDoubled() && !coin.isShot();
		ms.push();
		RigInstance inst = rig();
		if (inst != null) {
			float s = (float) (Damage.SCALE_BLOCKS * Tune.get("coin.size"));
			// the Model disc lies in the prefab's XZ plane (0.1 × 0.1 × 0.01): stand it up to face sideways, spin it on its axis while airborne
			boolean landed = coin.isLanded();
			if (!landed) {
				// Instantiate(coin, ..., camObj.rotation): the disc faces where the thrower looked and keeps that
				ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-coin.getYaw()));
				ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(coin.getPitch()));
				if (!coin.isShot()) ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(t * (float) Tune.get("coin.spin")));
				ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
			} else {
				// a spent coin lies flat where it came to rest
				ms.translate(0, 0.01, 0);
				ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((coin.getId() * 73) % 360));
			}
			ms.scale(s, s, s);
			// CoinUnlit: coin01_2 at _Color white, no tint
			inst.tintR = 1f;
			inst.tintG = 1f;
			inst.tintB = 1f;
			if (!logged) { logged = true; inst.debugBounds = true; com.ultracraft.UltraCraft.LOG.info("[coin] rendering coin {} at {} shot={} size={}", coin.getId(), coin.getPos(), coin.isShot(), s); }
			inst.poseAtOrigin();
			// GetDeleted swaps in uselessMaterial "Coin" (coin01_3, lit) for the unlit CoinUnlit
			inst.setAllMeshTexture(landed ? "coin01_3" : null);
			// brighter while it flies (coin.glow): the rig's additive light pass, a warm white
			inst.lightAdd = landed ? 0f : (float) Tune.get("coin.glow");
			inst.lightR = 1f; inst.lightG = 0.95f; inst.lightB = 0.8f;
			inst.render(ms, buffers, landed ? light : 0xF000F0, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
			ms.pop();
			super.render(coin, yaw, tickDelta, ms, buffers, light);
			return;
		}
		// fallback: a glowing gold disc
		float r = (float) (0.1 * Damage.SCALE_BLOCKS * Tune.get("coin.size")) * 2;
		if (!coin.isShot()) ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 40f));
		ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
		float g = flash ? 1f : 0.85f, b = flash ? 0.9f : 0.1f;
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getLightning());
		Matrix4f m = ms.peek().getPositionMatrix();
		int n = 12;
		for (int i = 0; i < n; i++) {
			double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
			for (int side = 0; side < 2; side++) {
				float x0 = (float) Math.cos(side == 0 ? a0 : a1) * r, y0 = (float) Math.sin(side == 0 ? a0 : a1) * r;
				float x1 = (float) Math.cos(side == 0 ? a1 : a0) * r, y1 = (float) Math.sin(side == 0 ? a1 : a0) * r;
				float z = side == 0 ? 0.005f : -0.005f;
				vc.vertex(m, 0, 0, z).color(1f, g, b, 1f).next();
				vc.vertex(m, x0, y0, z).color(1f, g, b, 1f).next();
				vc.vertex(m, x1, y1, z).color(1f, g, b, 1f).next();
				vc.vertex(m, x1, y1, z).color(1f, g, b, 1f).next();
			}
		}
		ms.pop();
		super.render(coin, yaw, tickDelta, ms, buffers, light);
	}

	/**
	 * The coin's TrailRenderer: time 0.5 s, min vertex distance 0.1 u, width 0.2 u tapering to 0 over the trail,
	 * colour white with alpha 0.49 -> 0 (material Additive; alpha blended here so it shows on bright blocks).
	 */
	/**
	 * The trail and CoinFlash draw at AFTER_PARTICLES with a layer that writes depth: shader packs (Complementary)
	 * composite their clouds wherever the depth buffer still holds sky, so the old emissive, depth-less layer was
	 * painted over by clouds. (AFTER_WEATHER is not shown under a shader pack.)
	 */
	public static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent e) {
		int st = (int) Tune.get("coin.trailStage");
		var want = st == 1 ? net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
			: st == 2 ? net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES
			: st == 3 ? net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_WEATHER
			: net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES;
		if (e.getStage() != want) return;
		var client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.world == null) return;
		float td = e.getPartialTick();
		net.minecraft.util.math.Vec3d cam = e.getCamera().getPos();
		VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
		MatrixStack ms = e.getPoseStack();
		boolean any = false;
		for (net.minecraft.entity.Entity ent : client.world.getEntities()) {
			if (!(ent instanceof CoinEntity coin)) continue;
			if (coin.isLanded() && !coin.landedSeen) {
				coin.landedSeen = true;
				coin.trail.clear(); // GetDeleted destroys the TrailRenderer
				// CoinBreak: "319630__vehicle__coinflip-01" at volume 0.5
				com.ultracraft.client.UKSound.at(coin.getPos(), "uk.weapons.revolver.319630__vehicle__coinflip-01", 0.5f, 1f);
			}
			net.minecraft.util.math.Vec3d pos = coin.getLerpedPos(td);
			ms.push();
			ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
			if ((int) Tune.get("coin.trailLayer") == 5) {
				if (!coin.isLanded() && coin.trailParticle == null) {
					CoinTrailParticle tp = new CoinTrailParticle(client.world, coin);
					coin.trailParticle = tp;
					client.particleManager.addParticle(tp);
				}
			} else if (!coin.isLanded()) drawTrail(coin, td, ms, buffers);
			drawFlash(coin, coin.ageSeconds() + td * 0.05f, ms, buffers, e.getCamera().getRotation());
			ms.pop();
			any = true;
		}
		if (any) buffers.draw();
	}

	/** Receives trail vertices in world coordinates (quads, both faces). */
	public interface TrailSink {
		void v(double x, double y, double z, float a);
	}

	private static void drawTrail(CoinEntity coin, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		net.minecraft.util.math.Vec3d pos = coin.getLerpedPos(tickDelta);
		net.minecraft.util.math.Vec3d cam = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		VertexConsumer vc = buffers.getBuffer(layer(WHITE_TEX));
		Matrix4f m = ms.peek().getPositionMatrix();
		trailGeometry(coin, tickDelta, cam, (x, y, z, a) -> tv(vc, m, new net.minecraft.util.math.Vec3d(x - pos.x, y - pos.y, z - pos.z), a));
	}

	/**
	 * The coin's TrailRenderer: time 0.5 s, min vertex distance 0.1 u, width tapering to 0 over the trail, colour
	 * white with alpha 0.49 -> 0. Records this frame's point and emits the ribbon as one flat camera-facing band.
	 */
	public static void trailGeometry(CoinEntity coin, float tickDelta, net.minecraft.util.math.Vec3d cam, TrailSink sink) {
		double now = System.nanoTime() / 1e9;
		double s = Damage.SCALE_BLOCKS;
		net.minecraft.util.math.Vec3d pos = coin.getLerpedPos(tickDelta);
		java.util.ArrayDeque<double[]> tr = coin.trail;
		double[] last = tr.peekLast();
		if (coin.isShot() || coin.isLanded()) {
			// a shot coin stops; the trail runs out
		} else if (last == null || Math.sqrt(sq(last[0] - pos.x) + sq(last[1] - pos.y) + sq(last[2] - pos.z)) >= 0.1 * s) {
			tr.addLast(new double[] {pos.x, pos.y, pos.z, now});
		}
		double life = TRAIL_TIME;
		while (!tr.isEmpty() && now - tr.peekFirst()[3] > life) tr.removeFirst();
		if (tr.isEmpty()) return;
		java.util.List<double[]> pts = new java.util.ArrayList<>();
		pts.add(new double[] {pos.x, pos.y, pos.z, now});
		java.util.Iterator<double[]> it = tr.descendingIterator();
		while (it.hasNext()) pts.add(it.next());
		int n = pts.size();
		if (n < 2) return;
		float w0 = (float) (TRAIL_WIDTH * s), a0 = (float) TRAIL_ALPHA;
		double[] head = pts.get(0), tail = pts.get(n - 1), mid = pts.get(n / 2);
		net.minecraft.util.math.Vec3d along = new net.minecraft.util.math.Vec3d(head[0] - tail[0], head[1] - tail[1], head[2] - tail[2]);
		net.minecraft.util.math.Vec3d sd = along.crossProduct(cam.subtract(mid[0], mid[1], mid[2]));
		if (sd.lengthSquared() < 1e-12) sd = along.crossProduct(new net.minecraft.util.math.Vec3d(0, 1, 0));
		if (sd.lengthSquared() < 1e-12) sd = new net.minecraft.util.math.Vec3d(1, 0, 0);
		sd = sd.normalize();
		for (int i = 0; i + 1 < n; i++) {
			double[] p0 = pts.get(i), p1 = pts.get(i + 1);
			float k0 = (float) Math.max(0, 1 - (now - p0[3]) / life), k1 = (float) Math.max(0, 1 - (now - p1[3]) / life);
			double h0 = w0 * k0 / 2, h1 = w0 * k1 / 2;
			float A0 = a0 * k0, A1 = a0 * k1;
			double ax = p0[0] + sd.x * h0, ay = p0[1] + sd.y * h0, az = p0[2] + sd.z * h0;
			double bx = p0[0] - sd.x * h0, by = p0[1] - sd.y * h0, bz = p0[2] - sd.z * h0;
			double cx = p1[0] - sd.x * h1, cy = p1[1] - sd.y * h1, cz = p1[2] - sd.z * h1;
			double dx = p1[0] + sd.x * h1, dy = p1[1] + sd.y * h1, dz = p1[2] + sd.z * h1;
			sink.v(ax, ay, az, A0); sink.v(bx, by, bz, A0); sink.v(cx, cy, cz, A1); sink.v(dx, dy, dz, A1);
			sink.v(dx, dy, dz, A1); sink.v(cx, cy, cz, A1); sink.v(bx, by, bz, A0); sink.v(ax, ay, az, A0);
		}
	}

	/** The lightning layer takes position + colour only (additive: colour pre-multiplied by alpha). */
	private static boolean posColor;

	private static RenderLayer layer(Identifier tex) {
		posColor = (int) Tune.get("coin.trailLayer") == 2;
		return switch ((int) Tune.get("coin.trailLayer")) {
			case 0 -> RenderLayer.getEntityTranslucentEmissive(tex);
			case 2 -> RenderLayer.getLightning();
			case 3 -> RenderLayer.getEntityTranslucent(tex);
			case 4 -> RenderLayer.getEyes(tex);
			default -> com.ultracraft.client.render.UkLayers.emissiveDepth(tex);
		};
	}

	private static double sq(double v) {
		return v * v;
	}

	private static void tv(VertexConsumer vc, Matrix4f m, net.minecraft.util.math.Vec3d p, float a) {
		if (posColor) { vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(a, a, a, a).next(); return; }
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(1f, 1f, 1f, a).texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	/**
	 * Coin.TripleTime (0.35 s after the throw): CoinFlash on the coin, a camera-facing `muzzleflash` sprite (32 px at
	 * 100 ppu, scale 2 x 2, random spin) that SpriteController shrinks and fades at 1 per second; RemoveOnTime 0.5 s.
	 * Its AudioSource plays "coinflip" at volume 0.5, pitch 1.35 +- 0.05 (RandomPitch).
	 */
	private static void drawFlash(CoinEntity coin, float t, MatrixStack ms, VertexConsumerProvider buffers, org.joml.Quaternionf camRot) {
		float ft = t - 0.35f;
		float life = (float) Tune.get("coin.flashTime");
		if (ft < 0 || ft > life || coin.isShot() || coin.isLanded()) return;
		if (!coin.flashPlayed) {
			coin.flashPlayed = true;
			com.ultracraft.client.UKSound.at(coin.getPos(), "uk.weapons.revolver.coinflip", 0.5f, com.ultracraft.client.UKSound.range(1.3f, 1.4f));
		}
		// the game shrinks / fades it at 1 per second and removes it at 0.5 s (cut off at half strength); here it
		// fades all the way out over coin.flashTime
		float k = 1 - ft / life;
		float half = (float) (0.32 * 4 * Damage.SCALE_BLOCKS * FLASH_SIZE) * k / 2;
		ms.push();
		ms.multiply(camRot);
		ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((coin.getId() * 137) % 360));
		Matrix4f m = ms.peek().getPositionMatrix();
		boolean particleMode = (int) Tune.get("coin.trailLayer") == 5;
		// coin.flashLayer 2: a blended yellow star (its colour on any background) with a little added light on top
		// (brighter, but weak enough not to wash out to white on a bright sky); 1 = added light only, 0 = blended only
		int mode = (int) Tune.get("coin.flashLayer");
		float bright = (float) Tune.get("coin.flashBright");
		if (mode != 1) {
			VertexConsumer vc = buffers.getBuffer(particleMode ? RenderLayer.getEntityTranslucentEmissive(MUZZLE) : layer(MUZZLE));
			if (particleMode) posColor = false;
			// coin.flashBright: the sprite again on top (2 = twice), so it reads stronger
			for (int pass = 0; pass < Math.ceil(bright); pass++) {
				float a = k * Math.min(1f, bright - pass);
				flashTint = true;
				fv(vc, m, -half, -half, 0, 1, a); fv(vc, m, half, -half, 1, 1, a); fv(vc, m, half, half, 1, 0, a); fv(vc, m, -half, half, 0, 0, a);
				flashTint = false;
			}
		}
		if (mode != 0) {
			boolean pc = posColor;
			posColor = false;
			VertexConsumer gv = buffers.getBuffer(com.ultracraft.client.render.UkLayers.glow(MUZZLE));
			float a = k * (mode == 1 ? 1f : (float) Tune.get("coin.flashGlow"));
			flashTint = true;
			fv(gv, m, -half, -half, 0, 1, a); fv(gv, m, half, -half, 1, 1, a); fv(gv, m, half, half, 1, 0, a); fv(gv, m, -half, half, 0, 0, a);
			flashTint = false;
			posColor = pc;
		}
		ms.pop();
	}

	private static boolean flashTint;

	private static void fv(VertexConsumer vc, Matrix4f m, float x, float y, float u, float v, float a) {
		// the spark in bright yellow (coin.flashR/G/B), so it reads yellow without a shader pack too
		float r = flashTint ? (float) Tune.get("coin.flashR") : 1f, g = flashTint ? (float) Tune.get("coin.flashG") : 1f, b = flashTint ? (float) Tune.get("coin.flashB") : 1f;
		if (posColor) { vc.vertex(m, x, y, 0).color(r * a, g * a, b * a, a).next(); return; }
		vc.vertex(m, x, y, 0).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1).next();
	}

	@Override
	public Identifier getTexture(CoinEntity entity) {
		return new Identifier("minecraft", "textures/misc/white.png");
	}
}
