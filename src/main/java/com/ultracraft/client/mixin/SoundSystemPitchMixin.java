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
		// dying (UkDeathScreen): TimeController.SetAllPitch 1 → 0 over the sequence slows every game sound but its own
		float slow = com.ultracraft.client.hud.UkDeathScreen.worldPitch();
		if (slow < 1 && !com.ultracraft.client.hud.UkDeathScreen.ownSound(sound)) cir.setReturnValue(Math.max(0.01f, cir.getReturnValue() * slow));
	}
}
