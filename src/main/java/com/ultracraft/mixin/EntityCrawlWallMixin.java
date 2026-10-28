package com.ultracraft.mixin;

import com.ultracraft.weapon.ServerTune;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * V1 crawling (PlayerCrawlMixin) keeps its eye at crouch height, above the crawl box: Minecraft's suffocation check
 * (isInsideWall, at the eye) hurt it sliding under a one-block gap. For a crawling player the check sits inside its box.
 */
@Mixin(Entity.class)
abstract class EntityCrawlWallMixin {
	@Redirect(method = "isInsideWall", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getEyePos()Lnet/minecraft/util/math/Vec3d;"))
	private Vec3d ultracraft$crawlWallPoint(Entity self) {
		if (self instanceof PlayerEntity p && p.getPose() == EntityPose.SWIMMING && !p.isTouchingWater() && ServerTune.get("move.slideCrawl", 1) != 0)
			return p.getPos().add(0, 0.3, 0);
		return self.getEyePos();
	}
}
