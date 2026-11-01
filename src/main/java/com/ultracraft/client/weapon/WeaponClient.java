package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKKeys;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.client.v1.V1;
import com.ultracraft.item.RevolverItem;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.net.FirePayload;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

/**
 * Client side of V1's weapons: a port of Revolver.Update / Punch.Update / FistControl.Update with the
 * same charges and timings, driving the first-person rigs and sending Fire packets. Runs at 20 Hz on the
 * client tick (ULTRAKILL runs per frame; the charge rates are per second so nothing changes).
 */
public final class WeaponClient {
	public static final WeaponClient INSTANCE = new WeaponClient(false);
	/** The off-hand gun's copy (OffhandGun): its revolver and arsenal state, run with the hands swapped. */
	public static final WeaponClient OFF = new WeaponClient(true);

	/** The copy whose code is running: the off-hand gun's while OffhandGun runs it, else the main one. */
	public static WeaponClient cur() {
		return OffhandGun.running() ? OFF : INSTANCE;
	}
	private static final float DT = 0.05f;

	// Revolver (per variation where ULTRAKILL keeps them in WeaponCharges)
	public float shootCharge = 100;      // 200/s, shot allowed at 100 (0.5 s fire rate)
	public boolean shootReady = true;
	/** Seconds left in which an early left click still fires (weapons.fireBuffer). */
	private float fireBuffer;
	public boolean gunReady = true;      // set by the ReadyGun animation event
	public float pierceCharge = 100;     // Piercer battery, regen 40/s
	public boolean pierceReady = true;
	public float pierceShotCharge;       // charged shot 175/s (Sharpshooter spin 75/s)
	public boolean chargingPierce, twirlRecovery;
	public float rev1charge = 400;       // Marksman coins, 25/s
	public float rev2charge = 300;       // Sharpshooter, 15/s
	public float latestTwirlRotation;
	/** °/s of the twirl this tick and when it was set, so the frame renderer can run the spin smoothly between ticks. */
	public float twirlRate;
	public long twirlTickNanos;
	public float twirlLevel;
	public int lastVariation = -1;
	/** WeaponCharges.revaltpickupcharges per variation: seconds left in which the alternate revolver reloads on its pull-out. */
	public final float[] altPickupCharge = new float[3];
	private boolean lastAlt;
	public boolean revolverOut;

	// Fists (FistControl + Punch)
	public int currentFist;              // 0 Feedbacker (Standard), 1 Knuckleblaster (Heavy)
	public float fistCooldown;
	public float punchStamina = 2;       // WeaponCharges.punchStamina, regen 1.25/s
	public boolean punchReady = true;
	public boolean holdingPunch;
	public String punchHitText;
	public float hitstop;
	private WeaponKind lastHeldKind;
	/** the weapon, slot, variant in hand last tick (null before the first; "" while something else is held) */
	private String lastSwitchKey;
	private float clashCooldown;
	private boolean clashSounding;
	/** Revolver.coinPanelsCharged, one set per revolver (normal / alternate) and variation, as each is its own object. */
	private final boolean[][][] coinPanelsCharged = new boolean[2][3][4];

	private boolean hooked;
	private int hookedGen = -1;
	/** the hotbar slot held now and the one before it (Last Weapon) */
	private int curSlot = -1, lastSlot = -1;

	/** this is the off-hand copy: its own rigs (ViewModel's off-hand instances) and ArsenalClient.OFF */
	public final boolean off;
	final ArsenalClient arsenal;

	private WeaponClient(boolean off) {
		this.off = off;
		this.arsenal = off ? ArsenalClient.OFF : ArsenalClient.INSTANCE;
		Tune.def("clash.spinTime", 0.333, "clash mode: seconds a spin lasts (PlatformerMovement: spinJuice 1 → 0 at 3/s)");
		Tune.def("parry.freeze", 0.3, "TimeController.TrueStop on a parry: seconds the game is frozen (game: 0.25)");
		Tune.def("parry.freezeOn", 1, "1 = a parry freezes the frame (TimeController.TrueStop)");
		Tune.def("parry.pelletFrames", 7, "shotgun parry: a punch pressed up to this many frames (60 fps) after the shot, or on its frame, boosts its pellets");
		Tune.def("parry.flashAlpha", 0.3, "parry: white overlay opacity during the freeze (Canvas/ParryFlash: 0.49; tuned to 0.3)");
		Tune.def("damage.hpPerPoint", 40, "Minecraft HP per 1 ULTRAKILL damage (Filth 0.5 hp dies to one revolver shot = 20 HP zombie)");
		Tune.def("damage.headFraction", 0.25, "top fraction of a mob's box that counts as its head");
		Tune.def("damage.range", 128, "hitscan range in blocks (ULTRAKILL: 1000 u)");
		Tune.def("debug.weapons", 1, "1 = log weapon fire/hit events");
	}

	public void reset() {
		shootCharge = 100; shootReady = true; gunReady = true;
		pierceCharge = 100; pierceReady = true; pierceShotCharge = 0; chargingPierce = false;
		rev1charge = 400; rev2charge = 300; fistCooldown = 0; punchStamina = 2; punchReady = true;
		lastVariation = -1; revolverOut = false;
		arsenal.reset();
		if (off) return;
		// (the off-hand gun's copy with it)
		OFF.reset();
		ArsenalClient.SWINGS.clear();
		WhiplashClient.INSTANCE.reset();
		ViewModel.reset();
	}

	/** True while the main hand holds an ULTRAKILL weapon (vanilla attack/use are ours then). */
	public static boolean holdingUkWeapon(ClientPlayerEntity p) {
		return p != null && UKWeaponItem.kindOf(p.getMainHandStack()) != null;
	}

	public static WeaponKind heldKind(ClientPlayerEntity p) {
		return p == null ? null : UKWeaponItem.kindOf(p.getMainHandStack());
	}

	public RigInstance revolverRig(ItemStack held) {
		String n = RevolverItem.alternate(held) ? "revolver_alt" : "revolver";
		return off ? ViewModel.offInstance(n) : ViewModel.instance(n);
	}

	/** Whether this copy has a gun out (the off-hand copy puts its gun away when the off-hand empties). */
	public boolean gunOut() {
		return revolverOut || arsenal.isOut();
	}

	public RigInstance fistRig() {
		return ViewModel.instance(currentFist == 1 ? "knuckleblaster" : "feedbacker");
	}

	public void tick(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null || client.world == null) return;
		Damage.configure(Tune.get("damage.hpPerPoint"), Tune.get("damage.headFraction"), Tune.get("damage.range"));
		Damage.debug = Tune.on("debug.weapons");
		// (re)attach the animation-event listeners whenever the rigs were rebuilt (leaving and re-joining a world
		// recreated them without listeners: ReadyGun / ReadyToPunch never came and no weapon fired again)
		if (!hooked || hookedGen != ViewModel.generation) {
			hooked = false;
			hookedGen = ViewModel.generation;
			hookRigs();
		}
		// (at a Terminal the guns are put away and the arm points: GunControl.NoWeapon / FistControl.ShopMode)
		boolean inputOk = client.currentScreen == null && !p.isDead() && !com.ultracraft.client.shop.ShopMode.active();

