package com.ultracraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The shulker's bullet countdown: it shoots at 0. */
@Mixin(targets = "net.minecraft.entity.mob.ShulkerEntity$ShootBulletGoal")
public interface ShulkerShootAccessor {
	@Accessor("counter")
	int ultracraft$counter();
}
