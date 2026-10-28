package com.ultracraft.mixin;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A mob's goal selector, to hold its moving goals while it is on the whiplash. */
@Mixin(MobEntity.class)
public interface MobGoalsAccessor {
	@Accessor("goalSelector")
	GoalSelector ultracraft$goals();
}
