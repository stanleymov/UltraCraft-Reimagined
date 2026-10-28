package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.block.GrappleOrbBlockEntity;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.render.UkLayers;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/**
 * GrapplePoint.prefab as the game builds it (all in its units, mirrored into Minecraft's at the end):
 * <ul>
 * <li>Core (Spin (0.1, 0.25, 0)) holding the outer orb, Sphere_12 (1) in Bonus 4 (0, 0.75, 0);</li>
 * <li>Spinners (AlwaysLookAtCamera): two ProBuilder arches in Metal Decoration 1B (floordecoration1b), scale
 * (1.125, 1.125, 1) and (1.5, 1.5, 1), spinning about -y / +y;</li>
 * <li>LookAtCamera (AlwaysLookAtCamera): a Particle System 2.5 u behind (RageEffect 2: a ring every 0.5 s, growing to
 * 5 u over its 5 s life, fading over the last tenth, (0.58, 1, 0, 0.75), additive), Square (the glow 2 sprite x 15,
 * (0.25, 0.75, 0, 0.5)) and the inner orb Sphere_12 in Bonus 5 (0.65, 0.94, 0.34), spinning about z.</li>
 * </ul>
 * HookPoint.Update: with V1 within 5 u (and not hooked) the outer orb shrinks to 2.5 and the inner to 0; hooked, the
 * outer goes to 0 and the inner back to 3.5; otherwise 5 and 3.5 - Vector3.MoveTowards at 50/s. Every Spin runs at
 * 100 deg/s, 450 while hooked. Hooked() spawns HookPointGrab: RageEffectWhite in (0.404, 1, 0), its scale from 1 up
 * 80/s and its alpha down 5/s (ScaleNFade). The AudioSource hums CheckpointAmbiance (loop, volume 0.35, 35 u, linear)
 * at pitch 0.75 hooked, else max(0.5, outer / 5) / 2. Bonus 4/5 are transparent with a yellow fresnel rim
 * (_FresnelColor (1, 0.87, 0), strength 1).
 */
public final class GrappleOrbRenderer implements BlockEntityRenderer<GrappleOrbBlockEntity> {
	private static final Identifier BONUS = tex("bonuswhite"), ARCH = tex("floordecoration1b"), GLOW = tex("glow_2"), RING = tex("rageeffectwhite");
	private static final Vector3f CORE_AXIS = new Vector3f(0.1f, 0.25f, 0).normalize();
	private static final Quaternionf OUTER_BIND = new Quaternionf(-0.5f, 0.5f, 0.5f, 0.5f), INNER_BIND = new Quaternionf(0.70710677f, 0, 0, 0.70710677f);
	private static final float SQRT3 = (float) Math.sqrt(3);

	static {
		Tune.def("grapple.scale", Damage.SCALE_BLOCKS, "blocks per ULTRAKILL unit for the grapple orb model (from the game: 1.8 / 3.5, V1's height to Steve's)");
		Tune.def("grapple.itemScale", 0.24, "blocks per unit for the orb drawn as an item (by eye: the arches fill the slot)");
		Tune.def("grapple.fresnelPow", 2, "exponent of the orbs' fresnel rim (by eye: the shader's own falloff is compiled)");
		Tune.def("grapple.fresnelMix", 1, "strength of the yellow fresnel rim (from the game: _FresnelStrength 1)");
		Tune.def("grapple.hum", 0, "1 = the orb hums (the game's CheckpointAmbiance loop; off by the user's choice: annoying)");
		Tune.def("grapple.humVolume", 0.35, "the orb's hum volume (from the game: the AudioSource's 0.35)");
	}

	public GrappleOrbRenderer(BlockEntityRendererFactory.Context ctx) {}

	private static Identifier tex(String name) {
		return new Identifier(UltraCraft.MOD_ID, "textures/uk/" + name + ".png");
	}

