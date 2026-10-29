package com.ultracraft;

import net.minecraftforge.common.ForgeConfigSpec;

/** Player options (Mods → UltraCraft → Config): the few ULTRAKILL settings that matter in Minecraft. */
public final class UkConfig {
	public static final ForgeConfigSpec SPEC;
	public static final ForgeConfigSpec.BooleanValue SPEEDOMETER;
	public static final ForgeConfigSpec.BooleanValue PARRY_FLASH;
	public static final ForgeConfigSpec.DoubleValue ZOOM_SENSITIVITY;
	public static final ForgeConfigSpec.BooleanValue DROWNING;
	public static final ForgeConfigSpec.BooleanValue OFFHAND_WEAPONS;
	public static final ForgeConfigSpec.BooleanValue AIR_BRAKE;
	public static final ForgeConfigSpec.BooleanValue SHORT_HOP;

	static {
		ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
		b.push("hud");
		SPEEDOMETER = b.comment("Show the speedometer (SPEED: u/s) under the HUD, like ULTRAKILL's Speedometer option").define("speedometer", false);
		PARRY_FLASH = b.comment("White screen flash on a successful parry (ULTRAKILL's Parry Flash option)").define("parryFlash", true);
		b.pop();
		b.push("controls");
		ZOOM_SENSITIVITY = b.comment("Mouse sensitivity while zoomed in with the railcannon, as a share of normal (ULTRAKILL scales it with the zoom; 0.4 by the user's choice)").defineInRange("zoomSensitivity", 0.4, 0.05, 1.0);
		b.pop();
		b.push("movement");
		AIR_BRAKE = b.comment("Air brake: letting go of every movement key in mid-air slows V1's sideways drift, to land on a single block (not in ULTRAKILL; launches faster than walking keep their speed)").define("airBrake", true);
		SHORT_HOP = b.comment("Tap jump for a short hop (about a block), hold it for ULTRAKILL's full jump (not in ULTRAKILL)").define("shortHop", false);
		b.pop();
		b.push("gameplay");
		DROWNING = b.comment("Minecraft drowning under water (V1 has no air to run out of in ULTRAKILL: off by default)").define("drowning", false);
		OFFHAND_WEAPONS = b.comment("An ULTRAKILL weapon in the off-hand slot is drawn on the left (mirrored, like ULTRAKILL's dual wield) and fires together with the main-hand weapon on left and right click; off: it is only held like any other item").define("offhandWeapons", true);
		b.pop();
		SPEC = b.build();
	}

	private UkConfig() {}

	public static boolean speedometer() {
		return SPEC.isLoaded() && SPEEDOMETER.get();
	}

	public static double zoomSensitivity() {
		return SPEC.isLoaded() ? ZOOM_SENSITIVITY.get() : 0.4;
	}

	/** Off unless the player turned it on (also off where this config is not loaded). */
	public static boolean drowning() {
		return SPEC.isLoaded() && DROWNING.get();
	}

	public static boolean offhandWeapons() {
		return !SPEC.isLoaded() || OFFHAND_WEAPONS.get();
	}

	public static boolean airBrake() {
		return !SPEC.isLoaded() || AIR_BRAKE.get();
	}

	public static boolean shortHop() {
		return SPEC.isLoaded() && SHORT_HOP.get();
	}

	public static boolean parryFlash() {
		return !SPEC.isLoaded() || PARRY_FLASH.get();
	}
}
