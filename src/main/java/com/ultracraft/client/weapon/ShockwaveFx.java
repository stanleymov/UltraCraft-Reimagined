package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * PhysicalShockwavePlayer (the ground slam's shockwave): a ring that grows 75 u/s to 12 u across the floor, drawn
 * with the game's `shockwavetex` ring (the prefab's "Visual" mesh / "Shockwave" sprite, scale 7, fading 0.25/s).
 */
public final class ShockwaveFx {
	private static final Identifier TEX = new Identifier(UltraCraft.MOD_ID, "textures/uk/shockwavetex.png");

	private static final class Wave {
		Vec3d pos;
		float size, alpha = 1, speed, maxSize;
	}

	private static final List<Wave> waves = new ArrayList<>();
	private static long lastNanos;

	private ShockwaveFx() {}

	public static void spawn(Vec3d pos, float speedU, float maxSizeU) {
		Wave w = new Wave();
		w.pos = pos;
		w.speed = (float) (speedU * Damage.SCALE_BLOCKS);
		w.maxSize = (float) (maxSizeU * Damage.SCALE_BLOCKS);
		waves.add(w);
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || waves.isEmpty()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		if (com.ultracraft.client.uk.ViewModel.paused()) dt = 0;
		lastNanos = now;
		if (c.isPaused()) dt = 0;
		Vec3d camPos = e.getCamera().getPos();
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityTranslucent(TEX));
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		for (Iterator<Wave> it = waves.iterator(); it.hasNext(); ) {
			Wave w = it.next();
			w.size += w.speed * dt;
			if (w.size > w.maxSize) w.alpha -= dt * 4;
			if (w.alpha <= 0) { it.remove(); continue; }
			// the prefab's "Visual" is a ring mesh with the scrolling shockwavetex noise: an annulus, inner radius 70 %
			float r = Math.min(w.size, w.maxSize) / 2 + 0.3f, ri = r * 0.7f;
			float a = Math.max(0, Math.min(1, w.alpha)) * 0.9f;
			double y = w.pos.y + 0.03;
			int n = 40;
			for (int i = 0; i < n; i++) {
				double a0 = i * 2 * Math.PI / n, a1 = (i + 1) * 2 * Math.PI / n;
				float u0 = (float) i / n * 4, u1 = (float) (i + 1) / n * 4;
				q(vc, m, w.pos.x + Math.cos(a0) * ri, y, w.pos.z + Math.sin(a0) * ri, u0, 1, a);
				q(vc, m, w.pos.x + Math.cos(a0) * r, y, w.pos.z + Math.sin(a0) * r, u0, 0, a);
				q(vc, m, w.pos.x + Math.cos(a1) * r, y, w.pos.z + Math.sin(a1) * r, u1, 0, a);
				q(vc, m, w.pos.x + Math.cos(a1) * ri, y, w.pos.z + Math.sin(a1) * ri, u1, 1, a);
			}
		}
		ms.pop();
		buffers.draw();
	}

	private static void q(VertexConsumer vc, Matrix4f m, double x, double y, double z, float u, float v, float a) {
		vc.vertex(m, (float) x, (float) y, (float) z).color(1f, 1f, 1f, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