	/** HookPoint.Update's scales and the Spins, from the time since the last call (frame or tick, whichever comes). */
	static void animate(GrappleOrbBlockEntity be, boolean hooked, boolean near) {
		long now = System.nanoTime();
		float dt = be.lastFrameNanos == 0 ? 0 : Math.min(0.1f, (now - be.lastFrameNanos) / 1e9f);
		be.lastFrameNanos = now;
		float outerT = hooked ? 0 : near ? 2.5f : 5, innerT = hooked || !near ? 3.5f : 0;
		// MoveTowards on the whole (s, s, s) vector: 50 u/s along its length, 50 / sqrt 3 on each axis
		float step = dt * 50 / SQRT3;
		be.outerScale = towards(be.outerScale, outerT, step);
		be.innerScale = towards(be.innerScale, innerT, step);
		float spin = (hooked ? 450 : 100) * dt;
		be.coreSpin = (be.coreSpin + spin) % 360;
		be.arch1Spin = (be.arch1Spin + spin) % 360;
		be.arch2Spin = (be.arch2Spin + spin) % 360;
		be.innerSpin = (be.innerSpin + spin) % 360;
	}

	private static float towards(float v, float t, float step) {
		return Math.abs(t - v) <= step ? t : v + Math.signum(t - v) * step;
	}

	private static boolean near(MinecraftClient mc, Vec3d center) {
		return mc.player != null && mc.player.getEyePos().distanceTo(center) / Damage.SCALE_BLOCKS < 5;
	}

	private static final Set<String> humming = new HashSet<>();

	/** Every client tick: the hum of each loaded orb within its 35 u, with HookPoint's pitch (the octave-down copy at twice it). */
	public static void tick(MinecraftClient mc) {
		Set<String> keep = new HashSet<>();
		if (mc.player != null && mc.world != null && Tune.on("grapple.hum")) {
			for (GrappleOrbBlockEntity be : GrappleOrbBlockEntity.CLIENT.toArray(new GrappleOrbBlockEntity[0])) {
				if (be.isRemoved() || be.getWorld() != mc.world) continue;
				Vec3d c = Vec3d.ofCenter(be.getPos());
				if (c.distanceTo(mc.player.getEyePos()) > 35 * Damage.SCALE_BLOCKS + 1) continue;
				boolean hooked = WhiplashClient.INSTANCE.hooking(be.getPos());
				animate(be, hooked, near(mc, c));
				float pitch = hooked ? 0.75f : Math.max(0.5f, be.outerScale / 5) / 2;
				String key = "grapple.hum." + be.getPos().asLong();
				keep.add(key);
				UKSound.loopAt(key, c, "uk.environment.checkpointambiance_d2", Tune.getF("grapple.humVolume"), pitch * 2);
			}
		}
		for (String k : humming) if (!keep.contains(k)) UKSound.stop(k);
		humming.clear();
		humming.addAll(keep);
	}

	@Override
	public void render(GrappleOrbBlockEntity be, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light, int overlay) {
		MinecraftClient mc = MinecraftClient.getInstance();
		Vec3d c = Vec3d.ofCenter(be.getPos());
		boolean hooked = WhiplashClient.INSTANCE.hooking(be.getPos());
		animate(be, hooked, near(mc, c));
		Vec3d toCam = mc.gameRenderer.getCamera().getPos().subtract(c);
		ms.push();
		ms.translate(0.5, 0.5, 0.5);
		draw(ms, buffers, light, be, new Vector3f((float) toCam.x, (float) toCam.y, (float) -toCam.z), Tune.getF("grapple.scale"),
			(System.nanoTime() - be.grabbedNanos) / 1e9f, true);
		ms.pop();
	}

