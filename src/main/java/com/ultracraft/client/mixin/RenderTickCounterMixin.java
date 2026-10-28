package com.ultracraft.client.mixin;

import com.ultracraft.client.v1.V1;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TimeController.TrueStop (time scale 0) on the client: while the parry freeze runs no client ticks happen and the
 * partial tick stands still, so everything in the world holds its place; frames keep drawing (camera look, the
 * parry look on the arms) and time picks up where it stopped afterwards.
 */
@Mixin(RenderTickCounter.class)
abstract class RenderTickCounterMixin {
	@Shadow private long prevTimeMillis;
	@Shadow public float lastFrameDuration;

	@Inject(method = "beginRenderTick", at = @At("HEAD"), cancellable = true)
	private void ultracraft$parryFreeze(long timeMillis, CallbackInfoReturnable<Integer> cir) {
		if (MinecraftClient.getInstance().world == null || !V1.INSTANCE.frozen()) return;
		prevTimeMillis = timeMillis;
		lastFrameDuration = 0;
		cir.setReturnValue(0);
	}
}
