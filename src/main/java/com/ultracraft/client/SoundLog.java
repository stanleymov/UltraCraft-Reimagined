package com.ultracraft.client;

import net.minecraft.client.sound.SoundInstance;

import java.util.ArrayDeque;

/**
 * Logs bursts of sounds loud enough to clip (user: the overcharged green shotgun's blast and a parry on the Ender
 * Dragon cut the sound off): every sound, Minecraft's too, that starts within debug.soundWindow seconds is added up by
 * the volume it actually starts at, and a sum over debug.soundLoud writes "[sound] loud burst" with the sounds in it.
 * Minecraft itself logs "Failed to create new sound handle" when it runs out of sources.
 */
public final class SoundLog {
	static {
		Tune.def("debug.sound", 1, "1 = log bursts of sounds loud enough to clip");
		Tune.def("debug.soundLoud", 2.5, "debug.sound: a burst whose summed start volume reaches this is logged");
		Tune.def("debug.soundWindow", 0.15, "debug.sound: s the sounds of one burst start within");
	}

	private record Start(long at, String id, float volume) {}

	private static final ArrayDeque<Start> recent = new ArrayDeque<>();
	private static long lastLog;

	private SoundLog() {}

	static boolean debug() {
		return Tune.on("debug.sound");
	}

	/** SoundSystem.play: one of our sounds starts at `volume` (after the categories and our own volume). */
	public static synchronized void started(SoundInstance sound, float volume) {
		if (!Tune.on("debug.sound") || volume <= 0) return;
		long now = System.nanoTime();
		long window = (long) (Tune.get("debug.soundWindow") * 1e9);
		recent.addLast(new Start(now, sound.getId().getPath(), volume));
		while (!recent.isEmpty() && now - recent.peekFirst().at() > window) recent.removeFirst();
		double sum = 0;
		for (Start s : recent) sum += s.volume();
		// (once a burst is logged, the next is only logged after it has passed)
		if (sum < Tune.get("debug.soundLoud") || now - lastLog < window) return;
		lastLog = now;
		StringBuilder b = new StringBuilder();
		for (Start s : recent) b.append(' ').append(s.id()).append(String.format(" %.2f", s.volume()));
		com.ultracraft.UltraCraft.LOG.info("[sound] loud burst: {} sounds, total volume {} within {} s:{}", recent.size(),
			String.format("%.2f", sum), Tune.get("debug.soundWindow"), b);
	}
}
