package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;

/**
 * Something the player's attack did, for feedback and style on the client.
 * `kind`: {@link #ENEMY_HIT}, {@link #KILL}, {@link #WALL_HIT}, {@link #PARRY}, {@link #HEAL}, {@link #RICOSHOT},
 * {@link #COIN_HIT}. `hitter`: the StyleCalculator hitter ({@link #REVOLVER}, {@link #PUNCH}, …). `arg`: the limb for
 * hits (0 body, 1 head), the ricochet count for RICOSHOT, 1 for a ricochet wall hit, the amount for HEAL.
 * `flags`: {@link #BIG} (a "spider"-class enemy), {@link #ULTRA} (ultra ricoshot).
 */
public record HitPayload(int kind, int hitter, int arg, int flags, Vec3d pos) {
	public static final int ENEMY_HIT = 0, KILL = 1, WALL_HIT = 2, PARRY = 3, HEAL = 4, HURT = 5, RICOSHOT = 6, COIN_HIT = 7, JUGGLE = 8;
	/**
	 * The Impact Hammer's swing, to its swinger (ShotgunHammer.ImpactRoutine / ImpactEffects): pos = where the impact
	 * effects go, flags = the tier, arg = {@link #SWING_STRONG} | {@link #SWING_WALL} | {@link #SWING_NADE} | {@link #SWING_SAW}.
	 */
	public static final int SWING = 9;
	/** A living enemy took the swing (hitstop by tier, launched back, overheat rules). */
	public static final int SWING_STRONG = 1;
	/** Environment (or an enemy already dead): NewMovement.Launch off it. */
	public static final int SWING_WALL = 2;
	/** A grenade became its Grenade Beam (TrueStop 0.25). */
	public static final int SWING_NADE = 4;
	/** A chainsaw was knocked out again. */
	public static final int SWING_SAW = 8;
	public static final int REVOLVER = 0, PUNCH = 1, HEAVYPUNCH = 2, COIN = 3, GROUNDSLAM = 4, SHOTGUN = 5, SHOTGUNZONE = 6, NAIL = 7,
		RAILCANNON = 8, EXPLOSION = 9, CANNONBALL = 10, HARPOON = 11, DRILL = 12, ZAPPER = 13, SAW = 14, HAMMER = 15, SAWBLADE = 16, SPIN = 17;
	/** flags bits 2-3: the Jackhammer tier (0..2). */
	public int tier() {
		return (flags >> 2) & 3;
	}
	public static final int BIG = 1, ULTRA = 2;

	/** flags bits 4-5 on enemy hits: the gore size (0 smallest, 1 small, 2 body: 1+ damage). */
	public int gore() {
		return (flags >> 4) & 3;
	}

	public HitPayload(int kind, int hitter, int arg, Vec3d pos) {
		this(kind, hitter, arg, 0, pos);
	}

	public boolean head() {
		return arg == 1;
	}

	public boolean big() {
		return (flags & BIG) != 0;
	}

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(kind);
		buf.writeVarInt(hitter);
		buf.writeVarInt(arg);
		buf.writeVarInt(flags);
		Net.writeVec(buf, pos);
	}

	public static HitPayload read(PacketByteBuf buf) {
		return new HitPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), Net.readVec(buf));
	}
}
