package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UltraCraftClient;
import com.ultracraft.client.style.StyleState;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.v1.V1;
import com.ultracraft.item.WeaponKind;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The small displays and effects the weapon prefabs carry (world-space canvases, the Revolver's battery
 * monitor, the Piercer's charge glow, the Railcannon's pips, muzzle flashes), drawn in the bone frames of the
 * posed first-person rig. Layouts are the prefabs' RectTransforms (docs/ULTRAKILL_SOURCE.md → weapon displays).
 */
public final class GunDisplays {
	private static final double ON = Tune.def("view.displays", 1, "0 = no on-gun displays / muzzle flashes");
	static {
		Tune.def("view.dotPos", 3, "Marksman dot centres at +- this on the 15 unit canvas (the game: 2 in from each corner -> 3)");
		Tune.def("view.flashDepth", 0.8, "muzzle flash distance from the eye, blocks, for how far it moves with the camera shake (by eye)");
		Tune.def("view.flashDebug", 0, "1 = log the behind-the-gun muzzle flash each frame");
		Tune.def("view.flashPush", 0, "muzzle flash pushed this far back behind its point for the behind-the-gun test (view units; same size on screen)");
		Tune.def("view.altFlashPush", 1.0, "the same for the alternate revolver, whose shoot point is at the muzzle tip");
		Tune.def("view.shotgunTintR", 1, "shotgun display meter colour: red multiplier on the variation colour");
		Tune.def("view.shotgunTintG", 1, "shotgun display meter colour: green multiplier");
		Tune.def("view.shotgunTintB", 1, "shotgun display meter colour: blue multiplier");
		Tune.def("view.shotgunCornerPixel", 1, "1 = the meter's rounded ends in pixel steps (like the sprite), 0 = a smooth curve");
		Tune.def("view.sawEquipHide", 0.2, "Sawed-On: the chainsaw is hidden for this long at the start of Equip, s (by eye; it swung through the camera)");
		Tune.def("view.shotgunCorner", 3, "shotgun display meter: rounded corner size at its two ends, canvas units (the sprite's border: 2)");
		Tune.def("view.coreHeatR", 0.55, "Core Eject's flung cores: red");
		Tune.def("view.coreHeatG", 0.04, "Core Eject's flung cores: green");
		Tune.def("view.coreHeatB", 0.02, "Core Eject's flung cores: blue");
		Tune.def("view.coreSteamCount", 2, "steam puffs per core per tick while the Core Eject's cores fly");
		Tune.def("view.pumpYellowR", 1, "Pump Charge meter at one charge: red");
		Tune.def("view.pumpYellowG", 0.85, "Pump Charge meter at one charge: green");
		Tune.def("view.pumpYellowB", 0.05, "Pump Charge meter at one charge: blue");
		Tune.def("view.shotgunTickY", 0, "shotgun display dividers: offset across the meter, canvas units");
		Tune.def("view.shotgunScreenX", 2, "shotgun display offset along its canvas x, canvas units (60 x 20 canvas)");
		Tune.def("view.shotgunScreenY", 2, "shotgun display offset along its canvas y, canvas units");
		Tune.def("view.shotgunScreenOut", -0.005, "shotgun display lifted this far off its canvas toward the camera (it sits in a recess of the body), view units");
		Tune.def("view.altSharpX", 0, "alternate Sharpshooter target: offset, + moves it LEFT on screen, canvas units (15 wide)");
		Tune.def("view.altSharpY", 0, "alternate Sharpshooter target: offset, + moves it DOWN on screen, canvas units");
		Tune.def("view.altSharpSize", 11, "alternate Sharpshooter target: size, canvas units (the game: 11)");
		Tune.def("view.sharpX", 0.8, "Sharpshooter meters: group offset, + moves it LEFT on screen (the canvas is mirrored), canvas units (15 wide)");
		Tune.def("view.sharpY", 0.8, "Sharpshooter meters: group offset, + moves it DOWN on screen (the canvas is mirrored), canvas units");
		Tune.def("view.sharpGap", 4.2, "Sharpshooter meters: centre-to-centre spacing, canvas units (the game: 4.625)");
		Tune.def("view.sharpW", 3.4, "Sharpshooter meters: width of each line, canvas units (the game: 3.75)");
		Tune.def("view.sharpH", 10, "Sharpshooter meters: height of each line, canvas units (the game: 11)");
		Tune.def("view.canvasMirror", 1, "1 = the gun canvases (Marksman dots, Sharpshooter meters) are drawn x-mirrored (the rig's z mirror flips them)");
		Tune.def("view.flashLayer", 2, "muzzle flash: 2 = on the screen layer after the shader pack (true colours), 1 = solid in the world, 0 = see-through in the world");
		Tune.def("view.twirlSize", 1, "Sharpshooter twirl ring size (1 = the game's 0.32 u x 0.381 on the gun bone)");
		Tune.def("view.twirlAlpha", 1, "Sharpshooter twirl ring opacity multiplier (the game: 1/3, then 2/3 with charge)");
		Tune.def("view.flashBehindGun", 1, "1 = the screen-layer muzzle flash is hidden behind the gun (depth tested against it); 0 = on top");
		Tune.def("view.flashScreenYellow", 0, "yellow tint on the screen-layer flash (0 = the sprite's own colours)");
		Tune.def("view.flashEnd", 0.5, "muzzle flash size and opacity when it disappears (the game: about half)");
		Tune.def("view.muzzleCutHi", 0.65, "muzzle light: full while its intensity is above this share (0..1)");
		Tune.def("view.muzzleCutLo", 0.55, "muzzle light: gone below this share");
		Tune.def("view.flashSolid", 0, "0 = the muzzle flash starts solid, then shrinks and fades; 1 = it stays opaque while shrinking");
		Tune.def("view.flashYellow", 1.0, "muzzle flash yellow tint (0 = the sprite as is; shader packs wash its yellow out)");
		Tune.def("view.muzzleLight", 10, "strength of the muzzle flash's light on the gun (by eye; the game: a 20-intensity point light at the barrel)");
		Tune.def("view.muzzleLightTime", 0.2, "how long the muzzle light lasts, s (the game: 20 at -100/s)");
		Tune.def("view.cylDir", 1, "revolver cylinder turn direction: 1 clockwise as in the game, -1 the old way");
		Tune.def("view.cylOffset", 0, "debug: extra degrees on the revolver cylinder (turn it by eye to find the cylinder on screen)");
		Tune.def("view.heatGlow", 1, "multiplier on the shotgun heat sink glow alpha");
		Tune.def("view.sawRotX", 0, "Sawed-On chainsaw node rotation fix about x, degrees");
		Tune.def("view.sawRotY", 0, "Sawed-On chainsaw node rotation fix about y, degrees (prefab: 0)");
		Tune.def("view.sawRotZ", 0, "Sawed-On chainsaw node rotation fix about z, degrees");
		Tune.def("view.sawDebug", 0, "debug: draw the Sawed-On chainsaw magenta with the gun texture");
		Tune.def("view.steamOnGun", 0, "1 = also draw the old gun-attached steam puffs (debug)");
		Tune.def("view.steamCount", 22, "steam puffs per heat sink on ReleaseHeat");
		Tune.def("view.steamSpeed", 1.6, "steam puff speed along the sink, bone units/s");
		Tune.def("view.steamSize", 0.28, "steam puff start size, view units");
		Tune.def("view.steamLife", 0.9, "steam puff life, s");
		Tune.def("view.steamSpread", 35, "steam cone half angle, degrees");
	}
	private static final double FLASH_SIZE = Tune.def("view.flashSize", 1.6, "muzzle flash quad size in u");
	private static final double FLASH_TIME = Tune.def("view.flashTime", 0.1, "muzzle flash life in s (RemoveOnTime 0.1)");
	private static final double CHARGE_SIZE = Tune.def("view.chargeSize", 0.005, "Piercer ChargeEffect glow size per charge point (u)");

	private static final Identifier WHITE = new Identifier("minecraft", "textures/misc/white.png");
	private static float flashTime;
	private static String flashSprite, flashRig, flashBone;
	private static float flashSize = 1;
	private static Vector3f flashLocal;
	private static Matrix4f flashFixed;
	private static float flashDur = 0.1f;
	private static long flashStartNanos;
	/** The same moments on the game clock (ViewModel.gameSeconds: it stops through a parry freeze), for the flashes' lives. */
	private static double flashStartGame, muzzleGame, screenFlashGame;
	/** The muzzle flash on the screen layer: NDC centre and half width, when, how long, which sprite, its spin. */
	private static float screenFlashX, screenFlashY, screenFlashHalf, screenFlashDur, screenFlashSpin;
	private static boolean flashDouble, screenFlashDouble;
	private static long screenFlashNanos = -1;
	private static String screenFlashSprite, screenFlashRig;
	private static org.joml.Vector3f screenFlashView;
	private static float screenFlashViewHalf;

