package com.ultracraft.client.v1;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvents;

/**
 * Movement sounds from the Player prefab (NewMovement): jumpSound Bluezone-Autobots-footstep-013,
 * landingSound Landing, dodgeSound Dodge3, finalWallJump Error. The ones that are prefabs with their own
 * AudioSource (StaminaFail, SlideStopSound, DashJumpSound, QuakeJump) keep vanilla stand-ins until those
 * prefabs are dumped.
 */
public final class V1Sounds {
	private V1Sounds() {}

	public static void jump(ClientPlayerEntity p, float pitch) {
		UKSound.play("uk.movement.bluezone-autobots-footstep-013", 1f, pitch);
	}

	/** NewMovement.CheckLanding: landingSound at 0.5 + fallSpeed × −0.01, pitch 0.9–1.1, then a surface footstep at 0.5. */
	public static void land(ClientPlayerEntity p, double volume) {
		UKSound.play("uk.movement.landing", (float) Math.min(1, volume), UKSound.range(0.9f, 1.1f));
		footstep(p, (float) Tune.get("snd.landStep"));
	}

	/** NewMovement.LandingImpact: the heavy landing, with a surface footstep at full volume. */
	public static void impact(ClientPlayerEntity p) {
		UKSound.play("uk.movement.landingheavy", 1f, 1f);
		footstep(p, (float) Tune.get("snd.impactStep"));
	}

	private static int lastFootstep = -1;

	static {
		Tune.def("snd.landStep", 0.5, "surface footstep volume on a normal landing (PlayerFootsteps.Footstep(0.5))");
		Tune.def("snd.impactStep", 1.0, "surface footstep volume on a hard landing (Footstep(1))");
		Tune.def("snd.walkStep", 0.25, "surface footstep volume while walking (PlayerFootsteps.Update: Footstep(0.25))");
		Tune.def("snd.wallStep", 0.5, "wall's surface footstep volume on a wall jump (PlayerFootsteps.WallJump)");
	}

	/**
	 * PlayerFootsteps.Footstep(volume, force, delay): landings call it twice (delay 0 and 0.05 s) on one AudioSource,
	 * so the second replaces the first and one step is heard 0.05 s (one tick) after touchdown. The clip is random
	 * from the surface's set, never the same index twice in a row, pitch 0.9–1.1.
	 */
	public static void footstep(ClientPlayerEntity p, float volume) {
		step(Surfaces.under(p), volume, 1);
		stepTimer = 1; // Footstep() resets the walking timer
	}

	private static double stepTimer;

	/**
	 * PlayerFootsteps.Update while walking (on ground with friction): the timer runs down at
	 * min(speed, 15) / 15 × 3 per second; at 0 a step at 0.25 plays and the timer goes back to 1
	 * (about every 0.33 s at full run speed). `speed` is the Rigidbody speed in game units per second.
	 */
	public static void walkTick(ClientPlayerEntity p, boolean walking, boolean onGround, double speed, double dt) {
		if (!walking) return;
		stepTimer = Math.max(0, stepTimer - Math.min(speed, 15) / 15 * dt * 3);
		if (stepTimer <= 0 && onGround) {
			stepTimer = 1;
			step(Surfaces.under(p), (float) Tune.get("snd.walkStep"), 0);
		}
	}

	/** One clip from the surface's set, never the same index twice in a row, pitch 0.9–1.1, after `delayTicks`. */
	private static void step(Surfaces surface, float volume, int delayTicks) {
		if (volume <= 0) return;
		String[] clips = surface.clips;
		int i = (int) (Math.random() * clips.length);
		if (clips.length > 1 && i == lastFootstep) i = (i + 1) % clips.length;
		lastFootstep = i;
		var c = net.minecraft.client.MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		var snd = net.minecraft.client.sound.PositionedSoundInstance.master(UKSound.event(clips[i]), UKSound.range(0.9f, 1.1f), volume);
		if (delayTicks > 0) c.getSoundManager().play(snd, delayTicks);
		else c.getSoundManager().play(snd);
	}

	public static void dodge(ClientPlayerEntity p) {
		UKSound.play("uk.movement.dodge3", 1f, 1f);
	}

	public static void dashJump(ClientPlayerEntity p) {
		UKSound.play("uk.movement.quickwhoosh", 0.8f, 1f);
	}

