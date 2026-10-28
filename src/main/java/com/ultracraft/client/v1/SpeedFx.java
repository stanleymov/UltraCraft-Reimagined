package com.ultracraft.client.v1;

import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * ULTRAKILL's movement streaks, from the prefabs NewMovement spawns:
 * <ul>
 * <li>DodgeParticle (on a dash): at player + dodgeDirection × 10 u, facing back at the player; a burst of 20 from a
 * 6 × 10 × 2 u box, 30–50 u/s toward and past the player, 0.5 s; only their trails show (additive, white,
 * alpha 0.49 → 0, × 0.35 grey).</li>
 * <li>SlideParticle (while sliding): the same idea kept 10 u ahead of the slide, a 15 × 5 × 5 u box, 20 per second
 * (+20 at the start), 40 u/s, world space, trails × 0.23 grey.</li>
 * <li>SlideScrape/Sparks (while sliding on the ground): kept at the player root (1.4 u under the eye) + the ground
 * velocity direction × 1 u and turned to face back every frame; 80 per second from a 60° cone, 10 u/s (root scale
 * 0.25 × Sparks scale 4), gravity 0.5 × −40, 0.5 s, size 0.1–0.5 shrinking to 0, yellow (1, 0.83, 0) trails
 * 0.5 × size wide, alpha 0.2 → 0, simulated in the emitter's frame.</li>
 * </ul>
 * The player origin is the body centre, 1.4 u under the camera.
 */
public final class SpeedFx {
	private static final class Streak {
		Vec3d birth, vel;
		float age, life;
		float width, r, g, b, a0;
		boolean dash;
		boolean flash;     // a slide spark drawn at full length at once (fx.sparkFlash), fading instead of travelling      // a dash line (its own camera safe zone)
		boolean local;     // in the SlideScrape emitter's frame (sparks: simulation space Local) instead of the world
		double gravity;
		/** a slam streak: V1's place when it was made (it is carried down with V1 by fx.fallLineFollow) */
		Vec3d follow;
		/** a slam line: drawn from its top down over `grow` s, then fading out as a whole (the user's view of the game) */
		boolean slam;
		float grow;
	}

	private static final List<Streak> streaks = new ArrayList<>();
	private static final Random rnd = new Random();
	private static long lastNanos;
	private static double slideAcc, sparkAcc, fallAcc;
	private static boolean wasSlamming;
	private static boolean wasSliding;
	private static Vec3d anchor = Vec3d.ZERO;
	/** The SlideScrape emitter: player root + velocity direction × 1 u, facing back (HandleSlideState, every frame). */
	private static Vec3d sparkEmitter = Vec3d.ZERO;
	private static Vec3d[] sparkFrame = frame(new Vec3d(0, 0, 1));

