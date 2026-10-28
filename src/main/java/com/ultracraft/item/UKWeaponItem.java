package com.ultracraft.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * An ULTRAKILL weapon as a Minecraft item. NBT `var` = variation 0/1/2. Firing is handled by the client
 * weapon code and Fire packets, never by vanilla item use. The Revolver keeps its own class for history,
 * but reads through the same helpers.
 */
public class UKWeaponItem extends Item {
	public final WeaponKind kind;

	public UKWeaponItem(WeaponKind kind, Settings settings) {
		super(settings);
		this.kind = kind;
	}

	public static ItemStack of(WeaponKind kind, int variation) {
		ItemStack s = new ItemStack(UKItems.of(kind));
		s.getOrCreateNbt().putInt("var", variation);
		return s;
	}

	/** The weapon kind of a stack, or null when it is not an ULTRAKILL weapon. */
	public static WeaponKind kindOf(ItemStack stack) {
		if (stack.getItem() instanceof UKWeaponItem w) return w.kind;
		if (stack.getItem() instanceof RevolverItem) return WeaponKind.REVOLVER;
		return null;
	}

	public static ItemStack ofAlt(WeaponKind kind, int variation) {
		ItemStack s = of(kind, variation);
		s.getOrCreateNbt().putBoolean("alt", true);
		return s;
	}

	/** NBT `alt`: the slot's alternate weapon (Minos revolver, Jackhammer, Sawblade Launcher). */
	public static boolean alternate(ItemStack stack) {
		return stack.hasNbt() && stack.getNbt().getBoolean("alt");
	}

	public static int variation(ItemStack stack) {
		return stack.hasNbt() ? Math.floorMod(stack.getNbt().getInt("var"), 3) : 0;
	}

	public static void setVariation(ItemStack stack, int v) {
		v = Math.floorMod(v, 3);
		boolean alt = alternateOf(stack, v);
		stack.getOrCreateNbt().putInt("var", v);
		stack.getNbt().putBoolean("alt", alt);
	}

	/**
	 * Standard or alternate per variation (the shop's equipment buttons): NBT `altVars`, a bit per variation; `alt` is
	 * the held variation's. A stack from before the shop (no `altVars`) has its `alt` on every variation.
	 */
	public static boolean alternateOf(ItemStack stack, int variation) {
		if (!stack.hasNbt()) return false;
		if (!stack.getNbt().contains("altVars")) return stack.getNbt().getBoolean("alt");
		return (stack.getNbt().getInt("altVars") >> Math.floorMod(variation, 3) & 1) != 0;
	}

	public static void setAlternateOf(ItemStack stack, int variation, boolean alt) {
		int mask = 0;
		for (int v = 0; v < 3; v++) if (v == variation ? alt : alternateOf(stack, v)) mask |= 1 << v;
		stack.getOrCreateNbt().putInt("altVars", mask);
		if (Math.floorMod(stack.getNbt().getInt("var"), 3) == Math.floorMod(variation, 3)) stack.getNbt().putBoolean("alt", alt);
	}

	@Override
	public Text getName(ItemStack stack) {
		return Text.translatable("item.ultracraft." + kind.rig(alternate(stack)) + "." + variation(stack));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		return TypedActionResult.pass(user.getStackInHand(hand));
	}
}
