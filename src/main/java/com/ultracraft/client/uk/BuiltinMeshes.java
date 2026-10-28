package com.ultracraft.client.uk;

import java.util.ArrayList;
import java.util.List;

/** Unity's built-in primitives (not in the bundles): unit sphere (radius 0.5), cube (1 m), quad (1x1 facing -z). */
public final class BuiltinMeshes {
	private BuiltinMeshes() {}

	public static void fill(UkRig.Mesh m) {
		switch (m.builtin) {
			case "cube" -> cube(m);
			case "quad", "plane" -> quad(m);
			default -> sphere(m, 12, 8);
		}
		m.vertexCount = m.vertices.length / 3;
		int n = m.vertexCount;
		m.boneIndices = new int[n * 4];
		m.weights = new float[n * 4];
		for (int i = 0; i < n; i++) m.weights[i * 4] = 1f;
		if (m.bones == null || m.bones.length == 0) m.bones = new int[] {0};
		if (m.bindposes == null || m.bindposes.length == 0) m.bindposes = new org.joml.Matrix4f[] {new org.joml.Matrix4f()};
		if (m.submeshes.isEmpty()) m.submeshes.add(new UkRig.SubMesh(0, m.indices.length, m.texture, null));
	}

	private static void sphere(UkRig.Mesh m, int seg, int rings) {
		List<Float> v = new ArrayList<>(), nrm = new ArrayList<>(), uv = new ArrayList<>();
		List<Integer> idx = new ArrayList<>();
		for (int r = 0; r <= rings; r++) {
			double phi = Math.PI * r / rings;
			for (int s = 0; s <= seg; s++) {
				double th = 2 * Math.PI * s / seg;
				float x = (float) (Math.sin(phi) * Math.cos(th)), y = (float) Math.cos(phi), z = (float) (Math.sin(phi) * Math.sin(th));
				v.add(x * 0.5f); v.add(y * 0.5f); v.add(z * 0.5f);
				nrm.add(x); nrm.add(y); nrm.add(z);
				uv.add((float) s / seg); uv.add(1f - (float) r / rings);
			}
		}
		for (int r = 0; r < rings; r++) {
			for (int s = 0; s < seg; s++) {
				int a = r * (seg + 1) + s, b = a + seg + 1;
				idx.add(a); idx.add(b); idx.add(a + 1);
				idx.add(a + 1); idx.add(b); idx.add(b + 1);
			}
		}
		set(m, v, nrm, uv, idx);
	}

	private static void cube(UkRig.Mesh m) {
		List<Float> v = new ArrayList<>(), nrm = new ArrayList<>(), uv = new ArrayList<>();
		List<Integer> idx = new ArrayList<>();
		float[][] faces = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
		for (float[] n : faces) {
			float[] u = n[1] != 0 ? new float[] {1, 0, 0} : new float[] {0, 1, 0};
			float[] w = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
			int base = v.size() / 3;
			float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
			for (float[] c : corners) {
				v.add((n[0] + u[0] * c[0] + w[0] * c[1]) * 0.5f);
				v.add((n[1] + u[1] * c[0] + w[1] * c[1]) * 0.5f);
				v.add((n[2] + u[2] * c[0] + w[2] * c[1]) * 0.5f);
				nrm.add(n[0]); nrm.add(n[1]); nrm.add(n[2]);
				uv.add(c[0] * 0.5f + 0.5f); uv.add(c[1] * 0.5f + 0.5f);
			}
			idx.add(base); idx.add(base + 2); idx.add(base + 1);
			idx.add(base); idx.add(base + 3); idx.add(base + 2);
		}
		set(m, v, nrm, uv, idx);
	}

	private static void quad(UkRig.Mesh m) {
		List<Float> v = new ArrayList<>(List.of(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f));
		List<Float> nrm = new ArrayList<>(List.of(0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f));
		List<Float> uv = new ArrayList<>(List.of(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f));
		List<Integer> idx = new ArrayList<>(List.of(0, 2, 1, 0, 3, 2));
		set(m, v, nrm, uv, idx);
	}

	private static void set(UkRig.Mesh m, List<Float> v, List<Float> n, List<Float> uv, List<Integer> idx) {
		m.vertices = arr(v);
		m.normals = arr(n);
		m.uvs = arr(uv);
		m.indices = idx.stream().mapToInt(Integer::intValue).toArray();
	}

	private static float[] arr(List<Float> l) {
		float[] f = new float[l.size()];
		for (int i = 0; i < f.length; i++) f[i] = l.get(i);
		return f;
	}
}
