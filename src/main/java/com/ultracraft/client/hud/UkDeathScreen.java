package com.ultracraft.client.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.GlStateManager;
import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ULTRAKILL's death (NewMovement.GetHurt at 0 hp → DeathSequence, then its BlackScreen), in place of Minecraft's death
 * screen. GetHurt frees the body's rotation and gives it a turn (rb.constraints = None, AddTorque(right * -1,
 * VelocityChange)): the body keeps flying and tips over onto the ground, the camera with it. For 2 s the HUD and
 * weapons are gone, the picture breaks up (the post effect's _Deathness / _Sharpness ramp 0 → 1: coarser and coarser
 * pixels, smeared rows, blood-red bars) and every game sound slows to a stop (TimeController pitch 1 → 0); the
 * DeathSequence clip plays (0.66) and its diagnostic log types in a line every 0.05 s (TextAppearByLines, top left,
 * bold, red with orange warnings; the warning beep for an orange line, the error click for the rest). Then
 * EndSequence: the BlackScreen (crtbg at 0.05 grey, the TVScreenBuzz loop) with "[YOU ARE DEAD]" and the prompt (one
 * best-fit text over the whole screen), the LaughingSkull (two frames swapping at 0.5 s, 8bitAhh at 0.5 on the second)
 * and the Flash: the red ISeeYou picture shutting like a TV going off (HudOpenEffect reversed, Y first, speed 30) with
 * TV Off. Left click or R respawns at any point, the sequence included (no grace time, as in the game). The
 * texts come from the player's own ULTRAKILL (uk/death.json, tools/extract/export_death.py).
 */
public final class UkDeathScreen extends Screen {
	private static final Identifier FONT = new Identifier(UltraCraft.MOD_ID, "vcr");
	private static final Identifier CRT = new Identifier(UltraCraft.MOD_ID, "textures/uk/crtbg.png");
	private static final Identifier SKULL_A = new Identifier(UltraCraft.MOD_ID, "textures/uk/skullframedoublea.png");
	private static final Identifier SKULL_B = new Identifier(UltraCraft.MOD_ID, "textures/uk/skullframedoubleb.png");
	private static final Identifier EYE = new Identifier(UltraCraft.MOD_ID, "textures/uk/iseeyou.png");
	private static final String ERROR = "uk.ui.csfx-2_mechanics_45", WARNING = "uk.ui.beep_buzz_clean_tight_mid_x3";
	private static final String BUZZ = "uk.unused.tvscreenbuzz";
	private static final float SEQUENCE = 2f, SKULL_LOOP = 1.0166667f;
	/** The VCR font's glyph size (font/vcr.json: ttf size 32). */
	private static final float FONT_PX = 32f;
	/** VCR OSD Mono's line height per font size (TMP face: 145.5 at 149; the UI font: line spacing 15.625 at 16). */
	private static final float LINE = 145.5078125f / 149f;
	/** TMP's "orange" (the log's warnings) and the log's own colour (m_fontColor 1, 0, 0). */
	private static final int ORANGE = 0xFFFF8000, RED = 0xFFFF0000;
	/** The body's radius (the head lies this high once it is down). */
	private static final double RADIUS = 0.3;

	static {
		Tune.def("death.ukScreen", 1, "1 = ULTRAKILL's death sequence and black screen instead of Minecraft's death screen (not in hardcore)");
		Tune.def("death.clashRespawn", 1, "1 = dying in clash mode (third person) respawns at once, as ULTRAKILL's platformer does (DeathOver)");
		Tune.def("death.torque", 1.0, "the turn the body gets on death, rad/s about the world X axis (GetHurt: AddTorque(right * -1, VelocityChange))");
		Tune.def("death.throw", 12, "blocks/s the killing hit throws the body away from where it came from");
		Tune.def("death.throwUp", 6, "blocks/s up with that throw");
		Tune.def("death.throwTip", 1.5, "rad/s the throw tips the body over away from the hit (the camera falls back from a hit in front)");
		Tune.def("death.gravity", 32, "blocks/s^2 pulling the body down and over (Minecraft's 0.08 blocks/tick^2)");
		Tune.def("death.debug", 0, "1 = log the fall (feet, body, eye, view) every frame");
		Tune.def("death.textSize", 1.0, "size of the diagnostic log's lines (x the game's 16 at a 1280 x 720 canvas)");
		Tune.def("death.glitch", 1.0, "strength of the pixels, smears and blood bars over the 2 s");
		Tune.def("death.pixels", 48, "how coarse the picture gets at the end: the screen height in pixels / this (0 = off)");
		Tune.def("death.deadSize", 1.0, "size of \"[YOU ARE DEAD]\" and the prompt (x the game's best fit over the screen)");
	}

