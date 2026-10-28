package com.ultracraft.block;

import net.minecraft.block.Block;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.item.BlockItem;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/** The Terminal's item: drawn as the terminal itself (models/item/terminal.json is builtin/entity). */
public final class TerminalItem extends BlockItem {
	public TerminalItem(Block block, Settings settings) {
		super(block, settings);
	}

	@Override
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(new IClientItemExtensions() {
			@Override
			public BuiltinModelItemRenderer getCustomRenderer() {
				return com.ultracraft.client.shop.TerminalItemRenderer.get();
			}
		});
	}
}
