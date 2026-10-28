package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.render.UkLayers;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.net.GasolinePayload;
import com.ultracraft.weapon.Damage;
import com.ultracraft.weapon.Gasoline;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The Firestarter's gasoline as the client sees it: the stains (GasolineStain: the BloodPlane quad, 5.125 u across, one of
 * the five splats of the atlas picked at random and turned at random about the surface normal; the body and its rim
 * coloured here, by eye, where the game's CompositeGasoline shader does it), cut to the blocks they lie on (the game's
 * clipToSurface) - and the fire on a burning voxel (FireSimple: the 60-frame fire1_32 sheet at 12 fps, colour (1, 0.3125, 0)
 * added, facing the camera, root scale 4 shrinking over the 2 s it dies down, with the FireSmall loop at 0.3, pitch 1 +-
 * 0.2). Also tells V1 when it stands on gasoline (NewMovement.CheckForGasoline).
 */
public final class GasolineClient {
	private static final Identifier BODY = new Identifier(UltraCraft.MOD_ID, "textures/uk/gasoline_body.png");
	private static final Identifier RIM = new Identifier(UltraCraft.MOD_ID, "textures/uk/gasoline_rim.png");
	/** fire1_32 with FireMaterial's colour mode baked in (build_assets.bake_fire: + (1, 0.3125, 0)) */
	private static final Identifier FIRE = new Identifier(UltraCraft.MOD_ID, "textures/uk/fire1_32_lit.png");
	/** the Fire prefab's sheet (burning enemies), the same layout */
	private static final Identifier FIRE_64 = new Identifier(UltraCraft.MOD_ID, "textures/uk/fire1_64_lit.png");
	/** the BloodPlane mesh's half extent (0.16015629) x the stain's scale 16 */
	private static final double HALF = 0.16015629 * 16;
	/** cells per side the stain is cut into to keep only what lies on a block (the game clips to the surface) */
	private static final int GRID = 8;
	private static final int FIRE_SOUNDS = 16;

	static {
		Tune.def("gas.bodyR", 0.1206, "gasoline stain body colour, red (from the game: the gasoline drips' colour 0.12, 0.09, 0.18; the stains themselves are coloured in its CompositeGasoline shader)");
		Tune.def("gas.bodyG", 0.0905, "gasoline stain body colour, green (from the game: the drips)");
		Tune.def("gas.bodyB", 0.1792, "gasoline stain body colour, blue (from the game: the drips)");
		Tune.def("gas.bodyA", 0.95, "gasoline stain body opacity (by eye)");
		Tune.def("gas.rimR", 0.8175, "gasoline stain rim colour, red (from the game: the Gasoline material's fresnel colour 0.82, 0, 1)");
		Tune.def("gas.rimG", 0.0, "gasoline stain rim colour, green (from the game: Gasoline fresnel)");
		Tune.def("gas.rimB", 1.0, "gasoline stain rim colour, blue (from the game: Gasoline fresnel)");
		Tune.def("gas.rimA", 0.6, "gasoline stain rim opacity (by eye; 0 = the dark purple body alone)");
		Tune.def("gas.stainSize", 1.0, "gasoline stain size x the game's 5.125 u (from the game: 1)");
		Tune.def("gas.fireScale", 4.0, "gasoline fire size: FireSimple's root scale (from the game: BurningVoxel sets 4; the sprite is then 20.5 u)");
		Tune.def("gas.fireBase", 0.12, "how far into its frame the fire sprite is sunk below the puddle (the flame's lowest pixels are 0.06-0.1 up the frame; by eye: the user saw a gap at 0.086)");
		Tune.def("gas.fireFps", 60, "gasoline fire frames a second (by eye: the user picked 60, five times the game's speed; the Fire32 / Fire64 clips play 60 frames in 5 s = 12)");
		Tune.def("gas.fireSprite", 1, "gasoline fire sprite: 1 = fire1_64 (the Fire prefab, burning enemies; the user's pick, by eye), 0 = fire1_32 (FireSimple, what the game's code uses for gasoline)");
		Tune.def("gas.fireFace", 1, "1 = the gasoline fire faces the camera squarely (by eye: the user's view of the game), 0 = upright, turned about the vertical (AlwaysLookAtCamera, useYAxis off)");
		Tune.def("gas.fireAlphaPlain", 0.6, "gasoline fire opacity without a shader pack (by eye: see-through like it is with shaders)");
		Tune.def("gas.fireAlpha", 1.0, "gasoline fire opacity (from the game: 1)");
		Tune.def("gas.burnTime", 6.0, "gasoline burn time before it dies down, s (from the game: BurningVoxel 6, then 2 s dying down; also read by the server)");
		Tune.def("gas.fireVolume", 0.3, "gasoline fire loop volume (from the game: FireSimple's AudioSource 0.3)");
		Tune.def("gas.oilR", 0.55, "gasolined mob tint, red (by eye: the user's purple; the game darkens it by _OiledAmount)");
		Tune.def("gas.oilG", 0.2, "gasolined mob tint, green (by eye)");
		Tune.def("gas.oilB", 0.85, "gasolined mob tint, blue (by eye)");
		Tune.def("gas.oilStrength", 1.0, "gasolined mob tint at full fuel (5); the game's _OiledAmount = fuel / 5");
		Tune.def("gas.oilMin", 0.5, "gasolined mob tint as soon as it carries any (by eye: one droplet is 0.1 of 5 and barely shows)");
		Tune.def("gas.dropHide", 0.6, "gasoline droplets closer than this to the camera are not drawn, blocks (by eye: hidden under the spray flash at the barrel, seen flying from there)");
		Tune.def("gas.dropR", 0.4, "Firestarter droplet colour, red (by eye: the spray flash's purple; the game: Gasoline material 0.094 grey, purple fresnel)");
		Tune.def("gas.dropG", 0.0, "Firestarter droplet colour, green (by eye)");
		Tune.def("gas.dropB", 0.55, "Firestarter droplet colour, blue (by eye)");
	}

