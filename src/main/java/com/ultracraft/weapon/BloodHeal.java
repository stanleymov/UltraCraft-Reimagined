package com.ultracraft.weapon;

import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ULTRAKILL heals V1 with the blood of enemies hit close by. Every damaging hit (Zombie.GetHurt; not fire) leaves a
 * Bloodsplatter whose trigger sphere is on for 0.25 s (Bloodsplatter.OnEnable → DisableCollider): V1 inside it then
 * gets its hpAmount (GetHealth). The blood and its sphere by the hit (BS prefabs, tools/re/probe_blood.py):
 * <ul>
 * <li>Head (a head hit of 1+ or a kill; any Jackhammer hit): 30 hp, radius 7 u</li>
 * <li>Body (a body or limb hit of 1+ or a kill; the game's Limb is 20 hp in 6 u): 10 hp, 5 u</li>
 * <li>Small (any lighter hit): 10 hp, 5 u</li>
 * </ul>
 * Shotgun, point-blank and explosion blood is worth 3 whatever the gore, a nail's 1 (the enemies set hpAmount by hitter).
 * Blood an explosion spilled heals nothing while V1 is "exploded" (NewMovement.GetHealth: exploded && fromExplosion):
 * hurt by a blast, until its hurt invincibility ends (0.5 s, 0.8 s for 50+) or a parry. Splashes heal from the next
 * tick on, so a blast's own blood is too late once the same blast has hurt V1.
 * V1's 100 hp is the player's 20 HP (x5).
 */
public final class BloodHeal {
	private BloodHeal() {}

	/** A splash whose sphere is still on: who may drink it, where, how far, how much, for how many more ticks. */
	private record Splash(ServerPlayerEntity p, Vec3d at, double radius, int hp, boolean fromExplosion, int[] ticks) {}

	private static final List<Splash> splashes = new ArrayList<>();
	/** Per player: until when (server ticks) V1 counts as exploded. */
	private static final java.util.Map<java.util.UUID, Integer> explodedUntil = new java.util.HashMap<>();

	/** A blast hurt V1 for `hurtV1`: exploded until its hurt invincibility ends (currentColor.a: 0.5 s, 0.8 s for 50+). */
	public static void exploded(ServerPlayerEntity p, int hurtV1) {
		explodedUntil.put(p.getUuid(), p.getServer().getTicks() + (hurtV1 >= 50 ? 16 : 10));
	}

	/** NewMovement.Parry: exploded = false. */
	public static void clearExploded(ServerPlayerEntity p) {
		explodedUntil.remove(p.getUuid());
	}

	private static boolean isExploded(ServerPlayerEntity p) {
		Integer until = explodedUntil.get(p.getUuid());
		return until != null && p.getServer() != null && p.getServer().getTicks() < until;
	}

	public static void heal(ServerPlayerEntity p, int amountV1) {
		float before = p.getHealth();
		p.heal(amountV1 / 5f);
		if (p.getHealth() > before + 0.01f) {
			Net.toPlayer(p, new HitPayload(HitPayload.HEAL, HitPayload.PUNCH, amountV1, p.getPos()));
		}
	}

	/** Damage.deal's hit on `target`: the blood it leaves (none from fire, hitter -1: gasoline's burn). */
	public static void onHit(ServerPlayerEntity shooter, LivingEntity target, float points, int hitter, Vec3d point, boolean killed) {
		if (shooter == null || hitter < 0 || points <= 0 || ServerTune.get("blood.heal", 1) == 0) return;
		Vec3d at = point != null ? point : target.getBoundingBox().getCenter();
		boolean head = point != null && point.y >= target.getY() + target.getHeight() * 0.8;
		boolean full = points >= 1 || killed;
		boolean headBlood = hitter == HitPayload.HAMMER || (head && full);
		boolean fromExplosion = hitter == HitPayload.EXPLOSION || hitter == Damage.PARRIED_BLAST || hitter == Damage.ROCKET_BLAST;
		// Zombie / Machine / Statue.GetHurt: the gore of a "shotgun", "shotgunzone" or "explosion" hit is set to hpAmount 3,
		// a "nail" hit's to 1; any other keeps its prefab's (BS Head 30, Body / Small 10)
		int hp = hitter == HitPayload.SHOTGUN || hitter == HitPayload.SHOTGUNZONE || fromExplosion ? 3
			: hitter == HitPayload.NAIL ? 1 : headBlood ? 30 : 10;
		double radiusU = headBlood ? 7 : 5;
		// (a blast's blood waits for the next tick like any other: the same blast has reached V1 by then and, if it hurt
		// it, V1 is exploded - Bloodsplatter heals on OnTriggerEnter, a physics step after the Explosion hurt the player.
		// The user's ULTRAKILL test: a boosted blast on a blind Swordsmachine costs 8 every time, 35 against 9 pellets x 3)
		splashes.add(new Splash(shooter, at, radiusU * Damage.SCALE_BLOCKS, hp, fromExplosion, new int[] {5}));
		// Zombie.ChestExplosion: the point-blank shot (shotgunzone: chestHP 0) killing through the body bursts the chest -
		// one Body splash of 10 for every hit part under it (Filth 2, Soldier 6, Stray 8; blood.chestParts for a mob)
		if (killed && !head && hitter == HitPayload.SHOTGUNZONE) {
			Vec3d chest = target.getBoundingBox().getCenter();
			for (int i = 0; i < (int) ServerTune.get("blood.chestParts", 6); i++)
				splashes.add(new Splash(shooter, chest, 5 * Damage.SCALE_BLOCKS, 10, false, new int[] {5}));
		}
	}

	/** True if `s` healed its player now (V1 inside its sphere and, for blast blood, not exploded). */
	private static boolean drink(Splash s) {
		if (s.fromExplosion && isExploded(s.p)) return false;
		Box b = s.p.getBoundingBox();
		Vec3d c = new Vec3d(Math.max(b.minX, Math.min(b.maxX, s.at.x)), Math.max(b.minY, Math.min(b.maxY, s.at.y)), Math.max(b.minZ, Math.min(b.maxZ, s.at.z)));
		if (c.distanceTo(s.at) > s.radius) return false;
		heal(s.p, s.hp);
		return true;
	}

	/** Server tick: a splash heals the first time its player is inside its sphere, for 0.25 s (5 ticks). */
	public static void tick() {
		for (Iterator<Splash> it = splashes.iterator(); it.hasNext(); ) {
			Splash s = it.next();
			if (s.p.isRemoved() || !s.p.isAlive()) { it.remove(); continue; }
			if (drink(s) || --s.ticks[0] <= 0) it.remove();
		}
	}
}
