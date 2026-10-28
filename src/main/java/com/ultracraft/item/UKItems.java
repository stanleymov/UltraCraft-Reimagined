package com.ultracraft.item;

import com.ultracraft.UltraCraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** ULTRAKILL's weapons as Minecraft items (one item per weapon; the variation lives in NBT). */
public final class UKItems {
	public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(RegistryKeys.ITEM, UltraCraft.MOD_ID);
	public static final DeferredRegister<ItemGroup> GROUPS = DeferredRegister.create(RegistryKeys.ITEM_GROUP, UltraCraft.MOD_ID);

	public static final RegistryObject<Item> REVOLVER = ITEMS.register("revolver", () -> new RevolverItem(new Item.Settings().maxCount(1)));
	public static final RegistryObject<Item> SHOTGUN = ITEMS.register("shotgun", () -> new UKWeaponItem(WeaponKind.SHOTGUN, new Item.Settings().maxCount(1)));
	public static final RegistryObject<Item> NAILGUN = ITEMS.register("nailgun", () -> new UKWeaponItem(WeaponKind.NAILGUN, new Item.Settings().maxCount(1)));
	public static final RegistryObject<Item> RAILCANNON = ITEMS.register("railcannon", () -> new UKWeaponItem(WeaponKind.RAILCANNON, new Item.Settings().maxCount(1)));
	public static final RegistryObject<Item> ROCKET_LAUNCHER = ITEMS.register("rocket_launcher", () -> new UKWeaponItem(WeaponKind.ROCKET, new Item.Settings().maxCount(1)));

	public static final RegistryObject<ItemGroup> GROUP = GROUPS.register("ultracraft", () -> ItemGroup.builder()
		.icon(() -> new ItemStack(REVOLVER.get()))
		.displayName(Text.translatable("itemGroup.ultracraft"))
		.entries((ctx, entries) -> {
			for (int v = 0; v < 3; v++) entries.add(RevolverItem.of(v));
			for (WeaponKind k : new WeaponKind[] {WeaponKind.SHOTGUN, WeaponKind.NAILGUN, WeaponKind.RAILCANNON, WeaponKind.ROCKET})
				for (int v = 0; v < 3; v++) entries.add(UKWeaponItem.of(k, v));
			for (int v = 0; v < 3; v++) { ItemStack r = RevolverItem.of(v); r.getOrCreateNbt().putBoolean("alt", true); entries.add(r); }
			for (WeaponKind k : new WeaponKind[] {WeaponKind.SHOTGUN, WeaponKind.NAILGUN})
				for (int v = 0; v < 3; v++) entries.add(UKWeaponItem.ofAlt(k, v));
			entries.add(new ItemStack(com.ultracraft.block.UKBlocks.TERMINAL_ITEM.get()));
		}).build());

	private UKItems() {}

	public static Item of(WeaponKind kind) {
		return switch (kind) {
			case REVOLVER -> REVOLVER.get();
			case SHOTGUN -> SHOTGUN.get();
			case NAILGUN -> NAILGUN.get();
			case RAILCANNON -> RAILCANNON.get();
			case ROCKET -> ROCKET_LAUNCHER.get();
		};
	}

	public static void register(IEventBus modBus) {
		ITEMS.register(modBus);
		GROUPS.register(modBus);
	}
}
