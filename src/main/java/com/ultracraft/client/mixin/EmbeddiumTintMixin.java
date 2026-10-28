package com.ultracraft.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Embeddium (optional): a quad's vertex colours (BlockRenderer.getVertexColors, multiplied into the block's colour by
 * writeGeometry - and, under a shader pack, into its albedo) tinted toward the moving lights' colour where they light the
 * block (DynamicLights.tint): Minecraft's block light itself has one colour for every light.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
abstract class EmbeddiumTintMixin {
	@Inject(method = "getVertexColors", at = @At("RETURN"), remap = false)
	private void ultracraft$tint(@Coerce Object ctx, @Coerce Object colorProvider, @Coerce Object quad, CallbackInfoReturnable<int[]> cir) {
		com.ultracraft.client.render.DynamicLights.tintQuad(ctx, cir.getReturnValue());
	}
}
