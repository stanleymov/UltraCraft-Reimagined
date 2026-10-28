package com.ultracraft.client.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The block light entities (and the held gun, lit from the player) are drawn with: raised by the moving lights (DynamicLights). */
@Mixin(EntityRenderer.class)
abstract class EntityRendererLightMixin {
	@Inject(method = "getBlockLight", at = @At("RETURN"), cancellable = true)
	private void ultracraft$dynamicLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		int dyn = com.ultracraft.client.render.DynamicLights.blockLight(pos.getX(), pos.getY(), pos.getZ());
		if (dyn > cir.getReturnValueI()) cir.setReturnValue(dyn);
	}
}