	/** NewMovement.staminaFailSound: the StaminaFail sound bubble ("G4F SFX11 - ExoInterface - Cursor Click 25"). */
	public static void staminaFail(ClientPlayerEntity p) {
		UKSound.play("uk.ui.g4f_sfx11_-_exointerface_-_cursor_click_25", 0.6f, 0.5f); // the bubble's AudioSource: volume 0.6, pitch 0.5
	}

	private static Surfaces slideSurface;
	private static String slideLayer;

	/**
	 * NewMovement.CreateSlideScrape: the slide particle the Player Footstep Set gives the surface under V1, each with its
	 * looping AudioSources - SlideScrape (generic, and any surface without its own) the "wallcling" scrape at 1; the others
	 * the scrape at 0.5 and their own loop on top: grass slide_Grass 0.5, crunchy/dirt/gravel/rubble slide_Gravel 0.4,
	 * carpet/sand slide_Carpet 0.35 at pitch 0.45, snow slide_Snow 0.5, wood slide_Wood 0.3, flesh slide_Flesh 0.4. Made
	 * again when the surface changes under the slide.
	 */
	public static void slideStart(ClientPlayerEntity p) {
		slideSurface = null;
		slideTick(p);
	}

	/** While sliding: a new surface under V1 swaps the scrape's layers. */
	public static void slideTick(ClientPlayerEntity p) {
		Surfaces s = Surfaces.under(p);
		if (s == slideSurface) return;
		slideSurface = s;
		String[] layer = switch (s) {
			case GRASS -> new String[] {"uk.movement.footsteps.slide_grass", "0.5", "1"};
			case CRUNCHY, DIRT, GRAVEL, RUBBLE -> new String[] {"uk.movement.footsteps.slide_gravel", "0.4", "1"};
			case CARPET, SAND -> new String[] {"uk.movement.footsteps.slide_carpet", "0.35", "0.45"};
			case SNOW -> new String[] {"uk.movement.footsteps.slide_snow", "0.5", "1"};
			case WOOD, WOOD_THIN -> new String[] {"uk.movement.footsteps.slide_wood", "0.3", "1"};
			case FLESH -> new String[] {"uk.movement.footsteps.slide_flesh", "0.4", "1"};
			default -> null;
		};
		UKSound.loop("uk.movement.wallcling", layer == null ? 1f : 0.5f, 1f);
		if (slideLayer != null && (layer == null || !slideLayer.equals(layer[0]))) UKSound.stop(slideLayer);
		slideLayer = layer == null ? null : layer[0];
		if (layer != null) UKSound.loop(layer[0], Float.parseFloat(layer[1]), Float.parseFloat(layer[2]));
	}

	/** SlideStopSound: the "Ricochet" clip at 0.5 volume, pitch 1.5. */
	public static void slideStop(ClientPlayerEntity p) {
		UKSound.stop("uk.movement.wallcling");
		if (slideLayer != null) UKSound.stop(slideLayer);
		slideLayer = null;
		slideSurface = null;
		UKSound.play("uk.weapons.revolver.ricochet", 0.5f, 1.5f);
	}

	private static boolean wallScraping;

	/**
	 * NewMovement.CreateWallScrape / DetachWallScrape: while V1 slides down a wall (Cling), the generic WallScrape loops -
	 * "wallcling" at 0.7 - on every wall. (The game's footstep sets put a surface loop on top for dirt, grass, wood and so
	 * on; left out: only the plain stone/concrete scrape, user.) `wallNormal` null: off the wall, the scrape stops.
	 */
	public static void wallScrape(ClientPlayerEntity p, net.minecraft.util.math.Vec3d wallNormal) {
		if (wallNormal == null) {
			if (!wallScraping) return;
			wallScraping = false;
			UKSound.stop("wallscrape");
			return;
		}
		if (wallScraping) return;
		wallScraping = true;
		UKSound.loopAs("wallscrape", "uk.movement.wallcling", 0.7f, 1f);
	}

	public static void wallJump(ClientPlayerEntity p, int count, net.minecraft.util.math.Vec3d wallNormal) {
		UKSound.play("uk.movement.bluezone-autobots-footstep-013", 1f, 1f);
		// PlayerFootsteps.WallJump(wallPoint): the wall's surface step at 0.5, at once
		step(Surfaces.wall(p, wallNormal), (float) Tune.get("snd.wallStep"), 0);
		if (count == 3) UKSound.play("uk.movement.error", 1f, 1f);
	}
}
