package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;

/**
 * NewMovement.LaunchFromPoint(position, strength, maxDistance) for the client's V1, plus the hurt amount (V1 hp).
 * `rocketPush`: a rocket's 0-damage push wave (rocketExplosion), whose strength the client works out from its own
 * rocketJumps (100 / ((n + 3) / 3), back to 0 on landing) behind the 0.5 s safeExplosionLaunchCooldown.
 * `atSpeed`: a boosted pellet's blast, LaunchFromPointAtSpeed(max(strength, V1's speed)) - no fall-off with distance.
 */
public record LaunchPayload(Vec3d pos, float strength, float maxDistance, int hurt, boolean rocketPush, boolean atSpeed) {
	public LaunchPayload(Vec3d pos, float strength, float maxDistance, int hurt, boolean rocketPush) {
		this(pos, strength, maxDistance, hurt, rocketPush, false);
	}

	public void write(PacketByteBuf buf) {
		Net.writeVec(buf, pos);
		buf.writeFloat(strength);
		buf.writeFloat(maxDistance);
		buf.writeVarInt(hurt);
		buf.writeBoolean(rocketPush);
		buf.writeBoolean(atSpeed);
	}

	public static LaunchPayload read(PacketByteBuf buf) {
		return new LaunchPayload(Net.readVec(buf), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
	}
}
