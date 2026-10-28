package com.ultracraft.client.hud;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UltraCraftClient;
import com.ultracraft.client.style.StyleState;
import com.ultracraft.client.v1.V1;
import com.ultracraft.client.weapon.WeaponClient;
import com.ultracraft.item.RevolverItem;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.client.weapon.ArsenalClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;

/**
 * ULTRAKILL's HUD rebuilt from the Player prefab (`HUD Camera/HUD`): GunCanvas (stats panel with health /
 * hard damage / stamina, fist icon, weapon icon, speedometer) at (-1.06, -0.53, 1) turned -30°, and
 * StyleCanvas (rank, meter, style entries, multiplier, freshness) at (1.3, 0.3, 1) turned +30°, both seen
 * through a 90° camera. Rects below are the prefab's RectTransforms resolved to canvas units; colours are
 * the prefab's / ColorBlindSettings defaults. `hud.classic = 1` brings back the M1 bars.
 */
public final class UkHud {
	private static final HudDraw d = new HudDraw();
	// live layout: everything the eye tunes
	static {
		Tune.def("hud.classic", 0, "1 = the plain M1 bars instead of ULTRAKILL's HUD");
		Tune.def("hud.speedometer", 1, "1 = show the speedometer (SPEED: u/s, like ULTRAKILL's option)");
		Tune.def("hud.barHalf", 9, "health bar half height in panel units (the game's fill spans its 18-unit row)");
		Tune.def("hud.stamHalf", 8, "stamina bar half height in panel units (16-unit row)");
		Tune.def("hud.styleEntrySize", 48, "style entry text size (Panel/Text: TMP 48); a line wider than the 437 u box shrinks to fit");
		Tune.def("hud.multLabelSize", 35, "style MULTIPLIER label size (TMP 35)");
		Tune.def("hud.stamMidX", 0, "middle stamina bar x offset in panel units (negative = left)");
		Tune.def("hud.fov", 90, "HUD camera vertical FOV (prefab: 90)");
		Tune.def("hud.gunX", -1.06, "GunCanvas x in HUD camera space (prefab: -1.06)");
		Tune.def("hud.gunY", -0.53, "GunCanvas y (prefab: -0.53)");
		Tune.def("hud.gunZ", 1.0, "GunCanvas z (prefab: 1)");
		Tune.def("hud.gunYaw", -30, "GunCanvas yaw degrees (prefab: -30)");
		Tune.def("hud.styleX", 1.3, "StyleCanvas x (prefab: 1.3)");
		Tune.def("hud.styleY", 0.3, "StyleCanvas y (prefab: 0.3)");
		Tune.def("hud.styleZ", 1.0, "StyleCanvas z (prefab: 1)");
		Tune.def("hud.styleYaw", 30, "StyleCanvas yaw degrees (prefab: 30)");
		Tune.def("hud.fontScale", 1.0, "multiplier on text size (TMP points → the VCR font)");
		Tune.def("hud.styleOuterAlpha", 0, "style panel outer background opacity (0: the rank and action boxes stand apart, the countdown bar in the gap)");
		Tune.def("hud.boxAlpha", 0.3, "opacity of every HUD box background (gun, health, arm, railcannon, style rank / actions; the game: 0.5)");
		Tune.def("hud.styleInnerAlpha", 0.35, "style panel rank / actions box opacity (see-through)");
		Tune.def("hud.freshTextSize", 34, "freshness bar text size (the bar is 40 high; the game's TMP size 40)");
		Tune.def("hud.hpRed", 0, "1 = the health number turns red at 30 or less, as in the game; 0 = always white");
		Tune.def("hud.hpOutline", 1, "1 = black outline on the red low-health number (it sits on the red fill)");
		Tune.def("hud.hpOutlineSize", 0.8, "outline thickness in panel units");
		Tune.def("hud.styleAlways", 0, "1 = keep the style panel up outside combos (StyleHUD.forceMeterOn)");
		Tune.def("hud.weaponIcon", 1, "1 = show the weapon icon panel");
		Tune.def("hud.armIcon", 1, "1 = show the fist icon");
	}
	// ColorBlindSettings defaults (scene values)
	private static final float[] HEALTH = {1, 0, 0}, AFTER = {1, 0.392f, 0}, ANTI = {0.349f, 0.349f, 0.349f}, OVERHEAL = {0, 1, 0.004f};
	private static final float[] STAMINA = {0, 0.875f, 1}, STAMINA_EMPTY = {1, 0, 0};
	private static final float[][] STAMINA_FILL = {{0, 0.876f, 1}, {0.207f, 0.908f, 1}, {0.324f, 0.933f, 1}};
	private static final float PPU = 5.4f;
	private static final Identifier FILL_LARGE = HudDraw.tex("round_filllarge"), FILL_SMALL = HudDraw.tex("round_fillsmall"),
		METER = HudDraw.tex("round_meter"), METER_LEFT = HudDraw.tex("round_meterleft"), METER_RIGHT = HudDraw.tex("round_meterright"),
		ANTI_TEX = HudDraw.tex("round_antihealth"), FIST_ICON = HudDraw.tex("fisticon"), ARM_FEEDBACKER = HudDraw.tex("armfeedbacker"), ARM_KNUCKLEBLASTER = HudDraw.tex("armknuckleblaster"), REVOLVER_ICON = HudDraw.tex("singlerevolver"),
		REVOLVER_GLOW = HudDraw.tex("singlerevolverglow");
	// HealthBar / StaminaMeter smoothing state
	private static float hpShown, afterShown, antiShown, staminaShown;
	private static boolean[] staminaFull = {true, true, true};
	private static float[] staminaFlash = new float[3];
	private static float greenFlash;
	private static int lastHp = -1;
	private static final java.util.Random rnd = new java.util.Random();

