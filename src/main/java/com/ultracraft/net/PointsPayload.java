package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;

/**
 * Style points (P, the shop currency). Client → server: a delta earned since the last send. Server → client:
 * the persisted total (on login / after a save).
 */
public record PointsPayload(int points, boolean total) {
	public void write(PacketByteBuf buf) {
		buf.writeVarInt(points);
		buf.writeBoolean(total);
	}

	public static PointsPayload read(PacketByteBuf buf) {
		return new PointsPayload(buf.readVarInt(), buf.readBoolean());
	}
}
