package com.ultracraft.weapon;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.ultracraft.item.RevolverItem;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.net.Net;
import com.ultracraft.net.OwnedPayload;
import com.ultracraft.net.PointsSyncPayload;
import com.ultracraft.net.ShopPayload;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * The Terminal shop (ShopZone / VariationInfo), server side. A player owns variations (a bit mask per weapon, kept in
 * PlayerPersisted `ultracraft.owned`) and buys more with P. The user's rules: a new game starts with the Piercer only;
 * every other weapon and variation is bought; owning a weapon also gives its alternate, which the shop toggles
 * (standard / alternate) on the weapon in the inventory.
 */
public final class Shop {
	private Shop() {}

	/**
	 * VariationInfo.cost in Assets/Prefabs/Levels/Shop.prefab by colour (blue, green, red; the game's rev2 is the green
	 * Marksman, rev1 the red Sharpshooter). The game's blue variants cost 0 - they are found in its levels; here they are
	 * bought, cheaply (by eye, the user's call). Live: shop.price.<weapon><variation> in the live settings.
	 */
	private static final int[][] PRICES = {
		{0, 7500, 25000},       // revolver (the Piercer is the starter)
		{1000, 12500, 25000},   // shotgun
		{1500, 25000, 35000},   // nailgun
		{2500, 100000, 100000}, // railcannon
		{2500, 75000, 75000},   // rocket launcher
	};

	public static int price(WeaponKind kind, int variation) {
		return (int) ServerTune.get("shop.price." + kind.rig + variation, PRICES[kind.ordinal()][variation]);
	}

	/** Every standard (blue) weapon but the starter revolver, at its live price: a new player's starting P. */
	public static int standardTotal() {
		int sum = 0;
		for (WeaponKind k : WeaponKind.values()) if (k != WeaponKind.REVOLVER) sum += price(k, 0);
		return sum;
	}

	private static NbtCompound persisted(ServerPlayerEntity p) {
		return p.getPersistentData().getCompound("PlayerPersisted");
	}

	private static void save(ServerPlayerEntity p, NbtCompound persisted) {
		p.getPersistentData().put("PlayerPersisted", persisted);
	}

	public static int[] owned(ServerPlayerEntity p) {
		int[] stored = persisted(p).getIntArray("ultracraft.owned");
		int[] masks = new int[WeaponKind.values().length];
		for (int i = 0; i < masks.length && i < stored.length; i++) masks[i] = stored[i];
		return masks;
	}

	public static boolean owns(ServerPlayerEntity p, WeaponKind kind, int variation) {
		return (owned(p)[kind.ordinal()] & (1 << variation)) != 0;
	}

	private static void setOwned(ServerPlayerEntity p, int[] masks) {
		NbtCompound t = persisted(p);
		t.putIntArray("ultracraft.owned", masks);
		save(p, t);
	}

	/**
	 * On login, before the starter kit: a player with no ownership yet gets the Piercer; one who played before the shop
	 * existed (the starter kit already given) also keeps every variation of each ULTRAKILL weapon they carry.
	 */
	public static void onLogin(ServerPlayerEntity p, boolean playedBefore) {
		if (!persisted(p).contains("ultracraft.owned")) {
			int[] masks = new int[WeaponKind.values().length];
			masks[WeaponKind.REVOLVER.ordinal()] = 1;
			if (playedBefore)
				for (int i = 0; i < p.getInventory().size(); i++) {
					WeaponKind k = UKWeaponItem.kindOf(p.getInventory().getStack(i));
					if (k != null) masks[k.ordinal()] = 7;
				}
			setOwned(p, masks);
		}
		sync(p, 0);
	}

	public static void sync(ServerPlayerEntity p, int result) {
		Net.toPlayer(p, new OwnedPayload(owned(p), result));
	}

