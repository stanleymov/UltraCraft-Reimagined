package com.ultracraft.client.render;

import com.ultracraft.client.Tune;
import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's moving point lights as Minecraft block light: the Rocket's and the Grenade's Point Light (1, 0.24, 0) x 10,
 * range 10, and RocketLauncherFire's (white x 30, range 30, its range down 160/s). A light only changes what the chunk
 * meshes bake in, so the sections around a light are rebuilt whenever it moves or changes; the block light the meshes
 * (vanilla's getLightmapCoordinates, Embeddium's LightDataAccess) and the entities (EntityRenderer.getBlockLight) read is
 * raised to it. Minecraft's block light has no colour: under a shader pack it takes the pack's torch colour.
 */
public final class DynamicLights {
	/** A light: where, its range in blocks and its intensity (the Unity Light's). */
	private record Src(double x, double y, double z, double range, double intensity) {}

	private static final class Flash {
		long id;
		Vec3d pos;
		long startNanos;
		double range, shrink, intensity;
	}

	/** The lights the chunk builders read (their own threads): swapped whole, never changed in place. */
	private static volatile Src[] sources = new Src[0];
	private static final List<Flash> flashes = new ArrayList<>();
	/** Each light's last baked box (minX, minY, minZ, maxX, maxY, maxZ) and what it was baked from, by key. */
	private static Map<Long, int[]> baked = new HashMap<>();
	private static long flashIds;

	static {
		Tune.def("light.world", 1, "1 = rockets, grenades and the rocket launcher's shot light the world (Minecraft block light; the chunks around them are rebuilt as they move)");
		Tune.def("light.gain", 1, "world light: x the Unity light's intensity before it is turned into a block light level 0..15 (by eye)");
		Tune.def("light.rangeScale", 1, "world light: x the Unity light's range (from the game: 1)");
		Tune.def("light.colorR", 1, "Rocket / Grenade light colour, red (by eye, the user's call: a paler gold than the grenade star's mean (1, 0.794, 0.176), which looked too saturated; the game's light is (1, 0.239, 0) at intensity 10)");
		Tune.def("light.colorG", 0.92, "Rocket / Grenade light colour, green (by eye)");
		Tune.def("light.colorB", 0.65, "Rocket / Grenade light colour, blue (by eye)");
		Tune.def("light.tint", 1, "world light colour: blocks lit by a rocket / grenade take on light.color* this much where our light outshines their own and the sky's (Embeddium only; 0 = Minecraft's one light colour; by eye)");
		Tune.def("light.maxSources", 8, "world light: at most this many lights, nearest the camera first (each one rebuilds chunks as it moves)");
		Tune.def("light.rocket", 1, "1 = the Rocket's Point Light (1, 0.24, 0) x 10, range 10 lights the world");
		Tune.def("light.grenade", 1, "1 = the Grenade's Point Light (1, 0.24, 0) x 10, range 10 lights the world (the hammer's and the Core Eject's)");
		Tune.def("light.shot", 1, "1 = the rocket launcher's shot (RocketLauncherFire: white x 30, range 30 down 160/s) lights the world");
	}

	private DynamicLights() {}

	/** Loads this class (its live settings, light.color* among them, are read by GunDisplays too). */
	public static void ensureTunes() {}

	/** A light that lives for its range: RocketLauncherFire's Point Light with ScaleNFade on its range. */
	public static void flash(Vec3d pos, double range, double shrinkPerSecond, double intensity) {
		if (pos == null || !Tune.on("light.world") || !Tune.on("light.shot")) return;
		Flash f = new Flash();
		f.id = ++flashIds;
		f.pos = pos;
		f.startNanos = System.nanoTime();
		f.range = range;
		f.shrink = shrinkPerSecond;
		f.intensity = intensity;
		flashes.add(f);
	}

