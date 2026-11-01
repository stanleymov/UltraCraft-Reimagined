package com.ultracraft.client.uk;

import com.ultracraft.UltraCraft;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One animated copy of a rig: plays clips (with cross-fades and animation events like ULTRAKILL's
 * AnimationEvents "ReadyGun" / "ActiveStart"), poses the bones and CPU-skins the meshes into a vertex
 * consumer. Time runs in seconds, advanced from the render loop with real frame time.
 */
public final class RigInstance {
	public final UkRig rig;
	private final String rigName;
	private final Matrix4f[] local, world;
	private final List<Matrix4f[]> skin = new ArrayList<>();

	// current and previous (fading out) clip
	private UkClip clip, prevClip;
	private float time, prevTime, speed = 1, prevSpeed = 1;
	private float fade, fadeLength;
	private int nextEvent;
	public Consumer<UkClip.Event> onEvent = e -> {};
	/** Local overrides applied after the clip (bone path -> extra rotation), e.g. the revolver twirl. */
	private final java.util.Map<Integer, Quaternionf> extraRot = new java.util.HashMap<>();

	public RigInstance(String rigName, UkRig rig) {
		this.rigName = rigName;
		this.rig = rig;
		int n = rig.bones.size();
		local = new Matrix4f[n];
		world = new Matrix4f[n];
		for (int i = 0; i < n; i++) {
			local[i] = new Matrix4f();
			world[i] = new Matrix4f();
		}
		for (UkRig.Mesh m : rig.meshes) {
			Matrix4f[] s = new Matrix4f[m.bones.length];
			for (int i = 0; i < s.length; i++) s[i] = new Matrix4f();
			skin.add(s);
		}
	}

	public String rigName() {
		return rigName;
	}

	public String currentClip() {
		return clip == null ? null : clip.name;
	}

	public float clipTime() {
		return time;
	}

	/** True while a previous clip is still being cross-faded out (the arm is on its way to the new pose). */
	public boolean fading() {
		return prevClip != null && fadeLength > 0 && fade > 0;
	}

	/** Seconds until the current clip reaches `share` of its length at its speed (0 when past it or no clip). */
	public float secondsTo(float share) {
		if (clip == null || speed <= 0) return 0;
		return Math.max(0, (clip.length * share - time) / speed);
	}

	public boolean finished() {
		return clip == null || (!clip.loop && time >= clip.length);
	}

	/**
	 * The clip a one-shot returns to instead of the controller's Idle, or null: the arm's Hold while it holds an item
	 * (Punch: the Holding bool), its shop pose at a Terminal (FistControl.ShopMode's layer).
	 */
	public String restClip;
	/** Where restClip resumes, seconds in (the shop pose's slow loop carries on through a tap instead of restarting). */
	public float restTime;
	/** The key of its last first-person draw in ViewModel.lastPose (null: the rig's name; the off-hand copy's "off:" + name). */
	public String poseKey;

	/** Play a clip from the start (Animator.Play / a trigger transition), cross-fading over `fadeSeconds`. */
	public void play(String name, float speed, float fadeSeconds) {
		play(name, speed, fadeSeconds, 0f);
	}

	/** Play from `startSeconds` in (Animator.Play with a normalized time); events before it are skipped. */
	public void play(String name, float speed, float fadeSeconds, float startSeconds) {
		UkClip c = UkAssets.clip(rigName, name);
		if (c == null) {
			UltraCraft.LOG.warn("rig {} has no clip {}", rigName, name);
			return;
		}
		if (prevClip != null) flushPrevEvents();
		if (clip != null && fadeSeconds > 0) {
			prevClip = clip;
			prevTime = time;
			prevSpeed = this.speed;
			prevNextEvent = nextEvent;
			fade = fadeLength = fadeSeconds;
		} else {
			if (clip != null) { prevClip = clip; prevTime = time; prevNextEvent = nextEvent; flushPrevEvents(); }
			prevClip = null;
			fade = 0;
		}
		UkTransitions.State st = UkTransitions.state(rigName, name);
		clip = c;
		time = startSeconds;
		this.speed = speed * (st != null ? st.speed() : 1f);
		nextEvent = 0;
		while (nextEvent < c.events.size() && c.events.get(nextEvent).time() < startSeconds) nextEvent++;
	}

	/** The state's playback speed (a speed parameter such as TwirlSpeed changing while it plays). */
	public void setSpeed(float speed) {
		this.speed = speed;
	}

	/** The fading-out state keeps running in Unity, so its remaining events still fire. */
	private void flushPrevEvents() {
		if (prevClip == null) return;
		UkClip pc = prevClip;
		List<UkClip.Event> evs = pc.events;
		while (prevClip == pc && prevNextEvent < evs.size()) onEvent.accept(evs.get(prevNextEvent++));
	}

	private int prevNextEvent;

	public void play(String name) {
		play(name, 1f, 0f);
	}

	/**
	 * A second, override layer at a weight (Unity's next animator layer, e.g. the nailgun's WeightLayer: Idle over the
	 * Shoot kick): its clip loops on its own clock and pulls the bones it animates toward its pose by `weight`.
	 */
	private UkClip overlayClip;
	private float overlayTime, overlayWeight;

