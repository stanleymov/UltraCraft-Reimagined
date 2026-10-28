package com.ultracraft.client.weapon;

import com.ultracraft.block.GrappleOrbBlockEntity;
import com.ultracraft.block.UKBlocks;
import com.ultracraft.client.Tune;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The grapple orb item: the orb's own model (outer and inner orb, the two arches) facing the viewer, spinning as an
 * idle orb does; in the world (held, dropped, framed) with its glow and rings as well.
 */
public final class GrappleOrbItemRenderer extends BuiltinModelItemRenderer {
	private static GrappleOrbItemRenderer instance;
	private GrappleOrbBlockEntity state;

	private GrappleOrbItemRenderer() {
		super(MinecraftClient.getInstance().getBlockEntityRenderDispatcher(), MinecraftClient.getInstance().getEntityModelLoader());
	}

	public static GrappleOrbItemRenderer get() {
		if (instance == null) instance = new GrappleOrbItemRenderer();
		return instance;
	}

	@Override
	public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider buffers, int light, int overlay) {
		if (state == null) state = new GrappleOrbBlockEntity(BlockPos.ORIGIN, UKBlocks.GRAPPLE_ORB.get().getDefaultState());
		GrappleOrbRenderer.animate(state, false, false);
		ms.push();
		ms.translate(0.5, 0.5, 0.5);
		// where the camera is, in the item's own space: the stack ends in view space (camera at the origin; in the GUI an
		// orthographic view down -z), so the inverse brings the camera back here - held, dropped or framed, the glow and
		// the arches face the real camera like the placed orb's
		Matrix4f inv = new Matrix4f(ms.peek().getPositionMatrix()).invert();
		Vector3f toCam = mode == ModelTransformationMode.GUI ? inv.transformDirection(new Vector3f(0, 0, 1)) : inv.transformPosition(new Vector3f());
		GrappleOrbRenderer.draw(ms, buffers, light, state, new Vector3f(toCam.x, toCam.y, -toCam.z), Tune.getF("grapple.itemScale"), Float.MAX_VALUE,
			mode != ModelTransformationMode.GUI);
		ms.pop();
	}
}
