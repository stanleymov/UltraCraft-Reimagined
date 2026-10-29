package com.ultracraft.mixin;

import net.minecraft.entity.mob.CreeperEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The creeper's fuse (CreeperEntity.tick): it counts up by the fuse speed and blows at fuseTime (30 ticks). */
@Mixin(CreeperEntity.class)
public interface CreeperFuseAccessor {
	@Accessor("lastFuseTime")
	int ultracraft$lastFuseTime();

	@Accessor("currentFuseTime")
	int ultracraft$currentFuseTime();

	@Accessor("currentFuseTime")
	void ultracraft$setCurrentFuseTime(int t);

	@Accessor("fuseTime")
	int ultracraft$fuseTime();
}
