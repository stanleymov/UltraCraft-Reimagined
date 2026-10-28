package com.ultracraft.client.shop;

import com.ultracraft.block.TerminalBlock;
import com.ultracraft.block.UKBlocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;

/** The Terminal item: the terminal model (no screen), shrunk to fit a block. */
public final class TerminalItemRenderer extends BuiltinModelItemRenderer {
	private static TerminalItemRenderer instance;

	private TerminalItemRenderer() {
		super(MinecraftClient.getInstance().getBlockEntityRenderDispatcher(), MinecraftClient.getInstance().getEntityModelLoader());
	}

	public static TerminalItemRenderer get() {
		if (instance == null) instance = new TerminalItemRenderer();
		return instance;
	}

	@Override
	public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack ms, VertexConsumerProvider buffers, int light, int overlay) {
		Matrix4f m = TerminalRenderer.rootToBlock(UKBlocks.TERMINAL.get().getDefaultState().with(TerminalBlock.FACING, Direction.SOUTH));
		if (m == null) return;
		ms.push();
		// the terminal is about 2.6 blocks tall: a block's height in the slot
		ms.translate(0.5, 0, 0.5);
		ms.scale(0.38f, 0.38f, 0.38f);
		ms.translate(-0.5, 0, -0.5);
		ms.multiplyPositionMatrix(m);
		TerminalRenderer.poseDrawer(0);
		TerminalRenderer.drawModel(ms, buffers, light);
		ms.pop();
	}
}
