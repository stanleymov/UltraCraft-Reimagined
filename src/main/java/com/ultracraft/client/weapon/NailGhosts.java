package com.ultracraft.client.weapon;

import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.weapon.Damage;
import com.ultracraft.weapon.ServerTune;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V1's own nails as V1 sees them: the server's nail reaches this client a tick or two after the shot, so its trail
 * started where the gun had been. These are simulated here from the barrel on the frame of the shot, with the server's
 * nail physics (gravity, the terrain contact, resting, RemoveTime) on the same path (the client picks the spread and
 * the server follows it), and drawn with the nail model and trail; the server's copy of an own nail in flight is not
 * drawn. A nail that meets a mob ends here: the server's nail sticks in it and shows from then on.
 */
public final class NailGhosts {
	private static final double DT = 0.05;
	private static final Identifier WHITE = new Identifier("minecraft", "textures/misc/white.png");

	private static final class Ghost {
		final ProjectileKind kind;
		Vec3d pos, prev, vel;
		float yaw, pitch, prevYaw, prevPitch;
		/** The direction it travels (the model points its tip that way), this tick and the last. */
		Vec3d dir = new Vec3d(0, 0, 1), prevDir = new Vec3d(0, 0, 1);
		boolean hit, rest;
		float removeIn, age;
		/** The camera when it was fired: the nail's early, near positions move with the player (no parallax at the gun). */
		Vec3d camAtSpawn;
		final ArrayDeque<double[]> trail = new ArrayDeque<>();

		Ghost(ProjectileKind kind, Vec3d pos, Vec3d vel) {
			this.kind = kind;
			this.pos = this.prev = pos;
			this.vel = vel;
			face(vel);
			prevYaw = yaw;
			prevPitch = pitch;
			prevDir = dir;
		}

		void face(Vec3d v) {
			if (v.lengthSquared() < 1e-9) return;
			double h = Math.sqrt(v.x * v.x + v.z * v.z);
			yaw = (float) Math.toDegrees(MathHelper.atan2(v.x, v.z));
			pitch = (float) Math.toDegrees(MathHelper.atan2(v.y, h));
			dir = v.normalize();
		}
	}

	private static final List<Ghost> ghosts = new ArrayList<>();
	private static final Map<ProjectileKind, RigInstance> rigs = new EnumMap<>(ProjectileKind.class);

	private NailGhosts() {}

	public static boolean enabled() {
		return Tune.on("proj.nailPredict");
	}

	/** UKProjectileEntity.spawn on this side: at `pos`, `dirUnit` x speed u/s (velocity kept in blocks per tick). */
	public static void spawn(ProjectileKind kind, Vec3d pos, Vec3d dirUnit, double speedUnits) {
		if (!enabled()) return;
		Ghost g = new Ghost(kind, pos, dirUnit.normalize().multiply(speedUnits * Damage.SCALE_BLOCKS * DT));
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		g.camAtSpawn = cam;
		// the trail begins at the barrel on the frame of the shot (by its first drawn frame the nail is already metres out)
		g.trail.addLast(new double[] {pos.x, pos.y, pos.z, com.ultracraft.client.uk.ViewModel.gameSeconds(), cam.x, cam.y, cam.z});
		ghosts.add(g);
		if (ghosts.size() > 400) ghosts.remove(0);
	}