	public void setOverlay(String name, float weight) {
		if (name == null || weight <= 0) { overlayWeight = 0; return; }
		if (overlayClip == null || !overlayClip.name.equals(name)) {
			UkClip c = UkAssets.clip(rigName, name);
			if (c == null) return;
			overlayClip = c;
			overlayTime = 0;
		}
		overlayWeight = Math.min(1, weight);
	}

	/** Clips to restart a little later (a trigger set on a later fixed step than this frame), oldest first. */
	private final List<Object[]> queued = new ArrayList<>();

	/** play(name) from the start after `delay` seconds of animation time (the time past it is already played). */
	public void playAfter(String name, float delay) {
		playAfter(name, delay, 1f);
	}

	/** Drop the restarts still waiting (playAfter). */
	public void clearQueued() {
		queued.clear();
	}

	/** playAfter at a clip speed (the state's Speed in the controller). */
	public void playAfter(String name, float delay, float speed) {
		queued.add(new Object[] {name, delay, speed});
	}

	/** Advance time; fires events whose time was crossed, in order. */
	public void advance(float dt) {
		if (overlayClip != null) {
			overlayTime += dt;
			if (overlayClip.loop && overlayClip.length > 0) overlayTime %= overlayClip.length;
		}
		if (!queued.isEmpty()) {
			String due = null;
			float over = 0, dueSpeed = 1f;
			for (java.util.Iterator<Object[]> it = queued.iterator(); it.hasNext();) {
				Object[] q = it.next();
				q[1] = (Float) q[1] - dt;
				if ((Float) q[1] <= 0) { due = (String) q[0]; over = -(Float) q[1]; dueSpeed = (Float) q[2]; it.remove(); }
			}
			// the latest restart wins, already `over` seconds in
			if (due != null) { play(due, dueSpeed, 0f); dt = Math.min(dt, over); }
		}
		if (clip == null) return;
		float before = time;
		time += dt * speed;
		if (clip.loop && clip.length > 0) {
			while (time >= clip.length) {
				fireEvents(before, clip.length + 0.0001f);
				time -= clip.length;
				before = 0;
				nextEvent = 0;
			}
		}
		fireEvents(before, time);
		if (prevClip != null) {
			float pb = prevTime;
			prevTime += dt * prevSpeed;
			List<UkClip.Event> evs = prevClip.events;
			UkClip pc = prevClip;
			while (prevClip == pc && prevNextEvent < evs.size() && evs.get(prevNextEvent).time() <= prevTime) {
				UkClip.Event e = evs.get(prevNextEvent++);
				if (e.time() >= pb - 1e-4) onEvent.accept(e);
			}
			if (prevClip != pc) return;
			fade -= dt;
			if (fade <= 0) { flushPrevEvents(); prevClip = null; }
		}
		// the controller's exit-time transition back to Idle (or to restClip: the arm's Holding / shop layer)
		if (!clip.loop) {
			String idle = restClip != null ? restClip : UkTransitions.idle(rigName);
			UkTransitions.State st = UkTransitions.state(rigName, clip.name);
			if (idle != null && !clip.name.equalsIgnoreCase(idle) && (st == null || st.exit() >= 0)) {
				float exit = st != null ? st.exit() : 1f;
				float fadeS = st != null ? st.fade() : 0.1f;
				if (time >= exit * clip.length) play(idle, 1f, fadeS, restClip != null ? restTime : 0f);
			}
		}
	}

	private void fireEvents(float from, float to) {
		UkClip c = clip;
		List<UkClip.Event> evs = c.events;
		while (clip == c && nextEvent < evs.size() && evs.get(nextEvent).time() <= to) {
			UkClip.Event e = evs.get(nextEvent++);
			// a handler may play another clip (BlastCheck → PunchBlast): then this clip's events are done
			if (e.time() >= from - 1e-4) onEvent.accept(e);
		}
	}

	/**
	 * Pose with the prefab root's own position cancelled: a prefab exported from a scene keeps its placement in the
	 * root node (the Coin sat 311 units away, the shell 252), which an entity or block renderer does not want.
	 */
	public void poseAtOrigin() {
		UkRig.Bone root = rig.bones.get(0);
		pose(new Matrix4f().translation(-root.pos().x, -root.pos().y, -root.pos().z));
	}

	/** Log every mesh's posed bounds on the next render (set by the displays debug). */
	public boolean debugBounds;

	public void setExtraRotation(String bonePath, Quaternionf q) {
		int i = rig.boneIndex(bonePath);
		if (i >= 0) {
			if (q == null) extraRot.remove(i); else extraRot.put(i, q);
		}
	}