		// --- Last Weapon (ULTRAKILL's LastWeapon, the user's R): back to the hotbar slot held before this one, so it
		// swaps between the last two (the slot change reaches the server as any slot change does) ---
		int slot = p.getInventory().selectedSlot;
		if (slot != curSlot) { lastSlot = curSlot; curSlot = slot; }
		if (inputOk && UKKeys.LAST_WEAPON.press && lastSlot >= 0 && lastSlot < 9 && lastSlot != slot) p.getInventory().selectedSlot = lastSlot;

		// --- WeaponCharges regen (always, like ULTRAKILL's charges object; the off-hand gun's coins too) ---
		regenCoins(p.getMainHandStack());
		OFF.regenCoins(p.getOffHandStack());
		if (punchStamina < 2) punchStamina = Math.min(2, punchStamina + 1.25f * DT);
		if (fistCooldown > -1) fistCooldown = Math.max(0, fistCooldown - 2 * DT); // MoveTowards(…, dt*2)
		if (hitstop > 0) hitstop -= DT;
		// CameraController: fov returns to defaultFov at 300°/s (default 105)
		V1.INSTANCE.fovKick = Math.max(0, V1.INSTANCE.fovKick - 300.0 / 105 * DT);

		// --- third person = clash mode (PlatformerMovement.Spin): no guns, Fire1 / Fire2 spin the model into enemies ---
		boolean thirdPerson = !client.options.getPerspective().isFirstPerson();
		if (clashCooldown > 0) clashCooldown -= DT;
		if (clashSounding && System.nanoTime() > V1.INSTANCE.clashSpinUntil) { UKSound.stop("uk.movement.whooshloud"); clashSounding = false; }
		if (thirdPerson && inputOk && UKKeys.ATTACK.press && clashCooldown <= 0) clash(p);

		// --- Revolver / arsenal (and the off-hand gun's copy, OffhandGun) ---
		tickGun(client, p, inputOk, thirdPerson);
		OffhandGun.tick(client, p, inputOk, thirdPerson);
		ItemStack held = p.getMainHandStack();
		WeaponKind heldKind = UKWeaponItem.kindOf(held);

		// --- Fists ---
		if (inputOk && UKKeys.CHANGE_FIST.press) {
			currentFist = 1 - currentFist;
			if (fistCooldown > 0.1f) fistCooldown = 0.1f;
			punchReady = true;
			RigInstance f = fistRig();
			if (f != null) f.play("Idle", 1, 0); // the game only swaps the HUD icon
		}
		if (inputOk && UKKeys.PUNCH.press && punchReady && fistCooldown <= 0 && punchStamina >= 1) punchStart(p);
		OffhandHold.tick(p, fistRig());
		holdingPunch = UKKeys.PUNCH.held;
		WhiplashClient.INSTANCE.tick(client, inputOk);

