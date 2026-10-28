package com.ultracraft.client.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.function.Function;

/** Custom render layers (subclass only to reach RenderLayer's protected phases). */
public final class UkLayers extends RenderLayer {
	private UkLayers(String name, VertexFormat format, VertexFormat.DrawMode mode, int size, boolean crumbling, boolean translucent, Runnable begin, Runnable end) {
		super(name, format, mode, size, crumbling, translucent, begin, end);
	}

	/**
	 * Unlit translucent (the entity-translucent-emissive program, so it stays bright under shader packs) that also
	 * writes depth: shader-pack clouds composite wherever depth still says sky, and covered the depth-less layer.
	 */
	private static final Function<Identifier, RenderLayer> EMISSIVE_DEPTH = Util.memoize(tex -> of("ultracraft_translucent_emissive_depth",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, true, true,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_EMISSIVE_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(TRANSLUCENT_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.writeMaskState(ALL_MASK)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(false)));

	/** Light added on top, weighted by the sprite's alpha (SRC_ALPHA, ONE): a glow that can only brighten. */
	private static final Transparency GLOW_TRANSPARENCY = new Transparency("ultracraft_glow_transparency", () -> {
		com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		com.mojang.blaze3d.systems.RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
	}, () -> {
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
	});

	private static final Function<Identifier, RenderLayer> GLOW = Util.memoize(tex -> of("ultracraft_glow",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, false, true,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_EMISSIVE_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(GLOW_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.writeMaskState(COLOR_MASK)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(false)));

	public static RenderLayer glow(Identifier texture) {
		return GLOW.apply(texture);
	}

	/** glow() with the texture filtered smoothly (a small sprite drawn big: no blocky pixel edges). */
	private static final Function<Identifier, RenderLayer> GLOW_SMOOTH = Util.memoize(tex -> of("ultracraft_glow_smooth",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, false, true,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_EMISSIVE_PROGRAM)
			.texture(new Texture(tex, true, false))
			.transparency(GLOW_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.writeMaskState(COLOR_MASK)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(false)));

	public static RenderLayer glowSmooth(Identifier texture) {
		return GLOW_SMOOTH.apply(texture);
	}

	/** Unlit and laid over (alpha-blended), the texture filtered smoothly: a solid coloured sprite without pixel edges. */
	// (the plain translucent program, drawn full bright: a shader pack runs the emissive one as its see-through glow)
	private static final Function<Identifier, RenderLayer> SOLID_SMOOTH = Util.memoize(tex -> of("ultracraft_solid_smooth",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, false, true,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_PROGRAM)
			.lightmap(ENABLE_LIGHTMAP)
			.texture(new Texture(tex, true, false))
			.transparency(TRANSLUCENT_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.writeMaskState(COLOR_MASK)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(false)));

	public static RenderLayer solidSmooth(Identifier texture) {
		return SOLID_SMOOTH.apply(texture);
	}

	/**
	 * A world-space UI screen (the Terminal's canvas): unlit (it glows on the terminal), alpha blended, writing depth
	 * (its see-through parts are dropped by the program's alpha cut), and not sorted - drawn in the order given, each
	 * element a little in front of the last, as Unity draws a canvas.
	 */
	private static final Function<Identifier, RenderLayer> SCREEN = Util.memoize(tex -> of("ultracraft_screen",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, false, false,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_EMISSIVE_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(TRANSLUCENT_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.writeMaskState(ALL_MASK)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(false)));

	public static RenderLayer screen(Identifier texture) {
		return SCREEN.apply(texture);
	}

	/** Additive like glow(), but writing depth: shader-pack clouds are composited where depth still says sky. */
	private static final Function<Identifier, RenderLayer> GLOW_DEPTH = Util.memoize(tex -> of("ultracraft_glow_depth",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, false, true,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_EMISSIVE_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(GLOW_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.writeMaskState(ALL_MASK)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(false)));

	public static RenderLayer glowDepth(Identifier texture) {
		return GLOW_DEPTH.apply(texture);
	}

	/** The rig's shape into the depth buffer only (no colour): an occluder for things drawn after it. */
	private static final Function<Identifier, RenderLayer> DEPTH_ONLY = Util.memoize(tex -> of("ultracraft_depth_only",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, true, false,
		MultiPhaseParameters.builder()
			.program(ENTITY_CUTOUT_NONULL_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(NO_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.writeMaskState(DEPTH_MASK)
			.lightmap(ENABLE_LIGHTMAP)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(false)));

	public static RenderLayer depthOnly(Identifier texture) {
		return DEPTH_ONLY.apply(texture);
	}

	/**
	 * See-through and not writing depth, on programs a shader pack runs as part of the hand when drawn there (Oculus:
	 * rendertype_entity_translucent -> HAND_WATER_DIFFUSE, rendertype_text -> HAND_TEXT): they move with the pack's
	 * hand sway and do not glow, where the emissive program always goes to its glowing "eyes" program
	 * (ENTITIES_EYES_TRANS) and stood still against the swaying gun.
	 */
	private static final Function<Identifier, RenderLayer> HAND_TEXT = Util.memoize(tex -> of("ultracraft_hand_text",
		VertexFormats.POSITION_COLOR_TEXTURE_LIGHT, VertexFormat.DrawMode.QUADS, 256, false, true,
		MultiPhaseParameters.builder()
			.program(TEXT_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(TRANSLUCENT_TRANSPARENCY)
			.lightmap(ENABLE_LIGHTMAP)
			.cull(DISABLE_CULLING)
			.writeMaskState(COLOR_MASK)
			.build(false)));

	private static final Function<Identifier, RenderLayer> HAND_TRANSLUCENT = Util.memoize(tex -> of("ultracraft_hand_translucent",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, true, true,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(TRANSLUCENT_TRANSPARENCY)
			.lightmap(ENABLE_LIGHTMAP)
			.overlay(ENABLE_OVERLAY_COLOR)
			.cull(DISABLE_CULLING)
			.writeMaskState(COLOR_MASK)
			.build(false)));

	public static RenderLayer handText(Identifier texture) {
		return HAND_TEXT.apply(texture);
	}

	public static RenderLayer handTranslucent(Identifier texture) {
		return HAND_TRANSLUCENT.apply(texture);
	}

	/**
	 * A decal on the blocks (the gasoline stains): see-through, lit, not writing depth (overlapping stains do not fight
	 * each other) and pulled toward the camera by a polygon offset (not fighting the ground they lie on, far off too).
	 */
	private static final Layering DECAL_OFFSET = new Layering("ultracraft_decal_offset", () -> {
		com.mojang.blaze3d.systems.RenderSystem.polygonOffset(-2f, -20f);
		com.mojang.blaze3d.systems.RenderSystem.enablePolygonOffset();
	}, () -> {
		com.mojang.blaze3d.systems.RenderSystem.polygonOffset(0f, 0f);
		com.mojang.blaze3d.systems.RenderSystem.disablePolygonOffset();
	});

	private static final Function<Identifier, RenderLayer> DECAL = Util.memoize(tex -> of("ultracraft_decal",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, true, true,
		MultiPhaseParameters.builder()
			.program(ENTITY_TRANSLUCENT_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(TRANSLUCENT_TRANSPARENCY)
			.lightmap(ENABLE_LIGHTMAP)
			.overlay(ENABLE_OVERLAY_COLOR)
			.cull(DISABLE_CULLING)
			.writeMaskState(COLOR_MASK)
			.layering(DECAL_OFFSET)
			.build(false)));

	public static RenderLayer decal(Identifier texture) {
		return DECAL.apply(texture);
	}

	public static RenderLayer emissiveDepth(Identifier texture) {
		return EMISSIVE_DEPTH.apply(texture);
	}

	/**
	 * Opaque and lit like entityCutoutNoCull, but not one of the buffers vanilla draws at the end of the entity pass: it is
	 * drawn where it is used, so see-through parts drawn after it (that write no depth) are not painted over by it.
	 */
	private static final Function<Identifier, RenderLayer> LIT_SOLID = Util.memoize(tex -> of("ultracraft_lit_solid",
		VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS, 256, true, false,
		MultiPhaseParameters.builder()
			.program(ENTITY_CUTOUT_NONULL_PROGRAM)
			.texture(new Texture(tex, false, false))
			.transparency(NO_TRANSPARENCY)
			.cull(DISABLE_CULLING)
			.lightmap(ENABLE_LIGHTMAP)
			.overlay(ENABLE_OVERLAY_COLOR)
			.build(true)));

	public static RenderLayer litSolid(Identifier texture) {
		return LIT_SOLID.apply(texture);
	}

	private static java.lang.reflect.Method irisInUse, irisShadow;
	private static Object irisApi;
	private static boolean irisLooked;

	/** Whether a shader pack is drawing (Oculus' IrisApi, by reflection: Oculus is optional). */
	/**
	 * When an effect drawn without depth goes: after the particles under a shader pack (as it was tuned there), after the
	 * weather without one - vanilla draws its clouds after the particles, over anything that left no depth behind; drawn
	 * after them, the clouds' depth hides what is behind a cloud and nothing else.
	 */
	public static net.minecraftforge.client.event.RenderLevelStageEvent.Stage depthlessStage() {
		return shadersOn() ? net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES
			: net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_WEATHER;
	}

	/**
	 * The event's pose stack for drawing in world space at any stage. At AFTER_WEATHER vanilla has already put the
	 * camera's turn into RenderSystem's model-view matrix (for the clouds and the weather) and the event's stack holds
	 * it too: drawn with the stack as it is, the turn was applied twice. The model-view is taken back out here.
	 */
	public static net.minecraft.client.util.math.MatrixStack worldStack(net.minecraftforge.client.event.RenderLevelStageEvent e) {
		net.minecraft.client.util.math.MatrixStack ms = e.getPoseStack();
		org.joml.Matrix4f mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix();
		if (mv.equals(new org.joml.Matrix4f(), 1e-5f)) return ms;
		net.minecraft.client.util.math.MatrixStack out = new net.minecraft.client.util.math.MatrixStack();
		out.peek().getPositionMatrix().set(new org.joml.Matrix4f(mv).invert().mul(ms.peek().getPositionMatrix()));
		out.peek().getNormalMatrix().set(new org.joml.Matrix3f(out.peek().getPositionMatrix()).invert().transpose());
		return out;
	}

	/** A shader pack is drawing its shadow map now (IrisApi.isRenderingShadowPass): what is drawn now casts a shadow. */
	public static boolean shadowPass() {
		if (!shadersOn() || irisShadow == null) return false;
		try {
			return (Boolean) irisShadow.invoke(irisApi);
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean shadersOn() {
		if (!irisLooked) {
			irisLooked = true;
			try {
				Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				irisApi = c.getMethod("getInstance").invoke(null);
				irisInUse = c.getMethod("isShaderPackInUse");
				irisShadow = c.getMethod("isRenderingShadowPass");
			} catch (Throwable t) {
				irisApi = null;
			}
		}
		if (irisApi == null) return false;
		try {
			return (Boolean) irisInUse.invoke(irisApi);
		} catch (Throwable t) {
			return false;
		}
	}
}
