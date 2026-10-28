package com.ultracraft.client.v1;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.CameraSubmersionType;

/**
 * UnderwaterController, with the camera in water: EnterWater plays its overlay's AudioSource (volume 0.25) looping
 * WaterUnderLoop, RemoveFromWater plays WaterSurfacing once; the UNDERWATER shader keyword and _UnderwaterOverlay (the
 * water's colour, default (0, 0.497, 1), at alpha 0.3) tint and sway what the game draws, the guns and arms among it.
 * The game's shaders are not readable here, so the sway is by eye (view.underwaterWobble*) and the tint a 0.3 blend of
 * the overlay colour into the view model's.
 */
public final class UnderwaterFx {
	private static boolean in;

	static {
		Tune.def("view.underwaterTint", 0.3, "view model tint toward the water colour under water (the game's overlay alpha 0.3)");
		Tune.def("view.underwaterWobble", 0.004, "view model wave amplitude under water, blocks of view space (by eye)");
		Tune.def("view.underwaterWobbleFreq", 2.5, "view model wave speed under water, radians a second (by eye)");
		Tune.def("view.underwaterWobbleWave", 12, "view model wave across the screen, radians per block of view space (by eye)");
	}

	private UnderwaterFx() {}

	public static boolean submerged() {
		MinecraftClient c = MinecraftClient.getInstance();
		return c.world != null && c.gameRenderer.getCamera().getSubmersionType() == CameraSubmersionType.WATER;
	}

	/** Per client tick: the underwater loop and the surfacing sound. */
	public static void tick() {
		boolean now = submerged();
		if (now && !in) UKSound.loop("uk.environment.waterunderloop", 0.25f, 1f);
		if (!now && in) {
			UKSound.stop("uk.environment.waterunderloop");
			UKSound.play("uk.environment.watersurfacing", 0.25f, 1f);
		}
		in = now;
	}

	/** The view model's look for this frame: tinted and swaying under water, plain otherwise. */
	public static void apply(RigInstance r) {
		if (r == null) return;
		if (!submerged()) {
			r.tintR = r.tintG = r.tintB = 1;
			r.wobbleAmp = 0;
			return;
		}
		float k = (float) Tune.get("view.underwaterTint");
		// (0, 0.497, 1) blended in at k, as a multiplier on the model's own colour
		r.tintR = 1 - k;
		r.tintG = 1 - k + k * 0.497f;
		r.tintB = 1;
		r.wobbleAmp = (float) Tune.get("view.underwaterWobble");
		r.wobbleFreq = (float) Tune.get("view.underwaterWobbleFreq");
		r.wobbleWave = (float) Tune.get("view.underwaterWobbleWave");
		r.wobbleTime = (float) com.ultracraft.client.uk.ViewModel.gameSeconds();
	}
}
