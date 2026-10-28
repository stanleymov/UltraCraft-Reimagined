package com.ultracraft.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * The Terminal (Shop.prefab's ShopTerminal): ULTRAKILL's shop, about 2.6 blocks tall at V1's scale - a two-high block
 * like a door, facing the player who placed it. The lower half holds the block entity that draws the terminal and
 * its screen; both halves go together. It needs an iron pickaxe or better to drop (tags needs_iron_tool and
 * mineable/pickaxe); breaking particles are the game's Basic Color - Yellow (atlases/blocks.json).
 */
public final class TerminalBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;

	public TerminalBlock() {
		super(Settings.create().mapColor(MapColor.IRON_GRAY).strength(3f, 6f).requiresTool().sounds(BlockSoundGroup.METAL).nonOpaque().luminance(s -> 4));
		setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, HALF);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		BlockPos pos = ctx.getBlockPos();
		World world = ctx.getWorld();
		if (pos.getY() >= world.getTopY() - 1 || !world.getBlockState(pos.up()).canReplace(ctx)) return null;
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()).with(HALF, DoubleBlockHalf.LOWER);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
	}

	@Override
	public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		DoubleBlockHalf half = state.get(HALF);
		// the other half gone: this one goes too
		if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)
			&& !(neighbor.isOf(this) && neighbor.get(HALF) != half))
			return Blocks.AIR.getDefaultState();
		return super.getStateForNeighborUpdate(state, direction, neighbor, world, pos, neighborPos);
	}

	@Override
	public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		if (state.get(HALF) == DoubleBlockHalf.LOWER) return super.canPlaceAt(state, world, pos);
		BlockState below = world.getBlockState(pos.down());
		return below.isOf(this) && below.get(HALF) == DoubleBlockHalf.LOWER;
	}

	@Override
	public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		// breaking the upper half in creative: the lower one goes without a drop (as a door does)
		if (!world.isClient && player.isCreative() && state.get(HALF) == DoubleBlockHalf.UPPER) {
			BlockPos below = pos.down();
			BlockState b = world.getBlockState(below);
			if (b.isOf(this) && b.get(HALF) == DoubleBlockHalf.LOWER) world.setBlockState(below, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
		}
		super.onBreak(world, pos, state, player);
	}

	@Override
	public BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.INVISIBLE; // TerminalRenderer draws the model and the screen
	}

	/**
	 * The model's footprint at the default shop.scale (its bounds: 3.47 x 5.78 x 1.90 ULTRAKILL units = 1.78 x 2.97 x
	 * 0.97 blocks), centred on the block: wider than a block, so the shapes reach 0.39 into the neighbours on each side
	 * (Minecraft finds collision shapes up to a block outside their own, as with a fence's 1.5 height).
	 */
	public static final double HALF_WIDTH = 0.89, HALF_DEPTH = 0.49, HEIGHT = 2.97;

	/** The footprint from `y0` to `y1` (block units, from this block's floor), turned with the facing. */
	public static VoxelShape footprint(BlockState state, double y0, double y1) {
		boolean alongX = state.get(FACING).getAxis() == Direction.Axis.Z;
		double w = alongX ? HALF_WIDTH : HALF_DEPTH, d = alongX ? HALF_DEPTH : HALF_WIDTH;
		return VoxelShapes.cuboid(0.5 - w, y0, 0.5 - d, 0.5 + w, y1, 0.5 + d);
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return state.get(HALF) == DoubleBlockHalf.LOWER ? footprint(state, 0, 1) : footprint(state, 0, HEIGHT - 1);
	}

	/** Aiming at it: the whole terminal (no outline is drawn, TerminalHighlight). */
	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return getCollisionShape(state, world, pos, context);
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return state.get(HALF) == DoubleBlockHalf.LOWER ? new TerminalBlockEntity(pos, state) : null;
	}
}
