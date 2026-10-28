package com.ultracraft.client.weapon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.item.RevolverItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Hand;
import net.minecraftforge.client.event.RenderHandEvent;
import org.joml.Matrix4f;

/**
 * Draws V1's arms in first person from Forge's RenderHandEvent with their own projection. In the game the arms
 * and guns sit on layer 13, which the Main Camera does not render: the "HUD Camera" (a child of the main camera
 * at the same spot, vertical FOV 90, near 0.1, depth-only clear) draws them after the world, so they never clip
 * into geometry and keep their size whatever the player's FOV is. The fist arm sits under `Punch` (0, -0.225, 0)
 * and the weapon under `Guns` (0, 0, 0) relative to the camera, like the Player prefab.
 */
public final class HandRenderer {
	private static final double FOV = Tune.def("view.fov", 90, "vertical FOV of the gun camera (the Player prefab's HUD Camera: 90)");
	private static final double PUNCH_Y = Tune.def("view.punchY", -0.225, "Punch holder y offset under the camera (Player prefab: -0.225)");
	private static final double SHOW = Tune.def("view.show", 1, "0 = hide the ULTRAKILL arms (back to vanilla hands)");
	private static final double SWAY = Tune.def("view.sway", 1, "multiplier on the walking bob applied to the rigs");
	private static final double PARRY_BRIGHT = Tune.def("parry.armBright", 4, "parry: arm and gun brightness boost (their own colours added again this many times)");
	private static final double PARRY_WHITE = Tune.def("parry.armWhite", 0.9, "parry: flat white added on the arm and gun, 0..1 (1 = solid white)");
	private static final double PARRY_PREVIEW = Tune.def("parry.preview", 0, "1 = show the parry look (bright arms + overlay) all the time, to tune it live");
	private static final double WHIP_TEST = Tune.def("view.whipTest", 0, "1 = always draw the Whiplash arm (debug)");
	private static final double GUN_BACK = Tune.def("view.gunBack", 0.1, "gun camera distance behind the eye, u (0 = the prefab; recordings show the guns a little further out)");

	private HandRenderer() {}

	/**
	 * Advance the held rigs once per frame, whatever the camera does (the weapon logic reads the clips' state, so
	 * they must run in third person too, where RenderHandEvent never fires).
	 */
	static { Tune.def("view.fistIdle", 0, "1 = keep the fist arm on screen in its Idle pose (debug: shows where the game parks it)"); }

	/** Last frame's ShellEjector bone of the red arm in view units (Unity camera space), or null. */
	public static org.joml.Vector3f ejectorPos, ejectorFwd, ejectorUp, ejectorRight;
	private static boolean froze;

	static {
		Tune.def("view.gunTurnRate", 0, "guns lag a camera turn and catch up at this rate, °/s (0 = locked to the camera, like the game plays)");
		// per-weapon placement, tuned against ULTRAKILL captures: x right, y up, z away from the camera (− = closer), u
		for (String w : new String[] {"revolver", "shotgun", "nailgun", "railcannon", "rocket"}) {
			double[] d = w.equals("revolver") ? new double[] {-0.01, 0, -0.08} : w.equals("shotgun") ? new double[] {0, 0, -0.1} : w.equals("nailgun") ? new double[] {0, 0, -0.1} : w.equals("railcannon") ? new double[] {0, 0, -0.1} : w.equals("rocket") ? new double[] {0, 0, -0.1} : new double[] {0, 0, 0};
			Tune.def("view." + w + "X", d[0], w + " offset right, u");
			Tune.def("view." + w + "Y", d[1], w + " offset up, u");
			Tune.def("view." + w + "Z", d[2], w + " offset away from the camera, u (− = closer)");
		}
	}

