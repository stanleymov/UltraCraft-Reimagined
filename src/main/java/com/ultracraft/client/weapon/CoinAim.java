package com.ultracraft.client.weapon;

import com.ultracraft.entity.CoinEntity;
import com.ultracraft.net.FirePayload;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.Damage;
import com.ultracraft.weapon.Hitscan;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Coin hits decided on what the player sees. RevolverBeam.Shoot sphere-casts the beam radius (0.4 single, 0.6
 * charged, 1.2 railcannon) against the coin's 1 u trigger sphere, so a beam takes a coin whose centre is within
 * radius + 1 u of its line. The server's copy of a coin is a tick or two away from the one drawn here (about a
 * block when running), so the client tests its own coins and tells the server which point it hit; the server
 * checks the claim against its copy and uses it.
 */
public final class CoinAim {
	private CoinAim() {}

	private static long snapNanos;
	private static Vec3d snapEye, snapLook;
	private static final java.util.Map<Integer, Vec3d> snapCoins = new java.util.HashMap<>();

	/** At the click (frame time): the camera, the aim and every coin where it is drawn this frame. */
	public static void snapshot(MinecraftClient mc) {
		ClientPlayerEntity p = mc.player;
		if (p == null || mc.world == null) return;
		float td = mc.getTickDelta();
		snapNanos = System.nanoTime();
		snapEye = mc.options.getPerspective().isFirstPerson() ? mc.gameRenderer.getCamera().getPos() : p.getCameraPosVec(td);
		snapLook = p.getRotationVec(td);
		snapCoins.clear();
		for (Entity e : mc.world.getEntities()) {
			if (e instanceof CoinEntity c) snapCoins.put(c.getId(), c.getLerpedPos(td).add(0, c.getHeight() / 2, 0));
		}
	}

	/** The click this shot came from, if it was in the last 0.15 s. */
	private static boolean fresh() {
		return snapEye != null && System.nanoTime() - snapNanos < 150_000_000L;
	}

	/** The shot's origin: where the camera was at the click (or now). */
	public static Vec3d origin(ClientPlayerEntity p) {
		return fresh() ? snapEye : p.getEyePos();
	}

	/** The shot's direction: the aim at the click (or now). */
	public static Vec3d look(ClientPlayerEntity p) {
		return fresh() ? snapLook : p.getRotationVec(1f);
	}

	/** Before a hitscan shot: if the beam touches a coin this client sees, send the claim (then the fire packet). */
	public static void claim(ClientPlayerEntity p, Vec3d origin, Vec3d dir, float beamRadiusUnits) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) return;
		boolean useSnap = fresh();
		double s = Damage.SCALE_BLOCKS;
		Vec3d d = dir.normalize();
		double range = Damage.rangeBlocks();
		Vec3d end = origin.add(d.multiply(range));
		var block = mc.world.raycast(com.ultracraft.weapon.Plants.ctx(origin, end, p));
		double maxAlong = block.getType() == HitResult.Type.MISS ? range : block.getPos().distanceTo(origin);
		double r = beamRadiusUnits * s + Hitscan.coinTriggerBlocks();
		CoinEntity best = null;
		double bestAlong = Double.MAX_VALUE;
		for (Entity e : mc.world.getEntities()) {
			if (!(e instanceof CoinEntity c)) continue;
			// where the coin was drawn when the player clicked
			Vec3d cc = useSnap && snapCoins.containsKey(c.getId()) ? snapCoins.get(c.getId()) : c.getBoundingBox().getCenter();
			double t = cc.subtract(origin).dotProduct(d);
			double miss = origin.add(d.multiply(t)).distanceTo(cc);
			if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] coin check id={} age={} shot={} landed={} ghost={} t={} miss={} r={} maxAlong={}", c.getId(), c.ageSeconds(), c.isShot(), c.isLanded(), c.ghost, t, miss, r, maxAlong);
			if (c.isShot() || c.isLanded() || c.ageSeconds() < Hitscan.COIN_ARM_SECONDS) continue;
			if (t < 0 || t > maxAlong + r) continue;
			if (miss > r) continue;
			double along = Math.max(0, t - Math.sqrt(Math.max(0, r * r - miss * miss)));
			if (along < bestAlong) { bestAlong = along; best = c; }
		}
		if (best == null) return;
		Vec3d point = origin.add(d.multiply(bestAlong));
		Vec3d coinPos = useSnap && snapCoins.containsKey(best.getId()) ? snapCoins.get(best.getId()) : best.getBoundingBox().getCenter();
		if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] coin claim: point {} coin {} along {}", point, coinPos, bestAlong);
		Net.toServer(new FirePayload(FirePayload.COIN, 2, 0, point, coinPos));
	}
}
