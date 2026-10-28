package com.ultracraft.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** The Terminal's lower half: on the client it holds the screen's state (which windows are open) and where it was drawn. */
public final class TerminalBlockEntity extends BlockEntity {
	/** Client: the screen (com.ultracraft.client.shop.ShopScreen.State), made on first draw. */
	public Object screen;
	/** Client: nanoTime the drawer last opened (a purchase). */
	public long drawerNanos;

	/** Client: the loaded terminals (the shop zone is checked for each). */
	public static final java.util.Set<TerminalBlockEntity> CLIENT = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	public TerminalBlockEntity(BlockPos pos, BlockState state) {
		super(UKBlocks.TERMINAL_ENTITY.get(), pos, state);
	}

	@Override
	public void setWorld(World world) {
		super.setWorld(world);
		if (world.isClient) CLIENT.add(this);
	}

	/** Frustum culling: the whole model (about 1.8 x 3 blocks), not this block, or it vanishes looking up past it. */
	@Override
	public net.minecraft.util.math.Box getRenderBoundingBox() {
		return new net.minecraft.util.math.Box(pos).expand(1, 0, 1).stretch(0, 2, 0);
	}

	@Override
	public void markRemoved() {
		super.markRemoved();
		CLIENT.remove(this);
	}
}