	/** Pose the bones: bind pose, overridden by the clip (blended with the fading one), under `mount`. */
	public void pose(Matrix4f mount) {
		Vector3f p = new Vector3f(), s = new Vector3f();
		Quaternionf q = new Quaternionf();
		Vector3f p2 = new Vector3f(), s2 = new Vector3f();
		Quaternionf q2 = new Quaternionf();
		float blend = prevClip != null && fadeLength > 0 ? Math.max(0, fade / fadeLength) : 0; // weight of the previous clip
		for (int i = 0; i < rig.bones.size(); i++) {
			UkRig.Bone b = rig.bones.get(i);
			p.set(b.pos());
			q.set(b.rot());
			s.set(b.scale());
			if (clip != null) sample(clip, time, b.path(), p, q, s);
			if (blend > 0) {
				p2.set(b.pos());
				q2.set(b.rot());
				s2.set(b.scale());
				sample(prevClip, prevTime, b.path(), p2, q2, s2);
				p.lerp(p2, blend);
				s.lerp(s2, blend);
				q.slerp(q2, blend).normalize();
			}
			if (overlayWeight > 0 && overlayClip.tracks.containsKey(b.path())) {
				p2.set(p);
				q2.set(q);
				s2.set(s);
				sample(overlayClip, overlayTime, b.path(), p2, q2, s2);
				p.lerp(p2, overlayWeight);
				s.lerp(s2, overlayWeight);
				q.slerp(q2, overlayWeight).normalize();
			}
			Object[] pin = pinned.get(i);
			if (pin != null) {
				UkClip pc = UkAssets.clip(rigName, (String) pin[0]);
				if (pc != null) {
					Vector3f pp = new Vector3f(b.pos());
					sample(pc, (Float) pin[1], b.path(), pp, new Quaternionf(q), new Vector3f(s));
					p.set(pp);
				}
			}
			Quaternionf extra = extraRot.get(i);
			if (extra != null) q.mul(extra);
			Vector3f es = extraScale.get(i);
			if (es != null) s.mul(es);
			Vector3f ep = extraPos.get(i);
			if (ep != null) p.add(ep);
			// a projectile prefab's root IS the moving object: its saved place and turn in the prefab (the nail's sits 46 u
			// off, tipped -90 deg) are its in-scene state, not part of the model; only its scale stays
			if (rootAtOrigin && b.parent() < 0) { p.zero(); q.identity(); }
			local[i].translationRotateScale(p, q, s);
			if (b.parent() < 0) world[i].set(mount).mul(local[i]);
			else world[i].set(world[b.parent()]).mul(local[i]);
		}
		for (int mi = 0; mi < rig.meshes.size(); mi++) {
			UkRig.Mesh m = rig.meshes.get(mi);
			Matrix4f[] sk = skin.get(mi);
			for (int j = 0; j < m.bones.length; j++) {
				int bi = m.bones[j];
				if (bi < 0) sk[j].set(mount);
				else sk[j].set(world[bi]).mul(m.bindposes[j]);
			}
		}
	}

	private static void sample(UkClip c, float t, String path, Vector3f p, Quaternionf q, Vector3f s) {
		UkClip.Track tr = c.tracks.get(path);
		if (tr == null) return;
		float f = c.frameAt(t);
		if (tr.pos != null) c.samplePos(tr, f, p);
		if (tr.rot != null) c.sampleRot(tr, f, q);
		if (tr.scale != null) c.sampleScale(tr, f, s);
	}

	/** World matrix of a bone after pose(). */
	/** min x/y/z, max x/y/z of the skinned vertices of every mesh not in hiddenMeshes, at the current pose; null if none. */
	public float[] boundsOfVisible() {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		Vector4f v = new Vector4f();
		boolean any = false;
		for (int mi = 0; mi < rig.meshes.size(); mi++) {
			if (hiddenMeshes.contains(mi)) continue;
			UkRig.Mesh m = rig.meshes.get(mi);
			Matrix4f[] sk = skin.get(mi);
			boolean skinned = m.weights.length >= m.vertexCount * 4 && m.boneIndices.length >= m.vertexCount * 4;
			for (int i = 0; i < m.vertexCount; i++) {
				double ax = 0, ay = 0, az = 0, ws = 0;
				for (int k = 0; skinned && k < 4; k++) {
					float w = m.weights[i * 4 + k];
					int bi = m.boneIndices[i * 4 + k];
					if (w <= 0 || bi < 0 || bi >= sk.length) continue;
					v.set(m.vertices[i * 3], m.vertices[i * 3 + 1], m.vertices[i * 3 + 2], 1).mul(sk[bi]);
					ax += v.x * w; ay += v.y * w; az += v.z * w; ws += w;
				}
				if (ws <= 0) { ax = m.vertices[i * 3]; ay = m.vertices[i * 3 + 1]; az = m.vertices[i * 3 + 2]; ws = 1; }
				float x = (float) (ax / ws), y = (float) (ay / ws), z = (float) (az / ws);
				b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.min(b[2], z);
				b[3] = Math.max(b[3], x); b[4] = Math.max(b[4], y); b[5] = Math.max(b[5], z);
				any = true;
			}
		}
		return any ? b : null;
	}

