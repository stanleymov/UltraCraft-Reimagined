package com.ultracraft.entity;

/**
 * ULTRAKILL projectiles the server simulates and the client draws with the exported meshes. Speeds in u/s,
 * damage in ULTRAKILL points, straight from the prefabs (docs/ULTRAKILL_MECHANICS.md).
 */
public enum ProjectileKind {
	/** Shotgun Projectile: speed 75 ±10, damage 1 → DeliverDamage damage/4 = 0.25, 5 s life, no gravity. */
	PELLET("proj_pellet", 75f, 0.25f, false, 5f, 0.2f),
	/** Nail (Overheat / JumpStart): 200 u/s, 0.185, sticks 5 s. */
	NAIL("proj_nail", 200f, 0.185f, false, 5f, 0.16f),
	/** NailFodder (Attractor): 0.205 with the fodder boost (×2 Filth/Stray, ×1.5 Soldier-class). */
	NAIL_FODDER("proj_nail", 200f, 0.205f, false, 5f, 0.16f),
	/** NailHeated (Overheat dump / Sawblade heated): 0.185, ignites. */
	NAIL_HEATED("proj_nail", 200f, 0.185f, false, 5f, 0.16f),
	/** Grenade (Core Eject): gravity, explodes on impact (Explosion 35 / 6). */
	CORE("proj_grenade", 0f, 0f, true, 30f, 0.5f),
	/** Rocket: 100 u/s straight, explodes on impact (harmless = no player damage on surfaces), freezes. */
	ROCKET("proj_rocket", 100f, 0f, false, 30f, 0.35f),
	/** Cannonball: 150 u/s max, gravity, 1 bounce (+2 damage), damage 5, durability 2. */
	CANNONBALL("proj_cannonball", 150f, 5f, true, 30f, 1f),
	/** Harpoon with a Magnet (Attractor): 100 u/s, gravity (mass 100), sticks, pulls nails; health 3. */
	MAGNET("proj_harpoon", 100f, 0f, true, 30f, 0.1f),
	/** Harpoon Malicious (Screwdriver): 250 u/s, pierce damage 3, then 116 drill hits of 0.0625 every 0.05 s; 8 s bomb. */
	SCREWDRIVER("proj_harpoon", 250f, 3f, true, 30f, 0.1f),
	/** Gasoline droplet (Firestarter): 150 u/s, gravity; ignites what it hits, stains the ground. */
	GASOLINE("proj_pellet", 150f, 0f, true, 10f, 0.5f),
	/** Zapper (JumpStart): 100 u/s, tethers to an enemy (20 u), discharges 10 damage of stored nail charge. */
	ZAPPER("proj_nail", 100f, 10f, false, 30f, 0.16f),
	/** NailAlt (Sawblade Launcher): 200 u/s, damage 0.6, 2.9 hits (−1 per enemy, −0.125 per bounce), bounces off walls. */
	SAWBLADE("proj_sawblade", 200f, 0.6f, false, 10f, 0.16f),
	/** NailAltFodder (the Attractor's saw launcher): damage 0.75 with the fodder boost, 3.9 hits. */
	SAWBLADE_FODDER("proj_sawblade", 200f, 0.75f, false, 10f, 0.16f),
	/** NailAltHeated: damage 1, 20.9 hits, up to 3 hits on the same enemy, bounces to the surface normal, burns. */
	SAWBLADE_HEATED("proj_sawblade", 200f, 1f, false, 10f, 0.16f),
	/** ChainsawProjectile.prefab: the Sawed-On's chained saw (damage 1.5, speed from the rev; see UKProjectileEntity.tickChainsaw). */
	CHAINSAW("proj_chainsaw", 60f, 1.5f, false, 30f, 0.4f);

	public final String rig;
	public final float speed, damage, life, scale;
	public final boolean gravity;

	ProjectileKind(String rig, float speed, float damage, boolean gravity, float life, float scale) {
		this.rig = rig;
		this.speed = speed;
		this.damage = damage;
		this.gravity = gravity;
		this.life = life;
		this.scale = scale;
	}

	public boolean nail() {
		return this == NAIL || this == NAIL_FODDER || this == NAIL_HEATED;
	}

	public boolean sawblade() {
		return this == SAWBLADE || this == SAWBLADE_FODDER || this == SAWBLADE_HEATED;
	}

	public boolean harpoon() {
		return this == MAGNET || this == SCREWDRIVER;
	}
}
