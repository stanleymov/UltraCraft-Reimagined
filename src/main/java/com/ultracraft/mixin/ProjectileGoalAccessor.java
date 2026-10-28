package com.ultracraft.mixin;

import net.minecraft.entity.ai.goal.ProjectileAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The ranged attack countdown (drowned tridents, witches, the Wither's main head, llamas, snow golems): it shoots at 0. */
@Mixin(ProjectileAttackGoal.class)
public interface ProjectileGoalAccessor {
	@Accessor("updateCountdownTicks")
	int ultracraft$countdown();
}
