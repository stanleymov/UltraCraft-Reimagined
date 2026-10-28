package com.ultracraft.client.mixin;

import com.ultracraft.client.v1.V1Movement;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While V1 physics drive the local player, ULTRAKILL's surface footsteps replace Minecraft's step sounds. */
@Mixin(Entity.class)
abstract class EntityStepSoundMixin {
	@Inject(method = "playStepSounds", at = @At("HEAD"), cancellable = true)
	private void ultracraft$v1Steps(BlockPos pos, BlockState state, CallbackInfo ci) {
		if ((Object) this instanceof ClientPlayerEntity && V1Movement.isActive()) ci.cancel();
	}
}