	/**
	 * Weapon sway and bob: the arms lag the camera turn a little (clamped, springing back) and bob with the
	 * ground speed. Unity camera space (x right, y up, z forward).
	 */
	/**
	 * ULTRAKILL's gun movement, from the Player prefab's Main Camera/Guns node:
	 * <ul>
	 * <li>RotateToFaceFrustumTarget (maxDegreesDelta 180): every frame the Guns' world rotation turns toward the
	 * camera's at 180°/s (Quaternion.RotateTowards), so a camera turn leaves the guns behind and they catch up;</li>
	 * <li>WalkingBob: while walking, the Guns' local position moves between (±0.08, −0.025, 0) and back to the
	 * start, at dt × (2 − 3 × distance) × min(speed, 15) / 15 × 0.25 u/s; when not walking it returns at 1 u/s.</li>
	 * </ul>
	 * Unity camera space (x right, y up, z forward); the Guns pivot is the camera itself.
	 */
	private static Matrix4f sway(ClientPlayerEntity p, float tickDelta) {
		float yaw = p.prevYaw + (p.getYaw() - p.prevYaw) * tickDelta, pitch = p.prevPitch + (p.getPitch() - p.prevPitch) * tickDelta;
		long now = System.nanoTime();
		float dt = swayNanos == 0 ? 0 : Math.min(0.1f, (now - swayNanos) / 1e9f);
		swayNanos = now;
		if (ViewModel.paused()) dt = 0;
		// camera orientation in a frame with +z forward, +y up, +x left (Minecraft yaw/pitch)
		org.joml.Quaternionf cam = new org.joml.Quaternionf().rotationYXZ((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch), 0);
		// (time stopped, a pause or the parry freeze: the guns stay with the camera; holding their own turn there left them
		// pointing off to the side if the view moved, and swinging back after)
		if (gunRot == null || dt == 0) gunRot = new org.joml.Quaternionf(cam);
		float maxStep = (float) Math.toRadians(Tune.get("view.gunTurnRate") * dt);
		// Quaternion.Angle: 2·acos(|dot|)
		float dot = Math.min(1f, Math.abs(gunRot.dot(cam)));
		float angle = (float) (2 * Math.acos(dot));
		if (Tune.get("view.gunTurnRate") <= 0 || angle <= maxStep || angle > Math.PI * 0.75) gunRot.set(cam);
		else {
			if (gunRot.dot(cam) < 0) gunRot.set(-gunRot.x, -gunRot.y, -gunRot.z, -gunRot.w);
			gunRot.slerp(cam, maxStep / angle);
		}
		// the guns' rotation relative to the camera, then into Unity camera space (flip x: conjugate by diag(-1, 1, 1))
		org.joml.Quaternionf rel = new org.joml.Quaternionf(cam).conjugate().mul(gunRot);
		org.joml.Quaternionf relU = new org.joml.Quaternionf(rel.x, -rel.y, -rel.z, rel.w);
		// WalkingBob
		com.ultracraft.client.v1.V1 v = com.ultracraft.client.v1.V1.INSTANCE;
		if (dt > 0) {
			if (v.walking && v.onGround && !v.sliding) {
				double speed3 = Math.sqrt(v.vel.x * v.vel.x + v.vel.y * v.vel.y + v.vel.z * v.vel.z);
				float sp = (float) (dt * (2 - bobPos.distance(0, 0, 0) * 3) * (Math.min(speed3, 15) / 15));
				org.joml.Vector3f target = bobBack ? new org.joml.Vector3f() : bobTarget;
				moveTowards(bobPos, target, sp * 0.25f);
				if (bobPos.lengthSquared() < 1e-10f) bobBack = false;
				else if (bobPos.distanceSquared(BOB_RIGHT) < 1e-10f) { bobBack = true; bobTarget = BOB_LEFT; }
				else if (bobPos.distanceSquared(BOB_LEFT) < 1e-10f) { bobBack = true; bobTarget = BOB_RIGHT; }
			} else moveTowards(bobPos, new org.joml.Vector3f(), dt);
		}
		return new Matrix4f().translation(bobPos.x, bobPos.y, bobPos.z).rotate(relU);
	}

