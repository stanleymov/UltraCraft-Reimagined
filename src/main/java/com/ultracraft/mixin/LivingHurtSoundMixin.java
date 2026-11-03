package com.ultracraft.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One hurt sound per 10 ticks, Minecraft's own rate: its i-frames let a hurt sound through only after 10 of their 20 ticks,
 * and Damage.deal clears them for every ULTRAKILL hit. Without this every pellet played the mob's hurt sound: on the Ender
 * Dragon (getSoundVolume 5) a shotgun blast's 12..24 full-volume copies peaked at 4 x full scale and cut the sound out.
 */
@Mixin(LivingEntity.class)
abstract class LivingHurtSoundMixin {
	@Unique
	private int ultracraft$lastHurtSound = -100;

	@Inject(method = "playHurtSound", at = @At("HEAD"), cancellable = true)
	private void ultracraft$hurtSoundRate(DamageSource source, CallbackInfo ci) {
		int age = ((Entity) (Object) this).age;
		if (age >= ultracraft$lastHurtSound && age - ultracraft$lastHurtSound < 10) {
			ci.cancel();
			return;
		}
		ultracraft$lastHurtSound = age;
	}
}
