package com.ultracraft.weapon;

import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.net.FirePayload;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Optional;

/**
 * Server side of the Shotgun, Nailgun, Railcannon and Rocket Launcher: the client runs the timing and
 * charges (ArsenalClient) and sends Fire packets; this validates the held item and spawns the game's
 * projectiles / beams / explosions with their numbers.
 */
public final class ArsenalServer {
	/** Run on the next server tick (the off-hand copy's delayed actions). */
	private static final List<Runnable> LATER = new java.util.ArrayList<>();

	static void tickLater() {
		if (LATER.isEmpty()) return;
		List<Runnable> now = new java.util.ArrayList<>(LATER);
		LATER.clear();
		for (Runnable r : now) r.run();
	}

	private ArsenalServer() {}

	/**
	 * Rocket rides, whatever V1 holds: mode 4 Grenade.PlayerRideStart (dir.x = rocketRides), 5 PlayerRideEnd, 6 the
	 * rider's Move input (dir.x right, dir.y forward) for Grenade.LateUpdate's steering.
	 */
	public static void rideMessage(FirePayload msg, ServerPlayerEntity p) {
		if (!(p.getServerWorld().getEntityById(msg.arg()) instanceof UKProjectileEntity r) || r.kind() != ProjectileKind.ROCKET) return;
		switch (msg.mode()) {
			case 4 -> r.rideStart(p.getUuid(), (int) msg.dir().x);
			case 5 -> { if (p.getUuid().equals(r.rider)) r.rider = null; }
			case 6 -> { if (p.getUuid().equals(r.rider)) r.steer(msg.dir().x, msg.dir().y); }
			default -> {}
		}
	}

	public static void onFire(FirePayload msg, ServerPlayerEntity p) {
		ItemStack held = p.getMainHandStack();
		WeaponKind kind = UKWeaponItem.kindOf(held);
		if (kind == null) return;
		int variation = UKWeaponItem.variation(held);
		if (msg.mode() == FirePayload.MODE_VARIATION) {
			// only to an owned variation (the shop)
			if (Shop.owns(p, kind, Math.floorMod(msg.arg(), 3))) UKWeaponItem.setVariation(held, msg.arg());
			return;
		}
		ServerWorld world = p.getServerWorld();
		Vec3d origin = msg.origin();
		Vec3d dir = msg.dir().normalize();
		switch (msg.weapon()) {
			case FirePayload.SHOTGUN -> shotgun(world, p, variation, msg.mode(), msg.arg(), origin, dir);
			case FirePayload.NAILGUN -> nailgun(world, p, variation, msg.mode(), msg.arg(), origin, dir);
			case FirePayload.RAILCANNON -> railcannon(world, p, variation, msg.mode(), origin, dir);
			case FirePayload.ROCKET -> rocket(world, p, variation, msg.mode(), msg.arg(), origin, dir);
			default -> {}
		}
	}

	// ------------------------------------------------------------------ Shotgun

