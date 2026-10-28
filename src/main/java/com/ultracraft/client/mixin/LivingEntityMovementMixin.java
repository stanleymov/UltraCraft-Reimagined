package com.ultracraft.client.mixin;

import com.ultracraft.client.v1.V1Movement;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The local player moves with ULTRAKILL's physics instead of Minecraft's. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMovementMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void ultracraft$travel(Vec3d movementInput, CallbackInfo ci) {
		if ((Object) this instanceof ClientPlayerEntity p && p.isMainPlayer()) {
			if (V1Movement.active(p)) {
				V1Movement.travel(p);
				ci.cancel();
			} else V1Movement.inactiveTick();
		}
	}

	/** Space is V1's jump (handled in V1Movement); vanilla's jump must not fire on top of it. */
	@Inject(method = "jump", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noVanillaJump(CallbackInfo ci) {
		if ((Object) this instanceof ClientPlayerEntity p && p.isMainPlayer() && V1Movement.active(p)) ci.cancel();
	}
}
