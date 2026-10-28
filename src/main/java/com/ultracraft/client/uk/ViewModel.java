package com.ultracraft.client.uk;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.v1.V1;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * V1's first-person view: the fist arm on the left and the current weapon on the right, each an
 * ULTRAKILL rig placed with the game's own transforms (the prefab's position under `Guns` / `Punch`
 * in camera space, scaled to Steve size). Rendered from the RenderHandEvent in Minecraft's view space.
 */
public final class ViewModel {
	public static final Identifier TEXTURES = new Identifier(UltraCraft.MOD_ID, "textures/uk/");
	private static final Map<String, RigInstance> instances = new HashMap<>();
	private static long lastNanos;
	private static final double SCALE = Tune.def("view.scale", 1.0, "multiplier on the Steve scale for first-person rigs");
	private static final double CULL = Tune.def("view.cull", 0, "1 = back-face culling on the rigs (if a rig looks inside-out, flip this)");
	private static final double FULLBRIGHT = Tune.def("view.fullbright", 0, "1 = ignore world light on the rigs (ULTRAKILL's guns are unlit)");

	private ViewModel() {}

	public static RigInstance instance(String rig) {
		return instances.computeIfAbsent(rig, r -> {
			UkRig data = UkAssets.rig(r);
			return data == null ? null : new RigInstance(r, data);
		});
	}

	/** The off-hand gun's own instances of the rigs (a second copy, like ULTRAKILL's dual wield duplicate). */
	private static final Map<String, RigInstance> offInstances = new HashMap<>();

	public static RigInstance offInstance(String rig) {
		return offInstances.computeIfAbsent(rig, r -> {
			UkRig data = UkAssets.rig(r);
			if (data == null) return null;
			RigInstance i = new RigInstance(r, data);
			i.poseKey = "off:" + r;
			return i;
		});
	}

	/** Bumped on every reset: the rig instances are new, so their animation-event listeners must be attached again. */
	public static int generation;

	public static void reset() {
		generation++;
		instances.clear();
		offInstances.clear();
		UkAssets.clear();
	}

	/** Seconds since the last frame, for clip playback. */
	/** The next frameDelta() is 0 (after a held frame). */
	public static void resetClock() {
		lastNanos = System.nanoTime();
	}

	public static float frameDelta() {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1e9f;
		lastNanos = now;
		return paused() ? 0 : Math.min(0.1f, dt);
	}

	/**
	 * Time stands still for the view model while the game is paused or any screen (inventory, menus) is open, and
	 * through the parry freeze, like ULTRAKILL's time scale 0 (menus, TimeController.TrueStop): the sway, the weapon
	 * animations and the effects hold where they are. (The camera shake runs on regardless.)
	 */
	public static boolean paused() {
		MinecraftClient c = MinecraftClient.getInstance();
		return c.isPaused() || c.currentScreen != null || com.ultracraft.client.v1.V1.INSTANCE.frozen();
	}

	private static double gameSeconds;
	private static long gameClockNanos;

	/** A clock that stops whenever paused() (trails age on it, so they hold through a parry freeze). */
	public static double gameSeconds() {
		long now = System.nanoTime();
		if (gameClockNanos != 0 && !paused()) gameSeconds += Math.min(0.25, (now - gameClockNanos) / 1e9);
		gameClockNanos = now;
		return gameSeconds;
	}

	/** The full chain prefabRoot(local) * ... * animatorParent(local) as a single Unity-space matrix. */
	public static Matrix4f mountMatrix(UkRig rig, Matrix4f parentOffset) {
		Matrix4f m = new Matrix4f(parentOffset);
		for (UkRig.Xf x : rig.mount) m.mul(x.matrix());
		return m;
	}

	/**
	 * Draw a rig in view space. `matrices` is Minecraft's hand pose stack (camera at origin, -Z forward,
	 * +Y up, +X right); Unity's camera space is the same but with +Z forward, which the renderer handles
	 * per vertex, so here we only scale Unity units to blocks.
	 */
	public static void draw(RigInstance inst, Matrix4f parentOffset, MatrixStack matrices, VertexConsumerProvider buffers, int light) {
		if (inst == null) return;
		inst.pose(mountMatrix(inst.rig, parentOffset));
		matrices.push();
		float s = (float) (V1.SCALE * Tune.get("view.scale"));
		matrices.scale(s, s, s);
		int l = Tune.on("view.fullbright") ? 0xF000F0 : light;
		lastPose.put(inst.poseKey != null ? inst.poseKey : inst.rigName(), new Object[] {new org.joml.Matrix4f(matrices.peek().getPositionMatrix()), new org.joml.Matrix3f(matrices.peek().getNormalMatrix()),
			new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()), inst});
		inst.render(matrices, buffers, l, OverlayTexture.DEFAULT_UV, TEXTURES, Tune.on("view.cull"));
		matrices.pop();
	}

	/** rig name -> {pose, normal, projection, instance} of its last first-person draw this frame. */
	public static final java.util.Map<String, Object[]> lastPose = new java.util.HashMap<>();

	public static MinecraftClient client() {
		return MinecraftClient.getInstance();
	}
}
