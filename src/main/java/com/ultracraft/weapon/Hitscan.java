package com.ultracraft.weapon;

import com.ultracraft.net.BeamPayload;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import com.ultracraft.entity.CoinEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Server-side port of RevolverBeam.Shoot for Minecraft: a sphere cast along the camera ray that stops at
 * blocks and hits up to `hitAmount` living things (max `maxHitsPerTarget` each), with ULTRAKILL's damage
 * model (head = damage * (1 + crit), body = damage) scaled to Minecraft HP by {@link Damage#hpPerDamage()}.
 */
public final class Hitscan {
	/**
	 * The coin's trigger SphereCollider radius, game units. RevolverBeam.Shoot sphere-casts its radius (0.4 single,
	 * 0.6 piercing, 1.2 railcannon) against enemyLayerMask, which includes the Limb layer (10) the coin is on, and
	 * Physics.queriesHitTriggers is on: a beam hits a coin when its line passes within radius + 1 u of the centre.
	 */
	public static final double COIN_TRIGGER_RADIUS = 1.0;
	/** Coin.Start disables the coin's colliders; StartCheckingSpeed turns them on 0.1 s later. */
	public static final float COIN_ARM_SECONDS = 0.1f;

	/** The trigger radius in blocks (the game: 1 u). */
	public static double coinTriggerBlocks() {
		return COIN_TRIGGER_RADIUS * Damage.SCALE_BLOCKS;
	}

	/** The mobs the player's next beam shot met as the player saw them (client.weapon.HitView). */
	private record ViewHit(int id, Vec3d point, boolean head) {}
	private record ViewHits(List<ViewHit> hits, long tick) {}
	private static final java.util.Map<java.util.UUID, ViewHits> viewHits = new java.util.HashMap<>();

	public static void storeViewHit(ServerPlayerEntity p, int id, Vec3d point, boolean head) {
		long now = p.getServerWorld().getTime();
		ViewHits v = viewHits.get(p.getUuid());
		if (v == null || now - v.tick() > 2) viewHits.put(p.getUuid(), v = new ViewHits(new ArrayList<>(), now));
		if (id >= 0) v.hits().add(new ViewHit(id, point, head));
	}

	private record CoinClaim(Vec3d point, Vec3d coinPos, long tick) {}
	private static final java.util.Map<java.util.UUID, CoinClaim> claims = new java.util.HashMap<>();

	public static void storeCoinClaim(ServerPlayerEntity p, Vec3d point, Vec3d coinPos) {
		claims.put(p.getUuid(), new CoinClaim(point, coinPos, p.getServerWorld().getTime()));
	}

	/**
	 * The shooter's claim for this shot, checked against the server's coins: a shootable coin within 4 blocks of
	 * where the client drew it, the claimed point on this beam and within the hit radius of that coin.
	 */
	private static Cand2 claimedCoin(ServerPlayerEntity shooter, ServerWorld world, Vec3d start, Vec3d d, double radius) {
		CoinClaim c = claims.remove(shooter.getUuid());
		if (c == null || world.getTime() - c.tick() > 4) return null;
		double along = c.point().subtract(start).dotProduct(d);
		if (along < 0 || start.add(d.multiply(along)).distanceTo(c.point()) > 0.5) return null;
		if (c.point().distanceTo(c.coinPos()) > radius + coinTriggerBlocks() + 0.25) return null;
		CoinEntity best = null;
		double bestD = 4;
		for (CoinEntity coin : world.getEntitiesByClass(CoinEntity.class, new Box(c.coinPos(), c.coinPos()).expand(4), CoinEntity::shootable)) {
			double dd = coin.getBoundingBox().getCenter().distanceTo(c.coinPos());
			if (dd < bestD) { bestD = dd; best = coin; }
		}
		if (best == null) return null;
		// the coin is where the shooter saw it: its reflected beam starts there
		Vec3d off = best.getPos().subtract(best.getBoundingBox().getCenter());
		best.setPosition(c.coinPos().add(off));
		return new Cand2(best, along, c.point());
	}

	private record Cand2(CoinEntity coin, double along, Vec3d point) {}
	public record Beam(float damage, float crit, int hitAmount, int maxHitsPerTarget, float radiusUnits, float force, int kind, boolean instantHits) {
		public Beam(float damage, float crit, int hitAmount, int maxHitsPerTarget, float radiusUnits, float force, int kind) {
			this(damage, crit, hitAmount, maxHitsPerTarget, radiusUnits, force, kind, kind == 2 || kind == 5);
		}
	}

	/** Revolver Beam: damage 1, 1 hit, radius 0.4 u, force 5000. */
	public static final Beam REVOLVER = new Beam(1f, 1f, 1, 1, 0.4f, 5000f, 0);
	/** Revolver Beam Super (charged Piercer): damage 1, crit 1.5, pierces 3 bodies, 3 hits per target, radius 0.6. */
	public static final Beam REVOLVER_SUPER = new Beam(1f, 1.5f, 3, 3, 0.6f, 10000f, 1);
	/** Revolver Beam Sharp (Sharpshooter): damage 1, crit 1, pierces everything, 1 hit per target, ricochets. */
	public static final Beam REVOLVER_SHARP = new Beam(1f, 1f, 999, 1, 0.6f, 10000f, 4); // kind 4: drawn red
	/** Revolver Beam Alternative: ×1.25 damage, 2 hits per target (strongAlt). */
	public static final Beam REVOLVER_ALT = new Beam(1.25f, 1f, 1, 2, 0.4f, 5000f, 0, true);
	public static final Beam REVOLVER_ALT_SUPER = new Beam(1.25f, 1.5f, 3, 2, 0.6f, 10000f, 1, true);
	public static final Beam REVOLVER_ALT_SHARP = new Beam(1.25f, 1f, 999, 2, 0.6f, 10000f, 4, true);
	/** Railcannon Beam: damage 2, force 50000, pierces 999, 4 hits per target, radius 1.2, kind 2 (railcannon). */
	public static final Beam RAILCANNON = new Beam(2f, 0f, 999, 4, 1.2f, 50000f, 2);
	/** Railcannon Beam Malicious: same beam, the explosion at the end does the work. */
	public static final Beam RAILCANNON_MALICIOUS = new Beam(2f, 0f, 999, 4, 1.2f, 50000f, 5); // kind 5: drawn as the Malicious beam
	/** Grenade Beam (Grenade.GrenadeBeam, the Impact Hammer hitting a grenade): damage 2, one body, force 50000. */
	public static final Beam GRENADE_BEAM = new Beam(2f, 0f, 1, 1, 0.4f, 50000f, 0);

	private Hitscan() {}

	/** Where the last beam ended (for the Malicious railcannon's explosion). */
	public static Vec3d lastEnd = Vec3d.ZERO;

	public record Hit(Entity entity, Vec3d point, boolean head, boolean killed) {}

	/** True when the last beam ended on a coin (it took the shot to reflect it). */
	public static boolean lastHitCoin;

	/** Fire a beam; returns the hits. Draws the beam and hit feedback for everyone nearby. */
	public static List<Hit> fire(ServerPlayerEntity shooter, Vec3d origin, Vec3d dir, Beam beam, int ricochets) {
		return fire(shooter, origin, dir, beam, ricochets, true);
	}

	/** @param fromGun false: a coin's reflected copy of the beam (drawn from the coin, no client coin claim) */
	public static List<Hit> fire(ServerPlayerEntity shooter, Vec3d origin, Vec3d dir, Beam beam, int ricochets, boolean fromGun) {
		lastHitCoin = false;
		ServerWorld world = shooter.getServerWorld();
		List<Hit> hits = new ArrayList<>();
		Vec3d start = origin;
		Vec3d d = dir.normalize();
		double range = Damage.rangeBlocks();
		int bounces = Math.max(0, ricochets);
		int bodiesPierced = 0;
		Vec3d beamStart = start;
		for (int segment = 0; segment <= bounces; segment++) {
			Vec3d end = start.add(d.multiply(range));
			BlockHitResult block = world.raycast(Plants.ctx(start, end, shooter));
			if (block.getType() != HitResult.Type.MISS) end = block.getPos();
			double radius = beam.radiusUnits() * Damage.SCALE_BLOCKS;
			// End crystals the beam passes through go off (they are not living: the search below skips them)
			for (var crystal : Damage.crystalsOn(world, start, end, radius)) Damage.hitCrystal(shooter, crystal);
			// (wide: a mob is also tested where it was over the last ticks, as the player saw it, which can be blocks away)
			Box sweep = new Box(start, end).expand(radius + 8);
			// (and the player's grenades: RevolverBeam on a Grenade - not the Grenade Beam a grenade itself became)
			List<Entity> candidates = world.getOtherEntities(shooter, sweep, e -> (e instanceof LivingEntity || (e instanceof CoinEntity c && c.shootable())
				|| (beam != GRENADE_BEAM && e instanceof com.ultracraft.entity.UKProjectileEntity g && g.liveGrenade())) && e.isAlive() && !e.isSpectator());
			record Cand(Entity e, double dist, Vec3d point, Boolean head) {}
			List<Cand> found = new ArrayList<>();
			// the shot itself, from the gun: the mobs are the ones the player saw it meet, where they were drawn (they are
			// drawn a few ticks behind their place here; a single-player mod, so the player's view is the truth)
			ViewHits view = null;
			if (segment == 0 && fromGun) {
				view = viewHits.remove(shooter.getUuid());
				if (view != null && world.getTime() - view.tick() > 4) view = null;
			}
			// coins: the shooter's client decides what it saw (only for the first segment, the shot itself)
			// railcannon beams (and coin reflections) find coins on the server: only the revolver's client claims them
			boolean serverCoins = beam.kind() == 2 || beam.kind() == 5 || !fromGun;
			if (segment == 0 && !serverCoins) {
				Cand2 cl = claimedCoin(shooter, world, start, d, radius);
				if (cl != null && cl.along() <= start.distanceTo(end) + 0.5) found.add(new Cand(cl.coin(), cl.along(), cl.point(), null));
			}
			for (Entity e : candidates) {
				if (e instanceof CoinEntity && segment == 0 && !serverCoins) continue;
				if (e instanceof com.ultracraft.entity.UKProjectileEntity) {
					// a grenade: the beam's sphere cast against its catch sphere
					Vec3d c = e.getPos();
					double r = radius + com.ultracraft.entity.UKProjectileEntity.grenadeTrigger();
					Vec3d seg = end.subtract(start);
					double len2 = seg.lengthSquared();
					double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, c.subtract(start).dotProduct(seg) / len2));
					Vec3d closest = start.add(seg.multiply(t));
					if (closest.distanceTo(c) <= r) found.add(new Cand(e, t * Math.sqrt(len2), closest, null));
					continue;
				}
				if (e instanceof CoinEntity) {
					// sphere-cast vs the coin's 1 u trigger sphere: the segment within (beam radius + 1 u) of the centre
					Vec3d c = e.getBoundingBox().getCenter();
					double r = radius + coinTriggerBlocks();
					Vec3d seg = end.subtract(start);
					double len2 = seg.lengthSquared();
					double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, c.subtract(start).dotProduct(seg) / len2));
					Vec3d closest = start.add(seg.multiply(t));
					double miss = closest.distanceTo(c);
					if (miss <= r) {
						// where the cast sphere first touches it: back along the beam from the closest approach
						double back = Math.sqrt(Math.max(0, r * r - miss * miss));
						double along = Math.max(0, t * Math.sqrt(len2) - back);
						found.add(new Cand(e, along, start.add(seg.normalize().multiply(along)), null));
					}
					continue;
				}
				if (view != null) continue;
				var opt = hitPoint(e, radius, start, end);
				if (opt.isPresent()) found.add(new Cand(e, opt.get().distanceTo(start), opt.get(), null));
			}
			if (view != null) for (ViewHit vh : view.hits()) {
				if (!(world.getEntityById(vh.id()) instanceof LivingEntity le) || !le.isAlive() || le == shooter) continue;
				double along = vh.point().subtract(start).dotProduct(d);
				if (along > start.distanceTo(end) + 1) continue;
				found.add(new Cand(le, Math.max(0, along), vh.point(), vh.head()));
			}
			found.sort(Comparator.comparingDouble(Cand::dist));
			for (Cand c : found) {
				if (bodiesPierced >= beam.hitAmount()) break;
				if (c.e() instanceof com.ultracraft.entity.UKProjectileEntity g) {
					// RevolverBeam on a Grenade: ParryFlash, then Explode(super) - a railcannon beam's at twice the size
					// (ultrabooster)
					if (!g.liveGrenade()) continue;
					ParryServer.flash(shooter, c.point());
					g.superExplode(world, shooter, beam.kind() == 2 || beam.kind() == 5 ? 2f : 1f);
					bodiesPierced++;
					if (beam.hitAmount() == 1) { end = c.point(); break; }
					continue;
				}
				if (c.e() instanceof CoinEntity coin) {
					// the coin takes the shot and reflects it (Coin.DelayedReflectRevolver); a railcannon beam is kept as
					// its altBeam, so the reflection is a copy of the railcannon shot
					coin.shoot(shooter, c.point());
					if (beam.kind() == 2 || beam.kind() == 5) coin.altBeam = beam == RAILCANNON_MALICIOUS ? 2 : 1;
					lastHitCoin = true;
					Net.toNear(world, c.point(), new HitPayload(HitPayload.COIN_HIT, HitPayload.REVOLVER, 0, c.point()));
					end = c.point();
					bodiesPierced = beam.hitAmount();
					break;
				}
				LivingEntity target = (LivingEntity) c.e();
				boolean head = c.head() != null ? c.head() : isHeadshot(target, c.point(), start, d);
				float mult = head ? beam.damage() * (1 + beam.crit()) : beam.damage();
				int times = beam.instantHits() ? beam.maxHitsPerTarget() : 1; // railcannon / alt revolver hits land in one frame; super-beam multi-hits are coin splits
				boolean wasAlive = target.isAlive();
				// railcannon beams hit as "railcannon" (the electric one, kind 2, with the Electricity attribute too)
				// (the revolvers' right-click shots - Piercer charged, Sharpshooter - are their own kind for the bosses)
				int hitter = beam.kind() == 2 || beam.kind() == 5 ? HitPayload.RAILCANNON : beam.kind() == 1 || beam.kind() == 4 ? Damage.REVOLVER_ALT_FIRE : HitPayload.REVOLVER;
				for (int i = 0; i < times; i++) Damage.deal(shooter, target, mult, d, beam.force(), hitter, beam.kind() == 2, c.point());
				boolean killed = wasAlive && !target.isAlive();
				hits.add(new Hit(target, c.point(), head, killed));
				bodiesPierced++;
				Net.toNear(world, c.point(), new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, beam.kind() == 2 || beam.kind() == 5 ? HitPayload.RAILCANNON : HitPayload.REVOLVER, head ? 1 : 0, Damage.flags(target), c.point()));
				if (beam.hitAmount() == 1) { end = c.point(); break; }
			}
			// magnets in the way: the spike is a precisionOnly Breakable, which revolver beams (railcannon shots and coin
			// ricochets among them) break; the harpoon goes with it and its magnet goes off
			final Vec3d segA = start, segB = end;
			for (com.ultracraft.entity.UKProjectileEntity m : world.getEntitiesByClass(com.ultracraft.entity.UKProjectileEntity.class, new Box(start, end).expand(radius + 3),
					e -> e.kind() == com.ultracraft.entity.ProjectileKind.MAGNET && e.isAlive()))
				if (m.spikeCrossedBy(segA, segB, radius)) m.breakMagnet(world);
			// the plants on the beam, up to where it stopped (Plants: it went on through them)
			List<net.minecraft.util.math.BlockPos> burnt = Plants.shoot(world, shooter, start, end);
			// the Malicious railcannon sets them and where it lands on fire (BlockFire; user)
			if (beam == RAILCANNON_MALICIOUS)
				BlockFire.malicious(world, shooter, burnt, block.getType() != HitResult.Type.MISS && end.equals(block.getPos()) ? block : null);
			boolean off = Net.offhand(shooter);
			// (only the shot out of the gun is the shooter's: the client draws that one from the barrel; a ricochet's
			// segment starts where it bounced - user: all three Sharpshooter lines came from V1)
			boolean fromBarrel = beamStart.equals(start) && fromGun;
			Net.toNear(world, start, new BeamPayload(beam.kind(), fromBarrel ? shooter.getId() : -1, fromBarrel ? muzzle(shooter, origin, off) : start, end, off));
			lastEnd = end;
			boolean stoppedByBody = bodiesPierced >= beam.hitAmount();
			if (block.getType() == HitResult.Type.MISS || bounces == segment || stoppedByBody) {
				if (block.getType() != HitResult.Type.MISS && !stoppedByBody) Net.toNear(world, end, new HitPayload(HitPayload.WALL_HIT, HitPayload.REVOLVER, 0, end));
				break;
			}
			// ricochet off the block face
			Net.toNear(world, end, new HitPayload(HitPayload.WALL_HIT, HitPayload.REVOLVER, 1, end));
			Vec3d n = Vec3d.of(block.getSide().getVector());
			d = d.subtract(n.multiply(2 * d.dotProduct(n))).normalize();
			start = end.add(d.multiply(0.01));
			bodiesPierced = 0;
		}
		if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] hitscan kind={} hits={}", beam.kind(), hits.size());
		return hits;
	}

	/** The beam is drawn from a little right/below the eye, like the gun barrel. */
	private static Vec3d muzzle(ServerPlayerEntity p, Vec3d origin, boolean offHand) {
		Vec3d look = p.getRotationVec(1f);
		Vec3d right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();
		// the off-hand gun is on the left
		return origin.add(right.multiply(offHand ? -0.25 : 0.25)).add(0, -0.2, 0).add(look.multiply(0.6));
	}

	/**
	 * Where a shot meets `e` (its box grown by r): the first point on start..end, or start inside it. A mob being
	 * whiplashed is tested along its last step (WeaponServer.pullSweep).
	 */
	public static java.util.Optional<Vec3d> hitPoint(Entity e, double r, Vec3d start, Vec3d end) {
		List<Box> boxes = seenBoxes(e);
		java.util.Optional<Vec3d> best = java.util.Optional.empty();
		for (Box b0 : boxes) {
			Box b = b0.expand(r);
			java.util.Optional<Vec3d> hp = b.contains(start) ? java.util.Optional.of(start) : b.raycast(start, end);
			if (hp.isPresent() && (best.isEmpty() || hp.get().squaredDistanceTo(start) < best.get().squaredDistanceTo(start))) best = hp;
		}
		return best;
	}

	/** Where `e` is and was lately as the player may have seen it: now, along its whiplash step, its last ticks' boxes. */
	public static List<Box> seenBoxes(Entity e) {
		List<Box> out = new ArrayList<>();
		out.add(e.getBoundingBox());
		Box[] sweep = WeaponServer.pullSweep(e);
		if (sweep != null) java.util.Collections.addAll(out, sweep);
		List<Box> hist = WeaponServer.recentBoxes(e);
		if (hist != null) out.addAll(hist);
		return out;
	}

	/** Head = within the top part of the box (ULTRAKILL has real head colliders; mobs have one box). */
	public static boolean isHeadshot(LivingEntity e, Vec3d point, Vec3d start, Vec3d dir) {
		Box b = e.getBoundingBox();
		// a moving mob: the box (now, along a whiplash step, or a few ticks back as the player saw it) nearest the hit
		List<Box> seen = seenBoxes(e);
		if (seen.size() > 1) {
			double bd = Double.MAX_VALUE;
			for (Box s : seen) {
				Vec3d c = new Vec3d(Math.max(s.minX, Math.min(s.maxX, point.x)), Math.max(s.minY, Math.min(s.maxY, point.y)), Math.max(s.minZ, Math.min(s.maxZ, point.z)));
				double d = c.squaredDistanceTo(point) * 4 + s.getCenter().squaredDistanceTo(point) * 0.01;
				if (d < bd) { bd = d; b = s; }
			}
		}
		double headFrac = Damage.headFraction();
		double headY = b.maxY - (b.maxY - b.minY) * headFrac;
		// take the ray's height where it passes the entity's centre
		Vec3d center = b.getCenter();
		double t = Math.max(0, center.subtract(start).dotProduct(dir));
		double yAt = start.y + dir.y * t;
		return Math.max(point.y, yAt) >= headY;
	}
}