	/**
	 * The block light level (0..15) the lights give at a block. Unity's built-in point light falls off as
	 * 1 / (1 + 25 (d / range)^2), cut to nothing at its range (here faded over its last fifth); its intensity multiplies it
	 * and anything over 1 is full. (Approximated from Unity's documented falloff, not measured.)
	 */
	public static int blockLight(int bx, int by, int bz) {
		Src[] s = sources;
		if (s.length == 0) return 0;
		double x = bx + 0.5, y = by + 0.5, z = bz + 0.5;
		double best = 0;
		for (Src l : s) {
			double dx = x - l.x, dy = y - l.y, dz = z - l.z;
			double d2 = dx * dx + dy * dy + dz * dz, r2 = l.range * l.range;
			if (d2 >= r2) continue;
			double k = Math.sqrt(d2 / r2);
			double a = 1 / (1 + 25 * k * k) * (k > 0.8 ? (1 - k) / 0.2 : 1);
			best = Math.max(best, Math.min(1, a * l.intensity));
		}
		return (int) Math.round(best * 15);
	}

	/** How much the sky lights the world now (1 day, 0 night), for tintQuad: read by the chunk builders. */
	private static volatile float skyFactor = 1;
	private static volatile float tintStrength = 1, tintR = 1, tintG = 0.92f, tintB = 0.65f;
	private static java.lang.invoke.MethodHandle ctxPos, ctxWorld;
	private static boolean ctxLooked;

