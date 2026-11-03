package com.ultracraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keeps UltraCraft's sounds under full scale (user, on the Ender Dragon: the sound cut out, then came back louder; recorded
 * at the speaker output, a nailgun on the dragon peaked at 2.1). A blast's pellets and a nail stream all land on something
 * that big, each with its blood sound. This is the mod's, not ULTRAKILL's.
 *
 * <p>Every playing sound counts as its volume x distance x ClipLevels at that moment. Minecraft's own sounds count but are
 * never changed. Over the limits,
 * the hit sounds go down first (sound.busHits, to sound.busHitFloor), the rest of ours only if the mix would still go
 * over full scale (to sound.busMainFloor). Down at once, back up over sound.busRelease seconds.
 *
 * <p>sound.voices is ULTRAKILL's real voice count (AudioManager.m_RealVoiceCount 64). Which voice gives way is ours, not
 * Unity's: by the game's AudioSource priorities the blood (100..150) outranks the weapons (128), and a dropped gunshot
 * would be another cut-out, so here the hits give way, quietest first.
 */
public final class SoundBus {
	static {
		Tune.def("sound.busHits", 0.708, "the mix level (1 = full scale) over which the hit sounds are turned down");
		Tune.def("sound.busHitFloor", 0.25, "the lowest gain of the hit sounds");
		Tune.def("sound.busMain", 1.0, "the mix level over which UltraCraft's other sounds are turned down too");
		Tune.def("sound.busMainFloor", 0.5, "the lowest gain of UltraCraft's other sounds");
		Tune.def("sound.busSame", 1.4, "how one clip's copies add up: 1 = fully in step, 2 = like different clips");
		Tune.def("sound.busRelease", 0.3, "s: how fast the sounds come back up");
		Tune.def("sound.voices", 64, "most UltraCraft sounds at once (ULTRAKILL's real voice count)");
	}

	/** a gain that drops by more than this reaches the playing sounds right away, not on the next tick */
	private static final float APPLY_NOW = 0.95f;

	private static final class Voice {
		final SoundInstance sound;
		final Sound clip;
		final long start = System.nanoTime();
		final boolean ours;
		final boolean hit;
		final double range;
		float base;

		Voice(SoundInstance sound, float base, boolean ours) {
			this.sound = sound;
			this.clip = sound.getSound();
			this.base = base;
			this.ours = ours;
			this.hit = ours && hit(sound.getId().getPath());
			this.range = Math.max(sound.getVolume(), 1f) * clip.getAttenuation();
		}

		/** Linear distance, as SoundSystem.play sets the source up. */
		double reach() {
			if (sound.getAttenuationType() != SoundInstance.AttenuationType.LINEAR) return 1;
			Vec3d at = new Vec3d(sound.getX(), sound.getY(), sound.getZ());
			double dist = sound.isRelative() ? at.length() : at.distanceTo(MinecraftClient.getInstance().gameRenderer.getCamera().getPos());
			return Math.max(0, 1 - dist / range);
		}

		double level(long now) {
			double seconds = (now - start) / 1e9 * Math.max(0.01f, sound.getPitch());
			return base * reach() * ClipLevels.at(clip, seconds, sound.isRepeatable());
		}
	}

	private static final Map<SoundInstance, Voice> voices = new IdentityHashMap<>();

	private static float hitGain = 1;
	private static float mainGain = 1;
	private static float appliedHit = 1;
	private static float appliedMain = 1;
	private static long lastTick;
	private static long lastLog;
	private static int skipped;
	private static double lastLevel;

	private SoundBus() {}

	/** WeaponClient.goreSound's sounds: one for every pellet and nail that lands. */
	private static boolean hit(String id) {
		return id.startsWith("uk.impacts.impacts_simple_003") || id.startsWith("uk.gore.headbreak2") || id.startsWith("uk.gore.melee_deep_wound_05___kopio");
	}

