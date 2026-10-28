package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.render.UkLayers;
import com.ultracraft.net.FirePayload;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * The Jump Start's zapper (Zapper.cs), run on this side like the whiplash: it flies, latches onto a mob as the player
 * sees it, charges and checks its cable here, and only the shock goes to the server (nailgun mode 3).
 * <ul>
 * <li>Nailgun.ShootZapper: from the camera along the aim at 100 u/s (VelocityChange), falling with V1's gravity (-40);
 * a ConfigurableJoint keeps it within maxDistance - 5 of V1 while it flies.</li>
 * <li>CheckAttach: the trigger sphere (radius 1) on an enemy takes it to the enemy's bounds' centre (a small enemy);
 * the environment breaks it.</li>
 * <li>Update: charge climbs 1/s to 5, then Zap(): 10 damage, up x 1000, naiZapperRecharge = 0, Break(true).</li>
 * <li>FixedUpdate (0.008 s): further than maxDistance (20) from the cable's start, or the environment between them,
 * runs breakTimer to 1 (twice as fast when blocked) with the warning loops; at 1 it Breaks (cable snap).</li>
 * </ul>
 */
public final class ZapperClient {
	public static final ZapperClient INSTANCE = new ZapperClient(false);
	/** The off-hand gun's own zapper (each Nailgun copy has its currentZapper). */
	public static final ZapperClient OFF = new ZapperClient(true);

	/** The zapper of the gun whose code runs now (the off-hand one under OffhandGun.run). */
	public static ZapperClient cur() {
		return OffhandGun.running() ? OFF : INSTANCE;
	}

	private static final double U = Damage.SCALE_BLOCKS;
	/** Zapper prefab maxDistance. */
	public static final float MAX_DISTANCE = 20f;
	/** ULTRAKILL's fixed step. */
	private static final double STEP = 0.008;
	private static final Identifier GRADIENT = new Identifier(UltraCraft.MOD_ID, "textures/uk/gradient.png");
	private static final Identifier CHARGE = new Identifier(UltraCraft.MOD_ID, "textures/uk/charge.png");
	private static final Identifier BODY = new Identifier(UltraCraft.MOD_ID, "textures/uk/floordecoration3.png");
	private static final Identifier PRONGS = new Identifier(UltraCraft.MOD_ID, "textures/uk/floortiles2c.png");
	private static final Identifier WHITE = new Identifier("minecraft", "textures/misc/white.png");

	public enum State { NONE, FLYING, ATTACHED }

	public State state = State.NONE;
	/** Where it is (blocks), and where it was at the start of this tick (for drawing between ticks). */
	private Vec3d pos = Vec3d.ZERO, tickStart = Vec3d.ZERO, vel = Vec3d.ZERO;
	/** Its forward (the flight direction; it keeps it once attached). */
	private Vec3d facing = new Vec3d(0, 0, 1);
	private int targetId = -1;
	private double stepAcc;
	public float charge, breakTimer, distance;
	/** Zapper.damage: 10, +0.5 for each sawblade that hits the enemy it is on (Nail.HitEnemy). */
	private float zapDamage = 10;
	public boolean blocked, warning;
	/** Where the cable leaves the gun (Armature/Main/ZapperPosition) in the view, set when the Jump Start is drawn. */
	public Vector3f startView;
	public long startViewNanos;
	private final boolean off;

	private ZapperClient(boolean off) {
		this.off = off;
	}

	/** Its own loop keys, so the two zappers' loops do not stop each other. */
	private String key(String name) {
		return (off ? "zapperoff." : "zapper.") + name;
	}

	/** Where the drawn gun's ZapperPosition is in the view (the off-hand gun's mirrored to the left, where it is drawn). */
	static void setStart(Vector3f view) {
		ZapperClient z = cur();
		if (z.off) view.x = -view.x;
		z.startView = view;
		z.startViewNanos = System.nanoTime();
	}

	public boolean out() {
		return state != State.NONE;
	}

