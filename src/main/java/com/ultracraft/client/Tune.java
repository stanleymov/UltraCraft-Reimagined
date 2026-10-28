package com.ultracraft.client;

import com.ultracraft.UltraCraft;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live settings: `config/ultracraft-live.toml`, re-read whenever the file changes, so anything tuned by eye
 * can be changed while the game runs. Every value has ULTRAKILL's own number as its default; the file is
 * written out with all defaults the first time so it can be edited in place.
 */
public final class Tune {
	private static final Map<String, Double> defaults = new LinkedHashMap<>();
	private static final Map<String, String> comments = new LinkedHashMap<>();
	private static final Map<String, Double> values = new LinkedHashMap<>();
	private static long lastModified = -1;
	private static long lastCheck;
	private static Path file;

	private Tune() {}

	/** Register a setting with its default (ULTRAKILL's value) and a one-line comment. */
	public static double def(String key, double value, String comment) {
		defaults.put(key, value);
		comments.put(key, comment);
		return value;
	}

	public static double get(String key) {
		Double v = values.get(key);
		if (v != null) return v;
		Double d = defaults.get(key);
		if (d == null) throw new IllegalArgumentException("unknown tune key " + key);
		return d;
	}

	public static float getF(String key) {
		return (float) get(key);
	}

	public static boolean on(String key) {
		return get(key) != 0;
	}

	/** Called every client tick: cheap mtime poll once a second, reload on change. */
	public static void poll() {
		long now = System.currentTimeMillis();
		if (now - lastCheck < 1000) return;
		lastCheck = now;
		if (file == null) file = MinecraftClient.getInstance().runDirectory.toPath().resolve("config").resolve("ultracraft-live.toml");
		try {
			if (!Files.exists(file)) {
				writeDefaults();
			}
			long mod = Files.getLastModifiedTime(file).toMillis();
			if (mod != lastModified) {
				lastModified = mod;
				load();
			}
		} catch (IOException e) {
			UltraCraft.LOG.warn("live settings: {}", e.toString());
		}
	}

	private static void load() throws IOException {
		values.clear();
		for (String line : Files.readAllLines(file)) {
			String s = line.strip();
			if (s.isEmpty() || s.startsWith("#") || s.startsWith("[")) continue;
			int eq = s.indexOf('=');
			if (eq < 0) continue;
			String k = s.substring(0, eq).strip();
			String v = s.substring(eq + 1).strip();
			int hash = v.indexOf('#');
			if (hash >= 0) v = v.substring(0, hash).strip();
			try {
				if (v.equals("true")) values.put(k, 1.0);
				else if (v.equals("false")) values.put(k, 0.0);
				else values.put(k, Double.parseDouble(v));
			} catch (NumberFormatException ignored) {
				UltraCraft.LOG.warn("live settings: bad value for {}: {}", k, v);
			}
		}
		UltraCraft.LOG.info("live settings reloaded ({} values)", values.size());
	}

	private static void writeDefaults() throws IOException {
		Files.createDirectories(file.getParent());
		StringBuilder sb = new StringBuilder("# UltraCraft live settings. Edit while the game runs; it reloads within a second.\n");
		sb.append("# Defaults are ULTRAKILL's own values (Unity units / seconds unless noted). Delete a line to go back to it.\n\n");
		for (var e : defaults.entrySet()) {
			String c = comments.get(e.getKey());
			if (c != null && !c.isEmpty()) sb.append("# ").append(c).append('\n');
			sb.append(e.getKey()).append(" = ").append(fmt(e.getValue())).append("\n\n");
		}
		Files.writeString(file, sb.toString());
	}

	private static String fmt(double d) {
		if (d == Math.rint(d) && Math.abs(d) < 1e9) return Long.toString((long) d);
		return Double.toString(d);
	}
}
