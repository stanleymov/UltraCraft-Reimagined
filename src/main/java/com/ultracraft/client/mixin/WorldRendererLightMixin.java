package com.ultracraft.client.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The light vanilla's chunk meshes (and anything else asking for a block's lightmap) bake in: raised by the moving lights (DynamicLights). */
@Mixin(WorldRenderer.class)
abstract class WorldRendererLightMixin {
	@Inject(method = "getLightmapCoordinates(Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;)I", at = @At("RETURN"), cancellable = true)
	private static void ultracraft$dynamicLight(BlockRenderView world, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		int v = cir.getReturnValueI();
		int r = com.ultracraft.client.render.DynamicLights.raise(v, pos.getX(), pos.getY(), pos.getZ());
		if (r != v) cir.setReturnValue(r);
	}
}
