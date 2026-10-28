package com.ultracraft.weapon;

import com.ultracraft.entity.CoinEntity;
import com.ultracraft.entity.UKEntities;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/** Revolver.ThrowCoin on the server. */
public final class Coins {
	private Coins() {}

	/** Coin 0.5 u below the camera; velocity = forward 20 + up 15 + the player's velocity (u/s). */
	/**
	 * The client's Revolver.ThrowCoin: start point (camera - camera.up * 0.5) and the whole velocity in game
	 * units per second, player velocity included. Both are bounded so a client cannot spawn coins far away.
	 */
	public static void throwCoinWithVelocity(ServerPlayerEntity p, Vec3d start, Vec3d velUnits) {
		if (start.squaredDistanceTo(p.getEyePos()) > 4 * 4) start = p.getEyePos().add(0, -0.5 * Damage.SCALE_BLOCKS, 0);
		if (velUnits.length() > 400) velUnits = velUnits.normalize().multiply(400);
		CoinEntity coin = new CoinEntity(UKEntities.COIN.get(), p.getServerWorld());
		coin.setPosition(start);
		coin.setYaw(p.getYaw());
		coin.setPitch(p.getPitch());
		coin.owner = p.getUuid();
		coin.setVelocity(velUnits.multiply(Damage.SCALE_BLOCKS * 0.05)); // blocks per tick
		p.getServerWorld().spawnEntity(coin);
	}

	public static void throwCoin(ServerPlayerEntity p, Vec3d origin, Vec3d dir) {
		CoinEntity coin = new CoinEntity(UKEntities.COIN.get(), p.getServerWorld());
		Vec3d pos = origin.add(0, -0.5 * Damage.SCALE_BLOCKS, 0);
		coin.setPosition(pos);
		coin.owner = p.getUuid();
		// the server knows the vanilla velocity (blocks/tick); V1's own velocity arrives with the player's movement packets as position deltas,
		// so use the last tick's displacement
		Vec3d playerVel = new Vec3d(p.getX() - p.prevX, p.getY() - p.prevY, p.getZ() - p.prevZ).multiply(20);
		Vec3d v = dir.normalize().multiply(20 * Damage.SCALE_BLOCKS).add(0, 15 * Damage.SCALE_BLOCKS, 0).add(playerVel);
		coin.setVelocity(v.multiply(0.05)); // Minecraft keeps entity velocity in blocks per tick (that is what the spawn packet carries)
		p.getServerWorld().spawnEntity(coin);
	}
}
