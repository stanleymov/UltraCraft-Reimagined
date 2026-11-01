package com.ultracraft.client.mixin;

import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the whiplash holds a heavy enemy or a grapple point, pulls V1 to it, and until a slam stored on the way has
 * landed (WhiplashClient.blocksCrouch), the slide key only stores a slam: Minecraft does not see it as
 * sneaking, so V1 keeps its full height and camera (no crawl pose, here or on the server) and still reaches what it
 * hooked. The mod reads the key itself (UKKeys.SLIDE), so the slam and its shockwave on a held landing still work.
 */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void ultracraft$noCrouchWhilePulled(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
		if (com.ultracraft.client.weapon.WhiplashClient.INSTANCE.blocksCrouch()) ((KeyboardInput) (Object) this).sneaking = false;
	}
}
