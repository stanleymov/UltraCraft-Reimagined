package com.ultracraft.client.uk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One of ULTRAKILL's animation clips, sampled per frame by tools/extract/export_clips.py. */
public final class UkClip {
	public record Event(float time, String name, float floatArg, int intArg, String stringArg) {}

	public static final class Track {
		public float[] pos, rot, scale; // frames * 3 / 4 / 3, or null
	}

	public String name;
	public float sampleRate, length;
	public boolean loop;
	public List<Event> events = new ArrayList<>();
	public Map<String, Track> tracks = new HashMap<>();
	public int frames;

	public static UkClip parse(JsonObject o) {
		UkClip c = new UkClip();
		c.name = o.get("name").getAsString();
		c.sampleRate = o.get("sampleRate").getAsFloat();
		c.length = o.get("length").getAsFloat();
		c.loop = o.has("loop") && o.get("loop").getAsBoolean();
		for (JsonElement e : o.getAsJsonArray("events")) {
			JsonObject ev = e.getAsJsonObject();
			c.events.add(new Event(ev.get("time").getAsFloat(), ev.get("name").getAsString(), ev.get("float").getAsFloat(), ev.get("int").getAsInt(), ev.get("string").getAsString()));
		}
		for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("tracks").entrySet()) {
			JsonObject t = en.getValue().getAsJsonObject();
			Track tr = new Track();
			if (t.has("pos")) tr.pos = flat(t.getAsJsonArray("pos"));
			if (t.has("rot")) tr.rot = flat(t.getAsJsonArray("rot"));
			if (t.has("scale")) tr.scale = flat(t.getAsJsonArray("scale"));
			int n = tr.pos != null ? tr.pos.length / 3 : tr.rot != null ? tr.rot.length / 4 : tr.scale != null ? tr.scale.length / 3 : 0;
			c.frames = Math.max(c.frames, n);
			c.tracks.put(en.getKey(), tr);
		}
		return c;
	}

	private static float[] flat(JsonArray frames) {
		int n = frames.size();
		if (n == 0) return new float[0];
		int w = frames.get(0).getAsJsonArray().size();
		float[] f = new float[n * w];
		for (int i = 0; i < n; i++) {
			JsonArray a = frames.get(i).getAsJsonArray();
			for (int j = 0; j < w; j++) f[i * w + j] = a.get(j).getAsFloat();
		}
		return f;
	}

	/** Frame position for a time, clamped (or wrapped when looping). */
	public float frameAt(float time) {
		if (frames <= 1) return 0;
		float f = time * sampleRate;
		if (loop) {
			float max = frames - 1;
			f = f % max;
			if (f < 0) f += max;
			return f;
		}
		return Math.max(0, Math.min(frames - 1, f));
	}

	public void samplePos(Track t, float frame, Vector3f out) {
		int i0 = (int) Math.floor(frame), i1 = Math.min(frames - 1, i0 + 1);
		float u = frame - i0;
		float[] p = t.pos;
		out.set(lerp(p[i0 * 3], p[i1 * 3], u), lerp(p[i0 * 3 + 1], p[i1 * 3 + 1], u), lerp(p[i0 * 3 + 2], p[i1 * 3 + 2], u));
	}

	public void sampleScale(Track t, float frame, Vector3f out) {
		int i0 = (int) Math.floor(frame), i1 = Math.min(frames - 1, i0 + 1);
		float u = frame - i0;
		float[] p = t.scale;
		out.set(lerp(p[i0 * 3], p[i1 * 3], u), lerp(p[i0 * 3 + 1], p[i1 * 3 + 1], u), lerp(p[i0 * 3 + 2], p[i1 * 3 + 2], u));
	}

	public void sampleRot(Track t, float frame, Quaternionf out) {
		int i0 = (int) Math.floor(frame), i1 = Math.min(frames - 1, i0 + 1);
		float u = frame - i0;
		float[] r = t.rot;
		out.set(r[i0 * 4], r[i0 * 4 + 1], r[i0 * 4 + 2], r[i0 * 4 + 3]);
		if (u > 0 && i1 != i0) {
			Quaternionf b = new Quaternionf(r[i1 * 4], r[i1 * 4 + 1], r[i1 * 4 + 2], r[i1 * 4 + 3]);
			out.slerp(b, u);
		}
		out.normalize();
	}

	private static float lerp(float a, float b, float u) {
		return a + (b - a) * u;
	}
}
