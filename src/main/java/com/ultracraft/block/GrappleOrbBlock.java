package com.ultracraft.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * ULTRAKILL's grapple point (HookPoint, type Normal): a floating green orb the Whiplash hooks; V1 is pulled to it
 * and keeps its momentum when it arrives. No collision (the game's orb is a trigger), so it can hang in the air.
 */
public final class GrappleOrbBlock extends BlockWithEntity {
	private static final VoxelShape SHAPE = Block.createCuboidShape(3, 3, 3, 13, 13, 13);

	public GrappleOrbBlock() {
		super(Settings.create().mapColor(MapColor.LIME).strength(1.5f).sounds(BlockSoundGroup.AMETHYST_BLOCK).nonOpaque().luminance(s -> 7).noCollision());
	}

	@Override
	public BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.INVISIBLE; // the block entity renderer draws the orb
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return VoxelShapes.empty();
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new GrappleOrbBlockEntity(pos, state);
	}
}