	static {
		Tune.def("fx.speedLines", 1, "0 = no dash / slide streaks");
		Tune.def("fx.fallLineBright", 0.9, "slam streak brightness (the game: its amber trail colour, additive; by eye)");
		Tune.def("fx.fallLineAlpha", 0.25, "slam line opacity (the game: 0.49 additive; by eye: the user wants them a bit see-through)");
		Tune.def("fx.fallLineFollow", 1.0, "slam lines carried down with V1, share of its drop (1 = each line stays where it is next to V1, the user's view of the game; 0 = left in the world)");
		Tune.def("fx.fallLineRate", 240, "slam lines a second (the game's FallParticle: 20; by eye: the user wants more)");
		Tune.def("fx.fallLineBurst", 120, "slam lines at once when the slam starts (the game: 20; by eye)");
		Tune.def("fx.lineLayer", 0, "shaders: speed / slam line layer (0 glow + depth, 1 glow no depth, 2 translucent + depth, 3 translucent no depth)");
		Tune.def("fx.waterLineLayer", 0, "shaders, under water: speed / slam line layer (see fx.lineLayer)");
		Tune.def("fx.waterStage", 0, "under water with shaders: when the speed / slam lines are drawn - 0 after particles (as above water), 1 after entities, 2 after weather, 3 after the level (NOT in world space there: the lines turned with the view), 4 after translucent blocks");
		Tune.def("fx.fallSafeRadius", 0.8, "slam lines thin out closer than this to the camera, blocks (by eye)");
		Tune.def("fx.fallSafeFade", 1.2, "slam lines come back to full over this distance past fx.fallSafeRadius, blocks (by eye)");
		Tune.def("fx.fallSafeMin", 0.35, "share of a slam line still showing right at the camera (by eye: a soft window, not a hole)");
		Tune.def("fx.fallLineGrow", 0.2, "slam line: seconds it takes to draw down to its full length before it fades (by eye)");
		Tune.def("fx.fallLineNearWater", 0.35, "under water: slam lines in a square round V1 this share of 15 u across (by eye: inside a shader pack's water fog)");
		Tune.def("fx.fallLineRing", 1.0, "above water: slam lines on a ring this far round V1, blocks (by eye: the user's 2 m circle)");
		Tune.def("fx.fallLineRingWidth", 0.2, "above water: how far a slam line may stray from that ring, blocks across (by eye)");
		Tune.def("fx.fallLineRound", 1, "1 = slam lines in a square all round V1 above water too, as under water (the user's call); 0 = the game's 15 x 5 u box ahead");
		Tune.def("fx.fallLineNear", 0.6, "slam streaks drawn this much closer round V1 (x the game's 15 x 5 u box and 2.087 u ahead; by eye: the user wants them a little closer)");
		Tune.def("fx.fallLineWidth", 0.1, "slam streak width, u (the game: 0.1)");
		Tune.def("fx.dashLineLife", 1.5, "dash line lifetime, s: longer flies further past you and fades slower (the game: 0.5)");
		Tune.def("fx.lineAlpha", 0.15, "white dash / slide line opacity at its newest end (fades to 0 at the tail)");
		Tune.def("fx.speedOriginY", 0, "height of the streak origin above the feet, u");
		Tune.def("fx.speedAdditive", 0, "1 = draw the streaks additive like the game (they vanish on bright surfaces)");
		Tune.def("fx.dashLineBright", 0.8, "dash streak brightness (the game: additive white × 0.35 grey, alpha 0.49)");
		Tune.def("fx.slideLineBright", 0.8, "slide streak brightness (× 0.23 grey in the game)");
		Tune.def("fx.sparkBright", 1.0, "slide spark brightness");
		Tune.def("fx.sparkAlpha", 0.3, "slide spark trail start alpha (the game: 0.2)");
		Tune.def("fx.lineWidth", 0.12, "dash streak width, u (particle size 0.2 at scale 2)");
		Tune.def("fx.slideLineWidth", 0.1, "slide streak width, u");
		Tune.def("fx.sparkWidth", 0.25, "slide spark trail width, u");
		Tune.def("fx.sparkRate", 80, "slide sparks per second (the game: 80)");
		Tune.def("fx.sparkSpeed", 25, "slide spark speed, u/s (the game: 10)");
		Tune.def("fx.sparkSpeedVar", 0.6, "each spark's speed is × 1 ± this at random: short and long trails");
		Tune.def("fx.sparkFlash", 1, "1 = slide sparks appear at full length past you at once and flash out (lightning); 0 = the game's travelling sparks");
		Tune.def("fx.sparkRateShort", 133, "short flash sparks per second");
		Tune.def("fx.sparkRateMed", 133, "medium flash sparks per second");
		Tune.def("fx.sparkRateLong", 67, "long flash sparks per second");
		Tune.def("fx.sparkFlashLife", 0.1, "how long one flash spark stays, s (± 30 %)");
		Tune.def("fx.sparkLenShort", 2, "short flash spark length, u");
		Tune.def("fx.sparkLenMed", 4, "medium flash spark length, u");
		Tune.def("fx.sparkLenLong", 8, "long flash spark length, u");
		Tune.def("fx.sparksEverywhere", 0, "1 = slide sparks on every surface (the game: only stone-like, glass, marble, metal, wet)");
		Tune.def("fx.sparkCameraClear", 0.35, "slide sparks are re-aimed so their path stays this far from the camera, blocks (0 = off)");
		Tune.def("fx.sparkBelowEye", 1.4, "slide sparks sit at the player root: this many u under the eye (Main Camera local y 1.4)");
		Tune.def("fx.dashSafeRadius", 0.75, "dash lines: no line closer than this to the camera, blocks (smaller so they fly past you)");
		Tune.def("fx.dashSafeFade", 0.75, "dash lines: fade-in distance past their safe radius, blocks");
		Tune.def("fx.safeRadius", 1.5, "no streak is drawn closer than this to the camera, blocks");
		Tune.def("fx.safeFade", 1.0, "streaks fade in over this distance past the safe radius, blocks");
	}