	/**
	 * Embeddium's chunk builder (its threads): a block's quad colours (ABGR, -1 = untinted) multiplied toward the lights'
	 * colour by how much more our light gives the block than its own block light and the sky's (so in daylight or by a
	 * torch nothing changes). `ctx` is its BlockRenderContext (pos(), world()).
	 */
	public static void tintQuad(Object ctx, int[] colors) {
		if (colors == null || sources.length == 0 || tintStrength <= 0) return;
		try {
			if (!ctxLooked) {
				ctxLooked = true;
				var look = java.lang.invoke.MethodHandles.publicLookup();
				ctxPos = look.unreflect(ctx.getClass().getMethod("pos"));
				ctxWorld = look.unreflect(ctx.getClass().getMethod("world"));
			}
			if (ctxPos == null) return;
			net.minecraft.util.math.BlockPos pos = (net.minecraft.util.math.BlockPos) ctxPos.invoke(ctx);
			int dyn = blockLight(pos.getX(), pos.getY(), pos.getZ());
			if (dyn <= 0) return;
			int base = 0;
			if (ctxWorld.invoke(ctx) instanceof net.minecraft.world.BlockRenderView w) {
				// a solid block keeps 0 itself: its faces are lit from the air around it
				int bl = 0, sl = 0;
				for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
					net.minecraft.util.math.BlockPos n = pos.offset(d);
					bl = Math.max(bl, w.getLightLevel(net.minecraft.world.LightType.BLOCK, n));
					sl = Math.max(sl, w.getLightLevel(net.minecraft.world.LightType.SKY, n));
				}
				base = Math.max(bl, Math.round(sl * skyFactor));
			}
			float k = Math.min(1, Math.max(0, dyn - base) / 15f * tintStrength);
			if (k <= 0) return;
			float mr = 1 + (tintR - 1) * k, mg = 1 + (tintG - 1) * k, mb = 1 + (tintB - 1) * k;
			for (int i = 0; i < colors.length; i++) {
				int c = colors[i];
				// ABGR (Embeddium's providers: ColorARGB.toABGR): red in the low byte
				int b = Math.round(((c >> 16) & 255) * mb), g = Math.round(((c >> 8) & 255) * mg), r = Math.round((c & 255) * mr);
				colors[i] = (c & 0xFF000000) | (b << 16) | (g << 8) | r;
			}
		} catch (Throwable t) {
			ctxPos = null; // not the Embeddium we know: no tint
		}
	}

	/** Raises a packed lightmap value's block light to the lights' at that block. */
	public static int raise(int packed, int bx, int by, int bz) {
		int dyn = blockLight(bx, by, bz);
		if (dyn <= 0) return packed;
		int block = (packed & 0xFFFF) >> 4;
		return dyn > block ? (packed & 0xFFFF0000) | (dyn << 4) : packed;
	}

	/** Client tick (after the entities moved): gathers the lights and rebuilds the chunks whose light changed. */
	public static void tick(MinecraftClient c) {
		if (c.world == null || c.player == null || !Tune.on("light.world")) {
			if (sources.length > 0 || !baked.isEmpty()) clearAndRebuild(c);
			flashes.clear();
			return;
		}
		double S = Damage.SCALE_BLOCKS, rs = Tune.get("light.rangeScale"), gain = Tune.get("light.gain");
		skyFactor = 1 - c.world.getAmbientDarkness() / 11f;
		tintStrength = (float) Tune.get("light.tint");
		float top = (float) Math.max(Tune.get("light.colorR"), Math.max(Tune.get("light.colorG"), Tune.get("light.colorB")));
		if (top > 0) { tintR = (float) Tune.get("light.colorR") / top; tintG = (float) Tune.get("light.colorG") / top; tintB = (float) Tune.get("light.colorB") / top; }
		Vec3d cam = c.gameRenderer.getCamera().getPos();
		record Cand(long key, Src src, double dist) {}
		List<Cand> cands = new ArrayList<>();
		boolean rockets = Tune.on("light.rocket"), grenades = Tune.on("light.grenade");
		for (Entity e : c.world.getEntities()) {
			if (!(e instanceof UKProjectileEntity pe) || e.isRemoved()) continue;
			ProjectileKind k = pe.kind();
			if (!(k == ProjectileKind.ROCKET && rockets) && !(k == ProjectileKind.CORE && grenades)) continue;
			Vec3d p = e.getPos();
			cands.add(new Cand(e.getId(), new Src(p.x, p.y + e.getHeight() / 2, p.z, 10 * S * rs, 10 * gain), p.distanceTo(cam)));
		}
		long now = System.nanoTime();
		for (var it = flashes.iterator(); it.hasNext(); ) {
			Flash f = it.next();
			double t = (now - f.startNanos) / 1e9, range = f.range - f.shrink * t;
			if (range <= 0) { it.remove(); continue; }
			cands.add(new Cand(-f.id, new Src(f.pos.x, f.pos.y, f.pos.z, range * S * rs, f.intensity * gain), f.pos.distanceTo(cam)));
		}
		cands.sort((a, b) -> Double.compare(a.dist, b.dist));
		int max = (int) Tune.get("light.maxSources");
		if (cands.size() > max) cands = cands.subList(0, Math.max(0, max));
		Src[] next = new Src[cands.size()];
		Map<Long, int[]> nowBaked = new HashMap<>();
		for (int i = 0; i < next.length; i++) {
			Src s = cands.get(i).src;
			next[i] = s;
			int r = (int) Math.ceil(s.range);
			nowBaked.put(cands.get(i).key, new int[] {(int) Math.floor(s.x) - r, (int) Math.floor(s.y) - r, (int) Math.floor(s.z) - r,
				(int) Math.floor(s.x) + r, (int) Math.floor(s.y) + r, (int) Math.floor(s.z) + r, (int) Math.round(s.intensity * 4)});
		}
		sources = next;
		// rebuild where a light left, arrived or changed
		for (var e : baked.entrySet()) {
			int[] now2 = nowBaked.get(e.getKey());
			if (now2 == null || !java.util.Arrays.equals(now2, e.getValue())) rebuild(c, e.getValue());
		}
		for (var e : nowBaked.entrySet()) {
			int[] was = baked.get(e.getKey());
			if (was == null || !java.util.Arrays.equals(was, e.getValue())) rebuild(c, e.getValue());
		}
		baked = nowBaked;
	}

	private static void clearAndRebuild(MinecraftClient c) {
		sources = new Src[0];
		if (c.worldRenderer != null) for (int[] b : baked.values()) rebuild(c, b);
		baked = new HashMap<>();
	}

	public static void clear() {
		sources = new Src[0];
		baked = new HashMap<>();
		flashes.clear();
	}

	private static void rebuild(MinecraftClient c, int[] b) {
		if (c.worldRenderer == null) return;
		c.worldRenderer.scheduleBlockRenders(b[0], b[1], b[2], b[3], b[4], b[5]);
	}
}
