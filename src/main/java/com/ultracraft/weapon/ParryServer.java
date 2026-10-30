package com.ultracraft.weapon;

import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ULTRAKILL's parry, on Minecraft's enemies and projectiles.
 *
 * <ul>
 * <li>Melee: an enemy's melee hit on V1 does not land at once. It opens a short parry window instead (the game's
 * Enemy.parryable flash): the enemy flashes yellow and the hit lands when the window closes, unless the Feedbacker
 * (or the shotgun's point-blank zone, "shotgunzone") hits the enemy first; then the enemy takes the parry damage and
 * knockback and V1 is healed to full (NewMovement.Parry: ParryFlash, GetHealth(999), FullStamina, +100 PARRY).</li>
 * <li>Projectiles: during the punch's active frames (Punch.ActiveFrame on three ticks), an enemy projectile near the
 * camera is sent back where V1 looks at twice its speed, owned by V1 (Punch.ParryProjectile).</li>
 * <li>Projectile boost: on the first active frame, V1's own shotgun pellets still in front of the camera are boosted:
 * orange, twice as fast, a four times wider hitbox, and they explode on whatever they hit.</li>
 * </ul>
 */
public final class ParryServer {
	/** Ticks an enemy's melee hit hangs in the air as a parryable flash before it lands (ULTRAKILL flashes ~0.35 s). */
	public static final int WINDOW_TICKS = 10;
	/** Ticks a punch keeps checking the parry zone for projectiles (Jab: ActiveStart → ActiveEnd is 0.12 s). */
	public static final int ACTIVE_TICKS = 3;

	private record Pending(MobEntity mob, ServerPlayerEntity target, DamageSource source, float amount, int[] ticks) {}
	private record Active(ServerPlayerEntity p, int[] ticks) {}
	/** A Minecraft projectile V1 parried: flies straight on at its parried velocity until it hits something and goes off. */
	private record Parried(ProjectileEntity pr, ServerPlayerEntity p, Vec3d vel, int[] ticks) {}
	private static final Map<Integer, Parried> parried = new HashMap<>();
	/** The parry freeze (TimeController.TrueStop) on the server: the worlds stand still until this nanoTime. */
	private static volatile long freezeUntil;

	/** True while a parry freeze runs: ServerWorld.tick is skipped (ServerWorldFreezeMixin). */
	public static boolean worldFrozen() {
		return System.nanoTime() < freezeUntil;
	}

	private static final Map<Integer, Pending> pending = new HashMap<>();
	/** A Warden's sonic boom on V1, held a few ticks so a punch at the Warden can still parry it. */
	private record Sonic(LivingEntity warden, ServerPlayerEntity target, DamageSource source, float amount, int[] ticks) {}
	private static final List<Sonic> sonics = new ArrayList<>();
	/** Server tick of each player's last Feedbacker punch (armPunch). */
	private static final Map<java.util.UUID, Integer> lastPunch = new HashMap<>();
	private static final List<Active> active = new ArrayList<>();
	private static boolean applying;

	private ParryServer() {}

	public static void init() {
		MinecraftForge.EVENT_BUS.addListener(ParryServer::onAttack);
		// parried projectiles before the worlds tick: their coming step is checked before Minecraft moves them, or a
		// fast arrow met the mob by vanilla's own hit first and only sometimes went off (user)
		MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
			if (e.phase == TickEvent.Phase.START) { if (!worldFrozen()) tickParried(); }
			else tick();
		});
	}

	/** A mob's direct melee hit on a player becomes a parry window; the damage is applied when it closes. */
	private static void onAttack(LivingAttackEvent e) {
		if (applying || !(e.getEntity() instanceof ServerPlayerEntity p)) return;
		DamageSource src = e.getSource();
		// the Warden's sonic boom: no projectile in Minecraft, so it is parried like a projectile right at V1, by a punch
		// toward the Warden just before it lands or within a few ticks after (held that long)
		if (src.isOf(net.minecraft.entity.damage.DamageTypes.SONIC_BOOM) && src.getAttacker() instanceof LivingEntity w) {
			if (e.getAmount() <= 0 || p.isInvulnerableTo(src)) return;
			e.setCanceled(true);
			Integer at = lastPunch.get(p.getUuid());
			if (at != null && p.getServer().getTicks() - at <= ServerTune.get("parry.sonicEarly", 4) && facing(p, w)) parrySonic(p, w);
			else sonics.add(new Sonic(w, p, src, e.getAmount(), new int[] {(int) ServerTune.get("parry.sonicLate", 3)}));
			return;
		}
		// (an explosion is no swing: a creeper's blast comes straight from the creeper, which is gone when the window
		// would close, so it never landed; the creeper is parried before it blows, tryParryCreeper)
		if (src.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_EXPLOSION)) return;
		if (!(src.getAttacker() instanceof MobEntity mob) || src.getSource() != mob || !mob.isAlive()) return;
		if (e.getAmount() <= 0 || p.isInvulnerableTo(src)) return;
		e.setCanceled(true);
		if (pending.containsKey(mob.getId())) return;
		pending.put(mob.getId(), new Pending(mob, p, src, e.getAmount(), new int[] {WINDOW_TICKS}));
		com.ultracraft.UltraCraft.LOG.info("[parry] window: {} on {} ({} hp)", mob.getType().getUntranslatedName(), p.getName().getString(), e.getAmount());
		// (no warning on the mob: ULTRAKILL's enemies give none, the swing itself is the tell)
	}

	/** Chainsaws still waiting their turn in ChainsawPunchRoutine. */
	private record SawPunch(ServerPlayerEntity p, UKProjectileEntity saw, int[] ticks) {}
	private static final List<SawPunch> sawPunches = new ArrayList<>();

	/** ChainsawPunchRoutine on one saw: from the camera + forward, toward the parry look target (read at its turn). */
	private static void punchSaw(ServerPlayerEntity p, UKProjectileEntity saw, Vec3d start, Vec3d d, boolean queued) {
		if (!saw.isAlive() || p.isRemoved()) return;
		double s = Damage.SCALE_BLOCKS;
		Vec3d target = WeaponServer.parryLookTarget(p, start, d);
		Vec3d from = start.add(d.multiply(1.0 * s));
		saw.getPunched(from, target.subtract(from));
		Net.toPlayer(p, new HitPayload(HitPayload.JUGGLE, HitPayload.PUNCH, queued ? 3 : 0, from));
	}

	private static void tick() {
		if (worldFrozen()) return; // parry windows, active punches and parried projectiles wait out the freeze too
		for (Iterator<SawPunch> it = sawPunches.iterator(); it.hasNext(); ) {
			SawPunch sp = it.next();
			if (--sp.ticks()[0] > 0) continue;
			it.remove();
			punchSaw(sp.p(), sp.saw(), sp.p().getEyePos(), sp.p().getRotationVec(1f), true);
		}
		for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
			Pending pd = it.next();
			if (--pd.ticks[0] > 0) continue;
			it.remove();
			if (!pd.mob.isAlive() || !pd.target.isAlive() || pd.target.isRemoved() || pd.target.isDisconnected()) continue;
			// the swing still connects if V1 is about where it was (reach + a step)
			if (pd.mob.squaredDistanceTo(pd.target) > 5.5 * 5.5) continue;
			com.ultracraft.UltraCraft.LOG.info("[parry] landed: {}", pd.mob.getType().getUntranslatedName());
			applying = true;
			try {
				pd.target.damage(pd.source, pd.amount);
			} finally {
				applying = false;
			}
		}
		for (Iterator<Sonic> it = sonics.iterator(); it.hasNext(); ) {
			Sonic so = it.next();
			if (--so.ticks[0] > 0) continue;
			it.remove();
			if (!so.target.isAlive() || so.target.isRemoved() || so.target.isDisconnected()) continue;
			applying = true;
			try {
				so.target.damage(so.source, so.amount);
			} finally {
				applying = false;
			}
		}
		for (Iterator<Active> it = active.iterator(); it.hasNext(); ) {
			Active a = it.next();
			if (--a.ticks[0] < 0 || !a.p.isAlive() || a.p.isDisconnected()) { it.remove(); continue; }
			if (tryParryProjectiles(a.p, a.p.getEyePos(), a.p.getRotationVec(1f), false)) it.remove();
		}
	}

	/** The Feedbacker's active frames start: keep checking the projectile parry zone for a few ticks. */
	public static void armPunch(ServerPlayerEntity p) {
		active.removeIf(a -> a.p == p);
		active.add(new Active(p, new int[] {ACTIVE_TICKS - 1}));
		lastPunch.put(p.getUuid(), p.getServer().getTicks());
		// a sonic boom still in the air over V1: parried now
		for (Iterator<Sonic> it = sonics.iterator(); it.hasNext(); ) {
			Sonic so = it.next();
			if (so.target != p || !so.warden.isAlive() || !facing(p, so.warden)) continue;
			it.remove();
			parrySonic(p, so.warden);
		}
	}

	/** V1 looks at the Warden within parry.sonicCone degrees (by eye: the punch must go its way). */
	private static boolean facing(ServerPlayerEntity p, LivingEntity w) {
		Vec3d to = w.getBoundingBox().getCenter().subtract(p.getEyePos());
		if (to.lengthSquared() < 1e-6) return true;
		return to.normalize().dotProduct(p.getRotationVec(1f)) >= Math.cos(Math.toRadians(ServerTune.get("parry.sonicCone", 60)));
	}

	/**
	 * A parried sonic boom: NewMovement.Parry for V1, and the boom goes back down its line into the Warden (its
	 * particles and sound from V1 to the Warden) and goes off there as a parried projectile does (Explosions.PARRIED).
	 */
	private static void parrySonic(ServerPlayerEntity p, LivingEntity w) {
		var world = p.getServerWorld();
		Vec3d from = p.getEyePos().subtract(0, 0.3, 0), to = w.getBoundingBox().getCenter();
		Vec3d step = to.subtract(from);
		int n = Math.max(1, (int) Math.floor(step.length()) + 7);
		for (int i = 1; i < n; i++) {
			Vec3d q = from.add(step.multiply(i / (double) n));
			world.spawnParticles(net.minecraft.particle.ParticleTypes.SONIC_BOOM, q.x, q.y, q.z, 1, 0, 0, 0, 0);
		}
		world.playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sound.SoundEvents.ENTITY_WARDEN_SONIC_BOOM, net.minecraft.sound.SoundCategory.PLAYERS, 3f, 1f);
		success(p, from);
		Explosions.explode(world, p, to, Explosions.PARRIED, false, false, "parry", null);
	}

	/**
	 * A Feedbacker punch or shooting parry on a creeper about to blow: within parry.creeperWindow ticks of its blast
	 * (from the warning star on, ParryWarn) its own explosion is called off - its fuse stops - and the caller's parry
	 * kills it; then creeperBlast. Outside the window the hit just kills it, as any punch does.
	 */
	public static boolean tryParryCreeper(ServerPlayerEntity p, LivingEntity t) {
		if (!(t instanceof net.minecraft.entity.mob.CreeperEntity c) || !c.isAlive() || c.getFuseSpeed() <= 0) return false;
		var f = (com.ultracraft.mixin.CreeperFuseAccessor) c;
		int window = (int) ServerTune.get("parry.creeperWindow", ParryWarn.creeperLead() + 1);
		if (f.ultracraft$currentFuseTime() < f.ultracraft$fuseTime() - window) return false;
		c.setFuseSpeed(-1);
		f.ultracraft$setCurrentFuseTime(0);
		com.ultracraft.UltraCraft.LOG.info("[parry] creeper {} parried by {}", c.getId(), p.getName().getString());
		return true;
	}

	/**
	 * The parried creeper's blast, ULTRAKILL style (user): V1's own explosion where it stood, as a parried projectile's
	 * (Explosions.PARRIED, like the parried sonic boom) - it hits the mobs around, only pushes V1, breaks no blocks; a
	 * charged creeper's is twice as big, as Minecraft's is.
	 */
	public static void creeperBlast(ServerPlayerEntity p, LivingEntity creeper) {
		boolean charged = creeper instanceof net.minecraft.entity.mob.CreeperEntity c && c.shouldRenderOverlay();
		Explosions.explode(p.getServerWorld(), p, creeper.getBoundingBox().getCenter(), charged ? Explosions.PARRIED.scaled(2f) : Explosions.PARRIED,
			false, false, "parry", null);
		// (still alive - armour, a potion: its fuse stays off, it is just a creeper again)
	}

	/** A Feedbacker punch or shotgun zone hit landed on `target`: true if that parried its pending attack. */
	public static boolean tryParryMelee(ServerPlayerEntity p, LivingEntity target) {
		Pending pd = pending.get(target.getId());
		if (pd == null || pd.target != p) return false;
		pending.remove(target.getId());
		return true;
	}

	/**
	 * Punch.ActiveFrame, a Standard (Feedbacker) punch that hit no enemy and parried nothing: every punchable sawblade
	 * (one a magnet caught or that bounced) in reach goes at the parry look target (the parry's Hook and sound, no
	 * freeze), else a saw is taken off a nearby magnet's ring.
	 */
	public static boolean tryPunchSaws(ServerPlayerEntity p, Vec3d start, Vec3d d) {
		var world = p.getServerWorld();
		double s = Damage.SCALE_BLOCKS;
		// Punch.ActiveFrame: every punchable sawblade (one a magnet has caught) in the zone goes at the parry look target
		// (the parry's Hook and sound, no freeze)
		{
			boolean sawHit = false;
			// OverlapSphere(camera + forward x 3, 3) on the projectile layer; a saw is tested along its last tick's path, as
			// the player saw it go by (at 200 u/s it moves 5 blocks a tick and is almost never inside on the punch's tick)
			Vec3d sc = start.add(d.multiply(3 * s));
			double sr = ServerTune.get("punch.sawSphere", 3) * s;
			Vec3d look = WeaponServer.parryLookTarget(p, start, d);
			for (Entity e : world.getOtherEntities(p, new Box(sc, sc).expand(sr + 6), ent -> ent instanceof UKProjectileEntity u && u.sawPunchable() && u.ownedBy(p) && ent.isAlive())) {
				Vec3d a = new Vec3d(e.prevX, e.prevY, e.prevZ), b = e.getPos(), ab = b.subtract(a);
				double t = ab.lengthSquared() < 1e-9 ? 1 : Math.max(0, Math.min(1, sc.subtract(a).dotProduct(ab) / ab.lengthSquared()));
				if (a.add(ab.multiply(t)).squaredDistanceTo(sc) > sr * sr) continue;
				((UKProjectileEntity) e).punchSaw(look);
				sawHit = true;
			}
			// none in reach: a magnet of ours 6-12 u from camera + forward (within 3 u of its height) with saws on its ring
			// gives up its furthest saw, sent from just in front of the camera
			if (!sawHit) {
				Vec3d fp = start.add(d.multiply(s));
				for (Entity me : world.getOtherEntities(p, new Box(fp, fp).expand(12 * s), ent -> ent instanceof UKProjectileEntity u && u.kind() == ProjectileKind.MAGNET && u.isStuck() && u.ownedBy(p) && ent.isAlive())) {
					Vec3d mc = ((UKProjectileEntity) me).magnetCenter();
					double md = mc.distanceTo(fp);
					if (md < 6 * s || md > 12 * s || Math.abs(fp.y - mc.y) > 3 * s) continue;
					UKProjectileEntity far = null;
					double farD = -1;
					for (Entity e : world.getOtherEntities(p, me.getBoundingBox().expand(MagnetField.RADIUS + 2), ent -> ent instanceof UKProjectileEntity u && u.sawRingMagnet() == me.getId() && ent.isAlive())) {
						double sd = e.getPos().distanceTo(start);
						if (sd > farD) { farD = sd; far = (UKProjectileEntity) e; }
					}
					if (far == null) continue;
					far.punchSawFromRing(fp, look);
					sawHit = true;
					break;
				}
			}
			if (sawHit) {
				Net.toPlayer(p, new HitPayload(HitPayload.JUGGLE, HitPayload.PUNCH, 2, start.add(d.multiply(s))));
				return true;
			}
		}
		return false;
	}

	/** NewMovement.Parry: full heal, full stamina and the PARRY style bonus happen on the client from this packet. */
	public static void success(ServerPlayerEntity p, Vec3d at) {
		success(p, at, false);
	}

	/** @param own V1's own shot (boosted pellets): the parry happens, the style meter gets nothing (arg 1) */
	public static void success(ServerPlayerEntity p, Vec3d at, boolean own) {
		success(p, at, own, false);
	}

	/**
	 * A shooting parry (the shotgun's point-blank zone on an attacking enemy): the whole parry - flash, freeze, sound,
	 * heal, style - but no fist comes up (arg 3; user: the only difference from a punching parry).
	 */
	public static void shotSuccess(ServerPlayerEntity p, Vec3d at) {
		success(p, at, false, true);
	}

	private static void success(ServerPlayerEntity p, Vec3d at, boolean own, boolean shot) {
		com.ultracraft.UltraCraft.LOG.info("[parry] PARRY by {} at {}{}", p.getName().getString(), at, own ? " (own shot: no heal)" : shot ? " (shooting parry)" : "");
		// NewMovement.Parry's full heal only for parrying something hostile, not for boosting your own pellets
		if (!own) p.setHealth(p.getMaxHealth());
		// NewMovement.Parry: exploded = false (blast blood heals again)
		BloodHeal.clearExploded(p);
		// the whole game stops with the client's freeze (same live settings); only alone in the world, so a parry never
		// stops the game for other players
		if (ServerTune.get("parry.freezeOn", 1) != 0 && p.getServer() != null && p.getServer().getCurrentPlayerCount() <= 1)
			freezeUntil = System.nanoTime() + (long) (ServerTune.get("parry.freeze", 0.3) * 1e9);
		Net.toPlayer(p, new HitPayload(HitPayload.PARRY, HitPayload.PUNCH, own ? 1 : shot ? 3 : 0, at));
	}

	/**
	 * TimeController.ParryFlash on its own (a beam on a grenade): the white flash, TrueStop 0.25 and a 0.5 shake - no
	 * punch, no heal, no style.
	 */
	public static void flash(ServerPlayerEntity p, Vec3d at) {
		if (ServerTune.get("parry.freezeOn", 1) != 0 && p.getServer() != null && p.getServer().getCurrentPlayerCount() <= 1)
			freezeUntil = System.nanoTime() + (long) (0.25 * 1e9);
		Net.toPlayer(p, new HitPayload(HitPayload.PARRY, HitPayload.PUNCH, 2, at));
	}

	/**
	 * Punch.ActiveFrame's projectile checks around the camera: the closest enemy projectile in the zone (with a short
	 * look-ahead so a fast arrow is caught), else on the first frame V1's own pellets to boost.
	 */
	public static boolean tryParryProjectiles(ServerPlayerEntity p, Vec3d start, Vec3d d, boolean firstFrame) {
		return tryParryProjectiles(p, start, d, firstFrame, false);
	}

	/** @param pelletWindow the client timed the punch inside the shotgun parry window: fresh pellets count wherever they are */
	public static boolean tryParryProjectiles(ServerPlayerEntity p, Vec3d start, Vec3d d, boolean firstFrame, boolean pelletWindow) {
		var world = p.getServerWorld();
		double s = Damage.SCALE_BLOCKS;
		// Punch/Projectile Parry Zone: a box turned with the camera, 3 x 3 x 6 u, from the camera to 6 u ahead (centre z 3),
		// the Punch object 0.225 u below the eye (live: parry.zoneLength, parry.zoneWidth)
		double len = ServerTune.get("parry.zoneLength", 6) * s, half = ServerTune.get("parry.zoneWidth", 3) * s / 2, down = 0.225 * s;
		Vec3d flatRight = d.crossProduct(new Vec3d(0, 1, 0));
		Vec3d right = flatRight.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : flatRight.normalize();
		Vec3d up = right.crossProduct(d).normalize();
		Vec3d origin = start.subtract(up.multiply(down));
		java.util.function.Predicate<Vec3d> inZone = q -> {
			Vec3d r = q.subtract(origin);
			double z = r.dotProduct(d), x = r.dotProduct(right), y = r.dotProduct(up);
			return z >= 0 && z <= len && Math.abs(x) <= half && Math.abs(y) <= half;
		};
		Box zone = new Box(start, start.add(d.multiply(len))).expand(half + down);
		if (Damage.debug) {
			for (Entity e : world.getOtherEntities(p, zone.expand(14), ent -> ent instanceof UKProjectileEntity))
				com.ultracraft.UltraCraft.LOG.info("[parry] near {} kind={} own={} boosted={} age={} inZone={} dist={} first={} window={}", e.getId(), ((UKProjectileEntity) e).kind(),
					((UKProjectileEntity) e).ownedBy(p), ((UKProjectileEntity) e).isBoosted(), e.age, inZone.test(e.getPos()), String.format("%.2f", e.getPos().distanceTo(start)), firstFrame, pelletWindow);
		}
		// Punch.TryParryProjectile on a Cannonball (on the punch's own frame): Hook and the parry flash (parry 0: no heal),
		// then it is launched at the look target
		if (firstFrame) {
			for (Entity e : world.getOtherEntities(p, zone, ent -> ent instanceof UKProjectileEntity u && u.cannonballParryable() && inZone.test(ent.getPos()))) {
				((UKProjectileEntity) e).parryCannonball(start, d, WeaponServer.parryLookTarget(p, start, d));
				success(p, e.getPos(), true);
				return true;
			}
		}
		ProjectileEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : world.getOtherEntities(p, zone.expand(2.5), ent -> ent instanceof ProjectileEntity pr && pr.getOwner() != p && ent.isAlive())) {
			ProjectileEntity pr = (ProjectileEntity) e;
			Vec3d pos = pr.getPos(), v = pr.getVelocity();
			// with a short look-ahead (the next two ticks) so a fast arrow is not stepped over
			boolean in = inZone.test(pos) || inZone.test(pos.add(v.multiply(0.5))) || inZone.test(pos.add(v)) || inZone.test(pos.add(v.multiply(2)));
			if (!in) continue;
			double dist = pos.squaredDistanceTo(start);
			if (dist < bestDist) { bestDist = dist; best = pr; }
		}
		if (best != null) {
			parryProjectile(p, best, start, d);
			success(p, best.getPos());
			return true;
		}
		// chainsaw juggle: only on the punch's own frame, so a saw that flies into the later active ticks of an early punch
		// is a miss (no late Hook). The saw must be within arm's reach right then: its centre no further than
		// punch.sawReachBlocks from the eye and within punch.sawRadius of the aim line; or already caught at V1's chest.
		// It is sent out again at 105 u/s (Punch.ChainsawPunchRoutine: from the camera + forward, toward the parry look target)
		if (firstFrame) {
			double sawReach = ServerTune.get("punch.sawReachBlocks", 2.0), sawRadius = ServerTune.get("punch.sawRadius", 0.75) * s;
			Vec3d sawEnd = start.add(d.multiply(sawReach));
			// Punch.TryParryProjectile collects every chainsaw it touches (two Sawed-Ons' saws at once) and
			// ChainsawPunchRoutine sends them out one at a time, WaitForSeconds(0.05) apart: one tick each
			List<UKProjectileEntity> saws = new ArrayList<>();
			for (Entity e : world.getOtherEntities(p, new Box(start, sawEnd).expand(sawRadius + 1.5), ent -> ent instanceof UKProjectileEntity u && u.kind() == ProjectileKind.CHAINSAW && u.ownedBy(p) && ent.isAlive())) {
				UKProjectileEntity saw = (UKProjectileEntity) e;
				Vec3d c = saw.getPos();
				double along = c.subtract(start).dotProduct(d);
				boolean inReach = along >= 0 && c.distanceTo(start) <= sawReach && c.distanceTo(start.add(d.multiply(along))) <= sawRadius;
				if (inReach || saw.sawCaught()) saws.add(saw);
			}
			if (!saws.isEmpty()) {
				punchSaw(p, saws.get(0), start, d, false);
				for (int i = 1; i < saws.size(); i++) sawPunches.add(new SawPunch(p, saws.get(i), new int[] {i}));
				return true;
			}
		}
		if (firstFrame) {
			boolean any = false;
			for (Entity e : world.getOtherEntities(p, pelletWindow ? zone.expand(12) : zone, ent -> ent instanceof UKProjectileEntity u && u.kind() == ProjectileKind.PELLET && !u.isBoosted() && u.ownedBy(p) && ent.isAlive()
				&& (inZone.test(ent.getPos()) || (pelletWindow && ent.age <= ServerTune.get("parry.pelletAge", 12))))) {
				((UKProjectileEntity) e).boost();
				any = true;
			}
			if (any) {
				success(p, start.add(d.multiply(s)), true);
				return true;
			}
		}
		return false;
	}

	/**
	 * Punch.ParryProjectile on a Minecraft projectile: it is friendly, flies straight (no gravity, no homing) and its
	 * collider grows 4x (SphereCollider.radius * 4) so it actually meets enemies; whatever it touches first, enemy or
	 * wall, sets off parriedProjectileHitObject (the Explosion prefab). The check runs on the path of the coming tick,
	 * before vanilla's own collision, so arrows / tridents / potions / fireballs all explode instead of sticking or splashing.
	 */
	private static void tickParried() {
		for (Iterator<Parried> it = parried.values().iterator(); it.hasNext(); ) {
			Parried pa = it.next();
			ProjectileEntity pr = pa.pr();
			if (pr.isRemoved() || pa.p().isRemoved()) { it.remove(); continue; }
			if (--pa.ticks()[0] < 0) { it.remove(); pr.discard(); continue; }
			if (!(pr.getWorld() instanceof net.minecraft.server.world.ServerWorld world)) { it.remove(); continue; }
			pr.setNoGravity(true);
			pr.setVelocity(pa.vel());
			pr.velocityModified = true;
			Vec3d from = pr.getPos(), to = from.add(pa.vel());
			double r = ServerTune.get("parry.projRadius", 1.0);
			Vec3d at = null;
			double best = Double.MAX_VALUE;
			for (Entity e : world.getOtherEntities(pa.p(), new Box(from, to).expand(r + 1), ent -> ent instanceof LivingEntity && ent.isAlive() && ent != pr)) {
				Box bb = e.getBoundingBox().expand(r);
				java.util.Optional<Vec3d> h = bb.contains(from) ? java.util.Optional.of(from) : bb.raycast(from, to);
				if (h.isPresent() && h.get().squaredDistanceTo(from) < best) { best = h.get().squaredDistanceTo(from); at = h.get(); }
			}
			net.minecraft.util.hit.BlockHitResult wall = world.raycast(new net.minecraft.world.RaycastContext(from, to, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, pr));
			if (wall.getType() != net.minecraft.util.hit.HitResult.Type.MISS && (at == null || wall.getPos().squaredDistanceTo(from) < best)) at = wall.getPos();
			if (at == null) continue;
			it.remove();
			com.ultracraft.UltraCraft.LOG.info("[parry] parried {} went off at {}", pr.getType().getUntranslatedName(), at);
			Explosions.explode(world, pa.p(), at, Explosions.PARRIED, false, false, "parry", pr);
		}
	}

	/** Punch.ParryProjectile: back where V1 looks, twice the speed (at least 100 u/s), now V1's projectile. */
	private static void parryProjectile(ServerPlayerEntity p, ProjectileEntity pr, Vec3d start, Vec3d d) {
		Vec3d target = WeaponServer.parryLookTarget(p, start, d);
		double speed = Math.max(pr.getVelocity().length() * 2, 100 * Damage.SCALE_BLOCKS / 20);
		Vec3d v = target.subtract(pr.getPos()).normalize().multiply(speed);
		pr.setOwner(p);
		pr.setVelocity(v);
		pr.velocityModified = true;
		pr.setNoGravity(true);
		parried.put(pr.getId(), new Parried(pr, p, v, new int[] {200}));
		if (pr instanceof ExplosiveProjectileEntity ex) {
			// fireballs steer by their power vector (like a deflecting sword hit sets it)
			ex.powerX = v.x * 0.1;
			ex.powerY = v.y * 0.1;
			ex.powerZ = v.z * 0.1;
		}
		if (pr instanceof PersistentProjectileEntity arrow) {
			arrow.setCritical(true);
			arrow.pickupType = PersistentProjectileEntity.PickupPermission.ALLOWED;
		}
	}
}