	private UkHud() {}

	private static final Identifier CIRCLE_THICK = HudDraw.tex("circlethick");
	/** Canvas / Crosshair / StaminaBars: StaminaSlider (1)–(3) z rotations (degrees), bottom bar first. */
	private static final float[] CROSS_STAM_ROT = {3.0f, 62.5f, 122.0f};

	static {
		Tune.def("hud.crosshairStamina", 1, "crosshair HUD: stamina (right) and health (left) arcs (the game's Crosshair HUD option)");
		Tune.def("hud.crosshairFade", 1, "crosshair HUD arcs fade out 2 s after they last changed (the game's Crosshair HUD fade option)");
	}

	/** Canvas / Crosshair / HealthBars and StaminaBars (FadeOutBars): fadeOutTime, back to 2 whenever a fill changes. */
	private static float healthFade = 2, staminaFade = 2;
	private static float[] lastHealthFills = new float[5], lastStaminaFills = new float[3];
	/** HealthBars' Images (SliderToFillAmount maxFill 0.485): z rotation, clockwise, colour; drawn in this order. */
	private static final float[] HB_ROT = {-3f, -3f, 183f, -3f, -3f};
	private static final boolean[] HB_CW = {true, true, false, true, true};
	private static final float[][] HB_COLOR = {{1, 0, 0}, {0.2667f, 1, 0.2706f}, {0.75f, 0.75f, 0.75f}, {1, 0, 0}, {0.7028f, 1, 0.7044f}};