	private static final org.joml.Vector3f BOB_RIGHT = new org.joml.Vector3f(0.08f, -0.025f, 0), BOB_LEFT = new org.joml.Vector3f(-0.08f, -0.025f, 0);
	private static org.joml.Vector3f bobPos = new org.joml.Vector3f(), bobTarget = BOB_RIGHT;
	private static boolean bobBack;
	private static org.joml.Quaternionf gunRot;
	private static long swayNanos;

	private static void moveTowards(org.joml.Vector3f cur, org.joml.Vector3f target, float maxDelta) {
		org.joml.Vector3f d = new org.joml.Vector3f(target).sub(cur);
		float len = d.length();
		if (len <= maxDelta || len < 1e-9f) cur.set(target);
		else cur.add(d.mul(maxDelta / len));
	}

	/** A point of the view space (Unity camera units) in the world: scaled like the drawn arms, shifted from the gun FOV to the world FOV. */
	public static net.minecraft.util.math.Vec3d viewToWorld(org.joml.Vector3f unityPos) {
		MinecraftClient c = MinecraftClient.getInstance();
		net.minecraft.client.render.Camera cam = c.gameRenderer.getCamera();
		org.joml.Vector3f f = cam.getHorizontalPlane(), u = cam.getVerticalPlane();
		org.joml.Vector3f r = new org.joml.Vector3f(f).cross(u).normalize();
		float worldFov = (float) com.ultracraft.client.v1.V1.INSTANCE.worldFov;
		float k = (float) (Math.tan(Math.toRadians(com.ultracraft.client.v1.V1.INSTANCE.hudFov((float) Tune.get("view.fov")) / 2)) / Math.tan(Math.toRadians(worldFov / 2)));
		double s = com.ultracraft.client.v1.V1.SCALE * Tune.get("view.scale");
		double x = unityPos.x * k * s, y = unityPos.y * k * s, z = unityPos.z * s;
		return cam.getPos().add(r.x * x + u.x * y + f.x * z, r.y * x + u.y * y + f.y * z, r.z * x + u.z * y + f.z * z);
	}

	/** A direction of the view space in the world. */
	public static net.minecraft.util.math.Vec3d viewDirToWorld(org.joml.Vector3f d) {
		net.minecraft.client.render.Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
		org.joml.Vector3f f = cam.getHorizontalPlane(), u = cam.getVerticalPlane();
		org.joml.Vector3f r = new org.joml.Vector3f(f).cross(u).normalize();
		return new net.minecraft.util.math.Vec3d(r.x * d.x + u.x * d.y + f.x * d.z, r.y * d.x + u.y * d.y + f.y * d.z, r.z * d.x + u.z * d.y + f.z * d.z).normalize();
	}

