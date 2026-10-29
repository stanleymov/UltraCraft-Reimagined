package com.ultracraft.weapon;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minecraft fire from V1's weapons (the user's ask: ULTRAKILL's own fire never burnt a tree down). A fire goes where
 * Minecraft lets fire sit and V1 may build, as flint and steel places it (FlintAndSteelItem.useOnBlock); from there it
 * spreads and burns as Minecraft's fire does (doFireTick). Lit by: the Firestarter's gasoline wherever its splats lie,
 * and the Malicious railcannon through the plants it burns through and round where it lands.
 */
public final class BlockFire {
	private BlockFire() {}

	/** A fire in `pos` (air), lit from its `side`. */
	public static boolean place(ServerWorld w, ServerPlayerEntity by, BlockPos pos, Direction side) {
		if (!w.getBlockState(pos).isAir()) return false;
		if (by != null && !w.canPlayerModifyAt(by, pos)) return false;
		if (!AbstractFireBlock.canPlaceAt(w, pos, side)) return false;
		w.setBlockState(pos, AbstractFireBlock.getState(w, pos), Block.NOTIFY_ALL | Block.REDRAW_ON_MAIN_THREAD);
		return true;
	}

	/**
	 * A gasoline splat catching: a fire in front of every block it lies on (the user: any block under gasoline goes up,
	 * one it only partly covers too). The splat is the client's 5.125 u square (GasolineClient), its blob reaching
	 * gas.fireCover of the way out; `done` keeps one try per block across a voxel's splats.
	 */
	public static void splat(ServerWorld w, ServerPlayerEntity by, Vec3d pos, Vec3d normal, Set<BlockPos> done) {
		if (ServerTune.get("gas.blockFire", 1) == 0) return;
		double r = 2.5625 * Damage.SCALE_BLOCKS * ServerTune.get("gas.fireCover", 0.8);
		Vec3d n = normal.normalize();
		Vec3d a = n.crossProduct(Math.abs(n.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0)).normalize(), b = n.crossProduct(a);
		Direction side = Direction.getFacing(n.x, n.y, n.z);
		int steps = MathHelper.ceil(r / 0.25);
		BlockPos.Mutable back = new BlockPos.Mutable();
		for (int i = -steps; i <= steps; i++)
			for (int j = -steps; j <= steps; j++) {
				double x = i * 0.25, y = j * 0.25;
				if (x * x + y * y > r * r) continue;
				Vec3d p = pos.add(a.multiply(x)).add(b.multiply(y));
				// only where the splat lies on something (the client draws it there too: GasolineClient.clip)
				back.set(p.x - n.x * 0.05, p.y - n.y * 0.05, p.z - n.z * 0.05);
				if (w.getBlockState(back).getCollisionShape(w, back).isEmpty()) continue;
				BlockPos front = BlockPos.ofFloored(p.add(n.multiply(0.5)));
				if (done.add(front)) place(w, by, front, side);
			}
	}

	/**
	 * The Malicious railcannon's beam: the plants it burnt through catch where they stood, and where it lands, everything
	 * that can burn within rail.fireRadius blocks of the spot (the user: through the leaves, the block at the bottom and
	 * the ones beside them alight). `landed` null: it stopped in the air (a coin took it).
	 */
	public static void malicious(ServerWorld w, ServerPlayerEntity by, List<BlockPos> burntThrough, BlockHitResult landed) {
		double r = ServerTune.get("rail.fireRadius", 1.5);
		if (r <= 0) return;
		for (BlockPos pos : burntThrough) place(w, by, pos, Direction.UP);
		if (landed == null) return;
		Vec3d c = landed.getPos();
		BlockPos face = landed.getBlockPos().offset(landed.getSide());
		place(w, by, face, landed.getSide());
		Set<BlockPos> done = new HashSet<>();
		done.add(face);
		int n = MathHelper.ceil(r);
		for (BlockPos pos : BlockPos.iterate(face.add(-n, -n, -n), face.add(n, n, n)))
			if (Vec3d.ofCenter(pos).squaredDistanceTo(c) <= r * r && done.add(pos.toImmutable())) place(w, by, pos.toImmutable(), Direction.UP);
	}
}
