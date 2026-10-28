package com.ultracraft.client.weapon;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.client.v1.V1;
import com.ultracraft.net.ExplosionPayload;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Explosion.cs on screen: the Explosion prefab's Sphere_8 (exported as fx_explosion) grows 0.05 × speed per
 * physics tick from a 12 × scale root until its radius passes `maxSize`, white for the first 0.1 s, then the
 * material colour, then fades 2/s. Camera shake 1.5 within 3 × maxSize.
 */
public final class ExplosionFx {
	private static final class Fx {
		final Vec3d pos;
		final float maxSize, speed;
		final int kind;
		float time, scale = 1f, alpha = 1f;
		boolean fading;
		/** centred in water: drawn before the water (not writing depth), which then lies over it */
		final boolean inWater;
		Fx(ExplosionPayload m) {
			pos = m.pos();
			var w = MinecraftClient.getInstance().world;
			inWater = w != null && w.getFluidState(net.minecraft.util.math.BlockPos.ofFloored(pos)).isIn(net.minecraft.registry.tag.FluidTags.WATER);
			maxSize = m.maxSize();
			kind = m.kind() & 7;
			speed = kind == 1 ? 1.75f : kind == 2 ? 1.5f : kind == 3 || kind == 4 ? 2.5f : 1f;
		}
	}

	private static final List<Fx> list = new ArrayList<>();
	private static RigInstance rig;
	private static long lastNanos;
	private static final double SHOW = Tune.def("fx.explosions", 1, "1 = draw explosion spheres");
	private static final double SIZE = Tune.def("fx.explosionScale", 1.0, "multiplier on the explosion sphere's visual size (1 = the prefab's Sphere_8 at its scale)");

	private ExplosionFx() {}

