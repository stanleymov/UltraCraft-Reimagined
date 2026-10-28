package com.ultracraft.client.mixin;

import com.ultracraft.UkConfig;
import com.ultracraft.client.v1.V1;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * CameraController.LateUpdate while zooming: the look input is scaled by fov / defaultFov, so aim slows down with the
 * zoom (half at the railcannon's full zoom). Here the share at full zoom is the player's "Zoomed aim sensitivity".
 */
@Mixin(Mouse.class)
abstract class MouseZoomMixin {
	@Redirect(method = "updateMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
	private void ultracraft$zoomSensitivity(ClientPlayerEntity player, double dx, double dy) {
		V1 v = V1.INSTANCE;
		double k = 1;
		if (v.zoomHeld) {
			// how far into the zoom (world FOV x1 -> x0.5), mapped onto 1 -> the chosen share
			double into = Math.max(0, Math.min(1, (1 - v.zoomWorldMul) / 0.5));
			k = 1 - into * (1 - UkConfig.zoomSensitivity());
		}
		player.changeLookDirection(dx * k, dy * k);
	}
}