	/** Mean of a mesh's skinned vertices at the current pose (mount space, Unity handedness). */
	public Vector3f meshCentroid(UkRig.Mesh m) {
		int mi = rig.meshes.indexOf(m);
		Matrix4f[] sk = skin.get(mi);
		boolean skinned = m.weights.length >= m.vertexCount * 4 && m.boneIndices.length >= m.vertexCount * 4;
		Vector4f v = new Vector4f();
		double sx = 0, sy = 0, sz = 0;
		int n = 0;
		for (int i = 0; i < m.vertexCount; i++) {
			double ax = 0, ay = 0, az = 0, ws = 0;
			for (int k = 0; skinned && k < 4; k++) {
				float w = m.weights[i * 4 + k];
				int bi = m.boneIndices[i * 4 + k];
				if (w <= 0 || bi < 0 || bi >= sk.length) continue;
				v.set(m.vertices[i * 3], m.vertices[i * 3 + 1], m.vertices[i * 3 + 2], 1).mul(sk[bi]);
				ax += v.x * w; ay += v.y * w; az += v.z * w; ws += w;
			}
			if (ws <= 0) { ax = m.vertices[i * 3]; ay = m.vertices[i * 3 + 1]; az = m.vertices[i * 3 + 2]; ws = 1; }
			sx += ax / ws; sy += ay / ws; sz += az / ws; n++;
		}
		return n == 0 ? new Vector3f() : new Vector3f((float) (sx / n), (float) (sy / n), (float) (sz / n));
	}

	/** World (mount-space) matrix of the first bone with this name, or null. */
	public Matrix4f boneWorldByName(String name) {
		for (int i = 0; i < rig.bones.size(); i++) if (rig.bones.get(i).name().equals(name)) return world[i];
		return null;
	}

	public int rigBoneCount() {
		return rig.bones.size();
	}

	/** World (mount-space) matrix of bone `i` after the last pose(). */
	public Matrix4f boneWorldAt(int i) {
		return world[i];
	}

	public Matrix4f boneWorld(String path) {
		int i = rig.boneIndex(path);
		return i < 0 ? null : world[i];
	}