	public static void add(ExplosionPayload m) {
		list.add(new Fx(m));
		MinecraftClient c = MinecraftClient.getInstance();
		// Explosion prefabs' AudioSources: Explosion 1, Explosion Super → Explosion 2, Rocket Harmless → Explosion Harmless (RandomPitch)
		boolean harmless = (m.kind() & 8) != 0;
		int k = m.kind() & 7;
		// (Explosion Hammer Weak: Explosion Wave at 0.5, RandomPitch +-0.25)
		if (k == 4) UKSound.at(m.pos(), "uk.impacts.explosion_wave", 0.5f, UKSound.range(0.75f, 1.25f));
		else UKSound.at(m.pos(), k == 3 ? "uk.impacts.explosion_wave" : harmless ? "uk.impacts.explosion_harmless" : k == 1 ? "uk.impacts.explosion_2" : "uk.impacts.explosion_1", 1f, UKSound.range(0.9f, 1.1f));
		if (c.player != null) {
			double d = c.player.getPos().distanceTo(m.pos()) / Damage.SCALE_BLOCKS;
			if (d < 3 * m.maxSize()) V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 1.5);
			else if (d < 85) V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, (1.5 - (d - 20) / 65 * 1.5) / 6 * m.maxSize());
		}
	}

	/** 0 see-through, no depth; 1 emissive (a pack's glow), no depth; 2 emissive writing depth; 3 the plain translucent entity, writing depth. */
	private static java.util.function.Function<net.minecraft.util.Identifier, net.minecraft.client.render.RenderLayer> layer(int l) {
		return switch (l) {
			case 1 -> net.minecraft.client.render.RenderLayer::getEntityTranslucentEmissive;
			case 2 -> com.ultracraft.client.render.UkLayers::emissiveDepth;
			case 3 -> net.minecraft.client.render.RenderLayer::getEntityTranslucent;
			default -> com.ultracraft.client.render.UkLayers::handTranslucent;
		};
	}

	static {
		Tune.def("fx.expShaderEarly", -1, "shaders: layer of a blast in water seen from out of it (-1 auto; 0 see-through no depth, 1 glow no depth, 2 glow + depth, 3 translucent + depth)");
		Tune.def("fx.expShaderLate", -1, "shaders: layer of a blast out of the water (-1 auto = 3; see fx.expShaderEarly)");
		Tune.def("fx.expShaderTwice", 1, "shaders: a blast in water is drawn again after the water with depth, so the pack's clouds stay behind its part above the surface");
		Tune.def("fx.hammerWave", 1, "Impact Hammer pump blast brightness, x the game's 0.18 grey shockwave (by eye)");
		Tune.def("fx.expShaderUnder", 0, "shaders: layer of a blast while the camera is under water (0: writing depth there showed through the water fog)");
	}

	public static void clear() {
		list.clear();
	}

	/**
	 * Two moments: a blast centred in water, seen from out of it, before the see-through blocks and without writing depth
	 * (the water is laid over it like over anything under its surface); any other after them, writing depth (shader-pack
	 * clouds stay off it). Drawn after the water with depth, one under its surface was not seen; drawn before it with depth,
	 * its fading sphere cut the water away behind it and the sea floor showed dry through it.
	 */
	public static void onRenderLevel(RenderLevelStageEvent e) {
		boolean early = e.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES;
		// (without a shader pack the late draw comes after the weather, not writing depth: vanilla draws its clouds after
		// the particles, and the fading sphere's depth hid every cloud behind it until it was gone)
		boolean shaders = com.ultracraft.client.render.UkLayers.shadersOn();
		RenderLevelStageEvent.Stage late = shaders ? RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS : RenderLevelStageEvent.Stage.AFTER_WEATHER;
		if ((!early && e.getStage() != late) || list.isEmpty() || !Tune.on("fx.explosions")) return;
		// (no shadow: the game's explosions are see-through and cast none - in a shader pack's shadow pass the sphere
		// cast a solid one that stayed while it faded and went all at once)
		if (com.ultracraft.client.render.UkLayers.shadowPass()) return;
		float dt = 0;
		if (early) {
			// (the clock runs once a frame, in the first of the two)
			long now = System.nanoTime();
			dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
			if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
			lastNanos = now;
		}
		boolean camInWater = com.ultracraft.client.v1.UnderwaterFx.submerged();
		if (rig == null) {
			UkRig r = UkAssets.rig("fx_explosion");
			if (r == null) return;
			rig = new RigInstance("fx_explosion", r);
			rig.translucent = true;
			for (int i = 1; i < r.meshes.size(); i++) rig.hiddenMeshes.add(i); // only Sphere_8; the shockwave sphere is a helper
		}
		MinecraftClient c = MinecraftClient.getInstance();
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		MatrixStack ms = com.ultracraft.client.render.UkLayers.worldStack(e);
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		for (Iterator<Fx> it = list.iterator(); it.hasNext(); ) {
			Fx f = it.next();
			f.time += dt;
			// FixedUpdate at 50 Hz: localScale += 0.05 * speed; radius = 12 * scale * 0.2236
			f.scale += 0.05f * f.speed * dt * 50f;
			float radius = 12f * f.scale * 0.2236f;
			if (!f.fading && radius > f.maxSize) f.fading = true;
			if (f.fading) {
				f.alpha -= 2f * dt;
				f.scale += 0.05f * f.speed / 4f * dt * 50f;
			}
			if (f.alpha <= 0) { it.remove(); continue; }
			boolean drawnEarly = f.inWater && !camInWater;
			// (under a shader pack a blast in water is drawn again after the water, writing depth: its part above the surface
			// then stands in front of the pack's clouds, which were laid over the first, depth-less draw; its part under the
			// surface fails that second draw against the water in front of it, keeping the first, submerged look)
			boolean again = drawnEarly && !early && com.ultracraft.client.render.UkLayers.shadersOn() && Tune.on("fx.expShaderTwice");
			if (drawnEarly != early && !again) continue;
			rig.layerOverride = early || !shaders ? com.ultracraft.client.render.UkLayers::handTranslucent : null;
			// under a shader pack the layers are live while it is worked out (fx.expShader*: -1 = the above)
			if (com.ultracraft.client.render.UkLayers.shadersOn() && !again) {
				int l = (int) Tune.get(camInWater ? "fx.expShaderUnder" : early ? "fx.expShaderEarly" : "fx.expShaderLate");
				if (l >= 0) rig.layerOverride = layer(l);
			}
			ms.push();
			ms.translate(f.pos.x - camPos.x, f.pos.y - camPos.y, f.pos.z - camPos.z);
			float s = (float) (Damage.SCALE_BLOCKS * f.scale * Tune.get("fx.explosionScale")); // the rig root already carries the prefab's x12
			ms.scale(s, s, s);
			boolean white = f.time < 0.1f && f.kind != 4;
			if (f.kind == 4) {
				// Explosion Hammer Weak: ExplosionShockwave 3 (shockwavetex at 0.18 grey, added on), fading out with the sphere
				rig.layerOverride = com.ultracraft.client.render.UkLayers::glow;
				rig.setAllMeshTexture("shockwavetex");
				float k = (float) (0.18 * Tune.get("fx.hammerWave"));
				rig.tintR = rig.tintG = rig.tintB = Math.min(1f, k);
				rig.tintA = Math.max(0, Math.min(1, f.alpha));
				rig.pose(new Matrix4f());
				rig.render(ms, buffers, 0xF000F0, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
				rig.setAllMeshTexture(null);
				ms.pop();
				continue;
			}
			if (white) { rig.tintR = rig.tintG = rig.tintB = 1f; }
			else if (f.kind == 1) { rig.tintR = 1f; rig.tintG = 0.15f; rig.tintB = 0.1f; }
			else if (f.kind == 3) { rig.tintR = 1f; rig.tintG = 0.35f; rig.tintB = 0.3f; }
			else { rig.tintR = 1f; rig.tintG = 0.55f; rig.tintB = 0.1f; }
			rig.tintA = 0.55f * Math.max(0, Math.min(1, f.alpha));
			rig.pose(new Matrix4f());
			rig.render(ms, buffers, 0xF000F0, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
			ms.pop();
		}
		buffers.draw();
	}
}
