package com.ultracraft.client.shop;

import com.ultracraft.client.Tune;
import com.ultracraft.client.style.StyleState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;
import net.minecraftforge.client.event.ContainerScreenEvent;

/**
 * The player's P (the user's ask): at the top of the black box V1 stands in, in the inventory and on the creative
 * inventory's survival tab, in the HUD's ULTRAKILL font. Drawn with the background, so slots and tooltips stay on top.
 */
public final class InventoryPoints {
	static {
		Tune.def("inventory.pointsGap", 6, "inventory P: gap between the number and its P, font pixels (a digit is 21.3; by eye)");
	}

	/** every amount is drawn at the size that fits this one (user: the size 100 million P has; smaller ones were huge) */
	private static final String SIZE_OF = "100,000,000";

	private InventoryPoints() {}

	public static void onBackground(ContainerScreenEvent.Render.Background e) {
		var screen = e.getContainerScreen();
		int left = screen.getGuiLeft(), top = screen.getGuiTop();
		// the box (vanilla's textures): x 26-74, y 8-77 in the inventory; x 73-104, y 6-48 on the creative inventory tab
		if (screen instanceof InventoryScreen) draw(e.getGuiGraphics(), left + 50.5f, top + 10, 46);
		else if (screen instanceof CreativeInventoryScreen c && c.isInventoryTabSelected()) draw(e.getGuiGraphics(), left + 89f, top + 8, 30);
	}

	private static void draw(DrawContext ctx, float centerX, int y, int maxWidth) {
		var tr = MinecraftClient.getInstance().textRenderer;
		String amount = String.format(java.util.Locale.ROOT, "%,d", StyleState.INSTANCE.stylePoints);
		// the number and its P with a small gap (the font's space is a whole monospace cell: user, too far apart)
		Text num = Text.literal(amount).setStyle(StyleState.HUD_STYLE);
		Text p = Text.literal("P").setStyle(StyleState.HUD_STYLE);
		int gap = (int) Tune.get("inventory.pointsGap"), nw = tr.getWidth(num), pw = tr.getWidth(p);
		int w = nw + gap + pw;
		int ref = tr.getWidth(Text.literal(SIZE_OF).setStyle(StyleState.HUD_STYLE)) + gap + pw;
		float s = maxWidth / (float) Math.max(ref, w);
		// centred on the ink, not the cells: VCR OSD Mono's glyphs sit 100 of a 1200-unit cell in from each side, its
		// 1 300 (vcr_osd_mono.ttf)
		float cell = tr.getWidth(Text.literal("0").setStyle(StyleState.HUD_STYLE));
		float inkShift = cell * ((amount.startsWith("1") ? 300 : 100) - 100) / 1200f / 2f;
		ctx.getMatrices().push();
		ctx.getMatrices().translate(centerX, y, 0);
		ctx.getMatrices().scale(s, s, 1);
		ctx.getMatrices().translate(-w / 2f - inkShift, 0, 0);
		ctx.drawText(tr, num, 0, 0, 0xFFFFFF, false);
		ctx.drawText(tr, p, nw + gap, 0, 0xFFFFFF, false);
		ctx.getMatrices().pop();
	}
}