	/**
	 * The game's Crosshair HUD: 32 x 32 "circlethick" Images on the 1280 x 720 Canvas (CanvasScaler Expand), Filled Radial
	 * 360 from the bottom. Stamina, right: three, counter-clockwise, rotated 3 / 62.5 / 122 degrees, each filled to
	 * (value - min) / (max - min) x 0.15 in its stamina bar's colour: a half moon that empties from the top as dashes are
	 * spent. Health, left: HealthSliderAfterImage (red), HealthSlider (green), AntiHPSlider (grey, turned 183 degrees so
	 * it fills from the top), HealthSliderAfterImage (1) and SuperchargeSlider (pale green, over 100), clockwise from the
	 * bottom, x 0.485. SliderToFillAmount: a fill that changes resets its group's FadeOutBars to 2 s, counted down
	 * (unscaled) when the fade option is on, and the arcs' alpha is min(1, that): a second fully there, a second fading.
	 */
	private static void crosshairStamina(DrawContext ctx, MinecraftClient client, int w, int h, float dt) {
		float[] stam = new float[3];
		for (int i = 0; i < 3; i++) stam[i] = Math.max(0, Math.min(1, (staminaShown - i * 100) / 100f)) * 0.15f;
		float[] hb = {
			Math.max(0, Math.min(100, afterShown)) / 100f * 0.485f,
			Math.max(0, Math.min(100, hpShown)) / 100f * 0.485f,
			Math.max(0, Math.min(100, antiShown)) / 100f * 0.485f,
			Math.max(0, Math.min(100, afterShown - 100)) / 100f * 0.485f,
			Math.max(0, Math.min(100, hpShown - 100)) / 100f * 0.485f};
		if (!java.util.Arrays.equals(stam, lastStaminaFills)) { staminaFade = 2; lastStaminaFills = stam; }
		if (!java.util.Arrays.equals(hb, lastHealthFills)) { healthFade = 2; lastHealthFills = hb; }
		boolean fade = Tune.on("hud.crosshairFade");
		if (fade) {
			staminaFade = Math.max(0, staminaFade - dt);
			healthFade = Math.max(0, healthFade - dt);
		}
		float stamAlpha = fade ? Math.min(1, staminaFade) : 1, healthAlpha = fade ? Math.min(1, healthFade) : 1;
		if (stamAlpha <= 0 && healthAlpha <= 0) return;
		var win = client.getWindow();
		float unit = (float) (Math.min(win.getFramebufferWidth() / 1280.0, win.getFramebufferHeight() / 720.0) / win.getScaleFactor());
		float half = 16 * unit, cx = w / 2f, cy = h / 2f;
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionTexColorProgram);
		com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, CIRCLE_THICK);
		com.mojang.blaze3d.systems.RenderSystem.disableCull(); // the fan winds clockwise on the y-down GUI
		org.joml.Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
		var bb = net.minecraft.client.render.Tessellator.getInstance().getBuffer();
		bb.begin(net.minecraft.client.render.VertexFormat.DrawMode.TRIANGLES, net.minecraft.client.render.VertexFormats.POSITION_TEXTURE_COLOR);
		if (healthAlpha > 0) for (int i = 0; i < hb.length; i++)
			radial(bb, m, cx, cy, half, HB_ROT[i], hb[i], HB_CW[i], HB_COLOR[i][0], HB_COLOR[i][1], HB_COLOR[i][2], healthAlpha);
		if (stamAlpha > 0) for (int i = 0; i < 3; i++) {
			float[] c = staminaFull[i] || i != 0 ? STAMINA : STAMINA_EMPTY;
			float a = staminaFull[i] || i == 0 ? 1f : 0.6f;
			float r = c[0] + (1 - c[0]) * staminaFlash[i], g = c[1] + (1 - c[1]) * staminaFlash[i], b = c[2] + (1 - c[2]) * staminaFlash[i];
			radial(bb, m, cx, cy, half, CROSS_STAM_ROT[i], stam[i], false, r, g, b, a * stamAlpha);
		}
		net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(bb.end());
		com.mojang.blaze3d.systems.RenderSystem.enableCull();
	}

	/** One Image.Type Filled, Radial 360, origin bottom, turned rotDeg (counter-clockwise), `amount` of the circle. */
	private static void radial(net.minecraft.client.render.BufferBuilder bb, org.joml.Matrix4f m, float cx, float cy, float half, float rotDeg, float amount, boolean clockwise,
			float r, float g, float b, float a) {
		if (amount <= 0) return;
		double rot = Math.toRadians(rotDeg);
		double start = -Math.PI / 2, sweep = amount * Math.PI * 2 * (clockwise ? -1 : 1);
		int steps = Math.max(2, (int) Math.ceil(Math.abs(sweep) / Math.toRadians(3)));
		float[] prev = null;
		for (int k = 0; k <= steps; k++) {
			double phi = start + sweep * k / steps;
			// Unity radial fill: the ray from the centre to the sprite rectangle's edge
			double cs = Math.cos(phi), sn = Math.sin(phi), edge = 1 / Math.max(Math.abs(cs), Math.abs(sn));
			double lx = cs * edge * half, ly = sn * edge * half;
			float u = (float) (0.5 + lx / (2 * half)), vv = (float) (0.5 - ly / (2 * half));
			double rx = lx * Math.cos(rot) - ly * Math.sin(rot), ry = lx * Math.sin(rot) + ly * Math.cos(rot);
			float[] pt = {(float) (cx + rx), (float) (cy - ry), u, vv};
			if (prev != null) {
				bb.vertex(m, cx, cy, 0).texture(0.5f, 0.5f).color(r, g, b, a).next();
				bb.vertex(m, pt[0], pt[1], 0).texture(pt[2], pt[3]).color(r, g, b, a).next();
				bb.vertex(m, prev[0], prev[1], 0).texture(prev[2], prev[3]).color(r, g, b, a).next();
			}
			prev = pt;
		}
	}

	public static void render(DrawContext ctx, float tickDelta, int w, int h) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity p = client.player;
		if (p == null || client.options.hudHidden) return;
		if (Tune.on("hud.classic")) {
			V1Hud.render(ctx, tickDelta, w, h);
			return;
		}
		float dt = Math.min(0.1f, client.getLastFrameDuration() / 20f); // frame time in seconds
		lastDt = dt;
		V1 v = V1.INSTANCE;
		StyleState st = StyleState.INSTANCE;
		d.fontScale = Tune.getF("hud.fontScale");
		smooth(v, dt);
		if (client.options.getPerspective().isFirstPerson()) com.ultracraft.client.weapon.GunDisplays.drawScreenFlash(ctx, w, h);
		if (Tune.on("hud.crosshairStamina") && client.options.getPerspective().isFirstPerson()) crosshairStamina(ctx, client, w, h, dt);

		d.begin(V1.INSTANCE.hudFov(Tune.getF("hud.fov")));
		// ---------------------------------------------------------------- GunCanvas (882x496, scale 0.0007 x 0.001)
		d.canvas(Tune.getF("hud.gunX"), Tune.getF("hud.gunY"), Tune.getF("hud.gunZ"), yaw(Tune.getF("hud.gunYaw")), 0.0007f, 0.001f);
		float cx = -441, cy = -248; // canvas bottom-left
		// StatsPanel: pos (-79, 0) size 200x46, scale 4
		d.push();
		d.translate(cx - 79, cy);
		d.scale(4);
		statsPanel(p, v, st, dt);
		d.pop();
		// GunPanel: pos (-79, 187) size 200x100, scale 4
		if (Tune.on("hud.weaponIcon")) {
			d.push();
			d.translate(cx - 79, cy + 187);
			d.scale(4);
			gunPanel(p);
			d.pop();
		}
		// SpeedometerPanel: pos (-79, 590) size 200x25, scale 4
		if (Tune.on("hud.speedometer") && com.ultracraft.UkConfig.speedometer()) {
			d.push();
			d.translate(cx - 79, cy + 590);
			d.scale(4);
			d.sliced(FILL_LARGE, 83, 37, PPU, 0, 0, 200, 25, 0, 0, 0, boxA());
			double speed = Math.sqrt(v.vel.x * v.vel.x + v.vel.y * v.vel.y + v.vel.z * v.vel.z);
			d.text(String.format("SPEED: %.2f u/s", speed), 100, 20.5f, 16, HudDraw.Align.CENTER, 0xFFFFFFFF);
			d.pop();
		}
		d.pop();

		// ---------------------------------------------------------------- StyleCanvas (882x496, scale 0.001 x 0.002)
		if (st.comboActive || Tune.on("hud.styleAlways")) {
			d.canvas(Tune.getF("hud.styleX"), Tune.getF("hud.styleY"), Tune.getF("hud.styleZ"), yaw(Tune.getF("hud.styleYaw")), 0.001f, 0.002f);
			stylePanel(st);
			d.pop();
		}
		d.end();
		// TimeController.ParryFlash: the screen Canvas' ParryFlash image (white, alpha 0.49) over everything, the HUD
		// included, while the freeze lasts. Drawn last: the full-screen fill writes depth and hid the HUD text drawn after it
		if (((V1.INSTANCE.frozen() && V1.INSTANCE.parryFlash > 0) || Tune.on("parry.preview")) && com.ultracraft.UkConfig.parryFlash()) {
			int a = (int) (255 * Math.max(0, Math.min(1, Tune.get("parry.flashAlpha"))));
			ctx.fill(0, 0, w, h, (a << 24) | 0xFFFFFF);
		}
		if (!V1.INSTANCE.frozen()) V1.INSTANCE.parryFlash = 0;
	}

	private static Quaternionf yaw(float degrees) {
		// Unity: rotation about +y by `degrees` (left-handed), as the prefab stores it
		return new Quaternionf().rotationY((float) Math.toRadians(degrees));
	}

	/** HealthBar / StaminaMeter.Update smoothing. */
	private static void smooth(V1 v, float dt) {
		float hp = v.hp;
		if (hpShown < hp) hpShown = StyleState.moveTowards(hpShown, hp, dt * ((hp - hpShown) * 5 + 5));
		else hpShown = hp;
		if (afterShown < hpShown) afterShown = hpShown;
		else if (afterShown > hpShown) afterShown = StyleState.moveTowards(afterShown, hpShown, dt * ((afterShown - hpShown) * 5 + 5));
		float anti = (float) v.antiHp;
		antiShown = StyleState.moveTowards(antiShown, anti, dt * (Math.abs(antiShown - anti) * 5 + 5));
		float stamina = (float) v.boostCharge;
		if (staminaShown < stamina) staminaShown = StyleState.moveTowards(staminaShown, stamina, dt * ((stamina - staminaShown) * 25 + 25));
		else if (staminaShown > stamina) staminaShown = StyleState.moveTowards(staminaShown, stamina, dt * ((staminaShown - stamina) * 25 + 25));
		for (int i = 0; i < 3; i++) {
			float value = Math.max(i * 100, Math.min((i + 1) * 100, staminaShown));
			boolean full = value >= (i + 1) * 100;
			if (full && !staminaFull[i]) staminaFlash[i] = 1f; // ReadyFlash
			staminaFull[i] = full;
			if (staminaFlash[i] > 0) staminaFlash[i] = Math.max(0, staminaFlash[i] - dt);
		}
		if (lastHp >= 0 && v.hp > lastHp) { // GreenHPFlash on heal (its AudioSource: HpGet)
			greenFlash = 1f;
			com.ultracraft.client.UKSound.play("uk.ui.hpget", 1f, 1f);
		}
		if (greenFlash > 0) greenFlash = Math.max(0, greenFlash - dt);
		lastHp = v.hp;
	}

	/** The black behind every HUD box: one opacity for all of them, as in the game. */
	private static float boxA() {
		return (float) Tune.get("hud.boxAlpha");
	}

	/** StatsPanel in its own 200x46 space (y up). */
	private static void statsPanel(ClientPlayerEntity p, V1 v, StyleState st, float dt) {
		d.sliced(FILL_LARGE, 83, 37, PPU, 0, 0, 200, 46, 0, 0, 0, boxA());
		// health row: the slider fill spans the whole 18-unit row like the game's (no black edge around it)
		float bx0 = 5, bx1 = 195, bw = bx1 - bx0, byc = 32, bh = Tune.getF("hud.barHalf");
		d.sliced(METER, 64, 31, PPU, bx0, byc - bh, bx1, byc + bh, 0, 0, 0, 0.686f);
		// after-image (orange), health (red), overheal (green over 100), hard damage from the right (grey)
		float after = Math.min(100, afterShown) / 100f, hp = Math.min(100, hpShown) / 100f;
		if (after > 0) d.sliced(METER, 64, 31, PPU, bx0, byc - bh, bx0 + bw * after, byc + bh, AFTER[0], AFTER[1], AFTER[2], 1);
		if (hp > 0) d.sliced(METER, 64, 31, PPU, bx0, byc - bh, bx0 + bw * hp, byc + bh, HEALTH[0], HEALTH[1], HEALTH[2], 1);
		if (greenFlash > 0 && hp > 0) d.sliced(METER, 64, 31, PPU, bx0, byc - bh, bx0 + bw * hp, byc + bh, 0.324f, 1, 0, greenFlash);
		if (hpShown > 100) {
			float over = Math.min(100, hpShown - 100) / 100f;
			d.sliced(METER, 64, 31, PPU, bx0, byc - bh, bx0 + bw * over, byc + bh, OVERHEAL[0], OVERHEAL[1], OVERHEAL[2], 1);
		}
		float anti = Math.min(100, antiShown) / 100f;
		if (anti > 0.001f) d.sliced(ANTI_TEX, 50, 25, PPU, bx1 - bw * anti - 2, byc - bh, bx1, byc + bh, ANTI[0], ANTI[1], ANTI[2], 1);
		// "+" and the number (HP Text: left aligned at x 20, TMP 17; red under 30)
		int hpInt = Math.round(hpShown);
		int hpColor = Tune.on("hud.hpRed") ? (hpInt <= 30 ? 0xFFFF0000 : 0xFFFFFFFF) : 0xFFFFFFFF; // white always (the game turns it red at 30 or less: hud.hpRed 1)
		d.text("+", 10, 38.5f, 13, HudDraw.Align.LEFT, 0xFFFFFFFF);
		// HealthBar: red at 30 or less, as in the game; a thin black outline keeps it readable over the red fill
		if (hpInt <= 30 && Tune.on("hud.hpRed") && Tune.on("hud.hpOutline")) {
			float o = (float) Tune.get("hud.hpOutlineSize");
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
				if (dx == 0 && dy == 0) continue;
				d.text(Integer.toString(hpInt), 20 + dx * o, 40.5f + dy * o, 17, HudDraw.Align.LEFT, 0xFF000000);
			}
		}
		d.text(Integer.toString(hpInt), 20, 40.5f, 17, HudDraw.Align.LEFT, hpColor);
		// stamina row: three sliders 61 / 62 / 61 wide, each its own rounded pill (left / middle / right sprites);
		// the gaps between them stay clear
		float sy0 = 13 - Tune.getF("hud.stamHalf"), sy1 = 13 + Tune.getF("hud.stamHalf");
		float[][] slots = {{5, 66}, {69, 131}, {134, 195}};
		Identifier[] bg = {METER_LEFT, METER, METER_RIGHT};
		int[] bgSize = {50, 64, 50}, bgBorder = {25, 31, 25};
		for (int i = 0; i < 3; i++) {
			float x0 = slots[i][0], x1 = slots[i][1];
			if (i == 1) { x0 += Tune.getF("hud.stamMidX"); x1 += Tune.getF("hud.stamMidX"); }
			boolean flat = i == 1; // the middle slider is a plain bar; the outer two have the rounded outer end
			if (flat) d.fill(x0, sy0, x1, sy1, 0, 0, 0, 0.686f);
			else d.sliced(bg[i], bgSize[i], bgBorder[i], PPU, x0, sy0, x1, sy1, 0, 0, 0, 0.686f);
			float value = Math.max(0, Math.min(1, (staminaShown - i * 100) / 100f));
			float[] c = staminaFull[i] || i != 0 ? STAMINA : STAMINA_EMPTY; // one stamina colour; the first bar turns red when empty
			float a = staminaFull[i] || i == 0 ? 1f : 0.6f; // staminaChargingColor has alpha 0.6
			if (value > 0) {
				if (flat) d.fill(x0, sy0, x0 + (x1 - x0) * value, sy1, c[0], c[1], c[2], a);
				else d.sliced(bg[i], bgSize[i], bgBorder[i], PPU, x0, sy0, x0 + (x1 - x0) * value, sy1, c[0], c[1], c[2], a);
				if (staminaFlash[i] > 0) {
					if (flat) d.fill(x0, sy0, x0 + (x1 - x0) * value, sy1, 1, 1, 1, staminaFlash[i]);
					else d.sliced(bg[i], bgSize[i], bgBorder[i], PPU, x0, sy0, x0 + (x1 - x0) * value, sy1, 1, 1, 1, staminaFlash[i]);
				}
			}
		}
		// RailcannonMeter.RailcannonStatus: the meter is up whenever a railcannon is in the loadout, held or not
		int railVar = railcannonOwned(p);
		if (railVar >= 0) railcannonPanel(railVar);
		// FistPanel: 46x46 to the right of the panel (right edge at 247), icon 36x36 filled by punch stamina / 2
		if (Tune.on("hud.armIcon")) {
			float fx0 = 201, fx1 = 247, fy0 = 0, fy1 = 46;
			d.sliced(FILL_LARGE, 83, 37, PPU, fx0, fy0, fx1, fy1, 0, 0, 0, boxA());
			WeaponClient wc = WeaponClient.INSTANCE;
			// HudController.UpdateFistIcon: the arm's colour (Knuckleblaster red); the Feedbacker uses the stamina
			// bars' blue so the punch and dash read as one colour
			float r, g, b;
			if (wc.currentFist == 1) {
				int rgb = UltraCraftClient.VARIATION_COLORS[2];
				r = ((rgb >> 16) & 255) / 255f; g = ((rgb >> 8) & 255) / 255f; b = (rgb & 255) / 255f;
			} else {
				r = STAMINA[0]; g = STAMINA[1]; b = STAMINA[2];
			}
			float ix0 = 224 - 18, ix1 = 224 + 18, iy0 = 5, iy1 = 41;
			// FistPanel's icon sprite per arm: ArmFeedbacker (the parry arm) / ArmKnuckleblaster (the shotgun-shell arm)
			Identifier icon = wc.currentFist == 1 ? ARM_KNUCKLEBLASTER : ARM_FEEDBACKER;
			// HudController.Update: fistBackground in the colour × 0.2; fistFill (Filled, Vertical, from the bottom)
			// to punchStamina / 2, × 0.6 while under one punch (a punch needs stamina ≥ 1 of 2: two punches per charge)
			float fill = Math.max(0, Math.min(1, wc.punchStamina / 2f));
			float k = wc.punchStamina >= 1 ? 1f : 0.6f;
			d.sprite(icon, ix0, iy0, ix1, iy1, r * 0.2f, g * 0.2f, b * 0.2f, 1);
			if (fill > 0) d.sprite(icon, ix0, iy0, ix1, iy0 + (iy1 - iy0) * fill, 0, 1 - fill, 1, 1, r * k, g * k, b * k, 1);
		}
	}

	private static float railFlash, lastDt;
	private static boolean railFlashed = true;

	/** The held railcannon's variation, else the first one in the inventory's; -1 without one. */
	private static int railcannonOwned(ClientPlayerEntity p) {
		if (WeaponClient.heldKind(p) == WeaponKind.RAILCANNON) return UKWeaponItem.variation(p.getMainHandStack());
		var inv = p.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (UKWeaponItem.kindOf(s) == WeaponKind.RAILCANNON) return UKWeaponItem.variation(s);
		}
		return -1;
	}

	/** GunPanel in its 200x100 space: the held weapon's icon in its variation colour (+ glow). */
	private static void gunPanel(ClientPlayerEntity p) {
		d.sliced(FILL_LARGE, 83, 37, PPU, 0, 0, 200, 100, 0, 0, 0, boxA());
		ItemStack held = p.getMainHandStack();
		WeaponKind kind = UKWeaponItem.kindOf(held);
		if (kind == null) return;
		int var = UKWeaponItem.variation(held);
		int rgb = UltraCraftClient.VARIATION_COLORS[var];
		float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
		// Filler 190x90, WeaponDescriptor icon with preserveAspect (the icons are wide; SingleRevolver is 700x358)
		boolean alt = UKWeaponItem.alternate(held);
		float aspect = iconAspect(kind.icon(var, alt));
		float ih = 90f, iw = Math.min(190f, ih * aspect);
		if (iw >= 190f) { iw = 190f; ih = iw / aspect; }
		float x0 = 100 - iw / 2, y0 = 50 - ih / 2;
		d.sprite(HudDraw.tex(kind.icon(var, alt)), x0, y0, x0 + iw, y0 + ih, r, g, b, 1);
		d.sprite(HudDraw.tex(kind.glowIcon(var, alt)), x0, y0, x0 + iw, y0 + ih, r, g, b, 0.749f);
	}

	private static final java.util.Map<String, Float> ASPECTS = new java.util.HashMap<>();

	/** width / height of a HUD icon texture (read once from the resource). */
	private static float iconAspect(String name) {
		return ASPECTS.computeIfAbsent(name, n -> {
			try (var in = MinecraftClient.getInstance().getResourceManager().getResourceOrThrow(HudDraw.tex(n)).getInputStream()) {
				var img = net.minecraft.client.texture.NativeImage.read(in);
				float a = (float) img.getWidth() / img.getHeight();
				img.close();
				return a;
			} catch (Exception ex) {
				return 700f / 358f;
			}
		});
	}

	/** RailcannonChargePanel: 46x100 right of the stats panel, a lightning bolt filled bottom-up by raicharge / 5. */
	private static void railcannonPanel(int variation) {
		// RailcannonChargePanel: anchored to StatsPanel's right middle (200, 23) at (47, 73.75), pivot (1, 0.5), 46x100:
		// x 201..247 like the FistPanel below it, y 46.75..146.75 like the GunPanel beside it
		float x1 = 200 + 47, x0 = x1 - 46, yc = 23 + 73.75f, y0 = yc - 50, y1 = yc + 50;
		d.sliced(FILL_LARGE, 83, 37, PPU, x0, y0, x1, y1, 0, 0, 0, boxA());
		Identifier bolt = HudDraw.tex("lightningboltbigvector");
		float bx0 = (x0 + x1) / 2 - 18, bx1 = bx0 + 36, by0 = yc - 45, by1 = yc + 45;
		d.sprite(bolt, bx0, by0, bx1, by1, 0, 0, 0, 0.678f);
		// RailcannonMeter.Update: fill raicharge / 4 in the charging colour (red); over 4 it is full in the charged colour
		// (here the stamina / punch blue) after a white flash fading at 1 per second
		float rc = ArsenalClient.INSTANCE.raicharge;
		boolean full = rc > 4;
		float charge = full ? 1 : Math.max(0, Math.min(1, rc / 4f));
		if (full && !railFlashed) { railFlashed = true; railFlash = 1; }
		if (!full) { railFlashed = false; railFlash = 0; }
		railFlash = Math.max(0, railFlash - lastDt);
		float r = full ? STAMINA[0] : 1, g = full ? STAMINA[1] : 0, b = full ? STAMINA[2] : 0;
		if (railFlash > 0) { r += (1 - r) * railFlash; g += (1 - g) * railFlash; b += (1 - b) * railFlash; }
		if (charge > 0) d.sprite(bolt, bx0, by0, bx1, by0 + 90 * charge, 0, 1 - charge, 1, 1, r, g, b, 1);
	}

	/** StyleCanvas: Panel (1) spans x -221..221, y -248..248. */
	private static void stylePanel(StyleState st) {
		float x0 = -221, x1 = 221, y0 = -248, y1 = 248;
		// the panels are see-through (live: hud.styleOuterAlpha / hud.styleInnerAlpha; the game's prefab stacks
		// 0.49 + 0.5 + 0.5 black, which read far darker here than in the game)
		float outerA = (float) Tune.get("hud.styleOuterAlpha"), innerA = boxA();
		if (outerA > 0) d.sliced(FILL_LARGE, 83, 37, PPU, x0, y0, x1, y1, 0, 0, 0, outerA);
		// Panel (2): rank, top 150
		float ry0 = y1 - 150;
		if (innerA > 0) d.sliced(FILL_LARGE, 83, 37, PPU, x0, ry0, x1, y1, 0, 0, 0, innerA);
		float shakeX = 0, shakeY = 0;
		if (st.rankShaking > 0) {
			shakeX = st.rankShaking * (rnd.nextInt(6) - 3);
			shakeY = st.rankShaking * (rnd.nextInt(6) - 3);
		}
		float scale = 1 + Math.max(0, st.rankScale);
		float rcx = shakeX, rcy = (ry0 + y1) / 2 + shakeY;
		float rw = (x1 - x0) * scale / 2, rh = 150 * scale / 2;
		d.sprite(HudDraw.tex(st.rank().sprite()), rcx - rw, rcy - rh, rcx + rw, rcy + rh, 1, 1, 1, 1);
		// Slider: centre y = ry0 + 75 - 93, 25 high
		float sy = ry0 + 75 - 93;
		d.sliced(FILL_LARGE, 83, 37, PPU, x0, sy - 12.5f, x1, sy + 12.5f, 0, 0, 0, 1);
		float frac = Math.max(0, Math.min(1, st.currentMeter / st.rank().maxMeter()));
		d.sliced(FILL_LARGE, 83, 37, PPU, x0, sy - 12.5f, x0 + 10 + (x1 - x0 - 10) * frac, sy + 12.5f, 1, 1, 1, 1);
		// Panel: entries + multiplier, y -243..63
		float py0 = -243, py1 = 63;
		if (innerA > 0) d.sliced(FILL_LARGE, 83, 37, PPU, x0, py0, x1, py1, 0, 0, 0, innerA);
		float lineH = 48;
		float ty = py1;
		int shown = 0;
		for (StyleState.Entry e : st.entries) {
			if (shown++ >= 5) break;
			// Panel/Text: 437 u wide, size 48, clipped to its box: a line too long for it is drawn smaller to fit
			float es = (float) Tune.get("hud.styleEntrySize");
			float ew = d.textWidth(e.text, es);
			if (ew > 437) es *= 437 / ew;
			d.text(e.text, -218.5f, ty - (48 - es) / 2, es, HudDraw.Align.LEFT, 0xFFFFFFFF);
			ty -= lineH;
		}
		if (st.airTime != 1f) {
			String s;
			int color = 0xFFFFFFFF;
			if (st.airTime >= 3f) { s = "x3.00"; color = 0xFFFF0000; }
			else if (st.airTime >= 2f) { s = String.format("x%.2f", st.airTime); color = 0xFFFFA500; }
			else s = String.format("x%.2f", st.airTime);
			float size = st.airTime >= 3f ? 72 : st.airTime >= 2f ? 60 : 48;
			float jit = st.airTime >= 3f ? 6 : st.airTime >= 2f ? 3 : 0;
			float jx = jit > 0 ? rnd.nextInt((int) jit * 2 + 1) - jit : 0, jy = jit > 0 ? rnd.nextInt((int) jit * 2 + 1) - jit : 0;
			d.text(s, x1 - 8 + jx, py0 + 10 + size + jy, size, HudDraw.Align.RIGHT, color);
			// Text (1)/Text: "MULTIPLIER", size 35, bottom left of the multiplier's strip
			float ls = (float) Tune.get("hud.multLabelSize");
			d.text("MULTIPLIER", x0 + 6, py0 + 10 + ls, ls, HudDraw.Align.LEFT, 0xFFFFFFFF);
		}
		// freshness bar below the panel: y -288..-248, four layered sliders, bold centred text
		if (st.currentWeapon != null) {
			float fy0 = py0 - 45, fy1 = fy0 + 40;
			// a black bar; inside, the current state's track: its background is the state below's colour and its own
			// colour builds up across the state's range
			float v = st.freshnessSliderValue;
			float W = x1 - x0;
			StyleState.Freshness f = st.freshnessState(st.currentWeapon);
			float ffrac = clamp01((v - f.min()) / Math.max(1e-3f, f.max() - f.min()));
			float[] black = {0.123f, 0.065f, 0.065f}, grey = {0.302f, 0.244f, 0.244f}, red = {1, 0, 0}, yellow = {1, 0.726f, 0};
			float[] bg = f == StyleState.DULL ? null : f == StyleState.STALE ? black : f == StyleState.USED ? grey : red;
			float[] fg = f == StyleState.DULL ? black : f == StyleState.STALE ? grey : f == StyleState.USED ? red : yellow;
			// the colours fill the whole bar (no frame): the state below's colour behind, this state's building up
			if (bg != null) d.sliced(FILL_LARGE, 83, 37, PPU, x0, fy0, x1, fy1, bg[0], bg[1], bg[2], 1);
			if (ffrac > 0) d.sliced(FILL_LARGE, 83, 37, PPU, x0, fy0, x0 + Math.max(20, ffrac * W), fy1, fg[0], fg[1], fg[2], 1);
			Text t = Text.literal(st.freshnessState(st.currentWeapon).text()).setStyle(StyleState.HUD_STYLE.withBold(true));
			// a little padding above and below (the prefab's TMP 40 fills the 40-unit bar)
			float fs = (float) Tune.get("hud.freshTextSize");
			d.text(t, 0, fy1 - 2 - (40 - fs) / 2, fs, HudDraw.Align.CENTER, 0xFFFFFFFF);
		}
	}

	private static void layer(float x0, float y0, float y1, float W, float frac, float r, float g, float b) {
		if (frac <= 0) return;
		// slider fill: 10 + frac * (W - 20) wide, 50 % of the height
		float h = (y1 - y0) * 0.25f, yc = (y0 + y1) / 2;
		d.sliced(FILL_LARGE, 83, 37, PPU, x0, yc - h, x0 + 10 + frac * (W - 20), yc + h, r, g, b, 1);
	}

	private static float clamp01(float v) {
		return Math.max(0, Math.min(1, v));
	}
}