	/**
	 * The orb at the stack's origin. `camU`: toward the camera in the game's axes (z mirrored), which the Spinners and
	 * LookAtCamera face; `k`: blocks per unit; `grabAge`: seconds since Hooked(); `extras`: the glow, rings and flash.
	 */
	static void draw(MatrixStack ms, VertexConsumerProvider buffers, int light, GrappleOrbBlockEntity be, Vector3f camU, float k, float grabAge, boolean extras) {
		UkRig rig = UkAssets.rig("grapple_point");
		if (rig == null) return;
		UkRig.Mesh outer = mesh(rig, "Sphere_12 (1)"), inner = mesh(rig, "Sphere_12"), arch1 = mesh(rig, "Arch (1)"), arch2 = mesh(rig, "Arch (2)");
		Matrix4f look = lookAt(camU);
		Vector3f view = new Vector3f(look.m20(), look.m21(), look.m22());
		Matrix4f pm = ms.peek().getPositionMatrix();
		Matrix3f nm = ms.peek().getNormalMatrix();
		int full = 0xF000F0;
		// the arches first: they write depth, so the see-through parts after them hide behind them and not the other way
		VertexConsumer av = buffers.getBuffer(UkLayers.litSolid(ARCH));
		if (arch1 != null) emit(av, pm, nm, arch1, new Matrix4f(look).translate(0, 0, -0.25f).rotate(rad(be.arch1Spin), 0, -1, 0).scale(1.125f, 1.125f, 1), k, null, light, view, false);
		if (arch2 != null) emit(av, pm, nm, arch2, new Matrix4f(look).translate(0, 0, -0.25f).rotate(rad(be.arch2Spin), 0, 1, 0).scale(1.5f, 1.5f, 1), k, null, light, view, false);
		if (extras) rings(buffers.getBuffer(UkLayers.glowSmooth(RING)), pm, nm, new Matrix4f(look).translate(0, 0, -2.5f), k);
		// the transparent queue back to front: outer orb (centre), the glow square (centre), the inner orb (0.1 u nearer)
		VertexConsumer sv = buffers.getBuffer(UkLayers.solidSmooth(BONUS));
		if (outer != null && be.outerScale > 0.001f)
			emit(sv, pm, nm, outer, new Matrix4f().rotate(new Quaternionf().rotationAxis(rad(be.coreSpin), CORE_AXIS)).rotate(OUTER_BIND).scale(be.outerScale), k, new float[] {0, 0.75f, 0}, full, view, true);
		if (extras) quad(buffers.getBuffer(UkLayers.solidSmooth(GLOW)), pm, nm, new Matrix4f(look), 0.32f * 15, k, 0.25f, 0.75f, 0, 0.5f);
		if (inner != null && be.innerScale > 0.001f)
			emit(buffers.getBuffer(UkLayers.solidSmooth(BONUS)), pm, nm, inner, new Matrix4f(look).translate(0, 0, 0.1f).rotate(INNER_BIND).rotate(rad(be.innerSpin), 0, 0, 1).scale(be.innerScale), k,
				new float[] {0.6514581f, 0.9433962f, 0.3426486f}, full, view, true);
		float alpha = 1 - 5 * grabAge;
		if (extras && grabAge >= 0 && alpha > 0)
			quad(buffers.getBuffer(UkLayers.solidSmooth(RING)), pm, nm, new Matrix4f(look), 0.64f * (1 + 80 * grabAge), k, 0.404f, 1, 0, alpha);
	}

	private static float rad(float deg) {
		return (float) Math.toRadians(deg);
	}

	private static UkRig.Mesh mesh(UkRig rig, String name) {
		for (UkRig.Mesh m : rig.meshes) if (m.name.equals(name)) return m;
		return null;
	}

	/** Quaternion.LookRotation(toward, up) as a matrix: z toward the camera, x = up x z, y = z x x. */
	private static Matrix4f lookAt(Vector3f toward) {
		Vector3f z = new Vector3f(toward);
		if (z.lengthSquared() < 1e-8f) z.set(0, 0, 1);
		z.normalize();
		Vector3f x = new Vector3f(0, 1, 0).cross(z);
		if (x.lengthSquared() < 1e-8f) x.set(1, 0, 0);
		x.normalize();
		Vector3f y = new Vector3f(z).cross(x);
		return new Matrix4f(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, 0, 0, 0, 1);
	}

