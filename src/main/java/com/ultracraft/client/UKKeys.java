package com.ultracraft.client;

import com.ultracraft.client.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * ULTRAKILL's controls (InputActions), on the user's defaults: dash and slide on Minecraft's sprint and sneak keys,
 * mouse 4 (back) punch, G change fist, mouse 5 (forward) whiplash, C next variation, R last weapon, the hotbar keys
 * for the weapons.
 * Key state is polled straight from GLFW at the start of every client tick (`press`/`held`), the way
 * ULTRAKILL's `WasPerformedThisFrame` / `IsPressed` work, so a tap between ticks is never lost.
 */
public final class UKKeys {
	private static final String CATEGORY = "category.ultracraft";
	// Dash and slide / slam are Minecraft's own sprint and sneak keys (read directly, see tick()). The ULTRAKILL-only
	// actions sit on keys vanilla leaves free; the one exception is creative's save-hotbar key, moved from C to B for
	// next variation (ControlDefaults).
	public static final KeyBinding punch = new KeyBinding("key.ultracraft.punch", InputUtil.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_4, CATEGORY);
	public static final KeyBinding changeFist = new KeyBinding("key.ultracraft.change_fist", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
	public static final KeyBinding hook = new KeyBinding("key.ultracraft.hook", InputUtil.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_5, CATEGORY);
	public static final KeyBinding nextVariation = new KeyBinding("key.ultracraft.next_variation", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
	public static final KeyBinding lastWeapon = new KeyBinding("key.ultracraft.last_weapon", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);

	public static final KeyBinding[] ALL = {punch, changeFist, hook, nextVariation, lastWeapon};

	/** Edge-detected press this tick (true for exactly one tick per press). */
	public static final class Key {
		private final KeyBinding binding;
		public boolean held, press, release;
		boolean wasHeld;
		/** Press / release events since the last poll: a tap shorter than a tick still counts (vanilla counts presses too). */
		boolean queuedPress, queuedRelease;
		public int heldTicks;

		Key(KeyBinding binding) {
			this.binding = binding;
		}

		/** Down when input came back (a menu's button click closing it): ignored until it is let go. */
		boolean blocked, wasAllowed;

		void poll(MinecraftClient client, boolean allowed) {
			boolean phys = physicallyDown(client, binding);
			if (allowed && !wasAllowed && phys) blocked = true;
			if (!phys) blocked = false;
			wasAllowed = allowed;
			boolean now = allowed && phys && !blocked;
			press = (now && !wasHeld) || (allowed && !blocked && queuedPress);
			// a release counts even while input is not allowed (focus lost mid-charge releases the charge)
			release = (!now && wasHeld) || (queuedRelease && !now);
			held = now;
			heldTicks = now ? heldTicks + 1 : 0;
			wasHeld = now;
			queuedPress = queuedRelease = false;
		}

		/** This press was used for something else (a block's right click): the key reads as up until it is let go. */
		public void consume() {
			blocked = true;
			press = held = wasHeld = false;
			heldTicks = 0;
		}

		void event(boolean pressed) {
			if (pressed) queuedPress = true; else queuedRelease = true;
		}
	}

	private static Key[] tracked() {
		return JUMP == null ? KEYS : new Key[] {PUNCH, CHANGE_FIST, HOOK, NEXT_VAR, LAST_WEAPON, JUMP, FORWARD, BACK, LEFT, RIGHT, ATTACK, USE, DASH, SLIDE};
	}

	/** Raw mouse button event (Forge InputEvent.MouseButton.Pre). */
	public static void onMouse(MinecraftClient client, int button, int action) {
		if (client.currentScreen != null) return;
		boolean pressed = action == GLFW.GLFW_PRESS;
		if (!pressed && action != GLFW.GLFW_RELEASE) return;
		for (Key k : tracked()) if (k.binding.matchesMouse(button)) k.event(pressed);
		// Minecraft only acts on the click at the next tick (up to 50 ms later, a coin moves ~0.6 blocks in that
		// time): remember what the player saw and aimed at now
		if (pressed && ATTACK != null && ATTACK.binding.matchesMouse(button)) com.ultracraft.client.weapon.CoinAim.snapshot(client);
	}

	public static final Key PUNCH = new Key(punch), CHANGE_FIST = new Key(changeFist),
		HOOK = new Key(hook), NEXT_VAR = new Key(nextVariation), LAST_WEAPON = new Key(lastWeapon);
	/** Vanilla keys read the same way, so movement never sees Minecraft's sneak/sprint handling. DASH = sprint, SLIDE = sneak. */
	public static Key JUMP, FORWARD, BACK, LEFT, RIGHT, ATTACK, USE, DASH, SLIDE;
	/**
	 * ULTRAKILL's slot re-press: pressing the hotbar key of the slot that is already selected cycles the held weapon's
	 * variation. Detected from the key event itself (before vanilla handles the hotbar keys in the tick), so a plain
	 * slot switch never counts. `sameSlotPress` is true for the one tick after such a press.
	 */
	public static boolean sameSlotPress;
	private static boolean sameSlotEvent;

	private static final Key[] KEYS = {PUNCH, CHANGE_FIST, HOOK, NEXT_VAR, LAST_WEAPON};

	private UKKeys() {}

	/** Start of each client tick, before anything reads keys. */
	public static void tick(MinecraftClient client) {
		if (JUMP == null) {
			JUMP = new Key(client.options.jumpKey);
			FORWARD = new Key(client.options.forwardKey);
			BACK = new Key(client.options.backKey);
			LEFT = new Key(client.options.leftKey);
			RIGHT = new Key(client.options.rightKey);
			ATTACK = new Key(client.options.attackKey);
			USE = new Key(client.options.useKey);
			DASH = new Key(client.options.sprintKey);
			SLIDE = new Key(client.options.sneakKey);
		}
		boolean allowed = client.currentScreen == null && client.player != null && client.isWindowFocused();
		for (Key k : KEYS) k.poll(client, allowed);
		for (Key k : new Key[] {JUMP, FORWARD, BACK, LEFT, RIGHT, ATTACK, USE, DASH, SLIDE}) k.poll(client, allowed);
		sameSlotPress = sameSlotEvent && allowed;
		sameSlotEvent = false;
	}

	/** Raw key event (Forge InputEvent.Key): a press of the selected slot's own hotbar key is a re-press. */
	public static void onKey(MinecraftClient client, int key, int scanCode, int action) {
		if (client.currentScreen != null || client.options == null) return;
		if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_RELEASE) {
			boolean pressed = action == GLFW.GLFW_PRESS;
			for (Key k : tracked()) if (k.binding.matchesKey(key, scanCode)) k.event(pressed);
		}
		if (action != GLFW.GLFW_PRESS || client.player == null) return;
		int slot = client.player.getInventory().selectedSlot;
		if (slot >= 0 && slot < client.options.hotbarKeys.length && client.options.hotbarKeys[slot].matchesKey(key, scanCode)) sameSlotEvent = true;
	}

	/** Whether the button bound to `key` is physically down right now. */
	public static boolean physicallyDown(MinecraftClient client, KeyBinding key) {
		InputUtil.Key bound = ((KeyBindingAccessor) key).ultracraft$getBoundKey();
		long window = client.getWindow().getHandle();
		if (bound.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(window, bound.getCode()) == GLFW.GLFW_PRESS;
		if (bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() != -1) return InputUtil.isKeyPressed(window, bound.getCode());
		return false;
	}
}
