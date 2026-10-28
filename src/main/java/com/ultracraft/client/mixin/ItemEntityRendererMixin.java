package com.ultracraft.client.mixin;

import com.ultracraft.client.weapon.DroppedWeaponRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dropped UltraCraft weapon is drawn as its 3D weapon model instead of the flat item icon. */
@Mixin(ItemEntityRenderer.class)
abstract class ItemEntityRendererMixin {
	@Inject(method = "render(Lnet/minecraft/entity/ItemEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", at = @At("HEAD"), cancellable = true)
	private void ultracraft$weaponModel(ItemEntity item, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light, CallbackInfo ci) {
		if (DroppedWeaponRenderer.render(item, tickDelta, ms, buffers, light)) ci.cancel();
	}
}
