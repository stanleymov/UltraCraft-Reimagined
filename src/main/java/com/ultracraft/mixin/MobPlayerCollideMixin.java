package com.ultracraft.mixin;

import com.ultracraft.weapon.ServerTune;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * V1 and mobs are solid to each other while the whiplash is out (ULTRAKILL's enemies run into V1's body; Minecraft mobs and players walk through
 * each other): either one moving counts the other's box as a wall, like a boat or a shulker. Live v1.solid (0 =
 * Minecraft's).
 */
@Mixin(Entity.class)
abstract class MobPlayerCollideMixin {
	@Inject(method = "collidesWith", at = @At("HEAD"), cancellable = true)
	private void ultracraft$solidV1(Entity other, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (ServerTune.get("v1.solid", 1) <= 0 || self.hasPassenger(other) || other.hasPassenger(self)) return;
		// only while the whiplash is out (the user's call: walking into a crowd pinned V1 and shook the guns)
		if (!com.ultracraft.weapon.WeaponServer.whiplashOut) return;
		// a mob moving runs into a player, and a player moving runs into a mob
		boolean mobIntoPlayer = self instanceof LivingEntity && !(self instanceof PlayerEntity) && other instanceof PlayerEntity p && !p.isSpectator()
			&& !com.ultracraft.weapon.WeaponServer.hooked(self);
		boolean playerIntoMob = self instanceof PlayerEntity p2 && !p2.isSpectator() && other instanceof LivingEntity && !(other instanceof PlayerEntity) && other.isAlive()
			&& !(self.getWorld().isClient && other.getId() == com.ultracraft.weapon.WeaponServer.clientPullingId)
			&& !(self.getWorld().isClient && com.ultracraft.weapon.WeaponServer.crushed(other));
		if (mobIntoPlayer || playerIntoMob) cir.setReturnValue(true);
	}
}
