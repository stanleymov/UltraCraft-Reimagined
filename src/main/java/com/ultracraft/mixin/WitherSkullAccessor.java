package com.ultracraft.mixin;

import net.minecraft.entity.boss.WitherEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Wither's side heads: each shoots when the Wither's age reaches its cooldown. */
@Mixin(WitherEntity.class)
public interface WitherSkullAccessor {
	@Accessor("skullCooldowns")
	int[] ultracraft$skullCooldowns();
}
