package com.ultracraft.client.weapon;

import com.ultracraft.net.FirePayload;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a beam shot hit as the player saw it. Minecraft draws other entities a few ticks behind where the game's
 * (built-in) server has them: smoothed over three ticks, which for anything moving fast (popped up by the whiplash,
 * falling, knocked back) is a body length or more; ULTRAKILL has none of that. Every mob the shot's ray meets in its
 * drawn box is sent before the shot (FirePayload.VIEW_HIT: where, and whether the head), and the shot takes those
 * (Hitscan.fire). A single-player mod: what the player saw is the truth.
 */
public final class HitView {
	private HitView() {}

	public static void claim(ClientPlayerEntity p, Vec3d origin, Vec3d dir, float radiusUnits) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) return;
		float td = c.getTickDelta();
		Vec3d d = dir.normalize();
		Vec3d end = origin.add(d.multiply(Damage.rangeBlocks()));
		BlockHitResult block = c.world.raycast(com.ultracraft.weapon.Plants.ctx(origin, end, p));
		if (block.getType() != HitResult.Type.MISS) end = block.getPos();
		double r = radiusUnits * Damage.SCALE_BLOCKS;
		record Seen(LivingEntity e, Vec3d point, double along, boolean head) {}
		List<Seen> seen = new ArrayList<>();
		for (Entity e : c.world.getOtherEntities(p, new Box(origin, end).expand(r + 3), en -> en instanceof LivingEntity && !(en instanceof PlayerEntity) && en.isAlive())) {
			Box box = e.getBoundingBox().offset(e.getLerpedPos(td).subtract(e.getPos()));
			Box hitBox = box.expand(r);
			Optional<Vec3d> hp = hitBox.contains(origin) ? Optional.of(origin) : hitBox.raycast(origin, end);
			if (hp.isEmpty()) continue;
			// Hitscan.isHeadshot, on the drawn box
			double headY = box.maxY - (box.maxY - box.minY) * Damage.headFraction();
			double t = Math.max(0, box.getCenter().subtract(origin).dotProduct(d));
			boolean head = Math.max(hp.get().y, origin.y + d.y * t) >= headY;
			seen.add(new Seen((LivingEntity) e, hp.get(), hp.get().distanceTo(origin), head));
		}
		seen.sort(Comparator.comparingDouble(Seen::along));
		// (nothing seen is said too: the shot then hits no mob)
		if (seen.isEmpty()) Net.toServer(new FirePayload(FirePayload.VIEW_HIT, 0, -1, origin, d));
		for (int i = 0; i < Math.min(8, seen.size()); i++) {
			Seen s = seen.get(i);
			Net.toServer(new FirePayload(FirePayload.VIEW_HIT, s.head() ? 1 : 0, s.e().getId(), s.point(), d));
		}
	}
}
