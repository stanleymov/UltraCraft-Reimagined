package com.ultracraft.mixin;

import com.ultracraft.weapon.ServerTune;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No sneak edge-guard: V1's slide and crouch hold the sneak key, and Minecraft's clipAtLedge stopped every slide at a
 * block's edge (ULTRAKILL's NewMovement has no ledge stop). Off on both sides, or the server's movement check would pull
 * V1 back onto the ledge. move.ledgeGuard 1 brings it back.
 */
@Mixin(PlayerEntity.class)
abstract class PlayerLedgeMixin {
	@Inject(method = "clipAtLedge", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noLedgeGuard(CallbackInfoReturnable<Boolean> cir) {
		if (ServerTune.get("move.ledgeGuard", 0) == 0) cir.setReturnValue(false);
	}
}
