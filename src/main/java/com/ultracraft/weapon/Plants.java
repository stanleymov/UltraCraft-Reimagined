package com.ultracraft.weapon;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plants V1's shots go through and break (the user's list): leaves, grass and ferns, dead bushes, flowers, saplings,
 * crops, vines, glow berries, glow lichen, pointed dripstone, azaleas, sweet berry bushes, sugar cane, hanging roots,
 * spore blossoms, mushrooms, the Nether's roots, sprouts and fungi, dripleaves, cobwebs, bamboo and the underwater
 * plants. Every shot's block test uses ctx() - the plants are not there for it, so a shot goes on through leaves - and
 * breaks the plants on its way up to what it met (shoot); an explosion clears the plants in its radius (blast). A plant
 * goes as if V1 broke it by hand: its drops (none in creative), sound and particles, and not where V1 may not break
 * blocks (adventure mode, spawn protection, a Forge break event cancelled by a claim mod).
 */
public final class Plants {
	private Plants() {}

	private static final Set<Block> PLANTS = Set.of(
		Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH,
		Blocks.VINE, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
		Blocks.GLOW_LICHEN, Blocks.POINTED_DRIPSTONE, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.SWEET_BERRY_BUSH,
		Blocks.SUGAR_CANE, Blocks.HANGING_ROOTS, Blocks.SPORE_BLOSSOM, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM,
		Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.NETHER_SPROUTS, Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS,
		Blocks.SMALL_DRIPLEAF, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.COBWEB,
		Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT, Blocks.LILY_PAD,
		Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.NETHER_WART, Blocks.COCOA);

	public static boolean shootable(BlockState s) {
		if (s.isAir() || s.isOf(Blocks.CHORUS_FLOWER)) return false; // (in #flowers, but a solid block like the cactus)
		return PLANTS.contains(s.getBlock()) || s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS)
			|| s.isIn(BlockTags.CROPS) || s.isIn(BlockTags.CAVE_VINES);
	}

	/** A shot's block test: Minecraft's collision shapes, without the shootable plants. */
	public static RaycastContext ctx(Vec3d start, Vec3d end, Entity entity) {
		return new ShotContext(start, end, entity);
	}

	private static final class ShotContext extends RaycastContext {
		ShotContext(Vec3d start, Vec3d end, Entity entity) {
			super(start, end, ShapeType.COLLIDER, FluidHandling.NONE, entity);
		}

		@Override
		public VoxelShape getBlockShape(BlockState state, BlockView world, BlockPos pos) {
			return shootable(state) ? VoxelShapes.empty() : super.getBlockShape(state, world, pos);
		}
	}

	/** A shot went from a to b (up to what it met): every shootable plant whose outline it crossed breaks. Returns them. */
	public static List<BlockPos> shoot(ServerWorld world, ServerPlayerEntity shooter, Vec3d a, Vec3d b) {
		if (shooter == null || ServerTune.get("plants.shoot", 1) == 0 || a.squaredDistanceTo(b) < 1e-10) return List.of();
		List<BlockPos> crossed = new ArrayList<>();
		BlockView.raycast(a, b, crossed, (list, pos) -> {
			BlockState s = world.getBlockState(pos);
			if (shootable(s) && s.getOutlineShape(world, pos).raycast(a, b, pos) != null) list.add(pos.toImmutable());
			return null;
		}, list -> null);
		for (BlockPos pos : crossed) breakPlant(world, shooter, pos);
		return crossed;
	}

	/** A projectile's step from a to b: the plants up to the block it met, or the body it met before that. */
	public static List<BlockPos> shootStep(ServerWorld world, ServerPlayerEntity shooter, Vec3d a, Vec3d b, BlockHitResult block, Vec3d bodyHit) {
		Vec3d e = block.getType() != HitResult.Type.MISS ? block.getPos() : b;
		if (bodyHit != null && bodyHit.squaredDistanceTo(a) < e.squaredDistanceTo(a)) e = bodyHit;
		return shoot(world, shooter, a, e);
	}

	/** An explosion: the shootable plants within radius (explosion.plantRadius of it) go. */
	public static void blast(ServerWorld world, ServerPlayerEntity shooter, Vec3d center, double radius) {
		if (shooter == null || ServerTune.get("plants.blast", 1) == 0) return;
		double r = radius * ServerTune.get("explosion.plantRadius", 1.0);
		if (r <= 0) return;
		int n = MathHelper.ceil(r);
		BlockPos c = BlockPos.ofFloored(center);
		List<BlockPos> inside = new ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(c.add(-n, -n, -n), c.add(n, n, n)))
			if (Vec3d.ofCenter(pos).squaredDistanceTo(center) <= r * r && shootable(world.getBlockState(pos))) inside.add(pos.toImmutable());
		for (BlockPos pos : inside) breakPlant(world, shooter, pos);
	}

	private static void breakPlant(ServerWorld world, ServerPlayerEntity p, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (!shootable(s)) return; // (gone already: the top of a tall plant, a stalk above a broken piece)
		// a two-block plant drops from its lower half: broken there, the upper goes with it
		if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			BlockPos below = pos.down();
			if (world.getBlockState(below).isOf(s.getBlock())) { pos = below; s = world.getBlockState(below); }
		}
		if (!world.canPlayerModifyAt(p, pos) || p.isBlockBreakingRestricted(world, pos, p.interactionManager.getGameMode())) return;
		if (net.minecraftforge.common.ForgeHooks.onBlockBreakEvent(world, p.interactionManager.getGameMode(), p, pos) == -1) return;
		world.breakBlock(pos, !p.isCreative(), p);
	}
}
