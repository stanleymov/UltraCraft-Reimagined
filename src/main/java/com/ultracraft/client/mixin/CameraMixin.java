package com.ultracraft.client.mixin;

import com.ultracraft.client.v1.V1;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CameraController feel: the camera drops while sliding, tilts with strafing and shakes on impacts. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow @Final private Quaternionf rotation;

	@Shadow protected abstract void moveBy(double forward, double up, double right);

	@Shadow protected abstract void setPos(net.minecraft.util.math.Vec3d pos);

	@Inject(method = "update", at = @At("TAIL"))
	private void ultracraft$feel(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
		V1 s = V1.INSTANCE;
		if (thirdPerson) return;
		// dying: the camera rides in the falling body's head (UkDeathScreen)
		net.minecraft.util.math.Vec3d dead = com.ultracraft.client.hud.UkDeathScreen.cameraPos();
		if (dead != null) {
			this.setPos(dead);
			return;
		}
		double drop = s.camDrop;
		double shake = Math.max(0, s.shake);
		double sx = 0, sy = 0;
		if (shake > 0) {
			double amt = shake > 1 ? 1 : shake;
			sx = (Math.random() * 2 - 1) * amt * 0.1;
			sy = (Math.random() * 2 - 1) * amt * 0.1;
		}
		if (drop != 0 || sx != 0 || sy != 0) this.moveBy(0, -drop + sy, -sx);
		// (the shake's move this frame: things drawn over the world at a point in it, the muzzle flash, follow it)
		s.shakeRight = -sx;
		s.shakeUp = sy;
		if (Math.abs(s.tiltZ) > 0.001) this.rotation.rotateZ((float) Math.toRadians(-s.tiltZ));
	}
}
