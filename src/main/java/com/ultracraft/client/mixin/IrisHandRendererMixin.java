package com.ultracraft.client.mixin;

import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Oculus (optional): its see-through hand pass, drawn after the water, only runs for a see-through held item. With the
 * Rocket Launcher out under a shader pack it runs too, for the launcher's clock (HandRenderer.clockPass).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
abstract class IrisHandRendererMixin {
	@Inject(method = "isHandTranslucent", at = @At("HEAD"), cancellable = true, remap = false)
	private void ultracraft$rocketClock(Hand hand, CallbackInfoReturnable<Boolean> cir) {
		if (hand == Hand.MAIN_HAND && com.ultracraft.client.weapon.HandRenderer.clockPass()) cir.setReturnValue(true);
	}
}