	/**
	 * Skin and emit every mesh. `view` maps Unity camera space to Minecraft's view space (the caller sets
	 * up the matrix stack: Unity +Z forward becomes GL -Z). Front faces are flipped by that mirror, so the
	 * caller picks a no-cull layer.
	 */
	public void render(MatrixStack matrices, VertexConsumerProvider buffers, int light, int overlay, Identifier texturePath, boolean cull) {
		boolean debugBounds = this.debugBounds;
		this.debugBounds = false;
		Matrix4f pm = matrices.peek().getPositionMatrix();
		Matrix3f nm = matrices.peek().getNormalMatrix();
		Vector4f v = new Vector4f(), acc = new Vector4f();
		Vector3f n = new Vector3f(), nacc = new Vector3f();
		for (int mi = 0; mi < rig.meshes.size(); mi++) {
			if (hiddenMeshes.contains(mi)) continue;
			Float malpha = meshAlpha.get(mi);
			if (malpha != null && malpha <= 0.004f) continue;
			UkRig.Mesh m = rig.meshes.get(mi);
			Matrix4f[] sk = skin.get(mi);
			// skin all vertices once
			float[] sv = new float[m.vertexCount * 3], sn = new float[m.vertexCount * 3];
			boolean skinned = m.weights.length >= m.vertexCount * 4 && m.boneIndices.length >= m.vertexCount * 4;
			for (int i = 0; i < m.vertexCount; i++) {
				acc.set(0, 0, 0, 0);
				nacc.set(0, 0, 0);
				float wsum = 0;
				for (int k = 0; skinned && k < 4; k++) {
					float w = m.weights[i * 4 + k];
					if (w <= 0) continue;
					int bi = m.boneIndices[i * 4 + k];
					if (bi < 0 || bi >= sk.length) continue;
					Matrix4f M = sk[bi];
					v.set(m.vertices[i * 3], m.vertices[i * 3 + 1], m.vertices[i * 3 + 2], 1).mul(M);
					acc.add(v.x * w, v.y * w, v.z * w, 0);
					n.set(m.normals.length > 0 ? m.normals[i * 3] : 0, m.normals.length > 0 ? m.normals[i * 3 + 1] : 1, m.normals.length > 0 ? m.normals[i * 3 + 2] : 0);
					M.transformDirection(n);
					nacc.add(n.x * w, n.y * w, n.z * w);
					wsum += w;
				}
				if (wsum <= 0) {
					acc.set(m.vertices[i * 3], m.vertices[i * 3 + 1], m.vertices[i * 3 + 2], 1);
					nacc.set(0, 1, 0);
				}
				sv[i * 3] = acc.x; sv[i * 3 + 1] = acc.y; sv[i * 3 + 2] = acc.z;
				if (nacc.lengthSquared() > 1e-8) nacc.normalize();
				sn[i * 3] = nacc.x; sn[i * 3 + 1] = nacc.y; sn[i * 3 + 2] = nacc.z;
			}
			if (debugBounds && m.vertexCount > 0) {
				float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
				for (int i = 0; i < m.vertexCount; i++) { x0 = Math.min(x0, sv[i * 3]); x1 = Math.max(x1, sv[i * 3]); y0 = Math.min(y0, sv[i * 3 + 1]); y1 = Math.max(y1, sv[i * 3 + 1]); z0 = Math.min(z0, sv[i * 3 + 2]); z1 = Math.max(z1, sv[i * 3 + 2]); }
				UltraCraft.LOG.info("[rig] {} mesh {} bounds x {}..{} y {}..{} z {}..{} alpha={} color={} subs={} idx={} tex={} skinned={} bones={} sk={}", rigName, m.name, x0, x1, y0, y1, z0, z1,
					meshAlpha.get(mi), meshColor.get(mi) == null ? null : java.util.Arrays.toString(meshColor.get(mi)), m.submeshes.size(), m.indices.length, m.texture, skinned, java.util.Arrays.toString(m.bones), sk.length);
			}
			for (int subIdx = 0; subIdx < m.submeshes.size(); subIdx++) {
				UkRig.SubMesh sub = m.submeshes.get(subIdx);
				String tex = meshTexture.containsKey(mi) ? meshTexture.get(mi)
					: sub.material() != null && materialTexture.containsKey(sub.material()) ? materialTexture.get(sub.material())
					: sub.texture() != null ? sub.texture() : m.texture;
				Identifier id = tex != null ? new Identifier(texturePath.getNamespace(), texturePath.getPath() + tex + ".png") : new Identifier("minecraft", "textures/misc/white.png");
				Float ma = meshAlpha.get(mi);
				float[] sc = sub.material() != null ? materialColor.get(sub.material()) : null;
				if (sc != null && sc[3] <= 0.004f) continue;
				boolean trans = translucent || ma != null || sc != null;
				VertexConsumer vc = buffers.getBuffer(layerOverride != null ? layerOverride.apply(id) : trans ? (sc != null ? RenderLayer.getEntityTranslucentEmissive(id) : RenderLayer.getEntityTranslucent(id)) : cull ? RenderLayer.getEntityCutout(id) : RenderLayer.getEntityCutoutNoCull(id));
				int end = Math.min(m.indices.length, sub.first() + sub.count());
				// a muzzle light on the gun: the base pass at full brightness too (shader packs dim the additive pass)
				int ml = meshEmissive.contains(mi) || sc != null || lightAdd > 0.3f ? 0xF000F0 : light;
				subColor = sc;
				if (debugBounds && end >= 3) {
					int i0 = m.indices[sub.first()], i1 = m.indices[sub.first() + 1], i2 = m.indices[sub.first() + 2];
					Vector4f c0 = new Vector4f(sv[i0 * 3], sv[i0 * 3 + 1], -sv[i0 * 3 + 2], 1).mul(pm), c1 = new Vector4f(sv[i1 * 3], sv[i1 * 3 + 1], -sv[i1 * 3 + 2], 1).mul(pm), c2 = new Vector4f(sv[i2 * 3], sv[i2 * 3 + 1], -sv[i2 * 3 + 2], 1).mul(pm);
					UltraCraft.LOG.info("[rig] {} mesh {} sub {} tri0 idx {} {} {} -> pm {} | {} | {} layer={} tint={},{},{},{} meshColor={}", rigName, m.name, sub.material(), i0, i1, i2, c0, c1, c2, trans ? "translucent" : cull ? "cutout" : "cutoutNoCull", tintR, tintG, tintB, tintA, meshColor.get(mi));
				}
				for (int t = sub.first(); t + 2 < end; t += 3) {
					// mirrored handedness: reverse the winding so Unity's front faces stay front faces
					tri(vc, pm, nm, m, sv, sn, t, ml, overlay);
				}
				subColor = null;
				if (layerOverride != null) continue;
				if (lightAdd > 0.003f) {
					// up to 2: one additive pass per whole unit (a vertex colour caps at 1)
					int passes = (int) Math.min(10, Math.ceil(lightAdd));
					float per = lightAdd / passes;
					VertexConsumer lv = buffers.getBuffer(RenderLayer.getEyes(id));
					subColor = new float[] {Math.min(1f, lightR * per), Math.min(1f, lightG * per), Math.min(1f, lightB * per), 1f};
					for (int pass = 0; pass < passes; pass++) {
						for (int t = sub.first(); t + 2 < end; t += 3) {
							tri(lv, pm, nm, m, sv, sn, t, 0xF000F0, overlay);
						}
					}
					subColor = null;
				}
				if (pointAdd > 0.003f && pointDir != null) {
					int passes = (int) Math.min(10, Math.ceil(pointAdd));
					float per = pointAdd / passes;
					VertexConsumer pv = buffers.getBuffer(RenderLayer.getEyes(id));
					subColor = new float[] {Math.min(1f, pointR * per), Math.min(1f, pointG * per), Math.min(1f, pointB * per), 1f};
					subLightDir = pointDir;
					for (int pass = 0; pass < passes; pass++) {
						for (int t = sub.first(); t + 2 < end; t += 3) {
							tri(pv, pm, nm, m, sv, sn, t, 0xF000F0, overlay);
						}
					}
					subLightDir = null;
					subColor = null;
				}
				if (whiteAdd > 0.003f) {
					// flat white on top (not the texture): pushes every colour toward white
					int passes = (int) Math.min(10, Math.ceil(whiteAdd));
					float per = Math.min(1f, whiteAdd / passes);
					VertexConsumer wv = buffers.getBuffer(RenderLayer.getEyes(FLAT_WHITE));
					subColor = new float[] {per, per, per, 1f};
					for (int pass = 0; pass < passes; pass++) {
						for (int t = sub.first(); t + 2 < end; t += 3) {
							tri(wv, pm, nm, m, sv, sn, t, 0xF000F0, overlay);
						}
					}
					subColor = null;
				}
				Object[] so = subEmissive.get(((long) mi << 32) | subIdx);
				if (so != null) {
					Identifier soId = new Identifier(texturePath.getNamespace(), texturePath.getPath() + so[0] + ".png");
					VertexConsumer ev = buffers.getBuffer(so.length > 2 && Boolean.TRUE.equals(so[2]) ? com.ultracraft.client.render.UkLayers.glowDepth(soId) : RenderLayer.getEntityTranslucentEmissive(soId));
					subColor = (float[]) so[1];
					for (int t = sub.first(); t + 2 < end; t += 3) {
						tri(ev, pm, nm, m, sv, sn, t, 0xF000F0, overlay);
					}
					subColor = null;
				}
			}
			// emissive overlay (the gun's lights): the whole mesh again with the emissive map tinted, e.g. by variation
			String eo = layerOverride != null ? null : emissiveOverlay.get(mi);
			if (eo != null) {
				float[] ec = emissiveColor.get(mi);
				Identifier eid = new Identifier(texturePath.getNamespace(), texturePath.getPath() + eo + ".png");
				// (additive: added onto the mesh's own texture, like a heat glow; else laid over it)
				VertexConsumer vc = buffers.getBuffer(emissiveAdditive.contains(mi) ? com.ultracraft.client.render.UkLayers.glowDepth(eid) : RenderLayer.getEntityTranslucentEmissive(eid));
				subColor = ec;
				for (int t = 0; t + 2 < m.indices.length; t += 3) {
					tri(vc, pm, nm, m, sv, sn, t, 0xF000F0, overlay);
				}
				subColor = null;
			}
		}
	}

