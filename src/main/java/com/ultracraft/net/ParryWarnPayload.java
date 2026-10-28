package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;

/** Server → client: mob `entityId` shoots a parryable shot at V1 in a moment; `head` 0, or the Wither's side head 1 / 2. */
public record ParryWarnPayload(int entityId, int head) {
	public void write(PacketByteBuf buf) {
		buf.writeVarInt(entityId);
		buf.writeVarInt(head);
	}

	public static ParryWarnPayload read(PacketByteBuf buf) {
		return new ParryWarnPayload(buf.readVarInt(), buf.readVarInt());
	}
}
