package com.ultracraft.weapon;

import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ULTRAKILL's lava for a player with the ULTRAKILL lava option on (the client throws V1 back out and says so): the
 * level lava's HurtZone (damageType Burn, setDamage 20, hurtCooldown 1, bounceForce 500, hard damage 0.35) hurts V1 20
 * of its 100 health - 4 of the player's 20 - at most once a second, and nothing burns: Minecraft's lava damage and
 * fire are held off while V1 is bouncing out (lava.grace ticks).
 */
public final class LavaServer {
	private LavaServer() {}

	private static final Map<UUID, Integer> lastHurt = new HashMap<>();
	private static final Map<UUID, Integer> graceUntil = new HashMap<>();
	private static boolean dealing;

	public static void init() {
		MinecraftForge.EVENT_BUS.addListener((LivingAttackEvent e) -> {
			if (dealing || !(e.getEntity() instanceof ServerPlayerEntity p) || !e.getSource().isIn(DamageTypeTags.IS_FIRE)) return;
			if (inGrace(p)) e.setCanceled(true);
		});
		MinecraftForge.EVENT_BUS.addListener((TickEvent.PlayerTickEvent e) -> {
			if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayerEntity p)) return;
			if (inGrace(p) && p.isOnFire()) p.extinguish();
			Integer at = burnAt.get(p);
			if (at != null && p.getServer().getTicks() >= at) {
				burnAt.remove(p);
				if (!p.isAlive()) return;
				dealing = true;
				try {
					p.damage(p.getDamageSources().lava(), Float.MAX_VALUE);
				} finally {
					dealing = false;
				}
			}
		});
	}

	/** Burning players and the tick they die, by entity: a respawn is a new player entity and is not killed again. */
	private static final Map<ServerPlayerEntity, Integer> burnAt = new java.util.WeakHashMap<>();

	/**
	 * FirePayload.LAVA_BURN: clash mode V1 touched lava (PlatformerMovement.Burn). It dies where it burnt once the
	 * charcoal copy has crumbled (BecomeAsh 1 s, DeathOver 1 s later: lava.burnTicks 40); nothing else burns it till then.
	 */
	public static void burn(ServerPlayerEntity p) {
		if (!p.isAlive() || !nearLava(p) || burnAt.containsKey(p)) return;
		int now = p.getServer().getTicks(), ticks = (int) ServerTune.get("lava.burnTicks", 40);
		burnAt.put(p, now + ticks);
		graceUntil.put(p.getUuid(), now + ticks);
		p.extinguish();
	}

	/** Lava around the player (the server's copy is a tick behind a V1 that falls fast: up to 3 blocks under it counts). */
	private static boolean nearLava(ServerPlayerEntity p) {
		var world = p.getServerWorld();
		var b = p.getBoundingBox().expand(1);
		for (BlockPos bp : BlockPos.iterate((int) Math.floor(b.minX), (int) Math.floor(b.minY - 2), (int) Math.floor(b.minZ),
			(int) Math.floor(b.maxX), (int) Math.floor(b.maxY), (int) Math.floor(b.maxZ)))
			if (world.getFluidState(bp).isIn(FluidTags.LAVA)) return true;
		return false;
	}

	private static boolean inGrace(ServerPlayerEntity p) {
		Integer until = graceUntil.get(p.getUuid());
		return until != null && p.getServer() != null && p.getServer().getTicks() < until;
	}

	/** FirePayload.LAVA: the client's V1 touched lava (checked here: lava within a block of the player). */
	public static void touched(ServerPlayerEntity p) {
		if (!nearLava(p)) return;
		int now = p.getServer().getTicks();
		graceUntil.put(p.getUuid(), now + (int) ServerTune.get("lava.grace", 30));
		p.extinguish();
		Integer last = lastHurt.get(p.getUuid());
		if (last != null && now - last < 20 * ServerTune.get("lava.cooldown", 1)) return;
		lastHurt.put(p.getUuid(), now);
		dealing = true;
		try {
			// GetHurt(20, invincible: true, hard damage 0.35): 20 V1 health = 4 of Minecraft's
			p.damage(p.getDamageSources().lava(), (float) ServerTune.get("lava.damage", 20) / 5f);
		} finally {
			dealing = false;
		}
		p.extinguish();
	}
}
