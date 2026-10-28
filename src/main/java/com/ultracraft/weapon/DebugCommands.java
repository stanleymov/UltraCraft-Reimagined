package com.ultracraft.weapon;

import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Test setups for playtests that leave the player's inventory alone (op only):
 * <ul>
 * <li>/ukdebug sawring [saws]: a magnet shot into the ground 6 blocks ahead, then Attractor saws fired past it from
 * V1's eye, a few ticks apart, so they are caught and circle it.</li>
 * </ul>
 */
public final class DebugCommands {
	private record Later(int[] ticks, Runnable run) {}
	private static final List<Later> later = new ArrayList<>();

	private DebugCommands() {}

	public static void init() {
		MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(CommandManager.literal("ukdebug")
			.requires(src -> src.hasPermissionLevel(2))
			.then(CommandManager.literal("cannonball")
				.executes(c -> cannonball(c.getSource().getPlayerOrThrow(), 100))
				.then(CommandManager.argument("charge", IntegerArgumentType.integer(0, 100))
					.executes(c -> cannonball(c.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(c, "charge")))))
			.then(CommandManager.literal("blast")
				.executes(c -> blast(c.getSource().getPlayerOrThrow(), 5)))
			.then(CommandManager.literal("sawring")
				.executes(c -> sawRing(c.getSource().getPlayerOrThrow(), 6))
				.then(CommandManager.argument("saws", IntegerArgumentType.integer(1, 15))
					.executes(c -> sawRing(c.getSource().getPlayerOrThrow(), IntegerArgumentType.getInteger(c, "saws")))))));
		MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
			if (e.phase != TickEvent.Phase.END) return;
			for (Later l : new ArrayList<>(later)) if (--l.ticks[0] <= 0) { later.remove(l); l.run.run(); }
		});
	}

	/** An S.R.S. cannonball as RocketLauncher.ShootCannonball throws it (charge 0..100 %), from V1's eye where it looks. */
	private static int cannonball(ServerPlayerEntity p, int charge) {
		float cb = charge / 100f;
		Vec3d dir = p.getRotationVec(1f);
		Vec3d v = dir.multiply(Math.max(15, cb * 150));
		UKProjectileEntity.spawn(p.getServerWorld(), p, ProjectileKind.CANNONBALL, p.getEyePos().add(dir.multiply(Damage.SCALE_BLOCKS)), v.normalize(), v.length());
		return 1;
	}

	/**
	 * Explosion visuals only (no damage, no push) where V1 looks, `n` of them a second apart: for checking how blasts draw
	 * (in water, against the sky, under shader packs). The point is the block face looked at, or 20 blocks out.
	 */
	private static int blast(ServerPlayerEntity p, int n) {
		var world = p.getServerWorld();
		Vec3d eye = p.getEyePos(), to = eye.add(p.getRotationVec(1f).multiply(40));
		var hit = world.raycast(new net.minecraft.world.RaycastContext(eye, to, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
		Vec3d at = hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS ? eye.add(p.getRotationVec(1f).multiply(20)) : hit.getPos();
		for (int i = 0; i < n; i++)
			later.add(new Later(new int[] {1 + 20 * i}, () -> com.ultracraft.net.Net.toPlayer(p, new com.ultracraft.net.ExplosionPayload(0, Explosions.NORMAL.maxSize(), at))));
		return 1;
	}

	private static int sawRing(ServerPlayerEntity p, int saws) {
		var world = p.getServerWorld();
		float yaw = p.getYaw();
		Vec3d fwd = Vec3d.fromPolar(0, yaw), right = Vec3d.fromPolar(0, yaw + 90);
		Vec3d eye = p.getEyePos();
		Vec3d ground = new Vec3d(p.getX(), p.getY(), p.getZ()).add(fwd.multiply(6));
		UKProjectileEntity.spawn(world, p, ProjectileKind.MAGNET, eye, ground.subtract(eye), 100);
		// the saws go out level, a little to the side of the magnet, as a player sweeping past it would fire them
		for (int i = 0; i < saws; i++) {
			int n = i;
			later.add(new Later(new int[] {10 + 3 * i}, () -> {
				if (!p.isAlive()) return;
				Vec3d from = p.getEyePos().subtract(0, 0.6, 0);
				Vec3d aim = ground.add(right.multiply((n % 2 == 0 ? 1 : -1) * 2.5)).add(0, 0.4, 0);
				UKProjectileEntity saw = UKProjectileEntity.spawn(world, p, ProjectileKind.SAWBLADE_FODDER, from, aim.subtract(from), 200);
				saw.setSawVariation(0);
			}));
		}
		return 1;
	}
}