	/** One server tick of UKProjectileEntity.tick for a nail. */
	public static void tick(MinecraftClient c) {
		if (c.world == null || c.player == null || com.ultracraft.client.uk.ViewModel.paused() || com.ultracraft.client.v1.V1.INSTANCE.frozen()) {
			if (c.world == null) ghosts.clear();
			return;
		}
		// stuck magnets' fields: a nail entering one is held there (the server's nail is drawn from then on)
		List<Vec3d> fields = new ArrayList<>();
		for (Entity e : c.world.getEntities())
			if (e instanceof com.ultracraft.entity.UKProjectileEntity m && m.kind() == ProjectileKind.MAGNET && m.isStuck()) fields.add(m.magnetCenter());
		double fr = com.ultracraft.weapon.MagnetField.RADIUS;
		for (Iterator<Ghost> it = ghosts.iterator(); it.hasNext(); ) {
			Ghost g = it.next();
			boolean held = false;
			for (Vec3d f : fields) if (f.squaredDistanceTo(g.pos) <= fr * fr) { held = true; break; }
			if (held) { it.remove(); continue; }
			g.prev = g.pos;
			g.prevYaw = g.yaw;
			g.prevPitch = g.pitch;
			g.prevDir = g.dir;
			g.age += DT;
			if (g.age > g.kind.life) { it.remove(); continue; }
			if (g.hit && (g.removeIn -= DT) <= 0) { it.remove(); continue; }
			if (g.rest) continue;
			Vec3d v = g.vel.add(0, -9.81 * Damage.SCALE_BLOCKS * DT * DT, 0);
			Vec3d start = g.pos, end = start.add(v);
			double radius = g.kind.scale * 0.5 * Damage.SCALE_BLOCKS + 0.05;
			double best = Double.MAX_VALUE;
			if (!g.hit) for (Entity e : c.world.getOtherEntities(c.player, new Box(start, end).expand(radius + 0.5), en -> en instanceof LivingEntity && en.isAlive())) {
				Optional<Vec3d> p = e.getBoundingBox().expand(radius).raycast(start, end);
				if (p.isEmpty() && e.getBoundingBox().expand(radius).contains(start)) p = Optional.of(start);
				if (p.isPresent()) best = Math.min(best, p.get().squaredDistanceTo(start));
			}
			BlockHitResult block = c.world.raycast(com.ultracraft.weapon.Plants.ctx(start, end, c.player));
			boolean blockHit = block.getType() != HitResult.Type.MISS && block.getPos().squaredDistanceTo(start) < best;
			if (blockHit) bounce(g, block, v);
			else if (best < Double.MAX_VALUE) it.remove(); // into a mob: the server's nail takes over (it sticks in)
			else {
				g.vel = v;
				g.pos = end;
				g.face(v);
			}
		}
	}

	/** UKProjectileEntity.nailBounce. */
	private static void bounce(Ghost g, BlockHitResult block, Vec3d v) {
		Vec3d p = block.getPos();
		Vec3d n = Vec3d.of(block.getSide().getVector());
		if (!g.hit) {
			g.hit = true;
			g.removeIn = (float) ServerTune.get("nail.decay", 4.0);
		}
		double e = ServerTune.get("nail.bounce", 0.1), mu = ServerTune.get("nail.friction", 0.6);
		double vn = v.dotProduct(n);
		Vec3d vt = v.subtract(n.multiply(vn));
		double jn = -(1 + e) * vn, vtLen = vt.length();
		Vec3d slide = vtLen > 1e-9 ? vt.multiply(Math.max(0, 1 - mu * Math.max(0, jn) / vtLen)) : Vec3d.ZERO;
		slide = slide.multiply(1 - Math.max(0, Math.min(1, ServerTune.get("nail.slideLoss", 0.5))));
		Vec3d r = slide.add(n.multiply(-e * vn));
		g.pos = p.add(n.multiply(0.02));
		if (r.length() < ServerTune.get("nail.restSpeed", 0.02) && n.y > 0.5) {
			g.vel = Vec3d.ZERO;
			g.rest = true;
		} else {
			g.vel = r;
			if (r.lengthSquared() > 1e-8) g.face(r);
		}
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ghosts.isEmpty()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		Vec3d cam = e.getCamera().getPos();
		float td = e.getPartialTick();
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		double now = com.ultracraft.client.uk.ViewModel.gameSeconds();
		for (Ghost g : ghosts) {
			Vec3d pos = anchored(g.prev.lerp(g.pos, td), g.camAtSpawn, cam, g.hit ? 0 : fade(g));
			RigInstance inst = rigs.computeIfAbsent(g.kind, k -> {
				UkRig r = UkAssets.rig(k.rig);
				if (r == null) return null;
				RigInstance ri = new RigInstance(k.rig, r);
				ri.rootAtOrigin = true;
				return ri;
			});
			if (inst != null) {
				ms.push();
				ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
				aimNail(ms, g.prevDir, g.dir, td);
				float s = (float) (Damage.SCALE_BLOCKS * Tune.get("proj.scale"));
				ms.scale(s, s, s);
				inst.tintR = inst.tintG = inst.tintB = 1f;
				if (g.kind == ProjectileKind.NAIL_HEATED) { inst.tintR = 1f; inst.tintG = 0.55f; inst.tintB = 0.2f; }
				inst.pose(new Matrix4f());
				inst.render(ms, buffers, 0xF000F0, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
				ms.pop();
			}
			trail(g, pos, cam, now, ms, buffers);
		}
		buffers.draw();
	}

	/**
	 * Turn the nail model so its tip points along its travel: the mesh's tip is at -z, which RigInstance's z mirror makes
	 * +z here, so +z is turned onto the direction (blended between the last tick's and this tick's).
	 */
	public static void aimNail(MatrixStack ms, Vec3d from, Vec3d to, float td) {
		Vec3d d = from.lerp(to, td);
		if (d.lengthSquared() < 1e-8) d = to;
		d = d.normalize();
		ms.multiply(new org.joml.Quaternionf().rotationTo(0, 0, 1, (float) d.x, (float) d.y, (float) d.z));
		if (Tune.on("proj.nailFlip")) ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
	}

	/** The nail's TrailRenderer (the same as ProjectileRenderer.colorTrail). */
	private static void trail(Ghost g, Vec3d pos, Vec3d cam, double now, MatrixStack ms, VertexConsumerProvider buffers) {
		double life;
		float w, r, gg, b, a0;
		if (g.kind == ProjectileKind.NAIL_HEATED) { life = 1.0; w = 0.4f; r = 1f; gg = 0.592f; b = 0f; a0 = 0.686f; }
		else if (g.kind == ProjectileKind.NAIL_FODDER) { life = 0.25; w = 0.2f; r = 0.251f; gg = 0.906f; b = 1f; a0 = 0.49f; }
		else { life = 0.25; w = 0.2f; r = 1f; gg = 1f; b = 1f; a0 = 0.49f; }
		ArrayDeque<double[]> q = g.trail;
		Vec3d raw = g.prev.lerp(g.pos, MinecraftClient.getInstance().getTickDelta());
		if (q.isEmpty() || q.peekLast()[3] < now - 1.0 / 120) q.addLast(new double[] {raw.x, raw.y, raw.z, now, g.camAtSpawn.x, g.camAtSpawn.y, g.camAtSpawn.z});
		while (!q.isEmpty() && q.peekFirst()[3] < now - life) q.pollFirst();
		if (q.size() < 2) return;
		VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.glowDepth(WHITE));
		float hw = (float) (w * Damage.SCALE_BLOCKS / 2);
		float amax = a0 * (float) Tune.get("proj.nailTrailAlpha");
		ms.push();
		ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		List<double[]> pts = new ArrayList<>(q);
		Vec3d prevSide = null;
		for (int i = pts.size() - 1; i > 0; i--) {
			double[] pa0 = pts.get(i), pb0 = pts.get(i - 1);
			Vec3d pa = anchored(pa0, cam, fade(g)).subtract(pos), pb = anchored(pb0, cam, fade(g)).subtract(pos);
			Vec3d dir = pa.subtract(pb);
			if (dir.lengthSquared() < 1e-10) continue;
			Vec3d side = dir.crossProduct(cam.subtract(pos).subtract(pa));
			side = side.lengthSquared() < 1e-12 ? (prevSide != null ? prevSide : new Vec3d(0, 1, 0)) : side.normalize();
			float ta = (float) Math.min(1, (now - pa0[3]) / life), tb = (float) Math.min(1, (now - pb0[3]) / life);
			Vec3d sa = (prevSide != null ? prevSide : side).multiply(hw), sb = side.multiply(hw);
			v(vc, m, pa.add(sa), r, gg, b, amax * (1 - ta)); v(vc, m, pa.subtract(sa), r, gg, b, amax * (1 - ta));
			v(vc, m, pb.subtract(sb), r, gg, b, amax * (1 - tb)); v(vc, m, pb.add(sb), r, gg, b, amax * (1 - tb));
			prevSide = side;
		}
		ms.pop();
	}

