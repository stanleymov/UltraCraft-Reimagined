package com.ultracraft.client.mixin;

import com.ultracraft.client.v1.UnderwaterAudio;
import net.minecraft.client.sound.Source;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every OpenAL source is known to UnderwaterAudio, which muffles them all while the camera is under water. */
@Mixin(Source.class)
abstract class SourceMixin {
	@Shadow @Final private int pointer;

	@Inject(method = "play", at = @At("HEAD"))
	private void ultracraft$play(CallbackInfo ci) {
		UnderwaterAudio.started(pointer);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void ultracraft$close(CallbackInfo ci) {
		UnderwaterAudio.closed(pointer);
	}
}