	/**
	 * One mesh through `m` (game units), mirrored into Minecraft's axes and scaled by k. A `color` makes it a Bonus orb:
	 * unlit, back faces dropped (it writes no depth) and a fresnel rim added toward its edge.
	 */
	private static void emit(VertexConsumer vc, Matrix4f pm, Matrix3f nm, UkRig.Mesh mesh, Matrix4f m, float k, float[] color, int light, Vector3f view, boolean orb) {
		int n = mesh.vertexCount;
		float[] p = new float[n * 3], nr = new float[n * 3];
		Vector3f v = new Vector3f(), nv = new Vector3f();
		for (int i = 0; i < n; i++) {
			m.transformPosition(v.set(mesh.vertices[i * 3], mesh.vertices[i * 3 + 1], mesh.vertices[i * 3 + 2]));
			p[i * 3] = v.x; p[i * 3 + 1] = v.y; p[i * 3 + 2] = v.z;
			if (mesh.normals.length >= n * 3) m.transformDirection(nv.set(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2]));
			else nv.set(v);
			if (nv.lengthSquared() > 1e-12f) nv.normalize();
			nr[i * 3] = nv.x; nr[i * 3 + 1] = nv.y; nr[i * 3 + 2] = nv.z;
		}
		float pow = Tune.getF("grapple.fresnelPow"), mix = Tune.getF("grapple.fresnelMix");
		int[] idx = mesh.indices;
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int a = idx[t], b = idx[t + 1], c = idx[t + 2];
			// a sphere's far side (the game culls back faces; written without depth they would paint over the front)
			if (orb && (nr[a * 3] + nr[b * 3] + nr[c * 3]) * view.x + (nr[a * 3 + 1] + nr[b * 3 + 1] + nr[c * 3 + 1]) * view.y
				+ (nr[a * 3 + 2] + nr[b * 3 + 2] + nr[c * 3 + 2]) * view.z < 0) continue;
			vertex(vc, pm, nm, mesh, p, nr, a, k, color, light, view, pow, mix);
			vertex(vc, pm, nm, mesh, p, nr, b, k, color, light, view, pow, mix);
			vertex(vc, pm, nm, mesh, p, nr, c, k, color, light, view, pow, mix);
			vertex(vc, pm, nm, mesh, p, nr, c, k, color, light, view, pow, mix);
		}
	}

	private static void vertex(VertexConsumer vc, Matrix4f pm, Matrix3f nm, UkRig.Mesh mesh, float[] p, float[] nr, int i, float k, float[] color, int light, Vector3f view, float pow, float mix) {
		float r = 1, g = 1, b = 1;
		if (color != null) {
			float f = (float) Math.pow(1 - Math.min(1, Math.abs(nr[i * 3] * view.x + nr[i * 3 + 1] * view.y + nr[i * 3 + 2] * view.z)), pow) * mix;
			r = Math.min(1, color[0] + f);
			g = Math.min(1, color[1] + 0.868f * f);
			b = Math.min(1, color[2]);
		}
		float u = mesh.uvs.length > i * 2 + 1 ? mesh.uvs[i * 2] : 0, w = mesh.uvs.length > i * 2 + 1 ? 1 - mesh.uvs[i * 2 + 1] : 0;
		vc.vertex(pm, p[i * 3] * k, p[i * 3 + 1] * k, -p[i * 3 + 2] * k).color(r, g, b, 1f).texture(u, w).overlay(OverlayTexture.DEFAULT_UV).light(light)
			.normal(nm, nr[i * 3], nr[i * 3 + 1], -nr[i * 3 + 2]).next();
	}

	/** A sprite quad `size` units wide in the plane of `m` (its z toward the camera), full bright. */
	private static void quad(VertexConsumer vc, Matrix4f pm, Matrix3f nm, Matrix4f m, float size, float k, float r, float g, float b, float a) {
		float h = size / 2;
		float[][] corners = {{-h, -h, 0, 1}, {h, -h, 1, 1}, {h, h, 1, 0}, {-h, h, 0, 0}};
		Vector3f v = new Vector3f();
		for (float[] q : corners) {
			m.transformPosition(v.set(q[0], q[1], 0));
			vc.vertex(pm, v.x * k, v.y * k, -v.z * k).color(r, g, b, a).texture(q[2], q[3]).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(nm, 0, 1, 0).next();
		}
	}

	/** The Particle System's rings: one born every 0.5 s, 0 -> 5 u over 5 s, alpha 0.75 until 90% of its life, then out. */
	private static void rings(VertexConsumer vc, Matrix4f pm, Matrix3f nm, Matrix4f m, float k) {
		double t = System.nanoTime() / 1e9;
		float young = (float) (t % 0.5);
		for (int j = 0; j < 10; j++) {
			float age = young + 0.5f * j, life = age / 5;
			if (life >= 1) continue;
			float a = 0.75f * (life < 0.9f ? 1 : (1 - life) / 0.1f);
			quad(vc, pm, nm, m, 5 * life, k, 0.58f, 1, 0, a);
		}
	}

	@Override
	public boolean rendersOutsideBoundingBox(GrappleOrbBlockEntity be) {
		return true;
	}
}