	/** Nailgun.ShootZapper: a zapper already out breaks first. */
	public void shoot(ClientPlayerEntity p) {
		if (out()) breakZapper(false);
		pos = p.getEyePos();
		tickStart = pos;
		facing = p.getRotationVec(1f);
		vel = facing.multiply(100 * U);
		state = State.FLYING;
		targetId = -1;
		charge = 0;
		breakTimer = 0;
		blocked = false;
		warning = false;
		stepAcc = 0;
		distance = 0;
		zapDamage = 10;
	}

	/**
	 * The cable's start: the gun's ZapperPosition while the Jump Start is out and drawn; otherwise (Nailgun.OnDisable)
	 * V1 itself.
	 */
	private Vec3d lineStart(ClientPlayerEntity p, float td) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (startView != null && System.nanoTime() - startViewNanos < 150_000_000L && c.options.getPerspective().isFirstPerson())
			return HandRenderer.viewToWorld(startView);
		return p.getLerpedPos(td).add(0, p.getHeight() / 2, 0);
	}

	private static Vec3d center(Entity e, float td) {
		Vec3d at = e.getLerpedPos(td);
		return at.add(0, e.getHeight() / 2, 0);
	}

	/** Every client tick (END, after the mobs moved). */
	public void tick(MinecraftClient c) {
		ClientPlayerEntity p = c.player;
		if (p == null || c.world == null) { clear(); return; }
		if (state == State.NONE) return;
		if (c.isPaused() || com.ultracraft.client.v1.V1.INSTANCE.frozen()) return;
		if (p.isDead()) { clear(); return; }
		float dt = 0.05f;
		tickStart = pos;
		Vec3d start = lineStart(p, 1f);
		if (state == State.ATTACHED) {
			Entity t = c.world.getEntityById(targetId);
			// Zap() with the enemy gone: only the Break
			if (t == null || t.isRemoved()) { breakZapper(true); return; }
			pos = center(t, 1f);
			charge = Math.min(5, charge + dt);
		}
		stepAcc += dt;
		while (stepAcc >= STEP - 1e-6 && state != State.NONE) {
			stepAcc -= STEP;
			if (state == State.FLYING) fly(c.world, p);
			else cableStep(c.world, p, start);
		}
		if (state == State.NONE) return;
		distance = (float) (pos.distanceTo(start) / U);
		if (state == State.ATTACHED) {
			// the surge loop on the zapper (0.75, pitch 1 + charge / 5); the pulse orb's own loop (1, pitch 2)
			UKSound.loopAt(key("surge"), pos, "uk.weapons.electricitysurgecontinuous", 0.75f, 1 + charge / 5);
			UKSound.loopAt(key("orb"), start.lerp(pos, orbT()), "uk.weapons.electromagneticdevicesqueal", 1f, 2f);
			if (warning) {
				// FixedUpdate: both warnings at pitch 1 out of range, 2 when blocked
				float wp = blocked ? 2f : 1f;
				UKSound.loopAt(key("tighten"), pos, "uk.weapons.ropetighten", 1f, wp);
				UKSound.loopAt(key("warning"), pos, "uk.weapons.zapperwarning", 0.85f, wp);
			} else stopWarnings();
			Entity t = c.world.getEntityById(targetId);
			// only a full charge shocks and starts the recharge; a mob that died some other way just lets go (the user's call:
			// a hook that did not land its shock costs nothing; the game spent the recharge on that too)
			if (charge >= 5) zap(p, t);
			else if (!(t instanceof LivingEntity le) || le.isDead()) breakZapper(true);
		}
	}

	private void fly(ClientWorld w, ClientPlayerEntity p) {
		vel = vel.add(0, -40 * U * STEP, 0);
		Vec3d next = pos.add(vel.multiply(STEP));
		// the joint: no further than maxDistance - 5 from V1 while it flies
		Vec3d anchor = p.getPos().add(0, p.getHeight() / 2, 0);
		Vec3d rel = next.subtract(anchor);
		double lim = (MAX_DISTANCE - 5) * U;
		if (rel.length() > lim) {
			Vec3d n = rel.normalize();
			next = anchor.add(n.multiply(lim));
			double outward = vel.dotProduct(n);
			if (outward > 0) vel = vel.subtract(n.multiply(outward));
		}
		// an enemy in the trigger sphere (radius 1)
		LivingEntity hit = null;
		double best = Double.MAX_VALUE;
		double r = 1 * U;
		for (Entity e : w.getOtherEntities(p, new Box(pos, next).expand(r + 1), e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof PlayerEntity))) {
			Box b = e.getBoundingBox().expand(r);
			Optional<Vec3d> at = b.contains(pos) ? Optional.of(pos) : b.raycast(pos, next);
			if (at.isPresent() && at.get().squaredDistanceTo(pos) < best) {
				best = at.get().squaredDistanceTo(pos);
				hit = (LivingEntity) e;
			}
		}
		// the environment (the Cube's collider) breaks it
		BlockHitResult bh = w.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		if (bh.getType() != HitResult.Type.MISS && (hit == null || bh.getPos().squaredDistanceTo(pos) < best)) {
			pos = bh.getPos();
			breakZapper(false);
			return;
		}
		if (hit != null) {
			attach(hit);
			return;
		}
		if (vel.lengthSquared() > 1e-8) facing = vel.normalize();
		pos = next;
	}

	/** CheckAttach on an enemy: to its bounds' centre, the surge loop, ZapperAttachSound (harpoonPierce 0.66, pitch 2.5 +-0.1). */
	private void attach(LivingEntity t) {
		state = State.ATTACHED;
		targetId = t.getId();
		pos = center(t, 1f);
		charge = 0;
		breakTimer = 0;
		UKSound.at(pos, "uk.weapons.harpoonpierce", 0.66f, UKSound.range(2.4f, 2.6f));
	}

	/** Zapper.FixedUpdate while attached. */
	private void cableStep(ClientWorld w, ClientPlayerEntity p, Vec3d start) {
		// CheckLineOfSight: the environment between the zapper and the cable's start
		blocked = w.raycast(new RaycastContext(pos, start, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p)).getType() != HitResult.Type.MISS;
		float d = (float) (pos.distanceTo(start) / U);
		if (d > MAX_DISTANCE || blocked) {
			warning = true;
			breakTimer = Math.min(1, breakTimer + (float) STEP * (blocked ? 2 : 1));
			if (breakTimer >= 1) breakZapper(false);
		} else {
			warning = false;
			breakTimer = 0;
		}
	}

	/** Zapper.Zap: the shock (server), the recharge restarts, SparksNail (nailZap 0.5, pitch 1 +-0.2), Break(true). */
	private void zap(ClientPlayerEntity p, Entity t) {
		if (t != null) {
			// (the damage rides in dir.y)
			Net.toServer(new FirePayload(FirePayload.NAILGUN, 3, t.getId(), p.getEyePos(), new Vec3d(0, zapDamage, 0)));
			(off ? ArsenalClient.OFF : ArsenalClient.INSTANCE).naiZapperRecharge = 0;
			SparkFx.burst(pos);
			UKSound.at(pos, "uk.weapons.nailzap", 0.5f, UKSound.range(0.8f, 1.2f));
		}
		breakZapper(true);
	}

	/**
	 * Zapper.Break: BreakParticleMetalSaw (sawBreak 0.45 and the sparks' Ricochet 0.25), and RopeSnap when it was
	 * attached and did not zap.
	 */
	private void breakZapper(boolean zapped) {
		if (state == State.NONE) return;
		SparkFx.burst(pos);
		UKSound.at(pos, "uk.weapons.sawbreak", 0.45f, UKSound.range(0.9f, 1.1f));
		UKSound.at(pos, "uk.weapons.revolver.ricochet", 0.25f, UKSound.range(0.85f, 1.15f));
		if (state == State.ATTACHED && !zapped) UKSound.at(pos, "uk.weapons.ropesnap", 1f, 1f);
		clear();
	}

	/**
	 * Nail.HitEnemy: a sawblade hitting the enemy a zapper is on adds 0.5 to the zapper's damage and ChargeBoost(0.5):
	 * charge + 0.5, ZapperBoost (0.85) at the zapper.
	 */
	public void sawHit(Vec3d at) {
		if (state != State.ATTACHED) return;
		MinecraftClient c = MinecraftClient.getInstance();
		Entity t = c.world == null ? null : c.world.getEntityById(targetId);
		if (t == null || !t.getBoundingBox().expand(1).contains(at)) return;
		zapDamage += 0.5f;
		charge = Math.min(5, charge + 0.5f);
		UKSound.at(pos, "uk.weapons.zapperboost", 0.85f, 1f);
	}

	private void stopWarnings() {
		UKSound.stop(key("tighten"));
		UKSound.stop(key("warning"));
	}

	public void clear() {
		state = State.NONE;
		targetId = -1;
		charge = 0;
		breakTimer = 0;
		blocked = false;
		warning = false;
		UKSound.stop(key("surge"));
		UKSound.stop(key("orb"));
		stopWarnings();
	}

	/** Zapper.Update: the pulse orb's place along the cable, (charge % (0.25 / (charge / 4))) x charge (faster as it charges). */
	private float orbT() {
		return orbT(charge);
	}

	/** orbT at a given charge (drawn with the charge of this frame: Update runs every frame, the orb crosses in 0.2 s at the end). */
	private static float orbT(float charge) {
		if (charge <= 0) return 0;
		float period = 0.25f / (charge / 4f);
		return Math.max(0, Math.min(1, (charge % period) * charge));
	}

	// ------------------------------------------------------------------ drawing

	public void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || state == State.NONE) return;
		MinecraftClient c = MinecraftClient.getInstance();
		ClientPlayerEntity p = c.player;
		if (p == null || c.world == null) return;
		float td = e.getPartialTick();
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		Vec3d start = lineStart(p, td);
		Vec3d at = tickStart.lerp(pos, td);
		if (state == State.ATTACHED) {
			Entity t = c.world.getEntityById(targetId);
			if (t != null) at = center(t, td);
		}
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		// the cable (LineRenderer 0.1 wide, Gradient): 0.5 grey; flashing white / black while it is about to break;
		// toward red over the last 10 u before maxDistance
		float d = (float) (at.distanceTo(start) / U);
		float cr = 0.5f, cg = 0.5f, cb = 0.5f;
		if (breakTimer > 0) {
			float v = breakTimer % 0.1f > 0.05f ? 1 : 0;
			cr = cg = cb = v;
		} else if (d > MAX_DISTANCE - 10) {
			float k = Math.max(0, Math.min(1, (MAX_DISTANCE - d) / 10));
			cr = 1 + (0.5f - 1) * k;
			cg = 0.5f * k;
			cb = 0.5f * k;
		}
		// unlit materials, drawn as plain full-bright geometry (the emissive layer glows white under a shader pack)
		ribbon(buffers.getBuffer(net.minecraft.client.render.RenderLayer.getEntityCutoutNoCull(GRADIENT)), m, camPos, start, at, 0.1 * U, 0.1 * U, cr, cg, cb, 1, cr, cg, cb, 1);
		// the zapper: the Cube (0.5 x 0.5 x 0.25, Metal Decoration 2B) and four prongs (0.1 x 0.1 x 0.25, WallUnlit),
		// open while it flies, closed in once attached
		Vec3d f = facing.normalize();
		Vec3d upRef = Math.abs(f.y) > 0.95 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d right = upRef.crossProduct(f).normalize(), up = f.crossProduct(right).normalize();
		// (not while it is still at the camera it was launched from: the first frames would put the camera inside it)
		if (at.distanceTo(camPos) > 1.5 * U) {
			cube(buffers.getBuffer(net.minecraft.client.render.RenderLayer.getEntityCutoutNoCull(BODY)), m, at, right, up, f, 0, 0, 0, 0.25, 0.25, 0.125);
			VertexConsumer pv = buffers.getBuffer(net.minecraft.client.render.RenderLayer.getEntityCutoutNoCull(PRONGS));
			boolean closed = state == State.ATTACHED;
			double o = closed ? 0.25 : 0.3, oz = closed ? -0.125 : 0;
			for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2)
				cube(pv, m, at, right, up, f, sx * o, sy * o, oz, 0.05, 0.05, 0.125);
		}
		// the pulse orb (Charge, 0.75) running down the cable, and its trail back to the cable's start (pulseLine:
		// 0.5 wide to nothing, (0, 0.61, 1) to clear white, Additive)
		if (state == State.ATTACHED && charge > 0) {
			// the charge between ticks (it climbs 1/s), so the orb moves every frame, not in 20 Hz jumps
			boolean still = c.isPaused() || com.ultracraft.client.v1.V1.INSTANCE.frozen();
			float drawCharge = still ? charge : Math.min(5, charge + td * 0.05f);
			Vec3d orb = start.lerp(at, orbT(drawCharge));
			ribbon(buffers.getBuffer(UkLayers.glowDepth(WHITE)), m, camPos, orb, start, 0.5 * U, 0, 0, 0.61f, 1, 1, 1, 1, 1, 0);
			org.joml.Vector3f h = cam.getHorizontalPlane(), v = cam.getVerticalPlane();
			Vec3d bu = new Vec3d(v.x, v.y, v.z), br = new Vec3d(h.x, h.y, h.z).crossProduct(bu).normalize();
			double half = 0.75 * U / 2;
			VertexConsumer ov = buffers.getBuffer(UkLayers.glowDepth(CHARGE));
			Vec3d ax = br.multiply(half), ux = bu.multiply(half);
			vert(ov, m, orb.subtract(ax).subtract(ux), 0, 1, 1, 1, 1, 1);
			vert(ov, m, orb.add(ax).subtract(ux), 1, 1, 1, 1, 1, 1);
			vert(ov, m, orb.add(ax).add(ux), 1, 0, 1, 1, 1, 1);
			vert(ov, m, orb.subtract(ax).add(ux), 0, 0, 1, 1, 1, 1);
		}
		ms.pop();
		buffers.draw();
	}

	/** A camera-facing strip from a to b, `wa` / `wb` wide at its ends, texture v across it. */
	private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3d camPos, Vec3d a, Vec3d b, double wa, double wb,
			float ra, float ga, float ba, float aa, float rb, float gb, float bb, float ab) {
		Vec3d dir = b.subtract(a);
		if (dir.lengthSquared() < 1e-8) return;
		Vec3d side = dir.crossProduct(camPos.subtract(a));
		if (side.lengthSquared() < 1e-10) side = dir.crossProduct(new Vec3d(0, 1, 0));
		side = side.normalize();
		Vec3d sa = side.multiply(wa / 2), sb = side.multiply(wb / 2);
		vert(vc, m, a.add(sa), 0, 0, ra, ga, ba, aa);
		vert(vc, m, a.subtract(sa), 0, 1, ra, ga, ba, aa);
		vert(vc, m, b.subtract(sb), 1, 1, rb, gb, bb, ab);
		vert(vc, m, b.add(sb), 1, 0, rb, gb, bb, ab);
	}

	/** A box of half sizes (hx, hy, hz) at local (lx, ly, lz) in the frame (right, up, forward) at `at`, sizes in u. */
	private static void cube(VertexConsumer vc, Matrix4f m, Vec3d at, Vec3d right, Vec3d up, Vec3d fwd,
			double lx, double ly, double lz, double hx, double hy, double hz) {
		Vec3d c = at.add(right.multiply(lx * U)).add(up.multiply(ly * U)).add(fwd.multiply(lz * U));
		Vec3d x = right.multiply(hx * U), y = up.multiply(hy * U), z = fwd.multiply(hz * U);
		Vec3d[][] faces = {
			{x, y, z}, {x.negate(), y, z.negate()}, {z.negate(), y, x}, {z, y, x.negate()}, {x, z.negate(), y}, {x, z, y.negate()}};
		for (Vec3d[] fc : faces) {
			// face centre at c + n, spanned by a (u) and b (v)
			Vec3d a = fc[0], b = fc[1], n = fc[2];
			Vec3d fcn = c.add(n);
			vert(vc, m, fcn.subtract(a).subtract(b), 0, 1, 1, 1, 1, 1);
			vert(vc, m, fcn.add(a).subtract(b), 1, 1, 1, 1, 1, 1);
			vert(vc, m, fcn.add(a).add(b), 1, 0, 1, 1, 1, 1);
			vert(vc, m, fcn.subtract(a).add(b), 0, 0, 1, 1, 1, 1);
		}
	}

	private static void vert(VertexConsumer vc, Matrix4f m, Vec3d p, float u, float v, float r, float g, float b, float a) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
