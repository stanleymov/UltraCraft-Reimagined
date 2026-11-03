package com.ultracraft.client.mixin;

import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTOutputLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs whether OpenAL's output limiter is on for this device (read only), for reading a player's sound report. */
@Mixin(SoundEngine.class)
abstract class SoundEngineInfoMixin {
	@Shadow
	private long devicePointer;

	@Inject(method = "init(Ljava/lang/String;Z)V", at = @At("TAIL"))
	private void ultracraft$logLimiter(String deviceSpecifier, boolean directionalAudio, CallbackInfo ci) {
		if (devicePointer == 0 || !ALC10.alcIsExtensionPresent(devicePointer, "ALC_SOFT_output_limiter")) return;
		com.ultracraft.UltraCraft.LOG.info("[sound] OpenAL output limiter: {}", ALC10.alcGetInteger(devicePointer, SOFTOutputLimiter.ALC_OUTPUT_LIMITER_SOFT) == ALC10.ALC_FALSE ? "off" : "on");
	}
}
