package com.ultracraft.client.uk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A skinned rig converted from one of ULTRAKILL's prefabs (tools/extract/export_rig.py): the bone tree
 * (bind-pose local transforms), the skinned meshes with bind poses and bone weights, and the "mount"
 * chain of transforms from the prefab root down to the animator. Unity units, Unity's left-handed Y-up
 * axes; the renderer converts.
 */
public final class UkRig {
	public record Bone(String name, String path, int parent, Vector3f pos, Quaternionf rot, Vector3f scale) {}

	public record Xf(String name, Vector3f pos, Quaternionf rot, Vector3f scale) {
		public Matrix4f matrix() {
			return new Matrix4f().translationRotateScale(pos, rot, scale);
		}
	}

	public record SubMesh(int first, int count, String texture, String material) {}

	public static final class Mesh {
		public String name;
		public int[] bones;          // rig bone index per mesh bone
		public Matrix4f[] bindposes;
		public float[] vertices, normals, uvs, weights;
		public int[] boneIndices, indices;
		public List<SubMesh> submeshes = new ArrayList<>();
		public String texture;
		public int vertexCount;
		/** Material colour (static meshes), multiplied into the vertices; null = white. */
		public float[] color;
		/** Unity built-in primitive name ("sphere", "cube", "quad") when the mesh is generated. */
		public String builtin;
	}

	public String name, root;
	public List<Xf> mount = new ArrayList<>();
	public List<Bone> bones = new ArrayList<>();
	public List<Mesh> meshes = new ArrayList<>();

	public int boneIndex(String path) {
		for (int i = 0; i < bones.size(); i++) if (bones.get(i).path().equals(path)) return i;
		return -1;
	}

	public static UkRig parse(String name, JsonObject o) {
		UkRig r = new UkRig();
		r.name = name;
		r.root = o.get("root").getAsString();
		for (JsonElement e : o.getAsJsonArray("mount")) {
			JsonObject m = e.getAsJsonObject();
			r.mount.add(new Xf(m.get("name").getAsString(), v3(m.getAsJsonArray("pos")), quat(m.getAsJsonArray("rot")), v3(m.getAsJsonArray("scale"))));
		}
		for (JsonElement e : o.getAsJsonArray("bones")) {
			JsonObject b = e.getAsJsonObject();
			r.bones.add(new Bone(b.get("name").getAsString(), b.get("path").getAsString(), b.get("parent").getAsInt(),
				v3(b.getAsJsonArray("pos")), quat(b.getAsJsonArray("rot")), v3(b.getAsJsonArray("scale"))));
		}
		for (JsonElement e : o.getAsJsonArray("meshes")) {
			JsonObject m = e.getAsJsonObject();
			Mesh mesh = new Mesh();
			mesh.name = m.get("name").getAsString();
			mesh.texture = m.has("texture") && !m.get("texture").isJsonNull() ? m.get("texture").getAsString() : null;
			mesh.bones = ints(m.getAsJsonArray("bones"));
			JsonArray bp = m.getAsJsonArray("bindposes");
			mesh.bindposes = new Matrix4f[bp.size()];
			for (int i = 0; i < bp.size(); i++) {
				float[] f = floats(bp.get(i).getAsJsonArray());
				mesh.bindposes[i] = new Matrix4f().set(f); // column-major
			}
			if (m.has("color") && !m.get("color").isJsonNull()) mesh.color = floats(m.getAsJsonArray("color"));
			if (m.has("builtin") && !m.get("builtin").isJsonNull()) {
				mesh.builtin = m.get("builtin").getAsString();
				BuiltinMeshes.fill(mesh);
				r.meshes.add(mesh);
				continue;
			}
			mesh.vertices = floats(m.getAsJsonArray("vertices"));
			mesh.normals = floats(m.getAsJsonArray("normals"));
			mesh.uvs = floats(m.getAsJsonArray("uvs"));
			mesh.boneIndices = ints(m.getAsJsonArray("boneIndices"));
			mesh.weights = floats(m.getAsJsonArray("boneWeights"));
			mesh.indices = ints(m.getAsJsonArray("indices"));
			mesh.vertexCount = mesh.vertices.length / 3;
			for (JsonElement s : m.getAsJsonArray("submeshes")) {
				JsonObject so = s.getAsJsonObject();
				String tex = so.has("texture") && !so.get("texture").isJsonNull() ? so.get("texture").getAsString() : mesh.texture;
				String mat = so.has("material") && !so.get("material").isJsonNull() ? so.get("material").getAsString() : null;
				mesh.submeshes.add(new SubMesh(so.get("first").getAsInt(), so.get("count").getAsInt(), tex, mat));
			}
			r.meshes.add(mesh);
		}
		return r;
	}

	static Vector3f v3(JsonArray a) {
		return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	static Quaternionf quat(JsonArray a) {
		return new Quaternionf(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat());
	}

	static float[] floats(JsonArray a) {
		float[] f = new float[a.size()];
		for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
		return f;
	}

	static int[] ints(JsonArray a) {
		int[] f = new int[a.size()];
		for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsInt();
		return f;
	}
}
