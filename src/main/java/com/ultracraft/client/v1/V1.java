package com.ultracraft.client.v1;

import net.minecraft.util.math.Vec3d;

/**
 * V1's state, one instance for the local player. Units are ULTRAKILL's (Unity units, seconds); see
 * docs/ULTRAKILL_MECHANICS.md. Field names follow NewMovement so the port can be checked against it.
 */
public final class V1 {
	public static final V1 INSTANCE = new V1();

	/** Steve-sized V1: 1 Unity unit in blocks (V1's 3.5 u capsule = Steve's 1.8 blocks). */
	public static final double SCALE = 1.8 / 3.5;
	/** ULTRAKILL's fixed physics step. */
	public static final double DT = 0.008;

	// velocity in u/s, in Minecraft's world axes
	public Vec3d vel = Vec3d.ZERO;
	public Vec3d preDashSpeed = Vec3d.ZERO;
	public Vec3d velocityAfterSlide = Vec3d.ZERO;

	public int hp = 100;
	public double antiHp;
	public double antiHpCooldown;
	public boolean cantInstaHeal;
	public double hurtInvincibility;
	public boolean dead;

	public boolean onGround, slopeGround, onWall;
	public Vec3d wallNormal = Vec3d.ZERO;
	public Vec3d inputDir = Vec3d.ZERO;
	/** The raw Move input (forward, right), -1..1: Grenade.LateUpdate steers a ridden rocket with it. */
	public double inputF, inputR;
	public boolean walking;
	public boolean falling;
	public boolean jumping;
	public double jumpingTimer;
	public boolean jumpCooldown;
	public double jumpCooldownTimer;
	public double fallTime;
	public double fallSpeed;
	public double sinceLastGrounded;
	public double lastJump = 99;
	/** A plain jump that a quick release of the key can still cut short (the short hop option). */
	public boolean hopCuttable;
	/** V1 was inside a Nether portal last step (the portal stop catches it once on the way in). */
	public boolean inPortal;

	public boolean boost;
	public double boostCharge = 300;
	public double boostLeft;
	public boolean dashedFromGround;
	public double dashStorage;
	public Vec3d dodgeDirection = Vec3d.ZERO;
	/** 0 forward, 1 backward, 2 sideways (camera FOV / tilt). */
	public int dodgeDirectionKind;

	public boolean sliding;
	public double slideSafety;
	public boolean slideEnding;
	/** NewMovement.onGasoline / modForcedFrictionMultip 0: Firestarter gasoline under V1, no ground friction */
	public boolean onGasoline;
	public double slideLength;
	public int framesSinceSlide;
	public double slideTimestamp = -99, jumpTimestamp = -99;
	public double preSlideSpeed, preSlideDelay;
	public boolean crouching, slowMode, standing = true;

	public boolean heavyFall;
	/** A slam stored while the whiplash pulled V1: the slide key only holds it (no crouch) until it lands. */
	public boolean hookSlam;
	/** Clash mode (PlatformerMovement): the slam's wind-up, V1 held in the air before it drops, and its time so far. */
	public boolean aboutToSlam;
	public double slamWindup;
	/** The slam under way began as a clash-mode one (it lands the platformer's way). */
	public boolean clashSlam;
	public double slamForce;
	public boolean slamStorage;
	public double slamCooldown;
	public boolean stillHolding;
	public double superJumpChance, extraJumpChance;
	public boolean hasImpacted;
	/** GroundCheck.hitEnemies: the mobs this slam already landed on; bounceChance / bouncePosition: the late-jump bounce. */
	public final java.util.Set<Integer> slamHits = new java.util.HashSet<>();
	public double bounceChance;
	public Vec3d bouncePos = Vec3d.ZERO;

	public int currentWallJumps;
	public double clingFade;
	public int rocketJumps, hammerJumps, rocketRides;
	public double explosionLaunchResistance, safeExplosionLaunchCooldown;
	public double windState;

