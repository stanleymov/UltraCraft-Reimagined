package com.ultracraft.client.weapon;

import com.ultracraft.client.UKKeys;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Right click on a block that does something (a chest, a door, a button, a lever ...) with a gun in hand: Minecraft's
 * block use, not the gun's alternate fire. Minecraft's own order (ClientPlayerInteractionManager.interactBlock: the
 * block's onUse first, the held item only when the block passes); a sneak skips the block, like vanilla, so the gun
 * fires. Runs before the guns read the keys (the vanilla use is cancelled for ULTRAKILL weapons).
 */
public final class WorldUse {
	private WorldUse() {}

	/** Client tick start, after the keys are polled and before the weapons read them. */
	public static void tick(MinecraftClient c) {
		ClientPlayerEntity p = c.player;
		if (p == null || c.interactionManager == null || UKKeys.USE == null || !UKKeys.USE.press) return;
		if (c.currentScreen != null || !c.options.getPerspective().isFirstPerson() || com.ultracraft.client.shop.ShopMode.active()) return;
		// only where the gun would take the click: an ULTRAKILL weapon in hand, or an empty hand with the off-hand gun
		boolean main = WeaponClient.holdingUkWeapon(p);
		if (!main && !(p.getMainHandStack().isEmpty() && OffhandGun.active(p))) return;
		if (!(c.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		if (c.interactionManager.isBreakingBlock()) return;
		ActionResult r = c.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
		if (r.isAccepted()) UKKeys.USE.consume();
	}
}
