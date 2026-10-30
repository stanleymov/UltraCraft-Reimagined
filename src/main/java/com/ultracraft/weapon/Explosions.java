package com.ultracraft.weapon;

import com.ultracraft.net.ExplosionPayload;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.LaunchPayload;
import com.ultracraft.net.Net;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Port of Explosion.cs for the server. The explosion sphere grows to `maxSize` (world radius, u); enemies
 * inside take `damage / 10 × enemyMult` points, ÷1.5 once the sphere passed half its size (so the outer
 * half of the radius), and are set on fire when `ignite`; the player is launched away (NewMovement.
 * LaunchFromPoint with strength 200, rocket jumps with 100 / ((n + 3) / 3)) and hurt by `damage` unless
 * the explosion is the "harmless" variant that rockets make on surfaces.
 */
public final class Explosions {
	public record Spec(int damage, float enemyMult, float maxSize, boolean ignite, int fxKind) {
		/** Grenade.Explode: maxSize (and speed) times m, as a frozen rocket's 1.5. */
		public Spec scaled(float m) { return m == 1 ? this : new Spec(damage, enemyMult, maxSize * m, ignite, fxKind); }
	}

	/** Explosion prefab: 35, ×1, size 6. */
	public static final Spec NORMAL = new Spec(35, 1f, 6f, true, 0);
	/** Explosion Super: 35, enemies ×2, size 12. */
	public static final Spec SUPER = new Spec(35, 2f, 12f, true, 1);
	/** Pump Charge overpump: Explosion ×1.5 size, damage 50. */
	public static final Spec OVERPUMP = new Spec(50, 1f, 9f, true, 0);
	/** Explosion Malicious Railcannon: 50, enemies ×1.25, size 13.5. */
	public static final Spec MALICIOUS = new Spec(50, 1.25f, 13.5f, true, 2);
	/** Explosion Wave Knuckleblaster: 10, size 12, push ×1.5, enemies only (canHit 2), speed 2.5. */
	public static final Spec KNUCKLEBLASTER = new Spec(10, 1f, 12f, false, 3);
	/** The Explosion prefab a parried / boosted projectile leaves where it hits: 35, size 6; pushes V1 without hurting. */
	public static final Spec PARRIED = new Spec(35, 1f, 6f, true, 0);
	/** Explosion Hammer Weak (Jackhammer pump shots): 35, size 4, enemies only. */
	// (fx 4: its own look - ExplosionShockwave 3, the shockwave texture at a faint 0.18 grey, added: a white see-through wave)
	public static final Spec HAMMER_WEAK = new Spec(35, 1f, 4f, true, 4);

	private Explosions() {}

	/**
	 * @param harmless the "Explosion Rocket Harmless" case: rockets on surfaces push the player without hurting them
	 */
	public static void explode(ServerWorld world, ServerPlayerEntity shooter, Vec3d pos, Spec spec, boolean harmless, String hitterWeapon, Entity source) {
		explode(world, shooter, pos, spec, harmless, true, hitterWeapon, source);
	}

