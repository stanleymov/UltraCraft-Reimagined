package com.ultracraft.mixin;

import com.ultracraft.weapon.ServerTune;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No hunger (user, 2026-10-28; V1 has none in ULTRAKILL): the bar stays full and Minecraft's hunger tick never runs, so it
 * neither drains, starves nor heals - V1 heals from blood and from eating (WeaponServer: food heals directly).
 * food.vanillaHunger 1 brings Minecraft's back.
 */
@Mixin(HungerManager.class)
abstract class HungerManagerMixin {
	@Shadow private int foodLevel;
	@Shadow private float saturationLevel;
	@Shadow private float exhaustion;

	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noHunger(PlayerEntity player, CallbackInfo ci) {
		if (ServerTune.get("food.vanillaHunger", 0) != 0) return;
		foodLevel = 20;
		saturationLevel = 5;
		exhaustion = 0;
		ci.cancel();
	}
}
