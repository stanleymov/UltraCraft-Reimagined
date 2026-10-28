package com.ultracraft.weapon;

import com.ultracraft.net.HitPayload;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/**
 * ULTRAKILL damage onto Minecraft mobs. One ULTRAKILL damage point = {@link #hpPerDamage()} Minecraft HP
 * (default 40: a Filth has 0.5 hp and dies to one revolver shot, a 20-HP zombie too). Server-side config lives in
 * `config/ultracraft-server.toml`.
 * <p>
 * Big enemies and bosses (user's pairings, 2026-10-28) take ULTRAKILL's own rules instead: the paired enemy's health
 * (EnemyIdentifier.health: as many points kill them as kill it) and its weaknesses (weaknesses / weaknessMultipliers by
 * hitter). Every other mob's worn armour resists by piece and material (the user's shot counts), and Minecraft's own
 * armour formula is left out (the ultrakill damage type bypasses armour), since it barely dents 40-HP hits.
 */
public final class Damage {
	/** Blocks per Unity unit (Steve-sized V1). */
	public static final double SCALE_BLOCKS = 1.8 / 3.5;
	private static double hpPerDamage = 40;
	private static double headFraction = 0.25;
	private static double rangeBlocks = 128;
	/** Weapon debug logging (set from the client's live settings in single player). */
	public static boolean debug;

	private Damage() {}

	public static double hpPerDamage() {
		return hpPerDamage;
	}

	public static double headFraction() {
		return headFraction;
	}

	public static double rangeBlocks() {
		return rangeBlocks;
	}

	public static void configure(double hp, double head, double range) {
		hpPerDamage = hp;
		headFraction = head;
		rangeBlocks = range;
	}

	/** StyleCalculator's "spider" class (big enemies: BIG KILL / BIG FISTKILL / DISRESPECT) = mobs with at least `bigHp` max health. */
	public static double bigHp = 50;

	public static int flags(LivingEntity target) {
		// bits 4-5: the size of the hit just dealt to it, for its gore (BloodsplatterManager: Body for 1+, Small under
		// that, Smallest for the tiniest: single nails, saw ticks)
		Float pts = LAST_POINTS.get(target);
		int gore = pts == null ? 2 : pts >= 1 ? 2 : pts >= 0.3f ? 1 : 0;
		return (target.getMaxHealth() >= bigHp ? 1 : 0) | gore << 4;
	}

	/** The last hit each target took (points), for flags(). */
	private static final java.util.Map<LivingEntity, Float> LAST_POINTS = new java.util.WeakHashMap<>();

	/**
	 * A big enemy or boss: the ULTRAKILL enemy it plays as (`key` names its live settings mob.&lt;key&gt;.*), that enemy's
	 * health, and its damage multipliers by hitter (HitPayload kinds; ELECTRIC = HitterAttribute.Electricity).
	 */
	public record Profile(String key, double ukHealth, boolean boss, Map<Integer, Double> weak) {}

	/** The Electricity hitter attribute (Zapper, the electric railcannon), for the "electricity" weakness. */
	public static final int ELECTRIC = 100;
	/** A parried shotgun blast's pellet explosions (Explosions.PARRIED): the bosses' "parriedBlast" resistance. */
	public static final int PARRIED_BLAST = 101;
	/** A rocket's explosion (the Wither's "rocketBlast" weakness; other blasts stay "explosion"). */
	public static final int ROCKET_BLAST = 102;
	/** A revolver's right-click shot - the Piercer's charged beam, the Sharpshooter's (Hitscan kinds 1 and 4). */
	public static final int REVOLVER_ALT_FIRE = 103;

