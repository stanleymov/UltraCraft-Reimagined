package com.ultracraft.client.style;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayDeque;
import java.util.Deque;

/** TextMeshPro rich text (`<color=orange>…</color>`, `<size=…>`) → Minecraft Text, in the HUD font. */
public final class Rich {
	private Rich() {}

	public static Text parse(String s, Style base) {
		MutableText out = Text.empty();
		Deque<Style> stack = new ArrayDeque<>();
		stack.push(base);
		StringBuilder run = new StringBuilder();
		int i = 0;
		while (i < s.length()) {
			char ch = s.charAt(i);
			if (ch == '<') {
				int end = s.indexOf('>', i);
				if (end > i) {
					String tag = s.substring(i + 1, end);
					flush(out, run, stack.peek());
					if (tag.startsWith("color=")) {
						TextColor c = color(tag.substring(6));
						stack.push(c != null ? stack.peek().withColor(c) : stack.peek());
					} else if (tag.equals("/color") || tag.equals("/size") || tag.equals("/b")) {
						if (stack.size() > 1) stack.pop();
					} else if (tag.startsWith("size=")) {
						stack.push(stack.peek()); // sizes are one line height in the HUD
					} else if (tag.equals("b")) {
						stack.push(stack.peek().withBold(true));
					}
					i = end + 1;
					continue;
				}
			}
			run.append(ch);
			i++;
		}
		flush(out, run, stack.peek());
		return out;
	}

	private static void flush(MutableText out, StringBuilder run, Style style) {
		if (run.length() == 0) return;
		out.append(Text.literal(run.toString()).setStyle(style));
		run.setLength(0);
	}

	/** Unity's named colours and #rrggbb(aa). */
	public static TextColor color(String name) {
		name = name.trim().toLowerCase();
		if (name.startsWith("#")) {
			try {
				String hex = name.substring(1);
				if (hex.length() == 8) hex = hex.substring(0, 6);
				return TextColor.fromRgb(Integer.parseInt(hex, 16));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return switch (name) {
			case "orange" -> TextColor.fromRgb(0xFFA500);
			case "green" -> TextColor.fromRgb(0x00FF00);
			case "red" -> TextColor.fromRgb(0xFF0000);
			case "yellow" -> TextColor.fromRgb(0xFFEB04);
			case "grey", "gray" -> TextColor.fromRgb(0x808080);
			case "cyan" -> TextColor.fromRgb(0x00FFFF);
			case "white" -> TextColor.fromRgb(0xFFFFFF);
			case "black" -> TextColor.fromRgb(0x000000);
			default -> null;
		};
	}
}
