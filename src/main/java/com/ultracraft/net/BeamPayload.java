package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;

/**
 * A hitscan beam to draw: from `start` to `end`; `kind` 0 revolver, 1 revolver super/sharp, 2 railcannon, 3 coin beam;
 * `offHand`: shot by the shooter's off-hand gun (it starts at that gun's barrel, on the left).
 */
public record BeamPayload(int kind, int shooterId, Vec3d start, Vec3d end, boolean offHand) {
	public BeamPayload(int kind, int shooterId, Vec3d start, Vec3d end) {
		this(kind, shooterId, start, end, false);
	}

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(kind);
		buf.writeVarInt(shooterId);
		Net.writeVec(buf, start);
		Net.writeVec(buf, end);
		buf.writeBoolean(offHand);
	}

	public static BeamPayload read(PacketByteBuf buf) {
		return new BeamPayload(buf.readVarInt(), buf.readVarInt(), Net.readVec(buf), Net.readVec(buf), buf.readBoolean());
	}
}
