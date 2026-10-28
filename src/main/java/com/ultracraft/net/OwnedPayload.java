package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;

/**
 * Server → client: the variations the player owns, a bit mask (bit = variation 0 blue, 1 green, 2 red) per
 * WeaponKind in ordinal order; `result` answers a shop button (0 none, 1 bought / done, 2 refused).
 */
public record OwnedPayload(int[] masks, int result) {
	public void write(PacketByteBuf buf) {
		buf.writeIntArray(masks);
		buf.writeVarInt(result);
	}

	public static OwnedPayload read(PacketByteBuf buf) {
		return new OwnedPayload(buf.readIntArray(), buf.readVarInt());
	}
}
