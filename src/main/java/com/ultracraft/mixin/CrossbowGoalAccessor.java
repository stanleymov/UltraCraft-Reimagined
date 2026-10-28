package com.ultracraft.mixin;

import net.minecraft.entity.ai.goal.CrossbowAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The crossbow's charged countdown: it shoots when it runs out (the parry warning star, ParryWarn). */
@Mixin(CrossbowAttackGoal.class)
public interface CrossbowGoalAccessor {
	@Accessor("chargedTicksLeft")
	int ultracraft$chargedTicksLeft();
}
