package com.ultracraft.client.weapon;

import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.Map;

/**
 * Draws ULTRAKILL's projectiles with their exported meshes (proj_* rigs). The prefab's +z forward becomes
 * the entity's flight direction; heated nails glow orange, cannonballs tumble.
 */
public final class ProjectileRenderer extends EntityRenderer<UKProjectileEntity> {
	private final Map<ProjectileKind, RigInstance> rigs = new EnumMap<>(ProjectileKind.class);
	private static final double SIZE = Tune.def("proj.scale", 1.0, "multiplier on projectile mesh size");
	private static final double FLIP = Tune.def("proj.flip", 0, "1 = turn the projectile meshes 180° (if they fly backwards)");
	private static long sawLog;
	private static final double CHAINSAW_SCALE = Tune.def("proj.chainsawScale", 0.35, "extra scale on the launched chainsaw (the prefab's units come out large)");

	public ProjectileRenderer(EntityRendererFactory.Context ctx) {
		super(ctx);
	}

	private RigInstance rig(ProjectileKind k) {
		return rigs.computeIfAbsent(k, kk -> {
			UkRig r = UkAssets.rig(kk.rig);
			if (r == null) return null;
			RigInstance ri = new RigInstance(kk.rig, r);
			// (the prefab roots sit far out in their scenes: nails, the magnet and the cannonball (its root at -67, -6, 373)
			// are drawn about their own root)
			ri.rootAtOrigin = kk.nail() || kk == ProjectileKind.MAGNET || kk == ProjectileKind.CANNONBALL;
			return ri;
		});
	}

	/** Nails already given their start at the barrel. */
	private static final java.util.Set<Integer> NAIL_STARTED = new java.util.HashSet<>();
	/** Nails a magnet has held (drawn from the server's copy from then on). */
	private static final java.util.Set<Integer> HELD = new java.util.HashSet<>();

