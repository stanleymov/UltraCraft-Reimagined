package com.ultracraft.client.mixin;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
	@Accessor("boundKey")
	InputUtil.Key ultracraft$getBoundKey();

	@Accessor("timesPressed")
	int ultracraft$getTimesPressed();

	@Accessor("timesPressed")
	void ultracraft$setTimesPressed(int n);
}
