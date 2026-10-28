package com.ultracraft.client.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft clamps every pitch to 0.5 .. 2; our music sources (UKSound.music) go lower, down to near 0. */
@Mixin(SoundSystem.class)
abstract class SoundSystemPitchMixin {
	@Inject(method = "getAdjustedPitch", at = @At("RETURN"), cancellable = true)
	private void ultracraft$lowPitch(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		if (com.ultracraft.client.UKSound.lowPitch(sound)) cir.setReturnValue(Math.max(0.01f, Math.min(2f, sound.getPitch())));
	}
}
