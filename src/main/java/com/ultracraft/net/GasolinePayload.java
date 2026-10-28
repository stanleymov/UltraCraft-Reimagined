package com.ultracraft.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;

/**
 * The Firestarter's gasoline for clients to draw: {@link #STAIN} a new stain (`id`, `pos` on the surface, `normal` out of
 * it, `roll` degrees about the normal, `index` 0-4 into the splat atlas); {@link #FIRE} the voxel at `pos` (voxel
 * coordinates) caught fire or was refuelled (the client times the 6 s burn and 2 s dying down itself); {@link #REMOVE}
 * the voxel at `pos` burnt out or exploded (its stains go); {@link #DROP} the one stain `id` went (the oldest, over the cap);
 * {@link #OIL} the enemy `id` carries gasoline, `roll` = fuel / 5 (EnemyIdentifier's _OiledAmount).
 */
public record GasolinePayload(int op, int id, Vec3d pos, Vec3d normal, float roll, int index) {
	public static final int STAIN = 0, FIRE = 1, REMOVE = 2, DROP = 3, OIL = 4;

	public static GasolinePayload voxel(int op, int x, int y, int z) {
		return new GasolinePayload(op, 0, new Vec3d(x, y, z), Vec3d.ZERO, 0, 0);
	}

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(op);
		buf.writeVarInt(id);
		Net.writeVec(buf, pos);
		buf.writeFloat((float) normal.x);
		buf.writeFloat((float) normal.y);
		buf.writeFloat((float) normal.z);
		buf.writeFloat(roll);
		buf.writeVarInt(index);
	}

	public static GasolinePayload read(PacketByteBuf buf) {
		int op = buf.readVarInt(), id = buf.readVarInt();
		Vec3d pos = Net.readVec(buf);
		Vec3d n = new Vec3d(buf.readFloat(), buf.readFloat(), buf.readFloat());
		return new GasolinePayload(op, id, pos, n, buf.readFloat(), buf.readVarInt());
	}
}