	/**
	 * A point recorded with the camera at `then`, drawn now: the nearer it was to the camera, the more it moves along with
	 * the camera since (fully at the gun, not at all from proj.nailAnchor blocks out). Without this the trail's start, a
	 * block in front of the eye, swung across the screen with every step while the nail itself was far ahead.
	 */
	private static Vec3d anchored(Vec3d p, Vec3d then, Vec3d now, double k) {
		double reach = Tune.get("proj.nailAnchor");
		if (reach <= 0 || then == null || k <= 0) return p;
		double w = k * Math.max(0, Math.min(1, 1 - p.distanceTo(then) / reach));
		return w <= 0 ? p : p.add(now.subtract(then).multiply(w));
	}

	private static Vec3d anchored(double[] pt, Vec3d now, double k) {
		return anchored(new Vec3d(pt[0], pt[1], pt[2]), new Vec3d(pt[4], pt[5], pt[6]), now, k);
	}

	/** The anchoring lets go over the nail's first 0.4 s (a nail lying near you does not follow you about). */
	private static double fade(Ghost g) {
		return Math.max(0, 1 - g.age / 0.4);
	}

	private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	private static final double PREDICT = Tune.def("proj.nailPredict", 1, "1 = your own nails are drawn from your gun the frame you fire (the server's copy in flight is hidden)");
	private static final double ANCHOR = Tune.def("proj.nailAnchor", 8, "your nails and trails: points this many blocks from where you fired or nearer move along with you (0 = fixed in the world)");
	private static final double REST = Tune.def("nail.restSpeed", 0.02, "nails stop on the ground below this speed, blocks per tick");
}