	private SpeedFx() {}

	/** NewMovement's transform.position: the feet (the slide sparks then sit at the bottom of the view, as in the game). */
	private static Vec3d origin(ClientPlayerEntity p) {
		return origin(p, 1f);
	}

	/** Same, at the frame's interpolated position (so player-relative sparks stay still against the camera). */
	private static Vec3d origin(ClientPlayerEntity p, float tickDelta) {
		return p.getLerpedPos(tickDelta).add(0, Tune.get("fx.speedOriginY") * Damage.SCALE_BLOCKS, 0);
	}

	/** Local frame of LookRotation(forward): x right, y up, z forward. */
	private static Vec3d[] frame(Vec3d forward) {
		Vec3d z = forward.normalize();
		Vec3d x = new Vec3d(0, 1, 0).crossProduct(z);
		if (x.lengthSquared() < 1e-6) x = new Vec3d(1, 0, 0);
		x = x.normalize();
		Vec3d y = z.crossProduct(x).normalize();
		return new Vec3d[] {x, y, z};
	}

	/** NewMovement.Dodge: one DodgeParticle burst. `dir` is the dash direction (world, horizontal). */
	public static void dash(ClientPlayerEntity p, Vec3d dir) {
		if (!Tune.on("fx.speedLines") || dir.lengthSquared() < 1e-6) return;
		double s = Damage.SCALE_BLOCKS;
		Vec3d center = origin(p).add(dir.normalize().multiply(10 * s));
		Vec3d[] f = frame(dir.multiply(-1)); // LookRotation(-dodgeDirection)
		for (int i = 0; i < 20; i++) {
			Streak k = new Streak();
			k.birth = center.add(f[0].multiply((rnd.nextDouble() - 0.5) * 6 * s)).add(f[1].multiply((rnd.nextDouble() - 0.5) * 10 * s)).add(f[2].multiply((rnd.nextDouble() - 0.5) * 2 * s));
			k.vel = f[2].multiply((30 + rnd.nextDouble() * 20) * s);
			k.life = (float) Tune.get("fx.dashLineLife");
			k.dash = true;
			k.width = (float) (Tune.get("fx.lineWidth") * s);
			float br = (float) Tune.get("fx.dashLineBright");
			k.r = k.g = k.b = br;
			k.a0 = (float) Tune.get("fx.lineAlpha");
			streaks.add(k);
		}
	}

