package com.ultracraft.block;

import com.ultracraft.UltraCraft;
import com.ultracraft.item.UKItems;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKeys;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Placeable ULTRAKILL level pieces: the grapple point (HookPoint) the Whiplash pulls V1 to. */
public final class UKBlocks {
	public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(RegistryKeys.BLOCK, UltraCraft.MOD_ID);
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(RegistryKeys.BLOCK_ENTITY_TYPE, UltraCraft.MOD_ID);

	public static final RegistryObject<Block> GRAPPLE_ORB = BLOCKS.register("grapple_orb", GrappleOrbBlock::new);
	public static final RegistryObject<Item> GRAPPLE_ORB_ITEM = UKItems.ITEMS.register("grapple_orb", () -> new GrappleOrbItem(GRAPPLE_ORB.get(), new Item.Settings()));
	public static final RegistryObject<BlockEntityType<GrappleOrbBlockEntity>> GRAPPLE_ORB_ENTITY = BLOCK_ENTITIES.register("grapple_orb",
		() -> BlockEntityType.Builder.create(GrappleOrbBlockEntity::new, GRAPPLE_ORB.get()).build(null));

	public static final RegistryObject<Block> TERMINAL = BLOCKS.register("terminal", TerminalBlock::new);
	public static final RegistryObject<Item> TERMINAL_ITEM = UKItems.ITEMS.register("terminal", () -> new TerminalItem(TERMINAL.get(), new Item.Settings()));
	public static final RegistryObject<BlockEntityType<TerminalBlockEntity>> TERMINAL_ENTITY = BLOCK_ENTITIES.register("terminal",
		() -> BlockEntityType.Builder.create(TerminalBlockEntity::new, TERMINAL.get()).build(null));

	private UKBlocks() {}

	public static void register(IEventBus modBus) {
		BLOCKS.register(modBus);
		BLOCK_ENTITIES.register(modBus);
	}
}
