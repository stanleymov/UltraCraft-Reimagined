package com.ultracraft.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.item.RevolverItem;
import com.ultracraft.item.UKItems;
import com.ultracraft.net.FirePayload;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import com.ultracraft.net.PointsPayload;
import com.ultracraft.net.PointsSyncPayload;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.List;

/** Server side of the weapons: validates Fire packets, runs hitscans, gives the starter gear. */
public final class WeaponServer {
	private WeaponServer() {}

	/** A whiplashed enemy let go mid-pull (a punch cancels the hook): it flies on until it reaches V1, then stops there. */
	private record Released(Entity mob, ServerPlayerEntity p, int[] ticks) {}
	private static final List<Released> released = new java.util.ArrayList<>();

	/**
	 * In the game the released enemy's Rigidbody runs into V1's body and stops; Minecraft mobs pass through players, so
	 * it is stopped here once it is within one tick's travel (or whiplash.stopGap blocks) of V1.
	 */
	private static void tickReleased() {
		if (ParryServer.worldFrozen()) return;
		for (java.util.Iterator<Released> it = released.iterator(); it.hasNext(); ) {
			Released r = it.next();
			if (r.mob().isRemoved() || !r.mob().isAlive() || r.p().isRemoved() || --r.ticks()[0] < 0) { it.remove(); continue; }
			Box a = r.mob().getBoundingBox(), b = r.p().getBoundingBox();
			double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX)), dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY)), dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
			double gap = Math.sqrt(dx * dx + dy * dy + dz * dz);
			Vec3d v = r.mob().getVelocity();
			holdAi(r.mob());
			if (gap > Math.max(ServerTune.get("whiplash.stopGap", 0.3), v.length())) continue;
			r.mob().setVelocity(0, Math.min(0, v.y), 0);
			r.mob().velocityModified = true;
			r.mob().fallDistance = 0;
			it.remove();
		}
	}

	private static double boxGap(Box a, Box b) {
		double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX)), dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY)), dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Mobs being whiplashed: an enemy on the hook is helpless (no walking off, no attacking) until a few ticks past the
	 * last pull. Their goals that move, look or jump are held (that also stops melee and ranged attacks); turning the AI
	 * off would also have made them immobile, so the pull could not move them.
	 */
	private static final java.util.Map<net.minecraft.entity.mob.MobEntity, int[]> heldAi = new java.util.concurrent.ConcurrentHashMap<>();
	private static final net.minecraft.entity.ai.goal.Goal.Control[] HELD = {net.minecraft.entity.ai.goal.Goal.Control.MOVE, net.minecraft.entity.ai.goal.Goal.Control.LOOK, net.minecraft.entity.ai.goal.Goal.Control.JUMP};

	private static void holdAi(Entity e) {
		noFall.add(e);
		if (!(e instanceof net.minecraft.entity.mob.MobEntity m)) return;
		int[] h = heldAi.get(m);
		if (h == null) heldAi.put(m, new int[] {(int) ServerTune.get("whiplash.aiOff", 4)});
		else h[0] = (int) ServerTune.get("whiplash.aiOff", 4);
		for (var c : HELD) ((com.ultracraft.mixin.MobGoalsAccessor) m).ultracraft$goals().disableControl(c);
		m.getNavigation().stop();
		m.setForwardSpeed(0);
		m.setSidewaysSpeed(0);
		m.setUpwardSpeed(0);
		m.setJumping(false);
	}

	/** A pulled mob's last step (from where it was to where it is) and the world time of it. */
	private static final java.util.Map<Entity, Object[]> pullSteps = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/**
	 * A mob pulled within the last ticks: its box along its last step, from where it is back to where it was. The
	 * shooter sees it gliding between those two (the client draws the step over the tick), so shots test all of it.
	 * Null for any other entity.
	 */
	public static Box[] pullSweep(Entity e) {
		Object[] s = pullSteps.get(e);
		if (s == null) return null;
		if (e.getWorld().getTime() - (long) s[1] > 2) { pullSteps.remove(e); return null; }
		Vec3d step = (Vec3d) s[0];
		Box[] out = new Box[5];
		for (int i = 0; i < out.length; i++) out[i] = e.getBoundingBox().offset(step.multiply(-i / 4.0));
		return out;
	}

	/**
	 * Each mob's hitbox over the last ticks. The player sees mobs a few ticks behind where they are here (the client
	 * smooths their moves over three ticks, and gets them every few), which for a fast mob (popped up by the whiplash,
	 * falling, knocked back) is a body length or more: projectiles also test these, so they hit the mob the player saw
	 * (Hitscan.hitPoint; head or body on the box nearest the hit).
	 */
	private static final java.util.Map<Entity, java.util.ArrayDeque<Box>> boxHistory = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private static void recordBoxes(net.minecraft.server.MinecraftServer server) {
		if (ParryServer.worldFrozen()) return;
		int keep = (int) ServerTune.get("hit.historyTicks", 6);
		for (net.minecraft.server.world.ServerWorld w : server.getWorlds())
			for (Entity e : w.iterateEntities()) {
				if (!(e instanceof LivingEntity) || e instanceof net.minecraft.entity.player.PlayerEntity || !e.isAlive()) continue;
				java.util.ArrayDeque<Box> q = boxHistory.computeIfAbsent(e, k -> new java.util.ArrayDeque<>());
				Box b = e.getBoundingBox();
				// a mob standing still adds nothing new
				if (q.isEmpty() || !q.peekLast().equals(b)) q.addLast(b);
				while (q.size() > keep) q.pollFirst();
			}
	}

	/** A mob's hitboxes over the last ticks (oldest first), or null. */
	public static java.util.List<Box> recentBoxes(Entity e) {
		java.util.ArrayDeque<Box> q = boxHistory.get(e);
		return q == null ? null : new java.util.ArrayList<>(q);
	}

	/** The light enemy this game's own player is pulling (client side, its entity id), or -1: V1 does not bump into it. */
	public static volatile int clientPulledId = -1;
	/** The whiplash is out (thrown, caught or pulling), set by the client every tick: mobs are solid to V1 only then. */
	public static volatile boolean whiplashOut;
	/** Client side: the mob the whiplash is pulling right now (it is placed by the pull, so it passes through V1). */
	public static volatile int clientPullingId = -1;
	/** Client side: mobs a slam just crushed (id -> nanoTime until), no longer solid to V1 so it falls through them. */
	public static final java.util.Map<Integer, Long> clientCrushed = new java.util.concurrent.ConcurrentHashMap<>();

	public static boolean crushed(Entity e) {
		Long until = clientCrushed.get(e.getId());
		if (until == null) return false;
		if (System.nanoTime() > until) { clientCrushed.remove(e.getId()); return false; }
		return true;
	}

	/** A mob on the whiplash: it is not stopped by V1's (lagging) body on the server, the pull stops it (MobPlayerCollideMixin). */
	public static boolean hooked(Entity e) {
		return e instanceof net.minecraft.entity.mob.MobEntity m && heldAi.containsKey(m);
	}

	/** Mobs that were on the whiplash take no fall damage until they are back on the ground. */
	private static final java.util.Set<Entity> noFall = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	private static void tickHeldAi() {
		if (ParryServer.worldFrozen()) return;
		for (var it = noFall.iterator(); it.hasNext(); ) {
			Entity e = it.next();
			e.fallDistance = 0;
			if (!e.isAlive() || (e.isOnGround() && !(e instanceof net.minecraft.entity.mob.MobEntity m && heldAi.containsKey(m)))) it.remove();
		}
		for (var it = heldAi.entrySet().iterator(); it.hasNext(); ) {
			var en = it.next();
			if (--en.getValue()[0] > 0 && en.getKey().isAlive()) continue;
			for (var c : HELD) ((com.ultracraft.mixin.MobGoalsAccessor) en.getKey()).ultracraft$goals().enableControl(c);
			it.remove();
		}
	}

	public static void init() {
		Net.onServer(FirePayload.class, WeaponServer::onFire);
		Net.onServer(PointsPayload.class, WeaponServer::onPoints);
		ParryServer.init();
		Gasoline.init();
		DebugCommands.init();
		Shop.init();
		MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent e) -> { if (e.phase == net.minecraftforge.event.TickEvent.Phase.END) { ArsenalServer.tickLater(); tickReleased(); tickHeldAi(); recordBoxes(e.getServer()); ParryWarn.tick(e.getServer()); BossRewards.tick(e.getServer()); } });
		// the Iron Golem's hits on V1: Minecraft's own (7.5-21.5 on Normal, a one-hit kill possible; user's call) unless
		// mob.ironGolem.attack sets a fixed amount (the Cerberus it plays: StatueBoss tackle / stomp orb 25 of 100 = 5)
		MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingHurtEvent e) -> {
			double fixed = ServerTune.get("mob.ironGolem.attack", 0);
			if (fixed > 0 && e.getEntity() instanceof ServerPlayerEntity && e.getSource().getAttacker() instanceof net.minecraft.entity.passive.IronGolemEntity)
				e.setAmount((float) fixed);
		});
		// a boss killed pays its P bonus (BossRewards)
		MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingDeathEvent e) -> {
			if (!e.getEntity().getWorld().isClient) BossRewards.onDeath(e.getEntity(), e.getSource());
		});
		// no hunger (HungerManagerMixin): food heals directly, its hunger value in health (a steak's 8 = 4 hearts)
		MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Finish e) -> {
			if (!(e.getEntity() instanceof ServerPlayerEntity p) || ServerTune.get("food.vanillaHunger", 0) != 0) return;
			var food = e.getItem().getFoodProperties(p);
			if (food != null && food.getHunger() > 0) p.heal((float) (food.getHunger() * ServerTune.get("food.healPerHunger", 1.0)));
		});
		// the Drowning option (off by default): the player's air stays full under water
		MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.PlayerTickEvent e) -> {
			if (e.phase == net.minecraftforge.event.TickEvent.Phase.END && !e.player.getWorld().isClient && !com.ultracraft.UkConfig.drowning())
				e.player.setAir(e.player.getMaxAir());
		});
		MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayerEntity p) {
				com.ultracraft.net.Net.clearOffhand(p);
				Shop.onLogin(p, p.getPersistentData().getCompound("PlayerPersisted").getBoolean("ultracraft.starter"));
				starterKit(p);
				Net.toPlayer(p, new PointsSyncPayload(points(p)));
			}
		});
	}

	/** Base weapons from the start (user decision): the Revolver (Piercer), and P for the standard weapons. Once per player. */
	private static void starterKit(ServerPlayerEntity p) {
		var tag = p.getPersistentData();
		var persisted = tag.getCompound("PlayerPersisted");
		if (persisted.getBoolean("ultracraft.starter")) return;
		persisted.putBoolean("ultracraft.starter", true);
		tag.put("PlayerPersisted", persisted);
		if (p.getInventory().count(UKItems.REVOLVER.get()) == 0) p.getInventory().offerOrDrop(RevolverItem.of(0));
		// enough P to buy every standard weapon (user): the shop's standard prices added up, or shop.startP
		int start = (int) ServerTune.get("shop.startP", Shop.standardTotal());
		if (persisted.getInt("ultracraft.P") < start) {
			persisted.putInt("ultracraft.P", start);
			tag.put("PlayerPersisted", persisted);
		}
	}

	/** P (StatsManager.stylePoints) lives in the player's persisted NBT; the client reports what it earned. */
	public static int points(ServerPlayerEntity p) {
		return p.getPersistentData().getCompound("PlayerPersisted").getInt("ultracraft.P");
	}

	private static void onPoints(PointsPayload msg, ServerPlayerEntity p) {
		var tag = p.getPersistentData();
		var persisted = tag.getCompound("PlayerPersisted");
		// earned P x shop.pointsMultiplier (user: twice ULTRAKILL's, to buy faster; the style meter itself is unchanged)
		// (a fight that ended in the red - NewMovement.GetHurt takes damage x 5 off the style points - banks nothing: the
		// game's level points stop at 0 and P never goes down; it took from the bank, doubled)
		int earned = (int) Math.round(Math.max(0, msg.points()) * ServerTune.get("shop.pointsMultiplier", 2.0));
		int total = msg.total() ? msg.points() : Math.max(0, persisted.getInt("ultracraft.P") + earned);
		// (always logged: what each fight banked, to measure what a boss fight pays)
		if (!msg.total()) UltraCraft.LOG.info("[points] fight banked {} style -> +{} P (total {})", msg.points(), earned, total);
		persisted.putInt("ultracraft.P", total);
		tag.put("PlayerPersisted", persisted);
	}

	private static void onFire(FirePayload msg, ServerPlayerEntity p) {
		if (Damage.debug) UltraCraft.LOG.info("[weapons] server fire weapon={} mode={} arg={} from {}", msg.weapon(), msg.mode(), msg.arg(), msg.origin());
		// a coin claim carries the hit point (origin) and the coin's position on the client (dir): stored for the
		// hitscan shot that follows it (Hitscan.fire)
		if (msg.weapon() == FirePayload.VIEW_HIT) {
			Hitscan.storeViewHit(p, msg.arg(), msg.origin(), msg.mode() == 1);
			return;
		}
		if (msg.weapon() == FirePayload.COIN && msg.mode() == 2) {
			Hitscan.storeCoinClaim(p, msg.origin(), msg.dir());
			return;
		}
		// a rocket ride (start 4, end 5, steer 6) carries no ray, and goes on whatever V1 holds
		if (msg.weapon() == FirePayload.ROCKET && msg.mode() >= 4 && msg.mode() <= 6) {
			ArsenalServer.rideMessage(msg, p);
			return;
		}
		// sanity: the ray must start near the player's eyes (a hooked enemy's place, hook mode 5, is checked where it is taken)
		// (a slam's stomp too: V1 falls 2.6 blocks a tick, further than this check allows from the server's copy a tick behind)
		boolean pullPlace = (msg.weapon() == FirePayload.HOOK && msg.mode() == 5) || msg.weapon() == FirePayload.STOMP;
		if (!pullPlace && msg.origin().squaredDistanceTo(p.getEyePos()) > 4) return;
		switch (msg.weapon()) {
			case FirePayload.REVOLVER -> fireRevolver(msg, p);
			case FirePayload.PUNCH -> punch(msg, p);
			case FirePayload.COIN -> { if (msg.mode() == 1) Coins.throwCoinWithVelocity(p, msg.origin(), msg.dir()); else Coins.throwCoin(p, msg.origin(), msg.dir()); }
			case FirePayload.SHOTGUN, FirePayload.NAILGUN, FirePayload.RAILCANNON, FirePayload.ROCKET -> ArsenalServer.onFire(msg, p);
			case FirePayload.HOOK -> hook(msg, p);
			case FirePayload.SHOCKWAVE -> shockwave(msg, p);
			case FirePayload.STOMP -> stomp(msg, p);
			default -> {}
		}
	}

	private static void fireRevolver(FirePayload msg, ServerPlayerEntity p) {
		ItemStack held = p.getMainHandStack();
		if (!(held.getItem() instanceof RevolverItem)) return;
		if (msg.mode() == 9) { // variation switch (E / Q): only to an owned one (the shop)
			if (Shop.owns(p, com.ultracraft.item.WeaponKind.REVOLVER, Math.floorMod(msg.arg(), 3))) RevolverItem.setVariation(held, msg.arg());
			return;
		}
		int variation = RevolverItem.variation(held);
		boolean alt = RevolverItem.alternate(held);
		Hitscan.Beam beam = alt ? Hitscan.REVOLVER_ALT : Hitscan.REVOLVER;
		int ricochets = 0;
		if (msg.mode() == 1) {
			beam = variation == 2 ? (alt ? Hitscan.REVOLVER_ALT_SHARP : Hitscan.REVOLVER_SHARP) : (alt ? Hitscan.REVOLVER_ALT_SUPER : Hitscan.REVOLVER_SUPER);
			if (variation == 2) ricochets = Math.min(3, Math.max(0, msg.arg()));
		}
		Hitscan.fire(p, msg.origin(), msg.dir(), beam, ricochets);
	}

	/** HookArm on the server: the catch damage and the pull of light enemies (60 u/s toward V1). */
	/**
	 * PhysicalShockwavePlayer: damage 0, force 5000 × slamForce × 2.25, radius 12 u, 75 u/s. Enemies in the ring are
	 * pushed away from the player (the ring takes 0.16 s to cross its radius, so one pass is applied at once).
	 */
	/**
	 * GroundCheck.OnTriggerEnter: a slam that lands on an enemy deals it 2 damage (hitter "ground slam", force toward
	 * V1 x 5000): enough for the weak ones (80 HP here).
	 */
	private static void stomp(FirePayload msg, ServerPlayerEntity p) {
		Entity e = p.getServerWorld().getEntityById(msg.arg());
		if (!(e instanceof LivingEntity le) || !le.isAlive() || le.squaredDistanceTo(p) > 10 * 10) return;
		Vec3d point = le.getPos().add(0, le.getHeight() / 2, 0);
		Vec3d d = p.getPos().subtract(le.getPos());
		Vec3d dir = d.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : d.normalize();
		Damage.deal(p, le, (float) ServerTune.get("slam.stompDamage", 2.0), dir, 5000f, HitPayload.GROUNDSLAM);
		com.ultracraft.net.Net.toNear(p.getServerWorld(), point, new com.ultracraft.net.HitPayload(le.isAlive() ? com.ultracraft.net.HitPayload.ENEMY_HIT : com.ultracraft.net.HitPayload.KILL,
			com.ultracraft.net.HitPayload.GROUNDSLAM, 0, Damage.flags(le), point));
	}

	private static void shockwave(FirePayload msg, ServerPlayerEntity p) {
		// mode 1: the clash-mode slam's impactDust (PlatformerMovement.SlamEnd), a PhysicalShockwavePlayer at its own 5000
		float slamForce = Math.max(1f, msg.arg() / 100f);
		groundShockwave(p.getServerWorld(), p, msg.origin(), msg.mode() == 1 ? 5000f : 5000f * slamForce * 2.25f);
	}

	/**
	 * PhysicalShockwavePlayer (speed 75, maxSize 12, damage 0): the enemies on the ground in its 12 u ring are thrown up
	 * with `force` (the slam's 5000 x slamForce x 2.25; a cannonball's 10000 + speed x 80). V1 is left alone (it starts
	 * with hasHurtPlayer).
	 */
	public static void groundShockwave(net.minecraft.server.world.ServerWorld world, ServerPlayerEntity p, Vec3d c, float force) {
		double radius = 12 * Damage.SCALE_BLOCKS;
		for (Entity e : world.getOtherEntities(p, new net.minecraft.util.math.Box(c.add(-radius, -1, -radius), c.add(radius, 2, radius)), ent -> ent instanceof LivingEntity && ent.isAlive() && !(ent instanceof ServerPlayerEntity))) {
			Vec3d d = e.getPos().subtract(c);
			double dist = Math.sqrt(d.x * d.x + d.z * d.z);
			if (dist > radius) continue;
			Vec3d dir = dist < 1e-3 ? new Vec3d(1, 0, 0) : new Vec3d(d.x / dist, 0, d.z / dist);
			double push = Math.min(2.5, force / 20000.0);
			e.addVelocity(dir.x * push * 0.3, 0.15 + push * 0.1, dir.z * push * 0.3);
			e.velocityModified = true;
		}
	}

	private static void hook(FirePayload msg, ServerPlayerEntity p) {
		Entity target = p.getServerWorld().getEntityById(msg.arg());
		if (target == null || !target.isAlive()) return;
		switch (msg.mode()) {
			case 0 -> {
				// HookArm deals 0.2 on the grab; here a flat whiplash.grabDamage health (half a heart), which can kill:
				// hooking enemies over and over costs them
				if (target instanceof LivingEntity le) {
					float amount = (float) ServerTune.get("whiplash.grabDamage", 1.0);
					if (amount > 0) { le.timeUntilRegen = 0; le.hurtTime = 0; le.damage(p.getDamageSources().playerAttack(p), amount); }
				}
			}
			case 3 -> {
				if (target instanceof com.ultracraft.entity.CoinEntity coin) {
					coin.bounce();
					if (Damage.debug) UltraCraft.LOG.info("[weapons] coin bounced by the hook at {}", coin.getPos());
				}
			}
			case 1 -> {
				// toward where V1 is now (the client's eye it sent: the server's player is a tick behind a fast V1), 60 u/s,
				// but no further than V1's body: it stops there instead of flying past
				Vec3d feet = msg.origin().subtract(0, p.getStandingEyeHeight(), 0);
				if (feet.squaredDistanceTo(p.getPos()) > 8 * 8) feet = p.getPos();
				Box body = p.getBoundingBox().offset(feet.subtract(p.getPos()));
				Vec3d to = feet.add(0, 0.9, 0).subtract(target.getBoundingBox().getCenter());
				if (to.lengthSquared() < 1e-6) return;
				double step = Math.min(60 * Damage.SCALE_BLOCKS * 0.05, boxGap(target.getBoundingBox(), body));
				target.setVelocity(to.normalize().multiply(step));
				target.velocityModified = true;
				target.fallDistance = 0;
				holdAi(target);
			}
			case 5 -> {
				// the hooking client moves its light enemy (it sees where V1 really is): taken while it is a pull step or so
				// from where the enemy is here and near V1
				Vec3d want = msg.origin();
				if (want.squaredDistanceTo(target.getPos()) > 12 * 12 || want.squaredDistanceTo(p.getPos()) > 80 * 80) return;
				pullSteps.put(target, new Object[] {want.subtract(target.getPos()), target.getWorld().getTime()});
				// (no velocity: the mob's own tick would move it on again, past V1, whose body a hooked mob does not meet here)
				target.setPosition(want);
				target.setVelocity(Vec3d.ZERO);
				target.velocityModified = true;
				target.fallDistance = 0;
				// (a jockey's riders come along with it, helpless too)
				target.streamSelfAndPassengers().forEach(WeaponServer::holdAi);
			}
			case 2, 7 -> {
				// a hooked dropped item goes into the inventory when it reaches V1 (the game puts an ItemIdentifier in the
				// hand; here the user's wish: the inventory, what does not fit stays on the ground at V1)
				if (target instanceof net.minecraft.entity.ItemEntity item) {
					ItemStack stack = item.getStack().copy();
					int before = stack.getCount();
					p.getInventory().insertStack(stack);
					int taken = before - stack.getCount();
					if (taken > 0) {
						p.sendPickup(item, taken);
						p.increaseStat(net.minecraft.stat.Stats.PICKED_UP.getOrCreateStat(item.getStack().getItem()), taken);
					}
					if (stack.isEmpty()) item.discard();
					else {
						item.setStack(stack);
						item.setVelocity(Vec3d.ZERO);
						item.velocityModified = true;
					}
					return;
				}
				// HookArm's catch, right at V1: with V1 in the air (mode 7) the enemy is popped up (15 u/s), on the ground it
				// stops dead
				target.setVelocity(msg.mode() == 7 ? new Vec3d(0, 15 * Damage.SCALE_BLOCKS * 0.05, 0) : Vec3d.ZERO);
				target.velocityModified = true;
			}
			case 4 -> {
				// let go mid-pull by a punch: keeps flying at V1 and stops when it gets there
				holdAi(target);
				Vec3d toV1 = p.getPos().add(0, 0.9, 0).subtract(target.getBoundingBox().getCenter());
				if (toV1.lengthSquared() > 1e-6) {
					target.setVelocity(toV1.normalize().multiply(ServerTune.get("whiplash.pullBase", 80) * Damage.SCALE_BLOCKS * 0.05));
					target.velocityModified = true;
				}
				released.removeIf(r -> r.mob() == target);
				released.add(new Released(target, p, new int[] {40}));
				tickReleased();
			}
			default -> {}
		}
	}

	/**
	 * Punch.ActiveFrame: the Feedbacker first checks the parry zone at the camera for projectiles, then a
	 * ray 4 u + sphere 1 u radius 4 u ahead on enemies, then the environment.
	 */
	private static void punch(FirePayload msg, ServerPlayerEntity p) {
		// mode bit 8: the client timed this punch inside the shotgun parry window
		boolean pelletWindow = (msg.mode() & 8) != 0;
		int mode = msg.mode() & 7;
		if (mode == 3) {
			// clash mode (PlatformerMovement.Spin / SpinZone): everything around V1 is hit and shoved
			var world = p.getServerWorld();
			Vec3d c = p.getPos().add(0, 0.9, 0);
			for (Entity e : world.getOtherEntities(p, p.getBoundingBox().expand(1.5 * Damage.SCALE_BLOCKS * 2), e -> e instanceof LivingEntity && e.isAlive())) {
				LivingEntity t = (LivingEntity) e;
				Vec3d dir = t.getPos().subtract(c);
				dir = dir.lengthSquared() < 1e-6 ? msg.dir() : dir.normalize();
				// SpinZone.SpinEnemy: InstaKill (hitter "spin") and the body flies away flat at 250 u/s
				boolean wasAlive = t.isAlive();
				Vec3d flat = new Vec3d(dir.x, 0, dir.z);
				flat = flat.lengthSquared() < 1e-6 ? new Vec3d(msg.dir().x, 0, msg.dir().z).normalize() : flat.normalize();
				// (a boss is not killed outright: it takes boss.spinPoints and is not flung; user 2026-10-28)
				if (Damage.isBoss(t)) Damage.deal(p, t, (float) ServerTune.get("boss.spinPoints", 2.5), flat, 0f, HitPayload.SPIN);
				else {
					Damage.deal(p, t, Math.max(1f, t.getHealth() * 2), flat, 0f, HitPayload.SPIN);
					t.setVelocity(flat.multiply(250 * Damage.SCALE_BLOCKS / 20.0).add(0, 0.2, 0));
					t.velocityModified = true;
				}
				boolean killed = wasAlive && !t.isAlive();
				Net.toNear(world, t.getPos(), new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, HitPayload.SPIN, 0, Damage.flags(t), t.getPos()));
			}
			return;
		}
		if (mode == 2) {
			// Punch.BlastCheck: Explosion Wave Knuckleblaster 2 u ahead (or at the wall), enemies only
			Vec3d start = msg.origin();
			Vec3d d = msg.dir().normalize();
			Vec3d at = start.add(d.multiply(2 * Damage.SCALE_BLOCKS));
			BlockHitResult b = p.getServerWorld().raycast(new RaycastContext(start, at, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
			if (b.getType() != HitResult.Type.MISS) at = b.getPos().subtract(d.multiply(0.1 * Damage.SCALE_BLOCKS));
			Explosions.explode(p.getServerWorld(), p, at, Explosions.KNUCKLEBLASTER, true, "heavypunch", null);
			return;
		}
		boolean heavy = mode == 1;
		double reach = 4 * Damage.SCALE_BLOCKS;
		double radius = 1.0 * Damage.SCALE_BLOCKS;
		Vec3d start = msg.origin();
		Vec3d d = msg.dir().normalize();
		Vec3d end = start.add(d.multiply(reach));
		var world = p.getServerWorld();

		// parry: enemy projectiles near the camera go back where V1 looks (and V1's own pellets get boosted) on this
		// and the next active frames; a parried projectile ends the punch's hit check like the game's parriedSomething
		if (!heavy) {
			ParryServer.armPunch(p);
			if (ParryServer.tryParryProjectiles(p, start, d, true, pelletWindow)) return;
		}

		List<Entity> list = world.getOtherEntities(p, new Box(start, end).expand(radius), e -> e instanceof LivingEntity && e.isAlive());
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		Vec3d point = end;
		// the enemy the client's fist met (fast whiplashed enemies are ahead of what V1 saw on the server): trusted while
		// its body is still within punch.clientTrust blocks of the camera
		if (msg.arg() > 0 && world.getEntityById(msg.arg() - 1) instanceof LivingEntity seen && seen != p && seen.isAlive()) {
			Box body = seen.getBoundingBox();
			Vec3d near = new Vec3d(Math.max(body.minX, Math.min(body.maxX, start.x)), Math.max(body.minY, Math.min(body.maxY, start.y)), Math.max(body.minZ, Math.min(body.maxZ, start.z)));
			if (near.distanceTo(start) <= ServerTune.get("punch.clientTrust", 3.5)) { best = seen; bestDist = near.distanceTo(start); point = near; }
		}
		if (best == null) for (Entity e : list) {
			// Box.raycast needs an entry face: a mob already up against V1 (a whiplashed one flying in) has its expanded
			// box around the camera, so that is a hit right at the nearest point of its body
			Box bb = e.getBoundingBox().expand(radius);
			Box body = e.getBoundingBox();
			var hit = bb.contains(start)
				? java.util.Optional.of(new Vec3d(Math.max(body.minX, Math.min(body.maxX, start.x)), Math.max(body.minY, Math.min(body.maxY, start.y)), Math.max(body.minZ, Math.min(body.maxZ, start.z))))
				: bb.raycast(start, end);
			if (hit.isPresent() && hit.get().distanceTo(start) < bestDist) {
				bestDist = hit.get().distanceTo(start);
				best = (LivingEntity) e;
				point = hit.get();
			}
		}
		if (best != null) {
			boolean wasAlive = best.isAlive();
			// a Feedbacker hit on an enemy in its parry window (Zombie.GotHit with hitter "punch" while attacking):
			// the attack is cancelled, the enemy takes the parry damage and a hard shove, V1 is healed
			boolean parried = !heavy && ParryServer.tryParryMelee(p, best);
			if (parried) {
				Damage.deal(p, best, 2f, d, 25000f * 1000f / 20f, HitPayload.PUNCH);
				ParryServer.success(p, point);
			} else Damage.deal(p, best, heavy ? 2.5f : 1f, d, heavy ? 100f * 1000f / 20f : 25f * 1000f / 20f, heavy ? HitPayload.HEAVYPUNCH : HitPayload.PUNCH);
			boolean killed = wasAlive && !best.isAlive();
			boolean head = Hitscan.isHeadshot(best, point, start, d);
			Net.toNear(world, point, new HitPayload(killed ? HitPayload.KILL : HitPayload.ENEMY_HIT, heavy ? HitPayload.HEAVYPUNCH : HitPayload.PUNCH, head ? 1 : 0, Damage.flags(best), point));
			// blood heal: the enemy's blood lands on V1 at arm's length (BloodsplatterManager → GetHealth)
			BloodHeal.heal(p, heavy ? 10 : 5);
			return;
		}
		// (the game punches saws only when the punch hit no enemy: Punch.ActiveFrame's !hitSomething && !parriedSomething)
		if (!heavy && ParryServer.tryPunchSaws(p, start, d)) return;
		BlockHitResult block = world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		if (block.getType() != HitResult.Type.MISS) Net.toPlayer(p, new HitPayload(HitPayload.WALL_HIT, HitPayload.PUNCH, 0, block.getPos()));
	}

	/** Punch.GetParryLookTarget: the first enemy or surface the camera ray meets, else 100 u ahead. */
	static Vec3d parryLookTarget(ServerPlayerEntity p, Vec3d start, Vec3d d) {
		var world = p.getServerWorld();
		Vec3d far = start.add(d.multiply(100 * Damage.SCALE_BLOCKS));
		BlockHitResult block = world.raycast(new RaycastContext(start, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		Vec3d end = block.getType() == HitResult.Type.MISS ? far : block.getPos();
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		Vec3d point = end;
		for (Entity e : world.getOtherEntities(p, new Box(start, end).expand(0.5), e -> e instanceof LivingEntity && e.isAlive())) {
			var hit = e.getBoundingBox().expand(0.3).raycast(start, end);
			if (hit.isPresent() && hit.get().distanceTo(start) < bestDist) {
				bestDist = hit.get().distanceTo(start);
				best = (LivingEntity) e;
				point = e.getBoundingBox().getCenter();
			}
		}
		return best != null ? point : end;
	}
}
