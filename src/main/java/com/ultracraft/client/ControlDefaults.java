package com.ultracraft.client;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minecraft's own keys are never changed by UltraCraft. Earlier builds moved sneak / sprint / inventory / drop /
 * swap hands out of the way (and left a marker file); this puts those back to their defaults once, if they still
 * sit where we moved them or came unbound, and removes the marker.
 */
public final class ControlDefaults {
	private ControlDefaults() {}

	public static void applyOnce(MinecraftClient client) {
		var o = client.options;
		// our own keys used to default to F / E / Q / Tab (vanilla's swap hands / inventory / drop / player list):
		// anyone still on those old defaults moves to the new ones
		boolean moved = false;
		moved |= migrate(UKKeys.punch, GLFW.GLFW_KEY_F);
		moved |= migrate(UKKeys.nextVariation, GLFW.GLFW_KEY_E);
		moved |= migrate(UKKeys.prevVariation, GLFW.GLFW_KEY_Q);
		moved |= migrate(UKKeys.stats, GLFW.GLFW_KEY_TAB);
		if (moved) {
			KeyBinding.updateKeysByCode();
			o.write();
			UltraCraft.LOG.info("Moved UltraCraft keys off Minecraft's F / E / Q / Tab to their new defaults");
		}
		Path marker = client.runDirectory.toPath().resolve("config").resolve("ultracraft-keys-applied");
		if (!Files.exists(marker)) return;
		restore(o.sneakKey, GLFW.GLFW_KEY_C);
		restore(o.sprintKey, GLFW.GLFW_KEY_V);
		restore(o.inventoryKey, GLFW.GLFW_KEY_I);
		restore(o.dropKey, GLFW.GLFW_KEY_Z);
		restore(o.swapHandsKey, GLFW.GLFW_KEY_X);
		KeyBinding.updateKeysByCode();
		o.write();
		try {
			Files.delete(marker);
		} catch (IOException e) {
			UltraCraft.LOG.warn("could not delete {}", marker, e);
		}
		UltraCraft.LOG.info("Restored Minecraft's default sneak / sprint / inventory / drop / swap-hands keys");
	}

	private static boolean migrate(KeyBinding binding, int oldDefault) {
		InputUtil.Key now = ((KeyBindingAccessor) binding).ultracraft$getBoundKey();
		if (now.getCategory() == InputUtil.Type.KEYSYM && now.getCode() == oldDefault) {
			binding.setBoundKey(binding.getDefaultKey());
			return true;
		}
		return false;
	}

	private static void restore(KeyBinding binding, int movedTo) {
		InputUtil.Key now = ((KeyBindingAccessor) binding).ultracraft$getBoundKey();
		boolean ours = now.getCategory() == InputUtil.Type.KEYSYM && now.getCode() == movedTo;
		boolean unbound = now.equals(InputUtil.UNKNOWN_KEY);
		if (ours || unbound) binding.setBoundKey(binding.getDefaultKey());
	}
}
