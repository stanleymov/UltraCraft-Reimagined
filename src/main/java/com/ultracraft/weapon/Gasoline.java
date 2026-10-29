package com.ultracraft.weapon;

import com.ultracraft.net.GasolinePayload;
import com.ultracraft.net.Net;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The Firestarter's gasoline (StainVoxelManager / StainVoxel / VoxelProxy / BurningVoxel / FireZone, and the fuel enemies
 * carry: EnemyIdentifier.AddFlammable + Flammable). Stains sort into 2.75 u voxels (rounded from the stain's point half a
 * unit off the surface); a voxel set alight burns 6 s, then dies down over 2 s (the stains shrink with it, then go). Fire
 * spreads every 0.05 s to every stained voxel in the 5 x 5 x 5 box around a burning one, and a new stain next to (3 x 3 x 3)
 * a burning voxel catches. A voxel with water within 1.375 u explodes instead (one explosion per 5 x 5 x 5 voxels of a
 * spreading fire), or just goes out. The fire hurts V1 10 every 0.5 s until halfway through dying down and enemies 1 every
 * 0.5 s (shared cooldowns) in a 2.75 x 5.5 x 2.75 u box. Explosions light gasoline within 3 voxels (box) of their centre.
 * Gasoline on an enemy (0.1 a droplet, up to 5) burns when an explosion lights it: 0.175 fuel every 0.5 s with 0.5 damage
 * a pulse (0.2 once the fuel is gone and the heat runs down), lighting the gasoline it stands in.
 */
public final class Gasoline {
	public static final double VOXEL = 2.75;
	private static final float BURN = 6f, EXTINGUISH = 2f;
	/** stains kept per world (the game's buffer holds 10000; the oldest go first here) */
	private static final int MAX_STAINS = 4000;

	private static final class Stain {
		final int id;
		final boolean floor;
		final Vec3d pos, normal;

		Stain(int id, boolean floor, Vec3d pos, Vec3d normal) {
			this.id = id;
			this.floor = floor;
			this.pos = pos;
			this.normal = normal;
		}
	}

	private static final class Voxel {
		final int x, y, z;
		final List<Stain> stains = new ArrayList<>();
		boolean burning;
		float t;
		UUID igniter;

		Voxel(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		long key() {
			return Gasoline.key(x, y, z);
		}
	}

	private static final class Fuel {
		float fuel, heat, pulse;
		boolean burning;
		UUID igniter;
	}

	private static final class State {
		final Map<Long, Voxel> voxels = new HashMap<>();
		final Set<Long> pending = new HashSet<>();
		final Set<Long> exploded = new HashSet<>();
		final ArrayDeque<long[]> order = new ArrayDeque<>();
		final Map<UUID, Float> playerHurt = new HashMap<>();
		final Map<Integer, Float> enemyHurt = new HashMap<>();
		final Map<Integer, Fuel> fuel = new HashMap<>();
		Float sincePropagation;
		int nextId;
		float time;
	}

	private static final Map<ServerWorld, State> worlds = new WeakHashMap<>();
	/** set while gasoline's own explosions go off (VoxelProxy.ExplodeAndDestroy: tryIgniteGasoline = false) */
	private static boolean ownExplosion;
	/** set while the fire's own damage is dealt (not cancelled as vanilla fire on a burning enemy) */
	private static boolean dealing;

	private Gasoline() {}

