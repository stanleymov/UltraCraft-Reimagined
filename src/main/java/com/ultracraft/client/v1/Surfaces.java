package com.ultracraft.client.v1;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * ULTRAKILL's SurfaceType for the ground under V1, picked from the Minecraft block. The clip lists are the game's
 * "Player Footstep Set" (Assets/Data/Player Footstep Set.asset); Generic is footstep_heavy1–4. PlayerFootsteps.Footstep
 * uses Wet while touching water without being submerged.
 */
public enum Surfaces {
	GENERIC("footstep_heavy", 4, false),
	CARPET("carpet", 4), CRUNCHY("crunchy", 4), DIRT("dirt", 3), FLESH("flesh", 3), GLASS("glass", 3, new int[] {1, 3, 4}),
	GRASS("grass", 4), GRAVEL("gravel", 4), MARBLE("marble", 4), METAL_GRILL("metalgrill", 4), METAL_HIGH("metalhigh", 3),
	METAL_LOW("metallow", 4), METAL_STUMPY("metal", 4), METAL_THIN("metalthin", 4), RUBBLE("rubble", 3), SAND("sand", 4),
	SNOW("snow", 2, new int[] {1, 3}), WET("wet", 4), WOOD("wood", 4), WOOD_THIN("woodthin", 4);

	/** Sound event names, e.g. uk.movement.footsteps.footstep_wood_3. */
	public final String[] clips;

	Surfaces(String name, int n) {
		this(name, n, true);
	}

	Surfaces(String name, int n, boolean sub) {
		clips = new String[n];
		for (int i = 0; i < n; i++) clips[i] = sub ? "uk.movement.footsteps.footstep_" + name + "_" + (i + 1) : "uk.movement." + name + (i + 1);
	}

	Surfaces(String name, int n, int[] numbers) {
		clips = new String[n];
		for (int i = 0; i < n; i++) clips[i] = "uk.movement.footsteps.footstep_" + name + "_" + numbers[i];
	}

	/**
	 * Whether sliding here throws the yellow sparks: the footstep set's slideParticles give Carpet / Sand dust,
	 * Crunchy / Dirt / Gravel / Rubble dirt, Grass, Snow, Wood, Flesh and Hologram their own scrapes (debris, no
	 * trails); every other surface falls back to Generic, the SlideScrape with the sparks (CreateSlideScrape).
	 */
	public boolean sparks() {
		return switch (this) {
			// RUBBLE (cobblestone) sparks too, by request: stone blocks should throw sparks in Minecraft
			case GENERIC, GLASS, MARBLE, METAL_GRILL, METAL_HIGH, METAL_LOW, METAL_STUMPY, METAL_THIN, WET, RUBBLE -> true;
			default -> false;
		};
	}

	private static final Map<BlockSoundGroup, Surfaces> BY_GROUP = new IdentityHashMap<>();