	/**
	 * Client tick: end the parry freeze. While skipGameRender is set Minecraft skips the render tick events too, so
	 * advanceRigs never ran again to clear it and the game stayed frozen until a screen opened (Escape twice).
	 */
	public static void endFreeze() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (froze && (!com.ultracraft.client.v1.V1.INSTANCE.frozen() || client.world == null)) {
			client.skipGameRender = false;
			froze = false;
			// TrueStop is time scale 0: the arms carry on from the held frame instead of jumping ahead by the frozen time
			ViewModel.resetClock();
		}
	}

	/**
	 * Render tick end: once the parry frame (white arms, ParryFlash overlay) has been drawn, hold it. GameRenderer.render
	 * checks skipGameRender itself, so setting it at the frame's start skipped the very frame meant to be held.
	 */
	public static void holdFrame() {
		MinecraftClient client = MinecraftClient.getInstance();
		// the freeze no longer holds a stale frame (skipGameRender): the game keeps drawing with the arms stopped and the
		// parry look (parry.preview's bright arms + overlay) on, so the live settings show during the freeze too
		if (client.skipGameRender && froze) { client.skipGameRender = false; froze = false; }
	}

	/**
	 * A count of frames (RenderTickEvent START). A shader pack draws the hand twice a frame (its solid and its
	 * see-through pass): what moves with time (the sway, the charge shake) is worked out once a frame and reused, or
	 * the see-through parts (the rocket launcher's clock) were drawn a moment off the gun and bounced against it.
	 */
	public static long frame;

	private static Object irisHand;
	private static java.lang.reflect.Method irisActive, irisSolid;
	private static boolean irisLooked;

	/** Oculus' own hand pass: 0 none (no shader pack), 1 its solid pass, 2 its see-through pass (after the water). */
	private static int irisPass() {
		if (!irisLooked) {
			irisLooked = true;
			try {
				Class<?> c = Class.forName("net.irisshaders.iris.pathways.HandRenderer");
				irisHand = c.getField("INSTANCE").get(null);
				irisActive = c.getMethod("isActive");
				irisSolid = c.getMethod("isRenderingSolid");
			} catch (Throwable t) {
				irisHand = null;
			}
		}
		if (irisHand == null) return 0;
		try {
			if (!(Boolean) irisActive.invoke(irisHand)) return 0;
			return (Boolean) irisSolid.invoke(irisHand) ? 1 : 2;
		} catch (Throwable t) {
			return 0;
		}
	}

	/**
	 * Under a shader pack, with the Rocket Launcher out, its see-through clock is drawn in Oculus' see-through hand pass
	 * (IrisHandRendererMixin asks for that pass), which comes after the water: drawn with the solid hand, before the
	 * water, it either kept the water off the screen behind it (a window down to the bottom) or was painted over.
	 */
	public static boolean clockPass() {
		MinecraftClient c = MinecraftClient.getInstance();
		return c.player != null && Tune.on("view.holoLatePass") && com.ultracraft.client.render.UkLayers.shadersOn()
			&& WeaponClient.holdingUkWeapon(c.player) && WeaponClient.heldKind(c.player) == com.ultracraft.item.WeaponKind.ROCKET;
	}

	private static final double HOLO_LATE = Tune.def("view.holoLatePass", 1, "1 = under a shader pack the rocket launcher's clock is drawn in the see-through hand pass, after the water");
	private static long swayFrame = -1;
	private static Matrix4f swayThisFrame;

	public static void advanceRigs() {
		frame++;
		MinecraftClient client = MinecraftClient.getInstance();
		com.ultracraft.client.v1.V1.INSTANCE.zoomFrame();
		ClientPlayerEntity p = client.player;
		// TimeController.TrueStop (parry): the held frame is set up in holdFrame, after the parry frame is drawn
		if (froze && !com.ultracraft.client.v1.V1.INSTANCE.frozen()) endFreeze();
		if (p == null || !UkAssets.available()) return;
		WeaponClient w = WeaponClient.INSTANCE;
		// a new weapon / variation (the server's item sync lands between ticks) starts its pull-out this frame
		if (client.world != null) w.syncSwitchIn(p);
		// the off-hand gun's too (coming back from third person it showed its last pose for a frame or two first)
		if (client.world != null && OffhandGun.active(p)) OffhandGun.run(p, () -> WeaponClient.OFF.syncSwitchIn(p));
		float dt = ViewModel.frameDelta();
		if (w.hitstop > 0) dt = 0; // Punch hitstop freezes the arms for 0.1 s
		if (com.ultracraft.client.v1.V1.INSTANCE.frozen()) dt = 0; // TrueStop: the arms hold their pose for the parry freeze
		WhiplashClient wl = WhiplashClient.INSTANCE;
		RigInstance fist = wl.active() || Tune.on("view.whipTest") ? wl.rig() : w.fistRig();
		boolean uk = WeaponClient.holdingUkWeapon(p);
		com.ultracraft.item.WeaponKind kind = WeaponClient.heldKind(p);
		ArsenalClient.INSTANCE.alt = com.ultracraft.item.UKWeaponItem.alternate(p.getMainHandStack());
		RigInstance gun = !uk ? null : kind == com.ultracraft.item.WeaponKind.REVOLVER ? w.revolverRig(p.getMainHandStack()) : ArsenalClient.INSTANCE.rig(kind);
		if (fist != null) fist.advance(dt);
		if (gun != null) gun.advance(dt);
		RigInstance offGun = OffhandGun.rig(p);
		if (offGun != null && offGun != gun) offGun.advance(dt);
	}

	/** The fist arm's rig drawn this frame (null when it is down): the muzzle flash goes behind it too. */
	public static String lastFistRig;

	public static void onRenderHand(RenderHandEvent e) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity p = client.player;
		if (p == null || !Tune.on("view.show") || !UkAssets.available()) return;
		boolean uk = WeaponClient.holdingUkWeapon(p);
		if (e.getHand() == Hand.OFF_HAND) {
			// V1's left side is the fist arm, drawn with the main hand below; an off-hand item is held in it (OffhandHold)
			// or is a second gun (OffhandGun)
			e.setCanceled(true);
			return;
		}
		// at a Terminal: no gun, the arm pointing at the screen (whatever is held)
		boolean shop = com.ultracraft.client.shop.ShopMode.active();
		if (!uk && !shop && !client.options.getPerspective().isFirstPerson()) return;
		// (also while Minecraft's own hand still lowers an ULTRAKILL weapon that just left the main hand, e.g. swapped to the
		// off-hand: its item model flashed up there before the empty hand came up)
		if (uk || shop || com.ultracraft.item.UKWeaponItem.kindOf(e.getItemStack()) != null) e.setCanceled(true);

		WeaponClient w = WeaponClient.INSTANCE;
		WhiplashClient wl = WhiplashClient.INSTANCE;
		RigInstance fist = wl.active() || Tune.on("view.whipTest") ? wl.rig() : w.fistRig();
		// like the game, the fist arm is only on screen while it is doing something (punch, parry, coin flip, hook); the
		// cross-fade back into Idle (Jab exits at 0.65 with a 0.35 s fade) carries it down out of frame first
		if (!wl.active() && !Tune.on("view.whipTest") && !Tune.on("view.fistIdle") && fist != null && (fist.currentClip() == null || fist.currentClip().equalsIgnoreCase("Idle")) && !fist.fading()) fist = null;
		// Arm Blue carries a spare RightArm mesh for the no-weapon pose: never alongside a gun (it showed as a third hand on punches)
		if (fist != null) for (int i = 0; i < fist.rig.meshes.size(); i++) if (fist.rig.meshes.get(i).name.equals("RightArm")) fist.hiddenMeshes.add(i);
		if (wl.active() && fist != null) {
			// the hook mesh is drawn in the world while the hook is out
			fist.hiddenMeshes.clear();
			boolean out = wl.state != WhiplashClient.State.READY || wl.returning;
			for (int i = 0; i < fist.rig.meshes.size(); i++) if (out && fist.rig.meshes.get(i).name.equals("Hook")) fist.hiddenMeshes.add(i);
		}
		com.ultracraft.item.WeaponKind kind = WeaponClient.heldKind(p);
		RigInstance gun = !uk || shop ? null : kind == com.ultracraft.item.WeaponKind.REVOLVER ? w.revolverRig(p.getMainHandStack()) : ArsenalClient.INSTANCE.rig(kind);

		MatrixStack matrices = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
		buffers.draw();
		Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
		float aspect = (float) client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight();
		Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(com.ultracraft.client.v1.V1.INSTANCE.hudFov((float) Tune.get("view.fov"))), aspect, 0.01f, 10f);
		RenderSystem.setProjectionMatrix(proj, com.mojang.blaze3d.systems.VertexSorter.BY_DISTANCE);
		// the arms live in their own depth range: squeezed into the nearest 2 % of the depth buffer they always win
		// against world geometry (the game's HUD Camera draws them after the world), without clearing the world's
		// depth, which shader packs still need for their later passes
		org.lwjgl.opengl.GL11.glDepthRange(0.0, 0.02);

		matrices.push();
		int light = e.getPackedLight();
		// The Player prefab puts the HUD Camera at the eye, yet recordings of the game show the guns from ~0.15 u further
		// back (the nailgun's rear display and the shotgun's flank are on screen there, off-screen from the eye point):
		// the gun camera sits view.gunBack u behind the eye until the real cause is found (docs/ULTRAKILL_SOURCE.md).
		float back = (float) Tune.get("view.gunBack"); // Unity +z is forward: the guns move away from the eye
		if (swayFrame != frame || swayThisFrame == null) {
			swayFrame = frame;
			swayThisFrame = sway(p, e.getPartialTick());
		}
		Matrix4f sway = new Matrix4f(swayThisFrame);
		// the parry's held frame (or parry.preview to tune it live): arm and gun brightened until nearly blown out
		// (only a parry's freeze: the hammer's HitNade freezes the game the same way, without the flash or the white)
		boolean parryLook = (com.ultracraft.client.v1.V1.INSTANCE.frozen() && com.ultracraft.client.v1.V1.INSTANCE.parryFlash > 0) || Tune.on("parry.preview");
		float parryBright = parryLook ? (float) Tune.get("parry.armBright") : 0f, parryWhite = parryLook ? (float) Tune.get("parry.armWhite") : 0f;
		if (fist != null) { fist.lightAdd = parryBright; fist.whiteAdd = parryWhite; fist.lightR = 1; fist.lightG = 1; fist.lightB = 1; }
		// the clock in its own late pass: the solid pass draws everything else, the see-through pass the clock alone (the
		// gun's pose from this frame's solid pass)
		boolean late = clockPass();
		int pass = late ? irisPass() : 0;
		GunDisplays.holoMode = !late || pass == 0 ? 0 : pass == 1 ? 1 : 2;
		boolean clockOnly = GunDisplays.holoMode == 2;
		com.ultracraft.client.v1.UnderwaterFx.apply(fist);
		com.ultracraft.client.v1.UnderwaterFx.apply(gun);
		if (fist != null && !clockOnly) ViewModel.draw(fist, new Matrix4f().translation(0, (float) Tune.get("view.punchY"), back), matrices, buffers, light);
		if (fist != null && !clockOnly && !wl.active()) OffhandHold.drawFirstPerson(p, fist, matrices, buffers, light, (float) (com.ultracraft.client.v1.V1.SCALE * Tune.get("view.scale")));
		lastFistRig = fist == null ? null : fist.rigName();
		// the red arm's ShellEjector (Punch.Eject spawns the shells there), kept for ShellFx in the view's units
		ejectorPos = null;
		if (fist != null && fist.rigName().equals("knuckleblaster")) {
			Matrix4f ej = fist.boneWorld("Armature/clavicle/upperarm/lowerarm/ShellEjector");
			if (ej != null) {
				ejectorPos = ej.getTranslation(new org.joml.Vector3f());
				ejectorFwd = ej.transformDirection(new org.joml.Vector3f(0, 0, 1)).normalize();
				ejectorUp = ej.transformDirection(new org.joml.Vector3f(0, 1, 0)).normalize();
				ejectorRight = ej.transformDirection(new org.joml.Vector3f(1, 0, 0)).normalize();
			}
		}
		int variation = com.ultracraft.item.UKWeaponItem.variation(p.getMainHandStack());
		boolean alt = com.ultracraft.item.UKWeaponItem.alternate(p.getMainHandStack());
		if (gun != null) GunDisplays.apply(gun, kind, variation, alt);
		if (gun != null) gun.whiteAdd = parryWhite;
		if (gun != null && parryLook) { gun.lightAdd = parryBright; gun.lightR = 1; gun.lightG = 1; gun.lightB = 1; }
		org.joml.Vector3f shake = GunDisplays.shake(kind, variation);
		String wk = kind == null ? null : switch (kind) {
			case REVOLVER -> "revolver"; case SHOTGUN -> "shotgun"; case NAILGUN -> "nailgun"; case RAILCANNON -> "railcannon"; case ROCKET -> "rocket";
		};
		org.joml.Vector3f extra = wk == null ? new org.joml.Vector3f()
			: new org.joml.Vector3f((float) Tune.get("view." + wk + "X"), (float) Tune.get("view." + wk + "Y"), (float) Tune.get("view." + wk + "Z"));
		if (gun != null && !clockOnly) ViewModel.draw(gun, new Matrix4f().translation(0, 0, back).mul(sway).translate(shake.x + extra.x, shake.y + extra.y, shake.z + extra.z), matrices, buffers, light);
		if (gun != null) GunDisplays.draw(gun, kind, variation, alt, matrices, buffers, (float) (com.ultracraft.client.v1.V1.SCALE * Tune.get("view.scale")));
		// the off-hand gun: ULTRAKILL's dual wield copy, the same holder chain mirrored to the left (x scale -1)
		RigInstance offGun = shop ? null : OffhandGun.rig(p);
		if (offGun != null && !clockOnly) {
			com.ultracraft.item.WeaponKind ok = OffhandGun.kind(p);
			int ov = OffhandGun.variation(p);
			boolean oa = OffhandGun.alternate(p);
			String owk = switch (ok) {
				case REVOLVER -> "revolver"; case SHOTGUN -> "shotgun"; case NAILGUN -> "nailgun"; case RAILCANNON -> "railcannon"; case ROCKET -> "rocket";
			};
			org.joml.Vector3f oex = new org.joml.Vector3f((float) Tune.get("view." + owk + "X"), (float) Tune.get("view." + owk + "Y"), (float) Tune.get("view." + owk + "Z"));
			matrices.push();
			// the mirror on the positions only (MatrixStack.scale's normal fix-up assumes a positive scale): the normals stay
			// the main gun's, so the copy is shaded as its mirror image (Minecraft's two hand lights are not left-right
			// symmetric); the rig reverses its winding under the mirror (RigInstance.mirrored)
			matrices.peek().getPositionMatrix().scale(-1, 1, 1);
			// its displays read the off-hand copy's state (charges, ammo, heat): drawn as the off-hand weapon
			OffhandGun.run(p, () -> {
				GunDisplays.apply(offGun, ok, ov, oa);
				offGun.whiteAdd = parryWhite;
				if (parryLook) { offGun.lightAdd = parryBright; offGun.lightR = 1; offGun.lightG = 1; offGun.lightB = 1; }
				// the walking bob and the turn lag move both guns the same way (the copy hangs under GunControl, which bobs
				// and turns unmirrored): the sway conjugated by the mirror, so mirror x sway' = sway x mirror
				Matrix4f offSway = new Matrix4f().scaling(-1, 1, 1).mul(sway).scale(-1, 1, 1);
				offGun.mirrored = true;
				org.joml.Vector3f oshake = GunDisplays.shake(ok, ov);
				ViewModel.draw(offGun, new Matrix4f().translation(0, 0, back).mul(offSway).translate(oshake.x + oex.x, oshake.y + oex.y, oshake.z + oex.z), matrices, buffers, light);
				GunDisplays.draw(offGun, ok, ov, oa, matrices, buffers, (float) (com.ultracraft.client.v1.V1.SCALE * Tune.get("view.scale")));
				offGun.mirrored = false;
			});
			matrices.pop();
		}
		matrices.pop();
		buffers.draw();
		org.lwjgl.opengl.GL11.glDepthRange(0.0, 1.0);
		RenderSystem.setProjectionMatrix(savedProj, com.mojang.blaze3d.systems.VertexSorter.BY_DISTANCE);
	}
}
