package com.ultracraft.client.v1;

import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * V1 in third person: the game's own V1 model (the 2.5D platformer's `v1_combined`, 99 bones, texture
 * v1colour_tex) replaces the vanilla player model, animated with the V1Platformer controller's clips
 * (Idle / Running / Jump / Falling / Landing / Dash / Sliding / Spin / SlamStart / SlamEnd) driven by the
 * local V1 movement state (other players: their velocity and ground state). The held weapon's rig hangs
 * from `hand.R` the way V2's revolver does in the V2 prefab (local (−0.01, 0.056, 0.022), the same
 * rotation), its first-person arm meshes hidden.
 */
public final class V1Renderer {
	/** rig units → blocks: the model is 1.6 × 3 × 0.8 = 3.84 u tall and V1 is Steve-sized (1.8 blocks). */
	private static final double BASE_SCALE = 1.8 / 3.84;
	private static final double WING_GLOW = Tune.def("tp.wingGlow", 1.2, "V1 wing light intensity (the game's _EmissiveIntensity 1.2)");
	private static final double TP_WEAPONS = Tune.def("tp.weapons", 0, "1 = draw the held weapon on the third-person model (0 = clash mode, bare hands)");
	private static final double SCALE = Tune.def("tp.scale", 0.9, "multiplier on the third-person V1 size (0.9 ≈ 1.8 blocks to the head)");
	private static final double YAW = Tune.def("tp.yaw", 180, "degrees added to the body yaw when placing the model");
	private static final double GUN_SCALE = Tune.def("tp.gunScale", 1.0, "third-person weapon size relative to its first-person size");
	private static final double GUN_X = Tune.def("tp.gunX", 0, "extra weapon offset from the head frame (model units, +x right)");
	private static final double GUN_Y = Tune.def("tp.gunY", -0.3, "extra weapon offset, +y up (the eye sits below the head bone)");
	private static final double GUN_Z = Tune.def("tp.gunZ", 0, "extra weapon offset, +z forward");
	private static final double AIM_PITCH = Tune.def("tp.aimPitch", 1, "how much of the look pitch the third-person weapon follows (0..1)");
	private static final double SHOW = Tune.def("tp.show", 1, "0 = vanilla player model in third person");
	private static final double RUN_MIN = Tune.def("tp.runSpeed", 1.0, "u/s above which the Running clip plays");

	private static final Map<PlayerEntity, State> STATES = new WeakHashMap<>();
	/**
	 * The inventory's preview (InventoryScreen.drawEntity, flagged by InventoryEntityMixin): its own rig standing in Idle,
	 * turned to the cursor like the vanilla model, not frozen in whatever move the player is in.
	 */
	public static boolean inGui;
	private static final Map<PlayerEntity, State> GUI_STATES = new WeakHashMap<>();
	/** world position of the model's left hand this frame (the Whiplash rope starts there in third person). */
	public static Vec3d lastHandL;
	public static long lastHandFrame;

	private static final class State {
		RigInstance rig;
		String anim = "";
		boolean wasGround = true, wasBoost, wasSlam, wasSliding;
		int wallJumps;
		double airTime;
		long lastNanos;
		/** nanoTime the current Spin ends (PlatformerMovement: spinJuice 1 → 0 at 3/s = 0.33 s; the clip itself loops). */
		long spinUntil;
	}

	private V1Renderer() {}

	private static final double TP_SLIDE_LIFT = Tune.def("tp.slideLift", 0.15, "blocks the third-person model is raised while it plays Sliding (its legs sank into the ground)");
	private static final double TP_HATS = Tune.def("tp.seasonalHats", 1, "1 = V1 wears ULTRAKILL's seasonal hats on their dates (the game's Seasonal Events option)");
	/** The prefab's Seasonal Hats meshes (an older jar's rig has only the Pumpkin). */
	private static final java.util.Set<String> HATS = java.util.Set.of("Pumpkin", "Christmas", "Easter");

