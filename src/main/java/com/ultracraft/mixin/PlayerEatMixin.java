package com.ultracraft.mixin;

import com.ultracraft.weapon.ServerTune;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No hunger: food is eaten when V1 is hurt (it heals), not when the always-full bar says hungry; always-edible food any time. */
@Mixin(PlayerEntity.class)
abstract class PlayerEatMixin {
	@Inject(method = "canConsume", at = @At("HEAD"), cancellable = true)
	private void ultracraft$eatWhenHurt(boolean ignoreHunger, CallbackInfoReturnable<Boolean> cir) {
		if (ServerTune.get("food.vanillaHunger", 0) != 0) return;
		PlayerEntity self = (PlayerEntity) (Object) this;
		cir.setReturnValue(ignoreHunger || self.getAbilities().invulnerable || self.getHealth() < self.getMaxHealth());
	}
}
