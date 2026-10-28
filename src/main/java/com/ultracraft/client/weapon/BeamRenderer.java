package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.v1.V1;
import com.ultracraft.net.BeamPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Revolver beams: ULTRAKILL's `Revolver Beam` is a LineRenderer, width 0.25 u shrinking 1.5 u/s
 * (RevolverBeam.Update), colour white → (1, 0.807, 0) along its length, opaque (material White2, alpha 1: it
 * stays solid while it thins). Drawn unlit with depth (bright under shader packs) as two crossed
 * camera-facing ribbons so it reads from any angle.
 */
public final class BeamRenderer {
	private static final class Beam {
		final int kind;
		final Vec3d start, end;
		float width;
		/** ElectricityLine: frame and colour (white..cyan) picked again every 0.05 s. */
		int arcFrame;
		float arcColor, arcT;

		boolean rail() {
			return kind == 2 || kind == 5;
		}
		Beam(BeamPayload m) {
			kind = m.kind();
			start = m.start();
			end = m.end();
			width = m.kind() == 1 || m.kind() == 4 ? 0.5f : rail() ? 1f : 0.25f;
		}
	}

	private static final List<Beam> beams = new ArrayList<>();
	private static long lastNanos;
	private static final double WIDTH = Tune.def("beam.width", 1.25, "multiplier on the revolver beam width (game: 1)");
	private static final double SHRINK = Tune.def("beam.shrink", 1.1, "revolver beam width shrink, u/s (game: 1.5; lower = it stays longer)");
	private static final double FROM_BARREL = Tune.def("beam.fromBarrel", 1, "1 = your revolver beams start at the gun's barrel (the game's alternateStartPoint); 0 = a fixed point beside the crosshair");
	private static final double HOLD = Tune.def("debug.beamHold", 0, "1 = beams never fade (for screenshots)");
	private static final double END_YELLOW = Tune.def("beam.endYellow", 1, "1 = the game's gradient (white at the gun to (1, 0.81, 0) at the end), 0 = white all along");

	private BeamRenderer() {}

	public static void add(BeamPayload m) {
		if (Tune.on("debug.weapons")) UltraCraft.LOG.info("[weapons] beam kind={} {} -> {}", m.kind(), m.start(), m.end());
		MinecraftClient c = MinecraftClient.getInstance();
		Vec3d start = m.start();
		// our own beams start at the barrel: a little right and below the eye, in view space
		if (c.player != null && m.shooterId() == c.player.getId()) {
			Vec3d look = c.player.getRotationVec(1f);
			Vec3d right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();
			Vec3d up = right.crossProduct(look).normalize();
			// (the off-hand gun's on the left)
			start = c.player.getEyePos().add(right.multiply(m.offHand() ? -0.22 : 0.22)).add(up.multiply(-0.18)).add(look.multiply(0.5));
			// the gun's own barrel when the shot was drawn (Revolver.Shoot: alternateStartPoint = gunBarrel)
			Vec3d muzzle = Tune.on("beam.fromBarrel") ? GunDisplays.muzzleWorld(m.offHand()) : null;
			if (muzzle != null && m.kind() != 2 && m.kind() != 5) start = muzzle;
		}
		beams.add(new Beam(new BeamPayload(m.kind(), m.shooterId(), start, m.end(), m.offHand())));
	}

