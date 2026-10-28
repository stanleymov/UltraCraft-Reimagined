package com.ultracraft.client.mixin;

import com.ultracraft.client.v1.V1Renderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.LivingEntity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the inventory's player preview, so V1Renderer draws it standing (its own Idle rig) instead of mid-move. */
@Mixin(InventoryScreen.class)
abstract class InventoryEntityMixin {
	@Inject(method = "drawEntity(Lnet/minecraft/client/gui/DrawContext;IIILorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/entity/LivingEntity;)V", at = @At("HEAD"))
	private static void ultracraft$guiStart(DrawContext context, int x, int y, int size, Quaternionf q, Quaternionf q2, LivingEntity entity, CallbackInfo ci) {
		V1Renderer.inGui = true;
	}

	@Inject(method = "drawEntity(Lnet/minecraft/client/gui/DrawContext;IIILorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/entity/LivingEntity;)V", at = @At("RETURN"))
	private static void ultracraft$guiEnd(DrawContext context, int x, int y, int size, Quaternionf q, Quaternionf q2, LivingEntity entity, CallbackInfo ci) {
		V1Renderer.inGui = false;
	}
}
