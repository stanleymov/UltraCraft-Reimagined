package com.ultracraft.block;

import net.minecraft.block.Block;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.item.BlockItem;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/** The grapple orb's item: drawn as the orb itself (models/item/grapple_orb.json is builtin/entity). */
public final class GrappleOrbItem extends BlockItem {
	public GrappleOrbItem(Block block, Settings settings) {
		super(block, settings);
	}

	@Override
	public void initializeClient(Consumer<IClientItemExtensions> consumer) {
		consumer.accept(new IClientItemExtensions() {
			@Override
			public BuiltinModelItemRenderer getCustomRenderer() {
				return com.ultracraft.client.weapon.GrappleOrbItemRenderer.get();
			}
		});
	}
}