	/**
	 * SeasonalHats.Start: the hat for today's date - Christmas 22-28 December, the Halloween pumpkin 25-31 October,
	 * Easter's on Easter Sunday and the two days before - or null.
	 */
	private static String seasonalHat() {
		if (hatOverride != null) return hatOverride.isEmpty() ? null : hatOverride;
		if (!Tune.on("tp.seasonalHats")) return null;
		java.time.LocalDate d = java.time.LocalDate.now();
		if (d.getMonthValue() == 12) return d.getDayOfMonth() >= 22 && d.getDayOfMonth() <= 28 ? "Christmas" : null;
		if (d.getMonthValue() == 10) return d.getDayOfMonth() >= 25 ? "Pumpkin" : null;
		int easter = easter(d.getYear()).getDayOfYear();
		return d.getDayOfYear() >= easter - 2 && d.getDayOfYear() <= easter ? "Easter" : null;
	}

	/** /ukv1 (debug, this client only): null = by date, "" = no hat, else that hat's mesh name. */
	private static String hatOverride;

	/**
	 * /ukv1 normal|pumpkin|christmas|easter|date: V1 drawn without a hat, with one hat whatever the date, or back to
	 * the date's hat. Client side, so it changes only how this game draws V1s.
	 */
	public static void registerCommands(net.minecraftforge.client.event.RegisterClientCommandsEvent e) {
		var root = net.minecraft.server.command.CommandManager.literal("ukv1")
			.then(net.minecraft.server.command.CommandManager.literal("normal").executes(c -> setHat(c.getSource(), "", "V1 without a hat")))
			.then(net.minecraft.server.command.CommandManager.literal("date").executes(c -> setHat(c.getSource(), null, "V1's hat by the date again")));
		for (String hat : new String[] {"Pumpkin", "Christmas", "Easter"})
			root.then(net.minecraft.server.command.CommandManager.literal(hat.toLowerCase(java.util.Locale.ROOT))
				.executes(c -> setHat(c.getSource(), hat, "V1 with the " + hat + " hat")));
		e.getDispatcher().register(root);
	}

	private static int setHat(net.minecraft.server.command.ServerCommandSource src, String hat, String msg) {
		hatOverride = hat;
		src.sendFeedback(() -> net.minecraft.text.Text.literal(msg), false);
		return 1;
	}

	/** SeasonalHats.GetEaster (the anonymous Gregorian computus, as the game has it). */
	private static java.time.LocalDate easter(int year) {
		int num = year % 19, num2 = year / 100;
		int num3 = (num2 - num2 / 4 - (8 * num2 + 13) / 25 + 19 * num + 15) % 30;
		int num4 = num3 - num3 / 28 * (1 - num3 / 28 * (29 / (num3 + 1)) * ((21 - num) / 11));
		int num5 = num4 - (year + year / 4 + num4 + 2 - num2 + num2 / 4) % 7;
		int month = 3 + (num5 + 40) / 44;
		int day = num5 + 28 - 31 * (month / 4);
		return java.time.LocalDate.of(year, month, day);
	}

	/**
	 * Clash mode lava (PlatformerMovement.Burn): a still copy of V1 in the pose it has right now (the clip held where it
	 * is), every mesh drawn with the Charcoal material's texture (SandboxUtils.StripForPreview with burnMaterial).
	 */
	public static RigInstance charcoalCopy(PlayerEntity p) {
		State st = STATES.get(p);
		UkRig rig = UkAssets.rig("v1");
		if (st == null || st.rig == null || rig == null) return null;
		RigInstance c = new RigInstance("v1", rig);
		c.play(st.rig.currentClip() != null ? st.rig.currentClip() : "Idle", 0, 0, st.rig.clipTime());
		c.hiddenMeshes.addAll(st.rig.hiddenMeshes);
		for (int i = 0; i < rig.meshes.size(); i++) c.meshTexture.put(i, "charcoal");
		return c;
	}

	/** The model's turn and scale at the feet, as onRenderPlayer sets them (for a copy standing at `yaw`). */
	public static Matrix4f copyMatrix(float yaw) {
		float s = (float) (BASE_SCALE * Tune.get("tp.scale"));
		return new Matrix4f().rotateY((float) Math.toRadians(Tune.get("tp.yaw") - yaw)).scale(s);
	}

