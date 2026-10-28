package com.ultracraft.client.v1;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTEfx;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UnderwaterController.EnterWater -> AudioMixerController.IsInWater: the mixers' "Lowpass Simple" (cutoff 1000 Hz) goes
 * from -80 dB to 0 dB wet, muffling every sound while the camera is under water. Here an OpenAL EFX low-pass filter on
 * every source (Minecraft's own and ours) does the same; its high-frequency gain (at OpenAL's 5 kHz reference) stands in
 * for the 1000 Hz cutoff (live: sound.underwaterHF).
 */
public final class UnderwaterAudio {
	private static final Set<Integer> sources = ConcurrentHashMap.newKeySet();
	private static volatile boolean muffled;
	private static int filter = -1;
	private static boolean failed;
	private static float lastHf = -1;

	static {
		Tune.def("sound.underwaterHF", 0.15, "under water: OpenAL low-pass high-frequency gain (from the game's 1000 Hz cutoff: about -16 dB at OpenAL's 5 kHz reference)");
		Tune.def("sound.underwaterMuffle", 1, "1 = sounds are muffled while the camera is under water (the game's mixer low-pass)");
	}

	private UnderwaterAudio() {}

	/** Every client tick: muffle or clear every live source when the camera goes under or comes out. */
	public static void tick() {
		boolean want = UnderwaterFx.submerged() && Tune.on("sound.underwaterMuffle");
		float hf = (float) Tune.get("sound.underwaterHF");
		if (want == muffled && (!want || hf == lastHf)) return;
		muffled = want;
		if (want) setup(hf);
		for (int p : sources) apply(p);
	}

	/** Source.play: a new or restarted sound takes the current state. */
	public static void started(int pointer) {
		sources.add(pointer);
		if (muffled || filter >= 0) apply(pointer);
	}

	public static void closed(int pointer) {
		sources.remove(pointer);
	}

	private static void setup(float hf) {
		if (failed) return;
		try {
			if (filter < 0) {
				filter = EXTEfx.alGenFilters();
				EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			}
			EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1f);
			EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Math.max(0f, Math.min(1f, hf)));
			lastHf = hf;
		} catch (Throwable t) {
			failed = true;
			filter = -1;
			UltraCraft.LOG.warn("underwater muffle off: no OpenAL EFX ({})", t.toString());
		}
	}

	private static void apply(int pointer) {
		if (failed || filter < 0) return;
		try {
			AL10.alSourcei(pointer, EXTEfx.AL_DIRECT_FILTER, muffled ? filter : EXTEfx.AL_FILTER_NULL);
		} catch (Throwable t) {
			sources.remove(pointer);
		}
	}
}
