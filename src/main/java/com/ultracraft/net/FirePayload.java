package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;

/**
 * Client fired a weapon. `weapon`: {@link #REVOLVER}, {@link #PUNCH}, ... `mode`: weapon-specific (revolver:
 * 0 normal, 1 charged; punch: 0 standard, 1 heavy). `origin`/`dir`: the camera ray at fire time, so the
 * server shoots what the player saw. `arg`: variation / ricochet count.
 */
public record FirePayload(int weapon, int mode, int arg, Vec3d origin, Vec3d dir) {
	public static final int REVOLVER = 0;
	public static final int PUNCH = 1;
	public static final int COIN = 2;
	public static final int SHOTGUN = 3, NAILGUN = 4, RAILCANNON = 5, ROCKET = 6;
	/** the ground slam's PhysicalShockwavePlayer (arg = slamForce × 100) */
	public static final int SHOCKWAVE = 8;
	/** Whiplash: mode 0 caught (arg = entity id, 0.2 damage), 1 pull a light target toward V1, 2 pull done. */
	public static final int HOOK = 7;
	/** What a beam shot hit as the player saw it, sent just before the shot: arg = entity id (-1: nothing), mode 1 = head, origin = the point. */
	public static final int VIEW_HIT = 10;
	/** A slam landing on an enemy (GroundCheck.OnTriggerEnter while heavyFall): arg = entity id. */
	public static final int STOMP = 11;
	/** V1 touched lava with the ULTRAKILL lava option on (HurtZone, Lava: 20 damage a second, no burning). */
	public static final int LAVA = 12;
	/** Clash mode: V1 touched ULTRAKILL lava and burns to ash (killed 2 s later). */
	public static final int LAVA_BURN = 13;
	/** mode 9 on any weapon = variation switch (arg = new variation). */
	public static final int MODE_VARIATION = 9;

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(weapon);
		buf.writeVarInt(mode);
		buf.writeVarInt(arg);
		Net.writeVec(buf, origin);
		Net.writeVec(buf, dir);
	}

	public static FirePayload read(PacketByteBuf buf) {
		return new FirePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), Net.readVec(buf), Net.readVec(buf));
	}
}
