package com.ultracraft.client.mixin;

import com.ultracraft.client.weapon.WhiplashClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The light enemy V1 is pulling is moved on this side; the server's (older) positions for it are set aside meanwhile. */
@Mixin(LivingEntity.class)
abstract class HookedPositionMixin {
	@Inject(method = "updateTrackedPositionAndAngles", at = @At("HEAD"), cancellable = true)
	private void ultracraft$ownPull(double x, double y, double z, float yaw, float pitch, int steps, boolean interpolate, CallbackInfo ci) {
		if (((LivingEntity) (Object) this).getId() == WhiplashClient.INSTANCE.ownPulled()) ci.cancel();
	}
}
