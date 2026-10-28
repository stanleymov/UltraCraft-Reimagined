package com.ultracraft.weapon;

import com.ultracraft.UltraCraft;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side reader of the same live settings file as the client's Tune (`config/ultracraft-live.toml`), for the
 * few tuned-by-eye values that live in server code. Re-read at most once a second when the file changes; a key
 * missing from the file falls back to the default passed in.
 */
public final class ServerTune {
	private static final Map<String, Double> values = new java.util.concurrent.ConcurrentHashMap<>();
	private static long lastModified = -1;
	private static long lastCheck;

	private ServerTune() {}

	public static double get(String key, double def) {
		poll();
		Double v = values.get(key);
		return v != null ? v : def;
	}

	private static void poll() {
		long now = System.currentTimeMillis();
		if (now - lastCheck < 1000) return;
		lastCheck = now;
		Path file = FMLPaths.CONFIGDIR.get().resolve("ultracraft-live.toml");
		try {
			if (!Files.exists(file)) return;
			long mod = Files.getLastModifiedTime(file).toMillis();
			if (mod == lastModified) return;
			lastModified = mod;
			values.clear();
			for (String line : Files.readAllLines(file)) {
				String s = line.strip();
				if (s.isEmpty() || s.startsWith("#") || s.startsWith("[")) continue;
				int eq = s.indexOf('=');
				if (eq < 0) continue;
				String v = s.substring(eq + 1).strip();
				int hash = v.indexOf('#');
				if (hash >= 0) v = v.substring(0, hash).strip();
				try {
					values.put(s.substring(0, eq).strip(), v.equals("true") ? 1.0 : v.equals("false") ? 0.0 : Double.parseDouble(v));
				} catch (NumberFormatException ignored) {
				}
			}
		} catch (IOException e) {
			UltraCraft.LOG.warn("server live settings: {}", e.toString());
		}
	}
}
