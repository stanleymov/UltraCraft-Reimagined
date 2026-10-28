package com.ultracraft.client.uk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultracraft.UltraCraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the converted ULTRAKILL rigs and clips from the mod resources (`assets/ultracraft/uk/...`, built
 * into the jar by tools/extract/build_assets.py). Missing files are logged once and reported as absent so
 * the mod still runs without the game's assets (placeholders are then drawn).
 */
public final class UkAssets {
	private static final Map<String, UkRig> rigs = new HashMap<>();
	private static final Map<String, UkClip> clips = new HashMap<>();
	private static final Map<String, Boolean> missing = new HashMap<>();

	private UkAssets() {}

	public static void clear() {
		rigs.clear();
		clips.clear();
		missing.clear();
	}

	public static UkRig rig(String name) {
		return rigs.computeIfAbsent(name, n -> {
			JsonObject o = json("uk/rigs/" + n + ".json");
			return o == null ? null : UkRig.parse(n, o);
		});
	}

	/** A clip of a rig by its ULTRAKILL name ("Shoot", "PickUp"...), or null when not converted. */
	public static UkClip clip(String rig, String clip) {
		String key = rig + "/" + clip;
		if (clips.containsKey(key)) return clips.get(key);
		JsonObject o = json("uk/clips/" + rig + "/" + clip.toLowerCase().replace(' ', '_') + ".json");
		UkClip c = o == null ? null : UkClip.parse(o);
		clips.put(key, c);
		return c;
	}

	public static boolean available() {
		return rig("revolver") != null;
	}

	private static JsonObject json(String path) {
		Identifier id = new Identifier(UltraCraft.MOD_ID, path);
		try {
			Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(id);
			if (res.isEmpty()) {
				if (missing.putIfAbsent(path, true) == null) UltraCraft.LOG.warn("UK asset missing: {} (run tools/extract/build_assets.py)", id);
				return null;
			}
			try (var r = new InputStreamReader(res.get().getInputStream(), StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(r).getAsJsonObject();
			}
		} catch (Exception e) {
			UltraCraft.LOG.error("UK asset {} failed to load", id, e);
			return null;
		}
	}
}