	private static List<String> lines;
	private static List<String> deadLines;
	private static float lineDelay = 0.05f;
	private static float lineSize = 16;

	/** The death screen showing now (for the camera and the sound slow-down), or null. */
	private static UkDeathScreen current;

	private final long startNanos = System.nanoTime();
	private long blackNanos = -1;
	private int shownLines;
	private int laughs;
	private boolean hudWas, paused, respawning;
	private net.minecraft.client.network.ClientPlayerEntity deadPlayer;
	private final Random rnd = new Random();
	private SimpleFramebuffer small;

	// the body (a rod standing on its feet): where the feet are, how fast they go, how it is turned
	private Vec3d feet;
	private Vector3d vel = new Vector3d();
	private final Quaterniond turn = new Quaterniond();
	private final Vector3d spin = new Vector3d();
	private double eyeHeight = 1.62;
	private boolean grounded, bounced;
	private long simNanos = -1, debugNanos;
	private Vec3d eye;

	public UkDeathScreen() {
		super(Text.translatable("deathScreen.title"));
	}

	/** ScreenEvent.Opening: Minecraft's death screen becomes ours, or (clash mode) an instant respawn. */
	public static void onScreenOpening(net.minecraftforge.client.event.ScreenEvent.Opening e) {
		if (!(e.getNewScreen() instanceof net.minecraft.client.gui.screen.DeathScreen)) return;
		// (already showing ours: Minecraft re-opens its death screen when a screen closes while the player is dead)
		if (current != null) {
			e.setCanceled(true);
			return;
		}
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.player == null || c.world == null || c.world.getLevelProperties().isHardcore()) return;
		if (Tune.on("death.clashRespawn") && !c.options.getPerspective().isFirstPerson()) {
			// PlatformerMovement: no death screen; DeathOver → Respawn at the checkpoint (here: the spawn point / bed)
			e.setCanceled(true);
			c.player.requestRespawn();
			return;
		}
		if (Tune.on("death.ukScreen")) e.setNewScreen(new UkDeathScreen());
	}

	/** Minecraft draws its HUD under an open screen: none of it (chat, hotbar ...) while this one shows (screenHud off). */
	public static void onOverlay(net.minecraftforge.client.event.RenderGuiOverlayEvent.Pre e) {
		if (current != null) e.setCanceled(true);
	}

	/** Seconds since death, while this screen shows; -1 otherwise. */
	public static float elapsed() {
		return current == null ? -1 : (System.nanoTime() - current.startNanos) / 1e9f;
	}

	/** TimeController.SetAllPitch over the sequence: 1 → 0 in 2 s, then 0 (the game's sounds stop). */
	public static float worldPitch() {
		float t = elapsed();
		if (t < 0) return 1;
		return current.blackNanos >= 0 ? 0 : MathHelper.clamp(1 - t / SEQUENCE, 0, 1);
	}

	/** Our own death screen sounds keep their pitch. */
	public static boolean ownSound(net.minecraft.client.sound.SoundInstance s) {
		String p = s.getId().getPath();
		return p.startsWith("uk.ui.") || p.startsWith("uk.unused.");
	}

	/** Camera.update (first person): the camera sits in the falling body's head; null when not dying. */
	public static Vec3d cameraPos() {
		if (current == null || current.feet == null) return null;
		current.simulate();
		return current.eye;
	}

	/** ViewportEvent.ComputeCameraAngles: the camera turns with the body as it tips over. */
	public static void onCameraAngles(net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles e) {
		if (current == null || current.feet == null) return;
		// the view, as Minecraft builds it (Rz(roll) Rx(pitch) Ry(yaw + 180)), turned by the body: camera to world
		// C = B * Ry(-(yaw + 180)) Rx(-pitch) Rz(-roll), read back as yaw / pitch / roll
		double[][] c = mul(rotY(-Math.toRadians(e.getYaw() + 180)), mul(rotX(-Math.toRadians(e.getPitch())), rotZ(-Math.toRadians(e.getRoll()))));
		Matrix3d bm = new Matrix3d().set(current.turn);
		double[][] b = new double[3][3];
		for (int r = 0; r < 3; r++) for (int col = 0; col < 3; col++) b[r][col] = bm.get(col, r);
		double[][] m = mul(b, c);
		double pitch = Math.asin(MathHelper.clamp(-m[1][2], -1, 1));
		double yaw = Math.atan2(m[0][2], m[2][2]);
		double roll = Math.atan2(m[1][0], m[1][1]);
		if (Tune.on("death.debug") && current.debugNanos != current.simNanos) {
			current.debugNanos = current.simNanos;
			Vector3d up = current.turn.transform(new Vector3d(0, 1, 0));
			UltraCraft.LOG.info("death t {} feet {} up {} eye {} grounded {} view {}/{}/{} -> {}/{}/{}", String.format("%.2f", elapsed()), current.feet, up, current.eye, current.grounded,
				e.getYaw(), e.getPitch(), e.getRoll(), -Math.toDegrees(yaw) - 180, -Math.toDegrees(pitch), -Math.toDegrees(roll));
		}
		e.setYaw((float) (-Math.toDegrees(yaw) - 180));
		e.setPitch((float) -Math.toDegrees(pitch));
		e.setRoll((float) -Math.toDegrees(roll));
	}

	/** The fall: the feet fly on and land (gravity, block collisions, ground friction); the body tips over around them. */
	private void simulate() {
		long now = System.nanoTime();
		if (simNanos < 0 || blackNanos >= 0) {
			simNanos = now;
			if (eye == null) eye = eyeAt();
			return;
		}
		double dt = Math.min(0.1, (now - simNanos) / 1e9);
		simNanos = now;
		double g = Tune.get("death.gravity");
		var world = client.world;
		while (dt > 1e-6) {
			double h = Math.min(dt, 0.004);
			dt -= h;
			// the feet
			if (!grounded) vel.y -= g * h;
			else {
				double f = Math.exp(-6 * h);
				vel.x *= f;
				vel.z *= f;
			}
			Vec3d next = feet.add(vel.x * h, vel.y * h, vel.z * h);
			Vec3d from = feet.add(0, 0.05, 0), to = next.add(0, 0.05, 0);
			BlockHitResult hit = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, deadPlayer));
			if (hit.getType() == HitResult.Type.MISS) {
				feet = next;
				// (sliding off an edge: no ground right under the feet any more)
				if (grounded) grounded = world.raycast(new RaycastContext(feet.add(0, 0.05, 0), feet.add(0, -0.06, 0),
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, deadPlayer)).getType() != HitResult.Type.MISS;
			} else {
				Vec3d n = Vec3d.of(hit.getSide().getVector());
				feet = hit.getPos().add(n.multiply(0.01)).subtract(0, 0.05, 0);
				double into = vel.x * n.x + vel.y * n.y + vel.z * n.z;
				vel.sub(n.x * into, n.y * into, n.z * into);
				if (n.y > 0.5) grounded = true;
			}
			// the body: gravity pulls it further over (a rod on its end: 3g / 2L * sin), until it lies on the ground
			Vector3d up = turn.transform(new Vector3d(0, 1, 0));
			double k = 3 * g / (2 * eyeHeight);
			spin.add(new Vector3d(0, 1, 0).cross(up, new Vector3d()).mul(k * h));
			double along = spin.dot(up);
			spin.sub(new Vector3d(up).mul(along));
			double w = spin.length();
			if (w > 1e-9) turn.premul(new Quaterniond().rotationAxis(w * h, spin.x / w, spin.y / w, spin.z / w));
			turn.normalize();
			up = turn.transform(new Vector3d(0, 1, 0));
			if (up.y < 0) {
				// down: the head on the ground (a small bounce the first time)
				Vector3d flat = new Vector3d(up.x, 0, up.z);
				if (flat.lengthSquared() < 1e-9) flat.set(1, 0, 0);
				flat.normalize();
				turn.premul(new Quaterniond().rotationTo(up, flat));
				spin.mul(bounced ? 0 : -0.2);
				bounced = true;
			}
		}
		eye = eyeAt();
	}

	/** The head: up the tipped body from the feet, kept out of blocks. */
	private Vec3d eyeAt() {
		Vector3d up = turn.transform(new Vector3d(0, 1, 0));
		Vec3d base = feet.add(0, RADIUS, 0);
		Vec3d head = base.add(up.x * (eyeHeight - RADIUS), up.y * (eyeHeight - RADIUS), up.z * (eyeHeight - RADIUS));
		BlockHitResult hit = client.world.raycast(new RaycastContext(base, head, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, deadPlayer));
		if (hit.getType() == HitResult.Type.MISS) return head;
		Vec3d d = head.subtract(base);
		double len = d.length();
		double keep = Math.max(0, hit.getPos().distanceTo(base) - 0.15);
		return len < 1e-6 ? base : base.add(d.multiply(keep / len));
	}

	/** GetHurt at 0 hp: the body keeps its speed, the killing hit throws it away from where it came from, it starts to turn. */
	private void startFall() {
		var p = client.player;
		feet = p.getPos();
		eyeHeight = p.getStandingEyeHeight();
		Vec3d v = p.getVelocity().multiply(20);
		vel.set(v.x, Math.max(0, v.y), v.z);
		grounded = p.isOnGround();
		spin.set(-Tune.get("death.torque"), 0, 0);
		var src = p.getRecentDamageSource();
		Vec3d from = src == null ? null : src.getPosition();
		if (from != null) {
			Vec3d away = feet.subtract(from).multiply(1, 0, 1);
			if (away.lengthSquared() > 1e-4) {
				away = away.normalize();
				vel.add(away.x * Tune.get("death.throw"), Tune.get("death.throwUp"), away.z * Tune.get("death.throw"));
				if (Tune.get("death.throwUp") > 0) grounded = false;
				// tips over away from the hit: the turn that moves "up" towards "away"
				spin.add(new Vector3d(0, 1, 0).cross(new Vector3d(away.x, 0, away.z), new Vector3d()).mul(Tune.get("death.throwTip")));
			}
		}
	}

	private static double[][] rotX(double a) {
		double c = Math.cos(a), s = Math.sin(a);
		return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
	}

	private static double[][] rotY(double a) {
		double c = Math.cos(a), s = Math.sin(a);
		return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
	}

	private static double[][] rotZ(double a) {
		double c = Math.cos(a), s = Math.sin(a);
		return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
	}

	private static double[][] mul(double[][] a, double[][] b) {
		double[][] r = new double[3][3];
		for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
		return r;
	}

	private static void load() {
		if (lines != null) return;
		lines = new ArrayList<>();
		deadLines = new ArrayList<>();
		try {
			var res = MinecraftClient.getInstance().getResourceManager().getResource(new Identifier(UltraCraft.MOD_ID, "uk/death.json"));
			if (res.isPresent()) {
				try (var in = new InputStreamReader(res.get().getInputStream(), StandardCharsets.UTF_8)) {
					JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
					o.getAsJsonArray("lines").forEach(l -> lines.add(l.getAsString()));
					if (o.has("deadLines")) o.getAsJsonArray("deadLines").forEach(l -> deadLines.add(l.getAsString()));
					else if (o.has("deadText")) deadLines.add(o.get("deadText").getAsString());
					if (o.has("lineDelay")) lineDelay = o.get("lineDelay").getAsFloat();
					if (o.has("linesFontSize")) lineSize = o.get("linesFontSize").getAsFloat();
				}
			}
		} catch (Exception ex) {
			UltraCraft.LOG.warn("death screen texts not loaded: {}", ex.toString());
		}
		if (deadLines.isEmpty()) deadLines.add("[YOU ARE DEAD]");
	}

	@Override
	protected void init() {
		load();
		if (current != this) {
			current = this;
			deadPlayer = client.player;
			startFall();
			hudWas = client.options.hudHidden;
			client.options.hudHidden = true; // screenHud.SetActive(false); gunc.NoWeapon(); punch.NoFist()
			UKSound.once("uk.ui.deathsequence", 0.66f, 1f);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	/** Left click respawns at once, during the sequence too (no grace time, as in the game). */
	@Override
	public boolean mouseClicked(double x, double y, int button) {
		if (button == 0) respawn();
		return true;
	}

	/** R (the game's restart key) respawns as well. */
	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_R) {
			respawn();
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	private void respawn() {
		if (respawning || client.player == null) return;
		respawning = true;
		client.player.requestRespawn();
	}

	/** Minecraft closes only its own DeathScreen on respawn: this one closes once the new player is alive. */
	@Override
	public void tick() {
		// (a respawn makes a new player entity)
		if (client.player != null && client.player != deadPlayer && client.player.isAlive()) client.setScreen(null);
	}

	@Override
	public void removed() {
		if (current == this) {
			current = null;
			client.options.hudHidden = hudWas;
		}
		if (small != null) {
			small.delete();
			small = null;
		}
		UKSound.stop(BUZZ);
		UKSound.stop("uk.ui.deathsequence");
		UKSound.stop(ERROR);
		UKSound.stop(WARNING);
		if (paused) client.getSoundManager().resumeAll();
		paused = false;
	}

	/** DeathSequence.EndSequence: the sequence's sound and text stop, every game sound stops, the BlackScreen comes on. */
	private void toBlack() {
		blackNanos = System.nanoTime();
		UKSound.stop("uk.ui.deathsequence");
		UKSound.stop(ERROR);
		UKSound.stop(WARNING);
		// (pitch 0 freezes every game sound where it is: paused, resumed on respawn)
		client.getSoundManager().pauseAll();
		paused = true;
		UKSound.play("uk.ui.tv_off", 1f, 1f); // the Flash's and ISeeYou's AudioSources, both TV Off on awake
		UKSound.play("uk.ui.tv_off", 1f, 1f);
		UKSound.loop(BUZZ, 1f, 1f);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		float t = (System.nanoTime() - startNanos) / 1e9f;
		if (blackNanos < 0 && t >= SEQUENCE) toBlack();
		int w = width, h = height;
		if (blackNanos < 0) renderSequence(ctx, t, w, h);
		else renderBlack(ctx, (System.nanoTime() - blackNanos) / 1e9f, w, h);
	}

	/** The canvas's scale (CanvasScaler: 1280 x 720, expand) in GUI pixels per canvas unit. */
	private static float canvas(int w, int h) {
		return Math.min(w / 1280f, h / 720f);
	}

	/** The 2 s: the picture breaking up, the log typing in. */
	private void renderSequence(DrawContext ctx, float t, int w, int h) {
		float g = (float) Tune.get("death.glitch");
		float k = MathHelper.clamp(t / SEQUENCE, 0, 1) * g;
		breakUp(ctx, k);
		// the log: a new line every lineDelay, each with its beep
		int want = Math.min(lines.size(), (int) (t / lineDelay) + 1);
		while (shownLines < want) {
			String l = lines.get(shownLines++);
			boolean warn = l.startsWith("<");
			UKSound.stop(ERROR);
			UKSound.stop(WARNING);
			UKSound.once(warn ? WARNING : ERROR, 0.75f, 1f);
		}
		// TMP: 16 bold, top left in the canvas inset by 50, running on down (overflow) without scrolling
		float s = canvas(w, h);
		float px = (float) (s * lineSize * Tune.get("death.textSize"));
		float scale = px / FONT_PX;
		float lineH = px * LINE;
		float left = 50 * s, top = 50 * s;
		for (int i = 0; i < shownLines; i++) {
			String raw = lines.get(i);
			boolean orange = raw.contains("<color=orange>");
			drawText(ctx, raw.replaceAll("<[^>]*>", ""), left, top + i * lineH, scale, orange ? ORANGE : RED, true, false);
		}
		// blood-red bars over everything, more of them towards the end
		int bars = (int) (k * k * 14);
		for (int i = 0; i < bars; i++) {
			if (rnd.nextFloat() > 0.6f) continue;
			int y = rnd.nextInt(Math.max(1, h)), bh = Math.max(1, h / 100) + rnd.nextInt(Math.max(1, (int) (h * 0.06f)));
			int x = rnd.nextFloat() < 0.4f ? 0 : rnd.nextInt(Math.max(1, w));
			int x2 = rnd.nextFloat() < 0.7f ? w : x + rnd.nextInt(Math.max(1, w / 3));
			for (int yy = y; yy < y + bh; yy++) {
				float v = 0.75f + rnd.nextFloat() * 0.35f;
				int xs = x + rnd.nextInt(Math.max(1, w / 40));
				ctx.fill(xs, yy, x2, yy + 1, argb(0.92f, 0.55f * v, 0.05f * v, 0.04f * v));
			}
		}
	}

	/**
	 * _Sharpness / _Deathness on the world picture: coarser pixels as it goes (the screen copied down to a small
	 * picture and back up without smoothing) and rows smeared out to the right.
	 */
	private void breakUp(DrawContext ctx, float k) {
		ctx.draw();
		Framebuffer main = client.getFramebuffer();
		int fw = main.textureWidth, fh = main.textureHeight;
		double div = Tune.get("death.pixels");
		int block = div <= 0 ? 1 : Math.max(1, Math.round(1 + k * (float) (fh / div)));
		int sw = Math.max(1, fw / block), sh = Math.max(1, fh / block);
		if (small == null) small = new SimpleFramebuffer(sw, sh, false, MinecraftClient.IS_SYSTEM_MAC);
		else if (small.textureWidth != sw || small.textureHeight != sh) small.resize(sw, sh, MinecraftClient.IS_SYSTEM_MAC);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, small.fbo);
		GlStateManager._glBlitFrameBuffer(0, 0, fw, fh, 0, 0, sw, sh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, small.fbo);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main.fbo);
		GlStateManager._glBlitFrameBuffer(0, 0, sw, sh, 0, 0, fw, fh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		// smears: a few columns of a band stretched over the rest of the row
		int smears = (int) (k * 18);
		for (int i = 0; i < smears; i++) {
			if (rnd.nextFloat() > 0.5f) continue;
			int y0 = rnd.nextInt(sh), bh = 1 + rnd.nextInt(Math.max(1, sh / 25));
			int y1 = Math.min(sh, y0 + bh);
			int x0 = rnd.nextInt(sw), span = 1 + rnd.nextInt(Math.max(1, sw / 60));
			int x1 = Math.min(sw, x0 + span);
			int dx0 = x0 * fw / sw;
			int dx1 = rnd.nextFloat() < 0.7f ? fw : Math.min(fw, dx0 + rnd.nextInt(Math.max(1, fw / 3)) + (x1 - x0) * fw / sw);
			GlStateManager._glBlitFrameBuffer(x0, y0, x1, y1, dx0, y0 * fh / sh, dx1, y1 * fh / sh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		}
		main.beginWrite(true);
	}

	/** The BlackScreen: crtbg, the laughing skull, the texts, and the Flash shutting on top. */
	private void renderBlack(DrawContext ctx, float t, int w, int h) {
		ctx.fill(0, 0, w, h, 0xFF000000);
		// crtbg at 0.05 grey
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		ctx.setShaderColor(0.05f, 0.05f, 0.05f, 1f);
		ctx.drawTexture(CRT, 0, 0, w, h, 0, 0, 1281, 720, 1281, 720);
		ctx.setShaderColor(1f, 1f, 1f, 1f);
		// the skull: anchors y 0.25..0.75 (half the screen tall), square, frames A / B swapping at 0.5 s
		float cyc = t % SKULL_LOOP;
		int loops = (int) (t / SKULL_LOOP) + (cyc >= 0.5f ? 1 : 0);
		while (laughs < loops) {
			laughs++;
			UKSound.play("uk.ui.8bitahh", 0.5f, 1f); // LaughingSkull.PlayAudio, the clip's event at 0.5 s
		}
		int size = h / 2, sx = (w - size) / 2, sy = h / 4;
		ctx.drawTexture(cyc < 0.5f ? SKULL_A : SKULL_B, sx, sy, size, size, 0, 0, 1637, 1636, 1637, 1636);
		renderDeadText(ctx, w, h);
		// the Flash: 1280 x 720 at the centre, shrinking in Y first (MoveTowards at (|d| + 0.1) x 30 a second)
		float fy = (float) (1.1 * Math.exp(-30 * t) - 0.1);
		if (fy > 0) {
			int fh = (int) (h * fy), fyTop = (h - fh) / 2;
			ctx.enableScissor(0, fyTop, w, fyTop + fh);
			ctx.drawTexture(EYE, 0, 0, w, h, 0, 0, 480, 270, 480, 270);
			ctx.disableScissor();
		}
	}

	/**
	 * YouDiedText: one Text over the whole canvas (minus 20 top and bottom), middle centre, Best Fit (10 .. 200): the
	 * biggest size where its widest line fits across and all its lines fit down. The game's last line, "Press [R] TO
	 * RESTART", says RESPAWN (a longer line would shrink on its own to fit across).
	 */
	private void renderDeadText(DrawContext ctx, int w, int h) {
		float s = canvas(w, h);
		float cw = w / s, ch = h / s - 40;
		int n = deadLines.size();
		float widest = 0;
		for (String l : deadLines) widest = Math.max(widest, width(l));
		float fit = Math.min(ch / (n * LINE), widest > 0 ? cw / widest : 200);
		float size = (float) (MathHelper.clamp((float) Math.floor(fit), 10, 200) * Tune.get("death.deadSize"));
		float lineH = size * LINE;
		float top = h / s / 2 - n * lineH / 2;
		for (int i = 0; i < n; i++) {
			String l = deadLines.get(i);
			if (l.isEmpty()) continue;
			float ls = size;
			if (i == n - 1 && n > 1) {
				l = l.replace("RESTART", "RESPAWN");
				ls = Math.min(size, (float) Math.floor(cw * 0.96f / width(l)));
			}
			// (a smaller line sits on the same baseline)
			float y = top + i * lineH + (size - ls) * 0.88f;
			drawText(ctx, l, w / 2f, y * s, ls * s / FONT_PX, 0xFFFFFFFF, false, true);
		}
	}

	/** A line's width per unit of font size. */
	private float width(String s) {
		return textRenderer.getTextHandler().getWidth(Text.literal(s).setStyle(Style.EMPTY.withFont(FONT))) / FONT_PX;
	}

	/**
	 * Minecraft's TTF glyphs count a glyph's left side bearing twice (as the glyph's bearing and again in its bitmap),
	 * which only shows where a glyph's bearing differs from the letters' ("[" sits 300 font units further in than "Y"
	 * and lands on it). Each character is drawn on its own, pulled back by its bearing, so the ink lands where the font
	 * puts it (the game's TMP / UI text).
	 */
	private void drawText(DrawContext ctx, String s, float x, float y, float scale, int color, boolean bold, boolean centred) {
		Style plain = Style.EMPTY.withFont(FONT), style = plain.withBold(bold);
		// (exact widths: TextRenderer.getWidth rounds each glyph's advance to a whole unit, 20 letters add up)
		var handler = textRenderer.getTextHandler();
		float tw = handler.getWidth(Text.literal(s).setStyle(style));
		ctx.getMatrices().push();
		ctx.getMatrices().translate(x, y, 0);
		ctx.getMatrices().scale(scale, scale, 1);
		float pen = centred ? -tw / 2f : 0;
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			Text one = Text.literal(String.valueOf(ch)).setStyle(style);
			float advance = handler.getWidth(one);
			if (ch != ' ') {
				float back = bearing(ch) * handler.getWidth(Text.literal(String.valueOf(ch)).setStyle(plain));
				ctx.getMatrices().push();
				ctx.getMatrices().translate(pen - back, 0, 0);
				ctx.drawText(textRenderer, one, 0, 0, color, false);
				ctx.getMatrices().pop();
			}
			pen += advance;
		}
		ctx.getMatrices().pop();
	}

	/** Each ASCII glyph's left side bearing per its advance, from the font file (stb_truetype, as Minecraft reads it). */
	private static float[] bearings;

	private static float bearing(char c) {
		if (bearings == null) {
			bearings = new float[128];
			try {
				var res = MinecraftClient.getInstance().getResourceManager().getResource(new Identifier(UltraCraft.MOD_ID, "font/vcr_osd_mono.ttf"));
				if (res.isPresent()) {
					byte[] bytes;
					try (var in = res.get().getInputStream()) {
						bytes = in.readAllBytes();
					}
					java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
					try (var info = org.lwjgl.stb.STBTTFontinfo.malloc(); var stack = org.lwjgl.system.MemoryStack.stackPush()) {
						if (org.lwjgl.stb.STBTruetype.stbtt_InitFont(info, buf)) {
							var adv = stack.mallocInt(1);
							var lsb = stack.mallocInt(1);
							for (int cp = 33; cp < 127; cp++) {
								org.lwjgl.stb.STBTruetype.stbtt_GetCodepointHMetrics(info, cp, adv, lsb);
								if (adv.get(0) > 0) bearings[cp] = lsb.get(0) / (float) adv.get(0);
							}
						}
					} finally {
						org.lwjgl.system.MemoryUtil.memFree(buf);
					}
				}
			} catch (Exception ex) {
				UltraCraft.LOG.warn("death screen font metrics not read: {}", ex.toString());
			}
		}
		return c < 128 ? bearings[c] : 0;
	}

	private static int argb(float a, float r, float g, float b) {
		return (MathHelper.clamp((int) (a * 255), 0, 255) << 24) | (MathHelper.clamp((int) (r * 255), 0, 255) << 16)
			| (MathHelper.clamp((int) (g * 255), 0, 255) << 8) | MathHelper.clamp((int) (b * 255), 0, 255);
	}
}