	/** Per-submesh emissive overlay: (mesh index << 32 | submesh index) → {texture, rgba}. V1's wing lights. */
	private final java.util.Map<Long, Object[]> subEmissive = new java.util.HashMap<>();

	/** Glow an emissive map (alpha mask) over one submesh in a colour; texture null turns it off. */
	public void setSubmeshEmissive(String mesh, int sub, String texture, float r, float g, float b) {
		setSubmeshEmissive(mesh, sub, texture, r, g, b, false);
	}

	/** @param additive added onto the submesh (a coloured glow map on black, like the hammer's dial) instead of laid over */
	public void setSubmeshEmissive(String mesh, int sub, String texture, float r, float g, float b, boolean additive) {
		int i = meshIndex(mesh);
		if (i < 0) return;
		long key = ((long) i << 32) | sub;
		if (texture == null) subEmissive.remove(key);
		else subEmissive.put(key, new Object[] {texture, new float[] {r, g, b, 1f}, additive});
	}

	/** Emissive map drawn over a mesh (alpha = glow), tinted: the variation colour on the revolver's lights. */
	private final java.util.Map<Integer, String> emissiveOverlay = new java.util.HashMap<>();
	private final java.util.Map<Integer, float[]> emissiveColor = new java.util.HashMap<>();

	/** Emissive overlay at an intensity (alpha 0..1): the game's _EmissiveIntensity on a property block. */
	/** Meshes whose emissive overlay is added on (colour x alpha) instead of laid over. */
	private final java.util.Set<Integer> emissiveAdditive = new java.util.HashSet<>();

	/** setEmissiveOverlay, added onto the mesh's own colours when `additive` (a glow that keeps the texture). */
	public void setEmissiveOverlay(String mesh, String texture, float r, float g, float b, float a, boolean additive) {
		setEmissiveOverlay(mesh, texture, r, g, b, a);
		int i = meshIndex(mesh);
		if (i < 0) return;
		if (additive && texture != null) emissiveAdditive.add(i); else emissiveAdditive.remove(i);
	}

	public void setEmissiveOverlay(String mesh, String texture, float r, float g, float b, float a) {
		setEmissiveOverlay(mesh, texture, r, g, b);
		int i = meshIndex(mesh);
		if (i >= 0 && texture != null) emissiveColor.put(i, new float[] {r, g, b, Math.max(0f, Math.min(1f, a))});
	}

	public void setEmissiveOverlay(String mesh, String texture, float r, float g, float b) {
		int i = meshIndex(mesh);
		if (i < 0) return;
		if (texture == null) { emissiveOverlay.remove(i); return; }
		emissiveOverlay.put(i, texture);
		emissiveColor.put(i, new float[] {r, g, b, 1f});
	}