	private static final class Stain {
		final int id, index;
		final Vec3d pos, normal, a, b;
		final long voxel;
		final boolean floor;
		/** which grid cells lie on a block; null: all of them */
		boolean[] mask;

		Stain(int id, Vec3d pos, Vec3d normal, float roll, int index) {
			this.id = id;
			this.pos = pos;
			this.normal = normal;
			this.index = index;
			Vec3d t = Math.abs(normal.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
			Vec3d a0 = normal.crossProduct(t).normalize(), b0 = normal.crossProduct(a0).normalize();
			double r = Math.toRadians(roll), c = Math.cos(r), s = Math.sin(r);
			a = a0.multiply(c).add(b0.multiply(s));
			b = normal.crossProduct(a).normalize();
			int[] v = Gasoline.stainVoxel(pos, normal);
			voxel = Gasoline.key(v[0], v[1], v[2]);
			floor = normal.y > 0.25;
		}
	}

	private static final class Voxel {
		final int x, y, z;
		final List<Stain> stains = new ArrayList<>();
		int floors;
		boolean burning;
		float burnStart, speed = 1, offset, pitch = 1;

		Voxel(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	private static final Map<Long, Voxel> voxels = new HashMap<>();
	private static final Map<Integer, Stain> stains = new HashMap<>();
	private static final Set<String> sounding = new HashSet<>();
	/** entity id -> fuel / 5 on it */
	private static final Map<Integer, Float> oiled = new HashMap<>();
	/** the mob LivingEntityRenderer is drawing (set by its mixin) */
	public static final ThreadLocal<net.minecraft.entity.LivingEntity> drawing = new ThreadLocal<>();
	private static final Random rnd = new Random();
	/** the client's own clock for the fires (stops while the game is paused) */
	private static float clock;

	private GasolineClient() {}

	public static void onPayload(GasolinePayload m) {
		switch (m.op()) {
			case GasolinePayload.STAIN -> {
				Stain s = new Stain(m.id(), m.pos(), m.normal(), m.roll(), m.index());
				ClientWorld w = MinecraftClient.getInstance().world;
				if (w != null) s.mask = clip(w, s);
				stains.put(s.id, s);
				int[] v = Gasoline.stainVoxel(s.pos, s.normal);
				Voxel vox = voxels.computeIfAbsent(s.voxel, k -> new Voxel(v[0], v[1], v[2]));
				vox.stains.add(s);
				if (s.floor) vox.floors++;
			}
			case GasolinePayload.FIRE -> {
				int x = (int) m.pos().x, y = (int) m.pos().y, z = (int) m.pos().z;
				Voxel vox = voxels.computeIfAbsent(Gasoline.key(x, y, z), k -> new Voxel(x, y, z));
				if (!vox.burning) {
					// AnimationSpeedRandomizer (1 +- 0.1) and RandomPitch (1 +- 0.2) on the pooled fire
					vox.speed = 0.9f + rnd.nextFloat() * 0.2f;
					vox.offset = rnd.nextFloat() * 5f;
					vox.pitch = 0.8f + rnd.nextFloat() * 0.4f;
				}
				vox.burning = true;
				vox.burnStart = clock;
			}
			case GasolinePayload.REMOVE -> {
				Voxel vox = voxels.remove(Gasoline.key((int) m.pos().x, (int) m.pos().y, (int) m.pos().z));
				if (vox != null) for (Stain s : vox.stains) stains.remove(s.id);
			}
			case GasolinePayload.OIL -> {
				if (m.roll() <= 0) oiled.remove(m.id());
				else oiled.put(m.id(), m.roll());
			}
			case GasolinePayload.DROP -> {
				Stain s = stains.remove(m.id());
				if (s == null) return;
				Voxel vox = voxels.get(s.voxel);
				if (vox == null) return;
				vox.stains.remove(s);
				if (s.floor) vox.floors--;
				if (vox.stains.isEmpty() && !vox.burning) voxels.remove(s.voxel);
			}
			default -> {}
		}
	}

	/**
	 * The colour a mob is drawn in: white, toward the gasoline purple with the fuel it carries (the game's _OiledAmount;
	 * the purple is by eye, gas.oil*).
	 */
	public static float oilTint(int channel) {
		var e = drawing.get();
		Float k = e == null ? null : oiled.get(e.getId());
		if (k == null) return 1f;
		float t = (float) Math.min(1, k * Tune.get("gas.oilStrength") + (k > 0 ? Tune.get("gas.oilMin") : 0));
		float c = (float) Tune.get(channel == 0 ? "gas.oilR" : channel == 1 ? "gas.oilG" : "gas.oilB");
		return 1 + (c - 1) * t;
	}

	public static void clear() {
		oiled.clear();
		voxels.clear();
		stains.clear();
		for (String k : sounding) UKSound.stop(k);
		sounding.clear();
	}

	/**
	 * NewMovement.CheckForGasoline: gasoline on a floor in the 3 x 3 voxels round V1's feet, that voxel and the one above
	 * (VerticalBox, AnyFloor). `feet` in blocks.
	 */
	public static boolean onGasoline(Vec3d feet) {
		if (voxels.isEmpty()) return false;
		int[] c = Gasoline.voxelOf(feet);
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (int dy = 0; dy < 2; dy++) {
					Voxel v = voxels.get(Gasoline.key(c[0] + dx, c[1] + dy, c[2] + dz));
					if (v != null && v.floors > 0) return true;
				}
		return false;
	}

	/** BurningVoxel.SetSize: 1 while it burns, then down to 0 over the 2 s it dies down. */
	private static float size(Voxel v, float now) {
		if (!v.burning) return 1;
		float ext = now - v.burnStart - (float) Tune.get("gas.burnTime");
		return ext <= 0 ? 1 : Math.max(0, 1 - ext / 2f);
	}

	/** Every client tick: the clock and the fire loops of the nearest burning voxels. */
	public static void tick(MinecraftClient c) {
		if (c.world == null || c.player == null) { if (!voxels.isEmpty()) clear(); return; }
		if (!ViewModel.paused()) clock += 0.05f;
		if (!oiled.isEmpty()) oiled.keySet().removeIf(id -> c.world.getEntityById(id) == null);
		if (voxels.isEmpty() && sounding.isEmpty()) return;
		Vec3d eye = c.player.getEyePos();
		List<Voxel> lit = new ArrayList<>();
		for (Voxel v : voxels.values()) if (v.burning && size(v, clock) > 0) lit.add(v);
		lit.sort((p, q) -> Double.compare(center(p).squaredDistanceTo(eye), center(q).squaredDistanceTo(eye)));
		Set<String> now = new HashSet<>();
		float vol = (float) Tune.get("gas.fireVolume");
		for (int i = 0; i < Math.min(FIRE_SOUNDS, lit.size()); i++) {
			Voxel v = lit.get(i);
			String key = "gas.fire." + v.x + "." + v.y + "." + v.z;
			now.add(key);
			UKSound.loopAt(key, center(v), "uk.environment.firesmall", vol, v.pitch);
		}
		for (String k : sounding) if (!now.contains(k)) UKSound.stop(k);
		sounding.clear();
		sounding.addAll(now);
	}

	/** Where the voxel's gasoline lies: the height of its floor stains (of all its stains when it has none on a floor). */
	private static double floorY(Voxel v) {
		double sum = 0;
		int n = 0;
		for (Stain s : v.stains) if (s.floor) { sum += s.pos.y; n++; }
		if (n == 0) for (Stain s : v.stains) { sum += s.pos.y; n++; }
		return n == 0 ? center(v).y : sum / n;
	}

	private static Vec3d center(Voxel v) {
		return Gasoline.voxelCenter(v.x, v.y, v.z);
	}

	/** The grid cells of the stain with a block right behind them and room in front (null when all are). */
	private static boolean[] clip(ClientWorld w, Stain s) {
		double h = HALF * Damage.SCALE_BLOCKS * Tune.get("gas.stainSize");
		boolean[] m = new boolean[GRID * GRID];
		boolean all = true;
		BlockPos.Mutable bp = new BlockPos.Mutable();
		for (int i = 0; i < GRID; i++)
			for (int j = 0; j < GRID; j++) {
				double x = -h + (i + 0.5) * 2 * h / GRID, y = -h + (j + 0.5) * 2 * h / GRID;
				Vec3d p = s.pos.add(s.a.multiply(x)).add(s.b.multiply(y));
				Vec3d behind = p.subtract(s.normal.multiply(0.05)), front = p.add(s.normal.multiply(0.05));
				bp.set(behind.x, behind.y, behind.z);
				BlockState back = w.getBlockState(bp);
				boolean ok = !back.getCollisionShape(w, bp).isEmpty();
				if (ok) {
					bp.set(front.x, front.y, front.z);
					BlockState fr = w.getBlockState(bp);
					ok = !fr.isOpaqueFullCube(w, bp);
				}
				m[i * GRID + j] = ok;
				all &= ok;
			}
		return all ? null : m;
	}

	/**
	 * The stains with the terrain (after the cut-out blocks: under everything translucent, so a blast or water drawn later
	 * lies over them instead of hiding them), the fire after the entities.
	 */
	public static void onRenderLevel(RenderLevelStageEvent e) {
		boolean stainPass = e.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
		if ((!stainPass && e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) || voxels.isEmpty()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) return;
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		float now = clock + (ViewModel.paused() ? 0 : e.getPartialTick() * 0.05f);
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f mat = ms.peek().getPositionMatrix();
		double far = 96;
		double S = Damage.SCALE_BLOCKS;
		float[] body = {(float) Tune.get("gas.bodyR"), (float) Tune.get("gas.bodyG"), (float) Tune.get("gas.bodyB"), (float) Tune.get("gas.bodyA")};
		float[] rim = {(float) Tune.get("gas.rimR"), (float) Tune.get("gas.rimG"), (float) Tune.get("gas.rimB"), (float) Tune.get("gas.rimA")};
		double half = HALF * S * Tune.get("gas.stainSize");
		// the stains: the body, then its rim over it
		if (stainPass) for (int layer = 0; layer < 2; layer++) {
			VertexConsumer vc = buffers.getBuffer(UkLayers.decal(layer == 0 ? BODY : RIM));
			float[] col = layer == 0 ? body : rim;
			for (Voxel v : voxels.values()) {
				if (center(v).squaredDistanceTo(camPos) > far * far) continue;
				float size = size(v, now);
				if (size <= 0) continue;
				for (Stain s : v.stains) stain(vc, mat, c.world, s, half * size, col, layer);
			}
		}
		if (stainPass) {
			ms.pop();
			buffers.draw();
			return;
		}
		// the fire: FireSimple's sprite standing on the voxel, alpha-blended (FireMaterial: SrcAlpha / OneMinusSrcAlpha),
		// facing the camera (gas.fireFace 1, by eye: the user sees it face them; 0 = the game's AlwaysLookAtCamera with
		// useYAxis off, upright and turned about the vertical)
		double fs = Tune.get("gas.fireScale");
		// (without a shader pack its solid pixels show fully; a pack runs it as a glow, see-through - by eye, the user
		// wants it see-through both ways)
		float fa = (float) Tune.get(UkLayers.shadersOn() ? "gas.fireAlpha" : "gas.fireAlphaPlain");
		// (writing depth: shader-pack clouds are laid wherever depth still says sky, and covered the fire)
		VertexConsumer vc = buffers.getBuffer(UkLayers.emissiveDepth(Tune.on("gas.fireSprite") ? FIRE_64 : FIRE));
		boolean face = Tune.on("gas.fireFace");
		Vec3d fwd = new Vec3d(cam.getHorizontalPlane());
		Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);
		right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
		Vec3d upDir = new Vec3d(0, 1, 0);
		if (face) {
			org.joml.Vector3f cv = cam.getVerticalPlane();
			upDir = new Vec3d(cv.x, cv.y, cv.z).normalize();
			right = new Vec3d(cam.getHorizontalPlane()).crossProduct(upDir).normalize();
		}
		for (Voxel v : voxels.values()) {
			if (!v.burning) continue;
			float size = size(v, now);
			if (size <= 0) continue;
			Vec3d o = center(v);
			if (o.squaredDistanceTo(camPos) > far * far) continue;
			double k = fs * size;
			// the sprite 0.64 u x 8 at the root's scale; its flame starts 0.086 of the way up the frame. Stood on the
			// gasoline (the game hangs it 2.0625 + 2 x scale u over the voxel's grid point, which left it in the air
			// here), so dying down it sinks into the floor from the top
			double hh = 0.32 * 8 * k * S;
			// (the frame's bottom edge on the floor point, the sprite going up along the camera's up when it faces the camera)
			Vec3d base = new Vec3d(o.x, floorY(v), o.z).subtract(upDir.multiply(Tune.get("gas.fireBase") * 2 * hh));
			Vec3d mid = base.add(upDir.multiply(hh));
			Vec3d up = upDir.multiply(hh);
			int frame = (int) Math.floor(((now - v.burnStart) * v.speed + v.offset) * Tune.get("gas.fireFps")) % 60;
			float u0 = (frame % 10) / 10f, v0 = (frame / 10) / 6f, u1 = u0 + 0.1f, v1 = v0 + 1 / 6f;
			Vec3d r = right.multiply(hh);
			fv(vc, mat, mid.subtract(r).subtract(up), u0, v1, fa);
			fv(vc, mat, mid.add(r).subtract(up), u1, v1, fa);
			fv(vc, mat, mid.add(r).add(up), u1, v0, fa);
			fv(vc, mat, mid.subtract(r).add(up), u0, v0, fa);
		}
		ms.pop();
		buffers.draw();
	}

