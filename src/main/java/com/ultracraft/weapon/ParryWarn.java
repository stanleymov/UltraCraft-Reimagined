package com.ultracraft.weapon;

import com.ultracraft.mixin.BlazeShootAccessor;
import com.ultracraft.mixin.CreeperFuseAccessor;
import com.ultracraft.mixin.CrossbowGoalAccessor;
import com.ultracraft.mixin.GhastShootAccessor;
import com.ultracraft.mixin.MobGoalsAccessor;
import com.ultracraft.mixin.ProjectileGoalAccessor;
import com.ultracraft.mixin.ShulkerShootAccessor;
import com.ultracraft.mixin.WitherSkullAccessor;
import com.ultracraft.net.Net;
import com.ultracraft.net.ParryWarnPayload;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BowItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * The parry warning (the user's ask; ULTRAKILL's parryable enemies flash DefaultReferenceManager.parryableFlash, the
 * V2Flash star, at their head before the attack): a moment before one of our parryable ranged attacks leaves the mob
 * (parry.warnLead ticks), V1's client is told to flash the star there. Each shot is read off the mob's own countdown:
 * a drawn bow (BowAttackGoal shoots at 20 ticks of use), a charged crossbow, the ranged-attack countdown, a ghast's
 * fireball, a blaze's burst, a shulker's bullet, the Wither's side heads, the Warden's sonic boom and a creeper's fuse
 * (the user's ask: a creeper about to blow is parried with a punch or a shooting parry, ParryServer.tryParryCreeper).
 */
public final class ParryWarn {
	private ParryWarn() {}

	/** Server tick (end). */
	public static void tick(MinecraftServer server) {
		int lead = (int) ServerTune.get("parry.warnLead", 5);
		if (ServerTune.get("parry.warn", 1) == 0 || lead < 1) return;
		for (ServerWorld world : server.getWorlds()) {
			for (ServerPlayerEntity p : world.getPlayers()) {
				if (p.isSpectator()) continue;
				for (MobEntity m : world.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(64), m -> m.isAlive() && m.getTarget() == p)) {
					if (aboutToShoot(m, lead)) {
						Net.toPlayer(p, new ParryWarnPayload(m.getId(), 0));
						if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[parrywarn] {} warns {}", m.getType().getUntranslatedName(), p.getName().getString());
					}
					if (m instanceof WitherEntity w && ServerTune.get("parry.warnWither", 0) != 0) {
						int[] cd = ((WitherSkullAccessor) w).ultracraft$skullCooldowns();
						for (int i = 0; i < 2; i++)
							if (cd[i] - w.age == lead && w.getTrackedEntityId(i + 1) == p.getId()) Net.toPlayer(p, new ParryWarnPayload(w.getId(), i + 1));
					}
				}
			}
		}
	}

	/** Ticks before a creeper blows that its star flashes (user: the shot lead was too tight for a punch; 0.5 s). */
	public static int creeperLead() {
		return (int) ServerTune.get("parry.creeperLead", 10);
	}

	private static boolean aboutToShoot(MobEntity m, int lead) {
		// (no warnings on the Wither: its heads fire all the time, the star never stopped; user)
		if (m instanceof WitherEntity) return false;
		// a hissing creeper (CreeperEntity.tick: it blows when its fuse reaches fuseTime): the star as the fuse passes
		// fuseTime - parry.creeperLead on its way up
		if (m instanceof CreeperEntity c) {
			CreeperFuseAccessor f = (CreeperFuseAccessor) c;
			int at = f.ultracraft$fuseTime() - creeperLead();
			return c.getFuseSpeed() > 0 && f.ultracraft$lastFuseTime() < at && f.ultracraft$currentFuseTime() >= at;
		}
		// the Warden's sonic boom (SonicBoomTask): it goes off when SONIC_BOOM_SOUND_DELAY (34 ticks) runs out
		if (m instanceof net.minecraft.entity.mob.WardenEntity w) {
			var brain = w.getBrain();
			var delay = net.minecraft.entity.ai.brain.MemoryModuleType.SONIC_BOOM_SOUND_DELAY;
			return brain.hasMemoryModule(delay) && brain.getMemoryExpiry(delay) == lead;
		}
		if (m.isUsingItem() && m.getActiveItem().getItem() instanceof BowItem && m.getItemUseTime() == 20 - lead) return true;
		for (var pg : ((MobGoalsAccessor) m).ultracraft$goals().getGoals()) {
			if (!pg.isRunning()) continue;
			Object g = pg.getGoal();
			if (g instanceof CrossbowGoalAccessor c && c.ultracraft$chargedTicksLeft() == lead) return true;
			if (g instanceof ProjectileGoalAccessor r && r.ultracraft$countdown() == lead) return true;
			if (g instanceof GhastShootAccessor gh && gh.ultracraft$cooldown() == 20 - lead) return true;
			if (g instanceof BlazeShootAccessor b && b.ultracraft$fired() == 1 && b.ultracraft$cooldown() == lead) return true;
			if (g instanceof ShulkerShootAccessor s && s.ultracraft$counter() == lead) return true;
		}
		return false;
	}
}