		// the next variation key, or the selected slot's own number key again
		boolean samePress = UKKeys.sameSlotPress;
		if (inputOk && heldKind != null && (UKKeys.NEXT_VAR.press || samePress)) {
			// (to the next owned variation: the rest are bought at the Terminal)
			int v = com.ultracraft.client.shop.ShopClient.nextOwned(heldKind, UKWeaponItem.variation(held), 1);
			int code = switch (heldKind) { case SHOTGUN -> FirePayload.SHOTGUN; case NAILGUN -> FirePayload.NAILGUN; case RAILCANNON -> FirePayload.RAILCANNON; case ROCKET -> FirePayload.ROCKET; default -> FirePayload.REVOLVER; };
			if (v != UKWeaponItem.variation(held)) Net.toServer(new FirePayload(code, FirePayload.MODE_VARIATION, Math.floorMod(v, 3), p.getEyePos(), p.getRotationVec(1f)));
		}
	}

	/** WeaponCharges.Charge for this copy's revolver: the coins (25/s), the Sharpshooter's spins (35/s on the alternate). */
	private void regenCoins(ItemStack held) {
		if (rev1charge < 400) rev1charge = Math.min(400, rev1charge + 25 * DT);
		if (rev2charge < 300) rev2charge = Math.min(300, rev2charge + (held.getItem() instanceof RevolverItem && RevolverItem.alternate(held) ? 35 : 15) * DT);
		for (int i = 0; i < 3; i++) if (altPickupCharge[i] > 0) altPickupCharge[i] = Math.max(0, altPickupCharge[i] - DT);
	}

	/** WeaponCharges.MaxCharges (NewMovement.Respawn): every charge full again, both hands' guns. */
	public void maxCharges() {
		rev1charge = 400; rev2charge = 300;
		pierceCharge = 100; pierceReady = true;
		arsenal.reset();
		if (!off) OFF.maxCharges();
	}

	/** The held gun's tick (Revolver.Update / the arsenal's): this copy's, on whatever the main hand holds now. */
	void tickGun(MinecraftClient client, ClientPlayerEntity p, boolean inputOk, boolean thirdPerson) {
		if (off && (!hooked || hookedGen != ViewModel.generation)) {
			hooked = false;
			hookedGen = ViewModel.generation;
			hookRigs();
		}
		ItemStack held = p.getMainHandStack();
		WeaponKind heldKind = UKWeaponItem.kindOf(held);
		boolean revolver = heldKind == WeaponKind.REVOLVER;
		// GunControl.SwitchWeapon / variation switch: its AudioSource (Impacts_SIMPLE_003, volume 0.35, pitch 3) each time
		// another weapon or variant comes into hand - only for ULTRAKILL weapons (not when the hotbar moves to anything else)
		String switchKey = heldKind == null ? null : heldKind + ":" + p.getInventory().selectedSlot + ":" + (revolver ? RevolverItem.variation(held) : UKWeaponItem.variation(held))
			+ ":" + (revolver ? RevolverItem.alternate(held) : UKWeaponItem.alternate(held));
		if (switchKey != null && lastSwitchKey != null && !switchKey.equals(lastSwitchKey)) UKSound.play("uk.impacts.impacts_simple_003", 0.35f, 3f);
		if (switchKey != null || lastSwitchKey == null) lastSwitchKey = switchKey;
		else lastSwitchKey = "";
		if (heldKind != lastHeldKind) {
			// every held-weapon loop ends with the weapon (a Piercer charge loop kept playing after a switch)
			UKSound.stop("uk.weapons.revolver.piercecharge");
			UKSound.stop("uk.weapons.revolver.charging");
			lastHeldKind = heldKind;
		}
		arsenal.charges(heldKind == WeaponKind.NAILGUN && UKWeaponItem.variation(held) == 0 && inputOk && UKKeys.ATTACK.held && !thirdPerson);
		if (heldKind != null && !revolver && !thirdPerson) arsenal.tick(p, heldKind, UKWeaponItem.variation(held), inputOk);
		else arsenal.putAway();
		// the shop puts the gun down: its charged hum and capsule loops stop, and start again on the way out (user)
		arsenal.railHum(revolver ? null : heldKind, UKWeaponItem.variation(held), !thirdPerson && !com.ultracraft.client.shop.ShopMode.active());
		// third person is clash mode (GunControl.NoWeapon): the revolver is put away like the rest, so coming back plays
		// its PickUp (Revolver.OnEnable)
		if (revolver && !thirdPerson) {
			int variation = RevolverItem.variation(held);
			revolverSwitchIn(held);
			tickRevolver(p, held, variation, inputOk && !thirdPerson);
			coinBeeps(variation, RevolverItem.alternate(held));
		} else if (revolverOut) {
			revolverOut = false;
			lastVariation = -1;
			pierceShotCharge = 0;
			chargingPierce = false;
			endTwirl();
			UKSound.stop("uk.weapons.revolver.piercecharge");
		}
		// the Piercer's loops live on its own AudioSource: they only sound while the Piercer is in hand. The battery
		// "charging" loop stopped only when the battery filled on the Piercer, so a swap to another variation (whose
		// battery fills silently) or weapon left it looping forever
		boolean piercerHeld = revolver && RevolverItem.variation(held) == 0 && !thirdPerson;
		if (!piercerHeld || pierceReady) UKSound.stop("uk.weapons.revolver.charging");
		if (!piercerHeld || !chargingPierce) UKSound.stop("uk.weapons.revolver.piercecharge");
		// the Sharpshooter's Twirling loop (its ChargeEffect AudioSource) sounds only while it spins in hand
		boolean twirlHeld = revolver && RevolverItem.variation(held) == 2 && !thirdPerson;
		if (!twirlHeld || !(chargingPierce || twirlRecovery)) {
			UKSound.stop("uk.weapons.revolver.twirling");
			if (!twirlHeld) twirlRecovery = false;
		}
	}

	private void tickRevolver(ClientPlayerEntity p, ItemStack held, int variation, boolean inputOk) {
		boolean alt = RevolverItem.alternate(held);
		// Revolver.Update: fire-rate charge
		if (!shootReady) {
			if (shootCharge + 200 * DT < 100) shootCharge += 200 * DT;
			else { shootCharge = 100; shootReady = true; }
		}
		// Piercer battery
		if (!pierceReady) {
			if (variation == 0) {
				float num = alt ? 0.5f : 1f;
				if (pierceCharge + 40 * DT < 100) pierceCharge += 40 * DT * num;
				else {
					pierceCharge = 100;
					pierceReady = true;
					UKSound.stop("uk.weapons.revolver.charging");
					UKSound.play("uk.weapons.revolver.beepbeep_high", 0.35f, UKSound.range(1f, 1.1f));
				}
			} else if (pierceCharge + 480 * DT < 100) pierceCharge += 480 * DT;
			else { pierceCharge = 100; pierceReady = true; }
		}
		float coinCharge = variation == 1 ? rev1charge : rev2charge;
		// a click shortly before the gun is ready is kept and fires the moment it is (weapons.fireBuffer s)
		if (inputOk && UKKeys.ATTACK.press) fireBuffer = (float) Tune.get("weapons.fireBuffer");
		else if (fireBuffer > 0) fireBuffer = Math.max(0, fireBuffer - DT);
		boolean fire1 = inputOk && (UKKeys.ATTACK.held || fireBuffer > 0);
		boolean fire2 = inputOk && UKKeys.USE.held;
		boolean fire2Released = UKKeys.USE.release; // a release while input is blocked (focus lost) still ends the charge
		boolean fire2Pressed = inputOk && UKKeys.USE.press;
		if (variation != 1 && gunReady) {
			float num2 = variation == 0 ? 175 : (alt ? 750 : 75);
			boolean chargedReady = variation == 0 ? pierceShotCharge >= 100 : pierceShotCharge >= 25;
			if ((fire2Released || fire1) && shootReady && chargedReady) {
				shoot(p, held, variation, 2);
			} else if (fire1 && shootReady && !chargingPierce) {
				shoot(p, held, variation, 1);
			} else if (fire2 && (variation == 2 || shootReady) && (variation == 0 ? pierceReady : coinCharge >= (alt ? 300 : 100))
				&& (variation != 2 || chargingPierce || pulledOut(held))) {
				if (!chargingPierce && !twirlRecovery) latestTwirlRotation = 0;
				if (!chargingPierce && variation == 0) UKSound.loop("uk.weapons.revolver.piercecharge", 0.25f, 1f);
				chargingPierce = true;
				if (pierceShotCharge + num2 * DT < 100) pierceShotCharge += num2 * DT;
				else pierceShotCharge = 100;
			} else {
				if (chargingPierce) twirlRecovery = true;
				chargingPierce = false;
				if (pierceShotCharge - num2 * DT > 0) pierceShotCharge -= num2 * DT;
				else pierceShotCharge = 0;
				if (pierceShotCharge == 0) UKSound.stop("uk.weapons.revolver.piercecharge");
			}
			// the Piercer's charge effect: pitch and volume follow the charge
			if (variation == 0 && pierceShotCharge > 0) UKSound.setLoop("uk.weapons.revolver.piercecharge", 0.25f + pierceShotCharge * 0.005f, pierceShotCharge * 0.005f);
		} else if (variation == 1) {
			if (fire2Pressed && pierceReady && coinCharge >= 100) {
				rev1charge -= 100;
				throwCoin(p);
			} else if (gunReady && fire1 && shootReady) {
				shoot(p, held, variation, 1);
			}
		}
		// Sharpshooter twirl: the cylinder spins while charging; twirlLevel = min(3, floor(charge/25)) + 1
		RigInstance rig = revolverRig(held);
		if (variation == 2 && (chargingPierce || twirlRecovery)) {
			boolean flag = latestTwirlRotation < 0;
			if (chargingPierce) twirlLevel = Math.min(3, (float) Math.floor(pierceShotCharge / 25)) + 1;
			latestTwirlRotation += 1200 * (twirlLevel / 3 + 0.5f) * DT;
			if (twirlRecovery && flag && latestTwirlRotation >= 0) { latestTwirlRotation = 0; twirlRecovery = false; }
			while (latestTwirlRotation > 180) latestTwirlRotation -= 360;
			// Revolver.Update: twirlBone.localRotation += forward * latestTwirlRotation; drawn per frame by GunDisplays
			twirlRate = 1200 * (twirlLevel / 3 + 0.5f);
			twirlTickNanos = System.nanoTime();
			// Revolver.LateUpdate: ceaud (clip Twirling, loop) plays at pitch 0.5 + twirlLevel / 2; Update sets its
			// volume to 0.25 + pierceShotCharge * 0.005
			UKSound.loop("uk.weapons.revolver.twirling", 0.25f + pierceShotCharge * 0.005f, 0.5f + twirlLevel / 2f);
			// the animator (Revolver Heavy): Spinning true sends Idle to SpinPose1 (the Twirl clip, speed 2 x TwirlSpeed,
			// TwirlSpeed = twirlLevel / 3) in 0.079 s; the charged shot's Shoot3 (the hand kicks up) only hands over to
			// SpinPose1 at 62.5 % of its clip (0.047 s), else it plays out to Idle
			// (Revolver2, the alternate: Shoot / Shoot2 / Shoot3 hand over at 0.847 / 0.846 / 0.882 in 0.096 / 0.108 /
			// 0.118 s)
			if (rig != null) {
				String cur = rig.currentClip();
				boolean altRig = RevolverItem.alternate(held);
				float handOff = !altRig ? ("Shoot3".equalsIgnoreCase(cur) ? 0.625f : -1f)
					: "Shoot3".equalsIgnoreCase(cur) ? 0.882f : "Shoot".equalsIgnoreCase(cur) ? 0.847f : "Shoot2".equalsIgnoreCase(cur) ? 0.846f : -1f;
				com.ultracraft.client.uk.UkClip shotClip = handOff < 0 ? null : com.ultracraft.client.uk.UkAssets.clip(rig.rigName(), cur);
				boolean inShot = shotClip != null && rig.clipTime() < handOff * shotClip.length;
				float shotFade = !altRig ? 0.047f : "Shoot3".equalsIgnoreCase(cur) ? 0.118f : "Shoot".equalsIgnoreCase(cur) ? 0.096f : 0.108f;
				float spinSpeed = 2f * twirlLevel / 3f;
				if ("Twirl".equalsIgnoreCase(cur)) rig.setSpeed(spinSpeed);
				else if (!inShot && !"PickUp".equalsIgnoreCase(cur) && !"PickUpWithReload".equalsIgnoreCase(cur))
					rig.play("Twirl", spinSpeed, shotClip != null ? shotFade : Tune.getF("view.twirlFadeIn"));
			}
		} else if (rig != null && variation == 2) {
			twirlRate = 0;
			rig.setExtraRotation("Armature/Upper Arm/Forearm/Hand/Revolver_Bone", null);
			// SpinPose1 -> Idle once Spinning is false (0.049 s; Revolver2 0.092 s)
			if ("Twirl".equalsIgnoreCase(rig.currentClip())) rig.play("Idle", 1, RevolverItem.alternate(held) ? 0.092f : 0.049f);
		}
	}

	/**
	 * Revolver.CheckCoinCharges: when a coin panel / spin meter fills up, the canvas AudioSource plays Bell_10_b at
	 * pitch 1 + i/2 (volume 0.15).
	 */
	private void coinBeeps(int variation, boolean alt) {
		// the alternate Sharpshooter has one panel, full at charge 300
		boolean one = alt && variation == 2;
		int n = variation == 1 ? 4 : one ? 1 : variation == 2 ? 3 : 0;
		float charge = variation == 1 ? rev1charge : rev2charge;
		for (int i = 0; i < 4; i++) {
			boolean full = i < n && (one ? charge / 300f >= 1f : charge / 100f - i >= 1f);
			// (the sound event is uk.ui.bell_10_b: Bell_10_b lives in Assets/Sounds/UI)
			boolean[] charged = coinPanelsCharged[alt ? 1 : 0][Math.max(0, Math.min(2, variation))];
			if (full && !charged[i]) UKSound.play("uk.ui.bell_10_b", 0.15f, 1f + i / 2f);
			charged[i] = full;
		}
	}

	/** PlatformerMovement.Spin: the model spins through enemies while V1 is shoved along the look direction. */
	private void clash(ClientPlayerEntity p) {
		if (com.ultracraft.weapon.Damage.debug) UltraCraft.LOG.info("[weapons] clash spin");
		clashCooldown = (float) Tune.get("clash.spinTime") + 0.2f;
		V1.INSTANCE.clashSpin = true;
		V1.INSTANCE.clashSpinUntil = System.nanoTime() + (long) (Tune.get("clash.spinTime") * 1e9);
		Vec3d look = p.getRotationVec(1f);
		Net.toServer(new FirePayload(FirePayload.PUNCH, 3, 0, p.getEyePos(), look));
		// the SpinZone's Sound child: WhooshLoud (loop, 0.5) plays while the zone is active, i.e. for the spin only
		UKSound.loop("uk.movement.whooshloud", 0.5f, 1.5f); // pitch 1.5 picked by ear against the game
		clashSounding = true;
	}

	/**
	 * The Sharpshooter only starts spinning once the pull-out has played out and the hand has settled at Idle (the
	 * PickUp -> Idle blend is done): a right click held through a swap waits for it, then blends Idle into the spin
	 * (the game starts at ReadyGun, mid pull-out; kept off by choice).
	 */
	private boolean pulledOut(ItemStack held) {
		RigInstance rig = revolverRig(held);
		if (rig == null) return true;
		String cur = rig.currentClip();
		return !"PickUp".equalsIgnoreCase(cur) && !"PickUpWithReload".equalsIgnoreCase(cur) && !("Idle".equalsIgnoreCase(cur) && rig.fading());
	}

	/**
	 * The switch-in (GunControl: PickUp / Equip from the first frame) the moment the held weapon, its variation or its
	 * alternate version changes. Called every tick and at the start of every frame, before the arms are posed: the
	 * variation arrives with the server's item sync between ticks, and waiting for the next tick showed the new
	 * variation in the old idle pose for a few frames before the pull-out.
	 */
	public void syncSwitchIn(ClientPlayerEntity p) {
		MinecraftClient client = MinecraftClient.getInstance();
		ItemStack held = p.getMainHandStack();
		WeaponKind kind = UKWeaponItem.kindOf(held);
		// (clash mode puts every gun away, the revolver too: back in first person it is drawn again)
		if (kind == null || !client.options.getPerspective().isFirstPerson()) return;
		if (kind == WeaponKind.REVOLVER) revolverSwitchIn(held);
		else arsenal.switchIn(kind, UKWeaponItem.variation(held), UKWeaponItem.alternate(held));
	}

	private void revolverSwitchIn(ItemStack held) {
		int variation = RevolverItem.variation(held);
		boolean alt = RevolverItem.alternate(held);
		if (revolverOut && variation == lastVariation && alt == lastAlt) return;
		// GunControl: weapon switched in → PickUp anim, ReadyGun event re-arms it
		RigInstance rig = revolverRig(held);
		revolverOut = true;
		lastVariation = variation;
		lastAlt = alt;
		gunReady = false;
		pierceShotCharge = 0;
		chargingPierce = false;
		endTwirl();
		// Revolver.OnEnable: SlowPickup (the alternate revolver was put away uncocked) → PickUpWithReload
		if (rig != null) rig.play(alt && altPickupCharge[variation] > 0 ? "PickUpWithReload" : "PickUp", 1, 0);
		if (variation == 0 && !pierceReady) UKSound.loop("uk.weapons.revolver.charging", 0.55f, alt ? 0.5f : 1f);
	}

	/** A weapon or variation switch ends the Sharpshooter spin at once (the next gun is drawn straight). */
	private void endTwirl() {
		twirlRecovery = false;
		latestTwirlRotation = 0;
		twirlRate = 0;
		UKSound.stop("uk.weapons.revolver.twirling");
	}

	/** Revolver.Shoot(shotType): 1 normal beam, 2 charged (Super / Sharp). */
	private void shoot(ClientPlayerEntity p, ItemStack held, int variation, int shotType) {
		boolean alt = RevolverItem.alternate(held);
		fireBuffer = 0;
		shootReady = false;
		shootCharge = 0;
		RigInstance rig = revolverRig(held);
		int ricochets = 0;
		if (shotType == 1) {
			// gunShots: the alternate revolver's is AltRevolverShoot1D
			UKSound.play("uk.weapons.revolver." + (alt ? "altrevolvershoot1d" : Math.random() < 0.5 ? "shoot1c4" : "shoot1c3"), 0.55f, UKSound.range(0.9f, 1.1f));
			V1.INSTANCE.fovKick += 1f / 40f;
		} else {
			if (variation == 2) ricochets = Math.min(3, (int) Math.floor(pierceShotCharge / 25));
			pierceShotCharge = 0;
			pierceReady = false;
			pierceCharge = 0;
			if (variation == 0) {
				UKSound.stop("uk.weapons.revolver.piercecharge");
				UKSound.loop("uk.weapons.revolver.charging", 0.55f, alt ? 0.5f : 1f);
			} else {
				rev2charge -= alt ? 300 : 100;
				UKSound.play("uk.weapons.revolver.twirlshot", 1f, 1f);
			}
			UKSound.play("uk.weapons.revolver.shoot2b3", 1f, 1f);
			V1.INSTANCE.fovKick += 1f / 20f;
		}
		Vec3d origin = CoinAim.origin(p);
		Vec3d dir = CoinAim.look(p);
		CoinAim.claim(p, origin, dir, shotType == 1 ? 0.4f : 0.6f); // RevolverBeam radius: single 0.4, charged 0.6
		HitView.claim(p, origin, dir, shotType == 1 ? 0.4f : 0.6f);
		Net.toServer(new FirePayload(FirePayload.REVOLVER, shotType == 1 ? 0 : 1, ricochets, origin, dir));
		if (!alt) GunDisplays.cylinderTurn(); // RevolverCylinder.DoTurn: the normal revolver only
		// Revolver Beam: a MuzzleFlash child (the yellow 'muzzleflash' star, scale 8 = 2.56 u, spun at random, gone in
		// 0.1 s) and a point light (1, 0.94, 0.67) x 20, range 5, at the barrel
		// charged shots: Revolver Beam Super (Piercer) has the 'muzzleflashblue' star and a (0.27, 0.88, 1) light,
		// Revolver Beam Sharp (Sharpshooter) the red-orange 'muzzleflashnailgun' star and a (1, 0.235, 0.235) light
		boolean altRig = RevolverItem.alternate(held);
		boolean superBeam = shotType == 2 && variation == 0, sharpBeam = shotType == 2 && variation == 2;
		// a Sharpshooter shot fired mid-twirl: the game shoots from the barrel wherever the spin has it, but our closer gun
		// swings the barrel up to the camera, where the star landed at the screen's edge, away from the gun (user). The
		// spin ends at the shot instead, so the star and the beam leave the barrel where the gun is seen
		if (sharpBeam && (twirlRecovery || chargingPierce)) endTwirl();
		GunDisplays.flash(altRig ? "revolver_alt" : "revolver", altRig ? "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/ShootPoint (1)" : "Armature/Upper Arm/Forearm/Hand/Revolver_Bone/ShootPoint",
			superBeam ? "muzzleflashblue" : sharpBeam ? "muzzleflashnailgun" : "muzzleflash",
			(float) (Tune.get("view.revFlashSize") * (altRig && shotType == 1 ? Tune.get("view.altFlashSize") : !altRig ? Tune.get("view.mainFlashSize") : 1)),
			(float) (altRig && shotType == 1 ? Tune.get("view.altFlashTime") : !altRig ? Tune.get("view.mainFlashTime") : Tune.get("view.revFlashTime")), altRig && shotType == 1);
		if (superBeam) GunDisplays.muzzleLight(0.27f, 0.88f, 1f);
		else if (sharpBeam) GunDisplays.muzzleLight(1f, 0.235f, 0.235f);
		else GunDisplays.muzzleLight();
		if (Tune.on("debug.weapons")) UltraCraft.LOG.info("[weapons] revolver shot type={} var={} ricochets={}", shotType, variation, ricochets);
		// animator: Shoot / Shoot2 by RandomChance, Shoot3 for the charged shot (state speeds from the controller; the
		// 0.5 Revolver.Shoot passes for the alternate revolver is PlayerAnimations.Shoot, the third-person model)
		if (rig != null) {
			if (shotType == 1) rig.play(Math.random() < 0.5 ? "Shoot" : "Shoot2", 1f, 0);
			else rig.play("Shoot3", 1f, 0);
		}
		// WeaponCharges.revaltpickupcharges: an alternate revolver put away before its hammer is cocked (the Click
		// event) comes back with PickUpWithReload for 2 s
		if (alt) altPickupCharge[variation] = 2f;
		gunReady = false;
		V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, shotType == 1 ? 0.5 : 1.0);
	}

	/** Revolver.ThrowCoin: a Coin 0.5 u below the camera with forward 20 + up 15 + player velocity. */
	private void throwCoin(ClientPlayerEntity p) {
		pierceCharge = 0;
		pierceReady = false;
		RigInstance f = fistRig();
		if (f != null && punchReady) f.play(OffhandHold.holding(f, "CoinFlip"), 1, 0);
		UKSound.play("uk.weapons.revolver.coinflip", 1f, 1f);
		// Revolver.ThrowCoin: spawned at camera - camera.up * 0.5 with the camera's rotation, AddForce(forward * 20
		// + up * 15 + PlayerTracker.GetPlayerVelocity(true), VelocityChange). The player's velocity is V1's own
		// (full, dashes included; while riding a rocket it is the rocket's), which only the client knows exactly:
		// send the start point and the whole velocity (game units / s) so the coin keeps pace with V1.
		MinecraftClient mc = MinecraftClient.getInstance();
		double S = com.ultracraft.weapon.Damage.SCALE_BLOCKS;
		// from the player's tick position (eye minus the slide's camera drop), not the rendered camera: that one is
		// interpolated up to a tick behind, which put the coin beside / behind V1 while moving
		net.minecraft.util.math.Vec3d cam = p.getEyePos().subtract(0, V1.INSTANCE.camDrop, 0);
		net.minecraft.util.math.Vec3d look = p.getRotationVec(1f);
		net.minecraft.util.math.Vec3d camUp = net.minecraft.util.math.Vec3d.fromPolar(p.getPitch() - 90f, p.getYaw());
		net.minecraft.util.math.Vec3d start = cam.subtract(camUp.multiply(0.5 * S));
		// the game: forward 20, up 15, player velocity x1 (live: coin.throwForward / coin.throwUp / coin.throwPlayerVel)
		net.minecraft.util.math.Vec3d vel = look.multiply(Tune.get("coin.throwForward")).add(0, Tune.get("coin.throwUp"), 0)
			.add(V1.INSTANCE.vel.multiply(Tune.get("coin.throwPlayerVel")));
		com.ultracraft.entity.CoinEntity.clientThrowStart = start;
		com.ultracraft.entity.CoinEntity.clientThrowNanos = System.nanoTime();
		// the predicted coin: leaves the hand this frame; the server's coin takes over when it arrives
		if (mc.world != null) {
			com.ultracraft.entity.CoinEntity old = com.ultracraft.entity.CoinEntity.clientGhost;
			if (old != null && !old.isRemoved()) mc.world.removeEntity(old.getId(), net.minecraft.entity.Entity.RemovalReason.DISCARDED);
			com.ultracraft.entity.CoinEntity g = new com.ultracraft.entity.CoinEntity(com.ultracraft.entity.UKEntities.COIN.get(), mc.world);
			g.ghost = true;
			g.setPosition(start);
			g.setYaw(p.getYaw());
			g.setPitch(p.getPitch());
			// interpolate like the player this tick (prev = start minus V1's last tick of movement), so between
			// ticks the coin stays fixed to the view instead of lagging behind it
			double mx = p.getX() - p.prevX, my = p.getY() - p.prevY, mz = p.getZ() - p.prevZ;
			g.prevX = start.x - mx; g.prevY = start.y - my; g.prevZ = start.z - mz;
			g.lastRenderX = g.prevX; g.lastRenderY = g.prevY; g.lastRenderZ = g.prevZ;
			g.setVelocity(vel.multiply(S * 0.05)); // blocks per tick
			mc.world.addEntity(nextGhostId--, g);
			com.ultracraft.entity.CoinEntity.clientGhost = g;
		}
		if (com.ultracraft.weapon.Damage.debug) UltraCraft.LOG.info("[weapons] coin throw: start {} cam {} v1vel {} (u/s) moved/tick {} vel {}", start, cam, V1.INSTANCE.vel,
			p.getPos().subtract(p.prevX, p.prevY, p.prevZ), vel);
		Net.toServer(new FirePayload(FirePayload.COIN, 1, 0, start, vel));
	}

	private static int nextGhostId = -1_000_000;

	static {
		Tune.def("view.altFlashSize", 1.5, "alternate revolver muzzle flash size vs the normal one (Revolver Beam Alternative: MuzzleFlash scale 12 vs 8)");
		Tune.def("view.mainFlashSize", 1.5, "standard revolver muzzle flash size vs the game's (user, by eye: as big as the alternate's, view.altFlashSize)");
		Tune.def("view.mainFlashTime", 0.1, "standard revolver muzzle flash life, s (user, by eye: as long as the alternate's; the game: view.revFlashTime)");
		Tune.def("view.altFlashTime", 0.1, "alternate revolver muzzle flash life, s (SpriteController 5/s vs the normal 10/s)");
		Tune.def("weapons.fireBuffer", 0.1, "revolver: a left click this many seconds before the gun is ready still fires (about 5 frames at 60 fps)");
		Tune.def("view.twirlFadeIn", 0.2, "Sharpshooter: blend from the idle hand into the spin, s (the game: 0.079)");
		Tune.def("view.revFlashTime", 0.045, "revolver muzzle flash life, s (the game: 2 frames at 60 fps)");
		Tune.def("view.revFlashSize", 1.1, "revolver muzzle flash size factor (x view.flashSize 1.6 = 2.56 u, the game's MuzzleFlash scale 8)");
	}

	static {
		Tune.def("coin.throwForward", 15, "coin throw speed along the view, u/s (by eye, what the dev game uses; the game: 20)");
		Tune.def("coin.throwUp", 15, "coin throw speed straight up, u/s (the game: 15; sets the arc height and when it comes back down)");
		Tune.def("coin.throwPlayerVel", 1, "share of V1's own velocity the coin gets (the game: 1)");
	}

	/** Punch.PunchStart for the current arm. */
	private void punchStart(ClientPlayerEntity p) {
		boolean heavy = currentFist == 1;
		if (com.ultracraft.weapon.Damage.debug) UltraCraft.LOG.info("[weapons] punch heavy={} stamina={} rig={}", heavy, punchStamina, fistRig() == null ? "null" : fistRig().rigName());
		float cooldownCost = heavy ? 3f : 2f;
		punchPressNanos = System.nanoTime();
		// Punch.PunchStart -> HookArm.Cancel: the whip lets go (a pulled enemy flies on with its momentum) and the fist punches
		WhiplashClient.INSTANCE.cancel();
		punchReady = false;
		fistCooldown = cooldownCost * 0.25f;
		punchStamina -= cooldownCost / 2f;
		RigInstance f = fistRig();
		if (f != null) {
			if (heavy) f.play("Punch", 1, 0);
			else f.play(OffhandHold.holding(f, Math.random() < 0.5 ? "Jab" : "Jab2"), 1, 0);
		}
		UKSound.play(heavy ? "uk.weapons.punchswooshheavy" : "uk.weapons.punchswoosh", 1f, UKSound.range(0.9f, 1.1f));
	}

	/** ActiveStart animation event → the hit check is the server's (Punch.ActiveFrame). */
	private void punchActive(ClientPlayerEntity p) {
		// shotgun parry window (mode bit 8): the punch was pressed on the shot's own frame or up to parry.pelletFrames
		// frames (60 fps) after it, so that shot's pellets are boosted wherever they are; the game only takes the pellets
		// still inside the parry zone on the punch's first frame (~0.08 s after the shot)
		double since = (punchPressNanos - ArsenalClient.lastPelletShotNanos) / 1e9;
		double frame = 1 / 60.0;
		boolean pelletWindow = ArsenalClient.lastPelletShotNanos != 0 && since >= -(frame + 0.01) && since <= Tune.get("parry.pelletFrames") * frame + 0.01;
		int mode = (currentFist == 1 ? 1 : 0) | (pelletWindow ? 8 : 0);
		Net.toServer(new FirePayload(FirePayload.PUNCH, mode, punchTarget(p), p.getEyePos(), p.getRotationVec(1f)));
	}

	private long punchPressNanos;

	/**
	 * The enemy the fist meets as V1 sees it (Punch.ActiveFrame: 4 u ahead, 1 u around the ray), as entity id + 1 (0 =
	 * none). A whiplashed enemy flies in at 60 u/s, ~1.5 blocks a tick: by the time the server checks, its copy can be
	 * past V1, so the server takes this one when it is still close (punch.clientTrust).
	 */
	private static int punchTarget(ClientPlayerEntity p) {
		double s = com.ultracraft.weapon.Damage.SCALE_BLOCKS;
		net.minecraft.util.math.Vec3d start = p.getEyePos(), d = p.getRotationVec(1f), end = start.add(d.multiply(4 * s));
		net.minecraft.entity.Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (net.minecraft.entity.Entity e : p.getWorld().getOtherEntities(p, new net.minecraft.util.math.Box(start, end).expand(1.5), en -> en instanceof net.minecraft.entity.LivingEntity && en.isAlive())) {
			net.minecraft.util.math.Box bb = e.getBoundingBox().expand(1 * s);
			java.util.Optional<net.minecraft.util.math.Vec3d> hit = bb.contains(start) ? java.util.Optional.of(start) : bb.raycast(start, end);
			if (hit.isPresent() && hit.get().squaredDistanceTo(start) < bestDist) { bestDist = hit.get().squaredDistanceTo(start); best = e; }
		}
		return best == null ? 0 : best.getId() + 1;
	}

	private void hookRigs() {
		for (String name : new String[] {"revolver", "revolver_alt"}) {
			RigInstance r = off ? ViewModel.offInstance(name) : ViewModel.instance(name);
			if (r == null) continue;
			hooked = true;
			r.onEvent = e -> {
				if (e.name().equals("ReadyGun")) gunReady = true;
				// Revolver.Click (alternate only): the hammer is cocked; the Sharpshooter's spin ends with it
				else if (e.name().equals("Click") && name.equals("revolver_alt") && lastVariation >= 0) {
					UKSound.play("uk.weapons.revolver.revolverclick", 0.5f, UKSound.range(1.45f, 1.55f));
					altPickupCharge[lastVariation] = 0;
					if (lastVariation == 2) { chargingPierce = false; twirlRecovery = false; }
				}
			};
		}
		// the off-hand copy: its revolvers only, their events run as the off-hand weapon
		if (off) {
			OffhandGun.wrapEvents(ViewModel.offInstance("revolver"));
			OffhandGun.wrapEvents(ViewModel.offInstance("revolver_alt"));
			return;
		}
		RigInstance wl = ViewModel.instance("whiplash");
		if (wl != null) {
			hooked = true;
			wl.onEvent = e -> { if (e.name().equals("CatchOver")) WhiplashClient.INSTANCE.catchOver(); };
		}
		for (String name : new String[] {"feedbacker", "knuckleblaster"}) {
			RigInstance r = ViewModel.instance(name);
			if (r == null) continue;
			hooked = true;
			r.onEvent = e -> {
				ClientPlayerEntity p = MinecraftClient.getInstance().player;
				switch (e.name()) {
					case "ActiveStart" -> { if (p != null) punchActive(p); }
					case "ReadyToPunch" -> punchReady = true;
					case "BlastCheck" -> {
						// Knuckleblaster: still holding the punch key at this frame → PunchBlast + shockwave
						if (UKKeys.PUNCH.held && p != null) {
							RigInstance f = fistRig();
							if (f != null) f.play("PunchBlast", 1, 0);
							Net.toServer(new FirePayload(FirePayload.PUNCH, 2, 0, p.getEyePos(), p.getRotationVec(1f)));
							UKSound.play("uk.weapons.punch_heavy_1", 1f, UKSound.range(0.9f, 1.1f));
							V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 1.5);
						}
					}
					case "Eject" -> { UKSound.play("uk.weapons.fistreload", 0.5f, 1f); ShellFx.eject(2); } // Punch.Eject: two shells, the ejector's fistReload
					default -> {}
				}
			};
		}
	}

	/** Feedback from the server about what our attack did: sounds, hitstop, and the style system. */
	/** Block chips at a hit point: the block just behind the surface, as Minecraft's own crack particles. */
	static void bulletHole(MinecraftClient client, ClientPlayerEntity p, Vec3d at, int count) {
		Vec3d in = at.subtract(p.getEyePos()).normalize().multiply(0.05);
		net.minecraft.util.math.BlockPos bp = net.minecraft.util.math.BlockPos.ofFloored(at.add(in));
		net.minecraft.block.BlockState state = client.world.getBlockState(bp);
		if (state.isAir()) { bp = net.minecraft.util.math.BlockPos.ofFloored(at.subtract(in)); state = client.world.getBlockState(bp); }
		if (state.isAir() || state.getRenderType() == net.minecraft.block.BlockRenderType.INVISIBLE) return;
		java.util.Random r = new java.util.Random();
		for (int i = 0; i < count; i++) {
			client.world.addParticle(new net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, state), at.x, at.y, at.z,
				-in.x * 40 + (r.nextDouble() - 0.5) * 0.3, 0.15 + r.nextDouble() * 0.2, -in.z * 40 + (r.nextDouble() - 0.5) * 0.3);
		}
	}

	/**
	 * The sound of the gore an enemy hit spawns (BloodsplatterManager prefabs' AudioSources): BS Head (HeadBreak2, 0.4)
	 * for a headshot of 1+ damage or one that kills, BS Body / Body Small (Impacts_SIMPLE_003, 0.5), BS Body Smallest
	 * (the same, 0.15, pitch 1.5); a kill elsewhere bursts (BS Splatter, HeadBreak2 0.4). Revolver and railcannon beams
	 * add RevolverHit (Melee_Deep_Wound_05 kopio, 0.35, random pitch); a nail or sawblade its enemyHitSound
	 * (Impacts_SIMPLE_003, 0.2, pitch 0.9..1.1).
	 */
	private static void goreSound(HitPayload msg) {
		boolean kill = msg.kind() == HitPayload.KILL;
		int gore = msg.gore();
		if (msg.head() && (gore == 2 || kill)) UKSound.at(msg.pos(), "uk.gore.headbreak2", 0.4f, 1f);
		else {
			if (gore == 0) UKSound.at(msg.pos(), "uk.impacts.impacts_simple_003", 0.15f, 1.5f);
			else UKSound.at(msg.pos(), "uk.impacts.impacts_simple_003", 0.5f, 1f);
			if (kill) UKSound.at(msg.pos(), "uk.gore.headbreak2", 0.4f, 1f);
		}
		if (msg.hitter() == HitPayload.REVOLVER || msg.hitter() == HitPayload.RAILCANNON)
			UKSound.at(msg.pos(), "uk.gore.melee_deep_wound_05___kopio", 0.35f, UKSound.range(0.9f, 1.1f));
		// Nail.HitEnemy: aud.clip = enemyHitSound (Impacts_SIMPLE_003), volume 0.2, pitch 0.9..1.1, for nails and sawblades
		if (msg.hitter() == HitPayload.NAIL || msg.hitter() == HitPayload.SAWBLADE) UKSound.at(msg.pos(), "uk.impacts.impacts_simple_003", 0.2f, UKSound.range(0.9f, 1.1f));
	}

	public void onHit(HitPayload msg) {
		ClientPlayerEntity p = MinecraftClient.getInstance().player;
		MinecraftClient client = MinecraftClient.getInstance();
		if (Tune.on("debug.weapons")) UltraCraft.LOG.info("[weapons] hit kind={} hitter={} arg={} flags={} at {}", msg.kind(), msg.hitter(), msg.arg(), msg.flags(), msg.pos());
		switch (msg.kind()) {
			case HitPayload.ENEMY_HIT, HitPayload.KILL -> {
				if (msg.hitter() == HitPayload.SAWBLADE) { ZapperClient.INSTANCE.sawHit(msg.pos()); ZapperClient.OFF.sawHit(msg.pos()); }
				if (msg.hitter() == HitPayload.SPIN) UKSound.at(msg.pos(), "uk.gore.crashkill", 1f, 1f);
				// blood from the wound, away from the shooter (BloodsplatterManager BS Body Small per hit, Splatter on kills)
				if (p != null) {
					Vec3d dir = msg.pos().subtract(p.getEyePos());
					float amount = msg.kind() == HitPayload.KILL ? 2f : (msg.flags() & HitPayload.BIG) != 0 ? 1.5f : 1f;
					if (msg.hitter() == HitPayload.NAIL || msg.hitter() == HitPayload.SAW) amount *= 0.4f;
					BloodFx.spawn(msg.pos(), dir, amount, msg.kind() == HitPayload.KILL);
				}
				// (the hammer's own effects come with its SWING message)
				if (msg.hitter() == HitPayload.HAMMER) break;
				if (msg.hitter() == HitPayload.PUNCH || msg.hitter() == HitPayload.HEAVYPUNCH) { // heavyHit + hitstop
					UKSound.play("uk.weapons.punch_heavy", 1f, UKSound.range(0.9f, 1.1f));
					hitstop = 0.1f;
					V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.5);
				} else {
					goreSound(msg);
				}
			}
			case HitPayload.COIN_HIT -> UKSound.at(msg.pos(), "uk.weapons.revolver.ricochet", 1f, 1f);
			case HitPayload.WALL_HIT -> {
				// SceneHelper.CreateEnviroGibs: chips of the surface fly off where a shot lands
				if (p != null && client.world != null && msg.hitter() != HitPayload.HAMMER) bulletHole(client, p, msg.pos(), msg.hitter() == HitPayload.NAIL ? 3 : msg.hitter() == HitPayload.SHOTGUN ? 1 : 6);
				if (msg.hitter() == HitPayload.HAMMER) break;
				if (msg.hitter() == HitPayload.HARPOON && msg.arg() == 2) {
					// the magnet's TimeBomb goes: BreakParticleMetal (a Smoke2 cloud and metal debris, pitch 2 +-0.1)
					SteamFx.smoke(msg.pos());
					UKSound.at(msg.pos(), "uk.impacts.bluezone_bc0236_metal_debris_097", 1f, UKSound.range(1.9f, 2f));
					break;
				}
				// a magnet stops in the environment: Harpoon.environmentHitSound harpoonStop, 0.4, pitch 0.9..1.1
				if (msg.hitter() == HitPayload.HARPOON && msg.arg() == 3) { UKSound.at(msg.pos(), "uk.weapons.harpoonstop", 0.4f, UKSound.range(0.9f, 1.1f)); break; }
				if (msg.hitter() == HitPayload.SAWBLADE) { UKSound.at(msg.pos(), msg.arg() == 2 ? "uk.weapons.sawbreak" : "uk.weapons.revolver.ricochet", 0.8f, 1f); break; }
				if (msg.hitter() == HitPayload.CANNONBALL) {
					// Cannonball: 1 bounce (CannonballHit: Metal_Hit_Crash_199 0.85), 3 its ground shockwave
					// (PhysicalShockwavePlayer), 0 Break (BreakParticleBig: boulder impact at pitch 0.75 and 0.5, smoke)
					if (msg.arg() == 1) UKSound.at(msg.pos(), "uk.weapons.metal_hit_crash_199", 0.85f, 1f);
					else if (msg.arg() == 3) { ShockwaveFx.spawn(msg.pos(), 75f, 12f); V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.75); }
					else {
						SteamFx.smoke(msg.pos());
						UKSound.at(msg.pos(), "uk.impacts.boulder_impact_on_stones_14", 1f, 0.75f);
						UKSound.at(msg.pos(), "uk.impacts.boulder_impact_on_stones_14", 1f, 0.5f);
					}
					break;
				}
				// BulletSpark: 5 sparks and "Ricochet" at 0.1
				if (msg.hitter() == HitPayload.SHOTGUN) { SparkFx.burst(msg.pos()); UKSound.at(msg.pos(), "uk.weapons.revolver.ricochet", 0.1f, 1f); break; }
				// Nail.OnCollisionEnter on the environment: environmentHitSound "Ricochet" at 0.2, pitch 0.9..1.1
				if (msg.hitter() == HitPayload.NAIL) { UKSound.at(msg.pos(), "uk.weapons.revolver.ricochet", 0.2f, UKSound.range(0.9f, 1.1f)); break; }
				if (msg.hitter() == HitPayload.PUNCH || msg.hitter() == HitPayload.HEAVYPUNCH) UKSound.play("uk.weapons.punch_grit_wet_impact_05", 1f, UKSound.range(0.9f, 1.1f));
				else if (msg.arg() == 1) UKSound.at(msg.pos(), "uk.weapons.revolver.ricochet", 1f, 1f);
			}
			case HitPayload.SWING -> { if (p != null) ArsenalClient.swingAnswered(p, msg.arg(), msg.tier(), msg.pos()); }
			case HitPayload.JUGGLE -> {
				if (msg.arg() == 2) {
					// a punched sawblade: the parry's sound and Hook, without its flash or freeze
					UKSound.play("uk.weapons.punch_projectile", 1f, 1f);
					RigInstance f = fistRig();
					if (f != null) f.play("Hook", 1, 0, 0.065f);
					break;
				}
				// a punched chainsaw flies out again: Hook clip, full punch stamina (Punch.cs on a Chainsaw); the next saws
				// of the same punch (ChainsawPunchRoutine, 0.05 s apart) only make their hit
				UKSound.play("uk.weapons.punch_projectile", 0.8f, 1.2f);
				punchStamina = 2;
				if (msg.arg() == 3) break;
				RigInstance f = fistRig();
				if (f != null) f.play("Hook", 1, 0, 0.065f);
			}
			case HitPayload.PARRY -> {
				if (msg.arg() == 2) {
					// TimeController.ParryFlash alone (a shot set off a grenade): flash, TrueStop 0.25, shake - no fist
					V1.INSTANCE.parryFlash = 0.1;
					if (Tune.on("parry.freezeOn")) V1.INSTANCE.freeze(0.25);
					V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.5);
					return;
				}
				UKSound.play("uk.weapons.punch_projectile", 1f, 1f);
				// TimeController.ParryFlash: white flash 0.1 s + camera shake 0.5 (the hit-stop is not reproduced)
				V1.INSTANCE.parryFlash = 0.1;
				if (Tune.on("parry.freezeOn")) V1.INSTANCE.freeze(Tune.get("parry.freeze"));
				V1.INSTANCE.shake = Math.max(V1.INSTANCE.shake, 0.5);
				// full stamina and no hard damage only for parrying something hostile (arg 1 = boosting your own pellets)
				if (msg.arg() != 1) {
					V1.INSTANCE.boostCharge = 300;
					V1.INSTANCE.antiHp = 0;
				}
				// (arg 3: a shooting parry - the shotgun's point-blank blast parried it, the fist stays down)
				if (msg.arg() == 3) break;
				RigInstance f = fistRig();
				// Punch.Parry: anim.Play("Hook", 0, 0.065f) -- past the clip's own ActiveStart, so no second hit check
				if (f != null) f.play("Hook", 1, 0, 0.065f);
			}
			default -> {}
		}
		com.ultracraft.client.style.StyleState.INSTANCE.onHit(msg);
	}
}
