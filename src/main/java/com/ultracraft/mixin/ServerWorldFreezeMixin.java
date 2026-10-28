package com.ultracraft.mixin;

import com.ultracraft.weapon.ParryServer;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** The parry freeze on the server: the world (mobs, projectiles, blocks) does not tick while it runs. */
@Mixin(ServerWorld.class)
abstract class ServerWorldFreezeMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void ultracraft$parryFreeze(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		if (ParryServer.worldFrozen()) ci.cancel();
	}
}
