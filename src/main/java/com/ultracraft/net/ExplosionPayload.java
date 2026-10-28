package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;

/** An explosion to draw and hear: `kind` 0 normal (orange), 1 super (red), 2 malicious; `maxSize` in u. */
public record ExplosionPayload(int kind, float maxSize, Vec3d pos) {
	public void write(PacketByteBuf buf) {
		buf.writeVarInt(kind);
		buf.writeFloat(maxSize);
		Net.writeVec(buf, pos);
	}

	public static ExplosionPayload read(PacketByteBuf buf) {
		return new ExplosionPayload(buf.readVarInt(), buf.readFloat(), Net.readVec(buf));
	}
}
