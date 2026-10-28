package com.ultracraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The ghast's fireball count-up: it shoots at 20. */
@Mixin(targets = "net.minecraft.entity.mob.GhastEntity$ShootFireballGoal")
public interface GhastShootAccessor {
	@Accessor("cooldown")
	int ultracraft$cooldown();
}
