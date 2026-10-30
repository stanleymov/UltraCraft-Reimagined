package com.ultracraft.entity;

import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.Damage;
import com.ultracraft.weapon.Explosions;
import com.ultracraft.weapon.MagnetField;
import com.ultracraft.weapon.ServerTune;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One entity class for ULTRAKILL's player projectiles ({@link ProjectileKind}); the kind picks speed,
 * gravity, damage and what happens on a hit (Projectile.Collided, Nail.DamageEnemy, Grenade.Collision/
 * Explode, Cannonball, Harpoon). Velocities are stored in blocks per tick; the kinds' u/s are converted by
 * {@link Damage#SCALE_BLOCKS}. Physics is 9.81 u/s² for rigidbodies (Unity default), not V1's 40.
 */
public class UKProjectileEntity extends Entity {
	private static final TrackedData<Integer> KIND = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> STUCK = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> FROZEN = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** Grenade.levelledUp (a rocket frozen 1 s): the client plays its LevelUpEffect. */
	private static final TrackedData<Boolean> LEVELLED = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> STUCK_IN = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	/** Punch.ParryProjectile on a player bullet: orange, x2 speed, x4 hitbox, explodes on hit (projectile boost). */
	private static final TrackedData<Boolean> BOOSTED = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** The shooter's entity id, for the shooter's client (its own nails are drawn there from the gun). */
	private static final TrackedData<Integer> OWNER_ID = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	/** A nail held by one or more magnets (its owner's client draws the server's copy from then on). */
	private static final TrackedData<Boolean> MAGNETIZED = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** A magnet: the world time it stuck (TimeBomb.StartCountdown), -1 before; the client runs the beeps from it. */
	private static final TrackedData<Long> FUSE_AT = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.LONG);
	/** A held nail's magnet it circles and its true velocity (blocks/s; velocity packets clamp at 3.9 blocks a tick), for drawing it between ticks. */
	private static final TrackedData<Integer> MAG_TARGET = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<org.joml.Vector3f> MAG_VEL = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	/** A magnet's load, the held nails' mass over its limit (0..1). */
	private static final TrackedData<Float> MAG_LOAD = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.FLOAT);
	/** A held nail's turn from the way to its magnet now (degrees), for drawing it between ticks. */
	private static final TrackedData<Float> MAG_ANGLE = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.FLOAT);
	/**
	 * A sawblade steered round its magnets, for the client to run the same steps: the way round (0 = not steered), the
	 * current step's direction, how far into that 0.008 s step it is (s) and the server tick this state is from.
	 */
	private static final TrackedData<Integer> SAW_SPIN = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<org.joml.Vector3f> SAW_DIR = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	private static final TrackedData<Float> SAW_PHASE = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Integer> SAW_TICK = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	/**
	 * Where the steered saw is at the end of that tick, sent with the rest: Minecraft's own position updates (deltas on
	 * the last position sent) had a saw that came back from a long flight drawn 5 blocks off the server's for good.
	 */
	private static final TrackedData<org.joml.Vector3f> SAW_POS = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	/** A stuck magnet's field centre, as the server works it out (the client's own from the synced yaw / pitch is coarse). */
	private static final TrackedData<org.joml.Vector3f> MAG_C = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	/** The variation of the launcher that fired a sawblade (0 blue, 1 green, 2 red; -1 unknown), for its trail colour. */
	private static final TrackedData<Integer> SAW_VAR = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.INTEGER);
	/** A chainsaw thrown by the off-hand gun: its chain runs to that gun (each Shotgun keeps its own currentChainsaws). */
	private static final TrackedData<Boolean> OFF_HAND = DataTracker.registerData(UKProjectileEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final double DT = 0.05;

	public UUID owner;
	/** Grenade.playerRiding: the player standing on this rocket; it then flies at 0.65 × speed and sinks (downpull). */
	public UUID rider;
	private float downpull = -0.5f;
	/** Grenade: the rocket's transform while ridden (forward, up; turned by the rider's input), hasBeenRidden, the input. */
	private Vec3d rideFwd, rideUp;
	private boolean hasBeenRidden;
	private double steerX, steerY;

	/** Grenade.PlayerRideStart: the first ride of this rocket counts, and past 3 rides it starts 0.25 x (rides - 3) lower. */
	public void rideStart(UUID player, int rocketRides) {
		rider = player;
		steerX = steerY = 0;
		Vec3d v = getVelocity();
		if (v.lengthSquared() > 1e-10) rideFwd = v.normalize();
		else {
			double yaw = Math.toRadians(getYaw()), pitch = Math.toRadians(getPitch());
			rideFwd = new Vec3d(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		}
		Vec3d right = rideFwd.crossProduct(new Vec3d(0, 1, 0));
		rideUp = right.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : right.normalize().crossProduct(rideFwd);
		if (!hasBeenRidden) {
			hasBeenRidden = true;
			if (rocketRides > 3) downpull += 0.25f * (rocketRides - 3);
		}
	}

	/** The rider's Move input: x right, y forward (-1..1). */
	/** Grenade.LateUpdate's turn of a ridden rocket this step: input.y x 165 deg/s about its right, input.x about its up. */
	private void turnRide() {
		double pitch = Math.toRadians(steerY * 165 * DT), yaw = Math.toRadians(steerX * 165 * DT);
		Vec3d right = rideUp.crossProduct(rideFwd).normalize().multiply(-1);
		if (pitch != 0) {
			Vec3d f = rideFwd.multiply(Math.cos(pitch)).subtract(rideUp.multiply(Math.sin(pitch)));
			rideUp = rideUp.multiply(Math.cos(pitch)).add(rideFwd.multiply(Math.sin(pitch))).normalize();
			rideFwd = f.normalize();
		}
		if (yaw != 0) rideFwd = rideFwd.multiply(Math.cos(yaw)).add(right.multiply(Math.sin(yaw))).normalize();
	}

	public void steer(double x, double y) {
		steerX = Math.max(-1, Math.min(1, x));
		steerY = Math.max(-1, Math.min(1, y));
	}
	public float age;
	/** Nail.hit: the nail touched the environment; from then on it only tumbles about (no damage) until RemoveTime. */
	private boolean nailHit, nailRest;
	private float nailRemoveIn;
	/** Nail.magnets: the magnets whose field the nail has entered, in that order (entity ids). */
	private final List<Integer> magnets = new java.util.ArrayList<>();
	/** A held nail's true velocity (blocks/s) between ticks, its fixed steps not yet run, and whether a magnet let it go. */
	private Vec3d magVel = Vec3d.ZERO;
	private double magAcc;
	private boolean released;
	/** A held nail that has come to rest on its magnet. */
	private boolean heldStill;
	/** How far a held nail's circle has closed in (seconds held, ground scrapes counting extra). */
	private double heldTime;
	/** A nail on the pole: the magnet, where round it (radians), along it and how far out, and its time there. */
	private int clingMagnet = -1;
	/** The magnet a held nail is going to now (it counts toward that one's load). */
	private int targetMagnet = -1;
	private double clingPhi, clingZ, clingRho, clingT;
	/** A stuck magnet's forward (tip into the surface). */
	private Vec3d magnetFwd;
	public float damage;
	public int bounces;
	public float stuckFor;          // seconds left stuck in an enemy (nails: 5)
	public float drillHitsLeft;     // Screwdriver
	public float drillCooldown;
	public float magnetHealth = 3;  // Magnet
	public float timeFrozen;        // Rocket
	public boolean levelledUp;      // Rocket frozen ≥ 1 s → super explosion
	public float charge;            // Zapper nail charge, cannonball charge
	public float hitAmount;         // sawblades: Nail.hitAmount (2.9 / 20.9)
	/** The Sawed-On's launched chainsaw (Chainsaw.cs): a punch sends it out again (chainsaw juggle). */
	public boolean chainsaw;
	// Chainsaw.cs state: hitAmount 1 flies through enemies once each; 2+ stops in them and saws; 3 is heated
	public int sawHitAmount = 1;
	private boolean sawStopped, sawInPlayer;
	private int sawTarget = -1, sawCurrentHits;
	private float sawMultiHitCooldown, sawSameEnemyCooldown, sawIgnorePlayer, sawBlockedTimer, sawPlayerHitTimer;
	private Vec3d sawOriginalVel = Vec3d.ZERO;
	public int multiHit = 1;        // sawblades: hits allowed on one enemy
	private final java.util.Map<Integer, Integer> sawHits = new java.util.HashMap<>();
	/** A sawblade's speed (blocks a tick) and its magnetRotationDirection (0 = not yet picked). */
	private double sawSpeed;
	private int sawSpin;
	/** Steered round magnets: the current 0.008 s step's direction and how far into it the saw is (s). */
	private Vec3d sawDir = Vec3d.ZERO, sawRingEnd = Vec3d.ZERO;
	private double sawPhase;
	private int sawSteerTicks;
	/** Client: the last server state of a steered saw, and the server tick it is drawn at now (runs on ahead of it). */
	private Vec3d ringBase, ringBaseDir;
	private double ringBasePhase;
	private int ringBaseTick = Integer.MIN_VALUE;
	/** Client: the server tick a steered saw is drawn at (fractional: eased toward the newest state, never held). */
	private double ringShowTick;
	/** The sawblade was circling a magnet (Nail.MagnetRelease: it goes 5 s after the last magnet lets it go). */
	private boolean sawCircled;
	/** This tick the saw is steered round its ring (held, not punched out): walls do not stop it. */
	private boolean sawOnRing;
	/** The ring saw is inside a wall (it costs a hit on the way in only). */
	private boolean sawInWall;
	/** The magnet it circled and where that was (Magnet.Launch sends it at that point when the magnet goes off). */
	private int sawMagnetId = -1;
	private Vec3d sawMagnetAt = Vec3d.ZERO;
	/** Nail.punched / punchedTimer / punchDistance: a punched saw flies free of its magnet until it is further out. */
	private float sawPunchedUntil = -1;
	/** Nail.punchable: set by a wall bounce (and by a magnet catching it). */
	private boolean sawBounced;
	/** Nail.MagnetRelease: RemoveTime 5 x 3 s after the last magnet lets the saw go (the age it goes at; -1: none). */
	private float sawRemoveAt = -1;
	/** Nail.MagnetRelease on a ring punch: this magnet is left alone until the saw is out of its field. */
	private int sawIgnoreMagnet = -1;
	/** Nail.previouslyHitEnemy: a punched saw loses its double damage on reaching a second enemy. */
	private int sawPrevEnemy = -1;
	private double sawPunchDist;
	private Vec3d stuckOffset = Vec3d.ZERO;
	private int durability = 2;     // Cannonball
	/**
	 * Cannonball.cs (the S.R.S. Cannon's, Cannonball_0: damage 5, speed 150, maxBounces 1, durability 2, physics,
	 * canBreakBeforeLaunched): launched = parried (flying straight at speed, no gravity); the enemies it has hit; the
	 * time since its last bounce (instaBreakDefence: for 1 s an enemy it touches is only noted).
	 */
	private boolean cbLaunched, cbHasBounced;
	private float cbDamage = 5, cbDefence = 1;
	private final java.util.Set<Integer> cbHitEnemies = new java.util.HashSet<>();
	/** Rockets of this owner are frozen while this is true (WeaponCharges.rocketFrozen is per player). */
	public static final java.util.Set<UUID> frozenOwners = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public UKProjectileEntity(EntityType<? extends UKProjectileEntity> type, World world) {
		super(type, world);
	}

	public static UKProjectileEntity spawn(ServerWorld world, ServerPlayerEntity shooter, ProjectileKind kind, Vec3d pos, Vec3d dirUnit, double speedUnits) {
		return spawn(world, shooter, kind, pos, dirUnit, speedUnits, null);
	}

	/**
	 * @param before set up before it enters the world: the spawn packet goes out right then, so anything set afterwards
	 *               reaches the client a tick late (a rocket fired while frozen was run on a tick there first)
	 */
	public static UKProjectileEntity spawn(ServerWorld world, ServerPlayerEntity shooter, ProjectileKind kind, Vec3d pos, Vec3d dirUnit, double speedUnits, java.util.function.Consumer<UKProjectileEntity> before) {
		UKProjectileEntity e = new UKProjectileEntity(UKEntities.PROJECTILE.get(), world);
		e.setKind(kind);
		e.owner = shooter.getUuid();
		e.dataTracker.set(OWNER_ID, shooter.getId());
		e.damage = kind.damage;
		if (kind == ProjectileKind.SAWBLADE) e.hitAmount = 2.9f;
		if (kind == ProjectileKind.SAWBLADE_FODDER) e.hitAmount = 3.9f;
		if (kind == ProjectileKind.SAWBLADE_HEATED) { e.hitAmount = 20.9f; e.multiHit = 3; }
		e.setPosition(pos);
		e.setVelocity(dirUnit.normalize().multiply(speedUnits * Damage.SCALE_BLOCKS * DT));
		e.face(e.getVelocity());
		if (kind.sawblade()) e.sawSpeed = e.getVelocity().length();
		if (before != null) before.accept(e);
		world.spawnEntity(e);
		return e;
	}

	@Override
	protected void initDataTracker() {
		dataTracker.startTracking(KIND, 0);
		dataTracker.startTracking(STUCK, false);
		dataTracker.startTracking(FROZEN, false);
		dataTracker.startTracking(LEVELLED, false);
		dataTracker.startTracking(STUCK_IN, -1);
		dataTracker.startTracking(BOOSTED, false);
		dataTracker.startTracking(OWNER_ID, -1);
		dataTracker.startTracking(MAGNETIZED, false);
		dataTracker.startTracking(FUSE_AT, -1L);
		dataTracker.startTracking(MAG_TARGET, -1);
		dataTracker.startTracking(MAG_VEL, new org.joml.Vector3f());
		dataTracker.startTracking(MAG_LOAD, 0f);
		dataTracker.startTracking(MAG_ANGLE, 85f);
		dataTracker.startTracking(SAW_SPIN, 0);
		dataTracker.startTracking(SAW_DIR, new org.joml.Vector3f());
		dataTracker.startTracking(SAW_PHASE, 0f);
		dataTracker.startTracking(SAW_TICK, 0);
		dataTracker.startTracking(SAW_POS, new org.joml.Vector3f());
		dataTracker.startTracking(MAG_C, new org.joml.Vector3f());
		dataTracker.startTracking(SAW_VAR, -1);
		dataTracker.startTracking(OFF_HAND, false);
	}

	public ProjectileKind kind() {
		int k = dataTracker.get(KIND);
		return ProjectileKind.values()[Math.min(k, ProjectileKind.values().length - 1)];
	}

	public void setKind(ProjectileKind k) {
		dataTracker.set(KIND, k.ordinal());
	}

	public int ownerId() {
		return dataTracker.get(OWNER_ID);
	}

	public boolean isStuck() {
		return dataTracker.get(STUCK);
	}

	public boolean isMagnetized() {
		return dataTracker.get(MAGNETIZED);
	}

	public int sawVariation() {
		return dataTracker.get(SAW_VAR);
	}

	public void setSawVariation(int v) {
		dataTracker.set(SAW_VAR, v);
	}

	public boolean offHand() {
		return dataTracker.get(OFF_HAND);
	}

	public void setOffHand(boolean off) {
		dataTracker.set(OFF_HAND, off);
	}

	/**
	 * Where a sawblade steered round magnets is `tickDelta` into this tick (client): the server's last state run on
	 * through the same 0.008 s steps (ringAdvance) about the owner's magnets as the client has them, so the path drawn is
	 * the one the server takes, step for step (a lap is about six ticks: straight lines between ticks drew a hexagon,
	 * and a client running its own steps out of phase with the server's snapped back at every update). Null when the
	 * saw is not steered.
	 */
	public Vec3d ringPos(float tickDelta) {
		if (ringBase == null) return null;
		Vec3d p = ringSmoothAt(ringShowTick + tickDelta);
		if (p != null) ringLastShown = p;
		return p;
	}

	/**
	 * ringPathAt plus what is left of the last correction: when a server state disagrees with where the saw was drawn
	 * (a magnet stuck or went off a tick apart on the two sides: 2 to 7 blocks), the saw glides onto the new path over
	 * proj.ringBlend ticks instead of jumping.
	 */
	public Vec3d ringSmoothAt(double show) {
		Vec3d p = ringPathAt(show);
		if (p == null) return null;
		return p.add(ringOffset.multiply(blendLeft(show - ringOffsetAt)));
	}

	private static double blendLeft(double ticksSince) {
		double n = Math.max(0.01, com.ultracraft.weapon.ServerTune.get("proj.ringBlend", 3));
		return Math.max(0, Math.min(1, 1 - ticksSince / n));
	}

	/** Client: a saw that just stopped being steered glides from where it was last drawn onto its plain path. */
	public Vec3d ringExitOffset(float tickDelta) {
		if (ringExitOffset == null) return null;
		double left = blendLeft(ringExitAge + tickDelta);
		return left <= 0 ? null : ringExitOffset.multiply(left);
	}

	private Vec3d ringOffset = Vec3d.ZERO, ringLastShown, ringExitOffset;
	private double ringOffsetAt;
	private int ringExitAge;

	/** The server tick a steered saw is drawn at, `tickDelta` into this client tick (NaN when it is not steered). */
	public double ringShow(float tickDelta) {
		return ringBase == null || !kind().sawblade() || dataTracker.get(SAW_SPIN) == 0 ? Double.NaN : ringShowTick + tickDelta;
	}

	/**
	 * Where a steered saw was or is at server tick `show` (fractional): from the latest server state at or before it, run
	 * on through ringAdvance. Null outside the states kept (the last 16 ticks).
	 */
	public Vec3d ringPathAt(double show) {
		if (!kind().sawblade() || dataTracker.get(SAW_SPIN) == 0) return null;
		double[] from = null;
		for (java.util.Iterator<double[]> it = ringHistory.descendingIterator(); it.hasNext(); ) {
			double[] h = it.next();
			if (h[0] <= show) { from = h; break; }
		}
		if (from == null) return null;
		return ringAdvance(new Vec3d(from[1], from[2], from[3]), new Vec3d(from[4], from[5], from[6]), from[7], (show - from[0]) * DT, clientMagnets(), dataTracker.get(SAW_SPIN), kind().speed * Damage.SCALE_BLOCKS)[0];
	}

	/** Client: the server states of a steered saw as they came in ({tick, pos, dir, phase}), the newest last. */
	private final java.util.ArrayDeque<double[]> ringHistory = new java.util.ArrayDeque<>();

	/** Client: centres of the stuck magnets of this saw's owner, looked up once a frame. */
	private List<Vec3d> clientMagnets() {
		World w = getWorld();
		long key = w.getTime() * 31 + ownerId();
		if (key == clientMagnetsKey && clientMagnetsAt == System.nanoTime() / 2_000_000) return clientMagnetsList;
		List<Vec3d> list = new java.util.ArrayList<>();
		int owner = ownerId();
		double r = sawFieldRadius() * 2 + 4;
		for (UKProjectileEntity m : w.getEntitiesByClass(UKProjectileEntity.class, getBoundingBox().expand(r), e -> e.kind() == ProjectileKind.MAGNET && e.isStuck() && e.isAlive() && e.ownerId() == owner))
			list.add(m.magnetCenter());
		clientMagnetsKey = key;
		clientMagnetsAt = System.nanoTime() / 2_000_000;
		clientMagnetsList = list;
		return list;
	}

	private static long clientMagnetsKey = Long.MIN_VALUE, clientMagnetsAt;
	private static List<Vec3d> clientMagnetsList = List.of();

	/**
	 * How far from a magnet's centre a sawblade is caught and steered: the magnet's trigger Sphere, radius 25 u (Harpoon
	 * prefab, scale 0.1 x 10); live as magnet.sawRadius (u), smaller to catch only saws close to it.
	 */
	public static double sawFieldRadius() {
		return com.ultracraft.weapon.ServerTune.get("magnet.sawRadius", 25) * Damage.SCALE_BLOCKS;
	}

	/** The 0.008 s physics step of Nail.FixedUpdate. */
	private static final double STEP = 0.008;

	/**
	 * Nail.FixedUpdate on a sawblade held by magnets, run on for `dt` seconds from `pos`, moving along `dir` and `phase`
	 * seconds into the current step: at each step the target is the nearest magnet whose field it is in
	 * (GetTargetMagnet), and the velocity becomes the way to it turned 85 degrees about the vertical
	 * (Quaternion.Euler(0, 85 * magnetRotationDirection, 0)), at the saw's speed (blocks / s); between steps it goes
	 * straight. Turned about the vertical only, the up / down part of the way stays, so the saws come to the magnet's
	 * height and circle level there; the 5 degrees in against the step length settle them on a wide ring (radius =
	 * step / (2 cos 85), about 9 u at 200 u/s). Two magnets near each other: the nearest one changes on the way round
	 * and the rings run together into one loop round both, as in the game. Returns {position, direction, (phase, 0, 0)}.
	 */
	static Vec3d[] ringAdvance(Vec3d pos, Vec3d dir, double phase, double dt, List<Vec3d> centres, int spin, double speed) {
		double rr = sawFieldRadius() * sawFieldRadius();
		double left = dt;
		for (int guard = 0; guard < 4096 && left > 1e-12; guard++) {
			double until = STEP - phase;
			if (left < until) {
				pos = pos.add(dir.multiply(speed * left));
				phase += left;
				break;
			}
			pos = pos.add(dir.multiply(speed * until));
			left -= until;
			phase = 0;
			Vec3d c = null;
			double best = rr;
			for (Vec3d m : centres) {
				double d = m.squaredDistanceTo(pos);
				if (d <= best) { best = d; c = m; }
			}
			if (c == null) continue;
			Vec3d to = c.subtract(pos);
			if (to.lengthSquared() < 1e-9) continue;
			// GetTargetMagnet: magnetRotationDirection from the way it flies now, every step: -1 with the magnet on the
			// side of (v.z, v.y, -v.x), else 1, so it keeps turning round the magnet on the side it is on, and on to the
			// next magnet without a turn-about (a set way round per player made it double back where it changed magnets)
			Vec3d side = new Vec3d(dir.z, dir.y, -dir.x);
			int way = side.lengthSquared() > 1e-12 && to.dotProduct(side.normalize()) > 0 ? -1 : 1;
			double a = Math.toRadians(85 * way), cs = Math.cos(a), sn = Math.sin(a);
			to = to.normalize();
			dir = new Vec3d(to.x * cs + to.z * sn, to.y, -to.x * sn + to.z * cs);
		}
		return new Vec3d[] {pos, dir, new Vec3d(phase, 0, 0)};
	}

	/** A stuck magnet's world time of sticking, or -1. */
	public long fuseAt() {
		return dataTracker.get(FUSE_AT);
	}

	/** A magnet's field centre ("Sphere"), from its root (the tail end, this entity's position) along its forward. */
	public Vec3d magnetCenter() {
		if (getWorld().isClient && isStuck()) {
			org.joml.Vector3f c = dataTracker.get(MAG_C);
			if (c.lengthSquared() > 0) return new Vec3d(c.x, c.y, c.z);
		}
		return MagnetField.center(getPos(), magnetFwd != null ? magnetFwd : MagnetField.forward(getYaw(), getPitch()));
	}

	/**
	 * Where a held nail is `tickDelta` into this tick (client): the last server state run on through MagnetField's steps
	 * about its target magnet. Null if that is not known here.
	 */
	public Vec3d[] predictHeld(float tickDelta) {
		if (!(getWorld().getEntityById(dataTracker.get(MAG_TARGET)) instanceof UKProjectileEntity m) || !m.isStuck()) return null;
		List<Vec3d> held = List.of(m.magnetCenter());
		org.joml.Vector3f mv = dataTracker.get(MAG_VEL);
		if (mv.lengthSquared() < 1e-12) return null;
		Vec3d p = getPos(), v = new Vec3d(mv.x, mv.y, mv.z);
		double t = tickDelta * DT, pull = ServerTune.get("magnet.pull", 0), grav = MagnetField.GRAVITY * ServerTune.get("magnet.gravity", 0);
		Vec3d axis = MagnetField.axisFor(getId(), ServerTune.get("magnet.tilt", 60));
		double angle = dataTracker.get(MAG_ANGLE);
		while (t >= MagnetField.FIXED_DT) {
			Vec3d[] r = MagnetField.step(p, v, held, 0, grav, pull, axis, 0, angle);
			p = r[0];
			v = r[1];
			t -= MagnetField.FIXED_DT;
		}
		return new Vec3d[] {p.add(v.multiply(t)), v};
	}

	/** Grenade.Explode: a rocket going off while frozen is 1.5 times the size (super + big: 2.5 is not ours). */
	private float rocketBlastScale() {
		return isFrozen() ? 1.5f : 1f;
	}

	public boolean isLevelledUp() {
		return dataTracker.get(LEVELLED);
	}

	public boolean isFrozen() {
		return dataTracker.get(FROZEN);
	}

	/** A rocket fired while its owner's rockets are frozen starts frozen (the client never runs it on). */
	public void setFrozenAtSpawn(boolean frozen) {
		dataTracker.set(FROZEN, frozen);
	}

	public boolean isBoosted() {
		return dataTracker.get(BOOSTED);
	}

	public boolean ownedBy(ServerPlayerEntity p) {
		return owner != null && owner.equals(p.getUuid());
	}

	// ------------------------------------------------------------------ Chainsaw.cs

	private static Vec3d moveTowards(Vec3d from, Vec3d to, double maxDelta) {
		Vec3d d = to.subtract(from);
		double len = d.length();
		return len <= maxDelta || len < 1e-9 ? to : from.add(d.multiply(maxDelta / len));
	}

	/**
	 * Chainsaw.FixedUpdate / Update: the saw is pulled back toward the gun (100 u/s cap, harder the further it is),
	 * bounces off walls at half speed, is caught by the player when it comes within 1 u (and goes away 0.25 s later
	 * unless punched), turns into a free sawblade when a wall has blocked the chain for 0.25 s, and hits enemies:
	 * hitAmount 1 cuts each once in passing, 2+ stops in the enemy and saws it (currentHitAmount hits, 0.05 s apart).
	 */
	private void tickChainsaw(ServerWorld world) {
		ServerPlayerEntity sh = shooter();
		if (sh == null || !sh.isAlive()) { discard(); return; }
		double s = Damage.SCALE_BLOCKS;
		sawIgnorePlayer += DT;
		// attachedTransform = PlayerTracker.GetTarget(): the player's body centre (the saw comes back to the chest, below the view)
		Vec3d anchor = sh.getBoundingBox().getCenter();
		if (sawInPlayer) {
			setPosition(sh.getPos().add(0, 0.9, 0));
			setVelocity(Vec3d.ZERO);
			sawPlayerHitTimer += DT;
			if (sawPlayerHitTimer >= 0.25f) discard();
			return;
		}
		if (sawStopped) {
			setVelocity(Vec3d.ZERO);
			Entity host = sawTarget >= 0 ? world.getEntityById(sawTarget) : null;
			LivingEntity t = host instanceof LivingEntity le && le.isAlive() ? le : null;
			if (t != null) setPosition(t.getPos().add(stuckOffset));
			sawMultiHitCooldown -= DT;
			if (sawMultiHitCooldown <= 0) {
				if (t != null && sawCurrentHits > 0) { sawCurrentHits--; sawDamage(world, sh, t); }
				if (t == null || !t.isAlive() || sawCurrentHits <= 0) {
					sawStopped = false;
					dataTracker.set(STUCK, false);
					double speed = Math.max(sawOriginalVel.length(), 35 * s * DT);
					setVelocity(sawOriginalVel.lengthSquared() > 1e-10 ? sawOriginalVel.normalize().multiply(speed) : anchor.subtract(getPos()).normalize().multiply(speed));
					return;
				}
				sawMultiHitCooldown = 0.05f;
			}
			return;
		}
		if (sawSameEnemyCooldown > 0) { sawSameEnemyCooldown -= DT; if (sawSameEnemyCooldown <= 0) sawTarget = -1; }
		// Chainsaw.FixedUpdate at the game's 50 Hz (sub-steps inside the 20 Hz tick: the spring overshot at 0.05 s steps).
		// MoveTowards rates are u/s²: dist×10 while flying out, max(10, dist)×10 once retracting (heading within 60° of
		// the gun), capped at 100 u/s; caught within 1 u after 0.66 s
		int subs = Math.max(1, (int) Math.round(DT / 0.02));
		double h = DT / subs;
		Vec3d vs = getVelocity().multiply(1 / DT); // blocks/s
		Vec3d pos = getPos();
		Vec3d n = new Vec3d(0, 1, 0);
		double cap = 100 * s;
		for (int i = 0; i < subs; i++) {
			Vec3d toGun = anchor.subtract(pos);
			double dist = toGun.length() / s; // in u
			n = dist > 1e-6 ? toGun.normalize() : new Vec3d(0, 1, 0);
			if (sawIgnorePlayer > 0.66f && dist < 1) { touchPlayer(sh); return; }
			boolean retracting = vs.lengthSquared() > 1e-10 && vs.normalize().dotProduct(n) >= 0.5;
			if (!retracting) vs = moveTowards(vs, n.multiply(cap), dist * 10 * s * h);
			else vs = n.multiply(Math.min(cap, vs.length() + Math.max(10, dist) * 10 * s * h));
			pos = pos.add(vs.multiply(h));
		}
		Vec3d v = pos.subtract(getPos()); // this tick's path (blocks); the velocity carried on is vs × DT
		Vec3d vEnd = vs.multiply(DT);
		// the chain blocked by the world for 0.25 s snaps: the saw flies on as a sawblade
		BlockHitResult los = world.raycast(new RaycastContext(getPos(), anchor, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		if (los.getType() != HitResult.Type.MISS) sawBlockedTimer += DT; else sawBlockedTimer = 0;
		if (sawBlockedTimer >= 0.25f) { turnIntoSawblade(world, sh, v); return; }
		Vec3d start = getPos(), end = start.add(v);
		double radius = ProjectileKind.CHAINSAW.scale * 0.5 * s + 0.1;
		LivingEntity hitEnt = null;
		Vec3d hitPoint = null;
		double best = Double.MAX_VALUE;
		for (Entity e : world.getOtherEntities(this, new Box(start, end).expand(radius + 0.5), ent -> ent instanceof LivingEntity && ent.isAlive() && ent != sh)) {
			Optional<Vec3d> hp = e.getBoundingBox().expand(radius).raycast(start, end);
			if (hp.isEmpty() && e.getBoundingBox().expand(radius).contains(start)) hp = Optional.of(start);
			if (hp.isPresent() && hp.get().squaredDistanceTo(start) < best) { best = hp.get().squaredDistanceTo(start); hitEnt = (LivingEntity) e; hitPoint = hp.get(); }
		}
		BlockHitResult block = world.raycast(com.ultracraft.weapon.Plants.ctx(start, end, this));
		// (through the plants on the way: they break - Plants)
		com.ultracraft.weapon.Plants.shootStep(world, sh, start, end, block, hitEnt != null ? hitPoint : null);
		boolean blockHit = block.getType() != HitResult.Type.MISS && (hitEnt == null || block.getPos().squaredDistanceTo(start) < best);
		if (blockHit) {
			Vec3d normal = Vec3d.of(block.getSide().getVector());
			Vec3d dir = v.lengthSquared() > 1e-10 ? v.normalize() : n.multiply(-1);
			Vec3d refl = dir.subtract(normal.multiply(2 * dir.dotProduct(normal)));
			setPosition(block.getPos().add(normal.multiply(0.05)));
			setVelocity(refl.multiply(vEnd.length() / 2));
			face(getVelocity());
			Net.toNear(world, block.getPos(), new HitPayload(HitPayload.WALL_HIT, HitPayload.SAWBLADE, 1, block.getPos()));
			sawIgnorePlayer = 1f; // the game sets ignorePlayerTimer = 1 here: the player may catch it right after a bounce
			return;
		}
		if (hitEnt != null) {
			touchEnemy(world, sh, hitEnt, hitPoint, vEnd);
			if (sawStopped) return;
		}
		setVelocity(vEnd);
		setPosition(end);
		face(vEnd);
	}

	private void touchEnemy(ServerWorld world, ServerPlayerEntity sh, LivingEntity t, Vec3d point, Vec3d v) {
		if (sawHitAmount > 1) {
			if (sawSameEnemyCooldown > 0 && sawTarget == t.getId()) return;
			sawStopped = true;
			dataTracker.set(STUCK, true);
			sawCurrentHits = sawHitAmount;
			sawTarget = t.getId();
			stuckOffset = point.subtract(t.getPos());
			sawOriginalVel = v;
			sawSameEnemyCooldown = 0.25f;
			sawMultiHitCooldown = 0;
			setPosition(point);
			setVelocity(Vec3d.ZERO);
		} else {
			// Chainsaw.HitEnemy: once per enemy per flight (hitLimbs), the saw flies on
			if (sawHits.containsKey(t.getId())) return;
			sawHits.put(t.getId(), 1);
			sawDamage(world, sh, t);
		}
	}

	private void sawDamage(ServerWorld world, ServerPlayerEntity sh, LivingEntity t) {
		boolean wasAlive = t.isAlive();
		Vec3d dir = getVelocity().lengthSquared() > 1e-10 ? getVelocity().normalize() : t.getPos().subtract(sh.getPos()).normalize();
		Damage.deal(sh, t, damage, dir, 2000f, com.ultracraft.net.HitPayload.SAW);
		if (sawHitAmount >= 3) t.setOnFireFor(2);
		boolean killed = wasAlive && !t.isAlive();
		Net.toNear(world, t.getPos(), new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, HitPayload.SAW, 0, Damage.flags(t), t.getPos()));
	}

	private void touchPlayer(ServerPlayerEntity sh) {
		sawInPlayer = true;
		sawStopped = true;
		dataTracker.set(STUCK, true);
		sawOriginalVel = getVelocity();
		sawPlayerHitTimer = 0;
		setVelocity(Vec3d.ZERO);
		setPosition(sh.getPos().add(0, 0.9, 0));
	}

	private void turnIntoSawblade(ServerWorld world, ServerPlayerEntity sh, Vec3d v) {
		Vec3d dir = v.lengthSquared() > 1e-10 ? v.normalize() : sh.getRotationVec(1f);
		UKProjectileEntity nail = spawn(world, sh, sawHitAmount >= 3 ? ProjectileKind.SAWBLADE_HEATED : ProjectileKind.SAWBLADE, getPos(), dir, 105);
		nail.damage = damage;
		Net.toNear(world, getPos(), new HitPayload(HitPayload.WALL_HIT, HitPayload.SAWBLADE, 3, getPos()));
		discard();
	}

	/** Chainsaw.inPlayer: caught back at the thrower (the 0.25 s in which a punch still sends it out again). */
	public boolean sawCaught() {
		return sawInPlayer;
	}

	/** Chainsaw.GetPunched + Punch.cs: out again from in front of the camera at 105 u/s, one more hitAmount (3 = heated). */
	public void getPunched(Vec3d from, Vec3d toward) {
		sawInPlayer = false;
		sawStopped = false;
		dataTracker.set(STUCK, false);
		sawPlayerHitTimer = 0;
		sawIgnorePlayer = 0;
		sawSameEnemyCooldown = 0;
		sawTarget = -1;
		sawHits.clear();
		if (sawHitAmount < 3) { sawHitAmount++; if (sawHitAmount == 3) dataTracker.set(BOOSTED, true); }
		setPosition(from);
		setVelocity(toward.normalize().multiply(105 * Damage.SCALE_BLOCKS * DT));
		face(getVelocity());
		age = 0;
	}

	/** Chainsaw.GetPunched: out again from in front of the camera at 105 u/s, fresh. */
	public void relaunch(Vec3d pos, Vec3d dir, double speedUnits) {
		setPosition(pos);
		setVelocity(dir.normalize().multiply(speedUnits * Damage.SCALE_BLOCKS * DT));
		face(getVelocity());
		age = 0;
		sawHits.clear();
		dataTracker.set(STUCK, false);
		stuckFor = 0;
	}

	/** Projectile boost: the pellet's own damage goes, its explosion on hit is the damage. */
	/** Punch.ParryProjectile on our own pellet: boosted, damage 0, twice the speed, turned to `target` (LookAt). */
	public void boost(Vec3d target) {
		dataTracker.set(BOOSTED, true);
		damage = 0;
		double speed = getVelocity().length() * 2;
		Vec3d dir = target.subtract(getPos());
		if (dir.lengthSquared() < 1e-6) dir = getVelocity();
		dir = dir.normalize();
		setVelocity(dir.multiply(speed));
		velocityModified = true;
		face(dir);
	}

	private void face(Vec3d v) {
		if (v.lengthSquared() < 1e-9) return;
		double h = Math.sqrt(v.x * v.x + v.z * v.z);
		setYaw((float) Math.toDegrees(MathHelper.atan2(v.x, v.z)));
		setPitch((float) Math.toDegrees(MathHelper.atan2(v.y, h)));
		prevYaw = getYaw();
		prevPitch = getPitch();
	}

	private ServerPlayerEntity shooter() {
		return owner == null || getWorld().isClient ? null : (ServerPlayerEntity) ((ServerWorld) getWorld()).getPlayerByUuid(owner);
	}

	@Override
	public void tick() {
		super.tick();
		age += DT;
		ProjectileKind k = kind();
		if (getWorld().isClient) {
			// the chained saw changes velocity every tick on the server (pulled back to the gun): extrapolating it with a
			// stale velocity sent it 100 blocks off on the client. It follows the server's position updates instead.
			if (k == ProjectileKind.CHAINSAW) return;
			// a saw steered round magnets: take the newest server state as the base it is drawn from (ringPos); the tick
			// it is drawn at goes on by one a client tick, kept 0 to 3 ticks ahead of the newest state
			if (k.sawblade() && dataTracker.get(SAW_SPIN) != 0) {
				int st = dataTracker.get(SAW_TICK);
				ringShowTick++;
				if (st != ringBaseTick) {
					// (debug.weapons: how far the server's new state is from where the client had it run on to)
					if (Damage.debug && ringBase != null) {
						Vec3d pred = ringAdvance(ringBase, ringBaseDir, ringBasePhase, (st - ringBaseTick) * DT, clientMagnets(), dataTracker.get(SAW_SPIN), k.speed * Damage.SCALE_BLOCKS)[0];
						org.joml.Vector3f sp0 = dataTracker.get(SAW_POS);
						double err = pred.distanceTo(sp0.lengthSquared() > 0 ? new Vec3d(sp0.x, sp0.y, sp0.z) : getPos());
						if (err > 0.05) com.ultracraft.UltraCraft.LOG.info("[ring] saw {} st {} (+{}) show {} err {} mags {} pos {}", getId(), st, st - ringBaseTick, ringShowTick, String.format("%.3f", err), clientMagnets().size(), getPos());
					}
					// where it is drawn now on the old states (with what is left of the last correction)
					Vec3d before = ringBase != null ? ringSmoothAt(ringShowTick) : null;
					// eased toward the newest state rather than held at a limit: a server a little slower or faster than 20 tps
					// (or a late packet) changed the saws' speed for a moment instead of stopping them and then jumping
					if (ringBase == null || Math.abs(ringShowTick - st) > 4) ringShowTick = st;
					else ringShowTick += (st - ringShowTick) * 0.25;
					ringBaseTick = st;
					org.joml.Vector3f sp = dataTracker.get(SAW_POS);
					ringBase = sp.lengthSquared() > 0 ? new Vec3d(sp.x, sp.y, sp.z) : getPos();
					org.joml.Vector3f d = dataTracker.get(SAW_DIR);
					ringBaseDir = new Vec3d(d.x, d.y, d.z);
					ringBasePhase = dataTracker.get(SAW_PHASE);
					// (a new run of steering starts a new history)
					if (!ringHistory.isEmpty() && ringHistory.peekLast()[0] >= st) ringHistory.clear();
					ringHistory.addLast(new double[] {st, ringBase.x, ringBase.y, ringBase.z, ringBaseDir.x, ringBaseDir.y, ringBaseDir.z, ringBasePhase});
					while (ringHistory.size() > 16) ringHistory.pollFirst();
					// on the new state: the difference is glided away (ringSmoothAt)
					Vec3d after = before != null ? ringPathAt(ringShowTick) : null;
					if (after != null) {
						ringOffset = before.subtract(after);
						ringOffsetAt = ringShowTick;
						if (ringOffset.lengthSquared() > 64) ringOffset = Vec3d.ZERO; // (a teleport-sized jump: no glide)
					}
					ringExitOffset = null;
				} else if (ringShowTick > ringBaseTick + 6) ringShowTick = ringBaseTick + 6;
				return;
			}
			// just left the ring: glide from where it was last drawn
			if (ringBase != null && ringLastShown != null && k.sawblade()) {
				ringExitOffset = ringLastShown.subtract(getPos());
				if (ringExitOffset.lengthSquared() > 64) ringExitOffset = null;
				ringExitAge = 0;
			} else if (ringExitOffset != null) ringExitAge++;
			ringBase = null;
			ringLastShown = null;
			ringOffset = Vec3d.ZERO;
			ringHistory.clear();
			if (!isStuck() && !isFrozen() && !isMagnetized()) {
				Vec3d v = getVelocity();
				setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
			}
			return;
		}
		ServerWorld world = (ServerWorld) getWorld();
		boolean sawHeld = k.sawblade() && sawMagnet(world) != null;
		if (sawHeld) sawCircled = true;
		else if (sawCircled) {
			sawCircled = false;
			sawSpin = 0;
			// (not its flight life: a saw that circled longer than that went the moment its magnet did)
			sawRemoveAt = age + 15;
			// Magnet.Launch: its magnet went off: straight at where it was, at the saw's own speed (through it and on out)
			Entity gone = world.getEntityById(sawMagnetId);
			if (gone == null || !gone.isAlive()) {
				Vec3d to = sawMagnetAt.subtract(getPos());
				if (to.lengthSquared() > 1e-9) setVelocity(to.normalize().multiply(sawSpeed));
			}
			sawMagnetId = -1;
		}
		// Nail.MasterRemoveTime: every saw goes 60 s after it was fired, held or not
		if (k.sawblade() && age > 60) { discard(); return; }
		if (age > (k.sawblade() && sawRemoveAt >= 0 ? sawRemoveAt : k.life) && !sawHeld && !(k.nail() && (!magnets.isEmpty() || released))) { discard(); return; }
		if (k == ProjectileKind.CHAINSAW) { tickChainsaw(world); return; }
		if (isStuck()) { tickStuck(world, k); return; }
		// Nail.RemoveTime: gone 1 s after it first hit the environment
		// Harpoon.DestroyIfNotHit: a magnet that has not stuck in anything 5 s after the shot goes
		if (k == ProjectileKind.MAGNET && age > 5) { discard(); return; }
		// (MagnetCaught cancels RemoveTime; MagnetRelease with no magnet left starts it again, 5 s)
		if (k.nail() && (nailHit || released) && magnets.isEmpty() && (nailRemoveIn -= DT) <= 0) { discard(); return; }
		if (k.nail() && catchMagnets(world)) nailRest = false;
		if (k.nail() && nailRest) return;
		if (k.nail() && !magnets.isEmpty()) { heldTick(world, shooter()); return; }
		// Rockets: WeaponCharges.rocketFrozen of their owner (Grenade.FixedUpdate)
		if (k == ProjectileKind.ROCKET) {
			boolean frozen = owner != null && frozenOwners.contains(owner);
			if (frozen && !isFrozen()) {
				// the client draws a flying rocket a tick ahead of this one (it runs it on with its velocity): frozen here,
				// the rocket stopped a tick behind where it was seen, and on unfreezing it came back that far before going
				// on. It stops where the player saw it instead (short of anything solid on the way).
				Vec3d v = getVelocity(), to = getPos().add(v);
				BlockHitResult wall = world.raycast(com.ultracraft.weapon.Plants.ctx(getPos(), to, this));
				setPosition(wall.getType() == HitResult.Type.MISS ? to : wall.getPos().subtract(v.normalize().multiply(0.05)));
			}
			if (frozen != isFrozen()) dataTracker.set(FROZEN, frozen);
			if (frozen) {
				timeFrozen += DT;
				if (timeFrozen >= 1f && !levelledUp) {
					levelledUp = true;
					dataTracker.set(LEVELLED, true);
				}
				// Grenade.LateUpdate steers whenever V1 rides it, frozen or not: the frozen rocket turns where it stands,
				// and its kept speed turns with it (unfrozen, Grenade.FixedUpdate flies it along its forward)
				if (rider != null) {
					if (rideFwd == null) rideStart(rider, 0);
					turnRide();
					double keep = getVelocity().length();
					if (keep > 1e-9) setVelocity(rideFwd.multiply(keep));
					face(rideFwd);
				}
				return;
			}
		}
		Vec3d v = getVelocity();
		if (k == ProjectileKind.ROCKET && rider != null) {
			// Grenade.FixedUpdate while playerRiding: forward × 0.65 lerped toward straight down by downpull (+dt/4.5 per step)
			// Grenade.LateUpdate: transform.Rotate(input.y x 165 deg/s about its right (forward = nose down), input.x x 165
			// about its up) - the velocity follows the transform's forward, not the other way round
			if (rideFwd == null) rideStart(rider, 0);
			double speed = k.speed * Damage.SCALE_BLOCKS * DT;
			turnRide();
			Vec3d ride = rideFwd.multiply(speed * 0.65);
			// Grenade.FixedUpdate: with the camera under water (UnderwaterController.inWater) the rocket flies on at full
			// ride thrust and does not sink - downpull above 0 is put back to 0 and stops growing
			ServerPlayerEntity rp = world.getPlayerByUuid(rider) instanceof ServerPlayerEntity sp ? sp : null;
			if (rp != null && rp.isSubmergedInWater()) {
				if (downpull > 0) downpull = 0;
				v = ride;
			} else {
				Vec3d down = new Vec3d(0, -100 * Damage.SCALE_BLOCKS * DT, 0);
				double t = Math.max(0, Math.min(1, downpull));
				v = ride.multiply(1 - t).add(down.multiply(t));
				// (ride.downpullRate: 1 = the game's rate; a live multiplier if rides should sink sooner)
				downpull += (float) (DT / 4.5 * Math.max(1, 1 + v.normalize().y) * ServerTune.get("ride.downpullRate", 1));
			}
			setVelocity(v);
			face(rideFwd);
		}
		// nails are Rigidbodies with gravity (NailFodder / Nail / NailHeated: useGravity)
		if (k == ProjectileKind.CANNONBALL) {
			// Cannonball.FixedUpdate: launched it flies on at `speed` along its facing; otherwise a Rigidbody under the
			// project's gravity (0, -40, 0)
			cbDefence += DT;
			if (Damage.debug && ((int) (age / DT)) % 5 == 0) com.ultracraft.UltraCraft.LOG.info("[cannonball] {} at {} v {}", getId(), getPos(), v);
			if (cbLaunched) v = (v.lengthSquared() > 1e-12 ? v.normalize() : new Vec3d(0, 0, 1)).multiply(150 * Damage.SCALE_BLOCKS * DT);
			else v = v.add(0, -40 * Damage.SCALE_BLOCKS * DT * DT, 0);
		} else if (k == ProjectileKind.CORE) v = v.add(0, -40 * Damage.SCALE_BLOCKS * DT * DT, 0); // a Rigidbody under the project's (0, -40, 0)
		else if (k.gravity || k.nail()) v = v.add(0, -9.81 * Damage.SCALE_BLOCKS * DT * DT, 0);
		if (sawHeld) {
			// (punched: straight on until it is further from the magnet than when it was hit, then round again)
			boolean punchedOut = age < sawPunchedUntil && sawPunchDist > 0;
			if (punchedOut && getPos().distanceTo(sawMagnetAt) > sawPunchDist) { sawPunchDist = 0; punchedOut = false; }
			if (!punchedOut) v = sawCircle(world, v);
			sawOnRing = !punchedOut;
			syncRing(punchedOut ? 0 : sawSpin);
		} else if (k.sawblade()) { syncRing(0); sawOnRing = false; sawInWall = false; }
		Vec3d start = getPos();
		Vec3d end = start.add(v);
		// entities first (Projectile.Collided / Nail.TouchEnemy), then blocks
		LivingEntity hitEnt = null;
		Vec3d hitPoint = null;
		double best = Double.MAX_VALUE;
		double radius = k.scale * 0.5 * Damage.SCALE_BLOCKS + 0.05;
		if (isBoosted()) radius *= 4;
		ServerPlayerEntity sh = shooter();
		// Nail.OnCollisionEnter returns at once once `hit` is set: a nail that bounced off the environment hurts no one
		// (wide: a mob is also tested where it was over the last ticks, as the player saw it, which can be blocks away)
		// a punched pellet also goes off on a body still falling (Projectile.Collided: Explode before any dead check): the
		// shotgun's point-blank zone often kills the mob a tick before the punch, and the blast flew through it (user)
		boolean corpses = k == ProjectileKind.PELLET && isBoosted();
		if (!(k.nail() && nailHit)) for (Entity e : world.getOtherEntities(this, new Box(start, end).expand(radius + 8), ent -> ent instanceof LivingEntity && (ent.isAlive() || corpses) && ent != sh)) {
			// (a start inside the mob, pressed against V1, hits it there; a whiplashed mob is tested along its last step)
			Optional<Vec3d> p = com.ultracraft.weapon.Hitscan.hitPoint(e, radius, start, end);
			if (p.isPresent() && p.get().squaredDistanceTo(start) < best) {
				best = p.get().squaredDistanceTo(start);
				hitEnt = (LivingEntity) e;
				hitPoint = p.get();
			}
		}
		// (through the plants on the way: they break - Plants; the Firestarter's gasoline lands on them as before: user)
		BlockHitResult block = world.raycast(k == ProjectileKind.GASOLINE
			? new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)
			: com.ultracraft.weapon.Plants.ctx(start, end, this));
		if (k != ProjectileKind.GASOLINE) {
			var plants = com.ultracraft.weapon.Plants.shootStep(world, sh, start, end, block, hitEnt != null ? hitPoint : null);
			// a rocket or core through gasolined leaves lights the gasoline on them (user; in ULTRAKILL the leaves would
			// have stopped it, and its explosion - Grenade.Explode: TryIgniteAt - lit them)
			if (k == ProjectileKind.ROCKET || k == ProjectileKind.CORE)
				for (var pos : plants) com.ultracraft.weapon.Gasoline.tryIgniteAt(world, sh == null ? null : sh.getUuid(), Vec3d.ofCenter(pos), 3);
		}
		// the screwdriver sets off magnets it passes through, like the railcannon's beams (the player's choice: the game's
		// drill only breaks Breakables that are not precisionOnly, and the spike's is)
		if (k == ProjectileKind.SCREWDRIVER) {
			Vec3d segEnd = block.getType() != HitResult.Type.MISS ? block.getPos() : end;
			for (UKProjectileEntity m : world.getEntitiesByClass(UKProjectileEntity.class, new Box(start, segEnd).expand(radius + 3), e -> e.kind() == ProjectileKind.MAGNET && e.isAlive()))
				if (m.spikeCrossedBy(start, segEnd, radius)) m.breakMagnet(world);
		}
		// a Core Eject / hammer grenade in the way is knocked along (Grenade.Collision ignores projectiles, layer 14: what
		// moves it is the physics push of the nail, saw or rocket going through it)
		if (k.nail() || k.sawblade() || k == ProjectileKind.ROCKET) pushGrenades(world, start, end, radius, k);
		if (k == ProjectileKind.ROCKET) {
			// Grenade.Collision: a coin (layer 10, no enemy on it) is "something hit": the rocket explodes on it
			Vec3d coinAt = null;
			double coinBest = best;
			for (CoinEntity c : world.getEntitiesByClass(CoinEntity.class, new Box(start, end).expand(radius + 0.5), CoinEntity::isAlive)) {
				Optional<Vec3d> cp = c.getBoundingBox().expand(radius).raycast(start, end);
				if (c.getBoundingBox().expand(radius).contains(start)) cp = Optional.of(start);
				if (cp.isPresent() && cp.get().squaredDistanceTo(start) < coinBest) { coinBest = cp.get().squaredDistanceTo(start); coinAt = cp.get(); }
			}
			if (coinAt != null && (block.getType() == HitResult.Type.MISS || coinBest < block.getPos().squaredDistanceTo(start))) {
				Explosions.explode(world, sh, coinAt, (levelledUp ? Explosions.SUPER : Explosions.NORMAL).scaled(rocketBlastScale()), false, "rocket0", this);
				return;
			}
		}
		// an End crystal before any mob or wall (not living: the search above skips it) goes off and stops the shot; a
		// rocket explodes there
		if (sh != null) {
			Vec3d segEnd = block.getType() != HitResult.Type.MISS ? block.getPos() : end;
			for (var crystal : Damage.crystalsOn(world, start, segEnd, radius)) {
				if (crystal.squaredDistanceTo(start) > best + 4) break;
				Vec3d at = crystal.getBoundingBox().getCenter();
				Damage.hitCrystal(sh, crystal);
				if (k == ProjectileKind.ROCKET) { Explosions.explode(world, sh, at, (levelledUp ? Explosions.SUPER : Explosions.NORMAL).scaled(rocketBlastScale()), false, "rocket0", this); return; }
				discard();
				return;
			}
		}
		boolean blockHit = block.getType() != HitResult.Type.MISS && (hitEnt == null || block.getPos().squaredDistanceTo(start) < best);
		// a saw going round a magnet goes on through walls in its way (the user's account of the game: with the magnet in
		// a wall the ring cuts through it), a bounce's cost (0.1 while held) each time it goes in, breaking at none left;
		// steered back into the wall every step, the bounce had kept it there taking damage
		if (blockHit && k.sawblade() && sawOnRing) {
			if (!sawInWall) {
				Vec3d hp = block.getPos();
				if (hitAmount <= 0) { Net.toNear(world, hp, new HitPayload(HitPayload.WALL_HIT, HitPayload.SAWBLADE, 2, hp)); discard(); return; }
				hitAmount -= 0.1f;
				sawBounced = true;
				Net.toNear(world, hp, new HitPayload(HitPayload.WALL_HIT, HitPayload.SAWBLADE, 1, hp));
			}
			blockHit = false;
		}
		if (k.sawblade() && sawOnRing) {
			var bp = net.minecraft.util.math.BlockPos.ofFloored(end);
			sawInWall = !world.getBlockState(bp).getCollisionShape(world, bp).isEmpty();
		}
		if (blockHit) {
			onHitBlock(world, sh, k, block, v);
			return;
		}
		if (hitEnt != null) {
			onHitEntity(world, sh, k, hitEnt, hitPoint, v);
			return;
		}
		setVelocity(v);
		setPosition(end);
		// (a ridden rocket faces where it is steered, its velocity dipping below that with downpull)
		face(k == ProjectileKind.ROCKET && rider != null && rideFwd != null ? rideFwd : v);
	}

	// ------------------------------------------------------------------ Cannonball.cs

	/** Cannonball.Break: BreakParticleBig (its debris and sound) and gone. */
	private void cannonballBreak(ServerWorld world, Vec3d at) {
		if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[cannonball] {} breaks at {} age {}", getId(), at, String.format("%.2f", age));
		Net.toNear(world, at, new HitPayload(HitPayload.WALL_HIT, HitPayload.CANNONBALL, 0, at));
		discard();
	}

	/**
	 * Cannonball.Bounce: past maxBounces (1) it breaks; else off the enemy up by 0.15 of its speed and back 5 u/s,
	 * falling again, parryable, its durability back to 99, the bounce sound (CannonballHit).
	 */
	private void cannonballBounce(ServerWorld world, Vec3d v, Vec3d at) {
		if (bounces >= 1) { cannonballBreak(world, at); return; }
		cbDefence = 0;
		bounces++;
		durability = 99;
		cbHasBounced = true;
		cbLaunched = false;
		double speedU = v.length() / (Damage.SCALE_BLOCKS * DT);
		// (the game's 5 u/s back felt too strong here: live tune, by eye)
		Vec3d out = new Vec3d(0, speedU * 0.15, 0).add(v.normalize().multiply(-ServerTune.get("cannonball.bounceBack", 2))).multiply(Damage.SCALE_BLOCKS * DT);
		setPosition(at.subtract(v.normalize().multiply(0.2)));
		setVelocity(out);
		face(out);
		Net.toNear(world, at, new HitPayload(HitPayload.WALL_HIT, HitPayload.CANNONBALL, 1, at));
	}

	/** A cannonball that can be parried: not flying straight already (Cannonball.launchable stays true). */
	public boolean cannonballParryable() {
		return kind() == ProjectileKind.CANNONBALL && isAlive();
	}

	/**
	 * Punch.TryParryProjectile on a Cannonball, then Cannonball.Launch: within 10 u of the parry look target it is put
	 * where the camera's 5 u ray meets something (else 5 u ahead) facing the camera's way, otherwise it is turned to look
	 * at the target; launched (straight at speed 150, no gravity), its hits forgotten, +2 damage after a bounce.
	 */
	public void parryCannonball(Vec3d eye, Vec3d look, Vec3d target) {
		double S = Damage.SCALE_BLOCKS;
		Vec3d dir;
		if (getPos().distanceTo(target) < 10 * S) {
			Vec3d to = eye.add(look.multiply(5 * S));
			BlockHitResult wall = getWorld().raycast(new RaycastContext(eye, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
			setPosition(wall.getType() == HitResult.Type.MISS ? to : wall.getPos());
			dir = look;
		} else {
			dir = target.subtract(getPos()).normalize();
		}
		cbLaunched = true;
		cbHitEnemies.clear();
		cbDefence = 1;
		if (bounces == 1 && cbHasBounced) cbDamage += 2;
		bounces++;
		Vec3d v = dir.multiply(150 * S * DT);
		setVelocity(v);
		face(v);
	}

	// ------------------------------------------------------------------ Magnet.cs on sawblades

	/** Nail.punchable: a sawblade a magnet has caught can be punched. */
	public boolean sawPunchable() {
		return kind().sawblade() && (sawCircled || sawBounced);
	}

	/** The magnet whose ring this saw is on, or -1. */
	public int sawRingMagnet() {
		return kind().sawblade() && sawCircled ? sawMagnetId : -1;
	}

	/**
	 * Punch.ActiveFrame's second way, no saw in reach but a magnet with saws 6-12 u off: the saw is taken off the ring to
	 * in front of the camera and sent at the look target; heading away from the magnet it is let go of (MagnetRelease),
	 * else it comes round again once further out than it was.
	 */
	public void punchSawFromRing(Vec3d at, Vec3d target) {
		setPosition(at);
		prevX = at.x; prevY = at.y; prevZ = at.z;
		punchSaw(target);
		Vec3d v = getVelocity();
		if (v.lengthSquared() > 1e-12 && at.add(v.normalize()).distanceTo(sawMagnetAt) > at.distanceTo(sawMagnetAt)) {
			sawIgnoreMagnet = sawMagnetId;
			sawPunchDist = 0;
		}
	}

	/**
	 * Punch.ActiveFrame on a punchable sawblade: sent at the parry look target at its own speed, punched for 3 s, free
	 * of the magnet until it is further out than it was.
	 */
	public void punchSaw(Vec3d target) {
		Vec3d to = target.subtract(getPos());
		if (to.lengthSquared() < 1e-9) return;
		Vec3d v = to.normalize().multiply(sawSpeed);
		setVelocity(v);
		face(v);
		sawPunchedUntil = age + 3;
		sawPunchDist = sawCircled ? getPos().distanceTo(sawMagnetAt) : 0;
	}

	/** Nail.GetTargetMagnet for a sawblade: the nearest of its shooter's magnets whose field it is in, else null. */
	private UKProjectileEntity sawMagnet(ServerWorld world) {
		Vec3d p = getPos();
		// held: the magnet's whole 25 u field; not held yet: only within magnet.sawCatch (by eye, the user's call: a saw that
		// flew off and came back from far away was caught again by the next magnet, the game's field reaching 12.9 blocks)
		double r = sawCircled ? sawFieldRadius() : com.ultracraft.weapon.ServerTune.get("magnet.sawCatch", 14) * Damage.SCALE_BLOCKS, best = r * r;
		UKProjectileEntity t = null;
		for (UKProjectileEntity m : sawMagnets(world)) {
			double d = m.magnetCenter().squaredDistanceTo(p);
			if (m.getId() == sawIgnoreMagnet) {
				if (d > r * r) sawIgnoreMagnet = -1;
				continue;
			}
			if (d <= best) { best = d; t = m; }
		}
		return t;
	}

	/**
	 * Nail.FixedUpdate for a sawblade with magnets: this tick's 0.008 s steps (ringAdvance) about the owner's magnets.
	 * Returns this tick's move.
	 */
	private Vec3d sawCircle(ServerWorld world, Vec3d v) {
		UKProjectileEntity m = sawMagnet(world);
		if (m == null) return v;
		Vec3d c = m.magnetCenter();
		sawMagnetId = m.getId();
		sawMagnetAt = c;
		Vec3d pos = getPos();
		// (the way round is worked out every step from its flight, as the game does: ringAdvance; this only marks it steered)
		sawSpin = 1;
		// (newly steered, or again after a punch: from the way it flies, turned at once)
		if (!sawOnRing || sawDir.lengthSquared() < 1e-9) {
			if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[ring] srv saw {} (re)starts steering at {} age {}", getId(), pos, String.format("%.2f", age));
			sawDir = v.lengthSquared() > 1e-12 ? v.normalize() : new Vec3d(1, 0, 0);
			sawPhase = STEP;
		}
		List<Vec3d> centres = new java.util.ArrayList<>();
		for (UKProjectileEntity o : sawMagnets(world)) if (o.getId() != sawIgnoreMagnet) centres.add(o.magnetCenter());
		Vec3d[] r = ringAdvance(pos, sawDir, sawPhase, DT, centres, sawSpin, sawSpeed / DT);
		sawDir = r[1];
		sawPhase = r[2].x;
		sawRingEnd = r[0];
		sawSteerTicks++;
		if (Damage.debug && sawSteerTicks % 10 == 0) {
			Vec3d n = null;
			for (Vec3d cc : centres) if (n == null || cc.squaredDistanceTo(r[0]) < n.squaredDistanceTo(r[0])) n = cc;
			if (n != null) com.ultracraft.UltraCraft.LOG.info("[ring] srv saw {} st {} r {} dy {} mags {} c {}", getId(), sawSteerTicks, String.format("%.3f", Math.hypot(r[0].x - n.x, r[0].z - n.z)), String.format("%.3f", r[0].y - n.y), centres.size(), n);
		}
		Vec3d disp = r[0].subtract(pos);
		return disp.lengthSquared() < 1e-12 ? v : disp;
	}

	/** Tells the client how this saw is steered (spin 0: not), with the state at the end of this tick. */
	private void syncRing(int spin) {
		if (dataTracker.get(SAW_SPIN) != spin) dataTracker.set(SAW_SPIN, spin);
		if (spin == 0) return;
		dataTracker.set(SAW_DIR, new org.joml.Vector3f((float) sawDir.x, (float) sawDir.y, (float) sawDir.z));
		dataTracker.set(SAW_PHASE, (float) sawPhase);
		dataTracker.set(SAW_TICK, sawSteerTicks);
		dataTracker.set(SAW_POS, new org.joml.Vector3f((float) sawRingEnd.x, (float) sawRingEnd.y, (float) sawRingEnd.z));
	}

	// ------------------------------------------------------------------ Magnet.cs on nails

	/** The shooter's stuck magnets in the order they were put down (oldest first), worked out once a tick. */
	private static final java.util.Map<UUID, List<UKProjectileEntity>> OWN_MAGNETS = new java.util.HashMap<>();
	private static long ownMagnetsTick = -1;

	/**
	 * The shooter's stuck magnets that steer sawblades: those in enemies too (the user saw saws go round a magnet on an
	 * enemy in the game, following it as it moves), unlike nails.
	 */
	private List<UKProjectileEntity> sawMagnets(ServerWorld world) {
		ownMagnets(world);
		List<UKProjectileEntity> l = OWN_SAW_MAGNETS.getOrDefault(owner, new java.util.ArrayList<>());
		l.removeIf(m -> !m.isAlive());
		return l;
	}

	private static final java.util.Map<UUID, List<UKProjectileEntity>> OWN_SAW_MAGNETS = new java.util.HashMap<>();

	private List<UKProjectileEntity> ownMagnets(ServerWorld world) {
		if (world.getTime() != ownMagnetsTick) {
			ownMagnetsTick = world.getTime();
			OWN_MAGNETS.clear();
			OWN_SAW_MAGNETS.clear();
			// (a magnet stuck in an enemy pulls no nails; sawblades it does steer)
			for (UKProjectileEntity m : world.getEntitiesByType(UKEntities.PROJECTILE.get(), e -> e.kind() == ProjectileKind.MAGNET && e.isStuck() && e.isAlive() && e.fuseAt() >= 0)) {
				OWN_SAW_MAGNETS.computeIfAbsent(m.owner, o -> new java.util.ArrayList<>()).add(m);
				if (m.dataTracker.get(STUCK_IN) < 0) OWN_MAGNETS.computeIfAbsent(m.owner, o -> new java.util.ArrayList<>()).add(m);
			}
			for (List<UKProjectileEntity> l : OWN_MAGNETS.values())
				l.sort(java.util.Comparator.comparingLong(UKProjectileEntity::fuseAt).thenComparingInt(Entity::getId));
		}
		// (one that went off earlier this tick is still in the list)
		List<UKProjectileEntity> l = OWN_MAGNETS.getOrDefault(owner, new java.util.ArrayList<>());
		l.removeIf(m -> !m.isAlive());
		return l;
	}

	/**
	 * Magnet.OnTriggerEnter → HandleEnter → Nail.MagnetCaught: a nail coming into the field of one of its shooter's
	 * magnets is held by all of them, in the order they were put down: it goes to the first, and when that one goes to
	 * the next (the game takes the nearest; this order is the player's choice, magnet.order 0 for the game's). A nail a
	 * last magnet has thrown off is done with magnets.
	 */
	private boolean catchMagnets(ServerWorld world) {
		if (!magnets.isEmpty()) return true;
		if (released) return false;
		Vec3d p = getPos();
		double r = MagnetField.RADIUS;
		List<UKProjectileEntity> own = ownMagnets(world);
		boolean in = false;
		for (UKProjectileEntity m : own) if (m.magnetCenter().squaredDistanceTo(p) <= r * r) { in = true; break; }
		if (!in) return false;
		magVel = getVelocity().multiply(1 / DT);
		magAcc = 0;
		heldTime = 0;
		dataTracker.set(MAGNETIZED, true);
		for (UKProjectileEntity m : own) magnets.add(m.getId());
		return true;
	}

	/**
	 * A held nail's tick: the 0.008 s steps it owes (MagnetField.step), the turn about its own leaning axis (magnet.tilt)
	 * at an angle from the way to the magnet that starts at the game's 85 degrees and closes in over magnet.tightenTime
	 * (to magnet.angleEnd): the same speed on a tighter, faster circle. Ground it scrapes holds it nowhere (no friction)
	 * but brings it in sooner (magnet.groundTighten). The spike (the magnet's Cube collider) is solid. Once within
	 * magnet.stickRange of the magnet it stays on the pole. Enemies in the way are hurt (Nail.FixedUpdate: OverlapSphere
	 * 0.5 u, TouchEnemy). The closing in is by eye (the game's circles close as its nails knock into things).
	 */
	private void heldTick(ServerWorld world, ServerPlayerEntity sh) {
		List<UKProjectileEntity> ms = new java.util.ArrayList<>(ownMagnets(world));
		List<Vec3d> held = new java.util.ArrayList<>();
		magnets.clear();
		for (UKProjectileEntity m : ms) { magnets.add(m.getId()); held.add(m.magnetCenter()); }
		if (held.isEmpty()) {
			// (its magnets are gone without throwing it off: it is free and done with them, like a thrown one)
			released = true;
			nailRemoveIn = 5;
			dataTracker.set(MAGNETIZED, false);
			setVelocity(magVel.multiply(DT));
			velocityModified = true;
			return;
		}
		// on the pole: it keeps going round it and up and down along it until the magnet goes
		if (heldStill) {
			UKProjectileEntity m = null;
			for (UKProjectileEntity c : ms) if (c.getId() == clingMagnet) m = c;
			if (m == null) m = ms.get(0);
			long h = getId() * 0x9E3779B97F4A7C15L;
			h ^= h >>> 29;
			double spin = (2 + ((h & 0xFF) / 255.0) * 6) * ((h & 0x100) != 0 ? 1 : -1) * ServerTune.get("magnet.clingSpin", 2.5);
			double freq = 2 + ((h >>> 9) & 0xFF) / 255.0 * 4, phase = ((h >>> 17) & 0xFF) / 255.0 * Math.PI * 2;
			clingPhi += spin * DT;
			clingT += DT;
			double z = clingZ + ServerTune.get("magnet.clingSlide", 0.4) * Damage.SCALE_BLOCKS * Math.sin(clingT * freq + phase);
			Vec3d np = m.poleAt(clingPhi, z, clingRho);
			Vec3d d = np.subtract(getPos());
			setPosition(np);
			setVelocity(d);
			velocityModified = true;
			if (d.lengthSquared() > 1e-10) face(d);
			dataTracker.set(MAG_VEL, new org.joml.Vector3f());
			return;
		}
		boolean inOrder = ServerTune.get("magnet.order", 1) > 0;
		double pull = ServerTune.get("magnet.pull", 0), grav = MagnetField.GRAVITY * ServerTune.get("magnet.gravity", 0);
		Vec3d axis = MagnetField.axisFor(getId(), ServerTune.get("magnet.tilt", 60));
		double r = NAIL_RADIUS, near = ServerTune.get("magnet.stickRange", 1.6) * Damage.SCALE_BLOCKS;
		double minSpeed = ServerTune.get("magnet.minSpeed", 150) * Damage.SCALE_BLOCKS;
		double a0 = ServerTune.get("magnet.angleStart", 80), a1 = ServerTune.get("magnet.angleEnd", 55), tt = Math.max(0.05, ServerTune.get("magnet.tightenTime", 2.5));
		boolean grounded = false;
		Vec3d p = getPos(), vel = magVel;
		List<Vec3d> path = new java.util.ArrayList<>();
		path.add(p);
		int target = MagnetField.target(p, held, inOrder);
		double angle = a0;
		magAcc += DT;
		while (magAcc >= MagnetField.FIXED_DT - 1e-9) {
			magAcc -= MagnetField.FIXED_DT;
			target = MagnetField.target(p, held, inOrder);
			angle = a0 + (a1 - a0) * Math.min(1, heldTime / tt);
			// (a nail caught lying still, or slowed, is sent round at the circling speed)
			if (vel.length() < minSpeed) {
				Vec3d dir = vel.lengthSquared() > 1e-12 ? vel.normalize() : held.get(target).subtract(p).normalize();
				vel = dir.multiply(minSpeed);
			}
			Vec3d[] st = MagnetField.step(p, vel, held, target, grav, pull, axis, 0, angle);
			Vec3d np = st[0];
			vel = st[1];
			BlockHitResult bh = world.raycast(com.ultracraft.weapon.Plants.ctx(p, np, this));
			com.ultracraft.weapon.Plants.shootStep(world, sh, p, np, bh, null);
			if (bh.getType() != HitResult.Type.MISS) {
				Vec3d n = Vec3d.of(bh.getSide().getVector());
				np = bh.getPos().add(n.multiply(0.02));
				double vn = vel.dotProduct(n);
				if (vn < 0) vel = vel.subtract(n.multiply(vn));
				grounded = true;
			}
			for (UKProjectileEntity m : ms) {
				Vec3d[] c = m.spikeContact(np, r);
				if (c != null) { np = c[0]; vel = contact(vel, c[1]); }
			}
			p = np;
			path.add(p);
			if (p.squaredDistanceTo(held.get(target)) < near * near) {
				UKProjectileEntity m = ms.get(target);
				p = m.onPole(p, r, world.random);
				path.add(p);
				double[] pc = m.poleCoords(p);
				clingMagnet = m.getId();
				clingZ = pc[0];
				clingPhi = pc[1];
				clingRho = pc[2];
				clingT = 0;
				vel = Vec3d.ZERO;
				heldStill = true;
				break;
			}
		}
		heldTime += DT + (grounded ? ServerTune.get("magnet.groundTighten", 0.4) : 0);
		dataTracker.set(MAG_ANGLE, (float) angle);
		magVel = vel;
		int tid = ms.get(target).getId();
		targetMagnet = tid;
		dataTracker.set(MAG_TARGET, tid);
		if (!nailHit) {
			double reach = 0.5 * Damage.SCALE_BLOCKS;
			Box all = new Box(path.get(0), path.get(0));
			for (Vec3d q : path) all = all.union(new Box(q, q));
			for (Entity e : world.getOtherEntities(this, all.expand(reach + 0.5), ent -> ent instanceof LivingEntity && ent.isAlive() && ent != sh)) {
				for (int j = 1; j < path.size(); j++) {
					Optional<Vec3d> hp = e.getBoundingBox().expand(reach).raycast(path.get(j - 1), path.get(j));
					if (hp.isEmpty() && e.getBoundingBox().expand(reach).contains(path.get(j))) hp = Optional.of(path.get(j));
					if (hp.isPresent()) {
						onHitEntity(world, sh, kind(), (LivingEntity) e, hp.get(), vel.multiply(DT));
						return;
					}
				}
			}
		}
		setPosition(p);
		setVelocity(vel.multiply(DT));
		velocityModified = true;
		if (vel.lengthSquared() > 1e-8) face(vel);
		dataTracker.set(MAG_VEL, new org.joml.Vector3f((float) vel.x, (float) vel.y, (float) vel.z));
	}

	/** The nail's CapsuleCollider: radius 1 under its 0.16 root. */
	private static final double NAIL_RADIUS = 0.16 * Damage.SCALE_BLOCKS;

	private static double c(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** A Unity contact on the default physics material: the push into the surface goes, friction 0.6 on the slide. */
	private static Vec3d contact(Vec3d v, Vec3d n) {
		double vn = v.dotProduct(n);
		if (vn >= 0) return v;
		Vec3d vt = v.subtract(n.multiply(vn));
		double t = vt.length();
		return t < 1e-9 ? Vec3d.ZERO : vt.multiply(Math.max(0, 1 - 0.6 * -vn / t));
	}

	/**
	 * The spike's solid part (the Harpoon's "Cube": a 6 x 6 x 45 box at 13.5 + 7 along the root, x0.1): a sphere of
	 * radius r at `p` inside it is put back out through the nearest face. Returns {position, face normal} or null.
	 */
	public Vec3d[] spikeContact(Vec3d p, double r) {
		Vec3d f = magnetFwd != null ? magnetFwd : MagnetField.forward(getYaw(), getPitch());
		Vec3d t = Math.abs(f.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d ax = f.crossProduct(t).normalize(), ay = f.crossProduct(ax).normalize();
		Vec3d d = p.subtract(getPos());
		double S = Damage.SCALE_BLOCKS, hw = 0.3 * S + r;
		double lx = d.dotProduct(ax), ly = d.dotProduct(ay), lz = d.dotProduct(f);
		double z0 = -0.2 * S - r, z1 = 4.3 * S + r;
		if (Math.abs(lx) >= hw || Math.abs(ly) >= hw || lz <= z0 || lz >= z1) return null;
		double px = hw - Math.abs(lx), py = hw - Math.abs(ly), pz0 = lz - z0, pz1 = z1 - lz;
		double m = Math.min(Math.min(px, py), Math.min(pz0, pz1));
		Vec3d n;
		if (m == px) n = ax.multiply(Math.signum(lx) == 0 ? 1 : Math.signum(lx));
		else if (m == py) n = ay.multiply(Math.signum(ly) == 0 ? 1 : Math.signum(ly));
		else if (m == pz0) n = f.negate();
		else n = f;
		return new Vec3d[] {p.add(n.multiply(m)), n};
	}

	/**
	 * Whether the segment a..b, `radius` thick, passes through the spike's Cube (the Harpoon's Breakable: weak,
	 * precisionOnly, destroyOnBreak the harpoon), in the pole's frame (slab test).
	 */
	public boolean spikeCrossedBy(Vec3d a, Vec3d b, double radius) {
		Vec3d[] fr = poleFrame();
		double S = Damage.SCALE_BLOCKS, hw = 0.3 * S + radius;
		double[] lo = {-hw, -hw, -0.2 * S - radius}, hi = {hw, hw, 4.3 * S + radius};
		Vec3d da = a.subtract(getPos()), db = b.subtract(getPos());
		double[] p = {da.dotProduct(fr[1]), da.dotProduct(fr[2]), da.dotProduct(fr[0])};
		double[] q = {db.dotProduct(fr[1]), db.dotProduct(fr[2]), db.dotProduct(fr[0])};
		double t0 = 0, t1 = 1;
		for (int i = 0; i < 3; i++) {
			double dd = q[i] - p[i];
			if (Math.abs(dd) < 1e-12) { if (p[i] < lo[i] || p[i] > hi[i]) return false; continue; }
			double u = (lo[i] - p[i]) / dd, v = (hi[i] - p[i]) / dd;
			t0 = Math.max(t0, Math.min(u, v));
			t1 = Math.min(t1, Math.max(u, v));
			if (t0 > t1) return false;
		}
		return true;
	}

	/** Breakable.Break on the spike (a revolver or railcannon beam): the harpoon is destroyed, Magnet.OnDestroy launches its nails. */
	public void breakMagnet(ServerWorld world) {
		if (isAlive() && kind() == ProjectileKind.MAGNET) magnetExplode(world);
	}

	/** The pole's frame: forward (into the surface) and two sideways axes, as spikeContact uses them. */
	private Vec3d[] poleFrame() {
		Vec3d f = magnetFwd != null ? magnetFwd : MagnetField.forward(getYaw(), getPitch());
		Vec3d t = Math.abs(f.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d ax = f.crossProduct(t).normalize();
		return new Vec3d[] {f, ax, f.crossProduct(ax).normalize()};
	}

	/** {along, round (radians), out} of a point about the pole (from its tail). */
	public double[] poleCoords(Vec3d p) {
		Vec3d[] fr = poleFrame();
		Vec3d d = p.subtract(getPos());
		double x = d.dotProduct(fr[1]), y = d.dotProduct(fr[2]);
		return new double[] {d.dotProduct(fr[0]), Math.atan2(y, x), Math.sqrt(x * x + y * y)};
	}

	/** The point `along` the pole from its tail (kept on the part above the surface), `round` radians round it, `out` from its axis. */
	public Vec3d poleAt(double round, double along, double out) {
		Vec3d[] fr = poleFrame();
		double z = Math.max(0, Math.min(MagnetField.TIP - ServerTune.get("magnet.depth", 1.0) * Damage.SCALE_BLOCKS, along));
		return getPos().add(fr[0].multiply(z)).add(fr[1].multiply(Math.cos(round) * out)).add(fr[2].multiply(Math.sin(round) * out));
	}

	/** A point on the pole's surface next to `p` (a nail of radius r lying against it, a little way round at random). */
	public Vec3d onPole(Vec3d p, double r, net.minecraft.util.math.random.Random rnd) {
		Vec3d f = magnetFwd != null ? magnetFwd : MagnetField.forward(getYaw(), getPitch());
		double S = Damage.SCALE_BLOCKS;
		Vec3d d = p.subtract(getPos());
		double z = Math.max(0, Math.min(MagnetField.TIP - ServerTune.get("magnet.depth", 1.0) * S, d.dotProduct(f)));
		Vec3d onAxis = getPos().add(f.multiply(z));
		Vec3d out = p.subtract(onAxis);
		out = out.subtract(f.multiply(out.dotProduct(f)));
		if (out.lengthSquared() < 1e-9) {
			Vec3d t = Math.abs(f.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
			out = f.crossProduct(t);
		}
		return onAxis.add(out.normalize().multiply(0.3 * S + r + rnd.nextDouble() * r * 2));
	}

	/**
	 * Magnet.Update's load: the mass it pulls (0.2 a nail) against maxWeight 15 x (magnets touching it + 1). Over that
	 * the TimeBomb goes at once; under it the beeper runs from green to red (Color.Lerp), its pitch is load / 2 + 0.25
	 * and its size x (1 + load).
	 */
	private void magnetLoad(ServerWorld world) {
		int id = getId();
		Vec3d c = magnetCenter();
		double r = MagnetField.RADIUS;
		int nails = world.getEntitiesByClass(UKProjectileEntity.class, getBoundingBox().expand(r * 3), e -> e.isAlive() && e.targetMagnet == id && e.magnets.contains(id)).size();
		// (Magnet.OnTriggerEnter on another magnet: ConnectMagnets, the two fields touching)
		int linked = world.getEntitiesByClass(UKProjectileEntity.class, getBoundingBox().expand(r * 2 + 3), e -> e != this && e.kind() == ProjectileKind.MAGNET && e.isStuck() && e.isAlive() && e.magnetCenter().squaredDistanceTo(c) < 4 * r * r).size();
		// the sawblades in its field weigh in too (Magnet.sawblades: their Rigidbody masses, NailAlt 0.5, NailAltFodder 1,
		// NailAltHeated 3): 30 / 15 / 5 to a lone magnet
		double sawMass = 0;
		for (UKProjectileEntity e : world.getEntitiesByClass(UKProjectileEntity.class, getBoundingBox().expand(r + 2), e -> e.isAlive() && e.kind().sawblade() && e.sawCircled && e.getPos().squaredDistanceTo(c) <= r * r))
			sawMass += e.kind() == ProjectileKind.SAWBLADE_HEATED ? 3 : e.kind() == ProjectileKind.SAWBLADE_FODDER ? 1 : 0.5;
		double max = 15 * (linked + 1), mass = 0.2 * nails + sawMass;
		dataTracker.set(MAG_LOAD, (float) Math.min(1, mass / max));
		if (mass > max) magnetExplode(world);
	}

	/** A magnet's load (0..1) for its beeper. */
	public float magnetLoadShown() {
		return dataTracker.get(MAG_LOAD);
	}

	/**
	 * Magnet.Launch on a held nail when the magnet goes: the nail flies off at 120 u/s, along the line from the magnet
	 * thrown off by a random way (the game: along that line, back through the magnet with no surface within 100 u), and
	 * MagnetRelease: it circles the next magnet holding it, or with none left is free and goes 5 s later.
	 */
	private void magnetLaunch(ServerWorld world, int magnetId, Vec3d from) {
		Vec3d out = getPos().subtract(from);
		Vec3d rand = new Vec3d(world.random.nextGaussian(), world.random.nextGaussian(), world.random.nextGaussian()).normalize();
		// its line out from the magnet plus a random way (magnet.scatter): the nails on the pole all lie square to it, so
		// the line alone sent them out in one flat ring
		Vec3d dir = out.lengthSquared() > 1e-9 ? out.normalize().add(rand.multiply(ServerTune.get("magnet.scatter", 1.5))).normalize() : rand;
		clingMagnet = -1;
		Vec3d fling = dir.multiply(MagnetField.LAUNCH_SPEED * ServerTune.get("magnet.launch", 2));
		magVel = fling;
		nailRest = false;
		heldStill = false;
		magnets.remove((Integer) magnetId);
		targetMagnet = -1;
		// thrown toward the next magnet in line: it starts circling that one afresh
		heldTime = 0;
		if (magnets.isEmpty()) {
			released = true;
			nailRemoveIn = 5;
			dataTracker.set(MAGNETIZED, false);
			setVelocity(fling.multiply(DT));
			velocityModified = true;
			face(fling);
		}
	}

	/**
	 * Harpoon.OnTriggerEnter: the magnet stops where its tip meets the surface (the spike stands out of it, its tail with
	 * the beeper the way it came from); shot into a floor it stands straight up. TimeBomb.StartCountdown.
	 */
	private Vec3d stickMagnet(Vec3d tipAt, Vec3d v, Vec3d normal) {
		Vec3d f = v.lengthSquared() > 1e-12 ? v.normalize() : new Vec3d(0, 0, 1);
		if (normal != null && normal.y > 0.5 && ServerTune.get("magnet.floorUpright", 0) > 0) f = new Vec3d(0, -1, 0);
		magnetFwd = f;
		face(f);
		dataTracker.set(FUSE_AT, getWorld().getTime());
		return tipAt.subtract(f.multiply(MagnetField.TIP - ServerTune.get("magnet.depth", 1.0) * Damage.SCALE_BLOCKS));
	}

	/** TimeBomb at 0 / Magnet.OnDestroy: BreakParticleMetal at the root, and Launch on every nail it holds. */
	private void magnetExplode(ServerWorld world) {
		Vec3d c = magnetCenter();
		Net.toNear(world, getPos(), new HitPayload(HitPayload.WALL_HIT, HitPayload.HARPOON, 2, getPos()));
		int id = getId();
		for (UKProjectileEntity n : world.getEntitiesByClass(UKProjectileEntity.class, getBoundingBox().expand(128), e -> e.magnets.contains(id))) {
			if (n.targetMagnet == id) n.magnetLaunch(world, id, c);
			else n.magnets.remove((Integer) id);
		}
		discard();
	}

	// ------------------------------------------------------------------ Grenade (Core Eject / the Impact Hammer's)

	/** Grenade ids this projectile already pushed (one push each). */
	private java.util.Set<Integer> pushedGrenades;

	/** The grenade's catch sphere for shots and swings, blocks (its ParryHelper trigger, by eye: grenade.trigger u). */
	public static double grenadeTrigger() {
		// (the prefab's Sphere with its ParryHelpers: radius 0.5 x 5 under the 0.5 root = 1.25 u)
		return ServerTune.get("grenade.trigger", 1.25) * Damage.SCALE_BLOCKS;
	}

	/** A live player grenade (Core Eject, Impact Hammer): what shots, coins and the hammer can set off. */
	public boolean liveGrenade() {
		return kind() == ProjectileKind.CORE && isAlive();
	}

	private void pushGrenades(ServerWorld world, Vec3d start, Vec3d end, double radius, ProjectileKind k) {
		Vec3d seg = end.subtract(start);
		if (seg.lengthSquared() < 1e-9) return;
		double r = radius + ServerTune.get("grenade.body", 0.5) * Damage.SCALE_BLOCKS;
		for (UKProjectileEntity g : world.getEntitiesByClass(UKProjectileEntity.class, new Box(start, end).expand(r + 1), UKProjectileEntity::liveGrenade)) {
			if (pushedGrenades != null && pushedGrenades.contains(g.getId())) continue;
			Vec3d c = g.getPos();
			double t = MathHelper.clamp(c.subtract(start).dotProduct(seg) / seg.lengthSquared(), 0, 1);
			if (c.distanceTo(start.add(seg.multiply(t))) > r) continue;
			if (pushedGrenades == null) pushedGrenades = new java.util.HashSet<>();
			pushedGrenades.add(g.getId());
			// (by eye: a nail nudges it on ahead, a rocket or a saw sends it far)
			// (by eye, the user's check: 5-6 blocks at most)
			double speedU = k.nail() ? ServerTune.get("grenade.nailPush", 18) : ServerTune.get("grenade.rocketPush", 24);
			Vec3d d = seg.normalize().add(0, ServerTune.get("grenade.pushLift", 0.25), 0).normalize();
			g.setVelocity(d.multiply(speedU * Damage.SCALE_BLOCKS * DT));
			g.velocityModified = true;
		}
	}

	/** Grenade.Explode(super): a shot or a coin set it off - Explosion Super, x `mult` (a railcannon beam's 2). */
	public void superExplode(ServerWorld world, ServerPlayerEntity by, float mult) {
		if (!isAlive()) return;
		Explosions.explode(world, by != null ? by : shooter(), getPos(), Explosions.SUPER.scaled(mult), false, "shotgun0", this);
	}

	/**
	 * Grenade.GrenadeBeam (ShotgunHammer.HitNade): the hammer turns the grenade into its Grenade Beam - a beam from where
	 * it is to what the camera looks at (damage 2, one body, force 50000), ending in Explosion Big.
	 */
	public void grenadeBeam(ServerWorld world, ServerPlayerEntity by, Vec3d eye, Vec3d look) {
		if (!isAlive()) return;
		Vec3d from = getPos();
		double range = Damage.rangeBlocks();
		Vec3d far = eye.add(look.multiply(range));
		BlockHitResult b = world.raycast(new RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, by));
		Vec3d aim = b.getType() == HitResult.Type.MISS ? far : b.getPos();
		for (Entity e : world.getOtherEntities(by, new Box(eye, aim).expand(1), ent -> ent instanceof LivingEntity && ent.isAlive())) {
			Optional<Vec3d> hp = e.getBoundingBox().raycast(eye, aim);
			if (hp.isPresent() && hp.get().distanceTo(eye) < aim.distanceTo(eye)) aim = hp.get();
		}
		discard();
		Vec3d dir = aim.subtract(from);
		if (dir.lengthSquared() < 1e-6) dir = look;
		java.util.List<com.ultracraft.weapon.Hitscan.Hit> hits = com.ultracraft.weapon.Hitscan.fire(by, from, dir.normalize(), com.ultracraft.weapon.Hitscan.GRENADE_BEAM, 0, false);
		Vec3d endAt = hits.isEmpty() ? com.ultracraft.weapon.Hitscan.lastEnd : hits.get(hits.size() - 1).point();
		Explosions.explode(world, by, endAt, Explosions.SUPER, false, "hammer0", null);
	}

	private void onHitEntity(ServerWorld world, ServerPlayerEntity sh, ProjectileKind k, LivingEntity target, Vec3d point, Vec3d v) {
		Vec3d dir = v.normalize();
		boolean head = com.ultracraft.weapon.Hitscan.isHeadshot(target, point, getPos(), dir);
		switch (k) {
			case PELLET -> {
				if (isBoosted()) { Explosions.explode(world, sh, point, Explosions.PARRIED, false, "shotgun0", this); return; } // (hurts V1 near it: 35)
				deal(sh, target, damage, dir, 1000f, HitPayload.SHOTGUN, head, point);
				if (sh != null) com.ultracraft.weapon.ParryServer.pelletSpent(sh, point);
				discard();
			}
			case SAWBLADE, SAWBLADE_FODDER, SAWBLADE_HEATED -> {
				// Nail.DamageEnemy for sawblades: hits per enemy limited by multiHitAmount, −1 hitAmount each; breaks below 1
				int n = sawHits.getOrDefault(target.getId(), 0);
				if (n >= multiHit) { setVelocity(v); setPosition(getPos().add(v)); return; }
				sawHits.put(target.getId(), n + 1);
				float sd = damage;
				if (k == ProjectileKind.SAWBLADE_FODDER) sd *= fodderMultiplier(target); // fodderDamageBoost
				// Nail.DamageEnemy: punched, twice the damage (for 3 s, until it reaches a second enemy)
				if (sawPrevEnemy != target.getId()) {
					if (sawPrevEnemy != -1) sawPunchedUntil = -1;
					sawPrevEnemy = target.getId();
				}
				if (age < sawPunchedUntil) sd *= 2;
				deal(sh, target, sd, dir, 3000f, HitPayload.SAWBLADE, head, point);
				if (k == ProjectileKind.SAWBLADE_HEATED) target.setOnFireFor(2);
				hitAmount -= 1;
				if (hitAmount < 1) { Net.toNear(world, point, new HitPayload(HitPayload.WALL_HIT, HitPayload.SAWBLADE, 2, point)); discard(); return; }
				setVelocity(v);
				setPosition(point.add(v.normalize().multiply(0.3)));
			}
			case NAIL, NAIL_FODDER, NAIL_HEATED -> {
				float d = damage;
				if (k == ProjectileKind.NAIL_FODDER) d *= fodderMultiplier(target); // Nail.GetFodderDamageMultiplier
				deal(sh, target, d, dir, 3000f, HitPayload.NAIL, head, point);
				if (k == ProjectileKind.NAIL_HEATED) target.setOnFireFor(2);
				// (the game leaves the nail stuck in the enemy; here it goes on the hit, by the player's choice)
				discard();
			}
			case CORE -> Explosions.explode(world, sh, point, Explosions.NORMAL, false, "shotgun0", this);
			case ROCKET -> {
				// Grenade.Collision: airborne / fresh / levelled-up target → super explosion
				boolean sup = levelledUp || !target.isOnGround();
				// Grenade.Collision: on an enemy the full explosion, which hurts V1 close by (35, the user's check of the game);
				// only on a wall or the ground the harmless one that just pushes
				Explosions.explode(world, sh, point, (sup ? Explosions.SUPER : Explosions.NORMAL).scaled(rocketBlastScale()), false, "rocket0", this);
			}
			case CANNONBALL -> {
				// Cannonball.Collide on an enemy (physicsCannonball): one already hit, or touched within 1 s of a bounce,
				// is passed through
				if (cbHitEnemies.contains(target.getId()) || cbDefence < 1) {
					cbHitEnemies.add(target.getId());
					setVelocity(v);
					setPosition(getPos().add(v));
					return;
				}
				double speedU = v.length() / (Damage.SCALE_BLOCKS * DT);
				boolean wasAlive = target.isAlive();
				// damage min(damage, speed x 0.15), only above 10 u/s
				if (speedU > 10) deal(sh, target, (float) Math.min(cbDamage, speedU * 0.15), dir, 1000f, HitPayload.CANNONBALL, head, point);
				cbHitEnemies.add(target.getId());
				if (!target.isAlive()) {
					// killed: it goes on through, one durability less (2), or bounces if it was not launched
					if (wasAlive && --durability <= 0) { cannonballBreak(world, point); return; }
					if (!cbLaunched) { cannonballBounce(world, v, point); return; }
					setVelocity(v);
					setPosition(point.add(v.normalize().multiply(0.3)));
				} else if (speedU < 15) cannonballBreak(world, point);
				else cannonballBounce(world, v, point);
			}
			case MAGNET -> {
				// into a mob the spike goes on to its middle (where the flight passes nearest it), its tail with the beeper
				// left standing out of it (magnet.mobTail)
				Vec3d f = v.normalize();
				double S = Damage.SCALE_BLOCKS;
				double in = c(target.getBoundingBox().getCenter().subtract(point).dotProduct(f) + 0.3 * S, 1.0 * S, MagnetField.TIP - ServerTune.get("magnet.mobTail", 1.2) * S);
				Vec3d tip = point.add(f.multiply(in - ServerTune.get("magnet.depth", 1.0) * S));
				stickIn(target, stickMagnet(tip, v, null), (float) MagnetField.FUSE);
			}
			case SCREWDRIVER -> {
				deal(sh, target, damage, dir, 0f, HitPayload.HARPOON, head, point);
				drillHitsLeft = 116;
				stickIn(target, point, 8f);
			}
			case GASOLINE -> {
				// GasolineProjectile on an enemy: AddFlammable(0.1), no damage of its own
				com.ultracraft.weapon.Gasoline.addFuel(world, target, 0.1f);
				discard();
			}
			case ZAPPER -> stickIn(target, point, 30f);
		}
	}

	/**
	 * A nail meeting the environment (Nail.OnCollisionEnter + the Rigidbody's contact): the first contact sets `hit` (no
	 * more damage), makes the environment spark and sound and starts RemoveTime (1 s). The capsule has Unity's default
	 * surface (bounciness 0, friction 0.6): the push into the surface goes, the slide along it is slowed by friction, and
	 * the tumbling capsule hops on (live nail.bounce for that hop).
	 */
	private void nailBounce(ServerWorld world, BlockHitResult block, Vec3d v) {
		Vec3d p = block.getPos();
		Vec3d n = Vec3d.of(block.getSide().getVector());
		if (!nailHit) {
			nailHit = true;
			nailRemoveIn = (float) com.ultracraft.weapon.ServerTune.get("nail.decay", 4.0);
			Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.NAIL, 0, p));
		}
		double e = com.ultracraft.weapon.ServerTune.get("nail.bounce", 0.1), mu = com.ultracraft.weapon.ServerTune.get("nail.friction", 0.6);
		double vn = v.dotProduct(n);
		Vec3d vt = v.subtract(n.multiply(vn));
		double jn = -(1 + e) * vn, vtLen = vt.length();
		Vec3d slide = vtLen > 1e-9 ? vt.multiply(Math.max(0, 1 - mu * Math.max(0, jn) / vtLen)) : Vec3d.ZERO;
		// the capsule rolling and scraping along: each contact also loses part of the slide (live nail.slideLoss)
		slide = slide.multiply(1 - Math.max(0, Math.min(1, com.ultracraft.weapon.ServerTune.get("nail.slideLoss", 0.5))));
		Vec3d r = slide.add(n.multiply(-e * vn));
		setPosition(p.add(n.multiply(0.02)));
		// slow enough on a floor: it lies there until it goes (like a coin)
		if (r.length() < com.ultracraft.weapon.ServerTune.get("nail.restSpeed", 0.02) && n.y > 0.5) { r = Vec3d.ZERO; nailRest = true; }
		setVelocity(r);
		velocityModified = true;
		if (r.lengthSquared() > 1e-8) face(r);
		if (!magnets.isEmpty()) magVel = r.multiply(1 / DT);
	}

	private void onHitBlock(ServerWorld world, ServerPlayerEntity sh, ProjectileKind k, BlockHitResult block, Vec3d v) {
		// a punched pellet's blast hurts V1 near it (35), like the Core Eject's (the user's check of the game)
		if (k == ProjectileKind.PELLET && isBoosted()) { Explosions.explode(world, sh, block.getPos(), Explosions.PARRIED, false, "shotgun0", this); return; }
		Vec3d p = block.getPos();
		switch (k) {
			case CORE -> Explosions.explode(world, sh, p, Explosions.NORMAL, false, "shotgun0", this);
			case ROCKET -> Explosions.explode(world, sh, p, (levelledUp ? Explosions.SUPER : Explosions.NORMAL).scaled(rocketBlastScale()), true, "rocket0", this);
			case CANNONBALL -> {
				// Cannonball.FixedUpdate: onto ground (less than 45 degrees from flat) it leaves a PhysicalShockwavePlayer
				// (force 10000 + speed x 80) and breaks; Collide: any other surface breaks it too (canBreakBeforeLaunched)
				Vec3d n = Vec3d.of(block.getSide().getVector());
				if (n.y > Math.cos(Math.toRadians(45))) {
					double speedU = v.length() / (Damage.SCALE_BLOCKS * DT);
					com.ultracraft.weapon.WeaponServer.groundShockwave(world, sh, p, (float) (10000 + speedU * 80));
					Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.CANNONBALL, 3, p));
				}
				cannonballBreak(world, p);
			}
			case SAWBLADE, SAWBLADE_FODDER, SAWBLADE_HEATED -> {
				// Nail.FixedUpdate sawblade ricochet: reflect (heated: along the surface normal), hits as below (the game also
				// turns a circling saw the other way round, magnetRotationDirection *= -1; left out: it sent them into each other)
				// (Nail.FixedUpdate: no hits left breaks it on the wall; a bounce costs 0.25, 0.1 while a magnet holds it)
				if (hitAmount <= 0) { Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.SAWBLADE, 2, p)); discard(); return; }
				Vec3d n = Vec3d.of(block.getSide().getVector());
				Vec3d r = k == ProjectileKind.SAWBLADE_HEATED ? n.multiply(v.length()) : v.subtract(n.multiply(2 * v.dotProduct(n)));
				hitAmount -= sawCircled ? 0.1f : 0.25f;
				sawBounced = true;
				Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.SAWBLADE, 1, p));
				setPosition(p.add(n.multiply(0.1)));
				setVelocity(r);
				face(r);
			}
			case MAGNET -> {
				setPosition(stickMagnet(p, v, Vec3d.of(block.getSide().getVector())));
				setVelocity(Vec3d.ZERO);
				dataTracker.set(STUCK, true);
				stuckFor = (float) MagnetField.FUSE;
				Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.HARPOON, 3, p));
			}
			case SCREWDRIVER -> {
				setPosition(p);
				setVelocity(Vec3d.ZERO);
				dataTracker.set(STUCK, true);
				stuckFor = 2f;
				Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.HARPOON, 0, p));
			}
			case GASOLINE -> {
				// GasolineProjectile on the environment: a GasolineStain where it met the surface, facing out of it
				com.ultracraft.weapon.Gasoline.addStain(world, p, Vec3d.of(block.getSide().getVector()));
				discard();
			}
			case NAIL, NAIL_FODDER, NAIL_HEATED -> nailBounce(world, block, v);
			default -> {
				if (k.nail()) Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.NAIL, 0, p));
				// Projectile.explosionEffect BulletSpark where a shotgun pellet meets the surface; only this pellet stops
				if (k == ProjectileKind.PELLET) Net.toNear(world, p, new HitPayload(HitPayload.WALL_HIT, HitPayload.SHOTGUN, 0, p));
				discard();
			}
		}
	}

	private void deal(ServerPlayerEntity sh, LivingEntity target, float points, Vec3d dir, float force, int hitter, boolean head, Vec3d point) {
		if (sh == null) return;
		boolean wasAlive = target.isAlive();
		Damage.deal(sh, target, head ? points * 2 : points, dir, force, hitter, point);
		boolean killed = wasAlive && !target.isAlive();
		Net.toNear((ServerWorld) getWorld(), point, new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, hitter, head ? 1 : 0, Damage.flags(target), point));
	}

	/** Nail.GetFodderDamageMultiplier: fodder ×2, Soldier-class ×1.5 — mapped by max health. */
	private static float fodderMultiplier(LivingEntity e) {
		float hp = e.getMaxHealth();
		if (hp <= 20) return 2f;
		if (hp <= 40) return 1.5f;
		return 1f;
	}

	private void stickIn(LivingEntity target, Vec3d point, float seconds) {
		dataTracker.set(STUCK, true);
		dataTracker.set(STUCK_IN, target.getId());
		stuckOffset = point.subtract(target.getPos());
		stuckFor = seconds;
		setVelocity(Vec3d.ZERO);
		setPosition(point);
	}

	private void tickStuck(ServerWorld world, ProjectileKind k) {
		stuckFor -= DT;
		int id = dataTracker.get(STUCK_IN);
		Entity host = id >= 0 ? world.getEntityById(id) : null;
		if (id >= 0) {
			if (host == null || !host.isAlive()) { if (k == ProjectileKind.MAGNET) magnetExplode(world); else discard(); return; }
			setPosition(host.getPos().add(stuckOffset));
		}
		if (k == ProjectileKind.SCREWDRIVER && host instanceof LivingEntity target) {
			// Harpoon.FixedUpdate: 0.0625 every 0.05 s while drilling, 116 hits; each one's blood heals the driller close by
			// (Damage.deal → BloodHeal)
			drillCooldown -= DT;
			ServerPlayerEntity sh = shooter();
			while (drillCooldown <= 0 && drillHitsLeft > 0 && sh != null && target.isAlive()) {
				drillCooldown += 0.05f;
				drillHitsLeft--;
				boolean wasAlive = target.isAlive();
				Damage.deal(sh, target, 0.0625f, Vec3d.ZERO, 0f, HitPayload.DRILL);
				if (wasAlive && !target.isAlive()) Net.toNear(world, getPos(), new HitPayload(HitPayload.KILL, HitPayload.DRILL, 0, Damage.flags(target), getPos()));
			}
			if (drillHitsLeft <= 0) discard();
		}
		if (k == ProjectileKind.MAGNET && stuckFor > 0) {
			Vec3d mc = magnetCenter();
			org.joml.Vector3f was = dataTracker.get(MAG_C);
			if (Math.abs(was.x - mc.x) + Math.abs(was.y - mc.y) + Math.abs(was.z - mc.z) > 1e-4) dataTracker.set(MAG_C, new org.joml.Vector3f((float) mc.x, (float) mc.y, (float) mc.z));
			magnetLoad(world);
			if (isRemoved()) return;
		}
		if (stuckFor <= 0) {
			if (k == ProjectileKind.MAGNET) magnetExplode(world);
			else discard();
		}
	}

	/** Damage.flags needs the host for KILL credit; a magnet pulls nails toward itself (Nail.FixedUpdate). */
	public Vec3d magnetPos() {
		return getPos();
	}

	@Override
	public boolean canHit() {
		return false;
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		age = nbt.getFloat("age");
		if (nbt.containsUuid("owner")) owner = nbt.getUuid("owner");
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putFloat("age", age);
		if (owner != null) nbt.putUuid("owner", owner);
	}

	@Override
	public boolean shouldRender(double distance) {
		return distance < 128 * 128;
	}
}
