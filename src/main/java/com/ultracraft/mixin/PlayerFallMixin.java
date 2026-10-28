package com.ultracraft.mixin;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** V1 takes no fall damage (ULTRAKILL has none); ground slams are a weapon. */
@Mixin(PlayerEntity.class)
abstract class PlayerFallMixin {
	@Inject(method = "handleFallDamage", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}
}
