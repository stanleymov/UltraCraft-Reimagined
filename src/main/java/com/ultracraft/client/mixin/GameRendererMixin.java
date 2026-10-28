package com.ultracraft.client.mixin;

import com.ultracraft.client.v1.V1;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CameraController's FOV changes: dashing forward -5 %, backward +10 %; weapon kicks (Revolver.Shoot). */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void ultracraft$fov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
		if (!changingFov) return;
		V1 s = V1.INSTANCE;
		double fov = cir.getReturnValue();
		if (s.boost && !s.sliding) {
			if (s.dodgeDirectionKind == 0) fov -= fov / 20;
			else if (s.dodgeDirectionKind == 1) fov += fov / 10;
		}
		fov += fov * s.fovKick;
		s.zoomBase = fov;
		fov *= s.zoomWorldMul;
		s.worldFov = fov;
		cir.setReturnValue(fov);
	}

	/**
	 * Minecraft's damage tilt is not put on the arms and guns (ULTRAKILL's HUD camera has none): turned about the eye it
	 * swung the guns across the screen and back on every hit, and the parry freeze held them there.
	 */
	@Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;tiltViewWhenHurt(Lnet/minecraft/client/util/math/MatrixStack;F)V"))
	private void ultracraft$noHandHurtTilt(GameRenderer self, net.minecraft.client.util.math.MatrixStack matrices, float tickDelta) {
	}
}