	static {
		put(WOOD, BlockSoundGroup.WOOD, BlockSoundGroup.NETHER_WOOD, BlockSoundGroup.BAMBOO_WOOD, BlockSoundGroup.CHERRY_WOOD, BlockSoundGroup.CHISELED_BOOKSHELF, BlockSoundGroup.NETHER_STEM);
		put(WOOD_THIN, BlockSoundGroup.LADDER, BlockSoundGroup.SCAFFOLDING, BlockSoundGroup.BAMBOO);
		put(GRASS, BlockSoundGroup.GRASS, BlockSoundGroup.MOSS_BLOCK, BlockSoundGroup.MOSS_CARPET, BlockSoundGroup.AZALEA, BlockSoundGroup.CROP, BlockSoundGroup.NYLIUM, BlockSoundGroup.WET_GRASS);
		put(CRUNCHY, BlockSoundGroup.AZALEA_LEAVES, BlockSoundGroup.CHERRY_LEAVES, BlockSoundGroup.CORAL);
		put(GRAVEL, BlockSoundGroup.GRAVEL, BlockSoundGroup.SUSPICIOUS_GRAVEL);
		put(DIRT, BlockSoundGroup.ROOTED_DIRT, BlockSoundGroup.PACKED_MUD);
		put(SAND, BlockSoundGroup.SAND, BlockSoundGroup.SUSPICIOUS_SAND, BlockSoundGroup.SOUL_SAND, BlockSoundGroup.SOUL_SOIL);
		put(SNOW, BlockSoundGroup.SNOW, BlockSoundGroup.POWDER_SNOW);
		put(CARPET, BlockSoundGroup.WOOL);
		put(GLASS, BlockSoundGroup.GLASS, BlockSoundGroup.AMETHYST_BLOCK, BlockSoundGroup.AMETHYST_CLUSTER);
		put(METAL_STUMPY, BlockSoundGroup.METAL, BlockSoundGroup.NETHERITE, BlockSoundGroup.LODESTONE, BlockSoundGroup.LANTERN);
		put(METAL_GRILL, BlockSoundGroup.CHAIN);
		put(METAL_LOW, BlockSoundGroup.ANVIL);
		put(METAL_THIN, BlockSoundGroup.COPPER);
		put(MARBLE, BlockSoundGroup.CALCITE, BlockSoundGroup.POLISHED_DEEPSLATE, BlockSoundGroup.DEEPSLATE_TILES);
		put(WET, BlockSoundGroup.MUD, BlockSoundGroup.MUDDY_MANGROVE_ROOTS, BlockSoundGroup.SLIME, BlockSoundGroup.HONEY, BlockSoundGroup.FROGSPAWN);
		put(FLESH, BlockSoundGroup.WART_BLOCK, BlockSoundGroup.SCULK, BlockSoundGroup.SCULK_CATALYST, BlockSoundGroup.NETHER_WART);
	}

	private static void put(Surfaces s, BlockSoundGroup... groups) {
		for (BlockSoundGroup g : groups) BY_GROUP.put(g, s);
	}

	/** The surface V1 stands on (the block under the feet; the one below that for carpets / thin tops that are air). */
	public static Surfaces under(ClientPlayerEntity p) {
		var world = p.getWorld();
		BlockPos feet = p.getBlockPos();
		if (world.getFluidState(feet).isIn(FluidTags.WATER) && !p.isSubmergedInWater()) return WET;
		BlockPos pos = BlockPos.ofFloored(p.getX(), p.getY() - 0.05, p.getZ());
		BlockState st = world.getBlockState(pos);
		if (st.isAir()) st = world.getBlockState(pos.down());
		return of(st);
	}

	/** The wall V1 jumps off: the block just past the body along −wallNormal (SceneHelper raycast toward the wall point). */
	public static Surfaces wall(ClientPlayerEntity p, net.minecraft.util.math.Vec3d wallNormal) {
		var world = p.getWorld();
		var c = p.getPos().add(0, p.getHeight() * 0.5, 0);
		for (double d : new double[] {0.5, 0.9, 1.3}) {
			BlockState st = world.getBlockState(BlockPos.ofFloored(c.subtract(wallNormal.multiply(d))));
			if (!st.isAir()) return of(st);
		}
		return GENERIC;
	}

	public static Surfaces of(BlockState st) {
		if (st.isOf(Blocks.DIRT) || st.isOf(Blocks.COARSE_DIRT) || st.isOf(Blocks.PODZOL) || st.isOf(Blocks.FARMLAND) || st.isOf(Blocks.DIRT_PATH)) return DIRT;
		if (st.isOf(Blocks.COBBLESTONE) || st.isOf(Blocks.MOSSY_COBBLESTONE) || st.isOf(Blocks.COBBLED_DEEPSLATE)) return RUBBLE;
		if (st.isOf(Blocks.IRON_BARS)) return METAL_GRILL;
		if (st.isIn(BlockTags.LEAVES)) return CRUNCHY;
		if (Registries.BLOCK.getId(st.getBlock()).getPath().contains("quartz")) return MARBLE;
		return BY_GROUP.getOrDefault(st.getSoundGroup(), GENERIC);
	}
}
