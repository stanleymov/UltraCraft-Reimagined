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
 * UltraCraft's key defaults (the user's: mouse 4 punch, mouse 5 whiplash, C next variation, R last weapon), and the
 * keys of others it moves: creative's save-hotbar activator, C to B, so C is free for next variation, and Oculus's
 * reload-shaders key, R to J, so R is free for last weapon. Keys still on
 * an older build's defaults move to the new ones once (marker config/ultracraft-controls-2), so a key a player picks
 * later is never moved again. Earlier builds also moved sneak / sprint / inventory / drop / swap hands out of the way
 * (and left a marker file); those go back to their defaults once, if they still sit where we moved them or came
 * unbound, and the marker goes.
 */
public final class ControlDefaults {
	private ControlDefaults() {}

	public static void applyOnce(MinecraftClient client) {
		var o = client.options;
		((KeyBindingAccessor) o.saveToolbarActivatorKey).ultracraft$setDefaultKey(InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_B));
		// Oculus's reload-shaders key (in options.txt like any key, not in Oculus's own config) defaults to R, which is
		// last weapon: J instead (the user's pick), when Oculus is there
		KeyBinding shaderReload = null;
		for (KeyBinding k : o.allKeys) if (k.getTranslationKey().equals("iris.keybind.reload")) shaderReload = k;
		if (shaderReload != null) ((KeyBindingAccessor) shaderReload).ultracraft$setDefaultKey(InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_J));
		Path controls = client.runDirectory.toPath().resolve("config").resolve("ultracraft-controls-2");
		if (!Files.exists(controls)) {
			// the older defaults: punch F then V, whiplash R, next variation E then B
			boolean moved = false;
			moved |= migrate(UKKeys.punch, GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_V);
			moved |= migrate(UKKeys.hook, GLFW.GLFW_KEY_R);
			moved |= migrate(UKKeys.nextVariation, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_B);
			moved |= migrate(o.saveToolbarActivatorKey, GLFW.GLFW_KEY_C);
			if (shaderReload != null) moved |= migrate(shaderReload, GLFW.GLFW_KEY_R);
			if (moved) {
				KeyBinding.updateKeysByCode();
				o.write();
				UltraCraft.LOG.info("Moved UltraCraft's keys (and creative's save hotbar) to their new defaults");
			}
			try {
				Files.createDirectories(controls.getParent());
				Files.writeString(controls, "UltraCraft moved its keys to the new defaults once; delete to do it again\n");
			} catch (IOException e) {
				UltraCraft.LOG.warn("could not write {}", controls, e);
			}
		}
		// Minecraft's view bobbing (on by default) stacks on V1's own walking bob (WalkingBob): off once per install (marker
		// config/ultracraft-viewbob), so a player who turns it back on keeps it (user)
		Path viewBob = client.runDirectory.toPath().resolve("config").resolve("ultracraft-viewbob");
		if (!Files.exists(viewBob)) {
			if (o.getBobView().getValue()) {
				o.getBobView().setValue(false);
				o.write();
				UltraCraft.LOG.info("Turned Minecraft's view bobbing off (V1 has its own)");
			}
			try {
				Files.createDirectories(viewBob.getParent());
				Files.writeString(viewBob, "UltraCraft Reimagined turned view bobbing off once; delete to do it again\n");
			} catch (IOException e) {
				UltraCraft.LOG.warn("could not write {}", viewBob, e);
			}
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

	private static boolean migrate(KeyBinding binding, int... oldDefaults) {
		InputUtil.Key now = ((KeyBindingAccessor) binding).ultracraft$getBoundKey();
		for (int old : oldDefaults)
			if (now.getCategory() == InputUtil.Type.KEYSYM && now.getCode() == old) {
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
