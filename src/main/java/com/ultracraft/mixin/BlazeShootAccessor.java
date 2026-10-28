package com.ultracraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The blaze's burst: 3 s of charge (fireballsFired 1), then three fireballs 6 ticks apart. */
@Mixin(targets = "net.minecraft.entity.mob.BlazeEntity$ShootFireballGoal")
public interface BlazeShootAccessor {
	@Accessor("fireballsFired")
	int ultracraft$fired();

	@Accessor("fireballCooldown")
	int ultracraft$cooldown();
}