	@Override
	public void render(UKProjectileEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
		ProjectileKind k = e.kind();
		// our own nails are drawn by NailGhosts from the gun; the server's copy shows once it sticks in something
		MinecraftClient mc = MinecraftClient.getInstance();
		// (once a magnet has held it, NailGhosts has let it go and this copy is the one drawn)
		if (k.nail() && e.isMagnetized()) { HELD.add(e.getId()); if (HELD.size() > 1024) HELD.clear(); }
		if (k.nail() && !e.isStuck() && !HELD.contains(e.getId()) && NailGhosts.enabled() && mc.player != null && e.ownerId() == mc.player.getId()) return;
		// a nail we just fired reaches us a tick later, already metres out: its first frames run from the barrel it left,
		// so the nail itself comes out of the gun and the trail follows it
		if (k.nail() && NAIL_STARTED.add(e.getId())) {
			if (NAIL_STARTED.size() > 512) NAIL_STARTED.clear();
			double[] m = ArsenalClient.nailMuzzleNear(e.getPos());
			if (m != null && !HELD.contains(e.getId())) {
				e.prevX = e.lastRenderX = m[0];
				e.prevY = e.lastRenderY = m[1];
				e.prevZ = e.lastRenderZ = m[2];
			}
		}
		RigInstance inst = rig(k);
		if (k == ProjectileKind.CANNONBALL && Tune.on("debug.weapons") && System.nanoTime() - cbLog > 250_000_000L) {
			cbLog = System.nanoTime();
			com.ultracraft.UltraCraft.LOG.info("[cannonball] render {} at {} rig {}", e.getId(), e.getPos(), inst != null);
		}
		if (inst == null) return;
		// a held nail goes round its magnet several times a second: drawn where its steps put it now, not on straight
		// lines between the server's ticks
		Vec3d at = e.getLerpedPos(tickDelta), nv = e.getVelocity();
		Vec3d[] held = k.nail() && e.isMagnetized() ? e.predictHeld(tickDelta) : null;
		// (a saw on a magnet's ring: along the arc between ticks)
		Vec3d ring = k.sawblade() ? e.ringPos(tickDelta) : null;
		if (ring != null) held = new Vec3d[] {ring, nv};
		else if (k.sawblade() && e.ringExitOffset(tickDelta) != null) held = new Vec3d[] {at.add(e.ringExitOffset(tickDelta)), nv};
		// the rocket V1 rides is drawn from V1's own (smooth) place, so the two never drift apart between ticks
		com.ultracraft.client.v1.V1 v1 = com.ultracraft.client.v1.V1.INSTANCE;
		if (k == ProjectileKind.ROCKET && v1.ridingRocket == e.getId() && v1.rideOffset != null && mc.player != null)
			held = new Vec3d[] {mc.player.getLerpedPos(tickDelta).subtract(v1.rideOffset), nv};
		// (a gasoline droplet right at the barrel is not drawn: the spray flash covers it there in the game; by eye)
		if (k == ProjectileKind.GASOLINE && e.getLerpedPos(tickDelta).distanceTo(mc.gameRenderer.getCamera().getPos()) < Tune.get("gas.dropHide")) return;
		ms.push();
		if (held != null) {
			ms.translate(held[0].x - at.x, held[0].y - at.y, held[0].z - at.z);
			at = held[0];
			nv = held[1];
		}
		ms.push();
		// (shortest turn between ticks: a plain lerp spun the model the long way round across +-180)
		float ey = net.minecraft.util.math.MathHelper.lerpAngleDegrees(tickDelta, e.prevYaw, e.getYaw()), ep = e.prevPitch + (e.getPitch() - e.prevPitch) * tickDelta;
		// (the chainsaw keeps the server's heading along its velocity: blade first, out, back and after a punch)
		// model forward (Unity +z, mirrored to -z in RigInstance) → entity heading
		// the nail model points the other way from the other projectiles: nail first along its flight
		if (k.nail() && nv.lengthSquared() > 1e-8) {
			// along its velocity, tip first (stuck or resting it keeps the yaw / pitch it came in with)
			NailGhosts.aimNail(ms, nv, nv, 1f);
		} else {
			boolean flip = Tune.on("proj.flip") ^ (k.nail() && Tune.on("proj.nailFlip"));
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(ey + (flip ? 0 : 180)));
			ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(flip ? -ep : ep));
		}
		if (k == ProjectileKind.CANNONBALL) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((e.age + tickDelta * 0.05f) * 720f));
		// the nail mesh's long axis is up in its own space: lay it along the flight
		if (k.nail() && nv.lengthSquared() <= 1e-8) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) Tune.get("proj.nailPitch")));
		float s = (float) (Damage.SCALE_BLOCKS * Tune.get("proj.scale"));
		ms.scale(s, s, s);
		// the exported rig's root node carries the prefab scale already
		if (k == ProjectileKind.NAIL_HEATED) { inst.tintR = 1f; inst.tintG = 0.55f; inst.tintB = 0.2f; }
		else if (k == ProjectileKind.MAGNET) { inst.tintR = inst.tintG = inst.tintB = (float) Tune.get("magnet.tint"); } // (live magnet.tint)
		else { inst.tintR = inst.tintG = inst.tintB = 1f; }
		if (k == ProjectileKind.CHAINSAW && Tune.on("debug.weapons") && System.nanoTime() - sawLog > 250_000_000L) {
			sawLog = System.nanoTime();
			com.ultracraft.UltraCraft.LOG.info("[weapons] chainsaw render id={} at {} stuck={} boosted={} meshes={}", e.getId(), e.getPos(), e.isStuck(), e.isBoosted(), inst.rig.meshes.stream().map(mm -> mm.name).toList());
			inst.debugBounds = true;
		}
		if (k == ProjectileKind.CHAINSAW) {
			// ChainsawProjectile: only the saw and its blade (the prefab's inactive cube stays off); the body in the
			// variation red (ColorBlindGet), heated at 3 hits; caught (Chainsaw.TouchPlayer) the model is off
			inst.hiddenMeshes.clear();
			boolean caught = sawCaught(e, tickDelta);
			// right at the camera (the throw spawns it 0.5 u ahead; a punch sends it out from 1 u ahead) the model would fill
			// half the view for a frame: the game's is already well out by its first rendered frame
			boolean atCamera = e.getLerpedPos(tickDelta).distanceTo(MinecraftClient.getInstance().gameRenderer.getCamera().getPos()) < Tune.get("proj.sawNearHide") * Damage.SCALE_BLOCKS;
			for (int i = 0; i < inst.rig.meshes.size(); i++) if (caught || atCamera || !inst.rig.meshes.get(i).name.startsWith("Chainsaw")) inst.hiddenMeshes.add(i);
			inst.setMeshTexture("Chainsaw", "t_chainsaw_red");
			if (e.isBoosted()) { inst.tintR = 1f; inst.tintG = 0.55f; inst.tintB = 0.2f; }
			float cs = (float) Tune.get("proj.chainsawScale");
			ms.scale(cs, cs, cs);
		}
		if (k == ProjectileKind.PELLET || k == ProjectileKind.GASOLINE) {
			// Shotgun Projectile / gasoline: a glowing primitive sphere; pellets are 0.2 u, warm yellow light
			// (gasoline: the Gasoline material's dark grey with its purple fresnel, live gas.drop*)
			boolean gas = k == ProjectileKind.GASOLINE;
			inst.tintR = gas ? (float) Tune.get("gas.dropR") : 1f; inst.tintG = gas ? (float) Tune.get("gas.dropG") : 0.75f; inst.tintB = gas ? (float) Tune.get("gas.dropB") : 0f;
			if (e.isBoosted()) { inst.tintG = 0.35f; inst.tintB = 0f; ms.scale(2f, 2f, 2f); } // boosted: (1, 0.35, 0), four times the collider
			// the ChargeEffect child is inactive in the prefab (a dark ring around every pellet when drawn): boosted only
			inst.hiddenMeshes.clear();
			if (!e.isBoosted()) for (int i = 0; i < inst.rig.meshes.size(); i++) if (inst.rig.meshes.get(i).name.equals("ChargeEffect")) inst.hiddenMeshes.add(i);
			light = 0xF000F0;
		}
		// a rocket frozen 1 s levels up: its Model's ChangeMaterials puts Projectile 2 on the stripes (red Bonus -> cyan)
		if (k == ProjectileKind.ROCKET) {
			if (e.isLevelledUp()) inst.materialTexture.put("AltarUnlitRed", "skull2_compressed_2");
			else inst.materialTexture.remove("AltarUnlitRed");
		}
		inst.pose(new Matrix4f());
		// (lit by the world like the rockets: the spike is a plain lit material, not a glow)
		// (a frozen rocket culls back faces: fired from the eye, V1 sits inside the hull and it is not seen until V1 leaves it,
		// the flame, trail and freeze ring still showing; the one V1 rides is always drawn whole - the user's check of the game)
		boolean cullRocket = k == ProjectileKind.ROCKET && e.isFrozen() && com.ultracraft.client.v1.V1.INSTANCE.ridingRocket != e.getId();
		inst.render(ms, buffers, k == ProjectileKind.ROCKET || k == ProjectileKind.CANNONBALL || k == ProjectileKind.MAGNET ? light : 0xF000F0, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, cullRocket);
		if (k.sawblade()) {
			// NailAlt: the blade quad spins (Spin script) and a ProBuilder cylinder hub (texture "sawblade 1") sits in the middle
			ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((e.age + tickDelta * 0.05f) * (float) Tune.get("proj.sawSpin")));
			hub(ms, buffers, (float) Tune.get("proj.sawHubRadius"), (float) Tune.get("proj.sawHubThick"));
		}
		ms.pop();
		if (k == ProjectileKind.ROCKET && e.isFrozen()) freezeEffect(e, ms, buffers);
		if (k == ProjectileKind.ROCKET) {
			// Rocket_0's TrailRenderer: 0.15 s, 1 u wide, orange (1, 0.5, 0) at 0.39 fading out, on the legacy Particles/Additive
			// shader (2 x _TintColor 0.25 x colour: (0.5, 0.25, 0) at 0.78, the same light added); the colour live (by eye: the
			// user sees it yellower in the game). No depth: it opened a window into the water under a shader pack.
			if (!e.isFrozen()) colorTrail(e, at, ms, buffers, 0.15, 1, (float) Tune.get("proj.rocketTrailR"), (float) Tune.get("proj.rocketTrailG"), (float) Tune.get("proj.rocketTrailB"), (float) Tune.get("proj.rocketTrailA"), false);
			rocketFlame(e, tickDelta, ms, buffers);
		}
		if (k == ProjectileKind.CORE) grenadeFlare(e, ms, buffers);
		if (k.sawblade()) trail(e, tickDelta, ms, buffers);
		if (k == ProjectileKind.CHAINSAW) {
			sawTrail(e, tickDelta, ms, buffers);
			sawStar(e, tickDelta, ms, buffers);
		}
		if (k == ProjectileKind.PELLET && Tune.on("proj.pelletTrail")) pelletTrail(e, tickDelta, ms, buffers);
		// nail TrailRenderers: Nail white, NailFodder (the Attractor's) blue, both 0.25 s 0.2 u alpha 0.49; NailHeated orange 1 s 0.4 u 0.69
		if (k == ProjectileKind.NAIL) colorTrail(e, at, ms, buffers, 0.25, 0.2, 1f, 1f, 1f, 0.49f);
		else if (k == ProjectileKind.NAIL_FODDER) colorTrail(e, at, ms, buffers, 0.25, 0.2, 0.251f, 0.906f, 1f, 0.49f);
		else if (k == ProjectileKind.NAIL_HEATED) colorTrail(e, at, ms, buffers, 1.0, 0.4, 1f, 0.592f, 0f, 0.686f);
		if (k == ProjectileKind.SCREWDRIVER) {
			screwTrail(e, tickDelta, ms, buffers);
		}
		ms.pop();
		super.render(e, yaw, tickDelta, ms, buffers, light);
	}

	private static final double SAW_SPIN = Tune.def("proj.sawSpin", 1440, "sawblade spin, degrees per second");
	private static final double HUB_R = Tune.def("proj.sawHubRadius", 0.35, "sawblade hub radius (u; the prefab's ProBuilder cylinder is built at runtime, size by eye)");
	private static final double HUB_T = Tune.def("proj.sawHubThick", 0.14, "sawblade hub thickness (u)");
	private static final double TRAIL_W = Tune.def("proj.trailWidth", 2, "sawblade TrailRenderer width (prefab: 2 u)");
	private static final double TRAIL_T = Tune.def("proj.trailTime", 0.5, "sawblade TrailRenderer time (prefab: 0.5 s)");
	private static final double TRAIL_DEPTH = Tune.def("proj.trailDepth", 0, "1 = sawblade trails write depth (shader clouds then stay behind them; 0 = the game's _ZWrite 0, no comb where trails overlap)");
	private static final double TRAIL_LATE_DEPTH = Tune.def("proj.trailLateDepth", 1, "1 = the sawblade trails' depth is laid down after every trail's colour (AFTER_PARTICLES): clouds stay behind them with and without a shader pack, and no trail hides another (no comb)");
	private static final double TRAIL_DEPTH_BACK = Tune.def("proj.trailDepthBack", 1.5, "the sawblade trails' depth this many blocks further from the camera than the trail (by eye: at the trail itself a shader pack drew it spotty; further back, a trail more than this behind another is still hidden by it)");
	private static final double TRAIL_DEPTH_STAGE = Tune.def("proj.trailDepthStage", 0, "shaders: when the sawblade trails' depth goes in - 0 after the entities (before the pack lays its clouds in), 1 after the particles, 2 after the translucent blocks");
	/** This frame's sawblade trail quads in the world (4 x (x, y, z, alpha)), for their depth pass. */
	private static final java.util.List<float[]> TRAIL_DEPTH_QUADS = new java.util.ArrayList<>();

	/**
	 * The sawblade trails' depth, after all of their colour was drawn with the entities: written with the colour, the
	 * trails hid each other (a comb); not written at all, the clouds (drawn after the particles, or composited by a
	 * shader pack where depth still says sky) were laid over them. Faint parts (alpha under 0.1) are left out, as the
	 * cutout program drops them.
	 */
	public static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent e) {
		// (under a shader pack after the particles was too late: the pack had laid its clouds in by then; right after the
		// entities - still after every trail's colour - it is in time. proj.trailDepthStage: 0 after the entities, 1 after
		// the particles, 2 after the translucent blocks)
		boolean shadersOn = com.ultracraft.client.render.UkLayers.shadersOn();
		int st = shadersOn ? (int) Tune.get("proj.trailDepthStage") : 1;
		var want = st == 0 ? net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES
			: st == 2 ? net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
			: net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES;
		if (e.getStage() != want) return;
		if (TRAIL_DEPTH_QUADS.isEmpty()) return;
		if (TRAIL_DEPTH_QUADS.size() > 20000) { TRAIL_DEPTH_QUADS.clear(); return; }
		MinecraftClient c = MinecraftClient.getInstance();
		Vec3d cam = e.getCamera().getPos();
		MatrixStack ms = com.ultracraft.client.render.UkLayers.worldStack(e);
		net.minecraft.client.render.VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		// (under a shader pack the depth-only layer did not keep its clouds off: there the depth goes in with the glow
		// layer that writes it - the nail ghosts' - in black, which adds nothing to the picture)
		boolean shaders = com.ultracraft.client.render.UkLayers.shadersOn();
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(shaders ? com.ultracraft.client.render.UkLayers.glowDepth(WHITE) : com.ultracraft.client.render.UkLayers.depthOnly(WHITE));
		float shade = shaders ? 0f : 1f;
		Matrix4f m = ms.peek().getPositionMatrix();
		// (the depth sits proj.trailDepthBack blocks further from the camera than the trail: under a shader pack the trail's
		// colour came after it at the same depth and the two fought pixel by pixel - a spotty trail; pushed back, the colour
		// always draws and the clouds far behind are still kept off)
		double back = Tune.get("proj.trailDepthBack");
		for (float[] q4 : TRAIL_DEPTH_QUADS)
			for (int i = 0; i < 4; i++) {
				double dx = q4[i * 4] - cam.x, dy = q4[i * 4 + 1] - cam.y, dz = q4[i * 4 + 2] - cam.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double k = d < 1e-4 ? 1 : (d + back) / d;
				vc.vertex(m, (float) (dx * k), (float) (dy * k), (float) (dz * k)).color(shade, shade, shade, q4[i * 4 + 3])
					.texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
			}
		TRAIL_DEPTH_QUADS.clear();
		buffers.draw();
	}

	private static final double TRAIL_B = Tune.def("proj.trailBright", 1, "sawblade trail strength (Additive, alpha 0.49 at the saw; 1 = the prefab)");
	private static final Identifier HUB_TEX = new Identifier(com.ultracraft.UltraCraft.MOD_ID, "textures/uk/sawblade_1.png");
	private static final Identifier WHITE = new Identifier("minecraft", "textures/misc/white.png");
	private static final java.util.Map<Integer, java.util.ArrayDeque<double[]>> TRAILS = new java.util.HashMap<>();

	/** A short textured cylinder along the local z axis (the blade's spin axis), two discs + rim. */
	private static void hub(MatrixStack ms, VertexConsumerProvider buffers, float r, float t) {
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(net.minecraft.client.render.RenderLayer.getEntityCutoutNoCull(HUB_TEX));
		Matrix4f m = ms.peek().getPositionMatrix();
		int n = 16;
		for (int side = -1; side <= 1; side += 2) {
			float z = side * t / 2;
			for (int i = 0; i < n; i++) {
				double a0 = i * 2 * Math.PI / n, a1 = (i + 1) * 2 * Math.PI / n;
				float x0 = (float) Math.cos(a0) * r, y0 = (float) Math.sin(a0) * r, x1 = (float) Math.cos(a1) * r, y1 = (float) Math.sin(a1) * r;
				put(vc, m, 0, 0, z, 0.5f, 0.5f, 0, 0, side);
				put(vc, m, x0, y0, z, 0.5f + x0 / r / 2, 0.5f + y0 / r / 2, 0, 0, side);
				put(vc, m, x1, y1, z, 0.5f + x1 / r / 2, 0.5f + y1 / r / 2, 0, 0, side);
				put(vc, m, x1, y1, z, 0.5f + x1 / r / 2, 0.5f + y1 / r / 2, 0, 0, side);
			}
		}
		for (int i = 0; i < n; i++) {
			double a0 = i * 2 * Math.PI / n, a1 = (i + 1) * 2 * Math.PI / n;
			float x0 = (float) Math.cos(a0) * r, y0 = (float) Math.sin(a0) * r, x1 = (float) Math.cos(a1) * r, y1 = (float) Math.sin(a1) * r;
			put(vc, m, x0, y0, -t / 2, 0.5f, 0.5f, x0 / r, y0 / r, 0);
			put(vc, m, x1, y1, -t / 2, 0.5f, 0.5f, x1 / r, y1 / r, 0);
			put(vc, m, x1, y1, t / 2, 0.5f, 0.5f, x1 / r, y1 / r, 0);
			put(vc, m, x0, y0, t / 2, 0.5f, 0.5f, x0 / r, y0 / r, 0);
		}
	}

	private static void put(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		vc.vertex(m, x, y, z).color(255, 255, 255, 255).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(nx, ny, nz).next();
	}

	/**
	 * TrailRenderer: the last `proj.trailTime` seconds of positions as a camera-facing ribbon, width 2 u at the saw to
	 * nothing, Additive, (0.5 grey, alpha 0.49) at the saw to clear white at the end: only a faint streak.
	 */
	private void trail(UKProjectileEntity e, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		double now = com.ultracraft.client.uk.ViewModel.gameSeconds();
		Vec3d lerped = new Vec3d(net.minecraft.util.math.MathHelper.lerp(tickDelta, e.prevX, e.getX()), net.minecraft.util.math.MathHelper.lerp(tickDelta, e.prevY, e.getY()), net.minecraft.util.math.MathHelper.lerp(tickDelta, e.prevZ, e.getZ()));
		Vec3d ring = e.ringPos(tickDelta), exit = ring == null ? e.ringExitOffset(tickDelta) : null;
		Vec3d pos = ring != null ? ring : exit != null ? lerped.add(exit) : lerped;
		// (render() calls this with the matrices already moved to where the saw is drawn (pos): moving them again here
		// doubled the move, by however far the frame was into its tick, and the trails swung between a ring and a flower)
		java.util.ArrayDeque<double[]> q = TRAILS.computeIfAbsent(e.getId(), k -> new java.util.ArrayDeque<>());
		double life = Tune.get("proj.trailTime");
		// a saw steered round magnets: the points between the last one and now come from its path, every 0.004 s (half a
		// physics step), so the trail lies on the path the saw took; frame-rate samples 1.7 blocks apart drew a kinked,
		// wobbling ring
		double show = e.ringShow(tickDelta);
		if (q.isEmpty() || q.peekLast()[3] < now - 1.0 / 60) {
			double[] last = q.peekLast();
			if (!Double.isNaN(show) && last != null && last.length > 4 && !Double.isNaN(last[4]) && show > last[4] && show - last[4] < 10) {
				double s0 = last[4], t0 = last[3], stepTicks = 0.004 / 0.05;
				for (double s = s0 + stepTicks; s < show - stepTicks / 2; s += stepTicks) {
					Vec3d at = e.ringSmoothAt(s);
					if (at != null) q.addLast(new double[] {at.x, at.y, at.z, t0 + (now - t0) * (s - s0) / (show - s0), s});
				}
			}
			q.addLast(new double[] {pos.x, pos.y, pos.z, now, show});
		}
		while (!q.isEmpty() && q.peekFirst()[3] < now - life) q.pollFirst();
		if (TRAILS.size() > 256) TRAILS.entrySet().removeIf(en -> en.getValue().isEmpty() || en.getValue().peekLast()[3] < now - 2);
		if (q.size() < 2) return;
		// the Additive trail material writes no depth (_ZWrite 0): written, the trails of the saws on one ring hid
		// pieces of each other and of themselves, a comb of bright and dark bars along the band (proj.trailDepth 1 writes
		// it again, for shader-pack clouds over a trail against the sky)
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(Tune.on("proj.trailDepth") ? com.ultracraft.client.render.UkLayers.glowDepth(WHITE) : com.ultracraft.client.render.UkLayers.glow(WHITE));
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		boolean lateDepth = !Tune.on("proj.trailDepth") && Tune.on("proj.trailLateDepth");
		// the saw's own TrailRenderer: NailAlt grey 0.5 (the green and red launchers' saws: a see-through white streak, as the
		// user saw in the game), NailAltFodder (0, 0.88, 1), NailAltHeated (1, 0.59, 0) 3 u wide
		ProjectileKind tk = e.kind();
		float[] head = tk == ProjectileKind.SAWBLADE_FODDER ? new float[] {0f, 0.88f, 1f} : tk == ProjectileKind.SAWBLADE_HEATED ? new float[] {1f, 0.59f, 0f} : new float[] {0.5f, 0.5f, 0.5f};
		float w = (float) (Tune.get("proj.trailWidth") * (tk == ProjectileKind.SAWBLADE_HEATED ? 1.5 : 1) * Damage.SCALE_BLOCKS / 2);
		ms.push();
		Matrix4f m = ms.peek().getPositionMatrix();
		double[] prev = null;
		Vec3d prevSide = null;
		int i = 0, n = q.size();
		for (double[] pt : q) {
			Vec3d here = new Vec3d(pt[0], pt[1], pt[2]).subtract(pos);
			if (prev != null) {
				Vec3d there = new Vec3d(prev[0], prev[1], prev[2]).subtract(pos);
				Vec3d dir = here.subtract(there);
				Vec3d toCam = cam.subtract(pos).subtract(here);
				Vec3d side = dir.crossProduct(toCam);
				side = side.lengthSquared() < 1e-9 ? (prevSide != null ? prevSide : new Vec3d(0, 1, 0)) : side.normalize();
				// (the same side as the last segment: a short one turned the other way twisted its quad into a bow-tie)
				if (prevSide != null && side.dotProduct(prevSide) < 0) side = side.multiply(-1);
				// t 1 at the saw, 0 at the end of the trail
				float t0 = (float) Math.max(0, 1 - (now - prev[3]) / life), t1 = (float) Math.max(0, 1 - (now - pt[3]) / life);
				float w0 = w * t0, w1 = w * t1;
				Vec3d s0 = (prevSide != null ? prevSide : side).multiply(w0), s1 = side.multiply(w1);
				tvc(vc, m, there.add(s0), t0, head); tvc(vc, m, there.subtract(s0), t0, head); tvc(vc, m, here.subtract(s1), t1, head); tvc(vc, m, here.add(s1), t1, head);
				if (lateDepth) {
					float a0 = 0.49f * t0 * (float) Tune.get("proj.trailBright"), a1 = 0.49f * t1 * (float) Tune.get("proj.trailBright");
					Vec3d[] corners = {there.add(s0), there.subtract(s0), here.subtract(s1), here.add(s1)};
					float[] q4 = new float[16];
					for (int c = 0; c < 4; c++) {
						Vec3d wpt = pos.add(corners[c]);
						q4[c * 4] = (float) wpt.x; q4[c * 4 + 1] = (float) wpt.y; q4[c * 4 + 2] = (float) wpt.z; q4[c * 4 + 3] = c < 2 ? a0 : a1;
					}
					TRAIL_DEPTH_QUADS.add(q4);
				}
				prevSide = side;
			}
			prev = pt;
			i++;
		}
		ms.pop();
	}

	/**
	 * Shotgun Projectile's TrailRenderer: time 0.1 s, width 0.504 u at the pellet tapering to 0, colour (1, 0.773, 0)
	 * fading from opaque to clear; unlit and in front of shader clouds.
	 */
	private void pelletTrail(UKProjectileEntity e, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		double now = com.ultracraft.client.uk.ViewModel.gameSeconds();
		Vec3d pos = new Vec3d(net.minecraft.util.math.MathHelper.lerp(tickDelta, e.prevX, e.getX()), net.minecraft.util.math.MathHelper.lerp(tickDelta, e.prevY, e.getY()), net.minecraft.util.math.MathHelper.lerp(tickDelta, e.prevZ, e.getZ()));
		java.util.ArrayDeque<double[]> q = TRAILS.computeIfAbsent(e.getId(), k -> new java.util.ArrayDeque<>());
		double life = Tune.get("proj.pelletTrailTime");
		if (q.isEmpty() || q.peekLast()[3] < now - 1.0 / 120) q.addLast(new double[] {pos.x, pos.y, pos.z, now});
		while (!q.isEmpty() && q.peekFirst()[3] < now - life) q.pollFirst();
		if (TRAILS.size() > 256) TRAILS.entrySet().removeIf(en -> en.getValue().isEmpty() || en.getValue().peekLast()[3] < now - 2);
		if (q.isEmpty()) return;
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.emissiveDepth(WHITE));
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		float w = (float) (Tune.get("proj.pelletTrailWidth") * Damage.SCALE_BLOCKS / 2);
		float cr = 1f, cg = 0.773f, cb = 0f;
		Matrix4f m = ms.peek().getPositionMatrix();
		// the points, newest (the pellet itself) first; t = how far along the trail's life each one is
		java.util.List<double[]> pts = new java.util.ArrayList<>();
		pts.add(new double[] {pos.x, pos.y, pos.z, now});
		// the pellets leave from the camera: the oldest points (still at the eye) would sweep a wedge across the view,
		// so the trail stops proj.pelletTrailNear u short of the camera
		double near = Tune.get("proj.pelletTrailNear") * Damage.SCALE_BLOCKS;
		java.util.Iterator<double[]> di = q.descendingIterator();
		while (di.hasNext()) {
			double[] pt = di.next();
			if (cam.squaredDistanceTo(pt[0], pt[1], pt[2]) < near * near) break;
			pts.add(pt);
		}
		if (cam.squaredDistanceTo(pos) < near * near) return;
		Vec3d prevSide = null;
		for (int i = 1; i < pts.size(); i++) {
			double[] a = pts.get(i - 1), b = pts.get(i);
			Vec3d pa = new Vec3d(a[0], a[1], a[2]).subtract(pos), pb = new Vec3d(b[0], b[1], b[2]).subtract(pos);
			Vec3d dir = pa.subtract(pb);
			if (dir.lengthSquared() < 1e-10) continue;
			Vec3d side = dir.crossProduct(cam.subtract(pos).subtract(pa));
			side = side.lengthSquared() < 1e-12 ? (prevSide != null ? prevSide : new Vec3d(0, 1, 0)) : side.normalize();
			float ta = (float) Math.min(1, (now - a[3]) / life), tb = (float) Math.min(1, (now - b[3]) / life);
			Vec3d sa = (prevSide != null ? prevSide : side).multiply(w * (1 - ta)), sb = side.multiply(w * (1 - tb));
			ptv(vc, m, pa.add(sa), cr, cg, cb, 1 - ta); ptv(vc, m, pa.subtract(sa), cr, cg, cb, 1 - ta);
			ptv(vc, m, pb.subtract(sb), cr, cg, cb, 1 - tb); ptv(vc, m, pb.add(sb), cr, cg, cb, 1 - tb);
			prevSide = side;
		}
	}

	private static void ptv(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	private static final double PELLET_TRAIL = Tune.def("proj.pelletTrail", 1, "1 = shotgun pellets draw their TrailRenderer");
	private static final double PELLET_TRAIL_T = Tune.def("proj.pelletTrailTime", 0.1, "pellet trail time, s (prefab: 0.1)");
	private static final double PELLET_TRAIL_NEAR = Tune.def("proj.pelletTrailNear", 2, "no pellet trail within this many u of the camera (they spawn at the eye)");
	private static final double PELLET_TRAIL_W = Tune.def("proj.pelletTrailWidth", 0.504, "pellet trail width at the pellet, u (prefab: 0.504 tapering to 0)");

	/** Caught by its thrower (Chainsaw.TouchPlayer: within 1 u of the gun): the model is off, the star small. */
	private static boolean sawCaught(UKProjectileEntity e, float tickDelta) {
		net.minecraft.entity.Entity owner = MinecraftClient.getInstance().player; // (the owner is not synced; the saws are the local player's, as ChainsawFx assumes)
		if (owner == null) return false;
		// (within 1 u of the body centre, the saw's anchor, after 0.66 s; a little early here so the model never fills the view)
		Vec3d centre = owner.getLerpedPos(tickDelta).add(0, owner.getHeight() / 2, 0);
		return e.getLerpedPos(tickDelta).distanceTo(centre) < Tune.get("proj.sawCatchDist") * Damage.SCALE_BLOCKS && e.age > 13;
	}

	/**
	 * ChainsawProjectile's "New Sprite": the red-orange 'muzzleflashnailgun' star (0.32 u at scale 100 under the 0.1
	 * root: 3.2 u) spinning 3600 deg/s on the saw; 20 instead of 100 once caught. Drawn facing the camera, unlit.
	 */
	private static void sawStar(UKProjectileEntity e, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		boolean caught = sawCaught(e, tickDelta);
		float half = (float) (0.32 * 10 * (caught ? 0.2 : 1) * Tune.get("proj.sawStarSize") * Damage.SCALE_BLOCKS / 2);
		float spin = (float) Math.toRadians(((e.age + tickDelta) * 0.05 * Tune.get("proj.sawStarSpin")) % 360.0);
		net.minecraft.client.render.Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
		org.joml.Vector3f r = new org.joml.Vector3f(cam.getHorizontalPlane()).cross(cam.getVerticalPlane()).normalize(), u = new org.joml.Vector3f(cam.getVerticalPlane());
		float cs = (float) Math.cos(spin), sn = (float) Math.sin(spin);
		org.joml.Vector3f a = new org.joml.Vector3f(r).mul(cs).add(new org.joml.Vector3f(u).mul(sn)), b = new org.joml.Vector3f(u).mul(cs).sub(new org.joml.Vector3f(r).mul(sn));
		// solid (cut-out, full bright) like the muzzle flashes, not see-through
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(net.minecraft.client.render.RenderLayer.getEntityCutoutNoCull(STAR));
		Matrix4f m = ms.peek().getPositionMatrix();
		float[][] q = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		for (float[] c : q) {
			float x = (a.x * c[0] + b.x * c[1]) * half, y = (a.y * c[0] + b.y * c[1]) * half, z = (a.z * c[0] + b.z * c[1]) * half;
			vc.vertex(m, x, y, z).color(1f, 1f, 1f, 1f).texture(c[2], c[3]).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		}
	}

	/**
	 * ChainsawProjectile's TrailRenderer: 2 u wide tapering to 0 over 0.5 s, (1, 0.235, 0.235) at alpha 0.49 fading to
	 * white and clear, on an additive material.
	 */
	private static final double SCREW_TRAIL_T = Tune.def("proj.screwTrailTime", 1, "Screwdriver trail time, s (Harpoon Malicious TrailRenderer: 1)");
	private static final double SCREW_TRAIL_W = Tune.def("proj.screwTrailWidth", 0.5, "Screwdriver trail width, u (TrailRenderer: 0.5)");
	private static final double SCREW_TRAIL_A = Tune.def("proj.screwTrailAlpha", 0.5, "Screwdriver trail brightness (Additive, _TintColor 0.25 doubled)");
	private static final double SCREW_STAR = Tune.def("proj.screwStarSize", 1, "Screwdriver star size multiplier (muzzleflashnailgun 0.32 u at scale 5 = 1.6 u)");

	private static final double NAIL_FLIP = Tune.def("proj.nailFlip", 0, "1 = nails drawn turned round (nail first along the flight)");
	private static final double MAGNET_TINT = Tune.def("magnet.tint", 0.3, "magnet spike colour multiplier (by eye: the game's spike looks near black; its plain material is _Color 0.588)");
	private static final double NAIL_PITCH = Tune.def("proj.nailPitch", 0, "nail model turn about its side, degrees (its mesh stands upright; 90 or -90 lays it along the flight)");
	private static final double NAIL_TRAIL_A = Tune.def("proj.nailTrailAlpha", 1, "nail trails brightness multiplier (Additive)");

	/** A TrailRenderer of one colour fading from `a0` to 0 over `life` s, `widthU` u wide, additive. */
	private void colorTrail(UKProjectileEntity e, Vec3d pos, MatrixStack ms, VertexConsumerProvider buffers, double life, double widthU, float r, float g, float b, float a0) {
		colorTrail(e, pos, ms, buffers, life, widthU, r, g, b, a0, true);
	}

	/** @param depth false: writes no depth (under a shader pack a depth-writing trail kept the water off the screen behind it) */
	private void colorTrail(UKProjectileEntity e, Vec3d pos, MatrixStack ms, VertexConsumerProvider buffers, double life, double widthU, float r, float g, float b, float a0, boolean depth) {
		double now = com.ultracraft.client.uk.ViewModel.gameSeconds();
		java.util.ArrayDeque<double[]> q = TRAILS.computeIfAbsent(e.getId(), kk -> new java.util.ArrayDeque<>());
		// a nail we just fired shows up a tick later already metres out: its trail starts back at the barrel it left
		double[] muzzle = q.isEmpty() && e.kind().nail() ? ArsenalClient.nailMuzzleNear(e.getPos()) : null;
		if (muzzle != null) q.addLast(new double[] {muzzle[0], muzzle[1], muzzle[2], now - muzzle[3]});
		if (q.isEmpty() || q.peekLast()[3] < now - 1.0 / 120) q.addLast(new double[] {pos.x, pos.y, pos.z, now});
		while (!q.isEmpty() && q.peekFirst()[3] < now - life) q.pollFirst();
		if (q.size() < 2) return;
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(depth ? com.ultracraft.client.render.UkLayers.glowDepth(WHITE) : com.ultracraft.client.render.UkLayers.glow(WHITE));
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		float w = (float) (widthU * Damage.SCALE_BLOCKS / 2);
		float amax = a0 * (float) Tune.get("proj.nailTrailAlpha");
		Matrix4f m = ms.peek().getPositionMatrix();
		java.util.List<double[]> pts = new java.util.ArrayList<>(q);
		Vec3d prevSide = null;
		for (int i = pts.size() - 1; i > 0; i--) {
			double[] pa0 = pts.get(i), pb0 = pts.get(i - 1);
			Vec3d pa = new Vec3d(pa0[0], pa0[1], pa0[2]).subtract(pos), pb = new Vec3d(pb0[0], pb0[1], pb0[2]).subtract(pos);
			Vec3d dir = pa.subtract(pb);
			if (dir.lengthSquared() < 1e-10) continue;
			Vec3d side = dir.crossProduct(cam.subtract(pos).subtract(pa));
			side = side.lengthSquared() < 1e-12 ? (prevSide != null ? prevSide : new Vec3d(0, 1, 0)) : side.normalize();
			float ta = (float) Math.min(1, (now - pa0[3]) / life), tb = (float) Math.min(1, (now - pb0[3]) / life);
			Vec3d sa = (prevSide != null ? prevSide : side).multiply(w), sb = side.multiply(w);
			ptv(vc, m, pa.add(sa), r, g, b, amax * (1 - ta)); ptv(vc, m, pa.subtract(sa), r, g, b, amax * (1 - ta));
			ptv(vc, m, pb.subtract(sb), r, g, b, amax * (1 - tb)); ptv(vc, m, pb.add(sb), r, g, b, amax * (1 - tb));
			prevSide = side;
		}
	}

	/** Harpoon Malicious' TrailRenderer: 1 s, 0.5 u, (0.267, 1, 0.271) to white while fading out, additive. */
	private void screwTrail(UKProjectileEntity e, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		double now = com.ultracraft.client.uk.ViewModel.gameSeconds();
		Vec3d pos = e.getLerpedPos(tickDelta);
		java.util.ArrayDeque<double[]> q = TRAILS.computeIfAbsent(e.getId(), kk -> new java.util.ArrayDeque<>());
		double life = Tune.get("proj.screwTrailTime");
		if (q.isEmpty() || q.peekLast()[3] < now - 1.0 / 120) q.addLast(new double[] {pos.x, pos.y, pos.z, now});
		while (!q.isEmpty() && q.peekFirst()[3] < now - life) q.pollFirst();
		if (q.size() < 2) return;
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.glowDepth(WHITE));
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		float w = (float) (Tune.get("proj.screwTrailWidth") * Damage.SCALE_BLOCKS / 2);
		float amax = (float) Tune.get("proj.screwTrailAlpha");
		Matrix4f m = ms.peek().getPositionMatrix();
		java.util.List<double[]> pts = new java.util.ArrayList<>(q);
		Vec3d prevSide = null;
		for (int i = pts.size() - 1; i > 0; i--) {
			double[] a = pts.get(i), b = pts.get(i - 1);
			Vec3d pa = new Vec3d(a[0], a[1], a[2]).subtract(pos), pb = new Vec3d(b[0], b[1], b[2]).subtract(pos);
			Vec3d dir = pa.subtract(pb);
			if (dir.lengthSquared() < 1e-10) continue;
			Vec3d side = dir.crossProduct(cam.subtract(pos).subtract(pa));
			side = side.lengthSquared() < 1e-12 ? (prevSide != null ? prevSide : new Vec3d(0, 1, 0)) : side.normalize();
			float ta = (float) Math.min(1, (now - a[3]) / life), tb = (float) Math.min(1, (now - b[3]) / life);
			Vec3d sa = (prevSide != null ? prevSide : side).multiply(w), sb = side.multiply(w);
			float ra = 0.267f + 0.733f * ta, ba = 0.271f + 0.729f * ta, rb = 0.267f + 0.733f * tb, bb = 0.271f + 0.729f * tb;
			ptv(vc, m, pa.add(sa), ra, 1f, ba, amax * (1 - ta)); ptv(vc, m, pa.subtract(sa), ra, 1f, ba, amax * (1 - ta));
			ptv(vc, m, pb.subtract(sb), rb, 1f, bb, amax * (1 - tb)); ptv(vc, m, pb.add(sb), rb, 1f, bb, amax * (1 - tb));
			prevSide = side;
		}
	}

	/** Harpoon Malicious' "muzzleflash" child: the muzzleflashnailgun star (1.6 u) riding on the screwdriver in flight. */
	private static void screwStar(UKProjectileEntity e, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		float half = (float) (0.32 * 5 * Tune.get("proj.screwStarSize") * Damage.SCALE_BLOCKS / 2);
		net.minecraft.client.render.Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
		org.joml.Vector3f r = new org.joml.Vector3f(cam.getHorizontalPlane()).cross(cam.getVerticalPlane()).normalize(), u = new org.joml.Vector3f(cam.getVerticalPlane());
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(net.minecraft.client.render.RenderLayer.getEntityCutoutNoCull(STAR));
		Matrix4f m = ms.peek().getPositionMatrix();
		float[][] q = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		for (float[] c : q) {
			float x = (r.x * c[0] + u.x * c[1]) * half, y = (r.y * c[0] + u.y * c[1]) * half, z = (r.z * c[0] + u.z * c[1]) * half;
			vc.vertex(m, x, y, z).color(1f, 1f, 1f, 1f).texture(c[2], c[3]).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		}
	}

	private void sawTrail(UKProjectileEntity e, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		double now = com.ultracraft.client.uk.ViewModel.gameSeconds();
		Vec3d pos = e.getLerpedPos(tickDelta);
		java.util.ArrayDeque<double[]> q = TRAILS.computeIfAbsent(e.getId(), kk -> new java.util.ArrayDeque<>());
		double life = Tune.get("proj.sawTrailTime");
		if (q.isEmpty() || q.peekLast()[3] < now - 1.0 / 120) q.addLast(new double[] {pos.x, pos.y, pos.z, now});
		while (!q.isEmpty() && q.peekFirst()[3] < now - life) q.pollFirst();
		if (q.size() < 2) return;
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.glow(WHITE));
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		float w = (float) (Tune.get("proj.sawTrailWidth") * Damage.SCALE_BLOCKS / 2);
		float a0max = (float) Tune.get("proj.sawTrailAlpha");
		Matrix4f m = ms.peek().getPositionMatrix();
		java.util.List<double[]> pts = new java.util.ArrayList<>(q);
		Vec3d prevSide = null;
		for (int i = pts.size() - 1; i > 0; i--) {
			double[] a = pts.get(i), b = pts.get(i - 1);
			Vec3d pa = new Vec3d(a[0], a[1], a[2]).subtract(pos), pb = new Vec3d(b[0], b[1], b[2]).subtract(pos);
			Vec3d dir = pa.subtract(pb);
			if (dir.lengthSquared() < 1e-10) continue;
			Vec3d side = dir.crossProduct(cam.subtract(pos).subtract(pa));
			side = side.lengthSquared() < 1e-12 ? (prevSide != null ? prevSide : new Vec3d(0, 1, 0)) : side.normalize();
			float ta = (float) Math.min(1, (now - a[3]) / life), tb = (float) Math.min(1, (now - b[3]) / life);
			Vec3d sa = (prevSide != null ? prevSide : side).multiply(w * (1 - ta)), sb = side.multiply(w * (1 - tb));
			// colour (1, 0.235, 0.235) to white, alpha 0.49 to 0
			float ga = 0.235f + 0.765f * ta, gb = 0.235f + 0.765f * tb;
			ptv(vc, m, pa.add(sa), 1f, ga, ga, a0max * (1 - ta)); ptv(vc, m, pa.subtract(sa), 1f, ga, ga, a0max * (1 - ta));
			ptv(vc, m, pb.subtract(sb), 1f, gb, gb, a0max * (1 - tb)); ptv(vc, m, pb.add(sb), 1f, gb, gb, a0max * (1 - tb));
			prevSide = side;
		}
	}

	private static final Identifier STAR = new Identifier(com.ultracraft.UltraCraft.MOD_ID, "textures/uk/muzzleflashnailgun.png");
	// the 4-point star repeats every 90 deg, so above ~45 deg a frame (5400 deg/s at 120 fps) it starts to look like it turns backwards
	private static final double SAW_STAR_SPIN = Tune.def("proj.sawStarSpin", -1800, "chainsaw star spin, deg/s, negative = the other way (prefab Spin: 3600)");
	private static final double SAW_STAR = Tune.def("proj.sawStarSize", 1, "chainsaw star size multiplier (prefab: 3.2 u)");
	private static final double SAW_NEAR = Tune.def("proj.sawNearHide", 2.5, "chainsaw model hidden within this many u of the camera (its spawn / punch point)");
	private static final double SAW_CATCH = Tune.def("proj.sawCatchDist", 1.5, "chainsaw counts as caught (model off, star small) within this many u of its thrower's body centre");
	private static final double SAW_TRAIL_T = Tune.def("proj.sawTrailTime", 0.5, "chainsaw trail time, s (prefab: 0.5)");
	private static final double SAW_TRAIL_W = Tune.def("proj.sawTrailWidth", 2, "chainsaw trail width, u (prefab: 2 tapering)");
	private static final double SAW_TRAIL_A = Tune.def("proj.sawTrailAlpha", 0.49, "chainsaw trail opacity at the saw (prefab: 0.49)");

	/** The saw trail's gradient at t (1 at the saw): the saw's colour with alpha 0.49 to white with alpha 0. */
	private static void tvc(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, Vec3d p, float t, float[] head) {
		float a = 0.49f * t * (float) Tune.get("proj.trailBright");
		float r = 1 + (head[0] - 1) * t, g = 1 + (head[1] - 1) * t, b = 1 + (head[2] - 1) * t;
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	private static void tv(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, Vec3d p, float a) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(1f, 1f, 1f, a).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	private static long cbLog;
	private static final Identifier RAGE_RING = new Identifier("ultracraft", "textures/uk/rageeffectwhite.png");
	private static final double FREEZE_SHADER_GAIN = Tune.def("proj.freezeShaderGain", 1, "Freezeframe rocket ring under a shader pack: its colour x this (the pack blooms unlit layers; by eye)");
	private static final Identifier V_GRADIENT = new Identifier("ultracraft", "textures/uk/verticalgradient.png");

	/**
	 * Grenade.freezeEffect, on while the rockets are frozen: FreezeEffect (2 u ahead of the rocket, scale 3) / Sprite
	 * (AlwaysLookAtCamera, SpriteGetVariationColor) / New Sprite: 'RageEffectWhite' 0.64 u x 5 = a 9.6 u ring at alpha
	 * 0.75, with four 'verticalgradient' bars (0.01 x 2.56 u, scaled 2 x 0.06 x 15 = 0.3 x 2.3 u) 2.55 u out from its
	 * middle at the four quarters, each opaque at its outer end; all in the Freezeframe's colour. All of it under the
	 * rocket's root scale 0.35 (Rocket_0): a 3.36 u ring 0.7 u ahead.
	 */
	private void freezeEffect(UKProjectileEntity e, MatrixStack ms, VertexConsumerProvider buffers) {
		double S = Damage.SCALE_BLOCKS * 0.35;
		Vec3d v = e.getVelocity();
		Vec3d fwd = v.lengthSquared() > 1e-10 ? v.normalize() : Vec3d.fromPolar(e.getPitch(), e.getYaw());
		int rgb = com.ultracraft.client.UltraCraftClient.VARIATION_COLORS[0];
		float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
		// (a shader pack blooms the unlit layer: turned down there, by eye)
		if (com.ultracraft.client.render.UkLayers.shadersOn()) {
			float k = (float) Tune.get("proj.freezeShaderGain");
			r *= k; g *= k; b *= k;
		}
		ms.push();
		ms.translate(fwd.x * 2 * S, fwd.y * 2 * S, fwd.z * 2 * S);
		ms.multiply(this.dispatcher.getRotation());
		Matrix4f m = ms.peek().getPositionMatrix();
		float half = (float) (4.8 * S);
		// (unlit and writing depth, as the gun's hologram: lit it came out dark, and shader clouds drew over it)
		billboard(buffers.getBuffer(com.ultracraft.client.render.UkLayers.emissiveDepth(RAGE_RING)), m, -half, -half, half, half, 0, r, g, b, 0.75f);
		float bw = (float) (0.15 * S), bh = (float) (1.15 * S), off = (float) (2.55 * S);
		net.minecraft.client.render.VertexConsumer bars = buffers.getBuffer(com.ultracraft.client.render.UkLayers.emissiveDepth(V_GRADIENT));
		for (int i = 0; i < 4; i++) {
			// bar i: centre `off` out along angle 90 * i (0 = down), the image's opaque bottom outward
			ms.push();
			ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90 * i));
			billboard(bars, ms.peek().getPositionMatrix(), -bw, -off - bh, bw, -off + bh, 0, r, g, b, 1);
			ms.pop();
		}
		ms.pop();
	}

	private static final double ROCKET_TRAIL_R = Tune.def("proj.rocketTrailR", 1, "rocket trail colour, red (game: 1)");
	private static final double ROCKET_TRAIL_G = Tune.def("proj.rocketTrailG", 0.5, "rocket trail colour, green (game: 0.5)");
	private static final double ROCKET_TRAIL_B = Tune.def("proj.rocketTrailB", 0, "rocket trail colour, blue (game: 0)");
	private static final double ROCKET_TRAIL_A = Tune.def("proj.rocketTrailA", 0.39, "rocket trail strength at the rocket (game: 0.39)");
	private static final Identifier ROCKET_FLAME = new Identifier("ultracraft", "textures/uk/muzzleflashshotgun1.png");

	/**
	 * Rocket_0's muzzleflash at its tail: the 'muzzleflashshotgun 1' sprite (0.16 u, x 0.5 x 24 x the rocket's 0.35 =
	 * 0.67 u), always facing the camera, orange (1, 0.5, 0) on the Additive material, 0.01 x 24 x 0.35 u behind the
	 * rocket's origin; SpriteController blinking: its alpha runs down and up at 50 a second (a 0.04 s flicker).
	 */
	private void rocketFlame(UKProjectileEntity e, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers) {
		double S = Damage.SCALE_BLOCKS;
		float yaw = net.minecraft.util.math.MathHelper.lerpAngleDegrees(tickDelta, e.prevYaw, e.getYaw()), pitch = e.prevPitch + (e.getPitch() - e.prevPitch) * tickDelta;
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		Vec3d fwd = new Vec3d(Math.sin(yr) * Math.cos(pr), Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		double back = 0.01 * 24 * 0.35 * S;
		double t = com.ultracraft.client.uk.ViewModel.gameSeconds() * 50 % 2;
		float a = (float) (t < 1 ? 1 - t : t - 1);
		ms.push();
		ms.translate(-fwd.x * back, -fwd.y * back, -fwd.z * back);
		ms.multiply(this.dispatcher.getRotation());
		float half = (float) (0.16 * 0.5 * 24 * 0.35 * S / 2);
		billboard(buffers.getBuffer(com.ultracraft.client.render.UkLayers.glow(ROCKET_FLAME)), ms.peek().getPositionMatrix(), -half, -half, half, half, 0, 1f, 0.5f, 0f, a);
		ms.pop();
	}

	private static final Identifier GRENADE_FLARE = new Identifier("ultracraft", "textures/uk/muzzleflashshotgun.png");

	static {
		Tune.def("grenade.flareSize", 1.5, "Core Eject / hammer grenade star size, x the game's (muzzleflashshotgun x 24 under the 0.5 root; bigger by the user's call)");
		Tune.def("grenade.flareRate", 15, "grenade star blink rate, per second (the game's SpriteController blinks at 50; calmer by the user's call)");
		Tune.def("grenade.flareDepth", 0.4, "grenade star blink depth: 0 = steady, 1 = fully on and off (by eye)");
	}

	/**
	 * The grenade's star (its GameObject x 24: muzzleflashshotgun in (1, 0.74, 0.49), added on, facing the camera, blinking),
	 * writing depth so shader-pack clouds are not laid over it.
	 */
	private void grenadeFlare(UKProjectileEntity e, MatrixStack ms, VertexConsumerProvider buffers) {
		double S = Damage.SCALE_BLOCKS;
		double t = com.ultracraft.client.uk.ViewModel.gameSeconds() * Tune.get("grenade.flareRate") % 2;
		float tri = (float) (t < 1 ? 1 - t : t - 1);
		float a = 1 - (float) Tune.get("grenade.flareDepth") * tri;
		ms.push();
		ms.multiply(this.dispatcher.getRotation());
		float half = (float) (0.16 * 0.5 * 24 * 0.5 * S / 2 * Tune.get("grenade.flareSize"));
		billboard(buffers.getBuffer(com.ultracraft.client.render.UkLayers.glowDepth(GRENADE_FLARE)), ms.peek().getPositionMatrix(), -half, -half, half, half, 0, 1f, 0.74f, 0.49f, a);
		ms.pop();
	}

	/** A quad in the matrices' XY plane (x0, y0 bottom-left with the image's bottom), both faces, full bright. */
	private static void billboard(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, float r, float g, float b, float a) {
		float[][] c = {{x0, y0, 0, 1}, {x1, y0, 1, 1}, {x1, y1, 1, 0}, {x0, y1, 0, 0}};
		for (int pass = 0; pass < 2; pass++)
			for (int i = 0; i < 4; i++) {
				float[] q = c[pass == 0 ? i : 3 - i];
				vc.vertex(m, q[0], q[1], z).color(r, g, b, a).texture(q[2], q[3]).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1).next();
			}
	}

	/**
	 * Saws and chainsaws are drawn whenever they are near enough, not only with their own small box in view: their trails
	 * reach far from them (a ring's trail went out and back as the saw going round it left the screen).
	 */
	@Override
	public boolean shouldRender(UKProjectileEntity e, net.minecraft.client.render.Frustum frustum, double x, double y, double z) {
		ProjectileKind k = e.kind();
		// (a ridden rocket sits under the camera, at the frustum's edge: its small box flickered in and out)
		if (k.sawblade() || k == ProjectileKind.CHAINSAW || k == ProjectileKind.ROCKET) return e.shouldRender(x, y, z);
		return super.shouldRender(e, frustum, x, y, z);
	}

	@Override
	public Identifier getTexture(UKProjectileEntity entity) {
		return new Identifier("minecraft", "textures/misc/white.png");
	}
}
