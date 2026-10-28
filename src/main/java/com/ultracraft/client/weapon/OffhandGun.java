package com.ultracraft.client.weapon;

import com.ultracraft.UkConfig;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkClip;
import com.ultracraft.item.RevolverItem;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.net.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.function.Consumer;

/**
 * An ULTRAKILL weapon in the off-hand slot (option gameplay.offhandWeapons): a second gun on the left, ULTRAKILL's dual
 * wield copy (DualWieldPickup: the copy under GunControl at x scale -1, so it sits mirrored on the other side). It
 * reads the same left / right click as the main gun and fires with it, with its own charges, cooldowns and rigs
 * (WeaponClient.OFF / ArsenalClient.OFF). Its code is the main gun's: it runs with the hands swapped on this client,
 * and its packets go to the server tagged (HandPayload) so the server swaps them too.
 */
public final class OffhandGun {
	private OffhandGun() {}

	private static int depth;

	/** Whether the off-hand gun's code is running now (its tick or its rig events). */
	public static boolean running() {
		return depth > 0;
	}

	/** Whether the off-hand holds an ULTRAKILL weapon used as a second gun right now. */
	public static boolean active(PlayerEntity p) {
		if (p == null || !UkConfig.offhandWeapons()) return false;
		if (UKWeaponItem.kindOf(p.getOffHandStack()) == null) return false;
		// (in third person too, like the main gun: switching the camera back no longer put it away and drew it again)
		return !com.ultracraft.client.shop.ShopMode.active();
	}

	/** Runs `r` as the off-hand weapon: the hands swapped on this client and the packets it sends tagged. */
	public static void run(PlayerEntity p, Runnable r) {
		if (p == null || depth > 0) { r.run(); return; }
		depth++;
		Net.swapHands(p);
		Net.beginOffhand();
		GunDisplays.swapFlash();
		try {
			r.run();
		} finally {
			GunDisplays.swapFlash();
			Net.endOffhand();
			Net.swapHands(p);
			depth--;
		}
	}

	/** The off-hand copy's rig events (ReadyGun, CanShoot ...) run as the off-hand weapon. */
	static void wrapEvents(RigInstance r) {
		if (r == null) return;
		Consumer<UkClip.Event> h = r.onEvent;
		r.onEvent = e -> run(MinecraftClient.getInstance().player, () -> h.accept(e));
	}

	/** Client tick, after the main gun's: the off-hand gun's tick (or putting it away once the off-hand is emptied). */
	static void tick(MinecraftClient client, ClientPlayerEntity p, boolean inputOk, boolean thirdPerson) {
		WeaponClient off = WeaponClient.OFF;
		if (active(p)) run(p, () -> off.tickGun(client, p, inputOk, thirdPerson));
		else if (off.gunOut()) {
			// nothing to fire: the copy runs once on an empty hand, which puts its gun away
			net.minecraft.item.ItemStack keep = p.getOffHandStack();
			p.getInventory().offHand.set(0, net.minecraft.item.ItemStack.EMPTY);
			try {
				run(p, () -> off.tickGun(client, p, false, thirdPerson));
			} finally {
				p.getInventory().offHand.set(0, keep);
			}
		}
	}

	/** The off-hand gun's rig this frame (its own copy), or null. */
	public static RigInstance rig(PlayerEntity p) {
		if (!active(p)) return null;
		var s = p.getOffHandStack();
		WeaponKind kind = UKWeaponItem.kindOf(s);
		if (kind == WeaponKind.REVOLVER) return WeaponClient.OFF.revolverRig(s);
		ArsenalClient.OFF.alt = UKWeaponItem.alternate(s);
		return ArsenalClient.OFF.rig(kind);
	}

	/** The off-hand weapon's kind / variation / version, for its displays. */
	public static WeaponKind kind(PlayerEntity p) {
		return UKWeaponItem.kindOf(p.getOffHandStack());
	}

	public static int variation(PlayerEntity p) {
		return kind(p) == WeaponKind.REVOLVER ? RevolverItem.variation(p.getOffHandStack()) : UKWeaponItem.variation(p.getOffHandStack());
	}

	public static boolean alternate(PlayerEntity p) {
		return UKWeaponItem.alternate(p.getOffHandStack());
	}
}