	/** Per frame: the slide's streaks and sparks, and every streak drawn. */
	public static void onRenderLevel(RenderLevelStageEvent e) {
		// (under water with a shader pack the stage is live, fx.waterStage: the pack's water was laid over the lines)
		RenderLevelStageEvent.Stage want = RenderLevelStageEvent.Stage.AFTER_PARTICLES;
		if (com.ultracraft.client.v1.UnderwaterFx.submerged() && com.ultracraft.client.render.UkLayers.shadersOn()) {
			int st = (int) Tune.get("fx.waterStage");
			want = st == 1 ? RenderLevelStageEvent.Stage.AFTER_ENTITIES : st == 2 ? RenderLevelStageEvent.Stage.AFTER_WEATHER
				: st == 3 ? RenderLevelStageEvent.Stage.AFTER_LEVEL : st == 4 ? RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS : RenderLevelStageEvent.Stage.AFTER_PARTICLES;
		}
		if (e.getStage() != want) return;
		MinecraftClient c = MinecraftClient.getInstance();
		ClientPlayerEntity p = c.player;
		if (c.world == null || p == null) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (ViewModel.paused()) dt = 0;
		V1 v = V1.INSTANCE;
		double s = Damage.SCALE_BLOCKS;
		anchor = origin(p, e.getPartialTick());
		// FallParticle (the slam, NewMovement while heavyFall; parented to the player, world simulation): at the player +
		// (-0.24 right, 2.78 up, 2.087 ahead) u, turned to emit straight down; a 15 x 5 x 5 u box (shape 3 x 5 x 1 x the
		// emitter's 5 x 1 x 5: wide, ahead, tall), 20 at the start and 20 a second, 40 u/s down, 0.5 s; only the trails
		// show, amber (0.404, 0.282, 0) fading from 0.49. V1 slams down faster than they fall: they streak up past it.
		if (Tune.on("fx.speedLines") && v.heavyFall && dt > 0) {
			double yaw = Math.toRadians(p.getYaw());
			Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
			double near = Tune.get("fx.fallLineNear");
			// under water the lines come round V1, close in (by eye: a shader pack's water fog hid all but those just ahead)
			boolean wet = com.ultracraft.client.v1.UnderwaterFx.submerged();
			if (wet) near = Tune.get("fx.fallLineNearWater");
			// (above water too, by the user's call: all round V1 as under water, not only in the game's box ahead)
			boolean round = wet || Tune.on("fx.fallLineRound");
			Vec3d center = anchor.add(right.multiply(-0.24 * s)).add(0, 2.78 * s, 0).add(fwd.multiply(round ? 0 : 2.087 * s * near));
			int n = 0;
			if (!wasSlamming) n += (int) Tune.get("fx.fallLineBurst");
			fallAcc += Tune.get("fx.fallLineRate") * dt;
			while (fallAcc >= 1) { fallAcc -= 1; n++; }
			float br = (float) Tune.get("fx.fallLineBright");
			for (int i = 0; i < n; i++) {
				Streak k = new Streak();
				k.birth = center.add(right.multiply((rnd.nextDouble() - 0.5) * 15 * s * near)).add(fwd.multiply((rnd.nextDouble() - 0.5) * (round ? 15 : 5) * s * near)).add(0, (rnd.nextDouble() - 0.5) * 5 * s, 0);
				if (round && !wet) {
					// above water: one ring round V1, not the whole square (the user's call)
					double ang = rnd.nextDouble() * Math.PI * 2, rr = Tune.get("fx.fallLineRing") + (rnd.nextDouble() - 0.5) * Tune.get("fx.fallLineRingWidth");
					k.birth = center.add(Math.cos(ang) * rr, (rnd.nextDouble() - 0.5) * 5 * s, Math.sin(ang) * rr);
				}
				// 40 u/s down (the game), each line its own length (by eye: speed x 0.5-1.5)
				k.vel = new Vec3d(0, -40 * s * (0.5 + rnd.nextDouble()), 0);
				k.follow = anchor;
				k.slam = true;
				k.grow = (float) Tune.get("fx.fallLineGrow");
				k.life = 0.5f;
				k.width = (float) (Tune.get("fx.fallLineWidth") * s);
				// the trail colour (0.404, 0.282, 0), brought up to full brightness (the game adds it, x 2 x 0.25 tint)
				k.r = br; k.g = br * 0.698f; k.b = 0;
				k.a0 = (float) Tune.get("fx.fallLineAlpha");
				streaks.add(k);
			}
		}
		wasSlamming = v.heavyFall;
		if (Tune.on("fx.speedLines") && v.sliding && dt > 0) {
			Vec3d move = new Vec3d(v.vel.x, 0, v.vel.z);
			Vec3d dir = move.lengthSquared() > 1e-4 ? move.normalize() : (v.dodgeDirection.lengthSquared() > 1e-6 ? v.dodgeDirection.normalize() : p.getRotationVec(1f));
			Vec3d dodge = v.dodgeDirection.lengthSquared() > 1e-6 ? v.dodgeDirection.normalize() : dir;
			// SlideParticle: 10 u ahead along the velocity, emitting toward -dodgeDirection
			Vec3d center = anchor.add(dir.multiply(10 * s));
			Vec3d[] f = frame(dodge.multiply(-1));
			int n = 0;
			if (!wasSliding) n += 20;
			slideAcc += 20 * dt;
			while (slideAcc >= 1) { slideAcc -= 1; n++; }
			for (int i = 0; i < n; i++) {
				Streak k = new Streak();
				k.birth = center.add(f[0].multiply((rnd.nextDouble() - 0.5) * 15 * s)).add(f[1].multiply((rnd.nextDouble() - 0.5) * 5 * s)).add(f[2].multiply((rnd.nextDouble() - 0.5) * 5 * s));
				k.vel = f[2].multiply(40 * s);
				k.life = 0.5f;
				k.width = (float) (Tune.get("fx.slideLineWidth") * s);
				float br = (float) Tune.get("fx.slideLineBright");
				k.r = k.g = k.b = br;
				k.a0 = (float) Tune.get("fx.lineAlpha");
				streaks.add(k);
			}
			// SlideScrape (root scale 0.25) / Sparks (scale 4, Hierarchy scaling: × 1): at the player root + the ground
			// velocity direction × 1 u, facing back, moved and turned with the slide every frame (Local simulation).
			// 80 per second from a 60° cone, 10 u/s, 0.5 s, gravity modifier 0.5 × Physics.gravity −40.
			sparkEmitter = sparkRoot(p, e.getPartialTick()).add(dir.multiply(1 * s));
			sparkFrame = frame(dir.multiply(-1));
			if (v.onGround && (Surfaces.under(p).sparks() || Tune.on("fx.sparksEverywhere"))) {
				Vec3d camNow = e.getCamera().getPos();
				double clear = Tune.get("fx.sparkCameraClear");
				boolean flash = Tune.on("fx.sparkFlash");
				double rs = Tune.get("fx.sparkRateShort"), rm = Tune.get("fx.sparkRateMed"), rl = Tune.get("fx.sparkRateLong");
				sparkAcc += (flash ? rs + rm + rl : Tune.get("fx.sparkRate")) * dt;
				while (sparkAcc >= 1) {
					sparkAcc -= 1;
					Streak k = new Streak();
					k.local = true;
					k.birth = Vec3d.ZERO;
					double var = Tune.get("fx.sparkSpeedVar");
					double speed = Tune.get("fx.sparkSpeed") * s * (1 + var * (rnd.nextDouble() * 2 - 1));
					double len = 0;
					if (flash) {
						// one of three lengths (± 25 %), straight, gone in a blink
						double pick = rnd.nextDouble() * Math.max(1e-6, rs + rm + rl);
						int tier = pick < rs ? 0 : pick < rs + rm ? 1 : 2;
						len = Tune.get(tier == 0 ? "fx.sparkLenShort" : tier == 1 ? "fx.sparkLenMed" : "fx.sparkLenLong") * (0.75 + rnd.nextDouble() * 0.5);
					}
					// emitter-local direction (x right, y up, z forward = the cone axis), re-aimed until the spark's path
					// keeps fx.sparkCameraClear blocks from the camera: they go around the view, never through it
					double reach = flash ? len * s : speed * 0.5;
					Vec3d d = null;
					for (int attempt = 0; attempt < 16 && d == null; attempt++) {
						double ang = Math.toRadians(60) * Math.sqrt(rnd.nextDouble()), rot = rnd.nextDouble() * Math.PI * 2;
						Vec3d cd = new Vec3d(Math.sin(ang) * Math.cos(rot), Math.sin(ang) * Math.sin(rot), Math.cos(ang));
						Vec3d w = sparkFrame[0].multiply(cd.x).add(sparkFrame[1].multiply(cd.y)).add(sparkFrame[2].multiply(cd.z));
						if (segDist(camNow, sparkEmitter, sparkEmitter.add(w.multiply(reach))) >= clear) d = cd;
					}
					if (d == null) continue;
					k.vel = d.multiply(speed);
					k.gravity = 0.5 * 40 * s;
					k.life = 0.5f;
					if (flash) {
						k.flash = true;
						k.vel = d.multiply(len * s);
						k.gravity = 0;
						k.life = (float) (Tune.get("fx.sparkFlashLife") * (0.7 + rnd.nextDouble() * 0.6));
					}
					// trail width 0.5 × particle size (0.1–0.5 u); fx.sparkWidth is the widest (0.5 × 0.5)
					k.width = (float) (Tune.get("fx.sparkWidth") * s * (0.2 + rnd.nextDouble() * 0.8));
					float br = (float) Tune.get("fx.sparkBright");
					k.r = br; k.g = 0.83f * br; k.b = 0;
					k.a0 = (float) Tune.get("fx.sparkAlpha"); // trail alpha 0.2 → 0 in the game
					streaks.add(k);
				}
			}
		}
		wasSliding = v.sliding;
		if (streaks.isEmpty()) return;
		if (streaks.size() > 600) streaks.subList(0, streaks.size() - 600).clear();

		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		camRef = camPos;
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		// the game draws these additive; on Minecraft's bright skies and blocks additive white / yellow vanishes, so
		// they are alpha blended (fx.speedAdditive 1 = the game's additive look)
		// (under a shader pack they write depth: its underwater fog, worked out from the depth behind them, was laid over them)
		boolean shaders = com.ultracraft.client.render.UkLayers.shadersOn();
		int ll = shaders ? (int) Tune.get(com.ultracraft.client.v1.UnderwaterFx.submerged() ? "fx.waterLineLayer" : "fx.lineLayer") : 1;
		VertexConsumer vc = buffers.getBuffer(Tune.on("fx.speedAdditive") ? RenderLayer.getLightning()
			: ll == 0 ? com.ultracraft.client.render.UkLayers.emissiveDepth(WHITE) : ll == 2 ? RenderLayer.getEntityTranslucent(WHITE)
			: ll == 3 ? com.ultracraft.client.render.UkLayers.handTranslucent(WHITE) : RenderLayer.getEntityTranslucentEmissive(WHITE));
		additive = Tune.on("fx.speedAdditive");
		slideR = Tune.get("fx.safeRadius");
		slideF = Math.max(1e-3, Tune.get("fx.safeFade"));
		dashR = Tune.get("fx.dashSafeRadius");
		dashF = Math.max(1e-3, Tune.get("fx.dashSafeFade"));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		for (Iterator<Streak> it = streaks.iterator(); it.hasNext(); ) {
			Streak k = it.next();
			k.age += dt;
			if (k.age >= k.life) { it.remove(); continue; }
			float t = k.age;
			if (k.flash) {
				// full length from the first frame, fading out as a whole
				float a = k.a0 * Math.max(0, 1 - t / k.life);
				safeOn = false;
				Vec3d prev = at(k, 0);
				for (int i = 1; i <= 4; i++) {
					Vec3d pt = at(k, i / 4f);
					ribbon(vc, m, camPos, prev, pt, k.width, k.r, k.g, k.b, a, a);
					prev = pt;
				}
				continue;
			}
			if (k.slam) {
				// drawn down from its top until `grow`, then the whole line fades out where it is
				float g = Math.min(t, k.grow);
				float a = t <= k.grow ? k.a0 : k.a0 * Math.max(0, 1 - (t - k.grow) / Math.max(1e-3f, k.life - k.grow));
				// (a soft window in front of the eyes: they only thin out there, fx.fallSafeMin, by eye)
				safeOn = true;
				safeR = Tune.get("fx.fallSafeRadius");
				safeF = Math.max(1e-3, Tune.get("fx.fallSafeFade"));
				safeMin = (float) Tune.get("fx.fallSafeMin");
				Vec3d prev = at(k, 0);
				for (int i = 1; i <= 4; i++) {
					Vec3d pt = at(k, g * i / 4);
					ribbon(vc, m, camPos, prev, pt, k.width, k.r, k.g, k.b, a, a);
					prev = pt;
				}
				continue;
			}
			// the trail spans the particle's whole life: birth (tail) → now (head)
			int segs = k.gravity > 0 ? 4 : 8; // straight lines split so the camera safe zone fades them along their length
			float headA = k.a0, tailA = k.a0 * Math.max(0, 1 - t / k.life);
			Vec3d prev = at(k, 0);
			float prevA = tailA;
			// sparks: the size over life 1 → 0 narrows the whole trail (Size affects Width); no camera safe zone for
			// them (they belong at the bottom of the view)
			float w = k.local ? k.width * Math.max(0, 1 - t / k.life) : k.width;
			safeOn = !k.local;
			safeMin = 0;
			safeR = k.dash ? dashR : slideR;
			safeF = k.dash ? dashF : slideF;
			for (int i = 1; i <= segs; i++) {
				float u = t * i / segs;
				Vec3d pt = at(k, u);
				float a = tailA + (headA - tailA) * i / segs;
				ribbon(vc, m, camPos, prev, pt, w, k.r, k.g, k.b, prevA, a);
				prev = pt;
				prevA = a;
			}
		}
		ms.pop();
		buffers.draw();
	}