	/** Game time in seconds (ULTRAKILL's InputState.currentTime). */
	public double time;
	public double accumulator;

	/** Camera feel, read by the renderer (blocks / degrees). */
	public double camDrop, camDropTarget; // slide: 0.625 u down
	public double tiltZ, tiltZSmoothVel;
	public double shake;
	/** Seconds left of the parry's white screen flash. */
	public double parryFlash;
	/** TimeController.TrueStop: the game is frozen (last frame held, V1 does not move) until this nanoTime. */
	public long freezeUntil;
	/** The camera shake's move this frame (blocks, to the camera's right and up), set by CameraMixin. */
	public double shakeRight, shakeUp;
	/** Clash mode (third person): the model should start its Spin now. */
	public boolean clashSpin;
	/** nanoTime until which the third-person model keeps spinning (PlatformerMovement.spinJuice ≈ 0.33 s). */
	public long clashSpinUntil;

	public boolean frozen() {
		return System.nanoTime() < freezeUntil;
	}

	public void freeze(double seconds) {
		freezeUntil = Math.max(freezeUntil, System.nanoTime() + (long) (seconds * 1e9));
	}
	/** Weapon FOV kick as a fraction of the default FOV (Revolver: +1/40, charged +1/20; returns at 300°/s). */
	public double fovKick;
	/** CameraController.Zoom: FOV multiplier while zooming (0 = none; the Railcannon zooms to defaultFov / 2). */
	public double zoomFov;
	/**
	 * CameraController.Zoom (railcannon right click): while held both cameras ease toward defaultFov / 2 at 300 deg/s
	 * (MoveTowards), the world one from its FOV, the HUD camera (gun + HUD panel) from 90; released, back the same way.
	 */
	public boolean zoomHeld;
	/** The world FOV's zoom multiplier now (1 = none) and the HUD camera's FOV now, in degrees (90 = none). */
	public double zoomWorldMul = 1, zoomHudFov = 90, zoomBase = 70;
	private long zoomNanos;

	/** Once a frame: move both zooms toward their targets. */
	public void zoomFrame() {
		long now = System.nanoTime();
		double dt = zoomNanos == 0 ? 0 : Math.min(0.1, (now - zoomNanos) / 1e9);
		zoomNanos = now;
		if (frozen()) dt = 0;
		double step = 300 * dt;
		double base = Math.max(1, zoomBase);
		double cur = base * zoomWorldMul, tgt = zoomHeld ? base / 2 : base;
		cur = cur < tgt ? Math.min(tgt, cur + step) : Math.max(tgt, cur - step);
		zoomWorldMul = cur / base;
		double hudTgt = zoomHeld ? base / 2 : 90;
		zoomHudFov = zoomHudFov < hudTgt ? Math.min(hudTgt, zoomHudFov + step) : Math.max(hudTgt, zoomHudFov - step);
	}

	/** A HUD-camera FOV (gun or HUD panel, normally `def`) under the zoom. */
	public float hudFov(float def) {
		return (float) (def * zoomHudFov / 90.0);
	}
	/** the world camera's final vertical FOV this frame (the arms are drawn with view.fov instead) */
	public double worldFov = 70;
	/** GroundCheck.superJumpChance: 0.1 s after a slam landing; if slide is still held a PhysicalShockwavePlayer spawns. */
	public double slamShockTimer;
	/** Grenade.playerRiding: the rocket entity id we stand on (-1 = none), and its entity on the client. */
	public int ridingRocket = -1;
	/** Where V1's feet sit from the ridden rocket's position (the renderer draws the rocket from V1's place). */
	public net.minecraft.util.math.Vec3d rideOffset;
	public double hurtAlpha;
	public double landBob;

	public void resetOnSpawn() {
		vel = Vec3d.ZERO;
		hp = 100;
		antiHp = 0;
		boost = sliding = heavyFall = aboutToSlam = false;
		boostCharge = 300;
		falling = jumping = false;
		dead = false;
	}
}
