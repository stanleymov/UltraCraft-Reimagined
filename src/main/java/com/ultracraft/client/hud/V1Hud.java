package com.ultracraft.client.hud;

import com.ultracraft.client.Tune;
import com.ultracraft.client.v1.V1;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Temporary stand-in for ULTRAKILL's HUD (health + hard damage bar, three stamina bars, speedometer),
 * drawn with plain rectangles and the vanilla font until the game's own HUD art is converted.
 */
public final class V1Hud {
	private static final double SHOW_SPEED = Tune.def("hud.speedometer", 1, "1 = show the speedometer (u/s, like ULTRAKILL's)");

	private V1Hud() {}

	public static void render(DrawContext ctx, float tickDelta, int w, int h) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden) return;
		V1 s = V1.INSTANCE;
		int x = 8, y = h - 40;
		// health: red fill, black hard-damage block from the right, text
		int barW = 110, barH = 10;
		ctx.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xFF000000);
		int hpW = (int) Math.round(barW * Math.min(1, s.hp / 100.0));
		ctx.fill(x, y, x + hpW, y + barH, 0xFFD41010);
		int antiW = (int) Math.round(barW * Math.min(1, s.antiHp / 100.0));
		if (antiW > 0) ctx.fill(x + barW - antiW, y, x + barW, y + barH, 0xFF202020);
		ctx.drawTextWithShadow(client.textRenderer, Integer.toString(s.hp), x + barW + 5, y + 1, 0xFFFFFF);
		// stamina: three bars of 100
		int sy = y + barH + 4, seg = (barW - 4) / 3;
		for (int i = 0; i < 3; i++) {
			int sx = x + i * (seg + 2);
			ctx.fill(sx - 1, sy - 1, sx + seg + 1, sy + 5, 0xFF000000);
			double part = Math.max(0, Math.min(1, (s.boostCharge - i * 100) / 100.0));
			int color = part >= 1 ? 0xFF2FA8FF : 0xFF1A5C8F;
			ctx.fill(sx, sy, sx + (int) Math.round(seg * part), sy + 4, color);
		}
		if (Tune.on("hud.speedometer")) {
			double hs = Math.hypot(s.vel.x, s.vel.z);
			String txt = String.format("%.0f u/s", hs);
			ctx.drawTextWithShadow(client.textRenderer, txt, x, sy + 9, 0xFFFFFF);
		}
		if (Tune.on("hud.debug")) {
			String st = (s.onGround ? "G " : "  ") + (s.onWall ? "W " : "  ") + (s.boost ? "DASH " : "") + (s.sliding ? "SLIDE " : "")
				+ (s.heavyFall ? "SLAM " : "") + (s.falling ? "fall " : "") + (s.jumping ? "jump " : "") + "wj" + s.currentWallJumps;
			ctx.drawTextWithShadow(client.textRenderer, String.format("vy %.1f  %s", s.vel.y, st), x, sy + 19, 0xFFFF55);
		}
	}

	private static final double DEBUG = Tune.def("hud.debug", 1, "1 = show the movement state line under the speedometer");
}
