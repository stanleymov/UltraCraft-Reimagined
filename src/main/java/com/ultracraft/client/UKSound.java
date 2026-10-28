package com.ultracraft.client;

import com.ultracraft.UltraCraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.Map;

/**
 * ULTRAKILL's clips, played client side by their sounds.json event name (`uk.<folder>.<clip>`, see
 * tools/extract/build_assets.py). The events are not in the registry: the sound engine looks them up
 * by id, which is all a local sound needs.
 */
public final class UKSound {
	private static final Map<String, SoundEvent> events = new HashMap<>();
	private static final Map<String, Loop> loops = new HashMap<>();

	private UKSound() {}

	public static SoundEvent event(String name) {
		return events.computeIfAbsent(name, n -> SoundEvent.of(new Identifier(UltraCraft.MOD_ID, n)));
	}

	/**
	 * Minecraft caps pitch at 2: above it, the clip's octave-up copy (`<name>_x2`, made by build_assets for the clips
	 * the game plays that high) at half the pitch. Returns the name to play; `pitch[0]` is adjusted in place.
	 */
	/**
	 * The mono copy of a stereo clip (build_assets: <stem>_m), for sounds with a place in the world: Minecraft places only
	 * mono sounds, a stereo one played at the listener wherever it started (the rocket's thruster followed V1).
	 */
	private static String mono(String name) {
		MinecraftClient c = MinecraftClient.getInstance();
		String m = name + "_m";
		if (c.getSoundManager() == null || c.getSoundManager().get(new Identifier(UltraCraft.MOD_ID, m)) == null) return name;
		return m;
	}

	private static String high(String name, float[] pitch) {
		if (pitch[0] <= 2f) return name;
		MinecraftClient c = MinecraftClient.getInstance();
		String hi = name + "_x2";
		if (c.getSoundManager() == null || c.getSoundManager().get(new Identifier(UltraCraft.MOD_ID, hi)) == null) return name;
		pitch[0] /= 2f;
		return hi;
	}

	/** Non-positional, like ULTRAKILL's 2D weapon AudioSources. */
	public static void play(String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		float[] pt = {pitch};
		String n = high(name, pt);
		c.getSoundManager().play(PositionedSoundInstance.master(event(n), pt[0], volume));
	}

	private record Due(long at, String name, float volume, float pitch) {}

	private static final java.util.List<Due> due = new java.util.ArrayList<>();

	/** play() `delay` seconds from now (a shot on a later fixed step of this tick), sent from the render loop by pump(). */
	public static void playAfter(String name, float volume, float pitch, float delay) {
		if (delay <= 0) { play(name, volume, pitch); return; }
		due.add(new Due(System.nanoTime() + (long) (delay * 1e9), name, volume, pitch));
	}

	/** Every frame: the delayed sounds whose time has come. */
	public static void pump() {
		if (due.isEmpty()) return;
		long now = System.nanoTime();
		for (java.util.Iterator<Due> it = due.iterator(); it.hasNext();) {
			Due d = it.next();
			if (d.at() <= now) { it.remove(); play(d.name(), d.volume(), d.pitch()); }
		}
	}

	public static void play(String name, float volume) {
		play(name, volume, 1f);
	}

	/** Positional, in the world. */
	public static void at(Vec3d pos, String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		float[] pt = {pitch};
		String n = mono(high(name, pt));
		c.getSoundManager().play(new PositionedSoundInstance(event(n).getId(), SoundCategory.PLAYERS, volume, pt[0],
			Random.create(), false, 0, SoundInstance.AttenuationType.LINEAR, pos.x, pos.y, pos.z, false));
	}

	/** A looping 2D AudioSource (`aud.Play()` with loop = true); one per name. */
	public static void loop(String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		Loop l = loops.get(name);
		if (l != null && !l.isDone()) {
			l.set(volume, pitch);
			return;
		}
		l = new Loop(event(name), volume, pitch);
		loops.put(name, l);
		c.getSoundManager().play(l);
	}