	public static void clear() {
		beams.clear();
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || beams.isEmpty()) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		lastNanos = now;
		MinecraftClient c = MinecraftClient.getInstance();
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.emissiveDepth(WHITE));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		for (Iterator<Beam> it = beams.iterator(); it.hasNext(); ) {
			Beam b = it.next();
			// the LineRenderer shrinks 1.5 u/s in the game; beam.shrink lets it stay a little longer
			if (!Tune.on("debug.beamHold")) b.width -= dt * (float) (b.rail() ? Tune.get("rail.beamShrink") : Tune.get("beam.shrink"));
			if (b.width <= 0) { it.remove(); continue; }
			double w = b.width * V1.SCALE * Tune.get("beam.width") * 0.5;
			Vec3d dir = b.end.subtract(b.start);
			double len = dir.length();
			if (len < 1e-4) { it.remove(); continue; }
			dir = dir.multiply(1 / len);
			Vec3d toCam = camPos.subtract(b.start);
			Vec3d side = dir.crossProduct(toCam);
			if (side.lengthSquared() < 1e-6) side = dir.crossProduct(new Vec3d(0, 1, 0));
			side = side.normalize().multiply(w);
			Vec3d side2 = dir.crossProduct(side).normalize().multiply(w);
			ribbon(vc, m, b, side);
			ribbon(vc, m, b, side2);
			if (b.rail()) railLines(buffers, m, b, dir, camPos, len, dt);
		}
		ms.pop();
		buffers.draw();
	}

	private static final double RAIL_SHRINK = Tune.def("rail.beamShrink", 1.5, "railcannon beam width shrink, u/s (RevolverBeam: 1.5 from width 1)");
	private static final double RAIL_GLOW_W = Tune.def("rail.beamGlow", 10, "railcannon beam glow line width x the beam (Line (1): GlowLine16, width 10)");
	private static final double RAIL_ARC_W = Tune.def("rail.beamArcs", 20, "railcannon beam lightning width x the beam (Line: ElectricityLine 15..20)");
	private static final double RAIL_ARC_TILE = Tune.def("rail.beamArcTile", 1, "railcannon beam lightning: u of beam per texture repeat (Tile mode)");

	/**
	 * Railcannon Beam's two child lines, copying the beam (CopyLineRenderer: its points, width = beam width x their own):
	 * Line (1) a GlowLine16 glow 10 wide, Line an ElectricityLine of T_ElectricArcs 20 wide whose frame and colour change
	 * every 0.05 s. Both face the camera and add light. The Malicious beam has a single orange glow line 3 wide.
	 */
	private static void railLines(VertexConsumerProvider buffers, Matrix4f m, Beam b, Vec3d dir, Vec3d camPos, double len, float dt) {
		Vec3d side = dir.crossProduct(camPos.subtract(b.start));
		if (side.lengthSquared() < 1e-6) side = dir.crossProduct(new Vec3d(0, 1, 0));
		side = side.normalize();
		double w = b.width * V1.SCALE * 0.5;
		boolean mal = b.kind == 5;
		float[] end = mal ? MAL_END : SUPER_END;
		VertexConsumer glow = buffers.getBuffer(com.ultracraft.client.render.UkLayers.glowDepth(GLOWLINE));
		strip(glow, m, b.start, b.end, side.multiply(w * (mal ? 3 : Tune.get("rail.beamGlow"))), 0f, 1f, 1f, 1f, 1f, end[0], end[1], end[2]);
		if (mal) return;
		b.arcT -= dt;
		if (b.arcT <= 0) {
			b.arcT = 0.05f;
			b.arcFrame = (int) (Math.random() * 10);
			b.arcColor = (float) Math.random();
		}
		float cr = 1f - b.arcColor, cg = 1f - b.arcColor * (1f - 0.835f), cb = 1f;
		VertexConsumer arcs = buffers.getBuffer(com.ultracraft.client.render.UkLayers.glowDepth(new net.minecraft.util.Identifier("ultracraft", "textures/uk/electricarcs" + b.arcFrame + ".png")));
		float tiles = (float) (len / V1.SCALE / Math.max(0.05, Tune.get("rail.beamArcTile")));
		strip(arcs, m, b.start, b.end, side.multiply(w * Tune.get("rail.beamArcs")), 0f, tiles, cr, cg, cb, cr, cg, cb);
	}

	/** One camera-facing strip (both faces), u along the line, v across it. */
	private static void strip(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d c, Vec3d side, float u0, float u1, float r0, float g0, float b0, float r1, float g1, float b1) {
		tv(vc, m, a.add(side), u0, 0, r0, g0, b0);
		tv(vc, m, a.subtract(side), u0, 1, r0, g0, b0);
		tv(vc, m, c.subtract(side), u1, 1, r1, g1, b1);
		tv(vc, m, c.add(side), u1, 0, r1, g1, b1);
		tv(vc, m, c.add(side), u1, 0, r1, g1, b1);
		tv(vc, m, c.subtract(side), u1, 1, r1, g1, b1);
		tv(vc, m, a.subtract(side), u0, 1, r0, g0, b0);
		tv(vc, m, a.add(side), u0, 0, r0, g0, b0);
	}

	private static void tv(VertexConsumer vc, Matrix4f m, Vec3d p, float u, float v, float r, float g, float b) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, 1f).texture(u, v)
			.overlay(net.minecraft.client.render.OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}

	private static final net.minecraft.util.Identifier GLOWLINE = new net.minecraft.util.Identifier("ultracraft", "textures/uk/lineglow16.png");
	private static final float[] MAL_END = {1f, 0.631f, 0f};

	private static void ribbon(VertexConsumer vc, Matrix4f m, Beam b, Vec3d side) {
		// the LineRenderer gradient: white at the gun to the beam's colour at the end (beam.endYellow 0 = white all
		// along); Revolver Beam (1, 0.807, 0), Super (0, 0.834, 1), Sharp (1, 0.235, 0.235); solid while it thins
		float a = 1f;
		float[] end = b.kind == 1 || b.kind == 2 ? SUPER_END : b.kind == 4 ? SHARP_END : b.kind == 5 ? MAL_END : BEAM_END;
		quad(vc, m, b.start.add(side), b.start.subtract(side), b.end.subtract(side), b.end.add(side), a, end);
		quad(vc, m, b.end.add(side), b.end.subtract(side), b.start.subtract(side), b.start.add(side), a, end);
	}

	private static final float[] BEAM_END = {1f, 0.807f, 0f}, SUPER_END = {0f, 0.834f, 1f}, SHARP_END = {1f, 0.235f, 0.235f};

	private static void quad(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, Vec3d c, Vec3d d, float alpha, float[] end) {
		float k = (float) Tune.get("beam.endYellow");
		float er = 1f - (1f - end[0]) * k, eg = 1f - (1f - end[1]) * k, eb = 1f - (1f - end[2]) * k;
		v(vc, m, a, 1f, 1f, 1f, alpha);
		v(vc, m, b, 1f, 1f, 1f, alpha);
		v(vc, m, c, er, eg, eb, alpha);
		v(vc, m, d, er, eg, eb, alpha);
	}

	private static final net.minecraft.util.Identifier WHITE = new net.minecraft.util.Identifier("minecraft", "textures/misc/white.png");

	private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).texture(0.5f, 0.5f)
			.overlay(net.minecraft.client.render.OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