	private static void stain(VertexConsumer vc, Matrix4f mat, ClientWorld w, Stain s, double h, float[] col, int layer) {
		// lifted off the surface a little, each stain a hair more than the last so overlapping ones do not flicker
		Vec3d p = s.pos.add(s.normal.multiply(0.004));
		int light = WorldRenderer.getLightmapCoordinates(w, BlockPos.ofFloored(s.pos.add(s.normal.multiply(0.3))));
		float cu0 = s.index / 5f, cw = 1 / 5f;
		if (s.mask == null) {
			quad(vc, mat, p, s, -h, -h, h, h, cu0, cw, 0, 0, 1, 1, col, light);
			return;
		}
		double step = 2 * h / GRID;
		for (int i = 0; i < GRID; i++)
			for (int j = 0; j < GRID; j++) {
				if (!s.mask[i * GRID + j]) continue;
				quad(vc, mat, p, s, -h + i * step, -h + j * step, -h + (i + 1) * step, -h + (j + 1) * step, cu0, cw,
					i / (float) GRID, j / (float) GRID, (i + 1) / (float) GRID, (j + 1) / (float) GRID, col, light);
			}
	}

	private static void quad(VertexConsumer vc, Matrix4f mat, Vec3d p, Stain s, double x0, double y0, double x1, double y1,
			float cu0, float cw, float s0, float t0, float s1, float t1, float[] col, int light) {
		sv(vc, mat, p.add(s.a.multiply(x0)).add(s.b.multiply(y0)), cu0 + s0 * cw, 1 - t0, col, light, s.normal);
		sv(vc, mat, p.add(s.a.multiply(x1)).add(s.b.multiply(y0)), cu0 + s1 * cw, 1 - t0, col, light, s.normal);
		sv(vc, mat, p.add(s.a.multiply(x1)).add(s.b.multiply(y1)), cu0 + s1 * cw, 1 - t1, col, light, s.normal);
		sv(vc, mat, p.add(s.a.multiply(x0)).add(s.b.multiply(y1)), cu0 + s0 * cw, 1 - t1, col, light, s.normal);
	}

	private static void sv(VertexConsumer vc, Matrix4f m, Vec3d p, float u, float v, float[] col, int light, Vec3d n) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(col[0], col[1], col[2], col[3]).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(light).normal((float) n.x, (float) n.y, (float) n.z).next();
	}

	private static void fv(VertexConsumer vc, Matrix4f m, Vec3d p, float u, float v, float a) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(1f, 1f, 1f, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
