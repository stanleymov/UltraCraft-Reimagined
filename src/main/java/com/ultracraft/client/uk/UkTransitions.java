package com.ultracraft.client.uk;

import java.util.HashMap;
import java.util.Map;

/**
 * What the game's AnimatorControllers do after a one-shot state: the state's speed multiplier and its
 * exit-time transition back to Idle (normalized exit time, cross-fade seconds). Read from the controllers
 * with tools/re/controller_transitions.py (Punch, PunchRed, Revolver Heavy, ShotgunNew, Nailgun New,
 * RocketLauncher, Railcannon, GreenArm). States without an entry hold their last frame, like the game.
 */
public final class UkTransitions {
	/** speed multiplier, exit fraction, fade seconds (exit < 0 = no exit transition). */
	public record State(float speed, float exit, float fade) {}

	private static final Map<String, Map<String, State>> TABLE = new HashMap<>();
	private static final Map<String, String> IDLE = new HashMap<>();

	private static void put(String rig, String clip, float speed, float exit, float fade) {
		TABLE.computeIfAbsent(rig, r -> new HashMap<>()).put(clip.toLowerCase(), new State(speed, exit, fade));
	}

	static {
		// Punch.controller (Feedbacker): Jab/Jab2/Hook → Idle on exit; CoinFlip returns at 0.75
		IDLE.put("feedbacker", "Idle");
		put("feedbacker", "Equip", 1f, 0.9914f, 0.0053f);
		put("feedbacker", "Jab", 1f, 0.6455f, 0.3481f);
		put("feedbacker", "Jab2", 1f, 0.6455f, 0.3481f);
		put("feedbacker", "JabHolding", 1f, 0.6455f, 0.3481f);
		put("feedbacker", "Hook", 1f, 0.6627f, 0.3354f);
		put("feedbacker", "CoinFlip", 1f, 0.75f, 0.15f);
		put("feedbacker", "CoinFlipHolding", 1f, 0.75f, 0.15f);
		// Shop Layer: ShopTap -> ShopIdle at 0.7 with a 0.25 s fade
		put("feedbacker", "ShopTap", 1f, 0.7f, 0.25f);
		// PunchRed.controller (Knuckleblaster): Jab state plays the "Punch" clip at 0.85
		IDLE.put("knuckleblaster", "Idle");
		put("knuckleblaster", "Punch", 0.85f, 0.95f, 0.25f);
		put("knuckleblaster", "Hook", 1f, 0.6627f, 0.3354f);
		put("knuckleblaster", "CoinFlip", 1f, 0.75f, 0.15f);
		put("knuckleblaster", "PunchBlast", 1f, 0.97f, 0.2f);
		put("knuckleblaster", "Equip", 1f, 0.99f, 0.02f);
		// Shop Layer (both states at speed 0.75): ShopTap -> ShopIdle (the ShopPoint clip) at 0.7 with a 0.25 s fade
		put("knuckleblaster", "ShopTap", 0.75f, 0.7f, 0.25f);
		put("knuckleblaster", "ShopPoint", 0.75f, -1f, 0f);
		// Revolver Heavy.controller: PickUp → Idle at 0.625 / 0.25; Shoot states run at 1.2
		IDLE.put("revolver", "Idle");
		put("revolver", "PickUp", 1f, 0.625f, 0.25f);
		put("revolver", "PickUpWithReload", 1f, 0.9f, 0.25f);
		put("revolver", "Shoot", 1.2f, 0.8231f, 0.1179f);
		put("revolver", "Shoot2", 1.2f, 0.8182f, 0.1212f);
		put("revolver", "Shoot3", 1.2f, 0.8231f, 0.1179f);
		put("revolver", "ShootTwirl", 1f, 0.95f, 0.1f);
		// Revolver2.controller (the alternate revolvers): PickUp at 0.85 (or PickUpWithReload while SlowPickup), every
		// state at speed 1 and out to Idle at its end without a blend
		IDLE.put("revolver_alt", "Idle");
		put("revolver_alt", "PickUp", 0.85f, 1.002f, 0f);
		put("revolver_alt", "PickUpWithReload", 1f, 0.976f, 0.034f);
		put("revolver_alt", "Shoot", 1f, 1.01f, 0f);
		put("revolver_alt", "Shoot2", 1f, 0.998f, 0f);
		put("revolver_alt", "Shoot3", 1f, 1.001f, 0f);
		put("revolver_alt", "ShootTwirl", 1f, 0.905f, 0.066f);
		// ShotgunNew.controller
		IDLE.put("shotgun", "Idle");
		put("shotgun", "Equip", 1f, 0.9834f, 0.0166f);
		put("shotgun", "FireWithThrowReload", 1.5f, 0.9063f, 0.25f);
		put("shotgun", "Pump2", 1.65f, 0.9769f, 0f);
		put("shotgun", "FireWithPump", 1.1f, 0.8171f, 0.25f);
		put("shotgun", "FireWithReload", 1.1f, 0.9861f, 0.0159f);
		put("shotgun", "FireNoReload", 1f, 0.9582f, 0.0279f);
		put("shotgun", "IdleSawing", 5f, -1f, 0f);
		// Nailgun New.controller
		IDLE.put("nailgun", "Idle");
		put("nailgun", "Equip", 1.45f, 0.9165f, 0.0686f);
		put("nailgun", "Shoot", 1f, 1.0f, 0.0358f);
		// RocketLauncher.controller
		IDLE.put("rocket", "Idle");
		put("rocket", "Equip", 1f, 1f, 0f);
		put("rocket", "Fire", 1f, 1f, 0f);
		put("rocket", "Spray", 0.66f, 0.9771f, 0.0055f);
		// Railcannon.controller: Equip / Fire hold their last frame (no Idle state)
		// GreenArm.controller (Whiplash): Idle default; Throw / Pull / Catch are played by HookArm itself
		IDLE.put("whiplash", "Idle");
		// no transitions at all in GreenArm: each clip holds its last frame until HookArm plays the next one (the Throw
		// pose stays out while the hook flies; it fell back to Idle after 0.5 s and came up again on the Pull)
		put("whiplash", "Throw", 1f, -1f, 0f);
		put("whiplash", "Pull", 1f, -1f, 0f);
		put("whiplash", "Catch", 1f, -1f, 0f);
		put("whiplash", "Inspect", 0.5f, -1f, 0f);
		// Hammer.controller (Jackhammer): Fire / FireStrong → Idle at ≈0.98; FireStrongest → IdleCooldown; Idle runs at 0.5,
		// IdleSawing at 10; Equip / NadeSpawn / Pump / SawingShot → Idle at the end
		IDLE.put("hammer", "Idle");
		put("hammer", "Equip", 1f, 0.9999998f, 0f);
		put("hammer", "Fire", 1f, 0.9776531f, 0.0216f);
		put("hammer", "FireStrong", 1f, 0.9827608f, 0.0224f);
		put("hammer", "FireStrongest", 1f, 0.9617251f, 0.0498f);
		put("hammer", "NadeSpawn", 1f, 0.9999998f, 0f);
		put("hammer", "Pump", 1f, 0.9999998f, 0f);
		put("hammer", "SawingShot", 1f, 0.9963918f, 0.0017f);
		put("hammer", "Idle", 0.5f, -1f, 0f);
		put("hammer", "IdleCooldown", 1f, -1f, 0f);
		put("hammer", "IdleSawing", 10f, -1f, 0f);
		// Nailgun2.controller (Sawblade Launcher): Equip → Idle 0.9486 / 0.0514, Shoot 0.9143 / 0.0571, ShootSuper 0.9318 / 0.0318; Idle at 0.5
		IDLE.put("sawblade", "Idle");
		put("sawblade", "Equip", 1f, 0.948611f, 0.0514f);
		put("sawblade", "Shoot", 1f, 0.9142857f, 0.0571f);
		put("sawblade", "ShootSuper", 1f, 0.931805f, 0.0318f);
		put("sawblade", "Idle", 0.5f, -1f, 0f);
		// V1Platformer.controller (third-person V1): no automatic exits here — V1Renderer's state machine owns every
		// transition (an automatic return to Idle left it believing a finished Dash / Jump was still playing)
	}

	public static State state(String rig, String clip) {
		Map<String, State> m = TABLE.get(rig);
		return m == null ? null : m.get(clip.toLowerCase());
	}

	public static String idle(String rig) {
		return IDLE.get(rig);
	}
}
