package com.ultracraft.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.net.Net;
import com.ultracraft.net.PointsSyncPayload;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A boss killed pays a flat P bonus to the player who killed it (the user's amounts, not doubled like fight points; live:
 * boss.reward.*): the Warden 100,000, the Wither 25,000, the Ender Dragon 10,000. The dragon has no ordinary death - at 0
 * health it goes into its DYING phase - so it pays when that phase starts.
 */
public final class BossRewards {
	private BossRewards() {}

	private static final Set<EnderDragonEntity> paidDragons = Collections.newSetFromMap(new WeakHashMap<>());

	/** LivingDeathEvent: the Wither and the Warden. */
	public static void onDeath(LivingEntity dead, net.minecraft.entity.damage.DamageSource source) {
		String key = dead instanceof WardenEntity ? "warden" : dead instanceof WitherEntity ? "wither" : null;
		if (key == null) return;
		pay(killer(dead, source), key, key.equals("warden") ? 100000 : 25000);
	}

	/** Server tick: a dragon that has just started dying. */
	public static void tick(MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			for (EnderDragonEntity dragon : world.getAliveEnderDragons()) {
				if (dragon.getPhaseManager().getCurrent().getType() != PhaseType.DYING || !paidDragons.add(dragon)) continue;
				pay(killer(dragon, null), "enderDragon", 10000);
			}
		}
	}

	private static ServerPlayerEntity killer(LivingEntity dead, net.minecraft.entity.damage.DamageSource source) {
		if (source != null && source.getAttacker() instanceof ServerPlayerEntity p) return p;
		return dead.getPrimeAdversary() instanceof ServerPlayerEntity p ? p : null;
	}

	private static void pay(ServerPlayerEntity p, String key, int def) {
		if (p == null) return;
		int amount = (int) ServerTune.get("boss.reward." + key, def);
		if (amount <= 0) return;
		var tag = p.getPersistentData();
		var persisted = tag.getCompound("PlayerPersisted");
		int total = persisted.getInt("ultracraft.P") + amount;
		persisted.putInt("ultracraft.P", total);
		tag.put("PlayerPersisted", persisted);
		Net.toPlayer(p, new PointsSyncPayload(total));
		p.sendMessage(Text.literal("+" + String.format("%,d", amount) + " P"), true);
		UltraCraft.LOG.info("[points] boss {} killed by {}: +{} P (total {})", key, p.getName().getString(), amount, total);
	}
}
