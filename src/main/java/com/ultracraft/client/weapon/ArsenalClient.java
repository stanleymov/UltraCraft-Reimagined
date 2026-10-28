package com.ultracraft.client.weapon;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UKKeys;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.client.v1.V1;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.net.FirePayload;
import com.ultracraft.net.Net;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Client side of Shotgun / Nailgun / Railcannon / Rocket Launcher: ports of their Update loops with the
 * WeaponCharges regen rates, driving the rigs' animator clips and sending Fire packets (ArsenalServer).
 */
public final class ArsenalClient {
	public static final ArsenalClient INSTANCE = new ArsenalClient(false);
	/** The off-hand gun's own copy (OffhandGun): its charges, cooldowns and rigs, run with the hands swapped. */
	public static final ArsenalClient OFF = new ArsenalClient(true);

	/** The copy whose code is running: the off-hand gun's while OffhandGun runs it, else the main one. */
	public static ArsenalClient cur() {
		return OffhandGun.running() ? OFF : INSTANCE;
	}
	/** this is the off-hand copy: its rigs are ViewModel's off-hand instances */
	public final boolean off;
	private static final float DT = 0.05f;

	// WeaponCharges
	public float shoSawCharge = 1;
	public float naiAmmo = 100, naiMagnetCharge = 3, naiZapperRecharge = 5;
	public float raicharge = 5;
	public boolean railChargePlayed = true;
	public float rocketcharge;            // cooldown left (rateOfFire 1 s)
	public float rocketFreezeTime = 5, rocketCannonballCharge = 1, rocketNapalmFuel = 1;
	public boolean rocketFrozen;
	// Shotgun
	public boolean shotgunReady;
	public int primaryCharge;
	public float grenadeForce;
	public boolean charging;
	public float sinceLastCore = 1;
	/** Shotgun.resettingCores: set by the core shot, cleared by ReadyGun (its throw-reload) or a switch-in. */
	public boolean resettingCores;
	/** Shotgun.timeToBeep (Pump Charge at 3 charges). */
	public float pumpBeep;
	/** Seconds since the Core Eject's cores came back (a held charge waits weapons.coreReadyHold, showing the full meter). */
	public float coreReadyFor = 10;
	public float sawZoneTick;
	/** Shotgun.tempColor.a on the HeatSinkHeat material: 1 right after a shot (the sinks glow red), fading 2.5/s once ReleaseHeat vents them. */
	public float heatGlow;
	public boolean releasingHeat;
	private boolean sawIdling;
	// Nailgun
	public float heatUp, heatSinks = 2;

	/**
	 * Each nailgun and sawblade launcher (per variation) keeps its own heat, heat sinks and burnout while put away, like
	 * the game's separate weapon objects (WeaponCharges: naiHeatsinks for the Overheat nailgun, naiSawHeatsinks for the
	 * Overheat saw launcher); heatUp / heatSinks / burnOut above are the held one's. Key: alt * 3 + variation.
	 */
	private final java.util.Map<Integer, float[]> nailStates = new java.util.HashMap<>();

	/**
	 * The heat sinks a nailgun holds at most: 2 on the nailgun, 1 on the saw launcher (only the Overheat spends them; the
	 * others keep theirs full, which keeps their heat logic as it was).
	 */
	private static float sinkMax(int variation, boolean alt) {
		return alt ? 1 : 2;
	}

	private void saveNail() {
		if (outKind == WeaponKind.NAILGUN) nailStates.put((outAlt ? 3 : 0) + outVariation, new float[] {heatUp, heatSinks, burnOut ? 1 : 0});
	}

	private void loadNail(int variation, boolean alt) {
		float[] st = nailStates.remove((alt ? 3 : 0) + variation);
		heatUp = st == null ? 0 : st[0];
		heatSinks = st == null ? sinkMax(variation, alt) : st[1];
		burnOut = st != null && st[2] > 0;
	}
	public boolean burnOut, canShoot;
	public float fireCooldown;
	public int burstAmount;
	public float burstTimer;
	// Railcannon
	public boolean zooming;
	// Rocket Launcher
	public float cbCharge;
	private static final String CB_CHARGE = "uk.weapons.machinepumploop";
	private static final String CLOCK_TICK = "uk.ui.clockticktock";
	private float lastKnownFreezeTime = 5;
	private static final double SPRAY_RELEASE = Tune.def("spray.releaseSpeed", 0.66, "Firestarter release kick: the Spray clip's speed (from the game: the Spray state 0.66)");
	private static final double SPRAY_PIN = Tune.def("spray.pinBarrel", 1, "Firestarter spray: 1 = the barrel stays in place on the launcher (only the body kicks; by eye, the user's view of the game), 0 = the clip's barrel slide");
	/** Last tick's rocket timers, so the hologram can turn smoothly between the 20 Hz steps. */
	public float prevCbCharge, prevRocketFreezeTime = 5, prevRocketCannonballCharge = 1, prevRocketNapalmFuel = 1;
	public boolean firingNapalm;
	public float napalmCooldown;
	public float lastTimerAmount = -1;

	private WeaponKind outKind;
	private int outVariation = -1;
	private boolean hooked;
	private int hookedGen = -1;

	private ArsenalClient(boolean off) {
		this.off = off;
		Tune.def("view.nailFlashAlpha", 0.65, "nailgun muzzle star opacity (1 = solid)");
		Tune.def("rail.flashSize", 3.6, "railcannon muzzle flash size (x view.flashSize 1.6 = 5.76 u: muzzleflashblue at scale 18)");
		Tune.def("rail.flashSizeScrew", 1.0, "Screwdriver shot star at the gun (x view.flashSize 1.6 = 1.6 u: muzzleflashnailgun at scale 5)");
		Tune.def("rail.flashTimeScrew", 0.12, "Screwdriver shot star at the gun, s (until the screwdriver carries its own)");
		Tune.def("rail.flashSizeMalicious", 1.0, "Malicious railcannon muzzle flash size (charge2 at scale 5)");
		Tune.def("rail.flashTime", 0.6, "railcannon muzzle flash life, s (SpriteController fade 1/s)");
		Tune.def("rail.flashEnd", 0.2, "railcannon muzzle flash size and opacity when it ends");
		Tune.def("rail.shotLight", 1, "railcannon shot: the beam light on the gun (Light 20, range 10, fading 100/s)");
		Tune.def("weapons.sawZoneRate", 0.1, "seconds between Sawed-On chainsaw zone hits while revving (HurtZone cooldown)");
		Tune.def("view.heatFade", 1.2, "shotgun heat sink glow fade after ReleaseHeat, alpha/s (the game: 2.5)");
	}

	public void reset() {
		shoSawCharge = 1; naiAmmo = 100; naiMagnetCharge = 3; naiZapperRecharge = 5; raicharge = 5; railChargePlayed = true;
		rocketcharge = 0; rocketFreezeTime = 5; rocketCannonballCharge = 1; rocketNapalmFuel = 1; rocketFrozen = false;
		UKSound.stop(CLOCK_TICK);
		shotgunReady = true; primaryCharge = 0; grenadeForce = 0; charging = false;
		heatUp = 0; heatSinks = 2; burnOut = false; nailStates.clear(); canShoot = true; fireCooldown = 0;
		zooming = false; cbCharge = 0; firingNapalm = false;
		outKind = null; outVariation = -1; hooked = false;
	}

	public boolean alt;

	public RigInstance rig(WeaponKind k) {
		return vm(k.rig(alt));
	}

	/** This copy's instance of a rig (the off-hand copy has its own). */
	RigInstance vm(String name) {
		return off ? ViewModel.offInstance(name) : ViewModel.instance(name);
	}

	/** When the last magnet left the gun (its entity reaches this side a tick or two later). */
	private long lastMagnetShotNanos;

	/** Own magnets in the world (wc.magnets). */
	private static int magnetsOut() {
		net.minecraft.client.MinecraftClient c = net.minecraft.client.MinecraftClient.getInstance();
		if (c.world == null || c.player == null) return 0;
		int n = 0, me = c.player.getId();
		for (net.minecraft.entity.Entity e : c.world.getEntities())
			if (e instanceof com.ultracraft.entity.UKProjectileEntity m && m.kind() == com.ultracraft.entity.ProjectileKind.MAGNET && m.ownerId() == me) n++;
		return n;
	}

	/** Every tick, whatever is held (WeaponCharges.Charge). */
	public void charges(boolean firingNailgunAmmo) {
		// WeaponCharges.Charge: 0.25/s, halved while a chainsaw is still out (shoSawAmount), so juggling still charges
		if (shoSawCharge < 1) shoSawCharge = Math.min(1, shoSawCharge + (ChainsawFx.anyOut() ? 0.125f : 0.25f) * DT);
		if (!firingNailgunAmmo) naiAmmo = Math.min(100, naiAmmo + 5 * DT);
		// WeaponCharges.Charge: magnet charges come back at 3/s, up to 3 less the magnets still out
		int out = magnetsOut();
		if (out < 3 && naiMagnetCharge < 3 - out && System.nanoTime() - lastMagnetShotNanos > 300_000_000L)
			naiMagnetCharge = Math.min(3 - out, naiMagnetCharge + 3 * DT);
		if (naiZapperRecharge < 5) naiZapperRecharge = Math.min(5, naiZapperRecharge + DT);
		if (raicharge < 5) {
			raicharge = Math.min(5, raicharge + 0.25f * DT);
			if (raicharge >= 4 && !railChargePlayed) {
				// WeaponCharges: the full-charge sound plays at 4 and the charge snaps to 5
				raicharge = 5;
				railChargePlayed = true;
				// in hand, the variant's own FullCharge loop starts instead (railHum); put away, a one-shot tells it is ready
				if (railHum < 0 && outKind != WeaponKind.RAILCANNON) UKSound.play("uk.weapons.railcannonfull", 0.6f, 1f);
			}
		}
		if (rocketcharge > 0) rocketcharge = Math.max(0, rocketcharge - DT);
		if (rocketCannonballCharge < 1) rocketCannonballCharge = Math.min(1, rocketCannonballCharge + 0.125f * DT);
		if (rocketNapalmFuel < 1 && !firingNapalm) rocketNapalmFuel = Math.min(1, rocketNapalmFuel + 0.125f * DT);
		rocketFreezeTime = rocketFrozen ? Math.max(0, rocketFreezeTime - DT) : Math.min(5, rocketFreezeTime + 0.5f * DT);
		if (rocketFrozen) UKSound.setLoop(CLOCK_TICK, 0.5f, 0.75f + 0.25f * rocketFreezeTime / 5f);
		if (rocketFrozen && rocketFreezeTime <= 0) setFrozen(false);
		// WeaponCharges.Update: once a rocket was out while frozen (canAutoUnfreeze), the freeze ends by itself when none is
		// left and none went for 0.5 s (timeSinceIdleFrozen, reset as each one goes) - a frozen rocket the hammer sent off
		if (rocketFrozen) {
			int rockets = rocketsOut();
			if (rockets > 0) rocketCanAutoUnfreeze = true;
			if (rockets < lastRocketsOut) rocketIdleFrozen = 0;
			lastRocketsOut = rockets;
			rocketIdleFrozen += DT;
			if (rocketCanAutoUnfreeze && rockets == 0 && rocketIdleFrozen > 0.5f) setFrozen(false);
		}
		// the held nailgun's heat sinks refill while it is not firing; the ones put away refill and cool on their own
		// (WeaponCharges.Charge: sinks 0.125/s, heat 0.3/s)
		if (outKind == WeaponKind.NAILGUN) {
			float max = sinkMax(outVariation, outAlt);
			if (heatSinks < max && !UKKeys.ATTACK.held) heatSinks = Math.min(max, heatSinks + 0.125f * DT);
		}
		for (var en : nailStates.entrySet()) {
			float[] st = en.getValue();
			float max = sinkMax(en.getKey() % 3, en.getKey() >= 3);
			if (st[1] < max) st[1] = Math.min(max, st[1] + 0.125f * DT);
			if (st[0] > 0) st[0] = Math.max(0, st[0] - 0.3f * DT);
			st[2] = 0;
		}
	}