	private static final Map<EntityType<?>, Profile> PROFILES = Map.of(
		// big enemies (EnemyIdentifier.bigEnemy)
		EntityType.EVOKER, new Profile("evoker", 10, false, Map.of(HitPayload.NAIL, 1.25, HitPayload.RAILCANNON, 1.5, ELECTRIC, 1.5)), // Virtue
		EntityType.ELDER_GUARDIAN, new Profile("elderGuardian", 15, false, Map.of(HitPayload.NAIL, 1.5, HitPayload.GROUNDSLAM, 2.0, HitPayload.HAMMER, 1.5)), // Malicious Face
		EntityType.RAVAGER, new Profile("ravager", 30, false, Map.of(HitPayload.SHOTGUN, 1.5, HitPayload.SHOTGUNZONE, 1.5)), // Swordsmachine
		EntityType.IRON_GOLEM, new Profile("ironGolem", 22, false, Map.of(HitPayload.CANNONBALL, 2.0)), // Cerberus (its 22 hp setting)
		// bosses (BossIdentifier); the railcannon halving on the Wither and the dragon is the user's
		// (and one weakness each, x1.5, the user's picks: the Warden the revolver and its coins, the Wither the jackhammer
		// and the revolver, the dragon nails)
		EntityType.WARDEN, new Profile("warden", 300, true, Map.of(HitPayload.RAILCANNON, 0.5, HitPayload.NAIL, 0.5, HitPayload.SAWBLADE, 0.5, PARRIED_BLAST, 0.1,
			HitPayload.REVOLVER, 1.5, REVOLVER_ALT_FIRE, 1.5, HitPayload.COIN, 1.5)), // Flesh Panopticon
		// (user: the Wither also takes half from the shotgun's pellets, and all three bosses a tenth from a parried blast's
		// pellet explosions - twelve pellets each going off for 3.5 took a fifth of the Wither a parry)
		EntityType.WITHER, new Profile("wither", 200, true, Map.of(HitPayload.RAILCANNON, 0.5, HitPayload.SHOTGUN, 0.5, PARRIED_BLAST, 0.1,
			HitPayload.HAMMER, 1.5, REVOLVER_ALT_FIRE, 1.5)), // Sisyphus Prime (the revolvers' right clicks only: user) (parried blasts a tenth: user, at half ~10 parries killed it)
		EntityType.ENDER_DRAGON, new Profile("enderDragon", 160, true, Map.of(HitPayload.RAILCANNON, 0.5, PARRIED_BLAST, 0.1, HitPayload.NAIL, 1.5))); // Minos (the corpse)

	/** The live name of a hitter in mob.&lt;key&gt;.&lt;name&gt; (ULTRAKILL's hitter strings). */
	private static String hitterName(int h) {
		return switch (h) {
			case HitPayload.NAIL -> "nail";
			case HitPayload.RAILCANNON -> "railcannon";
			case HitPayload.SAWBLADE -> "sawblade";
			case HitPayload.GROUNDSLAM -> "groundSlam";
			case HitPayload.HAMMER -> "hammer";
			case HitPayload.SHOTGUN -> "shotgun";
			case HitPayload.SHOTGUNZONE -> "shotgunzone";
			case HitPayload.CANNONBALL -> "cannonball";
			case ELECTRIC -> "electricity";
			case PARRIED_BLAST -> "parriedBlast";
			case ROCKET_BLAST -> "rocketBlast";
			case HitPayload.REVOLVER -> "revolver";
			case REVOLVER_ALT_FIRE -> "revolverAltFire";
			case HitPayload.COIN -> "coin";
			default -> "hit" + h;
		};
	}

	public static Profile profile(LivingEntity t) {
		return PROFILES.get(t.getType());
	}

	/** A boss (BossIdentifier): not killed outright by the clash spin. */
	public static boolean isBoss(LivingEntity t) {
		Profile p = profile(t);
		return p != null && p.boss();
	}

	/** EnemyIdentifier.DeliverDamage: every weakness the hit matches multiplies it (the electric railcannon both). */
	private static double weakness(Profile p, int hitter, boolean electric) {
		double m = 1;
		for (var e : p.weak().entrySet()) {
			boolean hit = e.getKey() == hitter || (e.getKey() == ELECTRIC && electric);
			if (hit) m *= ServerTune.get("mob." + p.key() + "." + hitterName(e.getKey()), e.getValue());
		}
		return m;
	}

