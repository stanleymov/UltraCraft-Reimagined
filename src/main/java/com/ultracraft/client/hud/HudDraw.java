package com.ultracraft.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import com.ultracraft.UltraCraft;
import com.ultracraft.client.style.StyleState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Drawing in ULTRAKILL's HUD space: the HUD is two world-space canvases 1 m in front of a 90° camera
 * (Player prefab: HUD Camera → GunCanvas / StyleCanvas). {@link #begin} swaps in that camera, {@link #canvas}
 * pushes a canvas transform (Unity's, mirrored into GL), and the primitives draw in canvas units with y up.
 */
public final class HudDraw {
	public static final String TEX = "textures/uk/";
	private static Matrix4f savedProjection;
	private final MatrixStack ms = new MatrixStack();
	private final MinecraftClient client = MinecraftClient.getInstance();
	private final VertexConsumerProvider.Immediate buffers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
	/** TTF glyphs are `size` GUI units tall at scale 1 (font/vcr.json: 32). */
	public static final float FONT_PX = 32f;

	/** Perspective of the HUD camera; `fov` vertical degrees (prefab: 90). */
	public void begin(float fov) {
		savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
		float aspect = (float) client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight();
		Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 50f);
		RenderSystem.setProjectionMatrix(proj, VertexSorter.BY_DISTANCE);
		RenderSystem.getModelViewStack().push();
		RenderSystem.getModelViewStack().loadIdentity();
		RenderSystem.applyModelViewMatrix();
		RenderSystem.disableDepthTest();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
	}

	/** The text layers end with blending off; every primitive turns it back on. */
	private static void blend() {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1, 1, 1, 1);
	}

	public void end() {
		buffers.draw();
		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.getModelViewStack().pop();
		RenderSystem.applyModelViewMatrix();
		RenderSystem.setProjectionMatrix(savedProjection, VertexSorter.BY_DISTANCE);
	}

	/**
	 * Push a Unity canvas transform: position (Unity camera space, +z forward), rotation (Unity quaternion),
	 * scale (canvas units → metres). Mirroring z on both sides turns the left-handed transform into GL's.
	 */
	public void canvas(float px, float py, float pz, Quaternionf rot, float sx, float sy) {
		ms.push();
		Matrix4f m = new Matrix4f().scale(1, 1, -1).translate(px, py, pz).rotate(rot).scale(sx, sy, 1).scale(1, 1, -1);
		ms.multiplyPositionMatrix(m);
	}

	public void push() {
		ms.push();
	}

	public void pop() {
		ms.pop();
	}

	public void translate(float x, float y) {
		ms.translate(x, y, 0);
	}

	public void scale(float s) {
		ms.scale(s, s, 1);
	}

	public void scale(float sx, float sy) {
		ms.scale(sx, sy, 1);
	}

	public Matrix4f matrix() {
		return ms.peek().getPositionMatrix();
	}

	public static Identifier tex(String name) {
		return new Identifier(UltraCraft.MOD_ID, TEX + name + ".png");
	}

	/** Solid rectangle (y up), colour as floats. */
	public void fill(float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
		if (a <= 0) return;
		blend();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		Matrix4f m = matrix();
		BufferBuilder bb = Tessellator.getInstance().getBuffer();
		bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		bb.vertex(m, x0, y0, 0).color(r, g, b, a).next();
		bb.vertex(m, x1, y0, 0).color(r, g, b, a).next();
		bb.vertex(m, x1, y1, 0).color(r, g, b, a).next();
		bb.vertex(m, x0, y1, 0).color(r, g, b, a).next();
		BufferRenderer.drawWithGlobalProgram(bb.end());
	}

	/** Textured rectangle; u/v in 0..1 with v = 0 at the top of the image. */
	public void sprite(Identifier texture, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float r, float g, float b, float a) {
		if (a <= 0) return;
		blend();
		RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
		RenderSystem.setShaderTexture(0, texture);
		Matrix4f m = matrix();
		BufferBuilder bb = Tessellator.getInstance().getBuffer();
		bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
		bb.vertex(m, x0, y0, 0).texture(u0, v1).color(r, g, b, a).next();
		bb.vertex(m, x1, y0, 0).texture(u1, v1).color(r, g, b, a).next();
		bb.vertex(m, x1, y1, 0).texture(u1, v0).color(r, g, b, a).next();
		bb.vertex(m, x0, y1, 0).texture(u0, v0).color(r, g, b, a).next();
		BufferRenderer.drawWithGlobalProgram(bb.end());
	}

	public void sprite(Identifier texture, float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
		sprite(texture, x0, y0, x1, y1, 0, 0, 1, 1, r, g, b, a);
	}

	/**
	 * Unity "Sliced" Image: a 9-slice whose border is `border` texture pixels on each side, shown at
	 * border / ppuMultiplier canvas units (the HUD uses pixelsPerUnitMultiplier 5.4).
	 */
	public void sliced(Identifier texture, int texSize, int border, float ppuMult, float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
		if (a <= 0 || x1 <= x0 || y1 <= y0) return;
		blend();
		float bw = border / ppuMult;
		float w = x1 - x0, h = y1 - y0;
		// shrink the corners when the rect is smaller than two borders (Unity does the same)
		float bx = Math.min(bw, w / 2), by = Math.min(bw, h / 2);
		float bu = (float) border / texSize;
		float[] xs = {x0, x0 + bx, x1 - bx, x1};
		float[] ys = {y1, y1 - by, y0 + by, y0}; // top → bottom to match v
		float[] us = {0, bu, 1 - bu, 1};
		float[] vs = {0, bu, 1 - bu, 1};
		RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
		RenderSystem.setShaderTexture(0, texture);
		Matrix4f m = matrix();
		BufferBuilder bb = Tessellator.getInstance().getBuffer();
		bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				float qx0 = xs[i], qx1 = xs[i + 1], qy1 = ys[j], qy0 = ys[j + 1];
				if (qx1 <= qx0 || qy1 <= qy0) continue;
				bb.vertex(m, qx0, qy0, 0).texture(us[i], vs[j + 1]).color(r, g, b, a).next();
				bb.vertex(m, qx1, qy0, 0).texture(us[i + 1], vs[j + 1]).color(r, g, b, a).next();
				bb.vertex(m, qx1, qy1, 0).texture(us[i + 1], vs[j]).color(r, g, b, a).next();
				bb.vertex(m, qx0, qy1, 0).texture(us[i], vs[j]).color(r, g, b, a).next();
			}
		}
		BufferRenderer.drawWithGlobalProgram(bb.end());
	}

	public enum Align { LEFT, CENTER, RIGHT }

	/**
	 * Text in the HUD font, `size` canvas units tall (TMP font size), baseline-top at `y` (top of the line, y up).
	 */
	public void text(Text text, float x, float y, float size, Align align, int argb) {
		TextRenderer tr = client.textRenderer;
		float s = size / FONT_PX * fontScale;
		float w = tr.getWidth(text) * s;
		float x0 = align == Align.LEFT ? x : align == Align.CENTER ? x - w / 2 : x - w;
		ms.push();
		ms.translate(x0, y, 0);
		ms.scale(s, -s, 1);
		tr.draw(text, 0, 0, argb, false, ms.peek().getPositionMatrix(), buffers, TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
		buffers.draw();
		ms.pop();
	}

	public void text(String s, float x, float y, float size, Align align, int argb) {
		text(Text.literal(s).setStyle(StyleState.HUD_STYLE), x, y, size, align, argb);
	}

	public float textWidth(Text t, float size) {
		return client.textRenderer.getWidth(t) * size / FONT_PX * fontScale;
	}

	/** Live calibration of TMP point size → our font (hud.fontScale). */
	public float fontScale = 1f;

	public static int argb(float r, float g, float b, float a) {
		return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
	}
}
