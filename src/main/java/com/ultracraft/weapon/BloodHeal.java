package com.ultracraft.weapon;

import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * ULTRAKILL heals V1 with the blood of enemies hit at close range (BloodsplatterManager → GetHealth).
 * V1's 100 hp is the player's 20 HP (x5); `amount` is in V1 hp. Hard damage caps this on the client's
 * side for now (the cap moves server side with the style system).
 */
public final class BloodHeal {
	private BloodHeal() {}

	public static void heal(ServerPlayerEntity p, int amountV1) {
		float before = p.getHealth();
		p.heal(amountV1 / 5f);
		if (p.getHealth() > before + 0.01f) {
			Net.toPlayer(p, new HitPayload(HitPayload.HEAL, HitPayload.PUNCH, amountV1, p.getPos()));
		}
	}
}