	/** Tint multiplied into every vertex (the game's GunColorGetter / material colour); default white. */
	public float tintR = 1, tintG = 1, tintB = 1, tintA = 1;
	/** A slow wave through the vertices, in view space (the view model under water); 0 amplitude is off. */
	public float wobbleAmp, wobbleFreq, wobbleWave, wobbleTime;
	/** A light on the whole rig, 0..1 (a muzzle flash's point light): drawn as an additive pass in lightR/G/B. */
	public float lightAdd;
	/**
	 * A point light beside the rig (a Rocket's / Grenade's): added like lightAdd, but each vertex by how much its normal
	 * faces pointDir (view space, Minecraft's axes: x right, y up, -z ahead) - a rocket under V1 lights the gun's underside.
	 */
	public float pointAdd, pointR = 1, pointG = 1, pointB = 1;
	public org.joml.Vector3f pointDir;
	/** while the point light's pass is drawn: its direction, and a scratch vector for the turned normal */
	private org.joml.Vector3f subLightDir;
	private final org.joml.Vector3f scratchN = new org.joml.Vector3f();
	/** mesh index → {near, far, floor}: alpha from `floor` at `near` (view units from the camera) up to 1 at `far`. */
	private final java.util.Map<Integer, float[]> distFade = new java.util.HashMap<>();

	/** Fade a mesh by its distance from the camera (null clears): none nearer than `near`, full beyond `far`. */
	public void setDistanceFade(String mesh, float near, float far, float floor) {
		int i = meshIndex(mesh);
		if (i < 0) return;
		distFade.put(i, new float[] {near, far, floor});
	}

	/** Pose the root bone at the origin, unturned (projectiles: the entity places and turns the model). */
	public boolean rootAtOrigin;

	/** Flat white added on top, 0..1 per unit (1 = solid white): the parry's blown-out look. */
	public float whiteAdd;
	private static final Identifier FLAT_WHITE = new Identifier("minecraft", "textures/misc/white.png");
	/** When set, every base pass uses this layer and the overlay passes are skipped (e.g. a depth-only redraw). */
	public java.util.function.Function<Identifier, RenderLayer> layerOverride;
	public float lightR = 1, lightG = 0.93f, lightB = 0.72f;
	/**
	 * Per-material colour override by material name (RGBA): the game's _TintColor on a shared material, e.g. the
	 * shotgun's HeatSinkHeat glow. The submesh is then drawn emissive and translucent with this colour; alpha 0 hides it.
	 */
	public final java.util.Map<String, float[]> materialColor = new java.util.HashMap<>();
	private float[] subColor;

	public void setMaterialColor(String material, float r, float g, float b, float a) {
		materialColor.put(material, new float[] {r, g, b, a});
	}

	/** Mesh indices not drawn (e.g. an effect's helper spheres). */
	public final java.util.Set<Integer> hiddenMeshes = new java.util.HashSet<>();
	/** Draw with the translucent entity layer (alpha blending) instead of cutout. */
	public boolean translucent;
	/** Per-mesh alpha override (a MaterialPropertyBlock _Color.a in the game, e.g. the Nailgun's barrel heat glow). */
	public final java.util.Map<Integer, Float> meshAlpha = new java.util.HashMap<>();
	/** Per-mesh texture override (the Revolver's battery monitor swaps _MainTex). */
	public final java.util.Map<Integer, String> meshTexture = new java.util.HashMap<>();
	/** Per-material texture override (ChangeMaterials swapping one material for another, e.g. a levelled-up rocket's stripes). */
	public final java.util.Map<String, String> materialTexture = new java.util.HashMap<>();
	/** Per-mesh RGB multiplier (a MaterialPropertyBlock _Color / _EmissiveColor). */
	public final java.util.Map<Integer, float[]> meshColor = new java.util.HashMap<>();
	/** Per-bone extra local scale (the Piercer's ChargeEffect grows with the charge). */
	private final java.util.Map<Integer, Vector3f> extraScale = new java.util.HashMap<>();
	private final java.util.Map<Integer, Vector3f> extraPos = new java.util.HashMap<>();

	/** bone index -> {clip name, time}: the bone's position taken from that clip there instead of the playing one. */
	private final java.util.Map<Integer, Object[]> pinned = new java.util.HashMap<>();

	/** Hold a bone's position at `clip`'s pose at `time` whatever plays (null clip lets it go); false if no such bone. */
	public boolean pinPosition(String path, String clip, float time) {
		int i = rig.boneIndex(path);
		if (i < 0) return false;
		if (clip == null) pinned.remove(i); else pinned.put(i, new Object[] {clip, time});
		return true;
	}

	/** Extra local translation on a bone (parent space), for tuning static attachments by eye. */
	public void setBoneOffset(String path, Vector3f offset) {
		int i = rig.boneIndex(path);
		if (i < 0) return;
		if (offset == null) extraPos.remove(i); else extraPos.put(i, offset);
	}

	public int meshIndex(String name) {
		for (int i = 0; i < rig.meshes.size(); i++) if (rig.meshes.get(i).name.equals(name)) return i;
		return -1;
	}

	/** Override the texture on every mesh (null clears): e.g. the spent coin's Coin material. */
	public void setAllMeshTexture(String texture) {
		for (int i = 0; i < rig.meshes.size(); i++) {
			if (texture == null) meshTexture.remove(i); else meshTexture.put(i, texture);
		}
	}

