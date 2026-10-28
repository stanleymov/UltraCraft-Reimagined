package com.ultracraft.client.mixin;

import com.ultracraft.client.weapon.GasolineClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob carrying Firestarter gasoline is drawn tinted purple (EnemyIdentifier's _OiledAmount). */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	private static final String RENDER = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V";
	private static final String MODEL = "Lnet/minecraft/client/render/entity/model/EntityModel;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V";

	@Inject(method = RENDER, at = @At("HEAD"))
	private void ultracraft$begin(LivingEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light, CallbackInfo ci) {
		GasolineClient.drawing.set(e);
	}

	@Inject(method = RENDER, at = @At("RETURN"))
	private void ultracraft$end(LivingEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light, CallbackInfo ci) {
		GasolineClient.drawing.remove();
	}

	@ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = MODEL), index = 4)
	private float ultracraft$red(float r) {
		return r * GasolineClient.oilTint(0);
	}

	@ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = MODEL), index = 5)
	private float ultracraft$green(float g) {
		return g * GasolineClient.oilTint(1);
	}

	@ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = MODEL), index = 6)
	private float ultracraft$blue(float b) {
		return b * GasolineClient.oilTint(2);
	}
}