	/** A streak's point `u` seconds after its birth (local sparks: in the emitter's current frame, gravity world down). */
	private static Vec3d at(Streak k, float u) {
		Vec3d g = new Vec3d(0, -0.5 * k.gravity * u * u, 0);
		// a slam streak goes down with V1 by a share of V1's own drop since it was made (by eye: in the game they stay in
		// the world and a long slam leaves them far above the view)
		if (k.follow != null) g = g.add(anchor.subtract(k.follow).multiply(Tune.get("fx.fallLineFollow")));
		if (!k.local) return k.birth.add(k.vel.multiply(u)).add(g);
		Vec3d l = k.birth.add(k.vel.multiply(u));
		return sparkEmitter.add(sparkFrame[0].multiply(l.x)).add(sparkFrame[1].multiply(l.y)).add(sparkFrame[2].multiply(l.z)).add(g);
	}

	/** Distance from point c to the segment a–b. */
	private static double segDist(Vec3d c, Vec3d a, Vec3d b) {
		Vec3d ab = b.subtract(a);
		double l2 = ab.lengthSquared();
		double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, c.subtract(a).dotProduct(ab) / l2));
		return c.distanceTo(a.add(ab.multiply(t)));
	}

	/** NewMovement's transform.position (the root): fx.sparkBelowEye u under the standing eye, interpolated. */
	private static Vec3d sparkRoot(ClientPlayerEntity p, float tickDelta) {
		return p.getLerpedPos(tickDelta).add(0, p.getStandingEyeHeight() - Tune.get("fx.sparkBelowEye") * Damage.SCALE_BLOCKS, 0);
	}

	private static boolean safeOn = true;
	/** how much of a streak still shows inside the safe zone (0: none) */
	private static float safeMin;

	private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3d cam, Vec3d a, Vec3d b, float width, float r, float g, float bl, float aA, float aB) {
		Vec3d dir = b.subtract(a);
		if (dir.lengthSquared() < 1e-8) return;
		Vec3d side = dir.crossProduct(cam.subtract(a));
		if (side.lengthSquared() < 1e-8) return;
		side = side.normalize().multiply(width / 2);
		v(vc, m, a.add(side), r, g, bl, aA);
		v(vc, m, a.subtract(side), r, g, bl, aA);
		v(vc, m, b.subtract(side), r, g, bl, aB);
		v(vc, m, b.add(side), r, g, bl, aB);
		// both faces (the lightning layer culls)
		v(vc, m, b.add(side), r, g, bl, aB);
		v(vc, m, b.subtract(side), r, g, bl, aB);
		v(vc, m, a.subtract(side), r, g, bl, aA);
		v(vc, m, a.add(side), r, g, bl, aA);
	}

	private static final net.minecraft.util.Identifier WHITE = new net.minecraft.util.Identifier("minecraft", "textures/misc/white.png");
	private static boolean additive;
	private static Vec3d camRef = Vec3d.ZERO;

	private static double safeR, safeF, slideR, slideF, dashR, dashF;

	private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
		// camera safe zone: 0 inside fx.safeRadius, smooth fade-in over fx.safeFade (world positions against the camera)
		if (safeOn) {
			double d = p.subtract(camRef).length();
			double x = Math.max(0, Math.min(1, (d - safeR) / safeF));
			a *= safeMin + (1 - safeMin) * (float) (x * x * (3 - 2 * x));
		}
		if (additive) vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r * a, g * a, b * a, a).next();
		else vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(Math.min(1, r * 1.25f), Math.min(1, g * 1.25f), Math.min(1, b * 1.25f), Math.min(1, a))
			.texture(0.5f, 0.5f).overlay(net.minecraft.client.render.OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	public static void clear() {
		streaks.clear();
	}
}