	/** A looping AudioSource under its own key, so one clip can loop on several sources at once (stop with the key). */
	public static void loopAs(String key, String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		Loop l = loops.get(key);
		if (l != null && !l.isDone()) {
			l.set(volume, pitch);
			return;
		}
		l = new Loop(event(name), volume, pitch);
		loops.put(key, l);
		c.getSoundManager().play(l);
	}

	/** A looping 3D AudioSource under a key that follows `pos` (called again each tick to move it along). */
	public static void loopAt(String key, Vec3d pos, String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		Loop l = loops.get(key);
		if (l != null && !l.isDone()) {
			l.set(volume, pitch);
			l.at(pos);
			return;
		}
		l = new Loop(event(mono(name)), volume, pitch);
		l.at(pos);
		loops.put(key, l);
		c.getSoundManager().play(l);
	}

	/**
	 * A looping 2D music source under a key, its pitch free below Minecraft's 0.5 floor down to near 0 (ScreenZone winds
	 * the shop music up from pitch 0 and back down). On the Master volume: the game routes the shop's music AudioSources
	 * to its Master mixer group, not Music, so it plays with the music turned down.
	 */
	public static void music(String key, String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		Loop l = loops.get(key);
		if (l != null && !l.isDone()) {
			l.set(volume, pitch);
			return;
		}
		l = new Loop(event(name), volume, pitch, SoundCategory.MASTER);
		l.lowPitch = true;
		l.set(volume, pitch);
		loops.put(key, l);
		c.getSoundManager().play(l);
	}

	/** A sound of ours whose pitch may go under Minecraft's 0.5 (SoundSystemPitchMixin). */
	public static boolean lowPitch(net.minecraft.client.sound.SoundInstance s) {
		return s instanceof Loop l && l.lowPitch;
	}

	/** `aud.volume = …; aud.pitch = …` on a running loop (no-op if it is not playing). */
	public static void setLoop(String name, float volume, float pitch) {
		Loop l = loops.get(name);
		if (l != null && !l.isDone()) l.set(volume, pitch);
	}

	/** A one-shot that can be cut off with stop(name), like an instantiated sound object that is later Destroy()ed. */
	public static void once(String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		stop(name);
		Loop l = new Loop(event(name), volume, pitch);
		l.once();
		loops.put(name, l);
		c.getSoundManager().play(l);
	}

	/** A positional one-shot under a key: playing it again cuts off the last one (aud.Play() on one 3D AudioSource). */
	public static void onceAt(String key, Vec3d pos, String name, float volume, float pitch) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getSoundManager() == null) return;
		stop(key);
		Loop l = new Loop(event(name), volume, pitch);
		l.once();
		l.at(pos);
		loops.put(key, l);
		c.getSoundManager().play(l);
	}

	/** `aud.Stop()`. */
	public static void stop(String name) {
		Loop l = loops.remove(name);
		if (l != null) l.stop();
	}

	/** Unity's Random.Range(a, b) for pitch. */
	public static float range(float a, float b) {
		return a + (float) Math.random() * (b - a);
	}

	private static final class Loop extends AbstractSoundInstance implements TickableSoundInstance {
		private boolean done;

		/** the pitch may go under 0.5 (music) */
		boolean lowPitch;

		Loop(SoundEvent e, float volume, float pitch) {
			this(e, volume, pitch, SoundCategory.PLAYERS);
		}

		Loop(SoundEvent e, float volume, float pitch, SoundCategory category) {
			super(e, category, Random.create());
			this.repeat = true;
			this.repeatDelay = 0;
			this.relative = true;
			this.attenuationType = AttenuationType.NONE;
			this.volume = volume;
			this.pitch = pitch;
		}

		void set(float volume, float pitch) {
			this.volume = volume;
			this.pitch = Math.max(lowPitch ? 0.01f : 0.5f, Math.min(2f, pitch));
		}

		void stop() {
			done = true;
		}

		void once() {
			this.repeat = false;
		}

		void at(Vec3d p) {
			this.relative = false;
			this.attenuationType = AttenuationType.LINEAR;
			this.x = p.x;
			this.y = p.y;
			this.z = p.z;
		}

		@Override
		public boolean isDone() {
			return done;
		}

		@Override
		public void tick() {}
	}
}
