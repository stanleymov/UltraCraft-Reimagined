package com.ultracraft.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * The Revolver. NBT `var`: 0 Piercer (blue), 1 Marksman (green), 2 Sharpshooter (red); `alt` 1 = the
 * alternate (Minos) revolver. Firing is handled by the client weapon code and the Fire packet, not by
 * vanilla item use.
 */
public class RevolverItem extends Item {
	public static final String[] VARIATION_NAMES = {"Piercer", "Marksman", "Sharpshooter"};

	public RevolverItem(Settings settings) {
		super(settings);
	}

	public static ItemStack of(int variation) {
		ItemStack s = new ItemStack(UKItems.REVOLVER.get());
		s.getOrCreateNbt().putInt("var", variation);
		return s;
	}

	public static int variation(ItemStack stack) {
		return stack.hasNbt() ? Math.floorMod(stack.getNbt().getInt("var"), 3) : 0;
	}

	public static boolean alternate(ItemStack stack) {
		return stack.hasNbt() && stack.getNbt().getBoolean("alt");
	}

	public static void setVariation(ItemStack stack, int v) {
		UKWeaponItem.setVariation(stack, v);
	}

	@Override
	public Text getName(ItemStack stack) {
		return Text.translatable("item.ultracraft." + (alternate(stack) ? "revolver_alt" : "revolver") + "." + variation(stack));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		// right click = ULTRAKILL alt fire, driven client side; nothing for vanilla to do
		return TypedActionResult.pass(user.getStackInHand(hand));
	}
}
