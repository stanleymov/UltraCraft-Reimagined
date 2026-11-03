package com.ultracraft.client.mixin;

import com.ultracraft.client.SoundBus;
import net.minecraft.client.sound.Channel;
import net.minecraft.client.sound.Source;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * The options' sound volume (UkConfig.soundScale) and SoundBus's gain on every sound of ours (the ultracraft namespace): as
 * it starts (SoundSystem.play) and on every tick. Minecraft's own sounds are only counted by the bus.
 */
@Mixin(SoundSystem.class)
abstract class SoundSystemVolumeMixin {
	@Shadow
	@Final
	private Map<SoundInstance, Channel.SourceManager> sources;

	@Shadow
	protected abstract float getAdjustedVolume(float volume, SoundCategory category);

	@Shadow
	protected abstract float getAdjustedVolume(SoundInstance sound);

	private static boolean ours(SoundInstance sound) {
		return sound != null && sound.getId() != null && com.ultracraft.UltraCraft.MOD_ID.equals(sound.getId().getNamespace());
	}

	@Redirect(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundSystem;getAdjustedVolume(FLnet/minecraft/sound/SoundCategory;)F"))
	private float ultracraft$startVolume(SoundSystem self, float volume, SoundCategory category, SoundInstance sound) {
		float v = getAdjustedVolume(volume, category);
		if (v <= 0) return v;
		if (!ours(sound)) {
			SoundBus.started(sound, v, false);
			com.ultracraft.client.SoundLog.started(sound, v);
			return v;
		}
		v = (float) (v * com.ultracraft.UkConfig.soundScale());
		// (0 makes play() skip it)
		if (!SoundBus.admit(sound, v, this::ultracraft$stop)) return 0f;
		v *= SoundBus.started(sound, v, true);
		if (SoundBus.droppedFar()) ultracraft$applyBus();
		com.ultracraft.client.SoundLog.started(sound, v);
		return v;
	}

	@Inject(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
	private void ultracraft$tickVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		if (!ours(sound)) return;
		float base = (float) (cir.getReturnValueF() * com.ultracraft.UkConfig.soundScale());
		cir.setReturnValue(base * SoundBus.playing(sound, base));
	}

	@Inject(method = "tick()V", at = @At("TAIL"))
	private void ultracraft$busTick(CallbackInfo ci) {
		SoundBus.tick(sources::containsKey);
		ultracraft$applyBus();
	}

	/** The bus's gain on our one-shots; ticking sounds get it from SoundSystem.tick's own getAdjustedVolume. */
	private void ultracraft$applyBus() {
		for (SoundInstance sound : SoundBus.ours()) {
			Channel.SourceManager source = sources.get(sound);
			if (source == null || sound instanceof TickableSoundInstance) continue;
			float v = getAdjustedVolume(sound);
			source.run(s -> s.setVolume(v));
		}
	}

	private void ultracraft$stop(SoundInstance sound) {
		Channel.SourceManager source = sources.get(sound);
		if (source != null) source.run(Source::stop);
	}
}