	/** @param hurtPlayer false: V1 is only pushed (parried projectiles, the parried sonic boom and creeper blast) */
	public static void explode(ServerWorld world, ServerPlayerEntity shooter, Vec3d pos, Spec spec, boolean harmless, boolean hurtPlayer, String hitterWeapon, Entity source) {
		double radius = spec.maxSize() * Damage.SCALE_BLOCKS;
		double half = radius / 2;
		if (source != null) source.discard();
		Net.toNear(world, pos, new ExplosionPayload(spec.fxKind() | (harmless ? 8 : 0), (float) spec.maxSize(), pos));
		// ExplosionController.Start: gasoline within 3 voxels (a box) lights; every player explosion uses the default 3
		Gasoline.onExplosion(world, shooter, pos, 3);
		// the plants in the blast go (the user's ask; no other block is touched, as in ULTRAKILL)
		Plants.blast(world, shooter, pos, radius);
		for (Entity e : world.getOtherEntities(null, new Box(pos, pos).expand(radius))) {
			// an End crystal in the blast goes off
			if (e instanceof net.minecraft.entity.decoration.EndCrystalEntity crystal) {
				if (closestPoint(crystal.getBoundingBox(), pos).distanceTo(pos) <= radius) Damage.hitCrystal(shooter, crystal);
				continue;
			}
			if (!(e instanceof LivingEntity living) || !living.isAlive()) continue;
			Vec3d closest = closestPoint(living.getBoundingBox(), pos);
			double dist = closest.distanceTo(pos);
			if (dist > radius) continue;
			if (living instanceof ServerPlayerEntity p) {
				// Explosion.FixedUpdate: the sphere grows to maxSize; past half of it the damage is RoundToInt(damage / 1.5)
				// (35 -> 23, 50 -> 33; the user measured about 22 at the edge), and it is harmless once full grown. So V1
				// takes the whole damage within explosion.playerHurt (0.5) of the radius, the cut one out to
				// explosion.playerHurtOuter (1: all of it), and is pushed by all of it either way
				int hurt = 0;
				if (hurtPlayer && dist <= radius * ServerTune.get("explosion.playerHurtOuter", 1.0))
					hurt = dist <= radius * ServerTune.get("explosion.playerHurt", 0.5) ? spec.damage() : Math.round(spec.damage() / 1.5f);
				if (spec.fxKind() != 3 && spec.fxKind() != 4) launchPlayer(p, pos, dist, spec, harmless, hurt);
				continue;
			}
			if (shooter == null) continue;
			float points = spec.damage() / 10f * spec.enemyMult();
			if (dist > half) points /= 1.5f;
			boolean wasAlive = living.isAlive();
			Vec3d dir = living.getPos().subtract(pos).normalize();
			Damage.deal(shooter, living, points, dir, 5000f, spec == PARRIED ? Damage.PARRIED_BLAST
				: hitterWeapon != null && hitterWeapon.startsWith("rocket") ? Damage.ROCKET_BLAST : com.ultracraft.net.HitPayload.EXPLOSION);
			// Explosion.Collide: an enemy carrying gasoline starts burning it (StartBurning); others catch the usual fire
			if (spec.ignite() && !Gasoline.ignite(world, living, shooter)) living.setOnFireFor(4);
			boolean killed = wasAlive && !living.isAlive();
			Net.toNear(world, living.getPos(), new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, HitPayload.EXPLOSION, 0, Damage.flags(living), living.getPos()));
		}
	}

	/** Explosion.Collide's player branch → LaunchFromPoint on the client (V1 owns its velocity). */
	private static void launchPlayer(ServerPlayerEntity p, Vec3d pos, double dist, Spec spec, boolean harmless, int hurt) {
		int strength = 200;
		if (hurt <= 0 && !harmless) {
			Net.toPlayer(p, new LaunchPayload(pos, strength, spec.maxSize(), 0, false));
			return;
		}
		if (harmless) {
			// Explosion Rocket Harmless: its 35-damage sphere is switched off, only the push wave (damage 0, maxSize 8,
			// rocketExplosion) reaches V1; the client keeps rocketJumps (NewMovement resets it on the ground)
			// (the harmless prefab whatever the rocket's level; only a frozen rocket's x1.5 carries over)
			float scale = spec.maxSize() / (spec.fxKind() == 1 ? SUPER.maxSize() : NORMAL.maxSize());
			Net.toPlayer(p, new LaunchPayload(pos, 100, 8 * scale, 0, true));
			return;
		}
		Net.toPlayer(p, new LaunchPayload(pos, strength, spec.maxSize(), hurt, false));
		if (hurt > 0) {
			// 35 V1 hp = 7 HP; GetHurt(…, explosion) with its invincibility window
			p.damage(p.getDamageSources().explosion(null, null), hurt / 5f);
		}
	}

	private static Vec3d closestPoint(Box b, Vec3d p) {
		return new Vec3d(Math.max(b.minX, Math.min(b.maxX, p.x)), Math.max(b.minY, Math.min(b.maxY, p.y)), Math.max(b.minZ, Math.min(b.maxZ, p.z)));
	}
}
