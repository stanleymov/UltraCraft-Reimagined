package com.ultracraft.client.shop;

import com.ultracraft.client.UKSound;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.net.Net;
import com.ultracraft.net.OwnedPayload;
import com.ultracraft.net.ShopPayload;

/** The shop's client side: what the player owns (the server's word) and the buttons sent to it. */
public final class ShopClient {
	private static int[] owned = new int[WeaponKind.values().length];

	private ShopClient() {}

	public static void onOwned(OwnedPayload msg) {
		owned = msg.masks().length >= WeaponKind.values().length ? msg.masks() : java.util.Arrays.copyOf(msg.masks(), WeaponKind.values().length);
		// ShopSuccess / ShopFail (sound bubbles: smileOS 2 ok / error)
		if (msg.result() == 1) { UKSound.play("uk.ui.smileos2ok", 1f, 1f); ShopMode.openDrawer(); }
		else if (msg.result() == 2) UKSound.play("uk.ui.smileos2error", 1f, 1f);
	}

	public static boolean owns(WeaponKind kind, int variation) {
		return (owned[kind.ordinal()] & (1 << variation)) != 0;
	}

	public static boolean ownsAny(WeaponKind kind) {
		return owned[kind.ordinal()] != 0;
	}

	/** The next owned variation from `from` going `step` (+1 / -1), or `from` when it is the only one. */
	public static int nextOwned(WeaponKind kind, int from, int step) {
		for (int i = 1; i <= 3; i++) {
			int v = Math.floorMod(from + step * i, 3);
			if (owns(kind, v)) return v;
		}
		return from;
	}

	public static void buy(WeaponKind kind, int variation) {
		com.ultracraft.client.style.StyleState.INSTANCE.flushPoints();
		Net.toServer(new ShopPayload(ShopPayload.BUY, kind.ordinal(), variation));
	}

	public static void toggleAlt(WeaponKind kind, int variation) {
		Net.toServer(new ShopPayload(ShopPayload.TOGGLE_ALT, kind.ordinal(), variation));
	}
}
