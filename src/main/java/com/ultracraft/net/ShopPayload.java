package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;

/** Client → server: a shop button. action BUY a variation, or TOGGLE_ALT a variation's standard / alternate. */
public record ShopPayload(int action, int kind, int variation) {
	public static final int BUY = 0, TOGGLE_ALT = 1;

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(action);
		buf.writeVarInt(kind);
		buf.writeVarInt(variation);
	}

	public static ShopPayload read(PacketByteBuf buf) {
		return new ShopPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
	}
}