	/**
	 * The flash on the screen layer but behind the gun: the gun is drawn again into the depth buffer only, with this
	 * frame's pose and the hand projection, and the flash is drawn in the same space with a depth test.
	 */
	private static boolean drawFlashBehindGun(float scale, float alpha, float k) {
		Object[] lp = screenFlashRig == null ? null : com.ultracraft.client.uk.ViewModel.lastPose.get(screenFlashRig);
		if (lp == null || screenFlashView == null) return false;
		Matrix4f pose = (Matrix4f) lp[0];
		org.joml.Matrix3f normal = (org.joml.Matrix3f) lp[1];
		Matrix4f proj = (Matrix4f) lp[2];
		RigInstance gun = (RigInstance) lp[3];
		com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix();
		com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(proj, com.mojang.blaze3d.systems.VertexSorter.BY_DISTANCE);
		var mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
		mv.push();
		mv.loadIdentity();
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
		com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC);
		com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
		com.mojang.blaze3d.systems.RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
		// 1. the gun into depth
		net.minecraft.client.util.math.MatrixStack ms = new net.minecraft.client.util.math.MatrixStack();
		ms.peek().getPositionMatrix().set(pose);
		ms.peek().getNormalMatrix().set(normal);
		var buffers = net.minecraft.client.MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
		gun.layerOverride = com.ultracraft.client.render.UkLayers::depthOnly;
		gun.mirrored = gun.poseKey != null && gun.poseKey.startsWith("off:");
		gun.render(ms, buffers, 0xF000F0, OverlayTexture.DEFAULT_UV, com.ultracraft.client.uk.ViewModel.TEXTURES, false);
		gun.mirrored = false;
		gun.layerOverride = null;
		// and the fist arm, when it is up (a punch, the shotgun boost's parry): the flash is behind the hand too
		Object[] fp = HandRenderer.lastFistRig == null ? null : com.ultracraft.client.uk.ViewModel.lastPose.get(HandRenderer.lastFistRig);
		if (fp != null && fp[3] instanceof RigInstance fist) {
			net.minecraft.client.util.math.MatrixStack fs = new net.minecraft.client.util.math.MatrixStack();
			fs.peek().getPositionMatrix().set((Matrix4f) fp[0]);
			fs.peek().getNormalMatrix().set((org.joml.Matrix3f) fp[1]);
			fist.layerOverride = com.ultracraft.client.render.UkLayers::depthOnly;
			fist.render(fs, buffers, 0xF000F0, OverlayTexture.DEFAULT_UV, com.ultracraft.client.uk.ViewModel.TEXTURES, false);
			fist.layerOverride = null;
		}
		buffers.draw();
		// the layer's phases are cleared after its draw (the depth test switched off again): back on for the flash
		com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
		com.mojang.blaze3d.systems.RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
		// 2. the flash, camera-facing in view space at the barrel point, behind whatever of the gun is closer
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		com.mojang.blaze3d.systems.RenderSystem.disableCull();
		com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
		com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionTexColorProgram);
		com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, new Identifier(UltraCraft.MOD_ID, "textures/uk/" + screenFlashSprite + ".png"));
		var bb = net.minecraft.client.render.Tessellator.getInstance().getBuffer();
		bb.begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS, net.minecraft.client.render.VertexFormats.POSITION_TEXTURE_COLOR);
		// pushed back along the view ray (same size and place on screen): the alternate's ShootPoint (1) sits at the
		// muzzle's very tip, in front of its own barrel; further back the gun covers the flash like the normal one's
		float push = screenFlashRig != null && screenFlashRig.endsWith("_alt") ? Tune.getF("view.altFlashPush") : Tune.getF("view.flashPush");
		float fz = Math.abs(screenFlashView.z) < 1e-4f ? 1f : 1f + push / Math.abs(screenFlashView.z);
		if (Tune.on("view.flashDebug")) {
			Matrix4f tip = gun.boneWorld(screenFlashRig.endsWith("_alt") ? "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/ShootPoint (1)" : "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/ShootPoint");
			Vector4f tv = tip == null ? null : new Vector4f(0, 0, 0, 1).mul(tip);
			Vector4f tw = tv == null ? null : new Vector4f(tv.x, tv.y, -tv.z, 1).mul(pose);
			UltraCraft.LOG.info("[flash] rig={} t={} view={} push={} fz={} half={} tipNow={} clip={} {}", screenFlashRig, (System.nanoTime() - screenFlashNanos) / 1e6, screenFlashView, push, fz, screenFlashViewHalf, tw, gun.currentClip(), gun.clipTime());
		}
		org.joml.Vector3f fv = new org.joml.Vector3f(screenFlashView).mul(fz);
		// the flash is in the world at the barrel (the game's muzzle flash prefabs are), the gun is not: the camera shake's
		// move this frame shifts it the other way in view space
		fv.x -= (float) com.ultracraft.client.v1.V1.INSTANCE.shakeRight;
		fv.y -= (float) com.ultracraft.client.v1.V1.INSTANCE.shakeUp;
		float half = screenFlashViewHalf * scale * fz;
		float cs = (float) Math.cos(screenFlashSpin), sn = (float) Math.sin(screenFlashSpin);
		float g = 1f - 0.1f * k, bl = 1f - 0.75f * k;
		float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		Matrix4f id = new Matrix4f();
		for (int star = 0; star < (screenFlashDouble ? 2 : 1); star++) {
			float h2 = star == 0 ? half : half * 0.75f;
			float c2 = star == 0 ? cs : (float) Math.cos(screenFlashSpin + Math.PI / 4), s2 = star == 0 ? sn : (float) Math.sin(screenFlashSpin + Math.PI / 4);
			for (float[] q : corners) {
				float x = q[0] * h2, y = q[1] * h2;
				bb.vertex(id, fv.x + x * c2 - y * s2, fv.y + x * s2 + y * c2, fv.z).texture(q[2], q[3]).color(1f, g, bl, alpha).next();
			}
		}
		net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(bb.end());
		com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
		com.mojang.blaze3d.systems.RenderSystem.enableCull();
		// restore the GUI state
		com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC);
		mv.pop();
		com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
		com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix();
		return true;
	}

	/**
	 * Called from the HUD: the muzzle flash where the shot left the barrel, shrinking and fading to view.flashEnd
	 * over its life. Drawn after the world (and the shader pack), so the sprite keeps its own white core and
	 * yellow arms, as in the game.
	 */
	public static void drawScreenFlash(net.minecraft.client.gui.DrawContext ctx, int w, int h) {
		drawScreenFlashOne(ctx, w, h);
		if (offFlash == null) return;
		swapFlash();
		try {
			drawScreenFlashOne(ctx, w, h);
		} finally {
			swapFlash();
		}
	}

	private static void drawScreenFlashOne(net.minecraft.client.gui.DrawContext ctx, int w, int h) {
		if (screenFlashSprite == null || screenFlashNanos < 0) return;
		float t = (float) (com.ultracraft.client.uk.ViewModel.gameSeconds() - screenFlashGame);
		if (t >= screenFlashDur || com.ultracraft.client.uk.ViewModel.paused() && t > screenFlashDur) return;
		float p = Math.max(0f, Math.min(1f, t / Math.max(1e-3f, screenFlashDur)));
		float end = screenFlashEnd >= 0 ? screenFlashEnd : (float) Tune.get("view.flashEnd");
		float k = (float) Tune.get("view.flashScreenYellow");
		float scale = 1f - (1f - end) * p, alpha = (1f - (1f - end) * p) * screenFlashAlpha;
		if (Tune.on("view.flashBehindGun") && drawFlashBehindGun(scale, alpha, k)) return;
		float cx = (screenFlashX * 0.5f + 0.5f) * w, cy = (1f - (screenFlashY * 0.5f + 0.5f)) * h;
		// the flash is in the world at the barrel (the game's muzzle flash prefabs are): the camera shake moves the camera,
		// so the flash moves the other way on screen by that move seen at its distance (view.flashDepth blocks)
		{
			var c = MinecraftClient.getInstance();
			double tanHalf = Math.tan(Math.toRadians(c.options.getFov().getValue()) / 2), depth = Math.max(0.1, Tune.get("view.flashDepth"));
			double aspect = (double) w / h;
			cx += (float) (-com.ultracraft.client.v1.V1.INSTANCE.shakeRight / depth / (tanHalf * aspect) * w / 2);
			cy += (float) (com.ultracraft.client.v1.V1.INSTANCE.shakeUp / depth / tanHalf * h / 2);
		}
		float half = screenFlashHalf * 0.5f * w * scale;
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		com.mojang.blaze3d.systems.RenderSystem.disableCull();
		com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionTexColorProgram);
		com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, new Identifier(UltraCraft.MOD_ID, "textures/uk/" + screenFlashSprite + ".png"));
		Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
		var bb = net.minecraft.client.render.Tessellator.getInstance().getBuffer();
		bb.begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS, net.minecraft.client.render.VertexFormats.POSITION_TEXTURE_COLOR);
		float cs = (float) Math.cos(screenFlashSpin), sn = (float) Math.sin(screenFlashSpin);
		float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
		float g = 1f - 0.1f * k, bl = 1f - 0.75f * k;
		for (int star = 0; star < (screenFlashDouble ? 2 : 1); star++) {
			float h2 = star == 0 ? half : half * 0.75f;
			float c2 = star == 0 ? cs : (float) Math.cos(screenFlashSpin + Math.PI / 4), s2 = star == 0 ? sn : (float) Math.sin(screenFlashSpin + Math.PI / 4);
			for (float[] q : corners) {
				float x = q[0] * h2, y = -q[1] * h2;
				bb.vertex(m, cx + x * c2 - y * s2, cy + x * s2 + y * c2, 0).texture(q[2], q[3]).color(1f, g, bl, alpha).next();
			}
		}
		net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(bb.end());
		com.mojang.blaze3d.systems.RenderSystem.enableCull();
	}

	private GunDisplays() {}

	/** The muzzle flash's point light on the gun, 1 at the shot (RevolverBeam: intensity 20 falling 100/s). */
	public static float muzzleLight;
	private static long muzzleNanos;

	public static void muzzleLight() {
		muzzleLight(1f, 0.93f, 0.72f);
	}

	/** The light in a colour (Revolver Beam Super: (0.27, 0.88, 1)). */
	public static void muzzleLight(float r, float g, float b) {
		muzzleLight(r, g, b, 1f);
	}

	/** The light at a fraction of view.muzzleLight (the shotgun's orange light would blow the whole gun out). */
	public static void muzzleLight(float r, float g, float b, float strength) {
		muzzleStrength = strength;
		muzzleLight = 1f;
		muzzleNanos = System.nanoTime();
		muzzleGame = com.ultracraft.client.uk.ViewModel.gameSeconds();
		muzzleColor[0] = r; muzzleColor[1] = g; muzzleColor[2] = b;
	}

	private static final float[] muzzleColor = {1f, 0.93f, 0.72f};
	private static float muzzleStrength = 1f;

	/** A muzzle flash sprite at `bone` of `rig` for a few frames. */
	public static void flash(String rig, String bone, String sprite, float size) {
		flash(rig, bone, sprite, size, (float) Tune.get("view.flashTime"));
	}

	/** A muzzle flash that lasts `seconds`. */
	public static void flash(String rig, String bone, String sprite, float size, float seconds) {
		flash(rig, bone, sprite, size, seconds, false);
	}

	/** `doubleStar`: a second star turned 45 degrees at 0.75 scale on top (Revolver Beam Alternative: eight spikes). */
	public static void flash(String rig, String bone, String sprite, float size, float seconds, boolean doubleStar) {
		// (the off-hand gun's shots have their own star: its flash state is swapped in while it runs, swapFlash)
		flashDouble = doubleStar;
		flashEndOverride = -1;
		flashAlphaOverride = 1;
		flashDur = seconds;
		flashRig = rig;
		flashBone = bone;
		flashLocal = null;
		flashSprite = sprite;
		flashSize = size;
		flashTime = seconds;
		flashFixed = null;
		flashStartNanos = System.nanoTime();
		flashStartGame = com.ultracraft.client.uk.ViewModel.gameSeconds();
	}

	/**
	 * The last shot's barrel point in the world (Revolver.Shoot: the beam's alternateStartPoint = gunBarrel), or null
	 * when there was no recent shot. The flash point is fixed when the shot is drawn, in the view model's space.
	 */
	/** A bone of a view-model rig, as it was last drawn, in the world (null before it was drawn). */
	public static net.minecraft.util.math.Vec3d boneInWorld(RigInstance gun, String bone) {
		Matrix4f m = gun == null ? null : gun.boneWorld(bone);
		if (m == null) return null;
		Vector4f c = new Vector4f(0, 0, 0, 1).mul(m);
		// the off-hand gun is drawn mirrored to the left (OffhandGun): its barrel is on the other side
		if (gun.poseKey != null && gun.poseKey.startsWith("off:")) c.x = -c.x;
		return HandRenderer.viewToWorld(new Vector3f(c.x, c.y, c.z));
	}

	public static net.minecraft.util.math.Vec3d muzzleWorld(boolean offHand) {
		int h = offHand ? 1 : 0;
		if (shotPoint[h] == null || com.ultracraft.client.uk.ViewModel.gameSeconds() - shotPointGame[h] > 0.5) return null;
		Vector4f c = new Vector4f(0, 0, 0, 1).mul(shotPoint[h]);
		// the off-hand gun is drawn mirrored to the left (OffhandGun): its barrel is on the other side
		return HandRenderer.viewToWorld(new Vector3f(offHand ? -c.x : c.x, c.y, c.z));
	}

	/** Each hand's last shot point (its flash's fixed barrel point) and when, for its beams (muzzleWorld). */
	private static final Matrix4f[] shotPoint = new Matrix4f[2];
	private static final double[] shotPointGame = new double[2];

	/** The other gun's muzzle flash state: swapped with the current one while the off-hand gun runs (OffhandGun.run). */
	private static Object[] offFlash;

	public static void swapFlash() {
		Object[] cur = {flashTime, flashSprite, flashRig, flashBone, flashSize, flashLocal, flashFixed, flashDur, flashStartNanos, flashStartGame,
			flashDouble, flashEndOverride, flashAlphaOverride, screenFlashX, screenFlashY, screenFlashHalf, screenFlashDur, screenFlashSpin,
			screenFlashDouble, screenFlashNanos, screenFlashSprite, screenFlashRig, screenFlashView, screenFlashViewHalf, screenFlashGame,
			screenFlashEnd, screenFlashAlpha};
		Object[] o = offFlash;
		offFlash = cur;
		if (o == null) {
			flashTime = 0; flashSprite = null; flashRig = null; flashBone = null; flashSize = 1; flashLocal = null; flashFixed = null;
			flashDur = 0.1f; flashStartNanos = 0; flashStartGame = 0; flashDouble = false; flashEndOverride = -1; flashAlphaOverride = 1;
			screenFlashNanos = -1; screenFlashSprite = null; screenFlashRig = null; screenFlashView = null; screenFlashDouble = false;
			screenFlashEnd = -1; screenFlashAlpha = 1;
			return;
		}
		int i = 0;
		flashTime = (float) o[i++]; flashSprite = (String) o[i++]; flashRig = (String) o[i++]; flashBone = (String) o[i++];
		flashSize = (float) o[i++]; flashLocal = (Vector3f) o[i++]; flashFixed = (Matrix4f) o[i++]; flashDur = (float) o[i++];
		flashStartNanos = (long) o[i++]; flashStartGame = (double) o[i++]; flashDouble = (boolean) o[i++];
		flashEndOverride = (float) o[i++]; flashAlphaOverride = (float) o[i++];
		screenFlashX = (float) o[i++]; screenFlashY = (float) o[i++]; screenFlashHalf = (float) o[i++]; screenFlashDur = (float) o[i++];
		screenFlashSpin = (float) o[i++]; screenFlashDouble = (boolean) o[i++]; screenFlashNanos = (long) o[i++];
		screenFlashSprite = (String) o[i++]; screenFlashRig = (String) o[i++]; screenFlashView = (org.joml.Vector3f) o[i++];
		screenFlashViewHalf = (float) o[i++]; screenFlashGame = (double) o[i++]; screenFlashEnd = (float) o[i++]; screenFlashAlpha = (float) o[i++];
	}

	/** The current flash lasts `seconds` instead (for flashAt, which takes the default life). */
	public static void flashTimeOverride(float seconds) {
		flashDur = seconds;
		flashTime = seconds;
	}

	/** The current flash ends at this size / opacity share instead of view.flashEnd. */
	public static void flashEndOverride(float end) {
		flashEndOverride = end;
	}

	private static float flashEndOverride = -1, screenFlashEnd = -1;
	/** The current flash's opacity (1 = solid; the nailgun's star is a little see-through). */
	private static float flashAlphaOverride = 1, screenFlashAlpha = 1;

	public static void flashAlpha(float a) {
		flashAlphaOverride = a;
	}

	/** A muzzle flash at a point given in the prefab root's space (for shoot points that are not rig bones). */
	public static void flashAt(String rig, Vector3f prefabLocal, String sprite, float size) {
		flash(rig, null, sprite, size);
		flashLocal = prefabLocal;
	}

	static {
		com.ultracraft.client.render.DynamicLights.ensureTunes();
		Tune.def("view.projLight", 0.125, "a near Rocket's / Grenade's orange light on the gun at point blank, share of view.muzzleLight (by eye: half the shotgun's 0.25, their light is 10 to its 20)");
	}

	/**
	 * The strongest Rocket / Grenade light at the camera: {strength 0..1, r, g, b, and the way to it in the world x, y, z}, or null. Unity's point falloff
	 * 1 / (1 + 25 (d / 10)^2), faded over the last fifth of its range, times its intensity 10, capped at 1; in one colour
	 * at any distance, the grenade star's (light.color*).
	 */
	private static float[] projectileNearLight() {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null || c.player == null || Tune.get("view.projLight") <= 0) return null;
		double S = com.ultracraft.weapon.Damage.SCALE_BLOCKS, range = 10 * S;
		net.minecraft.util.math.Vec3d cam = c.gameRenderer.getCamera().getPos();
		float td = c.getTickDelta(), best = 0;
		net.minecraft.util.math.Vec3d dir = null;
		for (var e : c.world.getEntitiesByClass(com.ultracraft.entity.UKProjectileEntity.class, new net.minecraft.util.math.Box(cam, cam).expand(range), x -> true)) {
			var k = e.kind();
			if (k != com.ultracraft.entity.ProjectileKind.ROCKET && k != com.ultracraft.entity.ProjectileKind.CORE) continue;
			net.minecraft.util.math.Vec3d at = e.getLerpedPos(td).add(0, e.getHeight() / 2, 0);
			double d = at.distanceTo(cam) / range;
			if (d >= 1) continue;
			float a = (float) (1 / (1 + 25 * d * d) * (d > 0.8 ? (1 - d) / 0.2 : 1)) * 10;
			if (a > best) { best = a; dir = at.subtract(cam); }
		}
		if (best <= 0 || dir == null) return null;
		if (dir.lengthSquared() < 1e-6) dir = new net.minecraft.util.math.Vec3d(0, -1, 0);
		dir = dir.normalize();
		float r = (float) Tune.get("light.colorR"), g = (float) Tune.get("light.colorG"), b = (float) Tune.get("light.colorB");
		float top = Math.max(r, Math.max(g, b));
		return top <= 0 ? null : new float[] {Math.min(1, best), r / top, g / top, b / top, (float) dir.x, (float) dir.y, (float) dir.z};
	}

	/** Per-frame state → mesh overrides on the gun rig (textures, colours, scales). */
	public static void apply(RigInstance gun, WeaponKind kind, int variation, boolean alt) {
		if (gun != null) {
			float since = (float) (com.ultracraft.client.uk.ViewModel.gameSeconds() - muzzleGame);
			float life = (float) Tune.get("view.muzzleLightTime");
			float k = muzzleLight <= 0 || since >= life ? 0 : 1 - since / life;
			if (k <= 0) muzzleLight = 0;
			// the point light (20 at -100/s) blows the gun out to white while it is strong and the gun snaps back
			// once it weakens: full until k = cutHi, gone at k = cutLo (4 white frames at 60 fps in the game)
			float hi = (float) Tune.get("view.muzzleCutHi"), lo = (float) Tune.get("view.muzzleCutLo");
			float c = Math.max(0f, Math.min(1f, (k - lo) / Math.max(1e-3f, hi - lo)));
			gun.lightAdd = (float) (c * Tune.get("view.muzzleLight") * muzzleStrength);
			gun.lightR = muzzleColor[0]; gun.lightG = muzzleColor[1]; gun.lightB = muzzleColor[2];
			// a Rocket's or a Grenade's Point Light ((1, 0.24, 0) x 10, range 10) on the gun while one is near: a thrown
			// grenade (2 u ahead) and one hit by the hammer (frozen in front of the camera) light it orange
			// (from its side: each part of the gun by how much it faces the light - riding a rocket lights the underside)
			float[] near = projectileNearLight();
			gun.pointAdd = 0;
			if (near != null) {
				gun.pointAdd = (float) (near[0] * Tune.get("view.muzzleLight") * Tune.get("view.projLight"));
				gun.pointR = near[1]; gun.pointG = near[2]; gun.pointB = near[3];
				net.minecraft.client.render.Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
				Vector3f f = cam.getHorizontalPlane(), u = cam.getVerticalPlane(), r = new Vector3f(f).cross(u).normalize();
				Vector3f w = new Vector3f(near[4], near[5], near[6]);
				gun.pointDir = new Vector3f(w.dot(r), w.dot(u), -w.dot(f)).normalize();
			}
		}
		if (gun == null || !Tune.on("view.displays")) return;
		WeaponClient w = WeaponClient.cur();
		ArsenalClient a = ArsenalClient.cur();
		int rgb = UltraCraftClient.VARIATION_COLORS[Math.min(2, variation)];
		float vr = ((rgb >> 16) & 255) / 255f, vg = ((rgb >> 8) & 255) / 255f, vb = (rgb & 255) / 255f;
		if (kind == WeaponKind.REVOLVER) {
			String monitor = alt ? "Monitor (1)" : "Monitor";
			if (variation == 2 && (w.chargingPierce || w.twirlRecovery)) {
				// the Sharpshooter twirl between ticks: latestTwirlRotation advanced by the tick's rate for the frame time since
				float since = Math.min(0.1f, (System.nanoTime() - w.twirlTickNanos) / 1e9f);
				if (com.ultracraft.client.uk.ViewModel.paused()) since = 0;
				float angle = w.latestTwirlRotation + w.twirlRate * since;
				// Revolver.LateUpdate: twirlBone euler += (altVersion ? Vector3.left : Vector3.forward) * latestTwirlRotation
				gun.setExtraRotation("Armature/Upper Arm/Forearm/Hand/Revolver_Bone", alt ? new Quaternionf().rotationX((float) Math.toRadians(-angle)) : new Quaternionf().rotationZ((float) Math.toRadians(angle)));
			} else {
				// not spinning (or another variation after a switch mid-spin): the gun sits straight in the hand
				gun.setExtraRotation("Armature/Upper Arm/Forearm/Hand/Revolver_Bone", null);
			}
			if (!alt) {
				// the revolver's lights (T_PistolNew_Emissive) glow in the variation colour
				gun.setEmissiveOverlay("Revolver_Body", "t_pistolnew_emissive", vr, vg, vb);
				gun.setEmissiveOverlay("Revolver_Cylinder", "t_pistolnew_emissive", vr, vg, vb);
			} else {
				// the alternate's two rows of lights (MinosRevolver's _EmissiveTex T_MinosRevolver_128_Emissive), in the
				// variation colour like the normal one's
				for (String m : new String[] {"MinosRevolver", "MinosRevolver_Cyllinder", "MinosRevolver_Hammer"})
					gun.setEmissiveOverlay(m, "t_minosrevolver_128_emissive", vr, vg, vb);
			}
			if (variation == 0) {
				// Revolver.Update: battery texture by pierceCharge / pierceShotCharge, _Color by state
				gun.setMeshAlpha(n -> n.equals(monitor), 1f);
				String tex;
				float r = 1, g = 1, b = 1;
				if (w.pierceShotCharge > 0) {
					tex = w.pierceShotCharge < 50 ? "batterycharging1" : w.pierceShotCharge < 100 ? "batterycharging2" : "batterycharging3";
					r = vr; g = vg; b = vb;
				} else if (w.pierceCharge < 50) { tex = "batterylow"; r = 1; g = 0; b = 0; }
				else if (w.pierceCharge < 100) { tex = "batterymid"; r = 1; g = 1; b = 0; }
				else { tex = "batteryfull"; r = vr; g = vg; b = vb; }
				gun.setMeshTexture(monitor, tex);
				gun.setMeshColor(monitor, r, g, b);
				// ChargeEffect: localScale = charge × 0.02 (drawn as a glow quad at the shoot point, see draw())
			} else {
				gun.setMeshAlpha(n -> n.equals(monitor), 0f); // the Marksman / Sharpshooter canvases sit where the monitor is
			}
			// RevolverCylinder: spinSpeed = pierceShotCharge; free spin at spinSpeed × 10 °/s about (0, -1, 0) while
			// spinSpeed × 10 > 240, otherwise it settles on the nearest of the 3 chambers at 240 °/s
			float dt = cylDt();
			float spin = w.pierceShotCharge * 10f;
			if (spin > 240f) { cylAngle += spin * dt; cylFree = true; }
			else {
				float step = alt ? 90f : 120f; // RevolverCylinder.bulletAmount 3 / 4 (alternate)
				if (cylFree) { cylFree = false; cylTarget = Math.round(cylAngle / step) * step; }
				float d = cylTarget - cylAngle;
				if (Math.abs(d) > 0.1f) cylAngle += Math.signum(d) * Math.min(Math.abs(d), 240f * dt);
			}
			gun.setExtraRotation((alt ? "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/Cylinder_Bone" : "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/Cylinder_Bone"),
				new Quaternionf().rotationAxis((float) Math.toRadians(Tune.get("view.cylDir") * (cylAngle + Tune.get("view.cylOffset"))), 0, -1, 0)); // clockwise from behind, as in the game
		} else if (kind == WeaponKind.SHOTGUN && !alt) {
			// HeatSinkHeat (Shotgun_New material 3): _TintColor (1, 0.24, 0) with alpha = Shotgun.tempColor.a
			// (the cores the Core Eject flings out are dark red: view.coreHeatR/G/B)
			if (a.resettingCores) gun.setMaterialColor("HeatSinkHeat", Tune.getF("view.coreHeatR"), Tune.getF("view.coreHeatG"), Tune.getF("view.coreHeatB"), a.heatGlow * (float) Tune.get("view.heatGlow"));
			else gun.setMaterialColor("HeatSinkHeat", 1f, 0.42f, 0.06f, a.heatGlow * (float) Tune.get("view.heatGlow"));
			// Shotgun.cs: the chainsaw on the forend (ChainSawAttachPoint) is active only on Sawed-On with a full saw charge
			boolean saw = variation == 2 && a.shoSawCharge >= 1f;
			// (not at the start of Equip: the gun comes up flipped from below the screen and the chainsaw, far out on the
			// forend, swings right through the camera for a frame or two, the same glitch as in the game; the gun itself is
			// still off screen then)
			if (saw && "Equip".equals(gun.currentClip()) && gun.clipTime() < Tune.get("view.sawEquipHide")) saw = false;
			for (int i = 0; i < gun.rig.meshes.size(); i++) {
				String n = gun.rig.meshes.get(i).name;
				if (n.startsWith("Chainsaw")) { if (saw) gun.hiddenMeshes.remove(i); else gun.hiddenMeshes.add(i); }
			}
			// the prefab's Chainsaw node as it is (scale 60 under the 0.01 attach point, at (-223, -56, -97)): on the gun's
			// left side, body low, blade forward along the barrels (checked against a game recording); view.saw* stay as
			// live nudges, 0 / 1 = the prefab
			float ss = (float) Tune.get("view.sawScale");
			// the body in the variation red: the game tints it through T_Chainsaw_ID (CUSTOM_COLORS), baked as t_chainsaw_red
			if (!Tune.on("view.sawDebug")) gun.setMeshTexture("Chainsaw", "t_chainsaw_red");
			if (Tune.on("view.sawDebug")) {
				for (String n : new String[] {"Chainsaw", "Chainsaw_Blade"}) { gun.setMeshTexture(n, "t_shotgunnew"); gun.setMeshColor(n, 1f, 0f, 1f); gun.setMeshEmissive(n, true); }
				// 2 = only the chainsaw meshes
				if (Tune.get("view.sawDebug") >= 2) for (int i = 0; i < gun.rig.meshes.size(); i++) if (!gun.rig.meshes.get(i).name.startsWith("Chainsaw")) gun.hiddenMeshes.add(i); else gun.hiddenMeshes.remove(i);
			} else if (sawDebugWas) {
				gun.hiddenMeshes.clear();
				for (String n : new String[] {"Chainsaw", "Chainsaw_Blade"}) { gun.setMeshTexture(n, null); gun.setMeshColor(n, 1f, 1f, 1f); gun.setMeshEmissive(n, false); }
			}
			sawDebugWas = Tune.on("view.sawDebug");
			Matrix4f apm = gun.boneWorld("GunArmature/MainBone/FrontBone/ChainSawAttachPoint");
			ChainsawFx.attach(apm);
			gun.setBoneScale("GunArmature/MainBone/FrontBone/ChainSawAttachPoint/Chainsaw", new Vector3f(ss, ss, ss));
			// the static node's axes come out turned (the FBX import's axis fix is not in the node transform): live euler fix
			gun.setExtraRotation("GunArmature/MainBone/FrontBone/ChainSawAttachPoint/Chainsaw", new Quaternionf().rotationXYZ((float) Math.toRadians(Tune.get("view.sawRotX")), (float) Math.toRadians(Tune.get("view.sawRotY")), (float) Math.toRadians(Tune.get("view.sawRotZ"))));
			gun.setBoneOffset("GunArmature/MainBone/FrontBone/ChainSawAttachPoint/Chainsaw", new Vector3f((float) Tune.get("view.sawX"), (float) Tune.get("view.sawY"), (float) Tune.get("view.sawZ")));
			if (Tune.on("view.displaysDebug") && System.nanoTime() - lastBranchLog > 1_000_000_000L) {
				lastBranchLog = System.nanoTime();
				gun.debugBounds = true;
				Matrix4f cs = gun.boneWorld("GunArmature/MainBone/FrontBone/ChainSawAttachPoint/Chainsaw");
				Matrix4f ap = gun.boneWorld("GunArmature/MainBone/FrontBone/ChainSawAttachPoint");
				Matrix4f fb = gun.boneWorld("GunArmature/MainBone/FrontBone");
				if (ap != null && fb != null) UltraCraft.LOG.info("[displays] attach point at {} axes x{} y{} z{} | front bone at {} axes x{} y{} z{}", ap.getTranslation(new Vector3f()),
					ap.transformDirection(new Vector3f(1, 0, 0)), ap.transformDirection(new Vector3f(0, 1, 0)), ap.transformDirection(new Vector3f(0, 0, 1)),
					fb.getTranslation(new Vector3f()), fb.transformDirection(new Vector3f(1, 0, 0)), fb.transformDirection(new Vector3f(0, 1, 0)), fb.transformDirection(new Vector3f(0, 0, 1)));
				UltraCraft.LOG.info("[displays] shotgun saw={} charge={} var={} chainsaw bone at {} scale {} hidden={}", saw, a.shoSawCharge, variation,
					cs == null ? "null" : cs.getTranslation(new Vector3f()), cs == null ? "-" : cs.getScale(new Vector3f()), gun.hiddenMeshes);
			}
		} else if (kind == WeaponKind.SHOTGUN && alt) {
			hammerView(gun, variation, vr, vg, vb);
		} else if (kind == WeaponKind.RAILCANNON) {
			railcannon(gun, a.raicharge, vr, vg, vb);
		} else if (kind == WeaponKind.NAILGUN && !alt) {
			// Nailgun New / the barrels: T_Nailgun_New_Glow lights the little dots between the barrels in the variation colour
			for (String m : new String[] {"Nailgun New", "Barrel_L", "Barrel_R"}) gun.setEmissiveOverlay(m, "t_nailgun_new_glow", vr, vg, vb, (float) Tune.get("nail.dotGlow"));
			// BarrelHeat: _EmissiveFogStart 2 / End 1.5 with the emission replacing the colour: only the part of the heat
			// shell more than ~2 u from the camera (the barrels' front ends) lights up
			float k = (float) (com.ultracraft.client.v1.V1.SCALE * Tune.get("view.scale"));
			for (String m : new String[] {"Barrel_L (1)", "Barrel_R (1)"})
				gun.setDistanceFade(m, (float) Tune.get("nail.heatNear") * k, (float) Tune.get("nail.heatFar") * k, (float) Tune.get("nail.heatNearAlpha"));
			// Spin on the barrels (speed = Nailgun.spinSpeed), left and right turning opposite ways
			long now = System.nanoTime();
			float sdt = nailSpinNanos == 0 ? 0 : Math.min(0.1f, (now - nailSpinNanos) / 1e9f);
			if (com.ultracraft.client.uk.ViewModel.paused() || com.ultracraft.client.v1.V1.INSTANCE.frozen()) sdt = 0;
			nailSpinNanos = now;
			nailSpinAngle = (nailSpinAngle + a.nailSpin * (float) Tune.get("nail.spinRate") * sdt) % 360f;
			gun.setExtraRotation("Armature/Main/Barrel_L", new org.joml.Quaternionf().rotationY((float) Math.toRadians(-nailSpinAngle)));
			gun.setExtraRotation("Armature/Main/Barrel_R", new org.joml.Quaternionf().rotationY((float) Math.toRadians(nailSpinAngle)));
			// barrelLights: orange (1, 0.45, 0) at heat x 10 on the gun
			float hl = a.heatUp * (float) Tune.get("nail.heatLight");
			if (hl > gun.lightAdd) { gun.lightAdd = hl; gun.lightR = 1f; gun.lightG = 0.45f; gun.lightB = 0f; }
		}
	}

	// ------------------------------------------------------------------ Railcannon (Railcannon.Update / RailCannonPip)

	/** RailCannonPip: bone, chargeLevel, pushAmount.x (the push is pushAmount * 0.01 on the local position). */
	private static final String[] RAIL_CAPS = {"Armature/Base/Main_Capsule",
		"Armature/Base/Prong_L/ProngCapsule_L_001", "Armature/Base/Prong_L/ProngCapsule_L_002", "Armature/Base/Prong_L/ProngCapsule_L_003", "Armature/Base/Prong_L/ProngCapsule_L_004",
		"Armature/Base/Prong_R/ProngCapsule_R_001", "Armature/Base/Prong_R/ProngCapsule_R_002", "Armature/Base/Prong_R/ProngCapsule_R_003", "Armature/Base/Prong_R/ProngCapsule_R_004"};
	private static final float[] RAIL_LEVEL = {5, 1, 2, 3, 4, 1, 2, 3, 4};
	private static final float[] RAIL_PUSH = {-5, 3, 3, 3, 3, -3, -3, -3, -3};
	private static final float[] railOut = {1, 1, 1, 1, 1, 1, 1, 1, 1}, railSpin = new float[9];
	/** The Railcannon's pips in its `pips` array order (each lights over its own unit of charge; BigPip is the fifth). */
	private static final String[] RAIL_PIPS = {"Pips_1", "Pips_2", "Pips_3", "Pips_4", "BigPip"};
	private static long railNanos;
	private static final double RAIL_GLOW = Tune.def("rail.glow", 1, "railcannon emission glow multiplier (T_Railcannon_Emission x variation colour x intensity)");
	private static final double RAIL_PUSH_K = Tune.def("rail.push", 1, "railcannon capsules pop-out distance multiplier (RailCannonPip pushAmount x 0.01)");
	private static final double RAIL_SPIN = Tune.def("rail.spin", -2400, "railcannon capsule spin when charged, deg/s about its local up (RailCannonPip: -2400)");
	private static final double RAIL_FULL_LIGHT = Tune.def("rail.fullLight", 0.35, "railcannon full charge: the FullCharge light on the gun (variation colour)");
	private static final double RAIL_SHAKE = Tune.def("rail.shake", 1, "railcannon full charge jitter multiplier (game: +-0.005 u per axis every frame)");
	private static final double RAIL_ARC_RATE = Tune.def("rail.arcRate", 10, "railcannon full charge lightning: arcs per second (FullCharge particle: 10/s, 1 s life)");
	private static final double RAIL_ARC_SIZE = Tune.def("rail.arcSize", 1, "railcannon full charge lightning size multiplier (particle size 1 in the opening's space)");

	/**
	 * Railcannon.Update / SetMaterialIntensity and the RailCannonPips: while charging (raicharge 0..5) the body's emission
	 * is charge / 5 and each pip lights over its own unit of charge; the capsules stand out until their chargeLevel,
	 * then slide in and spin. At full charge everything glows fully, the FullCharge light and lightning come on.
	 */
	private static void railcannon(RigInstance gun, float charge, float vr, float vg, float vb) {
		long now = System.nanoTime();
		float dt = railNanos == 0 ? 0 : Math.min(0.1f, (now - railNanos) / 1e9f);
		if (com.ultracraft.client.uk.ViewModel.paused() || com.ultracraft.client.v1.V1.INSTANCE.frozen()) dt = 0;
		railNanos = now;
		boolean full = charge >= 5;
		float glow = (float) Tune.get("rail.glow");
		gun.setEmissiveOverlay("Railcannon", "t_railcannon_emission", vr, vg, vb, (full ? 1f : charge / 5f) * glow);
		for (int i = 0; i < RAIL_PIPS.length; i++) {
			String m = RAIL_PIPS[i];
			gun.setMeshTexture(m, null);
			gun.setMeshColor(m, 1f, 1f, 1f);
			gun.setMeshEmissive(m, false);
			float in = full || charge > i + 1 ? 1f : Math.max(0f, charge - i);
			gun.setEmissiveOverlay(m, "t_railcannon_emission", vr, vg, vb, in * glow);
		}
		for (int i = 0; i < RAIL_CAPS.length; i++) {
			boolean charged = charge >= RAIL_LEVEL[i];
			float target = charged ? 0f : 1f;
			// MoveTowards(pos, target, distance * 50 * dt)
			railOut[i] += (target - railOut[i]) * Math.min(1f, 50f * dt);
			gun.setBoneOffset(RAIL_CAPS[i], new Vector3f(RAIL_PUSH[i] * 0.01f * railOut[i] * (float) Tune.get("rail.push"), 0, 0));
			if (charged) {
				railSpin[i] = (railSpin[i] + (float) Tune.get("rail.spin") * dt) % 360f;
				gun.setExtraRotation(RAIL_CAPS[i], new org.joml.Quaternionf().rotationY((float) Math.toRadians(railSpin[i])));
			} else {
				railSpin[i] = 0;
				gun.setExtraRotation(RAIL_CAPS[i], null);
			}
		}
		// FullCharge: a point light (variation colour) lights the gun from the opening
		if (full && gun.lightAdd < Tune.get("rail.fullLight")) {
			gun.lightAdd = (float) Tune.get("rail.fullLight");
			gun.lightR = vr;
			gun.lightG = vg;
			gun.lightB = vb;
		}
	}

	/** FullCharge/Particle System: arcs of T_ElectricArcs (one random frame for all, every 0.5 s) in the opening. */
	private static final class Arc {
		float x, y, age, rot;
	}

	private static final java.util.List<Arc> railArcs = new java.util.ArrayList<>();
	private static float railArcEmit, railArcFrameT;
	private static int railArcFrame;

	private static void railArcs(RigInstance gun, VertexConsumerProvider buffers, Matrix4f pm, float charge, float dt, float vr, float vg, float vb) {
		boolean full = charge >= 5;
		if (full) {
			railArcEmit += dt * (float) Tune.get("rail.arcRate");
			while (railArcEmit >= 1) {
				railArcEmit -= 1;
				Arc a = new Arc();
				// shape Rectangle, scale (2, 0.5): x in [-1, 1], y in [-0.25, 0.25]
				a.x = (float) ((Math.random() * 2 - 1) * Tune.get("rail.arcLength") + Tune.get("rail.arcShift"));
				a.y = (float) (Math.random() * 0.5 - 0.25);
				// startRotation: random 0..2 pi
				a.rot = (float) (Math.random() * Math.PI * 2);
				railArcs.add(a);
			}
		} else railArcEmit = 0;
		railArcFrameT -= dt;
		if (railArcFrameT <= 0) {
			railArcFrameT = 0.5f;
			railArcFrame = (int) (Math.random() * 10);
		}
		Matrix4f bone = gun.boneWorld("Armature/Base/FullCharge/Particle System");
		for (java.util.Iterator<Arc> it = railArcs.iterator(); it.hasNext(); ) {
			Arc a = it.next();
			a.age += dt;
			if (a.age >= 1f || !full) {
				it.remove();
				continue;
			}
			if (bone == null) continue;
			// scaling mode Local: the particle system's place and turn from the hierarchy but none of its parents' scale,
			// so the rectangle (and the 1 u particles) are in plain units; the shape's rotation is (0, 90, 90)
			org.joml.Quaternionf q = bone.getNormalizedRotation(new org.joml.Quaternionf());
			Matrix4f at = new Matrix4f().translation(bone.getTranslation(new Vector3f())).rotate(q)
				.rotateY((float) Math.toRadians(90)).rotateZ((float) Math.toRadians(90)).translate(a.x, a.y, 0);
			// colour over lifetime: alpha 1 -> 0
			arcQuad(buffers, pm, new Vector4f(0, 0, 0, 1).mul(at), "electricarcs" + railArcFrame, (float) Tune.get("rail.arcSize"), a.rot, vr, vg, vb, 1f - a.age);
		}
	}

	private static final double RAIL_ARC_LEN = Tune.def("rail.arcLength", 0.75, "railcannon full charge lightning: share of the 2 u spawn area's length used (game: 1)");
	private static final double RAIL_ARC_SHIFT = Tune.def("rail.arcShift", -0.15, "railcannon full charge lightning: spawn area moved along its length, u");
	private static final double RAIL_ARC_FADE = Tune.def("rail.arcFade", 0.25, "railcannon full charge lightning: share of each arc faded out at both ends");

	/** A camera-facing arc sprite turned by `rot`, in three strips whose outer ends fade to clear (the arcs run to the frame's edges). */
	private static void arcQuad(VertexConsumerProvider buffers, Matrix4f pm, Vector4f c, String sprite, float size, float rot, float r, float g, float b, float a) {
		Identifier id = new Identifier(UltraCraft.MOD_ID, "textures/uk/" + sprite + ".png");
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentEmissive(id));
		float h = size / 2, cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
		float f = (float) Math.max(0, Math.min(0.49, Tune.get("rail.arcFade")));
		float[] us = {0, f, 1 - f, 1}, as = {0, a, a, 0};
		for (int i = 0; i < 3; i++) {
			float u0 = us[i], u1 = us[i + 1], a0 = as[i], a1 = as[i + 1];
			float x0 = (u0 * 2 - 1) * h, x1 = (u1 * 2 - 1) * h;
			vtx(vc, pm, arcCorner(c, x0, -h, cs, sn), u0, 1, r, g, b, a0);
			vtx(vc, pm, arcCorner(c, x1, -h, cs, sn), u1, 1, r, g, b, a1);
			vtx(vc, pm, arcCorner(c, x1, h, cs, sn), u1, 0, r, g, b, a1);
			vtx(vc, pm, arcCorner(c, x0, h, cs, sn), u0, 0, r, g, b, a0);
		}
	}

	private static Vector4f arcCorner(Vector4f c, float x, float y, float cs, float sn) {
		return new Vector4f(c.x + x * cs - y * sn, c.y + x * sn + y * cs, c.z, 1);
	}

	private static final double NAIL_DOT = Tune.def("nail.dotGlow", 1, "nailgun: variation-coloured glow dots (T_Nailgun_New_Glow) strength");
	private static final double NAIL_HEAT_NEAR = Tune.def("nail.heatNear", 1.5, "nailgun barrel heat: no glow nearer than this to the camera, u (BarrelHeat _EmissiveFogEnd 1.5)");
	private static final double NAIL_HEAT_FAR = Tune.def("nail.heatFar", 2, "nailgun barrel heat: full glow beyond this, u (_EmissiveFogStart 2)");
	private static final double NAIL_HEAT_NEAR_A = Tune.def("nail.heatNearAlpha", 0, "nailgun barrel heat: how much of the shell shows at the near end (0 = none)");
	private static final double NAIL_TEXT = Tune.def("view.nailTextSize", 20, "nailgun display: ammo count digit height, canvas units (Text: 240 at x0.1)");
	private static final Identifier DIGITS = new Identifier(UltraCraft.MOD_ID, "textures/uk/digits.png");

	/**
	 * A number on a canvas from digits.png (0-9 of the game's VCR OSD Mono, made by build_assets), centred at (cx, cy),
	 * `h` tall, drawn like the other display quads (the world text pipeline came out dark under the shader pack).
	 * @param fy -1 when the canvas reads upside down in our rig
	 */
	private static void digits(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f bone, String s, float cx, float cy, float h, float r, float g, float b, float fy) {
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(DIGITS));
		float w = h * 56f / 85f;
		float x = cx - w * s.length() / 2;
		float yb = (cy - h / 2) * fy, yt = (cy + h / 2) * fy;
		for (int i = 0; i < s.length(); i++) {
			int d = s.charAt(i) - '0';
			if (d >= 0 && d <= 9) rectUV(vc, pm, bone, readX(cx, x), yb, readX(cx, x + w), yt, 0.003f, d / 10f, 0, (d + 1) / 10f, 1, r, g, b, 1);
			x += w;
		}
	}
	private static final double NAIL_TEXT_Y = Tune.def("view.nailTextY", 4, "nailgun display: ammo count height, canvas units (Text y +4)");
	private static float nailSinkFill = 3;
	/** The saw launcher's Shootpoint where it was drawn this frame (view space), for where its saws start. */
	public static Vector3f sawShootView;
	/** The nailgun's two ShootPoints where the gun was drawn this frame (view space), for where its nails start. */
	public static final Vector3f[] nailShootView = new Vector3f[2];
	private static long nailSpinNanos;
	private static float nailSpinAngle;
	private static final double NAIL_SPIN = Tune.def("nail.spinRate", 1, "nailgun barrel spin: degrees per second per unit of Nailgun.spinSpeed (250..2500)");
	private static final double NAIL_HEAT_LIGHT = Tune.def("nail.heatLight", 0.25, "nailgun barrel lights on the gun at full heat (orange)");
	private static final double NAIL_FLIP = Tune.def("view.nailFlip", 1, "nailgun display: 1 = the canvas turned the right way up in our rig (meters at the edge toward you)");

	/**
	 * The Attractor's Canvas (64 x 24): the Image backdrop (white, alpha 0.08), the ammo count (Text, naiAmmo, variation
	 * colour) and three magnet charges along the bottom: a 4 x 4 icon and a 15 x 5 meter each (HeatsinkMeterFilled 2 / 1),
	 * the meter filled horizontally. Nailgun.RefreshHeatSinkFill: the fill follows the charge at 20/s; a full one takes
	 * the variation colour, the one still filling is emptyColor (0.33 grey); the icons copy their meter's colour.
	 */
	private static void nailgunAttractor(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f cb, float ammo, float charge, float dt, float vr, float vg, float vb) {
		VertexConsumer white = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
		rect(white, pm, cb, -32, -12, 32, 12, 0.001f, 1, 1, 1, 0.078f);
		// this Canvas' frame has its y the other way round in our rig (the Text carries InvertCorrection in the game)
		float fy = Tune.on("view.nailFlip") ? -1 : 1;
		float target = Math.max(0, Math.min(3, charge));
		nailSinkFill += Math.signum(target - nailSinkFill) * Math.min(Math.abs(target - nailSinkFill), 20 * dt);
		float[][] icons = {{-31.5f, -27.5f}, {-9.5f, -5.5f}, {12f, 16f}};
		float[][] meters = {{-27f, -12f}, {-5f, 10f}, {16.5f, 31.5f}};
		for (int i = 0; i < 3; i++) {
			float fill = Math.max(0, Math.min(1, nailSinkFill - i));
			boolean full = nailSinkFill >= i + 1;
			float r = full ? vr : 0.33f, g = full ? vg : 0.33f, b = full ? vb : 0.33f;
			// (each texture's buffer fetched where it is used: without a shader pack every layer shares one buffer, and a
			// consumer kept across a switch draws in the other texture)
			rectUV(tex(buffers, ICON2), pm, cb, icons[i][0], -11.5f * fy, icons[i][1], -7.5f * fy, 0.002f, 0, 0, 1, 1, r, g, b, 1);
			if (fill > 0) rectUV(tex(buffers, METER1), pm, cb, meters[i][0], -12 * fy, meters[i][0] + (meters[i][1] - meters[i][0]) * fill, -7 * fy, 0.002f, 0, 0, fill, 1, r, g, b, 1);
		}
		digits(buffers, pm, cb, String.valueOf(Math.round(ammo)), 0, (float) Tune.get("view.nailTextY"), (float) Tune.get("view.nailTextSize"), vr, vg, vb, fy);
	}

	private static final float[] overheatSinkFill = {2, 1};

	/**
	 * The Overheat's Canvas (64 x 24): the Image backdrop (white, alpha 0.08); along the top the heat meter, Panel
	 * ('HeatsinkMeter', 64 x 16) whose colour runs from emptyColor (0.33 grey) to fullColor (1, 0.5, 0) with the heat
	 * (Nailgun.Update: sliderBg = Color.Lerp(empty, full, heatUp); black with no heat and no heat sink left) and in it
	 * Slider (1) (45 x 6 at x -2, fill 'meter 2' (1, 0.5, 0)) filled to the heat; along the bottom the two heat sinks,
	 * Panel and Panel (1) ('HeatsinkMeterFilled', 31 x 7 at x 0 and 32, filled horizontally): RefreshHeatSinkFill, the
	 * fill following the sinks at 20/s, a full one green (0.267, 1, 0.271), the one still filling emptyColor.
	 */
	private static void nailgunOverheat(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f cb, float heat, float sinks, float dt, float fy, boolean oneSink) {
		VertexConsumer white = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
		// (the saw launcher's backdrop is black at 0.08, the nailgun's white)
		float bg = oneSink ? 0 : 1;
		rect(white, pm, cb, -32, -12, 32, 12, 0.001f, bg, bg, bg, 0.078f);
		float h = Math.max(0, Math.min(1, heat));
		// the heat meter's frame
		float fr, fg, fb;
		if (h <= 0 && sinks < 1) { fr = fg = fb = 0; }
		else { fr = 0.33f + (1f - 0.33f) * h; fg = 0.33f + (0.5f - 0.33f) * h; fb = 0.33f * (1 - h); }
		VertexConsumer frame = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/heatsinkmeter.png")));
		rectUV(frame, pm, cb, -32, 12 * fy, 32, -4 * fy, 0.002f, 0, 0, 1, 1, fr, fg, fb, 1);
		// the heat
		if (h > 0) {
			VertexConsumer fill = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/meter_2.png")));
			rectUV(fill, pm, cb, -24.5f, 7 * fy, -24.5f + 45 * h, 1 * fy, 0.003f, 0, 0, 1, 1, 1f, 0.5f, 0f, 1);
		}
		// the heat sinks
		// (the saw launcher's one heat sink is one Panel 64 wide; the nailgun's two are 31 wide, at x 0 and 32)
		int slot = oneSink ? 1 : 0, n = oneSink ? 1 : 2;
		float target = Math.max(0, Math.min(n, sinks));
		overheatSinkFill[slot] += Math.signum(target - overheatSinkFill[slot]) * Math.min(Math.abs(target - overheatSinkFill[slot]), 20 * dt);
		VertexConsumer sink = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/heatsinkmeterfilled.png")));
		for (int i = 0; i < n; i++) {
			float f = Math.max(0, Math.min(1, overheatSinkFill[slot] - i));
			if (f <= 0) continue;
			boolean full = overheatSinkFill[slot] >= i + 1;
			float x0 = -32 + 32 * i, wd = oneSink ? 64 : 31;
			rectUV(sink, pm, cb, x0, -12 * fy, x0 + wd * f, -5 * fy, 0.002f, 0, 0, f, 1, full ? 0.267f : 0.33f, full ? 1f : 0.33f, full ? 0.271f : 0.33f, 1);
		}
	}

	private static final Identifier GLYPHS = new Identifier(UltraCraft.MOD_ID, "textures/uk/glyphs.png");
	private static final Identifier ICON2 = new Identifier(UltraCraft.MOD_ID, "textures/uk/heatsinkmeterfilled_2.png");
	private static final Identifier METER1 = new Identifier(UltraCraft.MOD_ID, "textures/uk/heatsinkmeterfilled_1.png");

	/** The buffer for a display texture, fetched at the point of drawing (see nailgunAttractor). */
	/**
	 * The HologramDisplay's material (UI-Premult: blend One / OneMinusSrcAlpha over UI colour x alpha), unlit like any
	 * UI: an emissive translucent layer, so neither the world's light nor a shader pack darkens it; it writes depth
	 * (shader-pack clouds composite wherever depth still says sky, and covered it).
	 */
	private static VertexConsumer holo(VertexConsumerProvider buffers, Identifier id) {
		int layer = (int) Tune.get(com.ultracraft.client.render.UkLayers.shadersOn() ? "view.holoLayerShaders" : "view.holoLayer");
		return buffers.getBuffer(switch (layer) {
			case 1 -> RenderLayer.getEntityTranslucentCull(id);
			case 2 -> RenderLayer.getText(id);
			case 3 -> RenderLayer.getEntityTranslucentEmissive(id);
			case 4 -> com.ultracraft.client.render.UkLayers.glowDepth(id);
			case 5 -> com.ultracraft.client.render.UkLayers.handText(id);
			case 6 -> com.ultracraft.client.render.UkLayers.handTranslucent(id);
			default -> com.ultracraft.client.render.UkLayers.emissiveDepth(id);
		});
	}

	private static VertexConsumer tex(VertexConsumerProvider buffers, Identifier id) {
		return buffers.getBuffer(RenderLayer.getEntityTranslucentCull(id));
	}
	/** glyphs.png's order (build_assets.export_digit_strip). */
	private static final String GLYPH_ORDER = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.:-";
	private static final double ZAP_TEXT = Tune.def("view.zapTextSize", 5.3, "Jump Start display: status text cell height, canvas units (TMP size 6 x the VCR font's 0.885)");
	private static final double SAW_ZAP_CANVAS = Tune.def("view.sawZapCanvas", 1, "1 = the Jump Start saw launcher's screen on its own Canvas (1) (scale 0.0012 x 0.0025, the prefab's), 0 = the Attractor's");
	private static final double ZAP_CORNER = Tune.def("view.zapCorner", 2, "Jump Start display: the meter sprite's rounded corners, canvas units (the sprite's border: 2)");

	/**
	 * Where a point of a line of text goes along its reading direction, about the line's centre `c`: the off-hand gun is
	 * drawn through a mirror, so its words and numbers are flipped back along the line there and read the right way
	 * round (the line itself keeps its mirrored place on the screen).
	 */
	private static float readX(float c, float x) {
		return OffhandGun.running() ? 2 * c - x : x;
	}

	/** Words on a canvas from glyphs.png (the game's VCR OSD Mono), centred at (cx, cy), `h` tall; like digits(). */
	private static void label(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f bone, String s, float cx, float cy, float h, float out, float r, float g, float b, float fy) {
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(GLYPHS));
		float w = h * 56f / 85f;
		float x = cx - w * s.length() / 2;
		float yb = (cy - h / 2) * fy, yt = (cy + h / 2) * fy;
		int n = GLYPH_ORDER.length();
		for (char ch : s.toUpperCase().toCharArray()) {
			int i = GLYPH_ORDER.indexOf(ch);
			if (i > 0) rectUV(vc, pm, bone, readX(cx, x), yb, readX(cx, x + w), yt, out, i / (float) n, 0, (i + 1) / (float) n, 1, r, g, b, 1);
			x += w;
		}
	}

	/** The Jump Start's DistanceMeter value (it eases toward its target at 10/s with no zapper out). */
	private static float zapDistanceMeter;

	/**
	 * The Jump Start's Canvas (64 x 24), Nailgun.UpdateZapHud: the Image backdrop (white, alpha 0.08); StatusText along
	 * the top (7 high, size 6); DistanceMeter across the middle (10 high: 'meter' 0.1 grey, the fill inset 2.5 / 1 in
	 * (0.5, 0.12, 0.12), a grey cap at the left, the handle (1, 0.235, 0.235) 5 wide, ticks at -16 / 0 / +16); ZapMeter
	 * along the bottom (5 high, 1 up: 'meter' (1, 0.235, 0.235) under a white fill, the black lightningboltsmall); the
	 * warning X (30 x 30, pixelx 1); and over everything while it recharges ZapperRecharge (black, the ZapperTimer
	 * clock 18 x 16 at y -3 filled radially, "RECHARGING..." at the top).
	 * With no zapper: a camera ray onto an enemy under maxDistance - 5 is READY (white, the meter at distance / 15),
	 * further OUT OF RANGE (variation colour), else NO TARGET (grey). With one out: DISTANCE: x.x from white to red
	 * as it nears maxDistance (meter distance / 20), or OUT OF RANGE / BLOCKED with the X flashing white / red.
	 */
	private static void nailgunZapper(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f cb, float recharge, float dt, float vr, float vg, float vb) {
		ZapperClient z = ZapperClient.cur();
		float fy = Tune.on("view.nailFlip") ? -1 : 1;
		float max = ZapperClient.MAX_DISTANCE;
		VertexConsumer white = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
		rect(white, pm, cb, -32, -12, 32, 12, 0.001f, 1, 1, 1, 0.078f);
		String status = "";
		float sr = 1, sg = 1, sb = 1;
		boolean showX = false, xWhite = false, zapOn = false;
		float zapValue = 0;
		// (a hook out shows its distance and charge; the recharge clock comes up once it is gone)
		boolean recharging = recharge < 5 && !z.out();
		if (recharging) zapDistanceMeter = 0;
		else if (!z.out()) {
			float target = 0;
			net.minecraft.client.MinecraftClient c = net.minecraft.client.MinecraftClient.getInstance();
			if (c.player != null && c.world != null) {
				net.minecraft.client.render.Camera cam = c.gameRenderer.getCamera();
				net.minecraft.util.math.Vec3d from = cam.getPos();
				Vector3f lf = cam.getHorizontalPlane();
				net.minecraft.util.math.Vec3d look = new net.minecraft.util.math.Vec3d(lf.x, lf.y, lf.z);
				net.minecraft.util.math.Vec3d to = from.add(look.multiply(256));
				net.minecraft.util.hit.BlockHitResult bh = c.world.raycast(new net.minecraft.world.RaycastContext(from, to, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, c.player));
				if (bh.getType() != net.minecraft.util.hit.HitResult.Type.MISS) to = bh.getPos();
				net.minecraft.util.hit.EntityHitResult eh = net.minecraft.entity.projectile.ProjectileUtil.raycast(c.player, from, to, new net.minecraft.util.math.Box(from, to).expand(1),
					e -> e instanceof net.minecraft.entity.LivingEntity le && le.isAlive() && !(e instanceof net.minecraft.entity.player.PlayerEntity), from.squaredDistanceTo(to));
				if (eh != null) {
					float d = (float) (eh.getPos().distanceTo(from) / com.ultracraft.weapon.Damage.SCALE_BLOCKS);
					if (d < max - 5) { status = "READY"; target = d / (max - 5); }
					else { status = "OUT OF RANGE"; sr = vr; sg = vg; sb = vb; }
				} else { status = "NO TARGET"; sr = sg = sb = 0.5f; }
			}
			zapDistanceMeter += Math.signum(target - zapDistanceMeter) * Math.min(Math.abs(target - zapDistanceMeter), 10 * dt);
		} else {
			zapOn = z.charge > 0;
			zapValue = z.charge / 5;
			if (z.distance > max || z.blocked) {
				showX = true;
				xWhite = z.breakTimer % 0.1f > 0.05f;
				zapDistanceMeter = 1;
				status = z.blocked ? "BLOCKED" : "OUT OF RANGE";
				sr = vr; sg = vg; sb = vb;
			} else {
				zapDistanceMeter = z.distance / max;
				status = String.format(java.util.Locale.ROOT, "DISTANCE: %.1f", z.distance);
				float k = (max - z.distance) / max;
				sr = 1; sg = k; sb = k;
			}
		}
		// ZapperRecharge covers the whole canvas while it recharges: what is under it is not drawn at all (the layer's
		// quads are sorted by distance, so the meter's thin ticks could land on top of the black)
		if (recharging) {
			rect(white, pm, cb, -32, -12, 32, 12, 0.0045f, 0, 0, 0, 1);
			VertexConsumer clock = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/zappertimer.png")));
			squareFill(clock, pm, cb, 0, -3 * fy, 8, 0.005f, recharge / 5, 1, 1, 1, 1);
			label(buffers, pm, cb, "RECHARGING...", 0, 12 - 3.4f, (float) Tune.get("view.zapTextSize") * 6.8f / 6f, 0.005f, 1, 1, 1, fy);
			return;
		}
		// StatusText: the top 7 units, centred
		if (!status.isEmpty()) label(buffers, pm, cb, status, 0, 8.5f, (float) Tune.get("view.zapTextSize"), 0.003f, sr, sg, sb, fy);
		// DistanceMeter: y -5 .. 5 (the white buffer again: the text took the shared one)
		white = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
		float corner = (float) Tune.get("view.zapCorner");
		float v = Math.max(0, Math.min(1, zapDistanceMeter));
		chamferRect(white, pm, cb, -32, -5 * fy, 32, 5 * fy, 0.002f, corner, 0.1f, 0.1f, 0.1f, 1);
		if (v > 0) rect(white, pm, cb, -29.5f, -4 * fy, -29.5f + 59 * v, 4 * fy, 0.0025f, 0.5f, 0.12f, 0.12f, 1);
		chamferRect(white, pm, cb, -32, -5 * fy, -27, 5 * fy, 0.003f, corner, 0.5f, 0.5f, 0.5f, 1);
		float hx = -29.5f + 59 * v;
		chamferRect(white, pm, cb, hx - 2.5f, -5 * fy, hx + 2.5f, 5 * fy, 0.0032f, corner, 1f, 0.235f, 0.235f, 1);
		rect(white, pm, cb, -0.5f, -5 * fy, 0.5f, 5 * fy, 0.0034f, 0.5f, 0.25f, 0.25f, 1);
		rect(white, pm, cb, 15.5f, -5 * fy, 16.5f, 5 * fy, 0.0034f, 0.5f, 0f, 0f, 1);
		rect(white, pm, cb, -16.5f, -5 * fy, -15.5f, 5 * fy, 0.0034f, 0.5f, 0.5f, 0.5f, 1);
		// ZapMeter: y -11 .. -6
		if (zapOn) {
			float zv = Math.max(0, Math.min(1, zapValue));
			chamferRect(white, pm, cb, -32, -11 * fy, 32, -6 * fy, 0.002f, corner, 1f, 0.235f, 0.235f, 1);
			if (zv > 0) chamferRect(white, pm, cb, -32, -11 * fy, -32 + 64 * zv, -6 * fy, 0.0025f, corner, 1, 1, 1, 1);
			VertexConsumer bolt = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/lightningboltsmall.png")));

			rectUV(bolt, pm, cb, -29, -10.5f * fy, -25, -6.5f * fy, 0.003f, 0, 0, 1, 1, 0, 0, 0, 1);
		}
		// the warning X
		if (showX) {
			VertexConsumer xv = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/pixelx_1.png")));
			rectUV(xv, pm, cb, -15, -15 * fy, 15, 15 * fy, 0.004f, 0, 0, 1, 1, 1, xWhite ? 1 : 0, xWhite ? 1 : 0, 1);
		}
	}

	// ---------------------------------------------------------------- the Rocket Launcher's HologramDisplay

	private static final double HOLO_LAYER = Tune.def("view.holoLayer", 3, "Rocket Launcher hologram layer, no shader pack: 0 unlit + depth, 1 lit, 2 text, 3 unlit, 4 additive + depth, 5 text no depth (shader pack: drawn as the hand), 6 lit no depth (as the hand)");
	private static final double HOLO_DEPTH = Tune.def("view.holoDepth", 1, "1 = the hologram's disc writes depth behind it (keeps shader-pack clouds off it)");
	private static final double HOLO_LAYER_SH = Tune.def("view.holoLayerShaders", 3, "the same under a shader pack");
	private static final String[] HOLO_VARIANTS = {"blue", "green", "red"};
	private static final String[] HOLO_PARTS = {"fade", "ring", "marks", "fill", "center", "hand"};
	/** {colour, alpha} per variant and part (HOLO_PARTS order), tuned by the user under Complementary (by eye). */
	private static final float[][][] HOLO_SHADER_DEFAULTS = {
		{{0.7f, 0.5f}, {0.6f, 0.4f}, {0.45f, 1f}, {0.6f, 0.4f}, {0.7f, 1f}, {0.7f, 1f}}, // blue
		{{0.7f, 0.6f}, {0.6f, 0.4f}, {0.6f, 1f}, {0.7f, 1f}, {0.7f, 1f}, {0.7f, 1f}}, // green
		{{0.7f, 0.5f}, {0.6f, 0.4f}, {0.8f, 1f}, {0.7f, 0.8f}, {0.7f, 1f}, {0.7f, 1f}}, // red
	};
	static {
		for (int vi = 0; vi < HOLO_VARIANTS.length; vi++)
			for (int pi = 0; pi < HOLO_PARTS.length; pi++) {
				String v = HOLO_VARIANTS[vi], part = HOLO_PARTS[pi];
				Tune.def("holoShader." + v + "." + part + ".color", HOLO_SHADER_DEFAULTS[vi][pi][0], "Rocket Launcher hologram under a shader pack, " + v + ", " + part + ": colour (its glow there) x this (by eye)");
				Tune.def("holoShader." + v + "." + part + ".alpha", HOLO_SHADER_DEFAULTS[vi][pi][1], "the same part's alpha x this (by eye)");
			}
	}

	/** {colour, alpha} multipliers for one hologram part: 1, 1 without a shader pack. */
	private static float[] holoPart(boolean shaders, String variant, String part) {
		if (!shaders) return new float[] {1, 1};
		return new float[] {(float) Tune.get("holoShader." + variant + "." + part + ".color"), (float) Tune.get("holoShader." + variant + "." + part + ".alpha")};
	}

	private static float c01(float v) {
		return Math.max(0, Math.min(1, v));
	}

	static {
		// the Firestarter's bars: bar 1 alone, the user's pick against the game
		for (int i = 1; i <= 4; i++) Tune.def("holo.redBar" + i, i == 1 ? 1 : 0, "Firestarter clock: bar " + i + " shown (1) or not (0); bar 1 starts at the canvas top, 2 left, 3 bottom, 4 right");
	}

	private static final double HOLO_Y = Tune.def("view.holoY", 0.31, "Rocket Launcher hologram: its anchored y on Body_Bone (the prefab's 0.31; the rig carries only its localPosition)");
	private static final double HOLO_SPIN = Tune.def("view.holoSpin", -1, "Rocket Launcher hologram: -1 = as seen in the game (the Freezeframe counts down clockwise, the S.R.S. fills counterclockwise), 1 = the other way");

	/**
	 * RocketLauncher's HologramDisplay (world-space canvas 128 x 128 at scale 0.001 on Body_Bone), the same on all three:
	 * Fade ('fadetowhite' 100 x 100 at x -30, turned -90), Outer Ring ('RageEffectWhite' over the canvas), Quartermarkers
	 * (four 'verticalgradient' 3 x 50 bars from 6 inside the edges of a 100 box toward the middle; the Napalm one shows only
	 * 3 and 4), Inner Ring ('ShockwaveFilled' 96 x 96, timerMeter: Radial360 from the right, clockwise, fillAmount = the
	 * timer) and Clock Center ('dot' 15 x 15, timerArm: turned Lerp(360, 0, timer) degrees) with its Clock Arm
	 * ('verticalgradientslowfalloff' 5 x 50 at x 30, turned -90). The seven variationColorables take the variation colour
	 * (their own alpha x num); RocketLauncher.Update: Freezeframe timer = rocketFreezeTime / 5; S.R.S. = the cannonball
	 * charge (cbCharge while winding up, the colour going to red with it; half alpha while it recharges); Firestarter =
	 * the fuel (grey below 0.25 when not spraying).
	 */
	private static void rocketHologram(RigInstance gun, VertexConsumerProvider.Immediate buffers, Matrix4f pm, int variation, float vr, float vg, float vb) {
		// (not with the view inside a block: the clock's see-through layers showed the world behind the wall, like x-ray)
		var pl = net.minecraft.client.MinecraftClient.getInstance().player;
		if (pl != null && pl.isInsideWall()) return;
		Matrix4f body = gun.boneWorld("RocketLauncher/Armature/Body_Bone");
		Matrix4f holo = gun.boneWorld("RocketLauncher/Armature/Body_Bone/HologramDisplay");
		if (body == null || holo == null) return;
		// the RectTransform's anchored y (0.31) along Body_Bone: older rig exports left it out of the localPosition, newer
		// ones carry it (adding it again put the clock out in front of the barrel), so only what is missing is added
		Matrix4f local = new Matrix4f(body).invert().mul(holo);
		float missing = (float) Tune.get("view.holoY") - local.m31();
		Matrix4f cb = new Matrix4f(body).translate(0, missing, 0).mul(local);
		ArsenalClient a = ArsenalClient.cur();
		// (the timers step at 20 Hz: shown between last tick and this one)
		float td = net.minecraft.client.MinecraftClient.getInstance().getTickDelta();
		float cbc = a.cbCharge > 0 ? a.prevCbCharge + (a.cbCharge - a.prevCbCharge) * td : 0;
		float value, num = 1, r = vr, g = vg, b = vb;
		if (variation == 0) value = (a.prevRocketFreezeTime + (a.rocketFreezeTime - a.prevRocketFreezeTime) * td) / 5f;
		else if (variation == 1) {
			if (a.cbCharge > 0) {
				value = cbc;
				r = vr + (1 - vr) * cbc; g = vg * (1 - cbc); b = vb * (1 - cbc);
			} else {
				value = a.rocketCannonballCharge >= 1 ? 1 : a.prevRocketCannonballCharge + (a.rocketCannonballCharge - a.prevRocketCannonballCharge) * td;
				if (value < 1) num = 0.5f;
			}
		} else {
			value = a.prevRocketNapalmFuel + (a.rocketNapalmFuel - a.prevRocketNapalmFuel) * td;
			if (!a.firingNapalm && value < 0.25f) { r = g = b = 0.5f; }
		}
		value = Math.max(0, Math.min(1, value));
		// under a shader pack (it takes the unlit layer for a glowing one) each part has its own colour and alpha
		// multipliers per variant, live (holoShader.<blue|green|red>.<part>.color / .alpha; by eye)
		boolean sh = com.ultracraft.client.render.UkLayers.shadersOn();
		String vn = HOLO_VARIANTS[Math.max(0, Math.min(2, variation))];
		Identifier gradTex = new Identifier(UltraCraft.MOD_ID, "textures/uk/verticalgradient.png");
		// Fade, Outer Ring (alpha 0.349)
		float[] m = holoPart(sh, vn, "fade");
		spriteQuad(holo(buffers, new Identifier(UltraCraft.MOD_ID, "textures/uk/fadetowhite.png")), pm, cb, -30, 0, 100, 100, 0.5f, 0.5f, -90, 0.001f, c01(r * m[0]), c01(g * m[0]), c01(b * m[0]), c01(0.349f * num * m[1]));
		m = holoPart(sh, vn, "ring");
		spriteQuad(holo(buffers, new Identifier(UltraCraft.MOD_ID, "textures/uk/rageeffectwhite.png")), pm, cb, 0, 0, 128, 128, 0.5f, 0.5f, 0, 0.0015f, c01(r * m[0]), c01(g * m[0]), c01(b * m[0]), c01(0.349f * num * m[1]));
		// Quartermarkers: {pivot x, pivot y, angle}
		float[][] marks = {{0, 44, 180}, {-44, 0, -90}, {0, -44, 0}, {44, 0, 90}};
		m = holoPart(sh, vn, "marks");
		// (the Firestarter keeps only two, its prefab's 3 and 4; which of ours lands where the game shows them is a live
		// on/off per bar, holo.redBar1..4, tuned with the user)
		for (int i = 0; i < 4; i++) {
			if (variation == 2 && !Tune.on("holo.redBar" + (i + 1))) continue;
			spriteQuad(holo(buffers, gradTex), pm, cb, marks[i][0], marks[i][1], 3, 50, 0.5f, 0, marks[i][2], 0.002f, c01(r * m[0]), c01(g * m[0]), c01(b * m[0]), c01(num * m[1]));
		}
		// Inner Ring: the timer as a Radial360 fill from the right, clockwise
		m = holoPart(sh, vn, "fill");
		radialFill(holo(buffers, new Identifier(UltraCraft.MOD_ID, "textures/uk/shockwavefilled.png")), pm, cb, 0, 0, 48, 0, Tune.get("view.holoSpin") > 0, value, 0.0025f, c01(r * m[0]), c01(g * m[0]), c01(b * m[0]), c01(0.349f * num * m[1]));
		// Clock Center and its arm, turned with the timer (white, not a colourable)
		float turn = (float) ((360 - 360 * value) * Tune.get("view.holoSpin"));
		m = holoPart(sh, vn, "center");
		spriteQuad(holo(buffers, new Identifier(UltraCraft.MOD_ID, "textures/uk/dot.png")), pm, cb, 0, 0, 15, 15, 0.5f, 0.5f, turn, 0.003f, c01(m[0]), c01(m[0]), c01(m[0]), c01(m[1]));
		double tr = Math.toRadians(turn);
		float ax = (float) (30 * Math.cos(tr)), ay = (float) (30 * Math.sin(tr));
		m = holoPart(sh, vn, "hand");
		spriteQuad(holo(buffers, new Identifier(UltraCraft.MOD_ID, "textures/uk/verticalgradientslowfalloff.png")), pm, cb, ax, ay, 5, 50, 0.5f, 0.5f, turn - 90, 0.0035f, c01(m[0]), c01(m[0]), c01(m[0]), c01(m[1]));
		// depth for the disc and its fade, just behind every part (the batches are drawn in no set order, so the parts
		// themselves cannot write depth without hiding each other): shader-pack clouds are composited where depth says sky
		if (Tune.on("view.holoDepth")) {
			// (everything batched so far goes out first - the gun among it: drawn after this mask, the barrel behind the
			// clock failed the depth test and was cut out of the see-through clock)
			buffers.draw();
			// (the parts' own shapes, cut where the sprite is clear: a square mask showed as a hole in the clouds)
			VertexConsumer rv = buffers.getBuffer(com.ultracraft.client.render.UkLayers.depthOnly(new Identifier(UltraCraft.MOD_ID, "textures/uk/rageeffectwhite.png")));
			spriteQuad(rv, pm, cb, 0, 0, 128, 128, 0.5f, 0.5f, 0, 0.0005f, 1, 1, 1, 1);
			VertexConsumer dv = buffers.getBuffer(com.ultracraft.client.render.UkLayers.depthOnly(new Identifier(UltraCraft.MOD_ID, "textures/uk/shockwavefilled.png")));
			spriteQuad(dv, pm, cb, 0, 0, 96, 96, 0.5f, 0.5f, 0, 0.0005f, 1, 1, 1, 1);
			VertexConsumer fv = buffers.getBuffer(com.ultracraft.client.render.UkLayers.depthOnly(new Identifier(UltraCraft.MOD_ID, "textures/uk/fadetowhite.png")));
			spriteQuad(fv, pm, cb, -30, 0, 100, 100, 0.5f, 0.5f, -90, 0.0005f, 1, 1, 1, 1);
		}
	}

	/**
	 * A sprite on a canvas like a UI Image: `w` x `h` with its pivot (px, py, 0..1) at (x, y), turned `deg` degrees
	 * counterclockwise (Unity's z rotation); the image's bottom at the sprite's own -y.
	 */
	private static void spriteQuad(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float x, float y, float w, float h, float px, float py, float deg, float out, float r, float g, float b, float a) {
		double t = Math.toRadians(deg);
		float c = (float) Math.cos(t), sn = (float) Math.sin(t);
		float[][] loc = {{-px * w, -py * h}, {(1 - px) * w, -py * h}, {(1 - px) * w, (1 - py) * h}, {-px * w, (1 - py) * h}};
		float[] xs = new float[4], ys = new float[4];
		for (int i = 0; i < 4; i++) {
			xs[i] = x + loc[i][0] * c - loc[i][1] * sn;
			ys[i] = y + loc[i][0] * sn + loc[i][1] * c;
		}
		quad4(vc, pm, bone, out, xs, ys, new float[] {0, 1, 1, 0}, new float[] {1, 1, 0, 0}, r, g, b, a);
	}

	/**
	 * Image fill Radial360: the square sprite of half size `half` at (cx, cy) shown from angle `start` (radians, 0 = +x,
	 * counterclockwise positive) through `fill` of a turn, clockwise or not.
	 */
	private static void radialFill(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float cx, float cy, float half, float start, boolean clockwise, float fill, float out, float r, float g, float b, float a) {
		radialFill(vc, pm, bone, cx, cy, half, start, clockwise, fill, out, r, g, b, a, false);
	}

	/** oneFace: the slices drawn once (the layers here don't cull; a see-through sprite drawn twice is blended twice). */
	private static void radialFill(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float cx, float cy, float half, float start, boolean clockwise, float fill, float out, float r, float g, float b, float a, boolean oneFace) {
		if (fill <= 0) return;
		float total = (float) (Math.min(1, fill) * Math.PI * 2) * (clockwise ? -1 : 1);
		int segs = Math.max(4, (int) Math.ceil(Math.abs(total) / (Math.PI * 2) * 48));
		for (int i = 0; i < segs; i++) {
			float t0 = start + total * i / segs, t1 = start + total * (i + 1) / segs;
			float[] e0 = squareEdge(t0), e1 = squareEdge(t1);
			float[] xs = {cx, cx + half * e0[0], cx + half * e1[0], cx + half * e1[0]};
			float[] ys = {cy, cy + half * e0[1], cy + half * e1[1], cy + half * e1[1]};
			float[] us = {0.5f, 0.5f + 0.5f * e0[0], 0.5f + 0.5f * e1[0], 0.5f + 0.5f * e1[0]};
			float[] vs = {0.5f, 0.5f - 0.5f * e0[1], 0.5f - 0.5f * e1[1], 0.5f - 0.5f * e1[1]};
			quad4(vc, pm, bone, out, xs, ys, us, vs, r, g, b, a, oneFace);
		}
	}

	/** Where the ray at angle t from the middle of the unit square [-1, 1]^2 leaves it. */
	private static float[] squareEdge(float t) {
		float x = (float) Math.cos(t), y = (float) Math.sin(t);
		float k = 1f / Math.max(Math.abs(x), Math.abs(y));
		return new float[] {x * k, y * k};
	}

	// ---------------------------------------------------------------- the Impact Hammer (ShotgunHammer.LateUpdate / UpdateMeter)

	private static final String H_ROOT = "ImpactHammer/Armature/Root";
	private static long hammerNanos;
	/** The dial's speed and the swing charge, eased every frame (the logic runs at 20 ticks a second). */
	private static float hammerSwingView, hammerMotorAngle, hammerSpeedView;
	private static final java.util.Random HAMMER_RND = new java.util.Random();

	static {
		Tune.def("hammer.meterStart", 180, "Impact Hammer secondary meter: where its fill starts, degrees on the canvas (from the game: Radial360, origin Left = 180)");
		Tune.def("hammer.meterDir", 1, "Impact Hammer secondary meter: 1 = fills counterclockwise from the left as in the prefab (bottom up on the arc), -1 = the other way");
		Tune.def("hammer.meterRot", 180, "Impact Hammer secondary meter: the whole meter turned on the dial, degrees (180 with meterMirror 1 = upside down: our panel frame's y points down; checked on screen against the dial's outline)");
		Tune.def("hammer.meterMirror", 1, "Impact Hammer secondary meter: 1 = mirrored left-right (with meterRot 180: flipped upside down, which puts the mask's arc on the dial's outline)");
		Tune.def("hammer.meterX", 0, "Impact Hammer secondary meter: shift on the dial, canvas units (120 = the dial's width; by eye)");
		Tune.def("hammer.meterY", 0, "Impact Hammer secondary meter: shift on the dial, canvas units (by eye)");
		Tune.def("hammer.meterScale", 1, "Impact Hammer secondary meter: size, x the prefab's 120 canvas units (by eye)");
		Tune.def("hammer.meterCut", 0.5, "Impact Hammer secondary meter: T_DialMask's soft halo cut away below this opacity, 0 = the game's halo (by eye: the halo bled past our blocky dial's outline)");
		Tune.def("hammer.meterAlpha", 1, "Impact Hammer secondary meter: opacity (by eye; with shaders it is drawn unlit but not glowing)");
		Tune.def("hammer.needleOffset", 0, "Impact Hammer dial needle: extra turn, degrees (by eye)");
		Tune.def("hammer.needleDir", 1, "Impact Hammer dial needle: 1 = the game's turn, -1 = the other way (live fix if it comes out mirrored)");
		Tune.def("hammer.swirlAlpha", 1, "Impact Hammer motor swirl opacity, x the game's (speed / 3)");
	}

	private static float hammerDt() {
		long now = System.nanoTime();
		float dt = hammerNanos == 0 ? 0 : Math.min(0.1f, (now - hammerNanos) / 1e9f);
		hammerNanos = now;
		return com.ultracraft.client.uk.ViewModel.paused() ? 0 : dt;
	}

	/**
	 * The hammer's moving parts: its lights in the variation colour (WeaponIcon: _EmissiveColor on T_ImpactHammer_Glow),
	 * the dial glow by tier (T_DialGlow1..3, blinking while the tier waits to drop, dark while overheated), the needle
	 * (Euler(0, 170 - speed x 200, -90)), the hammer pulled 0.65 u down the barrel by the swing charge, the model shaking
	 * by charge / 30, the motor spinning speed x 1500 deg/s and the Sawed-On's chainsaw on its attach point.
	 */
	private static void hammerView(RigInstance gun, int variation, float vr, float vg, float vb) {
		ArsenalClient a = ArsenalClient.cur();
		float dt = hammerDt();
		gun.setSubmeshEmissive("ImpactHammer", 0, "t_impacthammer_glow", vr, vg, vb);
		float glow = a.hammerOverheated ? 0 : a.tierDownTimer > 0 ? ((a.tierDownTimer % 0.1f) >= 0.05f ? 1 : 0) : 1;
		gun.setSubmeshEmissive("ImpactHammer", 1, glow > 0 ? "t_dialglow" + (Math.max(0, Math.min(2, a.tier)) + 1) : null, 1f, 1f, 1f, true);
		// the dial's speed eased here every frame like ShotgunHammer.Update (V1's speed / 60 at 2 a second)
		float target = a.hammerOverheated ? 0 : ArsenalClient.hammerGaugeTarget();
		boolean pulled = WhiplashClient.INSTANCE.state == WhiplashClient.State.PULLING && !WhiplashClient.INSTANCE.lightTarget;
		hammerSpeedView = a.hammerOverheated ? 0 : com.ultracraft.client.style.StyleState.moveTowards(hammerSpeedView, target, (float) Tune.get("hammer.speedEase") * dt);
		if (pulled) hammerSpeedView = Math.min(hammerSpeedView, 0.5f);
		float speed = hammerSpeedView;
		float needle = a.hammerOverheated ? 170 - a.hammerOverheat[variation] / 7f * 200 : 170 - speed * 200;
		gun.setExtraRotation(H_ROOT + "/Dial", new Quaternionf().rotationAxis((float) Math.toRadians((needle + Tune.get("hammer.needleOffset")) * Tune.get("hammer.needleDir")), -1, 0, 0));
		// (the swing charge eases in between ticks; a hit drops it at once)
		hammerSwingView = a.swingCharge <= 0 ? 0 : Math.min(a.swingCharge, hammerSwingView + 2 * dt);
		gun.setBoneOffset(H_ROOT + "/Hammer", new Vector3f(0, -0.65f * hammerSwingView, 0));
		float shake = hammerSwingView / 30 + a.hammerSawForce / 12000f;
		gun.setBoneOffset("ImpactHammer", shake <= 0 ? null : new Vector3f((HAMMER_RND.nextFloat() * 2 - 1) * shake, (HAMMER_RND.nextFloat() * 2 - 1) * shake, (HAMMER_RND.nextFloat() * 2 - 1) * shake));
		hammerMotorAngle = (hammerMotorAngle + speed * 10 * 150 * dt) % 360;
		gun.setExtraRotation(H_ROOT + "/MotorSpinner", new Quaternionf().rotationY((float) Math.toRadians(hammerMotorAngle)));
		// the chainsaw: on the gun only on the Sawed-On with a full saw charge (chainsawAttachPoint active)
		boolean saw = variation == 2 && a.shoSawCharge >= 1f;
		for (int i = 0; i < gun.rig.meshes.size(); i++) {
			String n = gun.rig.meshes.get(i).name;
			if (n.startsWith("Chainsaw")) { if (saw) gun.hiddenMeshes.remove(i); else gun.hiddenMeshes.add(i); }
		}
		gun.setMeshTexture("Chainsaw", "t_chainsaw_red");
		// while it revs the blade takes the motion material (ChainsawLitBladeMotion: the blurred blade)
		gun.setMeshTexture("Chainsaw_Blade", a.hammerSawing ? "t_chainsawblade_blurred" : null);
		Matrix4f apm = gun.boneWorld(H_ROOT + "/ChainSawAttachPoint");
		if (variation == 2) ChainsawFx.attach(apm);
	}

	/**
	 * The hammer's sprites: the motor's SpinSwirl (0.32 u, alpha speed / 3, laid over), the secondary meter (the
	 * T_DialMask Image, Radial360 from the left, filled 0.275..0.625 of a turn for 0 < fill < 1) and the overheat steam.
	 */
	private static void hammerDraw(RigInstance gun, int variation, VertexConsumerProvider.Immediate buffers, Matrix4f pm, float vr, float vg, float vb, float dt) {
		ArsenalClient a = ArsenalClient.cur();
		Matrix4f sw = gun.boneWorld(H_ROOT + "/MotorSpinner/SpinSprite");
		float swirlA = (float) (hammerSpeedView / 3 * Tune.get("hammer.swirlAlpha"));
		if (sw != null && swirlA > 0.004f) {
			VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.solidSmooth(new Identifier(UltraCraft.MOD_ID, "textures/uk/spinswirl.png")));
			float h = 0.16f;
			Vector4f[] q = {pt(sw, -h, -h, 0), pt(sw, h, -h, 0), pt(sw, h, h, 0), pt(sw, -h, h, 0)};
			float[][] uv = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
			for (int i = 0; i < 4; i++) vtx(vc, pm, q[i], uv[i][0], uv[i][1], 1, 1, 1, swirlA);
			for (int i = 3; i >= 0; i--) vtx(vc, pm, q[i], uv[i][0], uv[i][1], 1, 1, 1, swirlA);
		}
		// the secondary meter: the grenade's charge, the pumps (3 = overcharged: red / black with the beep) or the saw
		float fill;
		float r = 1, g = 1, b = 1;
		if (variation == 0) {
			fill = a.shoAltNadeCharge;
			if (fill >= 1) { r = vr; g = vg; b = vb; }
		} else if (variation == 1) {
			fill = a.primaryCharge / 3f;
			if (a.primaryCharge == 3) { fill = 1; boolean off = a.timeToBeep < 0.5f; r = off ? 0 : 1; g = 0; b = 0; }
			else if (a.primaryCharge == 1) { r = vr; g = vg; b = vb; }
			else if (a.primaryCharge == 2) { r = 1; g = 1; b = 0; }
		} else if (a.hammerSawing) {
			float k = a.hammerSawForce / 60f;
			fill = k;
			r = vr + (1 - vr) * k; g = vg + (0.25f - vg) * k; b = vb + (0.25f - vb) * k;
		} else {
			fill = a.shoSawCharge;
			if (fill >= 1) { r = vr; g = vg; b = vb; }
		}
		float shown = fill >= 1 ? 1 : fill <= 0 ? 0 : 0.275f + (0.625f - 0.275f) * fill;
		Matrix4f panel = gun.boneWorld(H_ROOT + "/Canvas/Panel");
		if (panel != null && shown > 0) {
			// (not the emissive program: a shader pack makes that glow, and the blue meter blew out)
			VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.handTranslucent(meterMask()));
			Matrix4f m = new Matrix4f(panel).translate((float) Tune.get("hammer.meterX"), (float) Tune.get("hammer.meterY"), 0)
				.rotateZ((float) Math.toRadians(Tune.get("hammer.meterRot")));
			float sc = (float) Tune.get("hammer.meterScale");
			m.scale(Tune.on("hammer.meterMirror") ? -sc : sc, sc, 1);
			float dir = (float) Tune.get("hammer.meterDir");
			radialFill(vc, pm, m, 0, 0, 60, (float) Math.toRadians(Tune.get("hammer.meterStart")), dir < 0, shown, 0, r, g, b, (float) Tune.get("hammer.meterAlpha"), true);
		}
		// overheated: the Particle System on the barrel (Smoke, 125/s, 0.25 s, 50 along its z at its 0.1 scale)
		if (a.hammerOverheated) hammerSteam(gun, dt);
		drawSteam(gun, buffers, pm);
	}

	private static final Identifier METER_MASK = new Identifier(UltraCraft.MOD_ID, "textures/uk/t_dialmask.png");
	private static final Identifier METER_MASK_CUT = new Identifier(UltraCraft.MOD_ID, "dynamic/t_dialmask_cut");
	private static net.minecraft.client.texture.NativeImageBackedTexture meterCutTex;
	private static double meterCutMade = -1;

	/** T_DialMask, or a copy made from it with its halo cut away (hammer.meterCut), rebuilt when the setting changes. */
	private static Identifier meterMask() {
		double cut = Tune.get("hammer.meterCut");
		if (cut <= 0) return METER_MASK;
		if (cut != meterCutMade) {
			meterCutMade = cut;
			try (java.io.InputStream in = MinecraftClient.getInstance().getResourceManager().getResource(METER_MASK).orElseThrow().getInputStream()) {
				net.minecraft.client.texture.NativeImage img = net.minecraft.client.texture.NativeImage.read(in);
				int t = (int) Math.round(cut * 255);
				for (int y = 0; y < img.getHeight(); y++)
					for (int x = 0; x < img.getWidth(); x++) {
						int c = img.getColor(x, y);
						img.setColor(x, y, ((c >>> 24) >= t ? 0xFF000000 : 0) | (c & 0xFFFFFF));
					}
				if (meterCutTex == null) {
					meterCutTex = new net.minecraft.client.texture.NativeImageBackedTexture(img);
					MinecraftClient.getInstance().getTextureManager().registerTexture(METER_MASK_CUT, meterCutTex);
				} else {
					meterCutTex.setImage(img);
					meterCutTex.upload();
				}
			} catch (Exception e) {
				UltraCraft.LOG.warn("hammer meter mask: {}", e.toString());
				return METER_MASK;
			}
		}
		return meterCutTex == null ? METER_MASK : METER_MASK_CUT;
	}

	private static float hammerSteamDebt;

	private static void hammerSteam(RigInstance gun, float dt) {
		hammerSteamDebt += 125 * dt;
		java.util.Random rnd = HAMMER_RND;
		for (; hammerSteamDebt >= 1; hammerSteamDebt--) {
			Puff pf = new Puff();
			pf.bone = H_ROOT + "/Particle System";
			double ang = Math.toRadians(15) * Math.sqrt(rnd.nextDouble()), rot = rnd.nextDouble() * Math.PI * 2;
			float sp = 50;
			pf.vx = (float) (Math.sin(ang) * Math.cos(rot)) * sp; pf.vy = (float) (Math.sin(ang) * Math.sin(rot)) * sp; pf.vz = (float) Math.cos(ang) * sp;
			pf.life = 0.25f;
			Matrix4f b = gun.boneWorld(pf.bone);
			pf.size = 3f * (b == null ? 0.1f : b.getScale(new Vector3f()).x);
			puffs.add(pf);
		}
	}

	// ---------------------------------------------------------------- the Sawblade Launchers' screens (Canvas (1))
	// Their canvas is turned on the gun and scaled 0.001 x 0.002, and much of what is on it is turned back -90 degrees
	// with its own scale: the corners below are the prefab's, composed into the canvas' own units (y up).

	private static final double SAW_FLIP = Tune.def("view.sawFlip", -1, "saw launcher screens: -1 = the canvas as the game shows it (it comes out mirrored in our rig, like the nailgun's), 1 = as in the prefab's data");
	private static final double SAW_DEPTH = Tune.def("view.sawScreenDepth", 0.008, "Attractor saw launcher screen: how far it sits into the gun, view units (by eye)");
	private static final double SAW_SX = Tune.def("view.sawScreenX", 0, "Attractor saw launcher screen: shift along the canvas x, canvas units (by eye)");
	private static final double SAW_SY = Tune.def("view.sawScreenY", 2, "Attractor saw launcher screen: shift along the canvas y, canvas units (by eye)");
	private static final double SAW_SS = Tune.def("view.sawScreenScale", 1, "Attractor saw launcher screen: size (1 = the prefab's, by eye)");
	private static final double SAW_TEXT_H = Tune.def("view.sawTextH", 53, "Attractor saw launcher: saw count glyph height, canvas units (Text size 240 x scale 0.25 x the font's 0.885)");
	private static final double SAW_TEXT_W = Tune.def("view.sawTextW", 21, "Attractor saw launcher: saw count glyph width, canvas units (240 x scale 0.15 x the font's 0.583)");

	/** A quad through four canvas points (y up), texture corners u / v (v 0 at the image's top), both faces. */
	private static void quad4(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float out, float[] xs, float[] ys, float[] us, float[] vs, float r, float g, float b, float a) {
		quad4(vc, pm, bone, out, xs, ys, us, vs, r, g, b, a, false);
	}

	private static void quad4(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float out, float[] xs, float[] ys, float[] us, float[] vs, float r, float g, float b, float a, boolean oneFace) {
		Vector3f n = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		Vector3f c = bone.getTranslation(new Vector3f());
		if (n.dot(c) > 0) n.negate();
		Vector3f off = n.mul(outBase + out * 4 * (float) Tune.get("view.displayOut"));
		Vector4f[] p = new Vector4f[4];
		for (int i = 0; i < 4; i++) p[i] = pt(bone, xs[i], -ys[i], 0).add(off.x, off.y, off.z, 0);
		for (int i = 0; i < 4; i++) vtx(vc, pm, p[i], us[i], vs[i], r, g, b, a);
		if (!oneFace) for (int i = 3; i >= 0; i--) vtx(vc, pm, p[i], us[i], vs[i], r, g, b, a);
	}

	/**
	 * Lines of text from glyphs.png in a turned frame: centred at (cx, cy), advancing along (advX, advY), glyphs w x h,
	 * lines stacked downward (against the frame's up, the advance turned +90 degrees) `lineH` apart.
	 */
	private static void labelTurned(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f bone, java.util.List<String> lines, float cx, float cy,
			float w, float h, float lineH, float advX, float advY, float out, float r, float g, float b, float fy) {
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(GLYPHS));
		float upX = -advY, upY = advX;
		int n = GLYPH_ORDER.length();
		for (int li = 0; li < lines.size(); li++) {
			String s = lines.get(li);
			float lineOff = ((lines.size() - 1) / 2f - li) * lineH;
			float start = -w * s.length() / 2;
			for (int k = 0; k < s.length(); k++) {
				int gi = GLYPH_ORDER.indexOf(Character.toUpperCase(s.charAt(k)));
				if (gi <= 0) continue;
				float a0 = readX(0, start + k * w), a1 = readX(0, start + (k + 1) * w);
				float[][] tc = {{a0, lineOff - h / 2}, {a1, lineOff - h / 2}, {a1, lineOff + h / 2}, {a0, lineOff + h / 2}};
				float[] xs = new float[4], ys = new float[4];
				for (int j = 0; j < 4; j++) {
					xs[j] = cx + advX * tc[j][0] + upX * tc[j][1];
					ys[j] = (cy + advY * tc[j][0] + upY * tc[j][1]) * fy;
				}
				float u0 = gi / (float) n, u1 = (gi + 1) / (float) n;
				quad4(vc, pm, bone, out, xs, ys, new float[] {u0, u1, u1, u0}, new float[] {1, 1, 0, 0}, r, g, b, 1);
			}
		}
	}

	/** TMP auto-size with word wrap into a box `len` long and `thick` deep: the lines and the size (from max down to min). */
	private static Object[] fitText(String s, float len, float thick, float max, float min) {
		for (float size = max; ; size -= 0.25f) {
			float w = size * 56f / 96f;
			int perLine = Math.max(1, (int) Math.floor(len / w));
			java.util.List<String> lines = new java.util.ArrayList<>();
			StringBuilder cur = new StringBuilder();
			boolean fits = true;
			for (String word : s.split(" ")) {
				if (word.length() > perLine) fits = false;
				if (cur.length() == 0) cur.append(word);
				else if (cur.length() + 1 + word.length() <= perLine) cur.append(' ').append(word);
				else { lines.add(cur.toString()); cur = new StringBuilder(word); }
			}
			if (cur.length() > 0) lines.add(cur.toString());
			if (fits && lines.size() * size <= thick) return new Object[] {lines, size};
			if (size - 0.25f < min) return new Object[] {lines, size};
		}
	}

	private static float sawSinkFill = 3;

	/**
	 * The Attractor saw launcher: the saw count (Text, size 240, variation colour, turned -90 at (18, 0)) and the magnet
	 * column (Image (1): backdrop white 0.08; Meter 1-3 15 x 10 upright, filled from the top; Magnet Icon 1-3 over them),
	 * like the nailgun's (RefreshHeatSinkFill: 20/s, full ones in the variation colour, the filling one grey 0.33).
	 */
	private static void sawAttractor(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f cb, float saws, float charge, float dt, float vr, float vg, float vb) {
		float fy = (float) Tune.get("view.sawFlip");
		VertexConsumer white = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
		rect(white, pm, cb, -58, -14 * fy, -10, 10 * fy, 0.001f, 1, 1, 1, 0.078f);
		float target = Math.max(0, Math.min(3, charge));
		sawSinkFill += Math.signum(target - sawSinkFill) * Math.min(Math.abs(target - sawSinkFill), 20 * dt);
		for (int i = 0; i < 3; i++) {
			float fill = Math.max(0, Math.min(1, sawSinkFill - i));
			boolean full = sawSinkFill >= i + 1;
			float r = full ? vr : 0.33f, g = full ? vg : 0.33f, b = full ? vb : 0.33f;
			float x1 = -10 - 10 * i, x0 = x1 - 10;
			// the meter's own left (u 0) is at the top, its own top (v 0) toward +x
			if (fill > 0) {
				float yb = 5 - 15 * fill;
				quad4(tex(buffers, METER1), pm, cb, 0.002f, new float[] {x1, x1, x0, x0}, new float[] {5 * fy, yb * fy, yb * fy, 5 * fy},
					new float[] {0, fill, fill, 0}, new float[] {0, 0, 1, 1}, r, g, b, 1);
			}
			float ix1 = x1 - 1, ix0 = ix1 - 8;
			quad4(tex(buffers, ICON2), pm, cb, 0.002f, new float[] {ix1, ix1, ix0, ix0}, new float[] {9.5f * fy, 5.5f * fy, 5.5f * fy, 9.5f * fy},
				new float[] {0, 1, 1, 0}, new float[] {0, 0, 1, 1}, r, g, b, 1);
		}
		labelTurned(buffers, pm, cb, java.util.List.of(String.valueOf(Math.round(saws))), 18, 0, (float) Tune.get("view.sawTextW"), (float) Tune.get("view.sawTextH"), 0,
			0, -1, 0.003f, vr, vg, vb, fy);
	}

	/** The Jump Start's screen state (Nailgun.UpdateZapHud); `alt` takes the saw launcher's words. */
	private record ZapHud(String status, float sr, float sg, float sb, boolean showX, boolean xWhite, boolean zapOn, float zapValue, boolean recharging) {}

	private static ZapHud zapHud(float recharge, float dt, float vr, float vg, float vb, boolean alt) {
		ZapperClient z = ZapperClient.cur();
		float max = ZapperClient.MAX_DISTANCE;
		String status = "";
		float sr = 1, sg = 1, sb = 1;
		boolean showX = false, xWhite = false, zapOn = false;
		float zapValue = 0;
		boolean recharging = recharge < 5 && !z.out();
		if (recharging) zapDistanceMeter = 0;
		else if (!z.out()) {
			float target = 0;
			net.minecraft.client.MinecraftClient c = net.minecraft.client.MinecraftClient.getInstance();
			if (c.player != null && c.world != null) {
				net.minecraft.client.render.Camera cam = c.gameRenderer.getCamera();
				net.minecraft.util.math.Vec3d from = cam.getPos();
				Vector3f lf = cam.getHorizontalPlane();
				net.minecraft.util.math.Vec3d to = from.add(new net.minecraft.util.math.Vec3d(lf.x, lf.y, lf.z).multiply(256));
				net.minecraft.util.hit.BlockHitResult bh = c.world.raycast(new net.minecraft.world.RaycastContext(from, to, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, c.player));
				if (bh.getType() != net.minecraft.util.hit.HitResult.Type.MISS) to = bh.getPos();
				net.minecraft.util.hit.EntityHitResult eh = net.minecraft.entity.projectile.ProjectileUtil.raycast(c.player, from, to, new net.minecraft.util.math.Box(from, to).expand(1),
					e -> e instanceof net.minecraft.entity.LivingEntity le && le.isAlive() && !(e instanceof net.minecraft.entity.player.PlayerEntity), from.squaredDistanceTo(to));
				if (eh != null) {
					float d = (float) (eh.getPos().distanceTo(from) / com.ultracraft.weapon.Damage.SCALE_BLOCKS);
					if (d < max - 5) { status = "READY"; target = d / (max - 5); }
					else { status = alt ? "TOO FAR" : "OUT OF RANGE"; sr = vr; sg = vg; sb = vb; }
				} else { status = alt ? "NULL" : "NO TARGET"; sr = sg = sb = 0.5f; }
			}
			zapDistanceMeter += Math.signum(target - zapDistanceMeter) * Math.min(Math.abs(target - zapDistanceMeter), 10 * dt);
		} else {
			zapOn = z.charge > 0;
			zapValue = z.charge / 5;
			if (z.distance > max || z.blocked) {
				showX = true;
				xWhite = z.breakTimer % 0.1f > 0.05f;
				zapDistanceMeter = 1;
				status = z.blocked ? "BLOCKED" : alt ? "TOO FAR" : "OUT OF RANGE";
				sr = vr; sg = vg; sb = vb;
			} else {
				zapDistanceMeter = z.distance / max;
				status = (alt ? "" : "DISTANCE: ") + String.format(java.util.Locale.ROOT, "%.1f", z.distance);
				float k = (max - z.distance) / max;
				sr = 1; sg = k; sb = k;
			}
		}
		return new ZapHud(status, sr, sg, sb, showX, xWhite, zapOn, zapValue, recharging);
	}

	/**
	 * The Jump Start saw launcher (Image (2)): ZapMeter and DistanceMeter 52 wide from the left, the status turned -90
	 * in the strip on the right (auto-sized 13.65 down to 6, wrapped), the X, and ZapperRecharge (the clock 32 x 20,
	 * RECHARGING turned in the strip from x 11).
	 */
	private static void sawZapper(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f cb, float recharge, float dt, float vr, float vg, float vb) {
		float fy = (float) Tune.get("view.sawFlip");
		ZapHud hud = zapHud(recharge, dt, vr, vg, vb, true);
		VertexConsumer white = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
		rect(white, pm, cb, -32, -12, 32, 12, 0.001f, 1, 1, 1, 0.078f);
		if (hud.recharging()) {
			rect(white, pm, cb, -32, -12, 32, 12, 0.0045f, 0, 0, 0, 1);
			VertexConsumer clock = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/zappertimer.png")));
			squareFill(clock, pm, cb, 0, 0, 10, 0.005f, recharge / 5, 1, 1, 1, 1);
			Object[] ft = fitText("RECHARGING", 24, 21, 8.2f, 4f);
			float s = (Float) ft[1];
			@SuppressWarnings("unchecked") java.util.List<String> lines = (java.util.List<String>) ft[0];
			labelTurned(buffers, pm, cb, lines, 21.5f, 0, s * 56f / 96f, s * 0.885f, s, 0, -1, 0.005f, 1, 1, 1, fy);
			return;
		}
		float corner = (float) Tune.get("view.zapCorner");
		if (hud.zapOn()) {
			float zv = Math.max(0, Math.min(1, hud.zapValue()));
			chamferRect(white, pm, cb, -32, -7.5f * fy, 20, 7.5f * fy, 0.002f, corner, 1f, 0.235f, 0.235f, 1);
			if (zv > 0) chamferRect(white, pm, cb, -32, -7.5f * fy, -32 + 52 * zv, 7.5f * fy, 0.0022f, corner, 1, 1, 1, 1);
		}
		float v = Math.max(0, Math.min(1, zapDistanceMeter));
		chamferRect(white, pm, cb, -32, -5 * fy, 20, 5 * fy, 0.0025f, corner, 0.1f, 0.1f, 0.1f, 1);
		if (v > 0) rect(white, pm, cb, -29.5f, -4 * fy, -29.5f + 47 * v, 4 * fy, 0.003f, 0.5f, 0.12f, 0.12f, 1);
		chamferRect(white, pm, cb, -32, -5 * fy, -27, 5 * fy, 0.0032f, corner, 0.5f, 0.5f, 0.5f, 1);
		float hx = -29.5f + 47 * v;
		chamferRect(white, pm, cb, hx - 2.5f, -5 * fy, hx + 2.5f, 5 * fy, 0.0034f, corner, 1f, 0.235f, 0.235f, 1);
		rect(white, pm, cb, -6.5f, -5 * fy, -5.5f, 5 * fy, 0.0036f, 0.5f, 0.25f, 0.25f, 1);
		rect(white, pm, cb, 9.5f, -5 * fy, 10.5f, 5 * fy, 0.0036f, 0.5f, 0f, 0f, 1);
		rect(white, pm, cb, -22.5f, -5 * fy, -21.5f, 5 * fy, 0.0036f, 0.5f, 0.5f, 0.5f, 1);
		if (!hud.status().isEmpty()) {
			Object[] ft = fitText(hud.status(), 25, 12, 13.65f, 6f);
			float s = (Float) ft[1];
			@SuppressWarnings("unchecked") java.util.List<String> lines = (java.util.List<String>) ft[0];
			labelTurned(buffers, pm, cb, lines, 26, 0, s * 56f / 96f, s * 0.885f, s, 0, -1, 0.0038f, hud.sr(), hud.sg(), hud.sb(), fy);
		}
		if (hud.showX()) {
			VertexConsumer xv = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/pixelx_1.png")));
			rectUV(xv, pm, cb, -30, -15 * fy, 30, 15 * fy, 0.004f, 0, 0, 1, 1, 1, hud.xWhite() ? 1 : 0, hud.xWhite() ? 1 : 0, 1);
		}
	}

	/** Draw the canvases, charge glow and muzzle flash in the same transform the rig was drawn with. */
	/** 0 everything; 1 all but the rocket launcher's clock (a shader pack's solid hand pass); 2 only the clock. */
	public static int holoMode;

	public static void draw(RigInstance gun, WeaponKind kind, int variation, boolean alt, MatrixStack ms, VertexConsumerProvider.Immediate buffers, float scale) {
		if (!Tune.on("view.displays")) return;
		// under water the gun wobbles (UnderwaterFx): its displays take the very same wave, or they swam off the gun
		if (gun != null && gun.wobbleAmp > 0) buffers = new WobbleBuffers(buffers, gun);
		long now = System.nanoTime();
		// (each hand's own frame time: the off-hand gun's displays are drawn right after the main gun's)
		int hand = OffhandGun.running() ? 1 : 0;
		float dt = lastNanos[hand] == 0 ? 0 : Math.min(0.1f, (now - lastNanos[hand]) / 1e9f);
		if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		lastNanos[hand] = now;
		ms.push();
		ms.scale(scale, scale, scale);
		Matrix4f pm = ms.peek().getPositionMatrix();
		WeaponClient w = WeaponClient.cur();
		ArsenalClient a = ArsenalClient.cur();
		int rgb = UltraCraftClient.VARIATION_COLORS[Math.min(2, variation)];
		float vr = ((rgb >> 16) & 255) / 255f, vg = ((rgb >> 8) & 255) / 255f, vb = (rgb & 255) / 255f;
		if (kind == WeaponKind.RAILCANNON) railArcs(gun, buffers, pm, a.raicharge, dt, vr, vg, vb);
		// (under a shader pack the clock waits for the see-through hand pass, after the water: HandRenderer.clockPass)
		if (holoMode == 2) {
			if (kind == WeaponKind.ROCKET && gun != null) rocketHologram(gun, buffers, pm, variation, vr, vg, vb);
			sprayFlash(gun, kind, variation, buffers, pm, dt);
			ms.pop();
			buffers.draw();
			return;
		}
		if (kind == WeaponKind.ROCKET && gun != null && holoMode != 1) rocketHologram(gun, buffers, pm, variation, vr, vg, vb);
		if (kind == WeaponKind.SHOTGUN && alt && gun != null) hammerDraw(gun, variation, buffers, pm, vr, vg, vb, dt);
		if (gun != null && Tune.on("view.displaysDebug") && (debugTick++ % 60) == 0) {
			for (String bp : new String[] {"GunArmature/MainBone/FrontBone/Canvas (2)", "Armature/Main/Canvas", "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/Monitor", "ShootPoint"}) {
				Matrix4f b = gun.boneWorld(bp);
				if (b != null) UltraCraft.LOG.info("[displays] {} {} at {} scale {}", gun.rigName(), bp, b.getTranslation(new Vector3f()), b.getScale(new Vector3f()));
			}
		}
		if (gun != null) {
			if (kind == WeaponKind.REVOLVER) {
				String monitor = alt ? "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/Monitor (1)" : "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/Monitor";
				Matrix4f mb = gun.boneWorld(monitor);
				if (mb != null && variation == 1) {
					// Marksman (Revolver.CheckCoinCharges): four 5x5 `dot` sprites, each anchored to a corner of the 15x15
					// canvas and 2 in from it, so their centres sit at (+-3, +-3) with a 1 unit gap; fillAmount = charge/100 - i,
					// Radial360 from the bottom clockwise on a canvas scaled (-x, -y), i.e. clockwise from the top on screen;
					// red while filling, the variation colour once full, nothing while empty
					VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
					rect(vc, pm, mb, -0.5f, -0.5f, 0.5f, 0.5f, 0.001f, 0, 0, 0, 1);
					VertexConsumer dv = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/dot.png")));
					float coinCharge = w.rev1charge;
					float dp = (float) Tune.get("view.dotPos") / 15f;
					float[][] dots = {{-dp, dp}, {dp, dp}, {-dp, -dp}, {dp, -dp}};
					for (int i = 0; i < 4; i++) {
						// CheckCoinCharges: charge / 300 is the alternate Sharpshooter's; the Marksman always fills dot by dot
						float fill = Math.max(0, Math.min(1, coinCharge / 100f - i));
						boolean full = fill >= 1;
						float r = full ? vr : 1f, g = full ? vg : 0f, b = full ? vb : 0f;
						disc(dv, pm, mb, dots[i][0], dots[i][1], 2.5f / 15, 0.002f, fill, r, g, b, 1);
					}
				} else if (mb != null && variation == 2 && alt) {
					// alternate Sharpshooter: Background 1 ('meter 7', 11x11, (0.21, 0.04, 0.04)) under Meter 1 ('targetSquare',
					// 11x11, Radial360 from the bottom clockwise) filled by charge / 300: grey while filling, the variation
					// colour when full; both centred on the 15x15 canvas
					VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
					rect(vc, pm, mb, -0.5f, -0.5f, 0.5f, 0.5f, 0.001f, 0, 0, 0, 1);
					float hs = Tune.getF("view.altSharpSize") / 30f, ox = Tune.getF("view.altSharpX") / 15f, oy = Tune.getF("view.altSharpY") / 15f;
					float mxs = Tune.on("view.canvasMirror") ? -1 : 1;
					VertexConsumer bg = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/meter_7.png")));
					rect(bg, pm, mb, mxs * ox - hs, oy - hs, mxs * ox + hs, oy + hs, 0.002f, 0.2075f, 0.042f, 0.042f, 1);
					float fill = Math.max(0, Math.min(1, w.rev2charge / 300f));
					boolean full = fill >= 1;
					VertexConsumer tv = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(new Identifier(UltraCraft.MOD_ID, "textures/uk/targetsquare.png")));
					squareFill(tv, pm, mb, ox, -oy, hs, 0.003f, fill, full ? vr : 0.5f, full ? vg : 0.5f, full ? vb : 0.5f, 1);
				} else if (mb != null && variation == 2) {
					// Sharpshooter: Background 1-3 (3.75x11, anchored bottom-right with a right/bottom pivot at x -1 / -5.625 /
					// -10.25, y 2, i.e. from -5.5 to 5.5 on the 15x15 canvas: centred; colour (0.21, 0.04, 0.04)) under
					// Meter 1-3 filled bottom-up by charge/100 - i; grey while filling, (1, 0.235, 0.235) once full
					VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
					rect(vc, pm, mb, -0.5f, -0.5f, 0.5f, 0.5f, 0.001f, 0, 0, 0, 1);
					float charge = w.rev2charge;
					float mx = Tune.on("view.canvasMirror") ? -1 : 1;
					// live (canvas units of 15): view.sharpX / sharpY move the group, sharpGap is the centre spacing,
					// sharpW / sharpH the size of each line
					float sw = Tune.getF("view.sharpW"), sh = Tune.getF("view.sharpH"), gap = Tune.getF("view.sharpGap");
					float ox = Tune.getF("view.sharpX"), oy = Tune.getF("view.sharpY");
					for (int i = 0; i < 3; i++) {
						float cx = mx * (gap * (1 - i) + ox) / 15f, half = sw / 30f, y0 = (oy - sh / 2) / 15f, y1 = (oy + sh / 2) / 15f;
						rect(vc, pm, mb, cx - half, y0, cx + half, y1, 0.002f, 0.2075f, 0.042f, 0.042f, 1);
						float fill = Math.max(0, Math.min(1, charge / 100f - i));
						boolean full = fill >= 1;
						if (fill > 0) rect(vc, pm, mb, cx - half, y1 - (y1 - y0) * fill, cx + half, y1, 0.003f, full ? 1f : 0.5f, full ? 0.235f : 0.5f, full ? 0.235f : 0.5f, 1);
					}
				}
				// Sharpshooter TwirlSprite: 'splash 2' (a blurred ring, 0.32 u at 100 px/u, scale 0.381) on Revolver_Bone, so it
				// spins with the gun; colour (1, 1, 1, min(2, floor(charge / 25)) / 3) while spinning
				if (variation == 2 && (w.chargingPierce || w.twirlRecovery)) {
					float ta = Math.min(2, (float) Math.floor(w.pierceShotCharge / 25)) / 3f * (float) Tune.get("view.twirlAlpha");
					Matrix4f tb = gun.boneWorld("Armature/Upper Arm/Forearm/Hand/Revolver_Bone");
					if (ta > 0.003f && tb != null && !alt) {
						float hs = 0.16f * 0.381f * (float) Tune.get("view.twirlSize");
						VertexConsumer tv = buffers.getBuffer(RenderLayer.getEntityTranslucentEmissive(new Identifier(UltraCraft.MOD_ID, "textures/uk/splash_2.png")));
						vtx(tv, pm, pt(tb, -hs, -hs, 0), 0, 1, 1, 1, 1, ta);
						vtx(tv, pm, pt(tb, hs, -hs, 0), 1, 1, 1, 1, 1, ta);
						vtx(tv, pm, pt(tb, hs, hs, 0), 1, 0, 1, 1, 1, ta);
						vtx(tv, pm, pt(tb, -hs, hs, 0), 0, 0, 1, 1, 1, ta);
					} else if (ta > 0.003f && tb != null) {
						// the alternate's TwirlSprite: at (0, -0.0079, -0.0068) on Revolver_Bone, turned -90 about y (so it
						// lies across the bone's y / z, the plane it spins in about x), scale 0.5
						float hs = 0.16f * 0.5f * (float) Tune.get("view.twirlSize");
						float oy = -0.0079f, oz = -0.0068f;
						VertexConsumer tv = buffers.getBuffer(RenderLayer.getEntityTranslucentEmissive(new Identifier(UltraCraft.MOD_ID, "textures/uk/splash_2.png")));
						vtx(tv, pm, pt(tb, 0, oy - hs, oz - hs), 0, 1, 1, 1, 1, ta);
						vtx(tv, pm, pt(tb, 0, oy - hs, oz + hs), 1, 1, 1, 1, 1, ta);
						vtx(tv, pm, pt(tb, 0, oy + hs, oz + hs), 1, 0, 1, 1, 1, ta);
						vtx(tv, pm, pt(tb, 0, oy + hs, oz - hs), 0, 0, 1, 1, 1, ta);
					}
				}
				// Piercer charge glow at the shoot point (ChargeEffect: 'charge' sprite, blue light, scale 0.02 × charge)
				if (variation == 0 && w.pierceShotCharge > 0) {
					Matrix4f sp = gun.boneWorld(alt ? "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/ShootPoint (1)" : "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/ShootPoint");
					if (sp != null) billboard(buffers, pm, sp, "charge", (float) (w.pierceShotCharge * Tune.get("view.chargeSize")), 0f, 0.63f, 1f, 0.9f);
				}
			} else if (kind == WeaponKind.SHOTGUN && !alt) {
				drawSteam(gun, buffers, pm);
				Matrix4f cb = gun.boneWorld("GunArmature/MainBone/FrontBone/Canvas (2)");
				if (Tune.on("view.displaysDebug") && System.nanoTime() - lastBranchLog2 > 1_000_000_000L) { lastBranchLog2 = System.nanoTime(); UltraCraft.LOG.info("[displays] shotgun branch cb={} n={} corner={}", cb == null ? "null" : cb.getTranslation(new Vector3f()), cb == null ? "-" : cb.transformDirection(new Vector3f(0, 0, 1)).normalize(), cb == null ? "-" : pt(cb, -30, -10, 0)); }
				outBase = Tune.getF("view.shotgunScreenOut");
				if (cb != null) cb = new Matrix4f(cb).translate(Tune.getF("view.shotgunScreenX"), Tune.getF("view.shotgunScreenY"), 0);
				if (cb != null) {
					// Canvas (2) 60×20: black panel, the slider's meter, ticks at 0 / ±15
					VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucentCull(WHITE));
					rect(vc, pm, cb, -30, -10, 30, 10, 0.001f, 0, 0, 0, 1);
					float value, r, g, b;
					if (variation == 1) {
						// value charge x 20 (0 still shows the slider's 10-unit nub: one bar), colour lerp(the variation green,
						// (1, .25, .25), charge / 2); at 3 full red, black while timeToBeep < 0.5 (a blink every 0.2 s)
						value = a.primaryCharge == 3 ? 60 : a.primaryCharge * 20;
						float k = Math.min(1f, a.primaryCharge / 2f);
						r = vr + (1 - vr) * k; g = vg + (0.25f - vg) * k; b = vb + (0.25f - vb) * k;
						// one charge: a clean yellow (the plain halfway blend read as a dirty green-yellow)
						if (a.primaryCharge == 1) { r = Tune.getF("view.pumpYellowR"); g = Tune.getF("view.pumpYellowG"); b = Tune.getF("view.pumpYellowB"); }
						if (a.primaryCharge == 3) { boolean off = a.pumpBeep < 0.5f; r = off ? 0 : 1; g = 0; b = 0; }
					} else if (a.grenadeForce > 0) {
						value = a.grenadeForce;
						float k = a.grenadeForce / 60f;
						r = vr + (1 - vr) * k; g = vg + (0.25f - vg) * k; b = vb + (0.25f - vb) * k;
					} else if (variation == 0) {
						value = 60; r = vr; g = vg; b = vb;
					} else {
						value = a.shoSawCharge * 60;
						boolean full = a.shoSawCharge >= 1;
						r = full ? vr : 0.5f; g = full ? vg : 0.5f; b = full ? vb : 0.5f;
					}
					// Slider: Fill Area 50 wide (sizeDelta -10), Fill sizeDelta (10, 0) with anchorMax.x = value: the fill spans
					// the full 20 height and 10 + 50 * value of the 60 width, from the slider's start (here x = 30)
					// the Core Eject's meter stays empty until the cores are back (ShootSinks: meterOverride until ReadyGun)
					// (and with right click held, straight from empty into the charge: no full bar in between)
					boolean hideFill = variation == 0 && a.grenadeForce <= 0 && (a.resettingCores || com.ultracraft.client.UKKeys.USE.held);
					if (!hideFill) {
						// the Fill is the 'meter' sprite, 9-sliced with a 2 px border: rounded corners at its two ends
						float x1 = 30, x0 = x1 - (10 + 50 * Math.max(0, Math.min(1, value / 60f)));
						// the Pump Charge fills whole cells: one per charge, plus the first
						if (variation == 1) x0 = x1 - 15 * (Math.min(3, a.primaryCharge) + 1);
						// the rounded ends as geometry: the 16 px sprite's corners sampled into a sawtooth at this size
						float tr = Math.min(1f, r * Tune.getF("view.shotgunTintR")), tg = Math.min(1f, g * Tune.getF("view.shotgunTintG")), tb = Math.min(1f, b * Tune.getF("view.shotgunTintB"));
						// rounded only at the meter's own two ends (x = 30, and x = -30 when it is full); where the lit part
						// stops inside the meter its end is square
						roundRect(vc, pm, cb, x0, -10, x1, 10, 0.002f, Tune.getF("view.shotgunCorner"), x0 <= -29.99f, true, tr, tg, tb, 1);
					}
					// the dividers just in front of the fill (a bigger gap shifted them against it at this angle, leaving a
					// strip of colour along the edge), a little taller than it; view.shotgunTickY nudges them
					float ty = Tune.getF("view.shotgunTickY");
					for (float tx : new float[] {-15, 0, 15}) rect(vc, pm, cb, tx - 1, -11 + ty, tx + 1, 11 + ty, 0.0022f, 0, 0, 0, 1);
				}
				outBase = 0;
			} else if (kind == WeaponKind.NAILGUN) {
				if (!alt) {
					Matrix4f s0 = gun.boneWorld("ShootPoint"), s1 = gun.boneWorld("ShootPoint (1)");
					nailShootView[0] = s0 == null ? null : s0.getTranslation(new Vector3f());
					nailShootView[1] = s1 == null ? null : s1.getTranslation(new Vector3f());
					// the Jump Start's cable leaves from Armature/Main/ZapperPosition (its prefab only; our rig is the Attractor's)
					Matrix4f main = variation == 2 ? gun.boneWorld("Armature/Main") : null;
					if (main != null) {
						ZapperClient.setStart(main.transformPosition(new Vector3f(0, 0.00384f, -0.00216f)));
					}
				}
				if (alt) {
					// the saw launcher's Shootpoint, where its saws leave
					Matrix4f sp = gun.boneWorld("Shootpoint");
					sawShootView = sp == null ? null : sp.getTranslation(new Vector3f());
				}
				if (alt && variation == 2) {
					// the saw launcher's cable leaves from Armature/Base/ZapperAttach (0, 0.45, 0.25)
					Matrix4f base = gun.boneWorld("Armature/Base");
					if (base != null) {
						ZapperClient.setStart(base.transformPosition(new Vector3f(0, 0.45f, 0.25f)));
					}
				}
				Matrix4f cb = gun.boneWorld(alt ? "Armature/Base/Canvas (1)" : "Armature/Main/Canvas");
				// the Attractor saw launcher's screen: live nudges (canvas units) and size, tuned by eye on the gun
				if (cb != null && alt && variation == 0) {
					float ss = Tune.getF("view.sawScreenScale");
					cb = new Matrix4f(cb).translate(Tune.getF("view.sawScreenX"), Tune.getF("view.sawScreenY"), 0).scale(ss, ss, 1);
				}
				// the Jump Start saw launcher's Canvas (1) is not the Attractor's the rig was exported from: scale 0.0012 x 0.0025
				// (0.001 x 0.002) and anchored 0.0021 lower, 0.0015 further out, on the same gun; in the Attractor's canvas
				// units that is 2.58 along x and 1.2 x 1.25 bigger
				if (cb != null && alt && variation == 2 && Tune.on("view.sawZapCanvas")) cb = new Matrix4f(cb).translate(-2.575f, 0, 0.0168f).scale(1.2f, 1.25f, 1);
				if (Tune.on("view.displaysDebug") && System.nanoTime() - lastBranchLog > 1_000_000_000L) { lastBranchLog = System.nanoTime(); UltraCraft.LOG.info("[displays] nailgun branch alt={} cb={}", alt, cb == null ? "null" : cb.getTranslation(new Vector3f())); }
				// the Attractor saw launcher's screen sits a little into the gun (its count poked out over the middle part)
				outBase = alt && variation == 0 ? -(float) Tune.get("view.sawScreenDepth") : 0;
				if (cb != null && !alt && variation == 0) nailgunAttractor(buffers, pm, cb, a.naiAmmo, a.naiMagnetCharge, dt, vr, vg, vb);
				else if (cb != null && !alt && variation == 1) nailgunOverheat(buffers, pm, cb, a.heatUp, a.heatSinks, dt, Tune.on("view.nailFlip") ? -1 : 1, false);
				else if (cb != null && !alt && variation == 2) nailgunZapper(buffers, pm, cb, a.naiZapperRecharge, dt, vr, vg, vb);
				else if (cb != null && alt && variation == 0) sawAttractor(buffers, pm, cb, a.naiSaws, a.naiMagnetCharge, dt, vr, vg, vb);
				else if (cb != null && alt && variation == 1) nailgunOverheat(buffers, pm, cb, a.heatUp, a.heatSinks, dt, (float) Tune.get("view.sawFlip"), true);
				else if (cb != null && alt && variation == 2) sawZapper(buffers, pm, cb, a.naiZapperRecharge, dt, vr, vg, vb);
				outBase = 0;
			}
		}
		// (under a shader pack the spray flash waits for the late see-through hand pass, like the clock: drawn here the pack's
		// water and clouds were laid over it)
		if (holoMode == 0) sprayFlash(gun, kind, variation, buffers, pm, dt);
		// muzzle flash
		if (flashTime > 0 && flashSprite != null && gun != null && gun.rig != null && flashRig != null && flashRig.equals(gun.rigName())) {
			// the flash belongs to the beam, not the gun: it stays where the shot left the barrel while the gun kicks
			if (flashFixed == null) {
				Matrix4f fb = flashLocal != null ? com.ultracraft.client.uk.ViewModel.mountMatrix(gun.rig, new Matrix4f()).translate(flashLocal) : gun.boneWorld(flashBone);
				if (fb != null) {
					flashFixed = new Matrix4f(fb);
					int h = OffhandGun.running() ? 1 : 0;
					shotPoint[h] = flashFixed;
					shotPointGame[h] = flashStartGame;
				}
			}
			if (flashFixed != null) {
				float k = (float) Tune.get("view.flashYellow");
				// SpriteController: shrinks and fades at 10/s; the white-cored sprite's yellow comes back with the tint
				// SpriteController: scale and alpha fall at 10/s; the flash is gone after ~0.05 s, at half size and half
				// opacity (frame by frame from the game)
				// by the clock (the draw can run more than once a frame)
				float p = Math.max(0f, Math.min(1f, (float) (com.ultracraft.client.uk.ViewModel.gameSeconds() - flashStartGame) / Math.max(1e-3f, flashDur)));
				float end = (float) Tune.get("view.flashEnd");
				float size0 = (float) (Tune.get("view.flashSize") * flashSize);
				if ((int) Tune.get("view.flashLayer") == 2) {
					// on the screen layer, after the shader pack (its own colours, in front of the gun): project the
					// barrel point once, at the shot, with the hand projection
					if (screenFlashNanos != flashStartNanos) {
						Vector4f c = new Vector4f(0, 0, 0, 1).mul(flashFixed);
						Vector4f vp = new Vector4f(c.x, c.y, -c.z, 1).mul(pm);
						Vector4f ve = new Vector4f(c.x + size0 / 2, c.y, -c.z, 1).mul(pm);
						Matrix4f proj = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
						Vector4f pa = proj.transform(new Vector4f(vp)), pb = proj.transform(new Vector4f(ve));
						if (pa.w > 1e-4f && pb.w > 1e-4f) {
							screenFlashView = new org.joml.Vector3f(vp.x, vp.y, vp.z);
							screenFlashViewHalf = (float) Math.sqrt((ve.x - vp.x) * (ve.x - vp.x) + (ve.y - vp.y) * (ve.y - vp.y) + (ve.z - vp.z) * (ve.z - vp.z));
							screenFlashRig = gun.poseKey != null ? gun.poseKey : gun.rigName();
							screenFlashX = pa.x / pa.w;
							screenFlashY = pa.y / pa.w;
							screenFlashHalf = Math.abs(pb.x / pb.w - pa.x / pa.w);
							screenFlashNanos = flashStartNanos;
							screenFlashGame = flashStartGame;
							screenFlashDur = flashDur;
							screenFlashEnd = flashEndOverride;
							screenFlashAlpha = flashAlphaOverride;
							screenFlashSprite = flashSprite;
							screenFlashSpin = (float) (Math.random() * Math.PI * 2);
							screenFlashDouble = flashDouble;
						}
					}
				} else {
					billboard(buffers, pm, flashFixed, flashSprite, size0 * (1f - (1f - end) * p), 1f, 1f - 0.1f * k, 1f - 0.75f * k, Tune.on("view.flashSolid") ? 1f : 1f - (1f - end) * p);
				}
			}
		}
		if (flashTime > 0 && com.ultracraft.client.uk.ViewModel.gameSeconds() - flashStartGame >= flashDur) flashTime = 0;
		ms.pop();
		buffers.draw();
	}

	private static final long[] lastNanos = new long[2];
	private static long cylNanos;
	private static float cylAngle, cylTarget;
	private static boolean cylFree;

	/** RevolverCylinder.DoTurn (Revolver.Shoot): advance one chamber. */
	public static void cylinderTurn() {
		if (!cylFree) cylTarget = Math.round(cylAngle / 120f) * 120f + 120f;
	}

	private static float cylDt() {
		long now = System.nanoTime();
		float dt = cylNanos == 0 ? 0 : Math.min(0.1f, (now - cylNanos) / 1e9f);
		cylNanos = now;
		return com.ultracraft.client.uk.ViewModel.paused() ? 0 : dt;
	}

	/**
	 * Revolver.Update while charging the Piercer: localPosition = default + pierceShotCharge / 250 × Random(−0.05, 0.05)
	 * on each axis (Unity units), a new random offset every frame.
	 */
	private static final long[] shakeFrame = {-1, -1};
	private static final Vector3f[] shakeThisFrame = {new Vector3f(), new Vector3f()};

	/**
	 * The frame's shake, drawn the same in both of a shader pack's hand passes (see HandRenderer.frame); each gun its
	 * own (the off-hand copy's, under OffhandGun.run, reads its own charges).
	 */
	public static Vector3f shake(WeaponKind kind, int variation) {
		int h = OffhandGun.running() ? 1 : 0;
		if (shakeFrame[h] != HandRenderer.frame) {
			shakeFrame[h] = HandRenderer.frame;
			shakeThisFrame[h] = shakeNow(kind, variation);
		}
		return new Vector3f(shakeThisFrame[h]);
	}

	private static Vector3f shakeNow(WeaponKind kind, int variation) {
		WeaponClient w = WeaponClient.cur();
		if (com.ultracraft.client.uk.ViewModel.paused()) return new Vector3f();
		float a;
		if (kind == WeaponKind.REVOLVER && variation == 0 && w.pierceShotCharge > 0) a = w.pierceShotCharge / 250f * 0.05f * (float) Tune.get("view.chargeShake");
		// Shotgun.Update while charging: localPosition = default + Random(-f, f) per axis, f = grenadeForce / 3000
		// (Sawed-On: / 12000)
		else if (kind == WeaponKind.SHOTGUN && ArsenalClient.cur().grenadeForce > 0 && !ArsenalClient.cur().alt)
			a = ArsenalClient.cur().grenadeForce / (variation == 2 ? 12000f : 3000f) * (float) Tune.get("view.chargeShake");
		// Railcannon.Update at full charge: localPosition = default + Random(-0.005, 0.005) per axis, every frame
		else if (kind == WeaponKind.RAILCANNON && ArsenalClient.cur().raicharge >= 5 && !com.ultracraft.client.v1.V1.INSTANCE.frozen())
			a = 0.005f * (float) Tune.get("rail.shake");
		// RocketLauncher.Update while the S.R.S. winds up: localPosition = default + Random(-f, f) per axis, f = cbCharge / 100
		else if (kind == WeaponKind.ROCKET && variation == 1 && ArsenalClient.cur().cbCharge > 0)
			a = ArsenalClient.cur().cbCharge / 100f * (float) Tune.get("view.chargeShake");
		else return new Vector3f();
		java.util.Random r = java.util.concurrent.ThreadLocalRandom.current();
		return new Vector3f((r.nextFloat() * 2 - 1) * a, (r.nextFloat() * 2 - 1) * a, (r.nextFloat() * 2 - 1) * a);
	}
	private static final double SHAKE = Tune.def("view.chargeShake", 1, "multiplier on the Piercer charge shake (game: ±0.05 u × charge/250)");
	private static int debugTick;
	private static long lastBranchLog, lastBranchLog2;
	private static final double DEBUG = Tune.def("view.displaysDebug", 0, "1 = log the display bone frames once a second");
	private static final double SAW_SCALE = Tune.def("view.sawScale", 1, "Sawed-On chainsaw model scale about its node (prefab: 1)");
	private static final double SAW_X = Tune.def("view.sawX", 0, "Sawed-On chainsaw node offset x, attach-point units (prefab: 0)");
	private static final double SAW_Y = Tune.def("view.sawY", 0, "Sawed-On chainsaw node offset y (prefab: 0)");
	private static final double SAW_Z = Tune.def("view.sawZ", 0, "Sawed-On chainsaw node offset z (prefab: 0)");
	private static final double TEXT = Tune.def("view.displayText", 0, "1 = draw the on-gun ammo number (work in progress)");
	private static final double OUT = Tune.def("view.displayOut", 1, "multiplier on how far the on-gun panels float off the gun surface (debug)");

	/** Extra lift of a whole display off its bone toward the camera. */
	private static float outBase;

	/** A rectangle with round corners of radius `rad` (canvas units), as flat geometry on the WHITE layer. */
	private static void roundRect(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float x0, float y0, float x1, float y1, float out, float rad, float r, float g, float b, float a) {
		roundRect(vc, pm, bone, x0, y0, x1, y1, out, rad, true, true, r, g, b, a);
	}

	/** roundRect with the corners rounded only at the x0 end and / or the x1 end. */
	private static void roundRect(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float x0, float y0, float x1, float y1, float out, float rad, boolean round0, boolean round1, float r, float g, float b, float a) {
		float c = Math.max(0, Math.min(rad, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
		if (c < 1e-4f || (!round0 && !round1)) { rect(vc, pm, bone, x0, y0, x1, y1, out, r, g, b, a); return; }
		if (Tune.on("view.shotgunCornerPixel")) {
			// the 'meter' sprite's corner in two pixel steps (its rows: inset 2 px, then 1 px): straight edges only, so
			// the corners keep the pixel look instead of an aliased curve; a square end keeps its full rows
			float u = c / 2;
			float a1 = round0 ? u : 0, a2 = round0 ? 2 * u : 0, b1 = round1 ? u : 0, b2 = round1 ? 2 * u : 0;
			rect(vc, pm, bone, x0, y0 + 2 * u, x1, y1 - 2 * u, out, r, g, b, a);
			rect(vc, pm, bone, x0 + a1, y0 + u, x1 - b1, y0 + 2 * u, out, r, g, b, a);
			rect(vc, pm, bone, x0 + a2, y0, x1 - b2, y0 + u, out, r, g, b, a);
			rect(vc, pm, bone, x0 + a1, y1 - 2 * u, x1 - b1, y1 - u, out, r, g, b, a);
			rect(vc, pm, bone, x0 + a2, y1 - u, x1 - b2, y1, out, r, g, b, a);
			return;
		}
		if (!round0 || !round1) { rect(vc, pm, bone, x0, y0, x1, y1, out, r, g, b, a); return; }
		rect(vc, pm, bone, x0 + c, y0, x1 - c, y1, out, r, g, b, a);
		rect(vc, pm, bone, x0, y0 + c, x0 + c, y1 - c, out, r, g, b, a);
		rect(vc, pm, bone, x1 - c, y0 + c, x1, y1 - c, out, r, g, b, a);
		float[][] centres = {{x0 + c, y0 + c, (float) Math.PI}, {x1 - c, y0 + c, (float) (Math.PI * 1.5)}, {x1 - c, y1 - c, 0f}, {x0 + c, y1 - c, (float) (Math.PI * 0.5)}};
		int segs = 8;
		for (float[] ce : centres) {
			for (int i = 0; i < segs; i++) {
				float t0 = ce[2] + (float) (Math.PI / 2) * i / segs, t1 = ce[2] + (float) (Math.PI / 2) * (i + 1) / segs;
				float ax = ce[0] + c * (float) Math.cos(t0), ay = ce[1] + c * (float) Math.sin(t0);
				float bx = ce[0] + c * (float) Math.cos(t1), by = ce[1] + c * (float) Math.sin(t1);
				tri(vc, pm, bone, out, ce[0], ce[1], ax, ay, bx, by, r, g, b, a);
			}
		}
	}

	/**
	 * The 'meter' sprite sliced (border 2): a box with its four corners cut off at 45 degrees, `c` deep (its corner pixels,
	 * smoothed as the game's filtering shows them). On the 5-wide distance meter handles it makes the diamond-ended
	 * markers, and the ends of the Jump Start's meters close in to a point.
	 */
	private static void chamferRect(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float x0, float y0, float x1, float y1, float out, float c0, float r, float g, float b, float a) {
		if (x1 < x0) { float t = x0; x0 = x1; x1 = t; }
		if (y1 < y0) { float t = y0; y0 = y1; y1 = t; }
		float c = Math.max(0, Math.min(c0, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
		if (c < 1e-4f) { rect(vc, pm, bone, x0, y0, x1, y1, out, r, g, b, a); return; }
		rect(vc, pm, bone, x0 + c, y0, x1 - c, y1, out, r, g, b, a);
		rect(vc, pm, bone, x0, y0 + c, x0 + c, y1 - c, out, r, g, b, a);
		rect(vc, pm, bone, x1 - c, y0 + c, x1, y1 - c, out, r, g, b, a);
		tri(vc, pm, bone, out, x0, y0 + c, x0 + c, y0 + c, x0 + c, y0, r, g, b, a);
		tri(vc, pm, bone, out, x1 - c, y0, x1 - c, y0 + c, x1, y0 + c, r, g, b, a);
		tri(vc, pm, bone, out, x1, y1 - c, x1 - c, y1 - c, x1 - c, y1, r, g, b, a);
		tri(vc, pm, bone, out, x0 + c, y1, x0 + c, y1 - c, x0, y1 - c, r, g, b, a);
	}

	/** One flat triangle in a bone's canvas (as a degenerate quad, both faces), rect()'s placement. */
	private static void tri(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float out, float ax, float ay, float bx, float by, float cx, float cy, float r, float g, float b, float a) {
		Vector3f n = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		Vector3f c = bone.getTranslation(new Vector3f());
		if (n.dot(c) > 0) n.negate();
		Vector3f off = n.mul(outBase + out * 4 * (float) Tune.get("view.displayOut"));
		Vector4f p0 = pt(bone, ax, -ay, 0).add(off.x, off.y, off.z, 0), p1 = pt(bone, bx, -by, 0).add(off.x, off.y, off.z, 0), p2 = pt(bone, cx, -cy, 0).add(off.x, off.y, off.z, 0);
		vtx(vc, pm, p0, 0, 0, r, g, b, a); vtx(vc, pm, p1, 0, 0, r, g, b, a); vtx(vc, pm, p2, 0, 0, r, g, b, a); vtx(vc, pm, p2, 0, 0, r, g, b, a);
		vtx(vc, pm, p2, 0, 0, r, g, b, a); vtx(vc, pm, p1, 0, 0, r, g, b, a); vtx(vc, pm, p0, 0, 0, r, g, b, a); vtx(vc, pm, p0, 0, 0, r, g, b, a);
	}

	/** A 9-sliced sprite over a canvas rectangle: corners `c` canvas units, `ub` of the texture each side. */
	private static void sliced(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float x0, float y0, float x1, float y1, float out, float c, float ub, float r, float g, float b, float a) {
		float cx = Math.min(c, (x1 - x0) / 2), cy = Math.min(c, (y1 - y0) / 2);
		float[] xs = {x0, x0 + cx, x1 - cx, x1}, ys = {y0, y0 + cy, y1 - cy, y1}, us = {0, ub, 1 - ub, 1};
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
			if (xs[i + 1] - xs[i] < 1e-5f || ys[j + 1] - ys[j] < 1e-5f) continue;
			rectUV(vc, pm, bone, xs[i], ys[j], xs[i + 1], ys[j + 1], out, us[i], us[j], us[i + 1], us[j + 1], r, g, b, a);
		}
	}

	/** rect() with texture coordinates (u along x, v along y, v 0 at y1 like rect's). */
	private static void rectUV(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float x0, float y0, float x1, float y1, float out, float u0, float v0, float u1, float v1, float r, float g, float b, float a) {
		Vector3f n = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		Vector3f c = bone.getTranslation(new Vector3f());
		if (n.dot(c) > 0) n.negate();
		Vector3f off = n.mul(outBase + out * 4 * (float) Tune.get("view.displayOut"));
		float ya = -y0, yb = -y1;
		Vector4f p0 = pt(bone, x0, ya, 0).add(off.x, off.y, off.z, 0), p1 = pt(bone, x1, ya, 0).add(off.x, off.y, off.z, 0), p2 = pt(bone, x1, yb, 0).add(off.x, off.y, off.z, 0), p3 = pt(bone, x0, yb, 0).add(off.x, off.y, off.z, 0);
		vtx(vc, pm, p0, u0, 1 - v0, r, g, b, a); vtx(vc, pm, p1, u1, 1 - v0, r, g, b, a); vtx(vc, pm, p2, u1, 1 - v1, r, g, b, a); vtx(vc, pm, p3, u0, 1 - v1, r, g, b, a);
		vtx(vc, pm, p3, u0, 1 - v1, r, g, b, a); vtx(vc, pm, p2, u1, 1 - v1, r, g, b, a); vtx(vc, pm, p1, u1, 1 - v0, r, g, b, a); vtx(vc, pm, p0, u0, 1 - v0, r, g, b, a);
	}

	/** A rectangle in a bone's local XY plane (canvas units), `out` u in front of it toward the camera. */
	private static void rect(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float x0, float y0, float x1, float y1, float out, float r, float g, float b, float a) {
		Vector3f n = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		Vector3f c = bone.getTranslation(new Vector3f());
		if (n.dot(c) > 0) n.negate(); // the camera is at the origin: face it
		Vector3f off = n.mul(outBase + out * 4 * (float) Tune.get("view.displayOut"));
		if (Tune.on("view.displaysDebug") && (debugTick % 60) == 1) UltraCraft.LOG.info("[displays] rect bone at {} n={} off={} p0={}", bone.getTranslation(new Vector3f()), n, off, pt(bone, x0, y0, 0));
		y0 = -y0; y1 = -y1;
		Vector4f p0 = pt(bone, x0, y0, 0).add(off.x, off.y, off.z, 0), p1 = pt(bone, x1, y0, 0).add(off.x, off.y, off.z, 0), p2 = pt(bone, x1, y1, 0).add(off.x, off.y, off.z, 0), p3 = pt(bone, x0, y1, 0).add(off.x, off.y, off.z, 0);
		vtx(vc, pm, p0, 0, 1, r, g, b, a); vtx(vc, pm, p1, 1, 1, r, g, b, a); vtx(vc, pm, p2, 1, 0, r, g, b, a); vtx(vc, pm, p3, 0, 0, r, g, b, a);
		vtx(vc, pm, p3, 0, 0, r, g, b, a); vtx(vc, pm, p2, 1, 0, r, g, b, a); vtx(vc, pm, p1, 1, 1, r, g, b, a); vtx(vc, pm, p0, 0, 1, r, g, b, a);
	}

	/**
	 * A sprite disc of radius `rad` at (cx, cy) on a bone's canvas (y up), filled clockwise as seen on screen from
	 * 12 o'clock up to `fill` (Unity Image fill Radial360 / origin Top / clockwise). `view.canvasMirror` flips the
	 * canvas x like the gun canvases appear (the rig's z mirror reverses their handedness).
	 */
	private static void disc(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float cx, float cy, float rad, float out, float fill, float r, float g, float b, float a) {
		if (fill <= 0) return;
		Vector3f n = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		Vector3f c = bone.getTranslation(new Vector3f());
		if (n.dot(c) > 0) n.negate();
		Vector3f off = n.mul(out * 4 * (float) Tune.get("view.displayOut"));
		float mx = Tune.on("view.canvasMirror") ? -1 : 1;
		float f = Math.min(1, fill);
		int segs = Math.max(1, (int) Math.ceil(24 * f));
		float total = (float) (f * Math.PI * 2);
		Vector4f pc = pt(bone, cx * mx, -cy, 0).add(off.x, off.y, off.z, 0);
		for (int i = 0; i < segs; i++) {
			float t0 = total * i / segs, t1 = total * (i + 1) / segs;
			// clockwise on screen from the top (the dots' own placement shows this canvas reads x right, y up after the
			// mirror and the -y below, so the sweep needs sin(t) * mx across and -cos(t) up)
			float sx0 = (float) Math.sin(t0) * mx, sy0 = (float) -Math.cos(t0), sx1 = (float) Math.sin(t1) * mx, sy1 = (float) -Math.cos(t1);
			Vector4f p0 = pt(bone, (cx + rad * sx0) * mx, -(cy + rad * sy0), 0).add(off.x, off.y, off.z, 0);
			Vector4f p1 = pt(bone, (cx + rad * sx1) * mx, -(cy + rad * sy1), 0).add(off.x, off.y, off.z, 0);
			float u0 = 0.5f + 0.5f * sx0 * mx, v0 = 0.5f - 0.5f * sy0, u1 = 0.5f + 0.5f * sx1 * mx, v1 = 0.5f - 0.5f * sy1;
			vtx(vc, pm, pc, 0.5f, 0.5f, r, g, b, a); vtx(vc, pm, p0, u0, v0, r, g, b, a); vtx(vc, pm, p1, u1, v1, r, g, b, a); vtx(vc, pm, p1, u1, v1, r, g, b, a);
			vtx(vc, pm, p1, u1, v1, r, g, b, a); vtx(vc, pm, p0, u0, v0, r, g, b, a); vtx(vc, pm, pc, 0.5f, 0.5f, r, g, b, a); vtx(vc, pm, pc, 0.5f, 0.5f, r, g, b, a);
		}
	}

	/**
	 * A square sprite of half size `hs` at (cx, cy) filled like `disc` (Radial360, clockwise on screen from the top),
	 * the sweep running out to the square's edges and corners (Image fill on a square sprite).
	 */
	private static void squareFill(VertexConsumer vc, Matrix4f pm, Matrix4f bone, float cx, float cy, float hs, float out, float fill, float r, float g, float b, float a) {
		if (fill <= 0) return;
		Vector3f n = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		Vector3f c = bone.getTranslation(new Vector3f());
		if (n.dot(c) > 0) n.negate();
		Vector3f off = n.mul(out * 4 * (float) Tune.get("view.displayOut"));
		float mx = Tune.on("view.canvasMirror") ? -1 : 1;
		float total = (float) (Math.min(1, fill) * Math.PI * 2);
		java.util.List<Float> ts = new java.util.ArrayList<>();
		int segs = 32;
		for (int i = 0; i <= segs; i++) ts.add(total * i / segs);
		for (int k = 0; k < 4; k++) { float t = (float) (Math.PI / 4 + k * Math.PI / 2); if (t < total) ts.add(t); }
		java.util.Collections.sort(ts);
		Vector4f pc = pt(bone, cx * mx, -cy, 0).add(off.x, off.y, off.z, 0);
		for (int i = 0; i + 1 < ts.size(); i++) {
			float t0 = ts.get(i), t1 = ts.get(i + 1);
			if (t1 - t0 < 1e-5f) continue;
			float[] e0 = edge(t0, mx), e1 = edge(t1, mx);
			Vector4f p0 = pt(bone, (cx + hs * e0[0]) * mx, -(cy + hs * e0[1]), 0).add(off.x, off.y, off.z, 0);
			Vector4f p1 = pt(bone, (cx + hs * e1[0]) * mx, -(cy + hs * e1[1]), 0).add(off.x, off.y, off.z, 0);
			float u0 = 0.5f + 0.5f * e0[0] * mx, v0 = 0.5f - 0.5f * e0[1], u1 = 0.5f + 0.5f * e1[0] * mx, v1 = 0.5f - 0.5f * e1[1];
			vtx(vc, pm, pc, 0.5f, 0.5f, r, g, b, a); vtx(vc, pm, p0, u0, v0, r, g, b, a); vtx(vc, pm, p1, u1, v1, r, g, b, a); vtx(vc, pm, p1, u1, v1, r, g, b, a);
			vtx(vc, pm, p1, u1, v1, r, g, b, a); vtx(vc, pm, p0, u0, v0, r, g, b, a); vtx(vc, pm, pc, 0.5f, 0.5f, r, g, b, a); vtx(vc, pm, pc, 0.5f, 0.5f, r, g, b, a);
		}
	}

	/** The point on the unit square's edge at sweep angle t (same orientation as disc()). */
	private static float[] edge(float t, float mx) {
		float sx = (float) Math.sin(t) * mx, sy = (float) -Math.cos(t);
		float k = 1f / Math.max(Math.abs(sx), Math.abs(sy));
		return new float[] {sx * k, sy * k};
	}

	// ---------------------------------------------------------------- steam (Shotgun.ReleaseHeat -> heatReleaseParticles)
	private static final class Puff {
		String bone;
		float x, y, z, vx, vy, vz, age, life, size;
	}

	private static final java.util.List<Puff> puffs = new java.util.ArrayList<>();
	private static long puffNanos;

	/**
	 * Shotgun.ReleaseHeat: the Smoke2 particle systems under Shell L / Shell R (cone 25 deg along their z, speed 2..5,
	 * size 0.3..0.5, life 0.5 s, grey 0.8 at alpha 0.75, a 1 s burst) -- sizes in the bones' units, tuned live.
	 */
	/** Steam trailing the cores the Core Eject flings out: a few puffs at both sinks, called every tick while they fly. */
	public static void coreSteam() {
		RigInstance gun = ArsenalClient.cur().rig(com.ultracraft.item.WeaponKind.SHOTGUN);
		String[] vents = {"GunArmature/Shell L", "GunArmature/Shell R"};
		float[][] ventAt = {{-0.0227f, -0.0411f, 0.0662f}, {-0.0549f, -0.0411f, 0.0434f}};
		int n = (int) Tune.get("view.coreSteamCount");
		for (int side = 0; side < 2 && n > 0; side++) {
			Matrix4f b = gun == null ? null : gun.boneWorld(vents[side]);
			if (b == null) continue;
			Vector4f p4 = pt(b, ventAt[side][0], ventAt[side][1], ventAt[side][2]);
			Vector3f dir = b.transformDirection(new Vector3f(0, 0, 1)).normalize();
			float mx = OffhandGun.running() ? -1 : 1; // the off-hand gun is drawn mirrored
			SteamFx.trail(HandRenderer.viewToWorld(new Vector3f(mx * p4.x, p4.y, p4.z)), HandRenderer.viewDirToWorld(dir.mul(mx, 1, 1)), n);
		}
	}

	/** Shotgun.ReleaseHeat: world-space steam from both heat sinks (the vents sit on the Shell L / Shell R bones). */
	public static void steam() {
		RigInstance gun = ArsenalClient.cur().rig(com.ultracraft.item.WeaponKind.SHOTGUN);
		String[] vents = {"GunArmature/Shell L", "GunArmature/Shell R"};
		float[][] ventAt = {{-0.0227f, -0.0411f, 0.0662f}, {-0.0549f, -0.0411f, 0.0434f}};
		for (int side = 0; side < 2; side++) {
			Matrix4f b = gun == null ? null : gun.boneWorld(vents[side]);
			if (b == null) continue;
			Vector4f p4 = pt(b, ventAt[side][0], ventAt[side][1], ventAt[side][2]);
			Vector3f dir = b.transformDirection(new Vector3f(0, 0, 1)).normalize();
			float mx = OffhandGun.running() ? -1 : 1;
			SteamFx.burst(HandRenderer.viewToWorld(new Vector3f(mx * p4.x, p4.y, p4.z)), HandRenderer.viewDirToWorld(dir.mul(mx, 1, 1)));
		}
		if (!Tune.on("view.steamOnGun")) return;
		java.util.Random rnd = new java.util.Random();
		steamLog = true;
		int n = (int) Tune.get("view.steamCount");
		String[] bones = {"GunArmature/Shell L", "GunArmature/Shell R"};
		float[][] at = {{-0.0227f, -0.0411f, 0.0662f}, {-0.0549f, -0.0411f, 0.0434f}};
		for (int side = 0; side < 2; side++) {
			for (int i = 0; i < n; i++) {
				Puff pf = new Puff();
				pf.bone = bones[side];
				pf.x = at[side][0]; pf.y = at[side][1]; pf.z = at[side][2];
				double ang = Math.toRadians(Tune.get("view.steamSpread")) * Math.sqrt(rnd.nextDouble()), rot = rnd.nextDouble() * Math.PI * 2;
				float sp = (float) (Tune.get("view.steamSpeed") * (0.4 + rnd.nextDouble() * 0.6));
				pf.vx = (float) (Math.sin(ang) * Math.cos(rot)) * sp; pf.vy = (float) (Math.sin(ang) * Math.sin(rot)) * sp; pf.vz = (float) Math.cos(ang) * sp;
				pf.age = -i * (0.6f / Math.max(1, n)); // the burst is spread over the emission ramp
				pf.life = (float) Tune.get("view.steamLife");
				pf.size = (float) (Tune.get("view.steamSize") * (0.6 + rnd.nextDouble() * 0.4));
				puffs.add(pf);
			}
		}
	}

	private static boolean steamLog, sawDebugWas;

	private static void drawSteam(RigInstance gun, VertexConsumerProvider buffers, Matrix4f pm) {
		if (puffs.isEmpty()) return;
		if (steamLog) {
			steamLog = false;
			Matrix4f b = gun.boneWorld("GunArmature/Shell L");
			if (b != null && Tune.on("view.displaysDebug")) UltraCraft.LOG.info("[displays] steam Shell L at {} scale {} z={}", b.getTranslation(new Vector3f()), b.getScale(new Vector3f()), b.transformDirection(new Vector3f(0, 0, 1)));
		}
		long now = System.nanoTime();
		float dt = puffNanos == 0 ? 0 : Math.min(0.1f, (now - puffNanos) / 1e9f);
		puffNanos = now;
		if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		for (java.util.Iterator<Puff> it = puffs.iterator(); it.hasNext(); ) {
			Puff pf = it.next();
			pf.age += dt;
			if (pf.age >= pf.life) { it.remove(); continue; }
			if (pf.age < 0) continue;
			pf.x += pf.vx * dt; pf.y += pf.vy * dt; pf.z += pf.vz * dt;
			Matrix4f b = gun.boneWorld(pf.bone);
			if (b == null) continue;
			float k = pf.age / pf.life;
			float sz = pf.size * (1 + k);
			billboardAt(buffers, pm, pt(b, pf.x, pf.y, pf.z), "smokesprite", sz, 0.92f, 0.92f, 0.9f, 0.8f * (1 - k * k), false);
		}
	}

	/** A camera-facing textured quad at a point of the view space. */
	private static void billboardAt(VertexConsumerProvider buffers, Matrix4f pm, Vector4f c, String sprite, float size, float r, float g, float b, float a, boolean emissive) {
		Identifier id = new Identifier(UltraCraft.MOD_ID, "textures/uk/" + sprite + ".png");
		VertexConsumer vc = buffers.getBuffer(emissive ? RenderLayer.getEntityTranslucentEmissive(id) : RenderLayer.getEntityTranslucent(id));
		float h = size / 2;
		Vector4f[] q = {new Vector4f(c.x - h, c.y - h, c.z, 1), new Vector4f(c.x + h, c.y - h, c.z, 1), new Vector4f(c.x + h, c.y + h, c.z, 1), new Vector4f(c.x - h, c.y + h, c.z, 1)};
		vtx(vc, pm, q[0], 0, 1, r, g, b, a); vtx(vc, pm, q[1], 1, 1, r, g, b, a); vtx(vc, pm, q[2], 1, 0, r, g, b, a); vtx(vc, pm, q[3], 0, 0, r, g, b, a);
	}

	/**
	 * Rocket Launcher Napalm's Napalm Muzzle Flash while spraying: the muzzleflashwhite sprite tinted (0.12, 0.075, 0.16)
	 * on Sprites-Default (laid over, not added), spinning 720 deg/s about the barrel (Spin), scale 3 the moment the spray
	 * starts and shrinking at 9/s once it stops (RocketLauncher.Update); colour, size and spin live (spray.flash*).
	 */
	private static void sprayFlash(RigInstance gun, WeaponKind kind, int variation, VertexConsumerProvider buffers, Matrix4f pm, float frameDt) {
		ArsenalClient a = ArsenalClient.cur();
		// its own clock: in the late shader pass the draw's dt is only the time since the first pass of this frame
		long now = System.nanoTime();
		float dt = sprayNanos == 0 ? 0 : Math.min(0.1f, (now - sprayNanos) / 1e9f);
		sprayNanos = now;
		if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		if (kind == WeaponKind.ROCKET && variation == 2 && gun != null) {
			sprayFlashScale = a.firingNapalm ? 3f : Math.max(0f, sprayFlashScale - 9f * dt);
			sprayFlashAngle = (sprayFlashAngle + 720f * (float) Tune.get("spray.flashSpinRate") * dt) % 360f;
			Matrix4f sp = sprayFlashScale > 0 ? gun.boneWorld("RocketLauncher/Armature/Body_Bone/Shootpoint") : null;
			if (sp != null) {
				float size = (float) (Tune.get("spray.flashSize") * Tune.get("view.flashSize")) * sprayFlashScale / 3f;
				float dir = (float) Tune.get("spray.flashSpin");
				spinBillboard(buffers, pm, sp, Tune.on("spray.flashYellow") ? "muzzleflash" : "muzzleflashwhite", size, (float) Math.toRadians(sprayFlashAngle * dir),
					(float) Tune.get("spray.flashR"), (float) Tune.get("spray.flashG"), (float) Tune.get("spray.flashB"), (float) Tune.get("spray.flashA"), Tune.on("spray.flashAdd"));
			}
			if (Tune.on("spray.drips") && (a.firingNapalm || !drips.isEmpty()))
				sprayDrips(gun.boneWorld("RocketLauncher/Armature/Body_Bone/Shootpoint"), buffers, pm, dt, a.firingNapalm);
		} else {
			sprayFlashScale = 0;
			drips.clear();
		}
	}

	/**
	 * Live drips: position (Shootpoint space, u), velocity (u/s), age (s); once the spray stops: 1, the place in the world
	 * (blocks), blocks per unit there and the velocity in the world (blocks/s).
	 */
	private static final java.util.List<float[]> drips = new java.util.ArrayList<>();
	private static float dripDebt;

	/**
	 * The Napalm Muzzle Flash's Particle System, the spray's drips: 25/s while spraying, each 0.5 s, from a cone of radius
	 * 0.2 u and 20 deg along the Shootpoint's forward at 5 u/s, 0.2 u growing from 0 over its life, the blooddrop sprite
	 * in (0.12, 0.09, 0.18) (BloodDropMult, multiplied over the view: drawn laid over here). It simulates in the gun's
	 * space (Local) and takes no scale from the gun (scaling mode Local), so the drips stay with the barrel in game units.
	 * Speed and size live (spray.dripSpeed / dripSize, by eye). When the spray stops, the drips left are let go into the
	 * world and finish their path and life there like the others (the user's call: they do not follow the barrel).
	 */
	private static void sprayDrips(Matrix4f sp, VertexConsumerProvider buffers, Matrix4f pm, float dt, boolean emitting) {
		if (emitting) {
			dripDebt += 25 * dt;
			for (; dripDebt >= 1; dripDebt--) {
				double phi = Math.random() * Math.PI * 2, f = Math.sqrt(Math.random()), tilt = Math.toRadians(20) * f;
				float speed = 5 * (float) Tune.get("spray.dripSpeed");
				float cx = (float) Math.cos(phi), cy = (float) Math.sin(phi), s = (float) Math.sin(tilt) * speed;
				drips.add(new float[] {0.2f * (float) f * cx, 0.2f * (float) f * cy, 0, cx * s, cy * s, (float) Math.cos(tilt) * speed, 0, 0, 0, 0, 0, 0, 0, 0, 0});
			}
		} else dripDebt = 0;
		for (java.util.Iterator<float[]> it = drips.iterator(); it.hasNext();) {
			float[] d = it.next();
			d[6] += dt;
			if (d[6] >= 0.5f) { it.remove(); continue; }
			if (d[7] == 0) { d[0] += d[3] * dt; d[1] += d[4] * dt; d[2] += d[5] * dt; }
			else { d[8] += d[12] * dt; d[9] += d[13] * dt; d[10] += d[14] * dt; }
		}
		if (drips.isEmpty()) return;
		net.minecraft.client.render.Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
		Vector3f camPos = cam.getPos().toVector3f();
		// the world-to-view turn GameRenderer.renderWorld applies: pitch about x, then yaw + 180 about y
		org.joml.Quaternionf toView = new org.joml.Quaternionf().rotationX((float) Math.toRadians(cam.getPitch())).rotateY((float) Math.toRadians(cam.getYaw() + 180));
		float blocksPerUnit = pm.getScale(new Vector3f()).x;
		VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.solidSmooth(new Identifier(UltraCraft.MOD_ID, "textures/uk/blooddrop.png")));
		float r = (float) Tune.get("spray.dripR"), g = (float) Tune.get("spray.dripG"), b = (float) Tune.get("spray.dripB");
		Vector3f o = null, ax = null, ay = null, az = null;
		if (sp != null) {
			o = sp.getTranslation(new Vector3f());
			ax = sp.transformDirection(new Vector3f(1, 0, 0)).normalize();
			ay = sp.transformDirection(new Vector3f(0, 1, 0)).normalize();
			az = sp.transformDirection(new Vector3f(0, 0, 1)).normalize();
			if (Tune.on("spray.dripForward")) {
				// (by eye) out of the barrel toward the view's forward instead of the Shootpoint's own z (straight down)
				az.set(0, 0, 1);
				ax.set(1, 0, 0);
				ay.set(0, 1, 0);
			}
		}
		Matrix4f ident = new Matrix4f();
		for (float[] d : drips) {
			if (d[7] == 0) {
				if (o == null) continue;
				float x = o.x + ax.x * d[0] + ay.x * d[1] + az.x * d[2], y = o.y + ax.y * d[0] + ay.y * d[1] + az.y * d[2], z = o.z + ax.z * d[0] + ay.z * d[1] + az.z * d[2];
				float h = 0.2f * (float) Tune.get("spray.dripSize") * d[6] / 0.5f / 2;
				if (!emitting) {
					// let go: its place and velocity in the hand's view (blocks, -z ahead) turned into the world by the camera
					org.joml.Quaternionf toWorld = new org.joml.Quaternionf(toView).conjugate();
					Vector3f world = toWorld.transform(pm.transformPosition(new Vector3f(x, y, -z))).add(camPos);
					float vx = ax.x * d[3] + ay.x * d[4] + az.x * d[5], vy = ax.y * d[3] + ay.y * d[4] + az.y * d[5], vz = ax.z * d[3] + ay.z * d[4] + az.z * d[5];
					Vector3f vel = toWorld.transform(pm.transformDirection(new Vector3f(vx, vy, -vz)));
					d[7] = 1; d[8] = world.x; d[9] = world.y; d[10] = world.z; d[11] = blocksPerUnit; d[12] = vel.x; d[13] = vel.y; d[14] = vel.z;
				} else {
					vtx(vc, pm, new Vector4f(x - h, y - h, z, 1), 0, 1, r, g, b, 1);
					vtx(vc, pm, new Vector4f(x + h, y - h, z, 1), 1, 1, r, g, b, 1);
					vtx(vc, pm, new Vector4f(x + h, y + h, z, 1), 1, 0, r, g, b, 1);
					vtx(vc, pm, new Vector4f(x - h, y + h, z, 1), 0, 0, r, g, b, 1);
					continue;
				}
			}
			// a drip that was let go: back from the world into the view, facing it, growing on to the end of its life
			Vector3f v = new Vector3f(d[8], d[9], d[10]).sub(camPos);
			toView.transform(v);
			float a = 1, h = 0.2f * (float) Tune.get("spray.dripSize") * d[6] / 0.5f / 2 * d[11];
			free(vc, ident, v.x - h, v.y - h, v.z, 0, 1, r, g, b, a);
			free(vc, ident, v.x + h, v.y - h, v.z, 1, 1, r, g, b, a);
			free(vc, ident, v.x + h, v.y + h, v.z, 1, 0, r, g, b, a);
			free(vc, ident, v.x - h, v.y + h, v.z, 0, 0, r, g, b, a);
		}
	}

	/** A vertex already in the hand pass's view space (blocks). */
	private static void free(VertexConsumer vc, Matrix4f m, float x, float y, float z, float u, float v, float r, float g, float b, float a) {
		vc.vertex(m, x, y, z).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1).next();
	}

	private static float sprayFlashScale, sprayFlashAngle;
	private static long sprayNanos;

	static {
		Tune.def("spray.flashR", 0.4, "Firestarter spray flash colour, red (by eye: the user's darker, solid purple; the game's sprite colour is 0.12, 0.075, 0.16)");
		Tune.def("spray.flashG", 0, "Firestarter spray flash colour, green (by eye; the game 0.075)");
		Tune.def("spray.flashB", 0.55, "Firestarter spray flash colour, blue (by eye; the game 0.16)");
		Tune.def("spray.flashA", 1, "Firestarter spray flash opacity (from the game: 1)");
		Tune.def("spray.flashAdd", 0, "1 = the spray flash adds light like the other muzzle flashes (by eye; the game: 0, laid over)");
		Tune.def("spray.flashYellow", 0, "1 = the spray flash uses the yellow muzzleflash sprite (the revolver's look, by eye) instead of muzzleflashwhite (the game: 0)");
		Tune.def("spray.flashSpin", 1, "spray flash turn: 1 = counterclockwise on the screen, -1 = clockwise (720 deg/s from the game)");
		Tune.def("spray.flashSpinRate", 1.5, "spray flash spin speed, x the game's 720 deg/s (by eye: the user wants it a bit faster)");
		Tune.def("spray.drips", 1, "1 = the Firestarter spray's drips from the barrel (the game's Napalm Muzzle Flash particles)");
		Tune.def("spray.dripSize", 8, "spray drip size, x the game's 0.2 u (by eye: the user's pick)");
		Tune.def("spray.dripSpeed", 12, "spray drip speed, x the game's 5 u/s (by eye: the user's pick next to ULTRAKILL)");
		Tune.def("spray.dripForward", 0, "1 = drips fly forward out of the barrel (by eye); 0 = the game's: along the Shootpoint's z, straight down from the barrel tip");
		Tune.def("spray.dripR", 0.12, "spray drip colour, red (from the game: 0.12, 0.09, 0.18)");
		Tune.def("spray.dripG", 0.09, "spray drip colour, green (from the game)");
		Tune.def("spray.dripB", 0.18, "spray drip colour, blue (from the game)");
		Tune.def("spray.flashSize", 0.7,"Firestarter spray flash size (by eye; the game: muzzleflashwhite 0.32 u at scale 3 on the 1.25 launcher = 1.2 u, i.e. 0.75 x view.flashSize)");
	}

	/** billboard() turned `angle` radians counterclockwise on the screen, alpha-blended (a Sprites-Default sprite). */
	private static void spinBillboard(VertexConsumerProvider buffers, Matrix4f pm, Matrix4f bone, String sprite, float size, float angle, float r, float g, float b, float a, boolean add) {
		Identifier id = new Identifier(UltraCraft.MOD_ID, "textures/uk/" + sprite + ".png");
		VertexConsumer vc = buffers.getBuffer(add ? com.ultracraft.client.render.UkLayers.glowSmooth(id) : com.ultracraft.client.render.UkLayers.solidSmooth(id));
		Vector4f c = new Vector4f(0, 0, 0, 1).mul(bone);
		float h = size / 2, cs = (float) Math.cos(angle) * h, sn = (float) Math.sin(angle) * h;
		// corners (-1,-1), (1,-1), (1,1), (-1,1) turned by the angle
		Vector4f[] q = {new Vector4f(c.x - cs + sn, c.y - sn - cs, c.z, 1), new Vector4f(c.x + cs + sn, c.y + sn - cs, c.z, 1),
			new Vector4f(c.x + cs - sn, c.y + sn + cs, c.z, 1), new Vector4f(c.x - cs - sn, c.y - sn + cs, c.z, 1)};
		vtx(vc, pm, q[0], 0, 1, r, g, b, a); vtx(vc, pm, q[1], 1, 1, r, g, b, a); vtx(vc, pm, q[2], 1, 0, r, g, b, a); vtx(vc, pm, q[3], 0, 0, r, g, b, a);
	}

	/** A camera-facing textured quad centred on a bone's origin (view space: x right, y up, the rig's z is mirrored). */
	private static void billboard(VertexConsumerProvider buffers, Matrix4f pm, Matrix4f bone, String sprite, float size, float r, float g, float b, float a) {
		Identifier id = new Identifier(UltraCraft.MOD_ID, "textures/uk/" + sprite + ".png");
		// view.flashLayer 1: solid (cutout, full bright) so shader packs do not wash the yellow out; 0: see-through
		VertexConsumer vc = buffers.getBuffer(Tune.on("view.flashLayer") && sprite.startsWith("muzzleflash") ? RenderLayer.getEntityCutoutNoCull(id) : RenderLayer.getEntityTranslucentEmissive(id));
		Vector4f c = new Vector4f(0, 0, 0, 1).mul(bone);
		float h = size / 2;
		Vector4f[] q = {new Vector4f(c.x - h, c.y - h, c.z, 1), new Vector4f(c.x + h, c.y - h, c.z, 1), new Vector4f(c.x + h, c.y + h, c.z, 1), new Vector4f(c.x - h, c.y + h, c.z, 1)};
		vtx(vc, pm, q[0], 0, 1, r, g, b, a); vtx(vc, pm, q[1], 1, 1, r, g, b, a); vtx(vc, pm, q[2], 1, 0, r, g, b, a); vtx(vc, pm, q[3], 0, 0, r, g, b, a);
	}

	/**
	 * Text on a canvas, centred at (cx, cy) canvas units, `sizeUnits` tall: the font's vertices are placed through the same
	 * canvas → bone → view mapping as the rects (the old matrix flipped the text and missed the canvas frame).
	 */
	private static void text(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f bone, String s, float cx, float cy, float sizeUnits, float r, float g, float b) {
		text(buffers, pm, bone, s, cx, cy, sizeUnits, r, g, b, false);
	}

	/** @param flip the canvas reads upside down in this rig's frame (its y runs the other way) */
	private static void text(VertexConsumerProvider.Immediate buffers, Matrix4f pm, Matrix4f bone, String s, float cx, float cy, float sizeUnits, float r, float g, float b, boolean flip) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		Text t = Text.literal(s).setStyle(StyleState.HUD_STYLE);
		float k = sizeUnits / 9f; // the font is 9 px tall: canvas units per px
		float w = tr.getWidth(t) * k;
		Vector3f n = bone.transformDirection(new Vector3f(0, 0, 1)).normalize();
		Vector3f c = bone.getTranslation(new Vector3f());
		if (n.dot(c) > 0) n.negate();
		Vector3f off = n.mul(outBase + 0.004f * 4 * (float) Tune.get("view.displayOut"));
		float x0 = cx - w / 2, ytop = cy + sizeUnits / 2;
		VertexConsumerProvider canvas = layer -> new CanvasVertices(buffers.getBuffer(layer), px -> {
			// text px (x right, y down from the top) → canvas units (y up) → the bone frame (canvas y is -y there) → view
			float cyy = ytop - px.y * k;
			Vector4f q = pt(bone, readX(cx, x0 + px.x * k), flip ? cyy : -cyy, 0).add(off.x, off.y, off.z, 0);
			return new Vector3f(q.x, q.y, -q.z).mulPosition(pm);
		});
		int argb = 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
		tr.draw(t, 0, 0, argb, false, new Matrix4f(), canvas, TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
	}

	/** A VertexConsumer that moves every position through `place` (text px → the view) and passes the rest on. */
	/** The draw's buffers with every vertex moved by the gun rig's underwater wobble (RigInstance.emit's wave). */
	private static final class WobbleBuffers extends VertexConsumerProvider.Immediate {
		private final VertexConsumerProvider.Immediate base;
		private final RigInstance gun;

		WobbleBuffers(VertexConsumerProvider.Immediate base, RigInstance gun) {
			super(new net.minecraft.client.render.BufferBuilder(256), java.util.Map.of());
			this.base = base;
			this.gun = gun;
		}

		@Override public VertexConsumer getBuffer(net.minecraft.client.render.RenderLayer layer) {
			return new CanvasVertices(base.getBuffer(layer), p -> {
				float x = p.x + gun.wobbleAmp * (float) Math.sin(gun.wobbleTime * gun.wobbleFreq + p.y * gun.wobbleWave);
				float y = p.y + gun.wobbleAmp * (float) Math.sin(gun.wobbleTime * gun.wobbleFreq * 1.3f + x * gun.wobbleWave);
				return new Vector3f(x, y, p.z);
			});
		}

		@Override public void drawCurrentLayer() { base.drawCurrentLayer(); }
		@Override public void draw() { base.draw(); }
		@Override public void draw(net.minecraft.client.render.RenderLayer layer) { base.draw(layer); }
	}

	private static final class CanvasVertices implements VertexConsumer {
		private final VertexConsumer d;
		private final java.util.function.Function<Vector3f, Vector3f> place;

		CanvasVertices(VertexConsumer d, java.util.function.Function<Vector3f, Vector3f> place) {
			this.d = d;
			this.place = place;
		}

		@Override public VertexConsumer vertex(double x, double y, double z) { Vector3f p = place.apply(new Vector3f((float) x, (float) y, (float) z)); d.vertex(p.x, p.y, p.z); return this; }
		@Override public VertexConsumer color(int r, int g, int b, int a) { d.color(r, g, b, a); return this; }
		@Override public VertexConsumer texture(float u, float v) { d.texture(u, v); return this; }
		@Override public VertexConsumer overlay(int u, int v) { d.overlay(u, v); return this; }
		@Override public VertexConsumer light(int u, int v) { d.light(u, v); return this; }
		@Override public VertexConsumer normal(float x, float y, float z) { d.normal(x, y, z); return this; }
		@Override public void next() { d.next(); }
		@Override public void fixedColor(int r, int g, int b, int a) { d.fixedColor(r, g, b, a); }
		@Override public void unfixColor() { d.unfixColor(); }
	}

	private static Vector4f pt(Matrix4f bone, float x, float y, float z) {
		return new Vector4f(x, y, z, 1).mul(bone);
	}

	private static void vtx(VertexConsumer vc, Matrix4f pm, Vector4f p, float u, float v, float r, float g, float b, float a) {
		vc.vertex(pm, p.x, p.y, -p.z).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1).next();
	}
}
