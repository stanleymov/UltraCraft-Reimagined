package com.ultracraft.mixin;

import com.ultracraft.weapon.ServerTune;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * V1's slide and crouch at crawl height (user; NewMovement shrinks the collider from 3.5 to 1.25 u while sliding or
 * crouched, about 0.64 blocks at Steve's size): with the sneak key held V1 takes Minecraft's crawl pose (0.6 blocks), on
 * both sides so the server's movement check agrees, and slides through a one-block gap. The user's call: V1 sliding
 * is half a block tall (move.crawlHeight) and the eye, so the camera, sits inside it (move.crawlEye) - a taller eye
 * darkened the view and clipped the camera under a gap. move.slideCrawl 0 = off.
 */
@Mixin(PlayerEntity.class)
abstract class PlayerCrawlMixin {
	@Inject(method = "updatePose", at = @At("TAIL"))
	private void ultracraft$slideCrawl(CallbackInfo ci) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (ServerTune.get("move.slideCrawl", 1) == 0) return;
		if (self.isSneaking() && !self.getAbilities().flying && !self.isFallFlying() && !self.isSleeping() && !self.hasVehicle()
			&& !self.isSwimming() && !self.isSpectator()) self.setPose(EntityPose.SWIMMING);
	}

	@Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
	private void ultracraft$crawlBox(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (pose == EntityPose.SWIMMING && !self.isTouchingWater() && ServerTune.get("move.slideCrawl", 1) != 0)
			cir.setReturnValue(EntityDimensions.changing(0.6F, (float) ServerTune.get("move.crawlHeight", 0.5)));
	}

	@Inject(method = "getActiveEyeHeight", at = @At("HEAD"), cancellable = true)
	private void ultracraft$crawlEye(EntityPose pose, EntityDimensions dimensions, CallbackInfoReturnable<Float> cir) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (pose == EntityPose.SWIMMING && !self.isTouchingWater() && ServerTune.get("move.slideCrawl", 1) != 0) cir.setReturnValue((float) ServerTune.get("move.crawlEye", 0.42));
	}
}