	/**
	 * Worn armour (the user's rule): each piece resists by its material, whatever the slot; damage x 1 / (1 + the pieces'
	 * sum). A 20-HP zombie and 1-point revolver shots: an iron piece 2 shots, full iron / gold / chainmail 3, full diamond
	 * 5, full netherite 10 (leather: full set 2).
	 */
	static double armorFactor(LivingEntity t) {
		double sum = 0;
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack st = t.getEquippedStack(s);
			if (!(st.getItem() instanceof ArmorItem a)) continue;
			var m = a.getMaterial();
			String k = m == ArmorMaterials.LEATHER ? "leather" : m == ArmorMaterials.CHAIN ? "chainmail" : m == ArmorMaterials.GOLD ? "gold"
				: m == ArmorMaterials.IRON ? "iron" : m == ArmorMaterials.TURTLE ? "turtle" : m == ArmorMaterials.DIAMOND ? "diamond"
				: m == ArmorMaterials.NETHERITE ? "netherite" : "other";
			double def = switch (k) {
				case "leather" -> 0.4;
				case "diamond" -> 2.0;
				case "netherite" -> 4.5;
				default -> 1.2; // chainmail, gold, iron, turtle, and modded armour
			};
			sum += ServerTune.get("armor." + k, def);
		}
		return 1 / (1 + sum);
	}

	/**
	 * A zombified piglin hit by V1: the group turns on it, as ZombifiedPiglinEntity.angerNearbyZombifiedPiglins passes its
	 * anger (follow range around, 10 up and down). Minecraft only passes it on from a living one's revenge; our hits
	 * mostly kill in one, so the hit itself raises the alarm.
	 */
	private static void angerZombifiedPiglins(ServerPlayerEntity shooter, net.minecraft.entity.mob.ZombifiedPiglinEntity hit) {
		if (shooter.isCreative() || shooter.isSpectator()) return;
		double d = hit.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE);
		var box = net.minecraft.util.math.Box.from(hit.getPos()).expand(d, 10.0, d);
		for (var zp : hit.getWorld().getEntitiesByClass(net.minecraft.entity.mob.ZombifiedPiglinEntity.class, box, net.minecraft.predicate.entity.EntityPredicates.EXCEPT_SPECTATOR)) {
			if (!zp.isAlive() || zp.isTeammate(shooter)) continue;
			zp.setAngryAt(shooter.getUuid());
			zp.chooseRandomAngerTime();
			if (zp.getTarget() == null) zp.setTarget(shooter);
		}
	}

	/**
	 * An End crystal hit by any of V1's weapons: it is not living, so the weapons' searches skip it; any player's hit
	 * destroys it (EndCrystalEntity.damage: it blows up and tells the dragon fight).
	 */
	public static void hitCrystal(ServerPlayerEntity shooter, net.minecraft.entity.decoration.EndCrystalEntity crystal) {
		if (shooter == null || !crystal.isAlive()) return;
		crystal.damage(source(shooter), 1f);
	}

	/** The End crystals a segment (widened by `radius`) passes through, from `start`, nearest first. */
	public static java.util.List<net.minecraft.entity.decoration.EndCrystalEntity> crystalsOn(net.minecraft.world.World world, Vec3d start, Vec3d end, double radius) {
		var list = new java.util.ArrayList<net.minecraft.entity.decoration.EndCrystalEntity>(world.getEntitiesByClass(net.minecraft.entity.decoration.EndCrystalEntity.class,
			new net.minecraft.util.math.Box(start, end).expand(radius + 1), c -> c.isAlive() && (c.getBoundingBox().expand(radius).raycast(start, end).isPresent() || c.getBoundingBox().expand(radius).contains(start))));
		list.sort(java.util.Comparator.comparingDouble(c -> c.squaredDistanceTo(start)));
		return list;
	}

	private static net.minecraft.entity.boss.dragon.EnderDragonPart nearestPart(EnderDragonEntity dragon, Vec3d point) {
		// (getBodyParts: head, neck, body, three tail parts, two wings)
		net.minecraft.entity.boss.dragon.EnderDragonPart best = dragon.getBodyParts()[2];
		double bd = Double.MAX_VALUE;
		for (var part : dragon.getBodyParts()) {
			var b = part.getBoundingBox();
			double dx = Math.max(0, Math.max(b.minX - point.x, point.x - b.maxX));
			double dy = Math.max(0, Math.max(b.minY - point.y, point.y - b.maxY));
			double dz = Math.max(0, Math.max(b.minZ - point.z, point.z - b.maxZ));
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bd) { bd = d; best = part; }
		}
		return best;
	}

	private static final RegistryKey<DamageType> ULTRAKILL = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, new Identifier("ultracraft", "ultrakill"));

	/** The player's hit as the ultrakill damage type (Minecraft's armour formula skipped; enchantments still count). */
	private static DamageSource source(ServerPlayerEntity shooter) {
		var reg = shooter.getWorld().getRegistryManager().get(RegistryKeys.DAMAGE_TYPE);
		var entry = reg.getEntry(ULTRAKILL);
		return entry.isPresent() ? new DamageSource(entry.get(), shooter) : shooter.getDamageSources().playerAttack(shooter);
	}

	/** Deal `points` ULTRAKILL damage with ULTRAKILL's knockback (force is DeliverDamage's, mass 100 ≈ scaled). */
	public static void deal(ServerPlayerEntity shooter, LivingEntity target, float points, Vec3d dir, float force) {
		deal(shooter, target, points, dir, force, -1, false);
	}

	public static void deal(ServerPlayerEntity shooter, LivingEntity target, float points, Vec3d dir, float force, int hitter) {
		deal(shooter, target, points, dir, force, hitter, hitter == HitPayload.ZAPPER);
	}

	public static void deal(ServerPlayerEntity shooter, LivingEntity target, float points, Vec3d dir, float force, int hitter, boolean electric) {
		deal(shooter, target, points, dir, force, hitter, electric, null);
	}

	public static void deal(ServerPlayerEntity shooter, LivingEntity target, float points, Vec3d dir, float force, int hitter, Vec3d point) {
		deal(shooter, target, points, dir, force, hitter, hitter == HitPayload.ZAPPER, point);
	}

	/**
	 * `hitter`: the HitPayload kind (-1: none of the weaknesses); `electric`: the hit has the Electricity attribute;
	 * `point`: where it landed (null: no one point, an explosion's), for the dragon's parts.
	 */
	public static void deal(ServerPlayerEntity shooter, LivingEntity target, float points, Vec3d dir, float force, int hitter, boolean electric, Vec3d point) {
		LAST_POINTS.put(target, points);
		Profile p = profile(target);
		float amount;
		if (p != null) {
			// as many points as the paired ULTRAKILL enemy's health take all of this mob's
			double uk = Math.max(0.01, ServerTune.get("mob." + p.key() + ".health", p.ukHealth()));
			amount = (float) (points * weakness(p, hitter, electric) * target.getMaxHealth() / uk);
		} else amount = (float) (points * hpPerDamage);
		if (p == null || !p.boss()) amount *= (float) armorFactor(target);
		DamageSource src = source(shooter);
		// no i-frames between ULTRAKILL hits
		target.timeUntilRegen = 0;
		target.hurtTime = 0;
		// the dragon as in Minecraft (user): the part nearest the hit takes it - the head whole, any other amount / 4 +
		// min(amount, 1) (EnderDragonEntity.damagePart); without a point, the body
		// (a boss keeps its velocity: Minecraft's 0.4 hit knockback, every hit, made the Wither twitch in the air)
		Vec3d keepVel = p != null && p.boss() ? target.getVelocity() : null;
		if (target instanceof EnderDragonEntity dragon && point != null) dragon.damagePart(nearestPart(dragon, point), src, amount);
		else target.damage(src, amount);
		if (keepVel != null) target.setVelocity(keepVel);
		if (target instanceof net.minecraft.entity.mob.ZombifiedPiglinEntity zp) angerZombifiedPiglins(shooter, zp);
		if (debug) com.ultracraft.UltraCraft.LOG.info("[damage] {} {} pts={} hitter={} -> {} hp (armour x{}), health now {}", target.getType().getUntranslatedName(),
			p == null ? "normal" : p.key(), points, hitter, amount, String.format("%.3f", armorFactor(target)), target.getHealth());
		// (no push on a boss: the Wither fought it in mid-air and jittered on every hit)
		double push = p != null && p.boss() ? 0 : Math.min(2.5, force / 20000.0);
		if (push > 0.01 && target.isAlive()) target.addVelocity(dir.x * push * 0.2, 0.05 + push * 0.05, dir.z * push * 0.2);
	}
}
