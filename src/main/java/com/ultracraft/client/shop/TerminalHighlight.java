package com.ultracraft.client.shop;

import com.ultracraft.block.TerminalBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraftforge.client.event.RenderHighlightEvent;

/** Aiming at a Terminal: no Minecraft block outline at all (the user's wish); it can still be aimed at and mined. */
public final class TerminalHighlight {
	private TerminalHighlight() {}

	public static void onHighlight(RenderHighlightEvent.Block e) {
		var world = MinecraftClient.getInstance().world;
		if (world != null && world.getBlockState(e.getTarget().getBlockPos()).getBlock() instanceof TerminalBlock) e.setCanceled(true);
	}
}