	private boolean outAlt;

	/** The railcannon variant whose FullCharge loop is playing (-1 = none) and the Malicious loop's rising pitch. */
	private int railHum = -1;
	private final boolean[] railPipOn = new boolean[5];
	private boolean railPipShown;
	private float railHumPitch = 1;
	private static final String[] RAIL_HUM = {"uk.weapons.railcannonfull", "uk.weapons.harpooncharged", "uk.weapons.throat_drone_stereo"};
	private static final float[] RAIL_HUM_VOL = {0.35f, 0.35f, 0.45f};

	/**
	 * Every client tick: the railcannon's FullCharge object (active while it is in hand and fully charged) carries a
	 * looping AudioSource that plays on awake, the variant's own sound: Electric RailcannonFull 0.35, Screwdriver
	 * HarpoonCharged 0.35, Malicious Throat Drone Stereo 0.45 whose pitch Railcannon.Update raises from 0 to 2 at 4/s.
	 * It stops when the shot empties the charge or the railcannon is put away.
	 */
	public void railHum(WeaponKind kind, int variation, boolean shown) {
		int want = shown && kind == WeaponKind.RAILCANNON && raicharge >= 5 ? Math.max(0, Math.min(2, variation)) : -1;
		if (want != railHum) {
			if (railHum >= 0) UKSound.stop(RAIL_HUM[railHum]);
			railHum = want;
			if (want >= 0) {
				railHumPitch = want == 2 ? 0.5f : 1f; // Minecraft plays 0.5..2
				UKSound.loop(RAIL_HUM[want], RAIL_HUM_VOL[want], railHumPitch);
			}
		}
		// RailCannonPip sounds: a capsule whose level is reached clicks once (only while it is in hand: switched in already
		// charged, the loops just start) and spins its RailcannonPipidle loop; the four left prong capsules at pitch 1, 1.5,
		// 2, 2.5 (click 0.35, loop 0.085), the main capsule at pitch 3 (loop 0.075). Minecraft plays up to pitch 2.
		boolean railShown = shown && kind == WeaponKind.RAILCANNON;
		for (int i = 0; i < 5; i++) {
			boolean on = railShown && raicharge >= (i < 4 ? i + 1 : 5);
			String key = "railpip" + i;
			float pitch = Math.min(2f, i < 4 ? 1f + 0.5f * i : 3f);
			if (on && !railPipOn[i]) {
				if (railPipShown && i < 4) UKSound.play("uk.weapons.railcannonpipclick", 0.35f, pitch);
				UKSound.loopAs(key, "uk.weapons.railcannonpipidle", i < 4 ? 0.085f : 0.075f, pitch);
			} else if (!on && railPipOn[i]) UKSound.stop(key);
			railPipOn[i] = on;
		}
		railPipShown = railShown;
		if (railHum == 2 && railHumPitch < 2) {
			railHumPitch = Math.min(2f, railHumPitch + 4 * DT);
			UKSound.setLoop(RAIL_HUM[2], RAIL_HUM_VOL[2], railHumPitch);
		}
	}

	public void tick(ClientPlayerEntity p, WeaponKind kind, int variation, boolean inputOk) {
		prevCbCharge = cbCharge;
		prevRocketFreezeTime = rocketFreezeTime;
		prevRocketCannonballCharge = rocketCannonballCharge;
		prevRocketNapalmFuel = rocketNapalmFuel;
		// (re)attach the animation-event listeners whenever the rigs were rebuilt (leaving and re-joining a world
		// recreated them without listeners: ReadyGun / ReadyToPunch never came and no weapon fired again)
		if (!hooked || hookedGen != ViewModel.generation) {
			hooked = false;
			hookedGen = ViewModel.generation;
			hookRigs();
		}
		alt = com.ultracraft.item.UKWeaponItem.alternate(p.getMainHandStack());
		switchIn(kind, variation, alt);
		// (the cores flung by the Core Eject stay red-hot through their whole throw-reload)
		if (releasingHeat && !resettingCores) heatGlow = Math.max(0, heatGlow - (float) Tune.get("view.heatFade") * DT);
		coreReadyFor += DT;
		// steam trails the flung cores for as long as they are out
		if (resettingCores && outKind == WeaponKind.SHOTGUN && !alt) GunDisplays.coreSteam();
		// Sawed-On: the attached chainsaw idles (ChainsawIdleStart, then the ChainsawIdle loop at 0.35)
		// (the Sawed-On hammer's chainsaw idles the same: its Chainsaw node carries ChainsawIdle 0.35 too)
		boolean sawIdle = kind == WeaponKind.SHOTGUN && variation == 2 && shoSawCharge >= 1 && !charging && !hammerSawing;
		if (sawIdle && !sawIdling) { UKSound.play("uk.weapons.chainsawidlestart", 0.35f, 1f); UKSound.loop("uk.weapons.chainsawidle", 0.35f, 1f); }
		else if (!sawIdle && sawIdling) UKSound.stop("uk.weapons.chainsawidle");
		sawIdling = sawIdle;
		switch (kind) {
			case SHOTGUN -> shotgun(p, variation, inputOk);
			case NAILGUN -> nailgun(p, variation, inputOk);
			case RAILCANNON -> railcannon(p, variation, inputOk);
			case ROCKET -> rocket(p, variation, inputOk);
			default -> {}
		}
	}

	/** The switch-in (Equip from the first frame) when the held arsenal weapon, variation or version changes. */
	public void switchIn(WeaponKind kind, int variation, boolean alt) {
		if (kind == outKind && variation == outVariation && alt == outAlt) return;
		outAlt = alt;
		// the new weapon's own rig: `alt` still held the last weapon's version when the switch is caught mid-frame
		// (advanceRigs), so Equip went to the other rig (alt revolver -> shotgun played it on the hammer, the sawblade
		// launcher's on the nailgun) and the gun just appeared
		this.alt = alt;
		if (outKind != null) { UKSound.stop("uk.weapons.electriccharge"); UKSound.stop("uk.weapons.chainsawloop"); UKSound.stop("uk.weapons.rocketsprayloop"); UKSound.stop("uk.weapons.machinegunspin"); nailSpin = 0; }
		hammerLoopsOff();
		if (kind == WeaponKind.SHOTGUN && alt) hammerOut();
		if (sawIdling) { UKSound.stop("uk.weapons.chainsawidle"); sawIdling = false; }
		// the nailgun put away keeps its heat and sinks; the one taken out gets its own back
		saveNail();
		if (kind == WeaponKind.NAILGUN) loadNail(variation, alt);
		// GunControl: weapon switched in → Equip clip, its event arms the gun
		outKind = kind;
		outVariation = variation;
		RigInstance r = rig(kind);
		if (r != null) r.play("Equip", 1, 0);
		shotgunReady = kind != WeaponKind.SHOTGUN || alt; // Equip's ReadyGun re-arms the shotgun (the hammer has no such event)
		resettingCores = false; // Shotgun.OnEnable
		canShoot = kind != WeaponKind.NAILGUN;     // Equip's CanShoot re-arms the nailgun and the sawblade launcher
		charging = false;
		grenadeForce = 0;
		primaryCharge = 0;
		cbCharge = 0;
		UKSound.stop(CB_CHARGE); // (switching away disables the gun, and its AudioSource with it)
		if (firingNapalm) stopNapalm();
	}

	private void hammerLoopsOff() {
		for (String l : new String[] {"uk.weapons.hammerpull", "uk.weapons.hammermotorloop_d2", "uk.environment.steamloop3", "uk.weapons.chainsawsparkloop"}) UKSound.stop(l);
		hammerOverheated = false;
	}

	/** Whether a weapon of this copy is out (it is put away when the hand empties). */
	public boolean isOut() {
		return outKind != null;
	}

	/** Called when no arsenal weapon is held. */
	public void putAway() {
		hammerLoopsOff();
		if (sawIdling) { UKSound.stop("uk.weapons.chainsawidle"); sawIdling = false; }
		if (outKind != null) {
			// no weapon loop outlives its weapon
			UKSound.stop("uk.weapons.electriccharge");
			UKSound.stop("uk.weapons.chainsawloop");
			UKSound.stop("uk.weapons.rocketsprayloop");
			UKSound.stop("uk.weapons.machinegunspin");
			nailSpin = 0;
			if (firingNapalm) stopNapalm();
			if (zooming) { zooming = false; V1.INSTANCE.zoomHeld = false; }
			charging = false;
			saveNail();
			outKind = null;
			outVariation = -1;
		}
	}

	static {
		Tune.def("weapons.coreReadyHold", 0, "Core Eject: after its reload a held right click waits this long, s (the full meter shows first)");
		Tune.def("rocket.fromBarrel", 1, "1 = rockets leave the launcher's barrel aimed at the crosshair (the user's call; the game: the camera); a Freezeframe rocket fired while frozen always leaves the eye");
		Tune.def("rocket.shotLight", 1, "rocket launcher shot light on the gun (RocketLauncherFire: white x 30), share of view.muzzleLight (by eye)");
		Tune.def("rocket.shotRings", 1, "1 = the rocket launcher's shot rings (RocketLauncherFire: grey from 8 at 1200/s, white from 4 at 600/s)");
		Tune.def("rocket.shotStar", 1, "1 = our star at the rocket launcher's barrel (not in the game: RocketLauncherFire has only the light and the rings)");
		Tune.def("view.shotgunLight", 0.25, "shotgun muzzle light on the gun, share of view.muzzleLight (1 blew the whole gun out orange)");
		Tune.def("view.shotgunFlashSize", 5.3, "shotgun muzzle star size (matched to a 60 fps game recording: its first frame covers about 2.6 times the area of the 3.3 we had)");
		Tune.def("view.shotgunFlashTime", 0.133, "shotgun muzzle star life, s (SpriteController 7.5/s: size and opacity fall linearly to nothing in 1/7.5 s)");
		Tune.def("view.shotgunFlashEnd", 0, "shotgun muzzle star size / opacity at the end of its life (the game: 0)");
	}