	public static void init() {
		MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END && e.level instanceof ServerWorld w) tick(w);
		});
		// a burning gasolined enemy shows Minecraft's flames; its burn damage is the Flammable pulses, not vanilla fire's
		MinecraftForge.EVENT_BUS.addListener((LivingAttackEvent e) -> {
			if (!(e.getEntity().getWorld() instanceof ServerWorld w) || !e.getSource().isIn(DamageTypeTags.IS_FIRE)) return;
			State s = worlds.get(w);
			Fuel f = s == null ? null : s.fuel.get(e.getEntity().getId());
			if (f != null && f.burning && !dealing) e.setCanceled(true);
		});
	}

	public static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}

	private static double voxelBlocks() {
		return VOXEL * Damage.SCALE_BLOCKS;
	}

	/** StainVoxelManager.WorldToVoxelPosition on a point in blocks. */
	public static int[] voxelOf(Vec3d p) {
		double v = voxelBlocks();
		return new int[] {(int) Math.round(p.x / v), (int) Math.round(p.y / v), (int) Math.round(p.z / v)};
	}

	public static Vec3d voxelCenter(int x, int y, int z) {
		double v = voxelBlocks();
		return new Vec3d(x * v, y * v, z * v);
	}

	/** GasolineStain.AttachTo: the voxel the stain counts for is half a unit off the surface. */
	public static int[] stainVoxel(Vec3d pos, Vec3d normal) {
		return voxelOf(pos.add(normal.multiply(0.5 * Damage.SCALE_BLOCKS)));
	}

	private static State state(ServerWorld w) {
		return worlds.computeIfAbsent(w, k -> new State());
	}

	// ------------------------------------------------------------------ stains

	/** GasolineProjectile.OnTriggerEnter on the environment: a stain where the droplet met the surface. */
	public static void addStain(ServerWorld w, Vec3d pos, Vec3d normal) {
		State s = state(w);
		int[] v = stainVoxel(pos, normal);
		Voxel vox = s.voxels.computeIfAbsent(key(v[0], v[1], v[2]), k -> new Voxel(v[0], v[1], v[2]));
		// GasolineStain.Start: IsFloor = the stain faces up more than 0.25
		Stain st = new Stain(s.nextId++, normal.y > 0.25, pos, normal);
		vox.stains.add(st);
		s.order.add(new long[] {st.id, vox.key()});
		Net.toNear(w, pos, new GasolinePayload(GasolinePayload.STAIN, st.id, pos, normal, w.random.nextFloat() * 360f, w.random.nextInt(5)));
		while (s.order.size() > MAX_STAINS) {
			long[] old = s.order.poll();
			Voxel ov = s.voxels.get(old[1]);
			if (ov == null) continue;
			ov.stains.removeIf(x -> x.id == old[0]);
			Net.toNear(w, voxelCenter(ov.x, ov.y, ov.z), new GasolinePayload(GasolinePayload.DROP, (int) old[0], Vec3d.ZERO, Vec3d.ZERO, 0, 0));
			if (ov.stains.isEmpty() && !ov.burning) s.voxels.remove(old[1]);
		}
		// StainVoxelManager.AcknowledgeNewStain: a burning voxel takes the new gasoline as fuel; one beside fire catches
		if (vox.burning) {
			refuel(w, vox);
			lightBlock(w, st, igniter(w, vox), new HashSet<>());
			return;
		}
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++) {
					Voxel n = s.voxels.get(key(vox.x + dx, vox.y + dy, vox.z + dz));
					if (n != null && n.burning) {
						vox.igniter = n.igniter;
						tryIgnite(w, s, vox);
						return;
					}
				}
	}

	// ------------------------------------------------------------------ fire

	/** ExplosionController.Start: an explosion lights the gasoline within `checkSize` voxels (a box) of it. */
	public static void onExplosion(ServerWorld w, ServerPlayerEntity shooter, Vec3d pos, int checkSize) {
		if (ownExplosion) return;
		tryIgniteAt(w, shooter == null ? null : shooter.getUuid(), pos, checkSize);
	}

	public static boolean tryIgniteAt(ServerWorld w, UUID igniter, Vec3d pos, int checkSize) {
		State s = worlds.get(w);
		if (s == null || s.voxels.isEmpty()) return false;
		int[] c = voxelOf(pos);
		int h = checkSize / 2;
		boolean any = false;
		for (int dx = -h; dx <= h; dx++)
			for (int dy = -h; dy <= h; dy++)
				for (int dz = -h; dz <= h; dz++) {
					Voxel v = s.voxels.get(key(c[0] + dx, c[1] + dy, c[2] + dz));
					if (v == null) continue;
					if (igniter != null) v.igniter = igniter;
					if (tryIgnite(w, s, v)) any = true;
				}
		return any;
	}

	/** StainVoxel.TryIgnite. */
	private static boolean tryIgnite(ServerWorld w, State s, Voxel v) {
		if (v.burning || v.stains.isEmpty()) return false;
		if (!waterNear(w, v)) {
			v.burning = true;
			v.t = 0;
			Net.toNear(w, voxelCenter(v.x, v.y, v.z), GasolinePayload.voxel(GasolinePayload.FIRE, v.x, v.y, v.z));
			lightBlocks(w, v);
		} else {
			// in water: one explosion per 5 x 5 x 5 of a spreading fire (ShouldExplodeAt), the rest just goes
			boolean explode = true;
			for (long k : s.exploded) {
				int ex = (int) (k >> 42) << 11 >> 11, ey = (int) ((k >> 21) & 0x1FFFFF) << 11 >> 11, ez = (int) (k & 0x1FFFFF) << 11 >> 11;
				if (Math.abs(v.x - ex) <= 2 && Math.abs(v.y - ey) <= 2 && Math.abs(v.z - ez) <= 2) { explode = false; break; }
			}
			remove(w, s, v);
			if (explode) {
				s.exploded.add(v.key());
				// VoxelProxy.ExplodeAndDestroy: the default Explosion, not lighting gasoline itself
				ServerPlayerEntity by = v.igniter == null ? null : (ServerPlayerEntity) w.getPlayerByUuid(v.igniter);
				ownExplosion = true;
				try {
					Explosions.explode(w, by, voxelCenter(v.x, v.y, v.z), Explosions.NORMAL, false, "napalm", null);
				} finally {
					ownExplosion = false;
				}
			}
		}
		schedulePropagation(s, v);
		return true;
	}

	/** The fire catches on Minecraft's blocks too, under every stain of a voxel that lights (BlockFire.splat; user). */
	private static void lightBlocks(ServerWorld w, Voxel v) {
		ServerPlayerEntity by = igniter(w, v);
		Set<BlockPos> done = new HashSet<>();
		for (Stain st : v.stains) lightBlock(w, st, by, done);
	}

	private static void lightBlock(ServerWorld w, Stain st, ServerPlayerEntity by, Set<BlockPos> done) {
		BlockFire.splat(w, by, st.pos, st.normal, done);
	}

	private static ServerPlayerEntity igniter(ServerWorld w, Voxel v) {
		return v.igniter == null ? null : (ServerPlayerEntity) w.getPlayerByUuid(v.igniter);
	}

	/** StainVoxel.TryIgnite's water check: an OverlapSphere of 1.375 u round the voxel's centre against water. */
	private static boolean waterNear(ServerWorld w, Voxel v) {
		Vec3d c = voxelCenter(v.x, v.y, v.z);
		double r = VOXEL / 2 * Damage.SCALE_BLOCKS;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int bx = (int) Math.floor(c.x - r); bx <= (int) Math.floor(c.x + r); bx++)
			for (int by = (int) Math.floor(c.y - r); by <= (int) Math.floor(c.y + r); by++)
				for (int bz = (int) Math.floor(c.z - r); bz <= (int) Math.floor(c.z + r); bz++) {
					m.set(bx, by, bz);
					var fs = w.getFluidState(m);
					if (!fs.isIn(FluidTags.WATER)) continue;
					double top = by + fs.getHeight(w, m);
					double dx = Math.max(bx - c.x, Math.max(0, c.x - bx - 1)), dz = Math.max(bz - c.z, Math.max(0, c.z - bz - 1));
					double dy = Math.max(by - c.y, Math.max(0, c.y - top));
					if (dx * dx + dy * dy + dz * dz <= r * r) return true;
				}
		return false;
	}

	/** StainVoxelManager.ScheduleFirePropagation: every unlit stained voxel in the 5 x 5 x 5 box, lit on the next 0.05 s. */
	private static void schedulePropagation(State s, Voxel v) {
		if (s.sincePropagation == null) s.sincePropagation = 0f;
		for (int dx = -2; dx <= 2; dx++)
			for (int dy = -2; dy <= 2; dy++)
				for (int dz = -2; dz <= 2; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					long k = key(v.x + dx, v.y + dy, v.z + dz);
					Voxel n = s.voxels.get(k);
					if (n != null && !n.burning) {
						if (n.igniter == null) n.igniter = v.igniter;
						s.pending.add(k);
					}
				}
		if (!s.exploded.isEmpty() && s.pending.isEmpty()) s.exploded.clear();
	}

	/** BurningVoxel.Refuel: new gasoline on a burning voxel starts its 6 s over. */
	private static void refuel(ServerWorld w, Voxel v) {
		v.t = 0;
		Net.toNear(w, voxelCenter(v.x, v.y, v.z), GasolinePayload.voxel(GasolinePayload.FIRE, v.x, v.y, v.z));
	}

	private static void remove(ServerWorld w, State s, Voxel v) {
		s.voxels.remove(v.key());
		v.stains.clear();
		v.burning = false;
		Net.toNear(w, voxelCenter(v.x, v.y, v.z), GasolinePayload.voxel(GasolinePayload.REMOVE, v.x, v.y, v.z));
	}

	// ------------------------------------------------------------------ fuel on enemies

	/** EnemyIdentifier.AddFlammable(0.1): a droplet on an enemy, up to 5. */
	public static void addFuel(ServerWorld w, LivingEntity e, float amount) {
		Fuel f = state(w).fuel.computeIfAbsent(e.getId(), k -> new Fuel());
		if (f.fuel < 5) f.fuel += Math.min(amount, 5 - f.fuel);
		oiled(w, e, f.fuel);
	}

	/** EnemyIdentifier: the renderers' _OiledAmount = fuel / 5 (the client tints the mob). */
	private static void oiled(ServerWorld w, LivingEntity e, float fuel) {
		Net.toNear(w, e.getPos(), new GasolinePayload(GasolinePayload.OIL, e.getId(), Vec3d.ZERO, Vec3d.ZERO, Math.max(0, fuel) / 5f, 0));
	}

	/**
	 * Explosion.Collide with ignite on an enemy carrying gasoline: EnemyIdentifier.StartBurning (fuel-only Flammables: heat
	 * 0.1). Returns false when it carries none (the explosion's usual fire applies).
	 */
	public static boolean ignite(ServerWorld w, LivingEntity e, ServerPlayerEntity by) {
		State s = worlds.get(w);
		Fuel f = s == null ? null : s.fuel.get(e.getId());
		if (f == null || f.fuel <= 0) return false;
		if (by != null) f.igniter = by.getUuid();
		f.heat = 0.1f;
		if (!f.burning) {
			f.burning = true;
			f.pulse = 0;
		}
		e.setFireTicks(Math.max(e.getFireTicks(), 20));
		return true;
	}

	// ------------------------------------------------------------------ tick

	private static void tick(ServerWorld w) {
		State s = worlds.get(w);
		if (s == null || ParryServer.worldFrozen()) return;
		float dt = 0.05f;
		s.time += dt;
		// fire spreading on its 0.05 s beat
		if (s.sincePropagation != null) {
			s.sincePropagation += dt;
			if (s.sincePropagation >= 0.05f && !s.pending.isEmpty()) {
				s.sincePropagation = -w.random.nextFloat() * 0.005f;
				List<Voxel> lit = new ArrayList<>();
				for (long k : s.pending) {
					Voxel v = s.voxels.get(k);
					if (v != null && !v.burning) lit.add(v);
				}
				s.pending.clear();
				for (Voxel v : lit) tryIgnite(w, s, v);
			}
		}
		// burning voxels: BurningVoxel's 6 s, 2 s dying down; FireZone damage while it lasts
		List<Voxel> done = new ArrayList<>();
		for (Voxel v : s.voxels.values()) {
			if (!v.burning) continue;
			v.t += dt;
			float ext = v.t - (float) ServerTune.get("gas.burnTime", BURN);
			if (ext >= EXTINGUISH) { done.add(v); continue; }
			if (ext / EXTINGUISH > 0.85f) continue;
			hurtIn(w, s, v, ext / EXTINGUISH <= 0.5f);
		}
		for (Voxel v : done) remove(w, s, v);
		// fuelled enemies: EnemyIdentifier.Burn every 0.5 s
		for (Iterator<Map.Entry<Integer, Fuel>> it = s.fuel.entrySet().iterator(); it.hasNext(); ) {
			var en = it.next();
			Entity ent = w.getEntityById(en.getKey());
			Fuel f = en.getValue();
			if (!(ent instanceof LivingEntity e) || !e.isAlive()) { it.remove(); continue; }
			if (!f.burning) continue;
			if (e.isTouchingWater()) { f.burning = false; f.heat = 0; continue; }
			f.pulse += dt;
			if (f.pulse < 0.5f) continue;
			f.pulse -= 0.5f;
			// Flammable.Pulse
			if (f.fuel >= 0.175f) f.fuel -= 0.175f;
			else if (f.fuel > 0) { f.heat = 0.175f - f.fuel; f.fuel = 0; }
			else f.heat -= 0.25f;
			oiled(w, e, f.fuel);
			if (f.heat <= 0) {
				f.burning = false;
				if (f.fuel <= 0) it.remove();
				continue;
			}
			e.setFireTicks(Math.max(e.getFireTicks(), 15));
			tryIgniteAt(w, f.igniter, e.getPos(), 3);
			hurtEnemy(w, e, f.fuel > 0 ? 0.5f : 0.2f, f.igniter);
		}
		s.playerHurt.values().removeIf(t -> s.time - t > 1);
		s.enemyHurt.values().removeIf(t -> s.time - t > 1);
		if (s.voxels.isEmpty() && s.fuel.isEmpty()) worlds.remove(w);
	}

	/** FireZone.OnTriggerStay in the voxel's 2.75 x 5.5 x 2.75 u box (centre 0.5 u up), on HurtCooldownCollection's 0.5 s. */
	private static void hurtIn(ServerWorld w, State s, Voxel v, boolean hurtPlayer) {
		double S = Damage.SCALE_BLOCKS;
		Vec3d c = voxelCenter(v.x, v.y, v.z).add(0, 0.5 * S, 0);
		Box box = new Box(c.x - 1.375 * S, c.y - 2.75 * S, c.z - 1.375 * S, c.x + 1.375 * S, c.y + 2.75 * S, c.z + 1.375 * S);
		for (Entity ent : w.getOtherEntities(null, box)) {
			if (!(ent instanceof LivingEntity e) || !e.isAlive()) continue;
			if (e instanceof ServerPlayerEntity p) {
				if (!hurtPlayer || p.isCreative() || p.isSpectator()) continue;
				Float last = s.playerHurt.get(p.getUuid());
				if (last != null && s.time - last < 0.5f - 1e-4f) continue;
				s.playerHurt.put(p.getUuid(), s.time);
				// NewMovement.GetHurt(10, invincible): 10 V1 hp = 2 HP, as the explosions' 35 = 7
				p.timeUntilRegen = 0;
				p.damage(p.getDamageSources().inFire(), 10 / 5f);
				continue;
			}
			Float last = s.enemyHurt.get(e.getId());
			if (last != null && s.time - last < 0.5f - 1e-4f) continue;
			s.enemyHurt.put(e.getId(), s.time);
			hurtEnemy(w, e, 1f, v.igniter);
		}
	}

	private static void hurtEnemy(ServerWorld w, LivingEntity e, float points, UUID by) {
		ServerPlayerEntity p = by == null ? null : (ServerPlayerEntity) w.getPlayerByUuid(by);
		dealing = true;
		try {
			if (p != null) Damage.deal(p, e, points, Vec3d.ZERO, 0);
			else {
				e.timeUntilRegen = 0;
				e.damage(w.getDamageSources().inFire(), (float) (points * Damage.hpPerDamage()));
			}
		} finally {
			dealing = false;
		}
	}
}