	public void setMeshTexture(String mesh, String texture) {
		int i = meshIndex(mesh);
		if (i < 0) return;
		if (texture == null) meshTexture.remove(i); else meshTexture.put(i, texture);
	}

	public void setMeshColor(String mesh, float r, float g, float b) {
		int i = meshIndex(mesh);
		if (i < 0) return;
		meshColor.put(i, new float[] {r, g, b});
	}

	/** Meshes drawn at full brightness (the game's emissive materials). */
	public final java.util.Set<Integer> meshEmissive = new java.util.HashSet<>();

	public void setMeshEmissive(String mesh, boolean on) {
		int i = meshIndex(mesh);
		if (i < 0) return;
		if (on) meshEmissive.add(i); else meshEmissive.remove(i);
	}

	public void setBoneScale(String path, Vector3f scale) {
		int i = rig.boneIndex(path);
		if (i < 0) return;
		if (scale == null) extraScale.remove(i); else extraScale.put(i, scale);
	}

	/** Set the alpha of every mesh whose name passes `test` (null = clear). */
	public void setMeshAlpha(java.util.function.Predicate<String> test, Float alpha) {
		for (int i = 0; i < rig.meshes.size(); i++) if (test.test(rig.meshes.get(i).name)) { if (alpha == null) meshAlpha.remove(i); else meshAlpha.put(i, alpha); }
	}

	/**
	 * Drawn through a mirror (the off-hand gun, x scale -1): the winding goes the other way, so the faces stay front
	 * faces. Shader packs light a back face with its normal turned around, which left the mirrored gun dark.
	 */
	public boolean mirrored;

	/** Triangle t of the mesh, as the quad the entity layers take (the last vertex repeated); Unity's handedness reversed. */
	private void tri(VertexConsumer vc, Matrix4f pm, Matrix3f nm, UkRig.Mesh m, float[] sv, float[] sn, int t, int light, int overlay) {
		int a = mirrored ? t : t + 2, c = mirrored ? t + 2 : t;
		emit(vc, pm, nm, m, sv, sn, m.indices[a], light, overlay);
		emit(vc, pm, nm, m, sv, sn, m.indices[t + 1], light, overlay);
		emit(vc, pm, nm, m, sv, sn, m.indices[c], light, overlay);
		emit(vc, pm, nm, m, sv, sn, m.indices[c], light, overlay);
	}

	private void emit(VertexConsumer vc, Matrix4f pm, Matrix3f nm, UkRig.Mesh m, float[] sv, float[] sn, int i, int light, int overlay) {
		float x = sv[i * 3], y = sv[i * 3 + 1], z = -sv[i * 3 + 2];
		float nx = sn[i * 3], ny = sn[i * 3 + 1], nz = -sn[i * 3 + 2];
		float u = m.uvs.length > 0 ? m.uvs[i * 2] : 0, w = m.uvs.length > 0 ? 1 - m.uvs[i * 2 + 1] : 0;
		float r = tintR, g = tintG, b = tintB, a = tintA;
		if (m.color != null) { r *= m.color[0]; g *= m.color[1]; b *= m.color[2]; a *= m.color[3]; }
		int mi2 = rig.meshes.indexOf(m);
		Float ma = meshAlpha.get(mi2);
		if (ma != null) a *= ma;
		float[] df = distFade.get(mi2);
		if (df != null) {
			// _EmissiveFogStart / End: the vertex's distance from the camera picks how much of it shows (near → none)
			org.joml.Vector4f vp = new org.joml.Vector4f(x, y, z, 1).mul(pm);
			float d = (float) Math.sqrt(vp.x * vp.x + vp.y * vp.y + vp.z * vp.z);
			float f = Math.max(0f, Math.min(1f, (d - df[0]) / Math.max(1e-5f, df[1] - df[0])));
			a *= df[2] + (1 - df[2]) * f;
		}
		float[] mc = meshColor.get(mi2);
		if (mc != null) { r *= mc[0]; g *= mc[1]; b *= mc[2]; }
		if (subColor != null) { r = subColor[0]; g = subColor[1]; b = subColor[2]; a *= subColor[3]; }
		if (subLightDir != null) {
			// the point light's pass: as much as this vertex faces it (its normal turned into view space)
			float f = Math.max(0f, nm.transform(scratchN.set(nx, ny, nz)).normalize().dot(subLightDir));
			r *= f; g *= f; b *= f;
		}
		if (wobbleAmp > 0) {
			org.joml.Vector4f vp = new org.joml.Vector4f(x, y, z, 1).mul(pm);
			vp.x += wobbleAmp * (float) Math.sin(wobbleTime * wobbleFreq + vp.y * wobbleWave);
			vp.y += wobbleAmp * (float) Math.sin(wobbleTime * wobbleFreq * 1.3f + vp.x * wobbleWave);
			vc.vertex(vp.x, vp.y, vp.z).color(r, g, b, a).texture(u, w).overlay(overlay).light(light).normal(nm, nx, ny, nz).next();
			return;
		}
		vc.vertex(pm, x, y, z).color(r, g, b, a).texture(u, w).overlay(overlay).light(light).normal(nm, nx, ny, nz).next();
	}
}