	/** When the last shotgun pellets left (for the shotgun parry window). */
	public static long lastPelletShotNanos;

	/** Nailgun.Shoot: the barrel the next nail leaves (shootPoints[barrelNum], alternating) and its spin (Spin.speed). */
	public int nailBarrel;
	public float nailSpin;
	/** Where the last nail left the gun, in the world, and when (its trail starts there). */
	/** Each hand's last nail barrel point and when (main 0, off-hand 1): a new nail's first frames run from the nearer one. */
	private static final net.minecraft.util.math.Vec3d[] nailMuzzle = new net.minecraft.util.math.Vec3d[2];
	private static final long[] nailMuzzleNanos = new long[2];

	/** The barrel a nail at `pos` just left (fired in the last 0.25 s, within 16 blocks), or null; [3] its age in s. */
	public static double[] nailMuzzleNear(net.minecraft.util.math.Vec3d pos) {
		double[] best = null;
		double bestD = 16;
		for (int i = 0; i < 2; i++) {
			net.minecraft.util.math.Vec3d m = nailMuzzle[i];
			double age = (System.nanoTime() - nailMuzzleNanos[i]) / 1e9;
			if (m == null || age >= 0.25 || pos.distanceTo(m) >= bestD) continue;
			bestD = pos.distanceTo(m);
			best = new double[] {m.x, m.y, m.z, age};
		}
		return best;
	}
	private net.minecraft.util.math.Vec3d fireOrigin, fireDir;

	private void fire(ClientPlayerEntity p, int weapon, int mode, int arg) {
		if (weapon == FirePayload.SHOTGUN && mode == 0) lastPelletShotNanos = System.nanoTime();
		boolean rail = weapon == FirePayload.RAILCANNON;
		if (rail) CoinAim.claim(p, CoinAim.origin(p), CoinAim.look(p), 1.2f);
		net.minecraft.util.math.Vec3d origin = fireOrigin != null ? fireOrigin : rail ? CoinAim.origin(p) : p.getEyePos();
		fireOrigin = null;
		net.minecraft.util.math.Vec3d dir = fireDir != null ? fireDir : rail ? CoinAim.look(p) : p.getRotationVec(1f);
		fireDir = null;
		// the Electric and Malicious beams: the mobs as this player saw them (the Screwdriver is a projectile)
		if (rail && com.ultracraft.item.UKWeaponItem.variation(p.getMainHandStack()) != 1) HitView.claim(p, origin, dir, 1.2f);
		Net.toServer(new FirePayload(weapon, mode, arg, origin, dir));
		// muzzle flashes (ShotgunMuzzleFlash / NailgunMuzzleFlash / the rocket's flash prefabs) at the shoot point bones
		// Shotgun.cs: the flash spawns at shootPoints[] (prefab children "ShootPoint L/R", outside the animator root)
		// (the Core Eject too: ShootSinks instantiates the same ShotgunMuzzleFlash, star and orange light)
		if (weapon == FirePayload.SHOTGUN && (mode == 0 || (mode == 1 && !alt))) {
			// ShotgunMuzzleFlash: scale 3 x MuzzleFlash 8 (three times the revolver's), SpriteController 7.5/s, and an
			// orange point light (1, 0.575, 0) x 20 on the gun
			float size = (float) Tune.get("view.shotgunFlashSize"), time = (float) Tune.get("view.shotgunFlashTime");
			if (alt) GunDisplays.flash("hammer", "ImpactHammer/Armature/Root/Hammer", "muzzleflashshotgun", size, time);
			else {
				GunDisplays.flashAt("shotgun", new org.joml.Vector3f(-3.933f, 1.432f, -0.363f), "muzzleflashshotgun", size); // ShootPoint L
				GunDisplays.flashTimeOverride(time);
			}
			// the game's star (60 fps recording): 6 frames, shrinking and fading to about 40 %
			GunDisplays.flashEndOverride((float) Tune.get("view.shotgunFlashEnd"));
			GunDisplays.muzzleLight(1f, 0.575f, 0f, (float) Tune.get("view.shotgunLight"));
		}
		else if (weapon == FirePayload.NAILGUN && mode == 0 && alt) {
			// NailgunMuzzleFlash 3: the muzzleflash sprite at 3 x 8 (three times the nailgun's), SpriteController shrinking
			// and fading at 25/s: big and bright for a frame or two, gone in 0.04 s
			GunDisplays.flash("sawblade", "Shootpoint", "muzzleflash", (float) Tune.get("saw.flashSize"), (float) Tune.get("saw.flashTime"));
			GunDisplays.flashEndOverride((float) Tune.get("saw.flashEnd"));
			GunDisplays.flashAlpha((float) Tune.get("saw.flashAlpha"));
		}
		else if (weapon == FirePayload.NAILGUN && mode == 0) {
			GunDisplays.flash("nailgun", nailBarrel == 1 ? "ShootPoint (1)" : "ShootPoint", "muzzleflashnailgun", 1f);
			GunDisplays.flashAlpha((float) Tune.get("view.nailFlashAlpha"));
		}
		else if (weapon == FirePayload.RAILCANNON) {
			// Railcannon Beam/MuzzleFlash (1): muzzleflashblue (0.32 u) at scale 18 = 5.76 u, SpriteController fade 1/s;
			// the Malicious beam's: charge2 at scale 5. Its Light (20, range 10) lights the gun in the beam's colour
			int rv = com.ultracraft.item.UKWeaponItem.variation(p.getMainHandStack());
			boolean mal = rv == 2;
			if (rv == 1) {
				// the Screwdriver fires Harpoon Malicious: no beam and (as the game shows it) no star, only its green trail;
				// the shot lights the gun green
				GunDisplays.muzzleLight(0.267f, 1f, 0.271f, (float) Tune.get("rail.shotLight"));
			} else {
				GunDisplays.flash("railcannon", "Armature/Base/Shootpoint", mal ? "charge2" : "muzzleflashblue",
					(float) Tune.get(mal ? "rail.flashSizeMalicious" : "rail.flashSize"), (float) Tune.get("rail.flashTime"));
				GunDisplays.flashEndOverride((float) Tune.get("rail.flashEnd"));
				GunDisplays.muzzleLight(mal ? 1f : 0.269f, mal ? 0.581f : 0.88f, mal ? 0f : 1f, (float) Tune.get("rail.shotLight"));
			}
		}
		else if (weapon == FirePayload.ROCKET && (mode == 0 || mode == 1)) {
			if (Tune.on("rocket.shotStar")) GunDisplays.flash("rocket", "RocketLauncher/Armature/Body_Bone/Shootpoint", "muzzleflash", 1.8f);
			// RocketLauncherFire at the shoot point (Shoot and ShootCannonball): its white Point Light (30, range 30, the
			// range down 160/s) on the gun and the world, and its two rings
			GunDisplays.muzzleLight(1f, 1f, 1f, (float) Tune.get("rocket.shotLight"));
			net.minecraft.util.math.Vec3d sp = GunDisplays.boneInWorld(rig(WeaponKind.ROCKET), "RocketLauncher/Armature/Body_Bone/Shootpoint");
			if (sp == null) sp = p.getEyePos().add(p.getRotationVec(1f).multiply(com.ultracraft.weapon.Damage.SCALE_BLOCKS));
			com.ultracraft.client.render.DynamicLights.flash(sp, 30, 160, 30);
			if (Tune.on("rocket.shotRings")) HammerFx.rocketRings(sp);
		}
	}

	// ------------------------------------------------------------------ Shotgun (Shotgun.Update)