	public static void init() {
		Net.onServer(ShopPayload.class, Shop::onShop);
		MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(CommandManager.literal("ukshop")
			.requires(src -> src.hasPermissionLevel(2))
			.then(CommandManager.literal("reset").executes(c -> { reset(c.getSource().getPlayerOrThrow(), 1); return 1; }))
			.then(CommandManager.literal("all").executes(c -> { reset(c.getSource().getPlayerOrThrow(), 7); return 1; }))
			.then(CommandManager.literal("points").then(CommandManager.argument("p", IntegerArgumentType.integer(0))
				.executes(c -> { setPoints(c.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(c, "p")); return 1; })))));
	}

	/** /ukshop reset: the Piercer only (mask 1 on the revolver); /ukshop all: everything. The items carried stay. */
	private static void reset(ServerPlayerEntity p, int mask) {
		int[] masks = new int[WeaponKind.values().length];
		for (int i = 0; i < masks.length; i++) masks[i] = mask == 7 ? 7 : 0;
		masks[WeaponKind.REVOLVER.ordinal()] |= 1;
		setOwned(p, masks);
		sync(p, 0);
		p.sendMessage(Text.literal(mask == 7 ? "UltraCraft Reimagined shop: everything owned" : "UltraCraft Reimagined shop: back to the Piercer only"));
	}

	private static void setPoints(ServerPlayerEntity p, int points) {
		NbtCompound t = persisted(p);
		t.putInt("ultracraft.P", points);
		save(p, t);
		Net.toPlayer(p, new PointsSyncPayload(points));
	}

	private static void onShop(ShopPayload msg, ServerPlayerEntity p) {
		if (msg.kind() < 0 || msg.kind() >= WeaponKind.values().length || msg.variation() < 0 || msg.variation() > 2) return;
		WeaponKind kind = WeaponKind.values()[msg.kind()];
		if (msg.action() == ShopPayload.BUY) buy(p, kind, msg.variation());
		else if (msg.action() == ShopPayload.TOGGLE_ALT) toggleAlt(p, kind, msg.variation());
	}

	/** ShopButton → VariationInfo: enough P buys it (ShopSuccess), else ShopFail; a new weapon comes into the inventory. */
	private static void buy(ServerPlayerEntity p, WeaponKind kind, int variation) {
		if (owns(p, kind, variation)) {
			// owned but no longer carried (lost on death, thrown away): back for 0 P
			if (find(p, kind) != null) { sync(p, 2); return; }
			p.getInventory().offerOrDrop(kind == WeaponKind.REVOLVER ? RevolverItem.of(variation) : UKWeaponItem.of(kind, variation));
			sync(p, 1);
			return;
		}
		int cost = price(kind, variation), points = WeaponServer.points(p);
		if (points < cost) { sync(p, 2); return; }
		NbtCompound t = persisted(p);
		t.putInt("ultracraft.P", points - cost);
		save(p, t);
		int[] masks = owned(p);
		boolean first = masks[kind.ordinal()] == 0;
		masks[kind.ordinal()] |= 1 << variation;
		setOwned(p, masks);
		if (first || find(p, kind) == null)
			p.getInventory().offerOrDrop(kind == WeaponKind.REVOLVER ? RevolverItem.of(variation) : UKWeaponItem.of(kind, variation));
		Net.toPlayer(p, new PointsSyncPayload(points - cost));
		sync(p, 1);
	}

	/**
	 * The equipment button: one variation of the carried weapon between its standard and its alternate (the Minos
	 * revolver, Jackhammer, Sawblade Launcher), each colour on its own.
	 */
	private static void toggleAlt(ServerPlayerEntity p, WeaponKind kind, int variation) {
		ItemStack s = find(p, kind);
		if (s == null || !kind.hasAlt() || !owns(p, kind, variation)) { sync(p, 2); return; }
		UKWeaponItem.setAlternateOf(s, variation, !UKWeaponItem.alternateOf(s, variation));
		sync(p, 1);
	}

	/** The first carried item of a weapon. */
	public static ItemStack find(ServerPlayerEntity p, WeaponKind kind) {
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (UKWeaponItem.kindOf(s) == kind) return s;
		}
		return null;
	}
}
