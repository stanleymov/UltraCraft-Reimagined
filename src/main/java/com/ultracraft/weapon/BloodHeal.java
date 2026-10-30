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
 * V1's 100 hp is the player's 20 HP (x5).
 */
public final class BloodHeal {
	private BloodHeal() {}

	/** A splash whose sphere is still on: who may drink it, where, how far, how much, for how many more ticks. */
	private record Splash(ServerPlayerEntity p, Vec3d at, double radius, int hp, int[] ticks) {}

	private static final List<Splash> splashes = new ArrayList<>();

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
		int hp = headBlood ? 30 : 10;
		double radiusU = headBlood ? 7 : 5;
		splashes.add(new Splash(shooter, at, radiusU * Damage.SCALE_BLOCKS, hp, new int[] {5}));
	}

	/** Server tick: a splash heals the first time its player is inside its sphere, for 0.25 s (5 ticks). */
	public static void tick() {
		for (Iterator<Splash> it = splashes.iterator(); it.hasNext(); ) {
			Splash s = it.next();
			if (s.p.isRemoved() || !s.p.isAlive()) { it.remove(); continue; }
			Box b = s.p.getBoundingBox();
			Vec3d c = new Vec3d(Math.max(b.minX, Math.min(b.maxX, s.at.x)), Math.max(b.minY, Math.min(b.maxY, s.at.y)), Math.max(b.minZ, Math.min(b.maxZ, s.at.z)));
			if (c.distanceTo(s.at) <= s.radius) {
				heal(s.p, s.hp);
				it.remove();
				continue;
			}
			if (--s.ticks[0] <= 0) it.remove();
		}
	}
}
