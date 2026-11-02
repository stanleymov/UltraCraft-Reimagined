package com.ultracraft.client.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The options' sound volume (UkConfig.soundScale) on every sound of ours (the ultracraft namespace): as it starts
 * (SoundSystem.play) and on every tick of a looping or moving one. Minecraft's own sounds are left as they are.
 */
@Mixin(SoundSystem.class)
abstract class SoundSystemVolumeMixin {
	@Shadow
	protected abstract float getAdjustedVolume(float volume, SoundCategory category);

	private static boolean ours(SoundInstance sound) {
		return sound != null && sound.getId() != null && com.ultracraft.UltraCraft.MOD_ID.equals(sound.getId().getNamespace());
	}

	@Redirect(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundSystem;getAdjustedVolume(FLnet/minecraft/sound/SoundCategory;)F"))
	private float ultracraft$startVolume(SoundSystem self, float volume, SoundCategory category, SoundInstance sound) {
		float v = getAdjustedVolume(volume, category);
		if (!ours(sound)) return v;
		v = (float) (v * com.ultracraft.UkConfig.soundScale());
		com.ultracraft.client.SoundLog.started(sound, v);
		return v;
	}

	@Inject(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
	private void ultracraft$tickVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		if (ours(sound)) cir.setReturnValue((float) (cir.getReturnValueF() * com.ultracraft.UkConfig.soundScale()));
	}
}