	/**
	 * mode 0: Shoot (arg = pump charge 0..3; 12 pellets, Pump Charge 10/16/24, 3 = overpump explosion);
	 * mode 1: ShootSinks (arg = grenadeForce 0..60); mode 2: saw zone tick (Sawed-On revving); mode 3: ShootSaw.
	 */
	private static void shotgun(ServerWorld world, ServerPlayerEntity p, int variation, int mode, int arg, Vec3d origin, Vec3d dir) {
		switch (mode) {
			case 0 -> {
				int charge = variation == 1 ? Math.max(0, Math.min(3, arg)) : 0;
				// point-blank zone: Bodies within 4 u take 4 damage ("shotgunzone")
				double reach = 4 * Damage.SCALE_BLOCKS;
				for (Entity e : world.getOtherEntities(p, new Box(origin, origin.add(dir.multiply(reach))).expand(0.6), ent -> ent instanceof LivingEntity && ent.isAlive())) {
					Optional<Vec3d> hit = e.getBoundingBox().expand(0.3).raycast(origin, origin.add(dir.multiply(reach)));
					if (hit.isEmpty()) continue;
					// not through a wall: the zone only reaches what the barrel can see
					BlockHitResult wall = world.raycast(Plants.ctx(origin, hit.get(), p));
					if (wall.getType() != HitResult.Type.MISS) continue;
					LivingEntity t = (LivingEntity) e;
					boolean wasAlive = t.isAlive();
					// Zombie.GotHit "shotgunzone" on an attacking enemy: double damage and a parry
					// (a creeper about to blow: a shooting parry too, its blast becomes V1's below)
					boolean creeper = ParryServer.tryParryCreeper(p, t);
					boolean parried = creeper || ParryServer.tryParryMelee(p, t);
					// Zombie / Machine / Statue.GetHurt: on an enemy not open to a parry the zone's damage is 0 - no blood -
					// unless it would kill it (target == chest && health - num <= 0). It still knocks the enemy back
					// (KnockBack(force / 100) comes first) and turns it on V1 (DeliverDamage: target = the player)
					if (!parried && !Damage.wouldKill(p, t, 4f, HitPayload.SHOTGUNZONE)) {
						Damage.shove(p, t, dir, 10000f);
						continue;
					}
					Damage.deal(p, t, parried ? 8f : 4f, dir, 10000f, HitPayload.SHOTGUNZONE, hit.get());
					if (parried) ParryServer.shotSuccess(p, hit.get());
					boolean killed = wasAlive && !t.isAlive();
					Net.toNear(world, hit.get(), new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, HitPayload.SHOTGUNZONE, 0, Damage.flags(t), hit.get()));
					if (creeper) ParryServer.creeperBlast(p, t);
				}
				if (variation == 1 && charge == 3) {
					// overpump: Explosion ×1.5 size, damage 50 one unit in front (or at the wall)
					Vec3d at = origin.add(dir.multiply(1 * Damage.SCALE_BLOCKS));
					BlockHitResult b = world.raycast(Plants.ctx(origin, at, p));
					if (b.getType() != HitResult.Type.MISS) at = b.getPos().subtract(dir.multiply(0.05));
					Explosions.explode(world, p, at, Explosions.OVERPUMP, false, "shotgun1", null);
					return;
				}
				int num = switch (charge) { case 1 -> 16; case 2 -> 24; default -> variation == 1 ? 10 : 12; };
				float spread = 10f * (variation == 1 ? (charge == 0 ? 1 / 1.5f : charge == 2 ? 2f : 1f) : 1f);
				for (int i = 0; i < num; i++) {
					Vec3d d = scatter(dir, spread, world.random);
					double speed = 75 + (world.random.nextDouble() * 2 - 1) * 10;
					UKProjectileEntity.spawn(world, p, ProjectileKind.PELLET, origin, d, speed);
				}
			}
			case 1 -> {
				// Core Eject: the core at 0.5 u in front, velocity force + 10 along the look (+ up tilt force·0.002)
				float force = Math.max(0, Math.min(60, arg));
				Vec3d v = dir.add(0, force * 0.002, 0);
				Vec3d at = origin.add(dir.multiply(0.5 * Damage.SCALE_BLOCKS));
				UKProjectileEntity core = UKProjectileEntity.spawn(world, p, ProjectileKind.CORE, at, v, force + 10);
				core.damage = 0;
			}
			case 2 -> {
				// Sawed-On rev: a HurtZone in front of the barrel, ticked by the client while charging (damage 0.25 per tick, 2 u)
				double reach = 2 * Damage.SCALE_BLOCKS;
				for (Entity e : world.getOtherEntities(p, new Box(origin, origin.add(dir.multiply(reach))).expand(0.5), ent -> ent instanceof LivingEntity && ent.isAlive())) {
					LivingEntity t = (LivingEntity) e;
					boolean wasAlive = t.isAlive();
					Damage.deal(p, t, 0.25f, dir, 100f, HitPayload.SAW);
					boolean killed = wasAlive && !t.isAlive();
					Net.toNear(world, t.getPos(), new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, HitPayload.SAW, 0, Damage.flags(t), t.getPos()));
				}
			}
			case 4 -> hammerImpact(world, p, variation, arg & 3, arg >> 2, origin, dir);
			case 5 -> {
				// ShotgunHammer.ThrowNade: the grenade at the camera + 2 u forward - 0.5 u up, thrown with forward x 3 + up x
				// 7.5 + V1's own velocity (the client works it out: dir, speed in arg / 100 u/s)
				UKProjectileEntity core = UKProjectileEntity.spawn(world, p, ProjectileKind.CORE, origin, dir, arg / 100.0);
				core.damage = 0;
			}
			case 3 -> {
				// ShootSaw: the chainsaw flies out as a saw projectile (1.5 damage, 3 hits) — modelled as a heavy nail
				// Shotgun.ShootSaw: the ChainsawProjectile 0.5 u ahead with (grenadeForce + 10) × 1.5 u/s, chained to the gun
				// the off-hand gun's saw is chained to that gun, and like every dual wield copy's ShootSaw it is Invoked
				// wid.delay later (DualWieldPickup: 0.05 s, one tick): the two saws fly apart instead of as one
				boolean off = Net.offhand(p);
				Vec3d at = p.getPos();
				Runnable throwSaw = () -> {
					if (p.isRemoved() || p.getServerWorld() != world) return;
					// (from where the player is by then: ShootSaw reads the camera when it runs)
					Vec3d from = origin.add(p.getPos().subtract(at)).add(dir.multiply(0.5 * Damage.SCALE_BLOCKS));
					UKProjectileEntity saw = UKProjectileEntity.spawn(world, p, ProjectileKind.CHAINSAW, from, dir, (Math.max(0, Math.min(60, arg)) + 10) * 1.5, e -> e.setOffHand(off));
					saw.damage = 1.5f;
					saw.chainsaw = true;
				};
				if (off) LATER.add(throwSaw);
				else throwSaw.run();
			}
			default -> {}
		}
	}

	/**
	 * ShotgunHammer.ImpactRoutine / DeliverDamage / ImpactEffects: chainsaws in the swing are knocked out again, a
	 * grenade becomes its Grenade Beam, an enemy takes the "hammerzone" 4 + 3 / 6 / 10 by tier (force 50000 x tier +
	 * 50000), and the pump's charge goes off where the swing landed, whatever it met.
	 */
	private static void hammerImpact(ServerWorld world, ServerPlayerEntity p, int variation, int tier, int pumpCharge, Vec3d origin, Vec3d dir) {
		double S = Damage.SCALE_BLOCKS, reach = 8 * S, radius = 2.5 * S;
		Vec3d end = origin.add(dir.multiply(reach));
		int result = 0;
		// chainsaws (layer 14, triggers too): at the camera or on the 8 u ray, sent out again from the camera + forward
		// at 105 u/s toward the parry look target (Chainsaw.GetPunched)
		double sawR = ProjectileKind.CHAINSAW.scale * 0.5 * S + ServerTune.get("hammer.sawRadius", 0.75) * S;
		for (Entity e : world.getOtherEntities(p, new Box(origin, end).expand(sawR + 1.5), ent -> ent instanceof UKProjectileEntity u && u.kind() == ProjectileKind.CHAINSAW && u.ownedBy(p) && ent.isAlive())) {
			UKProjectileEntity saw = (UKProjectileEntity) e;
			Vec3d c = saw.getPos();
			double along = c.subtract(origin).dotProduct(dir);
			boolean met = saw.sawCaught() || c.distanceTo(origin) <= sawR || (along >= 0 && along <= reach && c.distanceTo(origin.add(dir.multiply(along))) <= sawR);
			if (!met) continue;
			Vec3d target = WeaponServer.parryLookTarget(p, origin, dir);
			Vec3d from = origin.add(dir.multiply(1 * S));
			saw.getPunched(from, target.subtract(from));
			result |= HitPayload.SWING_SAW;
		}
		BlockHitResult block = world.raycast(Plants.ctx(origin, end, p));
		double wallDist = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : block.getPos().distanceTo(origin);
		// the 8 u ray: the first enemy, or a grenade in front of it (or at the camera)
		double bestDist = wallDist;
		LivingEntity best = null;
		Vec3d point = null;
		List<Entity> near = world.getOtherEntities(p, new Box(origin, end).expand(radius), ent -> ent instanceof LivingEntity && ent.isAlive());
		for (Entity e : near) {
			var hit = e.getBoundingBox().raycast(origin, end);
			if (hit.isPresent() && hit.get().distanceTo(origin) < bestDist) { bestDist = hit.get().distanceTo(origin); best = (LivingEntity) e; point = hit.get(); }
		}
		// the plants the swing goes through, up to the enemy or the wall it met (user: the hammer broke none)
		Plants.shootStep(world, p, origin, end, block, point);
		UKProjectileEntity nade = null;
		double nadeDist = Math.min(bestDist, wallDist), trig = UKProjectileEntity.grenadeTrigger();
		// (a Rocket is a Grenade too - in ObjectTracker.grenadeList, so the hammer's HitNade sends it off as the same
		// Grenade Beam: a frozen rocket hit by the hammer)
		for (UKProjectileEntity g : world.getEntitiesByClass(UKProjectileEntity.class, new Box(origin, end).expand(trig + 1),
				g -> g.liveGrenade() || (g.kind() == ProjectileKind.ROCKET && g.isAlive()))) {
			Vec3d c = g.getPos();
			double along = c.subtract(origin).dotProduct(dir);
			boolean met = c.distanceTo(origin) <= trig || (along >= 0 && along <= reach && c.distanceTo(origin.add(dir.multiply(along))) <= trig);
			if (met && Math.max(0, along) <= nadeDist) { nadeDist = Math.max(0, along); nade = g; }
		}
		Vec3d hitPos = null;
		if (nade != null) {
			// HitNade: TrueStop 0.25 (no flash), then the grenade's beam toward what the camera looks at
			hitPos = nade.getPos();
			nade.grenadeBeam(world, p, origin, dir);
			result |= HitPayload.SWING_NADE;
			best = null;
		} else if (best != null) hitPos = point;
		else if (block.getType() != HitResult.Type.MISS) hitPos = block.getPos();
		if (best == null) {
			// no enemy on the ray: the one closest to a 2.5 u sphere 2.5 u ahead, then a 2.5 u sphere cast on to 5.5 u
			// (the farthest along wins); both need line of sight
			Vec3d centre = origin.add(dir.multiply(2.5 * S));
			double num = radius;
			for (Entity e : near) {
				Vec3d cp = closestPoint(e.getBoundingBox(), centre);
				double d = cp.distanceTo(centre);
				if (d < num && visible(world, p, origin, cp)) { num = d; best = (LivingEntity) e; point = cp; }
			}
			double along = best == null ? -1 : dir.dotProduct(point.subtract(origin));
			for (Entity e : near) {
				Box b = e.getBoundingBox();
				Vec3d cp = null;
				double dmin = Double.MAX_VALUE;
				for (int i = 0; i <= 12; i++) {
					Vec3d c = centre.add(dir.multiply(3 * S * i / 12.0));
					Vec3d q = closestPoint(b, c);
					double d = q.distanceTo(c);
					if (d < dmin) { dmin = d; cp = q; }
				}
				if (dmin > radius || !visible(world, p, origin, cp)) continue;
				double a = dir.dotProduct(cp.subtract(origin));
				if (a > along) { along = a; best = (LivingEntity) e; point = cp; }
			}
			if (best != null) hitPos = point;
		}
		int tierFlags = tier << 2;
		if (best != null) {
			boolean wasAlive = best.isAlive();
			float dmg = tier == 2 ? 10f : tier == 1 ? 6f : 3f;
			float force = 50000f * tier + 50000f;
			// the "hammerzone" 4 only on a "Body"-tagged collider (ShotgunHammer: not the head or a limb); our mobs have no
			// limbs: anything below the head
			if (!Hitscan.isHeadshot(best, point, origin, dir)) Damage.deal(p, best, 4f, dir, force, HitPayload.HAMMER, point);
			if (best.isAlive()) Damage.deal(p, best, dmg, dir, force, HitPayload.HAMMER, point);
			boolean killed = wasAlive && !best.isAlive();
			Net.toNear(world, point, new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, HitPayload.HAMMER, 0, Damage.flags(best) | tierFlags, point));
			if (wasAlive) result |= HitPayload.SWING_STRONG;
		}
		// NewMovement.Launch off the environment (or a dead enemy), not when a grenade took the swing
		if ((result & HitPayload.SWING_NADE) == 0 && ((best == null && block.getType() != HitResult.Type.MISS) || (best != null && (result & HitPayload.SWING_STRONG) == 0)))
			result |= HitPayload.SWING_WALL;
		// ImpactEffects: 1 u back toward the camera from what was hit, or 2.5 u ahead
		Vec3d fx = hitPos != null ? hitPos.subtract(hitPos.subtract(origin).normalize().multiply(1 * S)) : origin.add(dir.multiply(2.5 * S));
		if (pumpCharge > 0) {
			// Explosion Hammer Weak (x2 its size at 2 pumps); 3 pumps: Explosion Super, which takes V1 too
			if (pumpCharge >= 3) Explosions.explode(world, p, fx, Explosions.SUPER, false, "hammer" + variation, null);
			else Explosions.explode(world, p, fx, Explosions.HAMMER_WEAK.scaled(pumpCharge == 2 ? 2f : 1f), true, "hammer" + variation, null);
		}
		Net.toPlayer(p, new HitPayload(HitPayload.SWING, HitPayload.HAMMER, result, tierFlags, fx));
	}

	private static Vec3d closestPoint(Box b, Vec3d p) {
		return new Vec3d(MathHelper.clamp(p.x, b.minX, b.maxX), MathHelper.clamp(p.y, b.minY, b.maxY), MathHelper.clamp(p.z, b.minZ, b.maxZ));
	}

	private static boolean visible(ServerWorld world, ServerPlayerEntity p, Vec3d from, Vec3d to) {
		if (from.squaredDistanceTo(to) < 1e-6) return true;
		return world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.MISS;
	}

	// ------------------------------------------------------------------ Nailgun

	/** mode 0: a nail (arg 1 = heated burst nail); mode 1: magnet harpoon; mode 3: the zapper's shock on mob `arg`. */
	private static void nailgun(ServerWorld world, ServerPlayerEntity p, int variation, int mode, int arg, Vec3d origin, Vec3d dir) {
		Vec3d at = origin.add(dir.multiply(1 * Damage.SCALE_BLOCKS));
		switch (mode) {
			case 0 -> {
				boolean heated = (arg & 1) != 0, saw = (arg & 2) != 0;
				if (saw) {
					// Sawblade Launcher: arg = heated | saw | multiHit << 2 | spread << 4 | hitAmount x 10 << 10 | fodder << 16
					Vec3d d = scatter(dir, ((arg >> 4) & 63) / 3f, world.random);
					ProjectileKind sk = heated ? ProjectileKind.SAWBLADE_HEATED : (arg & (1 << 16)) != 0 ? ProjectileKind.SAWBLADE_FODDER : ProjectileKind.SAWBLADE;
					// (from the Shootpoint the client sends as the origin)
					UKProjectileEntity blade = UKProjectileEntity.spawn(world, p, sk, origin, d, 200);
					blade.multiHit = Math.max(1, (arg >> 2) & 3);
					blade.setSawVariation(variation);
					// Nailgun.Shoot: the Overheat's saws 3 hits cold down to 1 hot, 1 with no heat sink or from the Jump Start
					int tenths = (arg >> 10) & 63;
					if (tenths > 0) blade.hitAmount = tenths / 10f;
					return;
				}
				float spread = variation == 1 ? 20f : 10f; // Overheat prefab spread 20, others 10; burst ×2
				if (heated) spread *= 2;
				// arg bit 0x100: the shooter's client already spread the nail (it draws its own nails on that path)
				Vec3d d = (arg & 0x100) != 0 ? dir.normalize() : scatter(dir, spread / 3f, world.random);
				ProjectileKind k = heated ? ProjectileKind.NAIL_HEATED : variation == 0 ? ProjectileKind.NAIL_FODDER : ProjectileKind.NAIL;
				// Nailgun.Shoot: the nail starts at the barrel's shootPoint (the client sends that point as the origin)
				// (magnets catch it on its way: UKProjectileEntity.catchMagnets)
				UKProjectileEntity.spawn(world, p, k, origin, d, 200);
			}
			case 1 -> {
				// WeaponCharges.wc.magnets: three out at most
				// ShootMagnet: from the camera, 1 u ahead (nail.magnetAhead: further, by the user's call - at 1 u it flashed in
				// the eyes); right at the camera when a wall is closer than that
				double ahead = ServerTune.get("nail.magnetAhead", 2) * Damage.SCALE_BLOCKS;
				BlockHitResult wall = world.raycast(new RaycastContext(origin, origin.add(dir.multiply(ahead)), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
				Vec3d mAt = wall.getType() == HitResult.Type.MISS ? origin.add(dir.multiply(ahead)) : origin;
				if (ownMagnets(world, p) < 3) UKProjectileEntity.spawn(world, p, ProjectileKind.MAGNET, mAt, dir, 100);
			}
			case 3 -> {
				// Zapper.Zap (the zapper itself flies, latches and charges on the client: ZapperClient): 10 damage with
				// an upward 1000 force on the mob it was attached to
				net.minecraft.entity.Entity e = world.getEntityById(arg);
				if (!(e instanceof net.minecraft.entity.LivingEntity le) || !le.isAlive() || le.squaredDistanceTo(p) > 64 * 64) return;
				Vec3d point = le.getPos().add(0, le.getHeight() / 2, 0);
				// (Zapper.damage 10, +0.5 for every sawblade that hit the zapped enemy: the client sends it as dir.y)
				float zd = (float) Math.max(10, Math.min(40, dir.y));
				Damage.deal(p, le, zd, new Vec3d(0, 1, 0), 1000f, HitPayload.ZAPPER);
				Net.toNear(world, point, new HitPayload(le.isAlive() ? HitPayload.ENEMY_HIT : HitPayload.KILL, HitPayload.ZAPPER, 0, Damage.flags(le), point));
			}
			default -> {}
		}
	}

	private static int ownMagnets(ServerWorld world, ServerPlayerEntity p) {
		return world.getEntitiesByClass(UKProjectileEntity.class, p.getBoundingBox().expand(256), e -> e.kind() == ProjectileKind.MAGNET && e.isAlive() && p.getUuid().equals(e.owner)).size();
	}

	// ------------------------------------------------------------------ Railcannon

	private static void railcannon(ServerWorld world, ServerPlayerEntity p, int variation, int mode, Vec3d origin, Vec3d dir) {
		switch (variation) {
			case 1 -> UKProjectileEntity.spawn(world, p, ProjectileKind.SCREWDRIVER, origin.add(dir.multiply(0.5)), dir, 250);
			case 2 -> {
				Hitscan.fire(p, origin, dir, Hitscan.RAILCANNON_MALICIOUS, 0);
				// a coin that took the shot carries the explosion on with its reflected beam
				if (!Hitscan.lastHitCoin) Explosions.explode(world, p, Hitscan.lastEnd.subtract(dir.multiply(0.1)), Explosions.MALICIOUS, false, "railcannon2", null);
			}
			default -> Hitscan.fire(p, origin, dir, Hitscan.RAILCANNON, 0);
		}
	}

	// ------------------------------------------------------------------ Rocket Launcher

	/** mode 0: rocket; mode 1: cannonball (arg = charge %); mode 2: napalm droplet; mode 3: freeze (arg 1) / unfreeze (0). */
	private static void rocket(ServerWorld world, ServerPlayerEntity p, int variation, int mode, int arg, Vec3d origin, Vec3d dir) {
		switch (mode) {
			case 0 -> {
				// (fired while frozen it stays right where it left the barrel; frozen before it is sent to the client)
				boolean frozen = UKProjectileEntity.frozenOwners.contains(p.getUuid());
				// RocketLauncher.Shoot: Instantiate(rocket, camera position, camera rotation) - at the eye itself, so a frozen
				// one can be jumped on from where V1 stands
				UKProjectileEntity.spawn(world, p, ProjectileKind.ROCKET, origin, dir, 100, r -> r.setFrozenAtSpawn(frozen));
			}
			case 1 -> {
				float cb = Math.max(0, Math.min(1, arg / 100f));
				// velocity = (1 - charge) · player velocity + forward · max(15, charge · 150)
				Vec3d pv = new Vec3d(p.getX() - p.prevX, p.getY() - p.prevY, p.getZ() - p.prevZ).multiply(20 / Damage.SCALE_BLOCKS);
				Vec3d v = pv.multiply(1 - cb).add(dir.multiply(Math.max(15, cb * 150)));
				UKProjectileEntity ball = UKProjectileEntity.spawn(world, p, ProjectileKind.CANNONBALL, origin.add(dir.multiply(1 * Damage.SCALE_BLOCKS)), v.normalize(), v.length());
			}
			case 2 -> {
				Vec3d d = scatter(dir, 1f, world.random);
				UKProjectileEntity.spawn(world, p, ProjectileKind.GASOLINE, origin.add(dir.multiply(1 * Damage.SCALE_BLOCKS)), d, 150);
			}
			case 3 -> {
				if (arg == 1) UKProjectileEntity.frozenOwners.add(p.getUuid());
				else UKProjectileEntity.frozenOwners.remove(p.getUuid());
				if (Damage.debug) for (UKProjectileEntity r : world.getEntitiesByClass(UKProjectileEntity.class, p.getBoundingBox().expand(64), e -> e.kind() == ProjectileKind.ROCKET)) com.ultracraft.UltraCraft.LOG.info("[weapons] rocket {} at {} frozen={}", r.getId(), r.getPos(), arg == 1);
			}
			default -> {}
		}
	}

	/** transform.Rotate(Random.Range(-s, s) on each axis), in degrees, as a direction scatter. */
	static Vec3d scatter(Vec3d dir, float spreadDeg, net.minecraft.util.math.random.Random rnd) {
		if (spreadDeg <= 0) return dir;
		Vec3d up = Math.abs(dir.y) > 0.99 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d right = dir.crossProduct(up).normalize();
		Vec3d realUp = right.crossProduct(dir).normalize();
		double a = Math.toRadians((rnd.nextDouble() * 2 - 1) * spreadDeg);
		double b = Math.toRadians((rnd.nextDouble() * 2 - 1) * spreadDeg);
		return dir.add(right.multiply(Math.tan(a))).add(realUp.multiply(Math.tan(b))).normalize();
	}
}