	/** Draw a copy from charcoalCopy with the stack at its feet. */
	public static void renderCopy(RigInstance c, MatrixStack ms, VertexConsumerProvider buffers, int light, float yaw) {
		ms.push();
		ms.multiplyPositionMatrix(copyMatrix(yaw));
		c.pose(new Matrix4f());
		c.render(ms, buffers, light, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
		ms.pop();
	}

	public static void onRenderPlayer(RenderPlayerEvent.Pre e) {
		if (!Tune.on("tp.show") || !UkAssets.available()) return;
		// burnt in clash-mode lava: the charcoal copy and then the ash stand in for the model
		if (!inGui && e.getEntity() == MinecraftClient.getInstance().player && V1Burn.hidesLive(e.getEntity())) {
			e.setCanceled(true);
			return;
		}
		UkRig rig = UkAssets.rig("v1");
		if (rig == null) return;
		PlayerEntity p = e.getEntity();
		State st = (inGui ? GUI_STATES : STATES).computeIfAbsent(p, k -> new State());
		if (st.rig == null) {
			st.rig = new RigInstance("v1", rig);
			st.rig.play("Idle", 1, 0);
			st.anim = "Idle";
		}
		e.setCanceled(true);
		String hat = seasonalHat();
		for (int i = 0; i < rig.meshes.size(); i++) {
			String n = rig.meshes.get(i).name;
			if (HATS.contains(n) && !n.equals(hat)) st.rig.hiddenMeshes.add(i);
			else st.rig.hiddenMeshes.remove(i);
		}
		float tickDelta = e.getPartialTick();
		long now = System.nanoTime();
		float dt = st.lastNanos == 0 ? 0 : Math.min(0.1f, (now - st.lastNanos) / 1e9f);
		if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		st.lastNanos = now;

		MinecraftClient client = MinecraftClient.getInstance();
		boolean local = p == client.player;
		if (!inGui) animate(st, p, local, dt);
		st.rig.advance(dt);

		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider buffers = e.getMultiBufferSource();
		int light = e.getPackedLight();
		// the inventory preview (InventoryScreen.drawEntity) sets bodyYaw / pitch for the pose but not their prev values, so
		// interpolating there swings the model and gun every tick: use the current values whenever a screen is open
		boolean gui = com.ultracraft.client.uk.ViewModel.stopsTime(client.currentScreen);
		float yaw = gui ? p.bodyYaw : MathHelper.lerp(tickDelta, p.prevBodyYaw, p.bodyYaw);
		// clash mode slide (PlatformerMovement): the model faces the slide's dodgeDirection, whatever the camera does
		if (!inGui && local && V1.INSTANCE.sliding && !client.options.getPerspective().isFirstPerson()) {
			Vec3d d = V1.INSTANCE.dodgeDirection.lengthSquared() > 1e-6 ? V1.INSTANCE.dodgeDirection : new Vec3d(V1.INSTANCE.vel.x, 0, V1.INSTANCE.vel.z);
			if (d.lengthSquared() > 1e-6) yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		}
		ms.push();
		// sliding, the legs sank into the ground and only the toes showed above it (user): the model is lifted (by eye)
		String clipNow = st.rig.currentClip() != null ? st.rig.currentClip() : st.anim;
		if (is(clipNow, "Sliding")) ms.translate(0, Tune.get("tp.slideLift"), 0);
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) (Tune.get("tp.yaw") - yaw)));
		// clash mode spin: PlatformerMovement rotates the model 3600°/s about its up while spinning (spinJuice 1 → 0 at 3/s)
		if (!inGui && p == client.player && V1.INSTANCE.clashSpinUntil > System.nanoTime()) {
			double left = (V1.INSTANCE.clashSpinUntil - System.nanoTime()) / 1e9;
			ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) ((System.nanoTime() / 1e9 * 3600.0) % 360)));
		}
		float s = (float) (BASE_SCALE * Tune.get("tp.scale"));
		ms.scale(s, s, s);
		// the prefab mount (PlatformerController's world offset) is not applied: the model stands at the entity
		st.rig.pose(new Matrix4f());
		updateWings(st, local ? V1.INSTANCE.boostCharge : 300);
		st.rig.render(ms, buffers, light, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
		if (!inGui && p == client.player && V1.INSTANCE.clashSpinUntil > System.nanoTime()) {
			Matrix4f root = st.rig.boneWorld("");
			drawSpinRings(ms, buffers, root == null ? new Matrix4f() : root);
		}
		if (Tune.on("tp.weapons")) drawWeapon(st, p, ms, buffers, light); // clash mode: the model fights bare-handed
		// the off-hand item in V1's left hand (OffhandHold)
		com.ultracraft.client.weapon.OffhandHold.drawThirdPerson(p, st.rig.boneWorldByName("hand.L"), ms, buffers, light);
		if (!inGui) recordHand(st, p, ms, yaw, s);
		ms.pop();
	}

	/**
	 * Player/SpinZone (local y 2, scale 4) / Effect (y 0.25, Spin 3600°/s): RageEffectWhite sprites (64 px at 100 ppu
	 * = 0.64 u) lying flat at scales 1 / 2 / 3 with alpha 0.5 / 0.25 / 0.125, and a fourth (scale 1, alpha 0.125)
	 * 0.25 lower. Drawn in the model's root space, so they size with the model.
	 */
	private static void drawSpinRings(MatrixStack ms, VertexConsumerProvider buffers, Matrix4f root) {
		net.minecraft.client.render.VertexConsumer vc = buffers.getBuffer(net.minecraft.client.render.RenderLayer.getEntityTranslucentEmissive(new net.minecraft.util.Identifier(com.ultracraft.UltraCraft.MOD_ID, "textures/uk/rageeffectwhite.png")));
		float ang = (float) ((System.nanoTime() / 1e9 * 3600.0) % 360);
		ms.push();
		ms.multiplyPositionMatrix(root);
		ms.translate(0, 2, 0);
		ms.scale(4, 4, 4);
		ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(ang));
		Matrix4f m = ms.peek().getPositionMatrix();
		float[] scales = {1f, 2f, 3f, 1f}, alphas = {0.5f, 0.25f, 0.125f, 0.125f}, ys = {0.25f, 0.25f, 0.25f, -0.25f};
		for (int i = 0; i < 4; i++) {
			Matrix4f mi = new Matrix4f(m).translate(0, ys[i], 0);
			ring(vc, mi, 0.32f * scales[i], alphas[i]);
		}
		ms.pop();
	}

	private static void ring(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, float h, float a) {
		int al = (int) (a * 255);
		vc.vertex(m, -h, 0, -h).color(255, 255, 255, al).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, -h, 0, h).color(255, 255, 255, al).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, h, 0, h).color(255, 255, 255, al).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, h, 0, -h).color(255, 255, 255, al).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, h, 0, -h).color(255, 255, 255, al).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, -1, 0).next();
		vc.vertex(m, h, 0, h).color(255, 255, 255, al).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, -1, 0).next();
		vc.vertex(m, -h, 0, h).color(255, 255, 255, al).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, -1, 0).next();
		vc.vertex(m, -h, 0, -h).color(255, 255, 255, al).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, -1, 0).next();
	}

	/**
	 * PlatformerMovement.UpdateWings: the V1Wing submeshes (materials 1–8 of v1_mdl) glow with their emissive map
	 * (v1_wingcolour_emissive_colorless) at intensity 1.2 or not at all. Materials 1, 2, 3, 6 need a full 300
	 * stamina, 4 and 7 need 200, 5 and 8 need 100; the colour is (1, 0.66, 0) at 300, (1, 0.33, 0) at 200, red below.
	 */
	private static void updateWings(State st, double boostCharge) {
		float r = 1f, g = boostCharge >= 300 ? 0.66f : boostCharge >= 200 ? 0.33f : 0f, b = 0f;
		float k = (float) Tune.get("tp.wingGlow");
		for (int i = 1; i <= 8; i++) {
			double need = (i == 4 || i == 7) ? 200 : (i == 5 || i == 8) ? 100 : 300;
			boolean lit = boostCharge >= need;
			st.rig.setSubmeshEmissive("v1_mdl", i, lit ? "v1_wingcolour_emissive_colorless" : null, Math.min(1f, r * k), Math.min(1f, g * k), Math.min(1f, b * k));
		}
	}

	/** Pick the clip from the movement state (V1Platformer controller: the same conditions, by hand). */
	private static void animate(State st, PlayerEntity p, boolean local, float dt) {
		boolean ground, sliding, boost, slam;
		double speed;
		boolean jumpedNow = false;
		if (local) {
			V1 v = V1.INSTANCE;
			ground = v.onGround;
			sliding = v.sliding;
			boost = v.boost && !v.sliding;
			// SlamStart / SlamEnd are the third-person (clash) slam: in first person the body (seen in its shadow) just falls
			// and lands (the user's call)
			slam = (v.heavyFall || v.aboutToSlam) && !net.minecraft.client.MinecraftClient.getInstance().options.getPerspective().isFirstPerson();
			speed = new Vec3d(v.vel.x, 0, v.vel.z).length();
			// gliding on gasoline with no key held is not running: V1 stands still on it (the user's call; the game has no
			// third person to check)
			if (v.onGasoline && v.inputDir.lengthSquared() < 1e-6) speed = 0;
			jumpedNow = v.jumping && v.jumpingTimer < 0.05 && !ground;
			if (v.currentWallJumps != st.wallJumps) {
				if (v.currentWallJumps > st.wallJumps) play(st, "Spin", 1, 0.05f);
				st.wallJumps = v.currentWallJumps;
			}
			if (v.clashSpin && p == net.minecraft.client.MinecraftClient.getInstance().player) {
				v.clashSpin = false;
				play(st, "Spin", 1, 0.05f);
				if (com.ultracraft.weapon.Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] clash: Spin clip -> {}", st.rig.currentClip());
			}
		} else {
			ground = p.isOnGround();
			sliding = p.isSneaking() && p.isOnGround();
			boost = false;
			slam = false;
			speed = new Vec3d(p.getX() - p.prevX, 0, p.getZ() - p.prevZ).length() * 20 / Damage.SCALE_BLOCKS;
		}
		if (!ground) st.airTime += dt;
		// what the rig is really playing (clip names come from the files, compare loosely)
		String cur = st.rig.currentClip() != null ? st.rig.currentClip() : st.anim;
		boolean spinning = is(cur, "Spin") && System.nanoTime() < st.spinUntil;
		boolean moving = ground && speed > Tune.get("tp.runSpeed");
		// V1Platformer: SlamEnd (the hit, the bounce up and back) plays out into Idle; running, it hands over to Running from
		// 51% of the clip on (blend 0.49 of it)
		boolean slamEndRunOut = is(cur, "SlamEnd") && moving && st.rig.clipTime() >= SLAM_END_RUN * SLAM_END_LENGTH;
		boolean oneShotRunning = ((is(cur, "Jump") || is(cur, "Landing") || is(cur, "Dash") || is(cur, "SlamEnd")) && !st.rig.finished() && !slamEndRunOut) || spinning;
		// the slam's end lands (it can come a frame after the feet touch: checked on its own, not with the landing)
		boolean slamLanded = st.wasSlam && !slam && ground;
		if (slam && !st.wasSlam) play(st, "SlamStart", 1, 0.05f);
		else if (slamLanded) {
			play(st, "SlamEnd", 1, 0.02f);
			st.airTime = 0;
		} else if (boost && !st.wasBoost) play(st, "Dash", 2.5f, 0.05f);
		else if (ground && !st.wasGround) {
			if (st.airTime > 0.25) play(st, "Landing", 1, 0.03f);
			st.airTime = 0;
		} else if (sliding) {
			if (!is(cur, "Sliding")) play(st, "Sliding", 1, 0.09f);
		} else if (!oneShotRunning && !is(cur, "SlamStart")) {
			if (!ground) {
				if (jumpedNow && !is(cur, "Jump")) play(st, "Jump", 1, 0.05f);
				else if (!is(cur, "Falling") && !is(cur, "Jump")) play(st, "Falling", 1, 0.12f);
			} else if (speed > Tune.get("tp.runSpeed")) {
				if (!is(cur, "Running")) play(st, "Running", 1, slamEndRunOut ? 0.49f * SLAM_END_LENGTH : 0.16f);
			} else if (!is(cur, "Idle")) play(st, "Idle", 1, 0.18f);
		}
		// a slam cut short (no SlamEnd this frame) does not leave the model frozen in SlamStart
		if (is(cur, "SlamStart") && !slam && ground && !slamLanded) play(st, "Idle", 1, 0.1f);
		st.wasGround = ground;
		st.wasBoost = boost;
		st.wasSlam = slam;
		st.wasSliding = sliding;
	}

	/** SlamEnd's length (s) and the share of it after which running takes over (V1Platformer's transition exit time). */
	private static final float SLAM_END_LENGTH = 0.6667f, SLAM_END_RUN = 0.5124f;

	private static boolean is(String cur, String name) {
		return cur != null && cur.equalsIgnoreCase(name);
	}

	private static void play(State st, String clip, float speed, float fade) {
		st.rig.play(clip, speed, fade);
		st.anim = clip;
		if (clip.equals("Spin")) st.spinUntil = System.nanoTime() + (long) (Tune.get("clash.spinTime") * 1e9);
	}

	private static void drawWeapon(State st, PlayerEntity p, MatrixStack ms, VertexConsumerProvider buffers, int light) {
		WeaponKind kind = UKWeaponItem.kindOf(p.getMainHandStack());
		if (kind == null) return;
		boolean alt = UKWeaponItem.alternate(p.getMainHandStack());
		RigInstance gun = ViewModel.instance(kind.rig(alt));
		if (gun == null) return;
		Matrix4f head = st.rig.boneWorldByName("spine.006");
		if (head == null) return;
		// The gun is posed exactly as in first person — the prefab's holder chain (mount) under a camera frame — only
		// the frame is the model's head instead of the player's eye: +Z forward in rig space like Unity's camera, the
		// look pitch applied, first-person units scaled to model units (1 u = 1.8/3.5 blocks vs 1.8/3.84).
		Vector3f hp = head.getTranslation(new Vector3f());
		float k = (float) (Tune.get("tp.gunScale") * Damage.SCALE_BLOCKS / BASE_SCALE);
		float td = MinecraftClient.getInstance().getTickDelta();
		boolean gui = com.ultracraft.client.uk.ViewModel.stopsTime(MinecraftClient.getInstance().currentScreen);
		float pitch = (float) Math.toRadians((gui ? p.getPitch() : MathHelper.lerp(td, p.prevPitch, p.getPitch())) * Tune.get("tp.aimPitch"));
		Matrix4f m = new Matrix4f().translation(hp.x + (float) Tune.get("tp.gunX"), hp.y + (float) Tune.get("tp.gunY"), hp.z + (float) Tune.get("tp.gunZ"))
			.rotateX(pitch)
			.scale(k)
			.mul(ViewModel.mountMatrix(gun.rig, new Matrix4f()));
		java.util.Set<Integer> hidden = new java.util.HashSet<>(gun.hiddenMeshes);
		for (int i = 0; i < gun.rig.meshes.size(); i++) if (gun.rig.meshes.get(i).name.toLowerCase().contains("arm")) gun.hiddenMeshes.add(i);
		gun.pose(m);
		gun.render(ms, buffers, light, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
		gun.hiddenMeshes.clear();
		gun.hiddenMeshes.addAll(hidden);
	}

	private static void recordHand(State st, PlayerEntity p, MatrixStack ms, float yaw, float s) {
		if (p != MinecraftClient.getInstance().player) return;
		Matrix4f hand = st.rig.boneWorldByName("hand.L");
		if (hand == null) return;
		Vector4f h = new Vector4f(0, 0, 0, 1).mul(hand);
		// same mapping as RigInstance.render (z mirrored), then the entity-local rotation and scale
		Vector3f v = new Vector3f(h.x, h.y, -h.z).mul(s);
		float a = (float) Math.toRadians(Tune.get("tp.yaw") - yaw);
		float cx = (float) Math.cos(a), sx = (float) Math.sin(a);
		Vector3f r = new Vector3f(v.x * cx + v.z * sx, v.y, -v.x * sx + v.z * cx);
		float td = MinecraftClient.getInstance().getTickDelta();
		Vec3d base = new Vec3d(MathHelper.lerp(td, p.prevX, p.getX()), MathHelper.lerp(td, p.prevY, p.getY()), MathHelper.lerp(td, p.prevZ, p.getZ()));
		lastHandL = base.add(r.x, r.y, r.z);
		lastHandFrame = System.nanoTime();
	}
}