	private static void update(long now) {
		// one clip's copies add up partly in step (measured: 4 overlapping nailgun shots of 0.52 peak at 1.43), different clips by power
		double same = Tune.get("sound.busSame");
		Map<Sound, double[]> clips = new IdentityHashMap<>();
		for (Voice v : voices.values()) {
			double[] sum = clips.computeIfAbsent(v.clip, c -> new double[3]);
			sum[v.hit ? 0 : v.ours ? 1 : 2] += Math.pow(v.level(now), same);
		}
		double hits = 0, main = 0, others = 0;
		for (double[] sum : clips.values()) {
			hits += Math.pow(sum[0], 2 / same);
			main += Math.pow(sum[1], 2 / same);
			others += Math.pow(sum[2], 2 / same);
		}
		lastLevel = Math.sqrt(hits + main + others);
		double hitLimit = Tune.get("sound.busHits"), mainLimit = Tune.get("sound.busMain");
		float hitWant = hits <= 0 ? 1 : (float) Math.max(Tune.get("sound.busHitFloor"), Math.min(1, Math.sqrt(Math.max(0, hitLimit * hitLimit - main - others) / hits)));
		float mainWant = main <= 0 ? 1 : (float) Math.max(Tune.get("sound.busMainFloor"), Math.min(1, Math.sqrt(Math.max(0, mainLimit * mainLimit - hits * hitWant * hitWant - others) / main)));
		double dt = lastTick == 0 ? 0 : (now - lastTick) / 1e9;
		lastTick = now;
		float back = (float) (1 - Math.exp(-dt / Math.max(0.001, Tune.get("sound.busRelease"))));
		hitGain = hitWant < hitGain ? hitWant : hitGain + (hitWant - hitGain) * back;
		mainGain = mainWant < mainGain ? mainWant : mainGain + (mainWant - mainGain) * back;
	}

	/**
	 * SoundSystem.play, one of ours about to start: is there a voice for it? At sound.voices the quietest hit sound gives
	 * way (only to a louder one, if this is a hit too); with none playing, a sound that isn't a hit takes the quietest
	 * one-shot's place.
	 */
	public static synchronized boolean admit(SoundInstance sound, float base, Consumer<SoundInstance> stop) {
		int ours = 0;
		for (Voice v : voices.values()) if (v.ours) ours++;
		if (ours < (int) Tune.get("sound.voices")) return true;
		long now = System.nanoTime();
		Voice incoming = new Voice(sound, base, true);
		Voice worst = null;
		double worstLevel = Double.MAX_VALUE;
		boolean hits = voices.values().stream().anyMatch(v -> v.hit);
		for (Voice v : voices.values()) {
			if (!v.ours || v.sound.isRepeatable() || (hits && !v.hit)) continue;
			double l = v.level(now);
			if (l < worstLevel) { worst = v; worstLevel = l; }
		}
		if (incoming.hit && (!hits || worstLevel >= incoming.level(now))) {
			skipped++;
			return false;
		}
		if (worst != null) {
			voices.remove(worst.sound);
			stop.accept(worst.sound);
			skipped++;
		}
		return true;
	}

	/** SoundSystem.play: a sound starts at `base`, its volume before this bus. Returns its gain. */
	public static synchronized float started(SoundInstance sound, float base, boolean ours) {
		Voice v = new Voice(sound, base, ours);
		voices.put(sound, v);
		update(System.nanoTime());
		return ours ? (v.hit ? hitGain : mainGain) : 1;
	}

	/** Has a gain fallen so far that the playing sounds should follow now, not on the next tick? */
	public static synchronized boolean droppedFar() {
		return hitGain < appliedHit * APPLY_NOW || mainGain < appliedMain * APPLY_NOW;
	}

	/** The volume a playing sound of ours is at before this bus; returns its gain. */
	public static synchronized float playing(SoundInstance sound, float base) {
		Voice v = voices.get(sound);
		if (v == null) return mainGain;
		v.base = base;
		return v.hit ? hitGain : mainGain;
	}

	/** Ours, playing. */
	public static synchronized List<SoundInstance> ours() {
		appliedHit = hitGain;
		appliedMain = mainGain;
		List<SoundInstance> list = new ArrayList<>();
		for (Voice v : voices.values()) if (v.ours) list.add(v.sound);
		return list;
	}

	/** SoundSystem.tick. */
	public static synchronized void tick(Predicate<SoundInstance> playing) {
		voices.keySet().removeIf(s -> !playing.test(s));
		long now = System.nanoTime();
		update(now);
		if (!SoundLog.debug() || now - lastLog < 500_000_000L) return;
		if (hitGain > 0.9f && mainGain > 0.97f && skipped == 0) return;
		lastLog = now;
		com.ultracraft.UltraCraft.LOG.info("[sound] bus: level {}, hits {} dB, main {} dB, {} voices, {} given way",
			String.format("%.2f", lastLevel), String.format("%.1f", 20 * Math.log10(hitGain)), String.format("%.1f", 20 * Math.log10(mainGain)), voices.size(), skipped);
		skipped = 0;
	}
}
