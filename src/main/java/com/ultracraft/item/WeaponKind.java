package com.ultracraft.item;

/**
 * ULTRAKILL's weapon slots. `rig` is the first-person rig name (one per weapon: variations share mesh and
 * textures, the variation colour is a tint), `icons` the WeaponDescriptor icon / glowIcon per variation.
 * `altRig` / `altIcons` are the alternate weapon of the slot (Minos revolver, Jackhammer, Sawblade Launcher).
 */
public enum WeaponKind {
	REVOLVER("revolver", 1, new String[] {"Piercer", "Marksman", "Sharpshooter"},
		new String[] {"singlerevolver", "revolverspecial", "revolversharp"}, new String[] {"singlerevolverglow", "revolverspecialglow", "revolversharpglow"},
		"revolver_alt", new String[] {"revolveraltsingle", "revolveraltspecial", "revolveraltsharp"}, new String[] {"revolveraltsingleglow", "revolveraltspecialglow", "revolveraltsharpglow"}),
	SHOTGUN("shotgun", 2, new String[] {"Core Eject", "Pump Charge", "Sawed-On"},
		new String[] {"shotgun", "shotgun1", "shotgunsaw"}, new String[] {"shotgunglow", "shotgun1glow", "shotgunsawglow"},
		"hammer", new String[] {"hammer", "hammerpump", "hammersaw"}, new String[] {"hammerglow", "hammerpumpglow", "hammersawglow"}),
	NAILGUN("nailgun", 3, new String[] {"Attractor", "Overheat", "JumpStart"},
		new String[] {"nailgun2", "nailgunoverheat", "nailgunzapper"}, new String[] {"nailgunglow2", "nailgunoverheatglow", "nailgunzapperglow"},
		"sawblade", new String[] {"sawbladelauncher", "sawbladelauncheroverheat", "sawbladelauncherzapper"}, new String[] {"sawbladelauncherglow", "sawbladelauncheroverheatglow", "sawbladelauncherzapperglow"}),
	RAILCANNON("railcannon", 4, new String[] {"Electric", "Screwdriver", "Malicious"},
		new String[] {"railcannon", "railcannonscrew", "railcannonmalicious"}, new String[] {"railcannonglow", "railcannonscrewglow", "railcannonmaliciousglow"},
		null, null, null),
	ROCKET("rocket", 5, new String[] {"Freezeframe", "S.R.S. Cannon", "Firestarter"},
		new String[] {"rocketlauncher", "rocketlaunchercannon", "rocketlaunchergasoline"}, new String[] {"rocketlauncherglow", "rocketlaunchercannonglow", "rocketlaunchergasolineglow"},
		null, null, null);

	public final String rig, altRig;
	public final int slot;
	public final String[] variationNames, icons, glowIcons, altIcons, altGlowIcons;

	WeaponKind(String rig, int slot, String[] variationNames, String[] icons, String[] glowIcons, String altRig, String[] altIcons, String[] altGlowIcons) {
		this.rig = rig;
		this.slot = slot;
		this.variationNames = variationNames;
		this.icons = icons;
		this.glowIcons = glowIcons;
		this.altRig = altRig;
		this.altIcons = altIcons;
		this.altGlowIcons = altGlowIcons;
	}

	public boolean hasAlt() {
		return altRig != null;
	}

	public String rig(boolean alt) {
		return alt && altRig != null ? altRig : rig;
	}

	public String icon(int variation, boolean alt) {
		return alt && altIcons != null ? altIcons[variation] : icons[variation];
	}

	public String glowIcon(int variation, boolean alt) {
		return alt && altGlowIcons != null ? altGlowIcons[variation] : glowIcons[variation];
	}

	/** StyleHUD freshness key / GunControl weapon id, e.g. "shotgun1". */
	public String id(int variation) {
		return (this == ROCKET ? "rocket" : rig) + variation;
	}
}