	private void shotgun(ClientPlayerEntity p, int variation, boolean inputOk) {
		if (alt) { hammer(p, variation, inputOk); return; }
		RigInstance rig = rig(WeaponKind.SHOTGUN);
		boolean fire1 = inputOk && UKKeys.ATTACK.held;
		boolean fire2 = inputOk && UKKeys.USE.held;
		boolean fire2Pressed = inputOk && UKKeys.USE.press;
		sinceLastCore += DT;
		if (fire1 && shotgunReady) {
			shotgunReady = false;
			int charge = variation == 1 ? primaryCharge : 0;
			fire(p, FirePayload.SHOTGUN, 0, charge);
			// Shotgun.Shoot: player nudged +1 u/s forward, shake 1, FOV-less; sound 0.45 (pitch by pump)
			Vec3d f = p.getRotationVec(1f);
			V1.INSTANCE.vel = V1.INSTANCE.vel.add(new Vec3d(f.x, 0, f.z).normalize());
			V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 1);
			float pitch = variation == 1 ? switch (charge) { case 0 -> UKSound.range(1.15f, 1.25f); case 1 -> UKSound.range(0.95f, 1.05f); default -> UKSound.range(0.75f, 0.85f); } : UKSound.range(0.95f, 1.05f);
			UKSound.play(Math.random() < 0.5 ? "uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_03" : "uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_05", 0.45f, pitch);
			if (rig != null) rig.play(variation == 1 ? "FireWithPump" : "FireWithReload", 1, 0);
			if (variation == 1) primaryCharge = 0;
			heatGlow = 1; releasingHeat = false; // Shotgun.Shoot: tempColor.a = 1
		}
		if (fire2Pressed && variation == 1 && shotgunReady) {
			// Pump: charge up to 3 (3 = overpump on the next shot), warning beep at 3
			shotgunReady = false;
			if (primaryCharge < 3) primaryCharge++;
			if (rig != null) rig.play("Pump2", 1, 0);
		}
		// Shotgun.UpdateMeter (Pump Charge): at 3 charges the warning beep repeats whenever timeToBeep runs out (1 at
		// 5/s: every 0.2 s), and the meter blinks black while timeToBeep < 0.5
		if (variation == 1) {
			if (pumpBeep > 0) pumpBeep = Math.max(0, pumpBeep - 5 * DT);
			if (primaryCharge == 3 && pumpBeep == 0) { pumpBeep = 1; UKSound.play("uk.ui.csfx-2_mechanics_45", 0.25f, 1f); }
		}
		// Shotgun.Update: the Core Eject charges only while not resettingCores (the throw-reload has to reach ReadyGun);
		// the Sawed-On needs a full saw and gunReady
		boolean canCharge = variation == 0 ? !resettingCores && (charging || coreReadyFor >= Tune.get("weapons.coreReadyHold")) : (variation == 2 && shoSawCharge >= 1 && shotgunReady);
		if (fire2 && variation != 1 && canCharge) {
			if (!charging && variation == 2) { UKSound.play("uk.weapons.chainsawon", 0.7f, 1f); UKSound.loop("uk.weapons.chainsawloop", 0.5f, 1f); }
			if (!charging && variation == 0) UKSound.loop("uk.weapons.electriccharge", 0.5f, 0.5f);
			charging = true;
			if (grenadeForce < 60) grenadeForce = Math.min(60, grenadeForce + 60 * DT);
			if (variation == 0) UKSound.setLoop("uk.weapons.electriccharge", 0.5f, Math.max(0.5f, grenadeForce / 60f));
			if (variation == 2) {
				// the revving saw hurts what it touches (HurtZone)
				sawZoneTick += DT;
				if (sawZoneTick >= Tune.get("weapons.sawZoneRate")) { sawZoneTick = 0; fire(p, FirePayload.SHOTGUN, 2, 0); }
			}
		} else if (charging && variation != 1) {
			charging = false;
			sinceLastCore = 0;
			if (variation == 0) resettingCores = true;
			if (variation == 0) {
				UKSound.stop("uk.weapons.electriccharge");
				fire(p, FirePayload.SHOTGUN, 1, Math.round(grenadeForce));
				UKSound.play("uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_05", 0.45f * (float) Math.sqrt(Math.max(0, 1 - (grenadeForce * grenadeForce) / 3600f)), 1f);
				UKSound.play("uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_03", 0.45f * (grenadeForce / 60f), UKSound.range(0.75f, 0.85f));
				if (rig != null) rig.play("FireWithThrowReload", 1, 0);
				shotgunReady = false;
				// the cores the throw-reload flings out go red-hot (as seen in the game) and cool once ReleaseHeat vents them
				heatGlow = 1; releasingHeat = false;
			} else {
				shoSawCharge = 0;
				UKSound.stop("uk.weapons.chainsawloop");
				fire(p, FirePayload.SHOTGUN, 3, Math.round(grenadeForce));
				// Shotgun.ShootSaw plays the same pair as the core shot: GrenadeLauncherSound at 0.45 * sqrt(1 - (force/60)^2)
				// and shootSound (Shot - 03) at 0.45 * max(0.5, force/60), pitch 0.75..0.85
				UKSound.play("uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_05", 0.45f * (float) Math.sqrt(Math.max(0, 1 - (grenadeForce * grenadeForce) / 3600f)), 1f);
				UKSound.play("uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_03", 0.45f * Math.max(0.5f, grenadeForce / 60f), UKSound.range(0.75f, 0.85f));
				// Shotgun.ShootSaw: anim.Play("FireNoReload"), gunReady at once
				if (rig != null) rig.play("FireNoReload", 1, 0);
				shotgunReady = true;
			}
			V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 1);
			grenadeForce = 0;
		}
		if (variation == 2 && rig != null && charging && !"IdleSawing".equals(rig.currentClip()) && shotgunReady) rig.play("IdleSawing", 1, 0.1f);
	}

	// ------------------------------------------------------------------ Nailgun (Nailgun.Update / Shoot)

	/** The ULTRAKILL 0.008 s steps of the nailgun's fire still owed this tick. */
	private float nailStepAcc;

	/**
	 * Nailgun.UpdateAnimationWeight: the WeightLayer (Idle, override) over the Shoot kick. The Overheat (not burning
	 * out) holds 0.9 of Idle with no heat sink left (a small chug) and heat x 0.6 otherwise (a cold gun kicks fully);
	 * the burnout and the other variations 0 (the burnout's restarts every 16 ms leave only the kick's first instant).
	 */
	private float nailAnimWeight(int variation) {
		if (variation != 1 || burnOut) return 0f;
		return heatSinks < 1 ? 0.9f : heatUp * 0.6f;
	}

	/** Nailgun.Shoot (one nail); `delay` = how far into this tick its fixed step falls. */
	private void nailShot(ClientPlayerEntity p, RigInstance rig, int variation, float delay) {
		if (variation == 0) naiAmmo--;
		// the nail leaves the barrel's ShootPoint (where it is on screen), aimed along the look
		fireOrigin = nailMuzzle(rig);
		// the spread is picked here (ArsenalServer.scatter's rule) so this client's own nail and the server's fly the same path
		int nailArg = burnOut ? 1 : 0;
		if (fireOrigin != null && NailGhosts.enabled()) {
			float spread = (variation == 1 ? 20f : 10f) * (burnOut ? 2 : 1);
			fireDir = scatterNail(p.getRotationVec(1f), spread / 3f);
			nailArg |= 0x100;
			com.ultracraft.entity.ProjectileKind nk = burnOut ? com.ultracraft.entity.ProjectileKind.NAIL_HEATED : variation == 0 ? com.ultracraft.entity.ProjectileKind.NAIL_FODDER : com.ultracraft.entity.ProjectileKind.NAIL;
			NailGhosts.spawn(nk, fireOrigin, fireDir, 200);
		}
		if (fireOrigin != null && Tune.on("debug.weapons")) {
			net.minecraft.util.math.Vec3d rel = fireOrigin.subtract(p.getEyePos()), look = p.getRotationVec(1f);
			net.minecraft.util.math.Vec3d right = look.crossProduct(new net.minecraft.util.math.Vec3d(0, 1, 0)).normalize();
			com.ultracraft.UltraCraft.LOG.info("[weapons] nail muzzle barrel={} right={} up={} fwd={}", nailBarrel, String.format("%.2f", rel.dotProduct(right)), String.format("%.2f", rel.y), String.format("%.2f", rel.dotProduct(look)));
		}
		if (fireOrigin != null) { nailMuzzle[off ? 1 : 0] = fireOrigin; nailMuzzleNanos[off ? 1 : 0] = System.nanoTime(); }
		fire(p, FirePayload.NAILGUN, 0, nailArg);
		nailBarrel = 1 - nailBarrel;
		// the Shoot trigger (AnyState, no blend, onto itself) on the step it fell on
		if (rig != null) {
			rig.setOverlay("Idle", nailAnimWeight(variation));
			rig.playAfter("Shoot", delay);
		}
		// the muzzle flash's MachineGun3B (0.65): RandomPitch 1 +-0.1; with no heat sink Shoot sets 0.25 at pitch 0.75; the
		// burnout's flash ('NailgunMuzzleFlash 2') has nailgunOverheatFix, so its RandomPitch (1.1 +-0.05) runs after
		// Shoot's SetPitch(2) and wins; each on the step its shot fell on
		if (burnOut) UKSound.playAfter("uk.weapons.machinegun3b", 0.65f, UKSound.range(1.05f, 1.15f), delay);
		else if (heatSinks < 1) UKSound.playAfter("uk.weapons.machinegun3b", 0.25f, 0.75f, delay);
		else UKSound.playAfter("uk.weapons.machinegun3b", 0.65f, UKSound.range(0.9f, 1.1f), delay);
		V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, burnOut ? 0.35 : 0.1);
	}

	private void nailgun(ClientPlayerEntity p, int variation, boolean inputOk) {
		if (alt) { sawblade(p, variation, inputOk); return; }
		RigInstance rig = rig(WeaponKind.NAILGUN);
		// Nailgun.SetHeat: the "Barrel_x (1)" overlay meshes (Basic Color - Orange) get _Color.a = heat
		if (rig != null) rig.setMeshAlpha(n -> n.endsWith("(1)"), Math.min(1f, heatUp));
		boolean fire1 = inputOk && UKKeys.ATTACK.held;
		boolean fire2Pressed = inputOk && UKKeys.USE.press;
		float fireRate = 4f;
		// heat
		if (burnOut || heatSinks < 1) {
			heatUp = Math.max(0, heatUp - DT);
			if (burnOut && heatUp <= 0) burnOut = false;
		} else if (canShoot && fire1 && (heatUp < 1 || variation == 2)) {
			// Nailgun.Update: the Jump Start runs at full speed only with its zapper attached
			if (variation == 2) heatUp = zapper().state == ZapperClient.State.ATTACHED ? 1f : 0.33f;
			else if (variation == 0) heatUp = 1f; // Attractor ("variation 1" in code: magnets) runs hot
			else heatUp = Math.min(1, heatUp + 0.55f * DT);
		} else if (heatUp > 0 && (!canShoot || !fire1)) heatUp = Math.max(0, heatUp - 0.3f * DT);
		// fire rate (prefab fireRate 4; the number is a cooldown in 1/100 s)
		float currentFireRate;
		if (variation != 1) currentFireRate = fireRate + 3.5f - heatUp * 3.5f;
		else if (burnOut) currentFireRate = fireRate - 2.5f;
		else if (heatSinks >= 1) currentFireRate = heatUp < 0.5f ? fireRate : fireRate + (heatUp - 0.5f) * 7f;
		else currentFireRate = fireRate + 10f;
		boolean ammoOk = variation != 0 || naiAmmo >= 1;
		// Nailgun.Update: the barrels spin at 250 + heat x 2250 while it fires, winding down at 1000/s; the MachineGunSpin
		// loop on Barrel_L (0.2) follows at pitch spinSpeed / 1500 x 2
		if (canShoot && fire1 && ammoOk) nailSpin = 250 + heatUp * 2250;
		else nailSpin = Math.max(0, nailSpin - 1000 * DT);
		if (nailSpin > 0) UKSound.loop("uk.weapons.machinegunspin", 0.2f, Math.max(0.5f, Math.min(2f, nailSpin / 1500f * 2f)));
		else UKSound.stop("uk.weapons.machinegunspin");
		// Nailgun.FixedUpdate in ULTRAKILL's 0.008 s steps (6.25 a tick): the cooldown falls 100/s, a shot when it is 0 with
		// the button held (or on its own through a heat sink's burnout); the shots fall on their steps, not on whole ticks
		// (a 4 / 100 s cooldown is 25 shots a second; ticks alone gave 20, and halved past a 5)
		nailStepAcc += DT;
		float stepAt = 0;
		while (nailStepAcc >= 0.008f - 1e-6f) {
			nailStepAcc -= 0.008f;
			float delay = stepAt;
			stepAt += 0.008f;
			if (fireCooldown > 0) {
				fireCooldown = Math.max(0, fireCooldown - 0.8f);
				if (fireCooldown < 0.01f) fireCooldown = 0;
			}
			boolean ammoNow = variation != 0 || naiAmmo >= 1;
			if (canShoot && (fire1 || burnOut) && fireCooldown <= 0 && ammoNow) {
				fireCooldown = currentFireRate;
				nailShot(p, rig, variation, delay);
			}
		}
		// alt fire
		if (fire2Pressed) {
			if (variation == 1 && canShoot && !burnOut && heatSinks >= 1 && heatUp >= 0.1f) {
				// Overheat: dump a heat sink → burst of heated nails while the heat drains
				burnOut = true;
				fireCooldown = 0;
				heatSinks--;
			} else if (variation == 0) {
				if (naiMagnetCharge >= 1) {
					fire(p, FirePayload.NAILGUN, 1, 0);
					naiMagnetCharge--;
					lastMagnetShotNanos = System.nanoTime();
					UKSound.play("uk.weapons.harpoonshoot", 0.8f, 1f);
					if (rig != null && canShoot) rig.play("Shoot", 1, 0);
				} else UKSound.play("uk.weapons.revolver.click", 0.5f, 2f);
			} else if (variation == 2) {
				// Nailgun.ShootZapper: the Shoot trigger, HarpoonShoot (0.35)
				if (naiZapperRecharge >= 5) {
					zapper().shoot(p);
					if (rig != null && canShoot) rig.play("Shoot", 1, 0);
					UKSound.play("uk.weapons.harpoonshoot", 0.35f, 1f);
				} else UKSound.play("uk.weapons.revolver.click", 0.5f, 2f);
			}
		}
		if (variation == 2) zapHudSound();
	}

	/** The Jump Start display's RECHARGING overlay is up; Nailgun.UpdateZapHud rings the gun's AudioSource (Bell_10_b 0.2, pitch 3) as it goes. */
	private boolean zapOverlay;

	private void zapHudSound() {
		if (naiZapperRecharge < 5) { if (!zapper().out()) zapOverlay = true; }
		else if (zapOverlay) {
			zapOverlay = false;
			UKSound.play("uk.ui.bell_10_b", 0.2f, 3f);
		}
	}

	/** ArsenalServer.scatter. */
	private static net.minecraft.util.math.Vec3d scatterNail(net.minecraft.util.math.Vec3d dir, float spreadDeg) {
		if (spreadDeg <= 0) return dir;
		net.minecraft.util.math.Vec3d up = Math.abs(dir.y) > 0.99 ? new net.minecraft.util.math.Vec3d(1, 0, 0) : new net.minecraft.util.math.Vec3d(0, 1, 0);
		net.minecraft.util.math.Vec3d right = dir.crossProduct(up).normalize();
		net.minecraft.util.math.Vec3d realUp = right.crossProduct(dir).normalize();
		double a = Math.toRadians((Math.random() * 2 - 1) * spreadDeg), b = Math.toRadians((Math.random() * 2 - 1) * spreadDeg);
		return dir.add(right.multiply(Math.tan(a))).add(realUp.multiply(Math.tan(b))).normalize();
	}

	/** The current barrel's ShootPoint in the world, or null without the rig. */
	private net.minecraft.util.math.Vec3d nailMuzzle(RigInstance rig) {
		// the point the muzzle flash sits on: the ShootPoint as the gun was drawn this frame
		if (off) return offPoint(rig, nailBarrel == 0 ? "ShootPoint" : "ShootPoint (1)");
		org.joml.Vector3f v = GunDisplays.nailShootView[nailBarrel];
		return v == null ? null : HandRenderer.viewToWorld(v);
	}

	/** The off-hand copy's barrel point in the world: its own rig's bone, mirrored to the left as it is drawn. */
	private net.minecraft.util.math.Vec3d offPoint(RigInstance rig, String bone) {
		org.joml.Matrix4f m = rig == null ? null : rig.boneWorld(bone);
		if (m == null) return null;
		org.joml.Vector3f v = m.getTranslation(new org.joml.Vector3f());
		v.x = -v.x;
		return HandRenderer.viewToWorld(v);
	}

	/** Where the saw launcher's saws leave (its Shootpoint as drawn). */
	private net.minecraft.util.math.Vec3d sawOrigin() {
		if (off) return offPoint(rig(WeaponKind.NAILGUN), "Shootpoint");
		return GunDisplays.sawShootView == null ? null : HandRenderer.viewToWorld(GunDisplays.sawShootView);
	}

	// ------------------------------------------------------------------ Railcannon (Railcannon.Update)

	private void railcannon(ClientPlayerEntity p, int variation, boolean inputOk) {
		RigInstance rig = rig(WeaponKind.RAILCANNON);
		boolean fire1Pressed = inputOk && UKKeys.ATTACK.press;
		boolean fire2 = inputOk && UKKeys.USE.held;
		if (raicharge >= 5 && fire1Pressed) {
			raicharge = 0;
			railChargePlayed = false;
			fire(p, FirePayload.RAILCANNON, 0, 0);
			if (variation == 2) UKSound.play("uk.weapons.bluezone-autobots-shutdown-003", 1f, 2f);
			else UKSound.play("uk.weapons.railcannonfire4", 0.65f, 1f);
			if (rig != null) rig.play("Fire", 1, 0);
			V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 2);
		} else if (fire1Pressed) UKSound.play("uk.weapons.railcannonpipclick", 0.5f, 1f);
		if (fire2) {
			zooming = true;
			V1.INSTANCE.zoomHeld = true; // cc.Zoom(defaultFov / 2): eased in by V1.zoomFrame
		} else if (zooming) {
			zooming = false;
			V1.INSTANCE.zoomHeld = false;
		}
	}

	// ------------------------------------------------------------------ Rocket Launcher (RocketLauncher.Update)

	private void rocket(ClientPlayerEntity p, int variation, boolean inputOk) {
		RigInstance rig = rig(WeaponKind.ROCKET);
		boolean fire1 = inputOk && UKKeys.ATTACK.held;
		boolean fire1Pressed = inputOk && UKKeys.ATTACK.press;
		boolean fire2 = inputOk && UKKeys.USE.held;
		boolean fire2Pressed = inputOk && UKKeys.USE.press;
		if (variation == 1) {
			if (fire2 && !fire1Pressed && rocketCannonballCharge >= 1) {
				// RocketLauncher.chargeSound (the HologramDisplay's MachinePumpLoop: looping, volume 0.4), pitch cbCharge + 0.5,
				// started again whenever it is not playing
				UKSound.loop(CB_CHARGE, 0.4f, cbCharge + 0.5f);
				cbCharge = Math.min(1, cbCharge + DT);
				UKSound.setLoop(CB_CHARGE, 0.4f, cbCharge + 0.5f);
			} else if (cbCharge > 0) {
				UKSound.stop(CB_CHARGE);
				fire(p, FirePayload.ROCKET, 1, Math.round(cbCharge * 100));
				rocketCannonballCharge = 0;
				rocketcharge = 1;
				UKSound.play("uk.weapons.rocketfire5", 1f, UKSound.range(0.6f, 0.8f));
				if (rig != null) rig.play("Fire", 1, 0);
				V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.75);
				cbCharge = 0;
			}
		} else if (variation == 0) {
			if (fire2Pressed && rocketFreezeTime > 0) setFrozen(!rocketFrozen);
			// RocketLauncher.Update: each quarter of the freeze time refilled winds the clock (timerWindupSound, ClockWindup,
			// pitch 0.6 + quarter x 0.1)
			if (lastKnownFreezeTime != rocketFreezeTime) {
				for (int q = 4; q > 0; q--) {
					if (rocketFreezeTime / 5f >= q / 4f && lastKnownFreezeTime / 5f < q / 4f) {
						UKSound.play("uk.ui.clockwindup", 0.5f, 0.6f + q * 0.1f);
						break;
					}
				}
				lastKnownFreezeTime = rocketFreezeTime;
			}
		} else {
			boolean want = fire2 && rocketNapalmFuel > 0 && (firingNapalm || rocketNapalmFuel >= 0.25f) && rocketcharge < 0.5f;
			if (want) {
				if (!firingNapalm) { UKSound.play("uk.weapons.rocketspraystart", 1f, UKSound.range(0.9f, 1.1f)); UKSound.loop("uk.weapons.rocketsprayloop", 0.8f, 1f); }
				firingNapalm = true;
				// RocketLauncher.FixedUpdate: a droplet whenever napalmProjectileCooldown is back at 0, then 0.02 again -
				// on 0.008 s steps that is every third step, 0.024 s. ShootNapalm sets the Spray trigger each time: the
				// state (speed 0.66, AnyState, can go to itself) restarts, so only its first frames show - the shake.
				float t = napalmCooldown;
				napalmCooldown -= DT;
				while (napalmCooldown <= 0) {
					napalmCooldown += 0.024f;
					rocketNapalmFuel -= 0.015f;
					fire(p, FirePayload.ROCKET, 2, 0);
					if (rig != null) rig.playAfter("Spray", Math.max(0, t), 0.66f);
					// (the clip slides Barrel_Bone 0.42 u in within its first 1/60 s: restarted this often the barrel pumped in and
					// out of the launcher; the user sees the whole launcher kick in the game, so the barrel is held at its Idle place
					// and only the body moves - spray.pinBarrel, by eye)
					if (rig != null && Tune.on("spray.pinBarrel") && !rig.pinPosition("RocketLauncher/Armature/Body_Bone/Barrel_Bone", "Idle", 0))
						rig.pinPosition("Armature/Body_Bone/Barrel_Bone", "Idle", 0);
					t += 0.024f;
				}
			} else if (firingNapalm) stopNapalm();
			else if (fire2Pressed && rocketNapalmFuel < 0.25f) UKSound.play("uk.ui.error", 0.5f, 1f);
		}
		if (rocketcharge <= 0 && fire1 && cbCharge == 0) {
			rocketcharge = 1; // rateOfFire
			// (the user's call, not the game's: the game spawns every rocket at the camera. Ours leave the launcher's barrel
			// toward what the crosshair is on - except a Freezeframe rocket fired while frozen, which stays at the eye so it
			// can be jumped on from where V1 stands)
			if (!(variation == 0 && rocketFrozen) && Tune.on("rocket.fromBarrel")) rocketFromBarrel(p, rig);
			fire(p, FirePayload.ROCKET, 0, 0);
			UKSound.play("uk.weapons.rocketfire5", 1f, UKSound.range(0.9f, 1.1f));
			if (rig != null && !firingNapalm) rig.play("Fire", 1, 0);
			V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.75);
		}
	}

	/**
	 * RocketLauncher.FreezeRockets / UnfreezeRockets: timerFreezeSound (ClockStart) and timerTickSound (ClockTickTock,
	 * looping at 0.5) while frozen - WeaponCharges turns its pitch from 1 down to 0.75 as the freeze time runs out -, or
	 * timerUnfreezeSound (ClockStop) and the ticking stopped.
	 */
	private boolean rocketCanAutoUnfreeze;
	private float rocketIdleFrozen;
	private int lastRocketsOut;

	/** WeaponCharges.rocketCount: the rockets out (Grenade.Start / OnDestroy), here the ones within 96 blocks. */
	private static int rocketsOut() {
		var c = net.minecraft.client.MinecraftClient.getInstance();
		if (c.world == null || c.player == null) return 0;
		return c.world.getEntitiesByClass(com.ultracraft.entity.UKProjectileEntity.class, c.player.getBoundingBox().expand(96),
			e -> e.kind() == com.ultracraft.entity.ProjectileKind.ROCKET && e.isAlive()).size();
	}

	private void setFrozen(boolean frozen) {
		rocketFrozen = frozen;
		// FreezeRockets: timeSinceIdleFrozen = 0; UnfreezeRockets: canAutoUnfreeze = false
		if (frozen) { rocketIdleFrozen = 0; lastRocketsOut = rocketsOut(); }
		else rocketCanAutoUnfreeze = false;
		UKSound.play(frozen ? "uk.ui.clockstart" : "uk.ui.clockstop", 1f, 1f);
		if (frozen) UKSound.loop(CLOCK_TICK, 0.5f, 1f);
		else UKSound.stop(CLOCK_TICK);
		if (net.minecraft.client.MinecraftClient.getInstance().player != null)
			fire(net.minecraft.client.MinecraftClient.getInstance().player, FirePayload.ROCKET, 3, frozen ? 1 : 0);
	}

	/** The next shot's origin at the Shootpoint as the launcher was drawn, aimed at the crosshair's point. */
	private void rocketFromBarrel(ClientPlayerEntity p, RigInstance rig) {
		fromBarrel(p, GunDisplays.boneInWorld(rig, "RocketLauncher/Armature/Body_Bone/Shootpoint"));
	}

	/** The next shot's origin at a barrel point in the world, aimed at the crosshair's point (blocks and mobs within 200 blocks). */
	private void fromBarrel(ClientPlayerEntity p, net.minecraft.util.math.Vec3d sp) {
		if (sp == null) return;
		net.minecraft.util.math.Vec3d eye = p.getEyePos(), look = p.getRotationVec(1f), end = eye.add(look.multiply(200));
		// a barrel inside a wall: from the eye, as the game does
		var world = p.getWorld();
		if (world.raycast(new net.minecraft.world.RaycastContext(eye, sp, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
			net.minecraft.world.RaycastContext.FluidHandling.NONE, p)).getType() != net.minecraft.util.hit.HitResult.Type.MISS) return;
		var block = world.raycast(new net.minecraft.world.RaycastContext(eye, end, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
			net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
		net.minecraft.util.math.Vec3d aim = block.getType() == net.minecraft.util.hit.HitResult.Type.MISS ? end : block.getPos();
		var mob = net.minecraft.entity.projectile.ProjectileUtil.raycast(p, eye, aim, p.getBoundingBox().stretch(aim.subtract(eye)).expand(1),
			e -> !e.isSpectator() && e.canHit() && e != p, eye.squaredDistanceTo(aim));
		if (mob != null) aim = mob.getPos();
		net.minecraft.util.math.Vec3d d = aim.subtract(sp);
		if (d.lengthSquared() < 1e-4 || d.normalize().dotProduct(look) < 0.5) return; // a point right at the barrel: straight ahead from the eye
		fireOrigin = sp;
		fireDir = d.normalize();
	}

	private void stopNapalm() {
		firingNapalm = false;
		RigInstance rig = rig(WeaponKind.ROCKET);
		// Letting go: the last Spray restart plays out (the state at 0.66 leaves for Idle only at its exit time, 0.977), and
		// with the barrel free its slide shows - in and back out, the kick the user sees in the game. Our clip loops, so
		// it is sent to Idle at that exit time.
		if (rig != null) {
			rig.clearQueued();
			if (!rig.pinPosition("RocketLauncher/Armature/Body_Bone/Barrel_Bone", null, 0)) rig.pinPosition("Armature/Body_Bone/Barrel_Bone", null, 0);
			rig.play("Spray", (float) Tune.get("spray.releaseSpeed"), 0f);
			rig.playAfter("Idle", rig.secondsTo(0.977f), 1f);
		}
		UKSound.stop("uk.weapons.rocketsprayloop");
		UKSound.play("uk.weapons.rocketspraystop", 1f, UKSound.range(0.9f, 1.1f));
	}


	// ------------------------------------------------------------------ Jackhammer (ShotgunHammer.Update / ImpactRoutine)
	public float swingCharge, hammerCooldown, currentSpeed, storedSpeed, shoAltNadeCharge = 1, shoAltYellowsTimer;
	public final float[] hammerOverheat = new float[3];
	public int tier, shoAltYellows;
	public boolean chargingSwing, hammerOverheated;
	static {
		Tune.def("hammer.speedMax", 60, "Impact Hammer dial: V1 speed (u/s) that fills it (from the game: 60)");
		Tune.def("hammer.speedEase", 2, "Impact Hammer dial: how fast it follows the speed, per second (from the game: 2)");
		Tune.def("hammer.dashDivide", 3, "Impact Hammer dial: V1's speed is divided by this while dashing, not sliding (from the game: 3)");
		Tune.def("hammer.tier1", 0.33, "Impact Hammer dial: fill above which the hit is yellow (from the game: 0.33)");
		Tune.def("hammer.tier2", 0.66, "Impact Hammer dial: fill above which the hit is red (from the game: 0.66)");
		Tune.def("hammer.tierHold", 0.5, "Impact Hammer dial: seconds below a tier before it drops, the dial blinking (from the game: 0.5)");
	}

	/** ShotgunHammer.tierDownTimer (TimeSince): reset while the speed holds the tier; the dial blinks while it runs. */
	public float tierDownTimer;
	/** pulledOut (TimeSince the gun came out, or the last pump / throw); the gun is ready from 0.5 s. */
	private float hammerPulledOut;
	private boolean hammerReady, fireHeldOnPullOut, hammerAboutToSecondary, nadeCharging;
	private float speedStorageTimer;
	/** The pump's overcharge warning (timeToBeep): 1 at each beep, down 5/s; the meter red then black. */
	public float timeToBeep;
	/** Sawed-On hammer: the rev (chargeForce, 0..60) while right click is held. */
	public float hammerSawForce;
	public boolean hammerSawing;
	private float sawSparkCooldown;

	private void hammer(ClientPlayerEntity p, int variation, boolean inputOk) {
		RigInstance rig = rig(WeaponKind.SHOTGUN);
		boolean fire1 = inputOk && UKKeys.ATTACK.held;
		boolean fire2 = inputOk && UKKeys.USE.held;
		V1 v = V1.INSTANCE;
		for (int i = 0; i < 3; i++) if (hammerOverheat[i] > 0) hammerOverheat[i] = Math.max(0, hammerOverheat[i] - DT);
		// WeaponCharges: the grenade back at 0.5 a second (HammerNadeReady when it is), the yellow-hit count's 3 s
		if (shoAltNadeCharge < 1) shoAltNadeCharge = Math.min(1, shoAltNadeCharge + 0.5f * DT);
		if (shoAltYellowsTimer > 0) { shoAltYellowsTimer = Math.max(0, shoAltYellowsTimer - DT); if (shoAltYellowsTimer <= 0) shoAltYellows = 0; }
		if (shoAltNadeCharge < 1) nadeCharging = true;
		else if (nadeCharging) { nadeCharging = false; UKSound.play("uk.weapons.hammernadeready", 0.75f, 1f); }
		boolean overheated = hammerOverheat[variation] > 0;
		if (overheated && !hammerOverheated) {
			if (rig != null) rig.play("IdleCooldown", 1, 0.1f);
			UKSound.loop("uk.environment.steamloop3", 0.75f, 0.75f);
		}
		if (!overheated && hammerOverheated) {
			if (rig != null) rig.play("Idle", 1, 0.1f);
			UKSound.stop("uk.environment.steamloop3");
		}
		hammerOverheated = overheated;
		// the dial: V1's speed over 60 u/s (PlayerTracker.GetPlayerVelocity: a third of it while dashing, not sliding),
		// eased 2 a second (held at 0.5 while the Whiplash pulls V1)
		currentSpeed = overheated ? 0 : StyleStateMove(currentSpeed, hammerGaugeTarget(), DT * (float) Tune.get("hammer.speedEase"));
		boolean pulled = WhiplashClient.INSTANCE.state == WhiplashClient.State.PULLING && !WhiplashClient.INSTANCE.lightTarget;
		if (pulled) currentSpeed = Math.min(currentSpeed, 0.5f);
		// UpdateMeter: a lower tier only after 0.5 s below it (the dial blinks meanwhile)
		int want = currentSpeed > Tune.get("hammer.tier2") ? 2 : currentSpeed > Tune.get("hammer.tier1") ? 1 : 0;
		tierDownTimer += DT;
		if (pulled && tier == 2) want = 1;
		else if (want < tier) { if (tierDownTimer <= Tune.get("hammer.tierHold")) want = tier; }
		else tierDownTimer = 0;
		tier = want;
		// FixedUpdate: the speed DeliverDamage gives back after a hit (the best of the last half second)
		float mag = (float) v.vel.length();
		speedStorageTimer += DT;
		if (mag >= storedSpeed - 5 || speedStorageTimer > 0.5f) { storedSpeed = mag; speedStorageTimer = 0; }
		hammerPulledOut += DT;
		if (hammerPulledOut >= 0.5f) hammerReady = true;
		// LateUpdate: the pull (HammerPull) louder as the hammer goes down, the motor (HammerMotorLoop) with the speed -
		// its pitch goes down to the octave-down copy's range
		// (only while audible: Minecraft drops a sound that starts at volume 0, and the loop then never played)
		if (swingCharge > 0.01f) UKSound.loop("uk.weapons.hammerpull", swingCharge * 0.6f, Math.max(0.5f, swingCharge + currentSpeed * 0.5f));
		else UKSound.stop("uk.weapons.hammerpull");
		if (currentSpeed > 0.01f) UKSound.loop("uk.weapons.hammermotorloop_d2", currentSpeed / 2f, Math.max(0.5f, currentSpeed * 2f));
		else UKSound.stop("uk.weapons.hammermotorloop_d2");
		// Fire1: the swing charges (the hammer slides down the barrel over 0.5 s); let go once it is all the way down
		// and it hits - a tap charges on by itself and hits as it gets there
		if (!chargingSwing && hammerCooldown <= 0 && !overheated && fire1 && (!fireHeldOnPullOut || hammerPulledOut >= 0.25f)) {
			fireHeldOnPullOut = false;
			chargingSwing = true;
		} else if (!fire1 && swingCharge >= 1 && hammerReady) {
			chargingSwing = false;
			swingCharge = 0;
			hammerCooldown = 0.5f;
			int charge = variation == 1 ? primaryCharge : 0;
			fire(p, FirePayload.SHOTGUN, 4, tier | (charge << 2));
			SWINGS.add(this); // the server's answer (HitPayload.SWING) comes back to this copy
			if (variation == 1) primaryCharge = 0;
		}
		// Sawed-On: right click revs the chainsaw (when it is on the gun), letting go throws it
		if (fire2 && variation == 2 && hammerReady && shoSawCharge >= 1) {
			if (!hammerSawing) { UKSound.play("uk.weapons.chainsawon", 0.7f, 1f); UKSound.loop("uk.weapons.chainsawloop", 0.5f, 0.5f); }
			hammerSawing = true;
			if (hammerSawForce < 60) hammerSawForce = Math.min(60, hammerSawForce + 60 * DT);
			// chargeSoundBubble (ChainsawLoop 0.5): pitch (chargeForce / 2 + 30) / 60
			UKSound.setLoop("uk.weapons.chainsawloop", 0.5f, (hammerSawForce / 2 + 30) / 60);
			sawZoneTick += DT;
			if (sawZoneTick >= Tune.get("weapons.sawZoneRate")) { sawZoneTick = 0; fire(p, FirePayload.SHOTGUN, 2, 0); }
			if (rig != null && !"IdleSawing".equals(rig.currentClip())) rig.play("IdleSawing", 1, 0.1f);
			// the saw on a wall within 3 u: sparks, ChainsawSparkLoop (0.75, pitch 1.5) and a 0.1 camera shake
			net.minecraft.util.hit.BlockHitResult wall = p.getWorld().raycast(new net.minecraft.world.RaycastContext(p.getEyePos(), p.getEyePos().add(p.getRotationVec(1f).multiply(3 * com.ultracraft.weapon.Damage.SCALE_BLOCKS)),
				net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
			if (wall.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
				UKSound.loop("uk.weapons.chainsawsparkloop", 0.75f, 1.5f);
				v.shake = Math.max(v.shake, 0.1);
				if ((sawSparkCooldown -= DT) <= 0) { sawSparkCooldown = 0.1f; SparkFx.burst(wall.getPos()); }
			} else UKSound.stop("uk.weapons.chainsawsparkloop");
		} else if (hammerSawing && variation == 2) {
			hammerSawing = false;
			shoSawCharge = 0;
			UKSound.stop("uk.weapons.chainsawloop");
			UKSound.stop("uk.weapons.chainsawsparkloop");
			// ShootSaw: the ChainsawProjectile at (chargeForce + 10) x 1.5 u/s, SawingShot, a camera shake of 1
			fire(p, FirePayload.SHOTGUN, 3, Math.round(hammerSawForce));
			UKSound.play("uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_05", 0.45f, UKSound.range(1.95f, 2.05f));
			if (rig != null) rig.play("SawingShot", 1, 0);
			v.shake = Math.max(v.shake, 1);
			hammerSawForce = 0;
		}
		if (chargingSwing) swingCharge = Math.min(1, swingCharge + 2 * DT);
		// Fire2 on the other two (held: again each time the gun is ready): the Core Eject grenade when charged, or a pump
		if (fire2 && variation != 2 && (variation == 1 || shoAltNadeCharge >= 1) && !hammerAboutToSecondary && hammerReady) {
			hammerReady = false;
			if (variation == 0) throwNade(p, rig);
			else pump(rig);
		}
		if (hammerCooldown > 0) hammerCooldown = Math.max(0, hammerCooldown - DT);
		// the overcharged pump beeps (warningBeep: CSFX-2_Mechanics_45, 0.25) every 0.2 s
		if (variation == 1 && primaryCharge == 3) {
			if (timeToBeep != 0) timeToBeep = Math.max(0, timeToBeep - 5 * DT);
			if (timeToBeep == 0) { timeToBeep = 1; UKSound.play("uk.ui.csfx-2_mechanics_45", 0.25f, 1f); }
		} else timeToBeep = 0;
	}

	/** ShotgunHammer.ThrowNade: from the camera + 2 u forward - 0.5 u up, thrown forward x 3 + up x 7.5 + V1's velocity. */
	private void throwNade(ClientPlayerEntity p, RigInstance rig) {
		shoAltNadeCharge = 0;
		hammerPulledOut = 0.3f;
		hammerAboutToSecondary = false;
		double s = com.ultracraft.weapon.Damage.SCALE_BLOCKS;
		Vec3d look = p.getRotationVec(1f);
		Vec3d up = Vec3d.fromPolar(p.getPitch() - 90, p.getYaw());
		Vec3d vel = look.multiply(3).add(0, 7.5, 0).add(V1.INSTANCE.vel);
		fireOrigin = p.getEyePos().add(look.multiply(2 * s)).subtract(up.multiply(0.5 * s));
		fireDir = vel;
		fire(p, FirePayload.SHOTGUN, 5, (int) Math.round(vel.length() * 100));
		if (rig != null) rig.play("NadeSpawn", 1, 0);
		// nadeSpawnSound (GrenadeLauncherSoundLight): 0.45, RandomPitch 2 +- 0.05
		UKSound.play("uk.weapons.steampunk_weapons_-_shotgun_2_-_shot_-_05", 0.45f, UKSound.range(1.95f, 2.05f));
	}

	/** ShotgunHammer.Pump: one more charge (up to 3), pumpCharge at pitch 1 + n / 5, ShotgunPump1, the Pump clip. */
	private void pump(RigInstance rig) {
		if (primaryCharge < 3) primaryCharge++;
		hammerPulledOut = 0;
		hammerAboutToSecondary = false;
		UKSound.play("uk.weapons.pumpcharge", 0.45f, 1f + primaryCharge / 5f);
		UKSound.play("uk.weapons.shotgunpump1", 1f, 1f);
		if (rig != null) rig.play("Pump", 1, 0);
	}

	/** The hammer comes out (ShotgunHammer.OnEnable): not ready for 0.5 s; a held fire waits for a new press. */
	private void hammerOut() {
		fireHeldOnPullOut = UKKeys.ATTACK.held;
		hammerPulledOut = 0;
		hammerReady = false;
		chargingSwing = false;
		swingCharge = 0;
		hammerCooldown = 0;
		tier = 0;
		hammerSawing = false;
		hammerSawForce = 0;
		timeToBeep = 0;
	}

	private static float StyleStateMove(float a, float b, float d) {
		return com.ultracraft.client.style.StyleState.moveTowards(a, b, d);
	}

	/** What the dial heads for: V1's speed over 60 u/s, a third of it while dashing (not sliding); the tick and the drawn needle share it. */
	public static float hammerGaugeTarget() {
		V1 v = V1.INSTANCE;
		double playerSpeed = v.vel.length() / (v.boost && !v.sliding ? Tune.get("hammer.dashDivide") : 1);
		return (float) Math.min(playerSpeed / Tune.get("hammer.speedMax"), 1);
	}

	/**
	 * What the swing did (HitPayload SWING): ImpactEffects - the clip by tier (Fire when it met no living enemy), the
	 * HammerHit light / medium / heavy burst and its HammerExplosion sound where it landed; DeliverDamage / the wall
	 * launch; HitNade's TrueStop.
	 */
	/** This copy's own zapper. */
	private ZapperClient zapper() {
		return off ? ZapperClient.OFF : ZapperClient.INSTANCE;
	}

	/** The copies whose hammer swings await the server's answer, in the order sent (the answers come back in that order). */
	static final java.util.ArrayDeque<ArsenalClient> SWINGS = new java.util.ArrayDeque<>();

	/** HitPayload.SWING: handed to the copy that swung (the off-hand hammer as the off-hand weapon). */
	public static void swingAnswered(ClientPlayerEntity p, int result, int hitTier, Vec3d at) {
		ArsenalClient a = SWINGS.poll();
		if (a == null || a == INSTANCE) INSTANCE.onHammerSwing(p, result, hitTier, at, com.ultracraft.item.UKWeaponItem.variation(p.getMainHandStack()));
		else OffhandGun.run(p, () -> a.onHammerSwing(p, result, hitTier, at, com.ultracraft.item.UKWeaponItem.variation(p.getMainHandStack())));
	}

	public void onHammerSwing(ClientPlayerEntity p, int result, int hitTier, Vec3d at, int variation) {
		V1 v = V1.INSTANCE;
		Vec3d look = p.getRotationVec(1f);
		boolean strong = (result & com.ultracraft.net.HitPayload.SWING_STRONG) != 0;
		int fxTier = strong ? hitTier : 0;
		RigInstance rig = rig(WeaponKind.SHOTGUN);
		if (rig != null) rig.play(fxTier == 2 ? "FireStrongest" : fxTier == 1 ? "FireStrong" : "Fire", 1, 0);
		HammerFx.impact(at, fxTier);
		UKSound.at(at, "uk.weapons.hammerexplosion" + (fxTier + 1), 1f, 1f);
		// hitSound (HammerHit): HammerImpact, RandomPitch 1 +- 0.2
		if ((result & (com.ultracraft.net.HitPayload.SWING_NADE | com.ultracraft.net.HitPayload.SWING_SAW)) != 0) UKSound.play("uk.weapons.hammerimpact", 1f, UKSound.range(0.8f, 1.2f));
		// HitNade (a grenade or a rocket): TimeController.TrueStop(0.25) - the whole game held, as a parry, without the flash
		if ((result & com.ultracraft.net.HitPayload.SWING_NADE) != 0) { hitstopFor(0.25f); V1.INSTANCE.freeze(0.25); }
		if (strong) {
			// DeliverDamage: TrueStop 0.05 / 0.25 / 0.5, HammerHit, launched back (300 tier + 100), at least the stored speed
			UKSound.play("uk.weapons.hammerimpact", 1f, UKSound.range(0.8f, 1.2f));
			hitstopFor(hitTier == 2 ? 0.5f : hitTier == 1 ? 0.25f : 0.05f);
			com.ultracraft.client.v1.V1Movement.launch(v, look.multiply(-(300 * hitTier + 100)), 8);
			if (v.vel.length() < storedSpeed) v.vel = look.multiply(-storedSpeed);
			if (hitTier == 2) hammerOverheat[variation] = 7f;
			else if (hitTier == 1) {
				if (shoAltYellows >= 2) hammerOverheat[variation] = 7f;
				shoAltYellowsTimer = 3f;
				shoAltYellows++;
			}
		}
		if ((result & com.ultracraft.net.HitPayload.SWING_WALL) != 0) {
			// NewMovement.Launch(-direction x (100 tier + 300) / ((hammerJumps + 3) / 3)) + enviro gibs
			com.ultracraft.client.v1.V1Movement.launch(v, look.multiply(-(100 * hitTier + 300) / ((v.hammerJumps + 3) / 3.0)), 8);
			v.hammerJumps++;
			v.explosionLaunchResistance = 0.5;
		}
	}

	private void hitstopFor(float s) {
		WeaponClient.INSTANCE.hitstop = Math.max(WeaponClient.INSTANCE.hitstop, s);
	}

	// ------------------------------------------------------------------ Sawblade Launcher (Nailgun.cs altVersion)
	public float naiSaws = 10;
	/** Nailgun.shotSuccesfully: a saw left since fire was last pressed (the first dry pull is harpoonStop, then clicks). */
	private boolean sawShotOk;
	private float sawStepAcc;

	/**
	 * The Sawblade Launchers (Nailgun with altVersion; fireRate 24 on all three, a cooldown in 1/100 s falling 100/s on
	 * the 0.008 s steps). Mod variation 0 = the Attractor's (NailAltFodder, 10 saws), 1 = the Overheat's (NailAlt, its
	 * hits 3 cold to 1 hot, one heat sink, SuperSaw), 2 = the Jump Start's (NailAlt, 1 hit; the zapper).
	 */
	private void sawblade(ClientPlayerEntity p, int variation, boolean inputOk) {
		RigInstance rig = rig(WeaponKind.NAILGUN);
		boolean fire1 = inputOk && UKKeys.ATTACK.held;
		boolean fire2Pressed = inputOk && UKKeys.USE.press;
		float fireRate = 24f;
		// WeaponCharges.Charge: saws come back at 0.5/s, never while fire is held (naiAmmoDontCharge)
		if (!fire1) { naiSaws = Math.min(10, naiSaws + 0.5f * DT); sawShotOk = false; }
		if (heatSinks > sinkMax(variation, true)) heatSinks = sinkMax(variation, true); // the alt has one heat sink
		if (burnOut || heatSinks < 1) {
			heatUp = Math.max(0, heatUp - DT);
			if (burnOut && heatUp <= 0) burnOut = false;
		} else if (canShoot && fire1 && (heatUp < 1 || variation == 2)) {
			if (variation == 2) heatUp = zapper().state == ZapperClient.State.ATTACHED ? 1f : 0.33f;
			else if (variation == 0) heatUp = 1f;
			// variation 1 (Overheat alt) heats per shot (+0.125), see sawShot
		} else if (heatUp > 0 && (!canShoot || !fire1)) heatUp = Math.max(0, heatUp - (fireCooldown <= 0 ? 0.2f : 0.03f) * DT);
		// Overheat: the blades glow with the heat (SetHeat: BarrelHeat 1 on Blade (1), Basic Color - Orange, alpha = heat)
		if (rig != null) {
			// (added onto the blade's own texture: yellow-hot metal with its detail, not a flat orange coat)
			if (variation == 1) rig.setEmissiveOverlay("Blade", "basic_color_-_orange", 1f, 1f, 1f, Math.min(1f, heatUp) * (float) Tune.get("saw.heatGlow"), true);
			else rig.setEmissiveOverlay("Blade", null, 1f, 1f, 1f);
		}
		float currentFireRate;
		// Nailgun.Update (altVersion): the Jump Start's twice as slow with no zapper out
		if (variation == 2) currentFireRate = zapper().out() ? fireRate + 3.5f - heatUp * 3.5f : fireRate * 2f;
		else if (variation == 0) currentFireRate = fireRate + 3.5f - heatUp * 3.5f;
		else if (burnOut) currentFireRate = 3f;
		else if (heatSinks >= 1) currentFireRate = heatUp < 0.5f ? fireRate : fireRate + (heatUp - 0.5f) * 65f;
		else currentFireRate = fireRate + 50f;
		// Nailgun.FixedUpdate on the game's steps (the cooldown is set again by a shot or a dry pull)
		sawStepAcc += DT;
		float stepAt = 0;
		while (sawStepAcc >= 0.008f - 1e-6f) {
			sawStepAcc -= 0.008f;
			float delay = stepAt;
			stepAt += 0.008f;
			if (fireCooldown > 0) {
				fireCooldown = Math.max(0, fireCooldown - 0.8f);
				if (fireCooldown < 0.01f) fireCooldown = 0;
			}
			if (!canShoot || !fire1 || fireCooldown > 0) continue;
			if (variation == 0 && Math.round(naiSaws) <= 0) {
				// out of saws: twice the cooldown, harpoonStop (0.5, pitch 1.25) for the first dry pull, then the click
				// (0.5, pitch 3 +-0.05)
				fireCooldown = currentFireRate * 2;
				if (sawShotOk) UKSound.playAfter("uk.weapons.harpoonstop", 0.5f, 1.25f, delay);
				else UKSound.playAfter("uk.weapons.revolver.click", 0.5f, UKSound.range(2.95f, 3.05f), delay);
				sawShotOk = false;
				continue;
			}
			fireCooldown = currentFireRate;
			sawShot(p, rig, variation, delay);
		}
		if (fire2Pressed) {
			if (variation == 1 && canShoot && !burnOut && heatSinks >= 1 && heatUp >= 0.1f) {
				// SuperSaw: NailAltHeated with multiHitAmount round(heat x 3), a heat sink, the heat back to 0, the SuperShoot
				// clip, muzzleFlash2 (PlasmaFire 0.45 pitch 1.5 +-0.1 and HarpoonShoot 0.25), camera shake 0.5
				int multi = Math.max(1, Math.round(heatUp * 3));
				if (sawOrigin() != null) fireOrigin = sawOrigin();
				fire(p, FirePayload.NAILGUN, 0, 1 | 2 | (multi << 2));
				heatSinks--;
				heatUp = 0;
				fireCooldown = currentFireRate;
				if (rig != null) rig.play("ShootSuper", 1, 0);
				UKSound.play("uk.weapons.plasmafire", 0.45f, UKSound.range(1.4f, 1.6f));
				UKSound.play("uk.weapons.harpoonshoot", 0.25f, 1f);
				V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.5);
			} else if (variation == 0) {
				if (naiMagnetCharge >= 1) {
					fire(p, FirePayload.NAILGUN, 1, 0); naiMagnetCharge--; lastMagnetShotNanos = System.nanoTime(); UKSound.play("uk.weapons.harpoonshoot", 0.35f, 1f); }
			} else if (variation == 2 && naiZapperRecharge >= 5) {
				zapper().shoot(p);
				if (rig != null && canShoot) rig.play("Shoot", 1, 0);
				UKSound.play("uk.weapons.harpoonshoot", 0.35f, 1f);
			}
		}
		if (variation == 2) zapHudSound();
	}

	static {
		Tune.def("saw.heatGlow", 1, "Overheat saw launcher: strength of the blades' heat glow (BarrelHeat alpha = heat)");
		Tune.def("saw.flashSize", 3, "saw launcher muzzle flash size, times the nailgun's (NailgunMuzzleFlash 3: scale 3 x 8)");
		Tune.def("saw.flashTime", 0.04, "saw launcher muzzle flash life, s (SpriteController 25/s)");
		Tune.def("saw.flashEnd", 0.33, "saw launcher muzzle flash size / opacity at the end of its life (by eye: ends at the old size, faint)");
		Tune.def("saw.flashAlpha", 0.5, "saw launcher muzzle flash opacity at the start (by eye: see-through yellow)");
	}

	/** Nailgun.Shoot for a saw launcher; `delay` = how far into this tick its step falls. */
	private void sawShot(ClientPlayerEntity p, RigInstance rig, int variation, float delay) {
		if (variation == 0) naiSaws--;
		sawShotOk = true;
		// the spread: the Overheat's widens with the heat (Lerp(0, 45, heat - 0.25)), 45 with no heat sink left
		float spread = variation == 1 ? (heatSinks < 1 ? 45f : 45f * Math.max(0, heatUp - 0.25f)) : 0f;
		// the saw's hits: the Overheat's Lerp(3, 1, heat) with a heat sink, 1 without; the Jump Start's 1; the
		// Attractor's NailAltFodder keeps its own 3.9
		float hits = variation == 1 ? (heatSinks >= 1 ? 3f + (1f - 3f) * Math.min(1f, heatUp) : 1f) : variation == 2 ? 1f : 0f;
		int tenths = Math.max(0, Math.min(63, Math.round(hits * 10)));
		// Nailgun.Shoot: the saw starts at the launcher's Shootpoint (where it is on screen), flying along the look
		if (sawOrigin() != null) fireOrigin = sawOrigin();
		fire(p, FirePayload.NAILGUN, 0, 2 | (1 << 2) | (Math.round(spread) << 4) | (tenths << 10) | (variation == 0 ? 1 << 16 : 0));
		if (variation == 1 && heatSinks >= 1) heatUp = Math.min(1, heatUp + 0.125f);
		if (rig != null) {
			rig.setOverlay("Idle", 0f);
			rig.playAfter("Shoot", delay);
		}
		// NailgunMuzzleFlash 3: PlasmaFire 0.45, RandomPitch 1.5 +-0.1
		UKSound.playAfter("uk.weapons.plasmafire", 0.45f, UKSound.range(1.4f, 1.6f), delay);
		V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.1);
	}

	private void hookRigs() {
		// the base rigs by name: rig(kind) follows the held weapon's version, so a session that began on an alternate
		// weapon hooked the hammer here (and the hammer's own hook below replaced it): the shotgun never heard ReadyGun
		RigInstance s = vm(WeaponKind.SHOTGUN.rig(false));
		if (s != null) {
			hooked = true;
			s.onEvent = e -> {
				switch (e.name()) {
					case "ReadyGun" -> {
						shotgunReady = true;
						// the new cores are in: the hot ones are gone, and the full meter shows before a held charge starts
						if (resettingCores) { heatGlow = 0; releasingHeat = false; coreReadyFor = 0; }
						resettingCores = false;
					}
					case "ReleaseHeat" -> {
						// the barrels are out: steam from both heat sinks, the sink AudioSource (steam lever 002, 0.25), glow fades
						releasingHeat = true;
						UKSound.play("uk.weapons.mechanism_-__designed_mega_steam_lever-002", 0.25f, 1f);
						GunDisplays.steam();
					}
					case "Pump1Sound" -> { UKSound.play("uk.weapons.shotgunpump1", 1f, UKSound.range(0.95f, 1.05f)); UKSound.play("uk.weapons.pumpcharge", 0.7f, 1f + primaryCharge / 5f); }
					case "Pump2Sound" -> UKSound.play("uk.weapons.shotgunpump2", 1f, UKSound.range(0.95f, 1.05f));
					case "ClickSound" -> UKSound.play("uk.weapons.mechanism_-__designed_mega_steam_lever-004b", 0.5f, UKSound.range(0.95f, 1.05f));
					default -> {}
				}
			};
		}
		for (String nm : new String[] {"nailgun", "sawblade"}) {
			RigInstance n = vm(nm);
			if (n != null) {
				hooked = true;
				// (Equip's SnapSound: the saw launcher's two front blades unfold, Nailgun.snapSound RevolverClick 0.5 with its
				// RandomPitch 1.75 +- 0.1)
				n.onEvent = e -> {
					if (e.name().equals("CanShoot")) canShoot = true;
					else if (e.name().equals("SnapSound")) UKSound.play("uk.weapons.revolver.revolverclick", 0.5f, UKSound.range(1.65f, 1.85f));
				};
			}
		}
		RigInstance hm = vm("hammer");
		if (hm != null) {
			hooked = true;
			hm.onEvent = e -> { if (e.name().equals("Pump2Sound")) UKSound.play("uk.weapons.shotgunpump2", 1f, UKSound.range(0.95f, 1.05f)); };
		}
		RigInstance r = vm(WeaponKind.ROCKET.rig(false));
		if (r != null) {
			hooked = true;
			r.onEvent = e -> {
				if (e.name().equals("Clunk")) {
					V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.25);
					UKSound.play("uk.impacts.impacts_processed_179", 0.5f, UKSound.range(e.floatArg() - 0.1f, e.floatArg() + 0.1f));
				}
			};
		}
		// the off-hand copy's events run as the off-hand weapon (hands swapped, its packets tagged)
		if (off) for (String nm : new String[] {WeaponKind.SHOTGUN.rig(false), "nailgun", "sawblade", "hammer", WeaponKind.ROCKET.rig(false), "railcannon"}) OffhandGun.wrapEvents(vm(nm));
	}
}
