package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;

/** Server → client: the player's persisted P total. */
public record PointsSyncPayload(int total) {
	public void write(PacketByteBuf buf) {
		buf.writeVarInt(total);
	}

	public static PointsSyncPayload read(PacketByteBuf buf) {
		return new PointsSyncPayload(buf.readVarInt());
	}
}
