package com.ultracraft.weapon;

import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * The Attractor's magnet (Harpoon.prefab's "Sphere" with Magnet.cs, a TimeBomb on the root) and what it does to
 * nails (Nail.FixedUpdate, Nail.GetTargetMagnet, Magnet.Update, Magnet.Launch). Positions in blocks, velocities in
 * blocks per second; the game's u and u/s go through {@link Damage#SCALE_BLOCKS} (the pull is linear in distance, so its
 * factor is the same in either unit; only the 1000 u²/s cut-off is scaled).
 */
public final class MagnetField {
	private static final double S = Damage.SCALE_BLOCKS;
	/** The trigger SphereCollider: radius 25 on "Sphere" (scale 10) under the harpoon root (scale 0.1). */
	public static final double RADIUS = 25 * S;
	/** "Sphere" sits 13.5 along the root's forward (x0.1): the field's centre. */
	public static final double CENTER = 1.35 * S;
	/** The spike (Plane002) reaches 39.2 along the root (x0.1): its tip. */
	public static final double TIP = 3.92 * S;
	/** TimeBomb.timer. */
	public static final double FUSE = 12;
	/** ULTRAKILL's fixed step (TimeManager Fixed Timestep 0.008, 125 Hz), the rate the steering runs at. */
	public static final double FIXED_DT = 0.008;
	/** Magnet.Launch: AddForce(dir x strength x 10) on a 0.2 mass nail over one step = 120 u/s. */
	public static final double LAUNCH_SPEED = 300 * 10 / 0.2 * FIXED_DT * S;
	/** ULTRAKILL's Physics.gravity (PhysicsManager: -40 u/s²), on the held nails. */
	public static final double GRAVITY = -40 * S;
	/** Magnet.strength, the nail Rigidbody's mass. */
	private static final double STRENGTH = 300, NAIL_MASS = 0.2;
	/** Magnet.Update only pulls while |v . (m - p)| < 1000 (u/s x u). */
	private static final double PULL_CUTOFF = 1000 * S * S;

	private MagnetField() {}

	/** An entity's facing (yaw / pitch as UKProjectileEntity.face sets them) as a unit vector. */
	public static Vec3d forward(float yawDeg, float pitchDeg) {
		double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
		return new Vec3d(Math.sin(y) * Math.cos(p), Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/** The field's centre for a harpoon whose root (the tail end) is at `root`, pointing `fwd`. */
	public static Vec3d center(Vec3d root, Vec3d fwd) {
		return root.add(fwd.multiply(CENTER));
	}

	/**
	 * Which held magnet the nail circles. Nail.GetTargetMagnet takes the nearest; magnet.order 1 takes them in the order
	 * they caught the nail instead (the oldest first).
	 */
	public static int target(Vec3d pos, List<Vec3d> held, boolean inOrder) {
		if (inOrder || held.size() < 2) return 0;
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < held.size(); i++) {
			double d = held.get(i).squaredDistanceTo(pos);
			if (d < bd) { bd = d; best = i; }
		}
		return best;
	}

	/**
	 * One fixed step of a nail held by magnets: {pos, vel} in, {pos, vel} out. Magnet.Update pulls with
	 * (m - p) x (R - d) / R x 50 x strength each frame (x dt, so x 0.02 over a step) while |v . (m - p)| is under 1000;
	 * Nail.FixedUpdate then turns the velocity to 85 degrees from the way to the target magnet, about the world up and
	 * keeping its speed (RotateTowards with no limit, the side it is already going); the Rigidbody adds the pull / mass
	 * and gravity and moves.
	 */
	public static Vec3d[] step(Vec3d pos, Vec3d vel, List<Vec3d> held, int target, double gravity) {
		return step(pos, vel, held, target, gravity, 1);
	}

	/** step() with the pull scaled (live magnet.pull, 1 = the game's). */
	public static Vec3d[] step(Vec3d pos, Vec3d vel, List<Vec3d> held, int target, double gravity, double pullScale) {
		return step(pos, vel, held, target, gravity, pullScale, UP, 0);
	}

	private static final Vec3d UP = new Vec3d(0, 1, 0);

	/**
	 * step() turning about `axis` (the game: world up) and losing `drag` of its speed per second. Both are by eye
	 * (magnet.tilt, magnet.drag): the game's circles close in on the spike as its nails scrape and knock into things,
	 * which the steps here leave out, and the circles slow with them (the 85-degree turn makes the circle's size go
	 * with the speed).
	 */
	public static Vec3d[] step(Vec3d pos, Vec3d vel, List<Vec3d> held, int target, double gravity, double pullScale, Vec3d axis, double drag) {
		return step(pos, vel, held, target, gravity, pullScale, axis, drag, 85);
	}

	/** step() turning `angleDeg` from the way to the magnet (the game: 85; smaller is a tighter, faster circle). */
	public static Vec3d[] step(Vec3d pos, Vec3d vel, List<Vec3d> held, int target, double gravity, double pullScale, Vec3d axis, double drag, double angleDeg) {
		Vec3d pull = Vec3d.ZERO;
		for (Vec3d m : held) {
			Vec3d to = m.subtract(pos);
			if (Math.abs(vel.dotProduct(to)) >= PULL_CUTOFF) continue;
			double d = to.length();
			pull = pull.add(to.multiply((RADIUS - d) / RADIUS * 50 * STRENGTH * FIXED_DT * FIXED_DT / NAIL_MASS * pullScale));
		}
		Vec3d to = held.get(target).subtract(pos);
		double speed = vel.length();
		if (to.lengthSquared() > 1e-12 && speed > 1e-9) {
			Vec3d n = to.normalize();
			Vec3d a = rot(n, axis, angleDeg), b = rot(n, axis, -angleDeg);
			vel = (a.dotProduct(vel) >= b.dotProduct(vel) ? a : b).multiply(speed * Math.max(0, 1 - drag * FIXED_DT));
		}
		vel = vel.add(pull).add(0, gravity * FIXED_DT, 0);
		return new Vec3d[] {pos.add(vel.multiply(FIXED_DT)), vel};
	}

	/** v turned deg about the unit `axis` (Rodrigues; about world up this is Quaternion.Euler(0, deg, 0) * v up to its sign). */
	private static Vec3d rot(Vec3d v, Vec3d k, double deg) {
		double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
		return v.multiply(c).add(k.crossProduct(v).multiply(s)).add(k.multiply(k.dotProduct(v) * (1 - c)));
	}

	/** A nail's own turning axis: world up leaned up to `tiltDeg` in a direction fixed by its id (the same on both sides). */
	public static Vec3d axisFor(int id, double tiltDeg) {
		long h = id * 0x9E3779B97F4A7C15L;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		double u = (h & 0xFFFF) / 65535.0, w = ((h >>> 16) & 0xFFFF) / 65535.0;
		double tilt = Math.toRadians(tiltDeg) * Math.sqrt(u), az = w * Math.PI * 2;
		return new Vec3d(Math.sin(tilt) * Math.cos(az), Math.cos(tilt), Math.sin(tilt) * Math.sin(az));
	}
}
