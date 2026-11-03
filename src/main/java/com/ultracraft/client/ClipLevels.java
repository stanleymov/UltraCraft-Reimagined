package com.ultracraft.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.Sound;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How loud each clip really is, for SoundBus: the peak of every WINDOW seconds of the decoded clip (0..1). A clip is decoded
 * the first time it plays, off the render thread; it needs nothing from the setup, so a code-only update has it too.
 */
final class ClipLevels {
	static final double WINDOW = 0.05;

	/** a streamed clip (music) isn't decoded just for this */
	private static final float STREAMED = 0.5f;
	/** until a clip is measured, or if it can't be */
	private static final float UNKNOWN = 0.7f;
	private static final float[] PENDING = {};

	private static final Map<Identifier, float[]> levels = new ConcurrentHashMap<>();

	private ClipLevels() {}

	private static float[] measure(Identifier location) {
		try (InputStream in = MinecraftClient.getInstance().getResourceManager().open(location); OggAudioStream ogg = new OggAudioStream(in)) {
			int channels = Math.max(1, ogg.getFormat().getChannels());
			int window = Math.max(1, (int) (ogg.getFormat().getSampleRate() * WINDOW) * channels);
			ByteBuffer bytes = ogg.getBuffer();
			ShortBuffer samples = bytes.order(ByteOrder.nativeOrder()).asShortBuffer();
			float[] env = new float[(samples.remaining() + window - 1) / window];
			for (int i = 0, n = samples.remaining(); i < n; i++) {
				float s = Math.abs(samples.get(i)) / 32768f;
				if (s > env[i / window]) env[i / window] = s;
			}
			return env;
		} catch (Exception ex) {
			com.ultracraft.UltraCraft.LOG.warn("[sound] can't measure {}: {}", location, ex.toString());
			return new float[] {UNKNOWN};
		}
	}

	/** The clip's level `seconds` into it (its next window too, so a rising clip counts before it gets there); 0 past its end. */
	static float at(Sound clip, double seconds, boolean repeats) {
		if (clip.isStreamed()) return STREAMED;
		Identifier location = clip.getLocation();
		float[] env = levels.get(location);
		if (env == null) {
			levels.put(location, PENDING);
			Util.getMainWorkerExecutor().execute(() -> levels.put(location, measure(location)));
			return UNKNOWN;
		}
		if (env.length == 0) return UNKNOWN;
		int w = (int) (seconds / WINDOW);
		if (repeats) return Math.max(env[w % env.length], env[(w + 1) % env.length]);
		if (w >= env.length) return 0;
		return w + 1 < env.length ? Math.max(env[w], env[w + 1]) : env[w];
	}
}
