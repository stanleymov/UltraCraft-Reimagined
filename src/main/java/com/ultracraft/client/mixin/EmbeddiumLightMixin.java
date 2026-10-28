package com.ultracraft.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Embeddium (optional): its chunk meshes take a block's light from LightDataAccess.compute, which reads the light level
 * itself for most blocks (not vanilla's getLightmapCoordinates). Its packed word keeps the block light in the low 4 bits
 * and "opaque" in bit 29 (packBL / unpackOP): an opaque block keeps its 0, the rest are raised by DynamicLights.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess", remap = false)
abstract class EmbeddiumLightMixin {
	@Inject(method = "compute(III)I", at = @At("RETURN"), cancellable = true, remap = false)
	private void ultracraft$dynamicLight(int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
		int v = cir.getReturnValueI();
		if (((v >>> 29) & 1) != 0) return;
		int dyn = com.ultracraft.client.render.DynamicLights.blockLight(x, y, z);
		if (dyn > (v & 15)) cir.setReturnValue((v & ~15) | dyn);
	}
}
