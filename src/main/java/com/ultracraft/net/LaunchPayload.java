package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;

/**
 * NewMovement.LaunchFromPoint(position, strength, maxDistance) for the client's V1, plus the hurt amount (V1 hp).
 * `rocketPush`: a rocket's 0-damage push wave (rocketExplosion), whose strength the client works out from its own
 * rocketJumps (100 / ((n + 3) / 3), back to 0 on landing) behind the 0.5 s safeExplosionLaunchCooldown.
 */
public record LaunchPayload(Vec3d pos, float strength, float maxDistance, int hurt, boolean rocketPush) {
	public void write(PacketByteBuf buf) {
		Net.writeVec(buf, pos);
		buf.writeFloat(strength);
		buf.writeFloat(maxDistance);
		buf.writeVarInt(hurt);
		buf.writeBoolean(rocketPush);
	}

	public static LaunchPayload read(PacketByteBuf buf) {
		return new LaunchPayload(Net.readVec(buf), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readBoolean());
	}
}
