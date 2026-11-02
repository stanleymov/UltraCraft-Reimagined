package com.ultracraft.entity;

import com.ultracraft.net.BeamPayload;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.Damage;
import com.ultracraft.weapon.Hitscan;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Port of Coin.cs. Thrown by the Marksman: falls under gravity, dies after 5 s or 0.1 s after coming to
 * rest. While in the air a revolver beam that hits it is reflected (0.1 s later) to the best target:
 * another coin (power +1) → the nearest enemy's head (not hit yet in this chain) → straight on. The
 * split windows 0.35–0.417 s and after 1 s ("doubled") make the coin reflect twice (hitTimes 2).
 */
public class CoinEntity extends Entity {
	private static final TrackedData<Boolean> SHOT = DataTracker.registerData(CoinEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> DOUBLED = DataTracker.registerData(CoinEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** Coin.GetDeleted ran: the coin hit the environment (or 5 s passed) and is a dead prop for 5 s more. */
	private static final TrackedData<Boolean> LANDED = DataTracker.registerData(CoinEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private float landedAge;
	/** Client: the landing was seen (CoinBreak sound played, trail removed). */
	public boolean landedSeen;

	public float power = 2;
	/** Coin.altBeam: the beam that hit it when it was not the revolver's (1 = railcannon, 2 = Malicious railcannon), reflected as is. */
	public int altBeam;
	/**
	 * The coin's Rigidbody falls with Physics.gravity, which ULTRAKILL sets to (0, -40, 0) (globalgamemanagers
	 * PhysicsManager); Revolver.ThrowCoin also copies that magnitude into the coin's custom gravity. 40 u/s² puts the
	 * apex of the 15 u/s throw at 0.375 s, right on the 0.35–0.417 s split-shot window.
	 */
	public static final double GRAVITY = 40;
	/** Client only: recent positions for the coin's TrailRenderer (x, y, z, time in seconds). */
	public final java.util.ArrayDeque<double[]> trail = new java.util.ArrayDeque<>();
	public boolean flashPlayed;
	/**
	 * Client only: the local player's last throw (start point and when). A coin reaches the client a few ticks after
	 * the throw at its start point while V1 has moved on, so it appeared behind the camera when running forward and
	 * off to the side when strafing; the first client tick moves it on by the time since the throw.
	 */
	public static Vec3d clientThrowStart;
	public static long clientThrowNanos;
	private boolean caughtUp;
	/**
	 * Client only: a predicted coin spawned locally the moment the coin is thrown, so it leaves the hand at once
	 * (the real one arrives a few ticks later). The real coin takes over its position, velocity, age and trail.
	 */
	public boolean ghost;
	public static CoinEntity clientGhost;
	/**
	 * Client only: this is the local player's own coin, predicted from the throw. While it flies the client's
	 * simulation is authoritative for drawing: the server's copy runs the same physics a few ticks behind (2-5
	 * blocks on running / jumping throws), and taking its positions or velocities snapped the coin backwards.
	 */
	public boolean predicted;

	/** Client only: this coin's trail particle (typed Object so this shared class stays free of client classes). */
	public Object trailParticle;
	public int hitTimes = 1;
	public int ricochets;
	public UUID owner;
	private float age;            // seconds
	private float reflectIn = -1; // seconds until ReflectRevolver
	private Vec3d hitPoint = Vec3d.ZERO;
	private Set<UUID> chain = new HashSet<>(); // CoinChainCache.beenHit

	public CoinEntity(EntityType<? extends CoinEntity> type, World world) {
		super(type, world);
		this.noClip = false;
	}

	@Override
	protected void initDataTracker() {
		dataTracker.startTracking(SHOT, false);
		dataTracker.startTracking(DOUBLED, false);
		dataTracker.startTracking(LANDED, false);
	}

	/** Coin.Bounce (Whiplash / punch): the coin is thrown 25 u/s straight up again as a fresh coin. */
	public void bounce() {
		age = 0;
		setVelocity(0, 25 * Damage.SCALE_BLOCKS * 0.05, 0); // blocks per tick
		velocityModified = true;
	}

	public boolean isShot() {
		return dataTracker.get(SHOT);
	}

	public boolean isDoubled() {
		return dataTracker.get(DOUBLED);
	}

	public float ageSeconds() {
		return age;
	}

	@Override
	public void tick() {
		super.tick();
		float dt = 0.05f;
		age += dt;
		if (!getWorld().isClient) {
			ServerWorld world = (ServerWorld) getWorld();
			if (isLanded()) {
				// GetDeleted: the Coin script, trigger sphere, trail and sounds are gone; the Rigidbody and the solid
				// box stay, so it falls and lies where it lands; RemoveOnTime 5 s
				landedAge += dt;
				physicsStep(dt);
				if (landedAge >= 5f) discard();
			} else if (!isShot()) {
				// Start: Invoke TripleTime 0.35, TripleTimeEnd 0.417, DoubleTime 1, GetDeleted 5
				boolean doubled = (age >= 0.35f && age < 0.417f) || age >= 1f;
				hitTimes = 1; // one target per coin (a split coin still carries the doubled power)
				if (doubled) power += 1;
				dataTracker.set(DOUBLED, doubled);
				// OnCollisionEnter with the environment -> GetDeleted; Invoke("GetDeleted", 5) as well
				if (physicsStep(dt) || age >= 5f) {
					dataTracker.set(LANDED, true);
					dataTracker.set(DOUBLED, false);
					velocityModified = true;
				}
			} else if (reflectIn >= 0) {
				reflectIn -= dt;
				if (reflectIn < 0) reflectRevolver(world);
			}
		} else {
			if (ghost) {
				// the real coin never came (refused / lost): drop the prediction
				if (age > 0.6f) { if (clientGhost == this) clientGhost = null; discard(); return; }
			} else if (!caughtUp) {
				caughtUp = true;
				if (!takeOverGhost()) catchUp();
			}
			// the same physics between server updates (sent every tick), so the coin flies at the frame rate
			if (!isShot()) physicsStep(dt);
		}
	}

	/**
	 * Client: the coin runs the same physics as the server, which reports a position every tick about one tick
	 * behind the prediction; snapping to each one put a sawtooth into the flight and the trail. Keep the prediction
	 * and only take the server's position when it disagrees clearly (a bounce, a hit, a reflected coin).
	 */
	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps, boolean interpolate) {
		// own coin in flight: keep the prediction (collisions are simulated here too; a hit or a landing arrives
		// as SHOT / LANDED and the server's position is taken again). Others' coins: smooth within 3 blocks.
		if (!isShot() && !isLanded() && (predicted || getPos().squaredDistanceTo(x, y, z) < 3 * 3)) return;
		super.updateTrackedPositionAndAngles(x, y, z, yaw, pitch, steps, interpolate);
	}

	/** The real coin arrives: continue exactly where the predicted one is, and remove it. */
	private boolean takeOverGhost() {
		CoinEntity g = clientGhost;
		if (g == null || g.isRemoved() || isShot() || isLanded()) return false;
		// it is ours when it appeared at our throw's start point (the server spawns it exactly there); the ghost
		// itself may already be many blocks further along on a fast throw
		if (clientThrowStart == null || getPos().squaredDistanceTo(clientThrowStart) > 1.5 * 1.5) return false;
		if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] coin takeover: real {} ghost {} (off {} blocks) age {}", getPos(), g.getPos(), getPos().distanceTo(g.getPos()), g.age);
		setPosition(g.getX(), g.getY(), g.getZ());
		prevX = g.prevX; prevY = g.prevY; prevZ = g.prevZ;
		lastRenderX = g.lastRenderX; lastRenderY = g.lastRenderY; lastRenderZ = g.lastRenderZ;
		setVelocity(g.getVelocity());
		setYaw(g.getYaw());
		setPitch(g.getPitch());
		age = g.age;
		flashPlayed = g.flashPlayed;
		trail.addAll(g.trail);
		clientGhost = null;
		clientThrowStart = null;
		predicted = true;
		g.discard(); // (no client classes here: this class also loads on a dedicated server)
		return true;
	}

	private void catchUp() {
		if (clientThrowStart == null || isShot() || isLanded()) return;
		double late = (System.nanoTime() - clientThrowNanos) / 1e9 - 0.05; // the first physics step below covers one tick
		if (late <= 0 || late > 0.5 || getPos().squaredDistanceTo(clientThrowStart) > 1.5 * 1.5) return;
		clientThrowStart = null;
		predicted = true;
		double ticks = late * 20;
		double g = GRAVITY * Damage.SCALE_BLOCKS * 0.05 * 0.05; // blocks per tick squared
		Vec3d v = getVelocity();
		setPosition(getX() + v.x * ticks, getY() + v.y * ticks - 0.5 * g * ticks * ticks, getZ() + v.z * ticks);
		setVelocity(v.x, v.y - g * ticks, v.z);
		age += (float) late;
	}

	@Override
	public void setVelocityClient(double x, double y, double z) {
		// the server's velocity is a few ticks older than the predicted one (higher on the way up)
		if (predicted && !isShot() && !isLanded()) return;
		super.setVelocityClient(x, y, z);
	}

	/** Rigidbody step: gravity, move with collisions, damped bounce. True when it hit something. */
	private boolean physicsStep(float dt) {
		// velocity in blocks per tick (Minecraft's unit, so the network carries it unclipped and exact)
		Vec3d v = getVelocity();
		if (!hasNoGravity()) v = v.add(0, -GRAVITY * Damage.SCALE_BLOCKS * dt * dt, 0);
		setVelocity(v);
		move(MovementType.SELF, v);
		// no bounce (no physics material): Minecraft's move already stops the colliding axis; slide to rest on the ground
		if (isOnGround()) setVelocity(getVelocity().multiply(0.6, 1, 0.6));
		return horizontalCollision || verticalCollision;
	}

	/** Coin.DelayedReflectRevolver: a beam hit the coin; it stops and reflects 0.1 s later. */
	public void shoot(ServerPlayerEntity shooter, Vec3d hitp) {
		if (isShot()) return;
		ricochets++;
		dataTracker.set(SHOT, true);
		setVelocity(Vec3d.ZERO);
		hitPoint = hitp;
		reflectIn = 0.1f;
		owner = shooter.getUuid();
	}

	/** Coin.ReflectRevolver, for one hit; repeats after 0.05 s while hitTimes remain. */
	private void reflectRevolver(ServerWorld world) {
		ServerPlayerEntity shooter = owner == null ? null : (ServerPlayerEntity) world.getPlayerByUuid(owner);
		Vec3d from = getPos();
		Vec3d start = hitPoint.equals(Vec3d.ZERO) ? from : hitPoint;
		double range = Damage.rangeBlocks();
		// 1. another coin in sight
		CoinEntity coin = null;
		double best = Double.MAX_VALUE;
		for (CoinEntity c : world.getEntitiesByClass(CoinEntity.class, getBoundingBox().expand(range), c -> c != this && c.isAlive() && !c.isShot())) {
			double d = c.squaredDistanceTo(this);
			if (d < best && canSee(world, from, c.getPos())) { best = d; coin = c; }
		}
		// style: the ricoshot only scores when this reflection goes to a mob (a coin-to-coin bounce hands its count on)
		boolean scored = false;
		if (coin != null && shooter != null) {
			coin.power = power + 1;
			coin.altBeam = altBeam;
			coin.ricochets += ricochets;
			coin.chain = chain;
			Net.toNear(world, from, new BeamPayload(3, -1, start, coin.getPos()));
			Net.toNear(world, from, new HitPayload(HitPayload.COIN_HIT, HitPayload.COIN, 0, coin.getPos()));
			coin.shoot(shooter, coin.getPos());
		} else if (altBeam == 0 && shooter != null && nearestGrenade(world, from, range) != null) {
			UKProjectileEntity g = nearestGrenade(world, from, range);
			// 2. Coin.ReflectRevolver: an explosive in sight (the player's grenades) before any enemy - the beam sets it off
			// (Grenade.Explode(super), no parry flash from a coin)
			Net.toNear(world, from, new BeamPayload(3, -1, start, g.getPos()));
			g.superExplode(world, shooter, 1f);
		} else {
			// 3. nearest living enemy not yet in this chain, head if it has one; a boss in sight comes before any other
			// (user: the Ender Dragon, not the Endermen around it, for the revolver's and the railcannon's reflections)
			LivingEntity target = null;
			best = Double.MAX_VALUE;
			boolean bossFound = false;
			for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(range), e -> e.isAlive() && e != shooter && !(e instanceof ServerPlayerEntity) && !chain.contains(e.getUuid()))) {
				boolean boss = Damage.isBoss(e);
				if (bossFound && !boss) continue;
				double d = e.squaredDistanceTo(this);
				if ((d < best || (boss && !bossFound)) && canSee(world, from, headOf(e))) { best = d; target = e; bossFound = boss; }
			}
			if (target != null && shooter != null && altBeam != 0) {
				// SpawnBeam(altBeam): the railcannon shot itself goes on to the target, with its own damage and pierce
				scored = true;
				chain.add(target.getUuid());
				fireAltBeam(world, shooter, from, headOf(target).subtract(from).normalize());
			} else if (target != null && shooter != null) {
				scored = true;
				chain.add(target.getUuid());
				Vec3d head = headOf(target);
				Vec3d dir = head.subtract(from).normalize();
				boolean wasAlive = target.isAlive();
				// DeliverDamage(weakPoint, …, power, crit 1) → head = power * 2
				Damage.deal(shooter, target, power * 2, dir, 10000f, com.ultracraft.net.HitPayload.COIN);
				boolean killed = wasAlive && !target.isAlive();
				Net.toNear(world, from, new BeamPayload(3, -1, start, head));
				Net.toNear(world, head, new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, HitPayload.COIN, 1, Damage.flags(target), head));
			} else {
				// 4. nothing: a beam straight ahead from where it was hit
				Vec3d dir = from.subtract(start);
				if (dir.lengthSquared() < 1e-6) dir = new Vec3d(0, -1, 0);
				dir = dir.normalize();
				if (altBeam != 0 && shooter != null) {
					fireAltBeam(world, shooter, from, dir);
					hitTimes--;
					if (hitTimes > 0) { reflectIn = 0.05f; return; }
					discard();
					return;
				}
				Vec3d end = from.add(dir.multiply(range));
				BlockHitResult block = world.raycast(com.ultracraft.weapon.Plants.ctx(from, end, this));
				if (block.getType() != HitResult.Type.MISS) end = block.getPos();
				com.ultracraft.weapon.Plants.shoot(world, shooter, from, end);
				Net.toNear(world, from, new BeamPayload(3, -1, start, end));
			}
		}
		if (shooter != null && scored) Net.toPlayer(shooter, new HitPayload(HitPayload.RICOSHOT, HitPayload.COIN, ricochets, 0, from));
		hitTimes--;
		if (hitTimes > 0) {
			// second hit of a split coin 0.05 s later; hitPoint stays the original so a target-less
			// reflection continues along the incoming beam, as in the game
			reflectIn = 0.05f;
			return;
		}
		discard();
	}

	private UKProjectileEntity nearestGrenade(ServerWorld world, Vec3d from, double range) {
		UKProjectileEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (UKProjectileEntity g : world.getEntitiesByClass(UKProjectileEntity.class, getBoundingBox().expand(range), UKProjectileEntity::liveGrenade)) {
			double d = g.squaredDistanceTo(this);
			if (d < bestD && canSee(world, from, g.getPos())) { bestD = d; best = g; }
		}
		return best;
	}

	private void fireAltBeam(ServerWorld world, ServerPlayerEntity shooter, Vec3d from, Vec3d dir) {
		com.ultracraft.weapon.Hitscan.Beam b = altBeam == 2 ? com.ultracraft.weapon.Hitscan.RAILCANNON_MALICIOUS : com.ultracraft.weapon.Hitscan.RAILCANNON;
		com.ultracraft.weapon.Hitscan.fire(shooter, from, dir, b, 0, false);
		if (altBeam == 2 && !com.ultracraft.weapon.Hitscan.lastHitCoin)
			com.ultracraft.weapon.Explosions.explode(world, shooter, com.ultracraft.weapon.Hitscan.lastEnd.subtract(dir.multiply(0.1)), com.ultracraft.weapon.Explosions.MALICIOUS, false, "railcannon2", null);
	}

	private static Vec3d headOf(LivingEntity e) {
		Box b = e.getBoundingBox();
		return new Vec3d(b.getCenter().x, b.maxY - (b.maxY - b.minY) * Damage.headFraction() * 0.5, b.getCenter().z);
	}

	private boolean canSee(ServerWorld world, Vec3d from, Vec3d to) {
		return world.raycast(com.ultracraft.weapon.Plants.ctx(from, to, this)).getType() == HitResult.Type.MISS;
	}

	/** Called by {@link Hitscan} when a beam sweep passes through this coin. */
	public boolean shootable() {
		// Coin.Start disables the colliders until StartCheckingSpeed (0.1 s)
		return !isShot() && !isLanded() && isAlive() && age >= com.ultracraft.weapon.Hitscan.COIN_ARM_SECONDS;
	}

	public boolean isLanded() {
		return dataTracker.get(LANDED);
	}

	@Override
	public boolean canHit() {
		return !isShot() && !isLanded();
	}

	@Override
	public void remove(RemovalReason reason) {
		if (!getWorld().isClient && Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] coin removed: {} age={} shot={}", reason, age, isShot());
		super.remove(reason);
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		power = nbt.contains("power") ? nbt.getFloat("power") : 2;
		age = nbt.getFloat("age");
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putFloat("power", power);
		nbt.putFloat("age", age);
	}

	@Override
	public boolean shouldRender(double distance) {
		return distance < 64 * 64;
	}
}
