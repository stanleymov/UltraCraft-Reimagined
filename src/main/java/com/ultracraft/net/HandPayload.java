package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;

/**
 * Client → server: the packets that follow come from the off-hand weapon (true) or the main hand again (false). The
 * server swaps the player's hands while it handles them, so the weapon code reads the off-hand weapon as the held one.
 */
public record HandPayload(boolean off) {
	public void write(PacketByteBuf buf) {
		buf.writeBoolean(off);
	}

	public static HandPayload read(PacketByteBuf buf) {
		return new HandPayload(buf.readBoolean());
	}
}
