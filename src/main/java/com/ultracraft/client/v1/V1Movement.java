package com.ultracraft.client.v1;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UKKeys;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Port of ULTRAKILL's NewMovement (Update + FixedUpdate + Move/Dodge/Jump/WallJump/Cling/slide/slam) onto
 * the Minecraft client player. Runs ULTRAKILL's 125 Hz physics in sub-steps inside each 20 Hz tick, moving
 * the player with Minecraft's own collision (`Entity.move`). All numbers are the game's; the ones worth
 * tuning by eye are live settings (Tune).
 *
 * Unity's `walkSpeed * Time.deltaTime * k` with the 0.008 s step is folded into the constants here:
 * ground speed 750·0.008·2.75 = 16.5 u/s, a jump force of 90·1500·k applied for one step on a 100 kg body
 * is a 10.8·k u/s velocity change, etc. (docs/ULTRAKILL_MECHANICS.md has the derivations).
 */
public final class V1Movement {
	private static final double GRAVITY = Tune.def("physics.gravity", 40, "u/s², Unity project gravity");
	private static final double STOMP_PAD = Tune.def("slam.stompPad", 0.35, "slam crush zone: padding around V1's feet, blocks (by eye)");
	private static final double STOMP_DAMAGE = Tune.def("slam.stompDamage", 2, "slam landing on an enemy: damage points (GroundCheck: 2, from the game)");
	private static final double STOMP_DEPTH = Tune.def("slam.stompDepth", 0.6, "slam crush zone: how far below V1's feet it reaches, blocks (by eye)");
	private static final double WALK = Tune.def("move.walkSpeed", 16.5, "u/s ground speed (750 * 0.008 * 2.75)");
	private static final double SLOW = Tune.def("move.slowSpeed", 7.5, "u/s while crouched under a low ceiling (slowMode)");
	private static final double GROUND_LERP = Tune.def("move.groundLerp", 0.25, "velocity lerp toward target per physics step on the ground");
	private static final double AIR_ACCEL = Tune.def("move.airAccelPerStep", 0.48, "u/s added per step in the air (6000 * 0.008 / mass 100)");
	private static final double AIR_CAP = Tune.def("move.airCap", 16.5, "u/s air control cap per input axis");
	private static final double JUMP_UNIT = Tune.def("jump.unit", 10.8, "u/s per jumpPower unit (90 * 1500 * 0.008 / 100)");
	private static final double JUMP_MULT = Tune.def("jump.normal", 2.6, "x unit: normal jump (28.08 u/s)");
	private static final double SLIDE_JUMP_MULT = Tune.def("jump.slide", 2.0, "x unit: slide jump");
	private static final double DASH_JUMP_MULT = Tune.def("jump.dash", 1.5, "x unit: dash jump");
	private static final double SLOW_JUMP_MULT = Tune.def("jump.slow", 1.25, "x unit: crouched jump");
	private static final double HOP_WINDOW = Tune.def("jump.hopWindow", 0.15, "short hop: jump released within this many s of the jump cuts it (mod option, not ULTRAKILL)");
	private static final double HOP_MIN = Tune.def("jump.hopMin", 0.08, "short hop: the cut waits this long into the jump, so the quickest tap still rises about 1.15 blocks (clears a portal frame)");
	private static final double HOP_KEEP = Tune.def("jump.hopKeep", 3, "short hop: upward speed left after the cut, u/s");
	private static final double AIR_BRAKE_RATE = Tune.def("move.airBrakeRate", 4, "air brake: sideways drift lost per s with no movement key held in mid-air (mod option, not ULTRAKILL; user: 8 too strong, 4 by feel)");
	private static final double PORTAL_STOP = Tune.def("move.portalStop", 1, "1 = running or dashing into a Nether portal stops V1 in it, so Minecraft's portal wait takes it through (user)");
	private static final double AIR_BRAKE_MAX = Tune.def("move.airBrakeMax", 1.5, "air brake: only drift up to this x walking speed is braked (launches faster than that keep their speed)");
	private static final double DASH_SPEED_MULT = Tune.def("dash.speedMult", 3, "dash speed = walkSpeed * this (49.5 u/s)");
	private static final double DASH_DRAIN = Tune.def("dash.drainPerStep", 4, "boostLeft 100 drains this per step (25 steps = 0.2 s)");
	private static final double DASH_COST = Tune.def("stamina.dashCost", 100, "stamina per dash");
	private static final double STAMINA_MAX = Tune.def("stamina.max", 300, "three bars");
	private static final double STAMINA_REGEN = Tune.def("stamina.regen", 70, "per second (Standard difficulty)");
	private static final double SLIDE_SPEED = Tune.def("slide.speed", 24, "u/s (750 * 0.008 * 4)");
	private static final double SLIDE_STRAFE = Tune.def("slide.strafe", 5, "u/s sideways input while sliding");
	private static final double SLAM_SPEED = Tune.def("slam.speed", 100, "u/s downward");
	private static final double SLAM_VOLUME = Tune.def("clash.slamVolume", 0.7, "clash slam landing sound volume (the game: 0.5, at V1 itself; by eye: 0.7 by the user's call; Minecraft stops at 1)");
	private static final double SLAM_PITCH = Tune.def("clash.slamPitch", 0.75, "clash slam landing sound pitch (from the game: SlamSound 0.75; Minecraft stops at 0.5)");
	private static final double SLAM_WINDUP = Tune.def("clash.slamWindup", 1, "1 = in clash mode (third person) the slam winds up first, V1 held up for 0.5 s (from the game: PlatformerMovement)");
	private static final double TERMINAL = Tune.def("physics.terminalVelocity", 100, "u/s");
	private static final double WALLJUMP_V = Tune.def("walljump.velocity", 24, "u/s away from the wall and the same up (2000 * 150 * 0.008 / 100)");
	private static final int WALLJUMPS = (int) Tune.def("walljump.count", 3, "wall jumps before touching ground");
	private static final double CLING_MAX = Tune.def("cling.max", 50, "clingFade grows 4/s to this; fall speed = 2 * clingFade");
	private static final double CAM_SLIDE_DROP = Tune.def("camera.slideDrop", 0.625, "u the camera drops while sliding");
	private static final double CAM_SLIDE_EYE = Tune.def("camera.slideEye", 0.42, "blocks above the feet the camera sits at most while sliding or crouched (user: half a block)");
	private static final double CAM_CRAWL_CLEAR = Tune.def("camera.crawlClear", 0.2, "blocks the camera keeps under a low ceiling while sliding or crouched at crawl height");
	private static final double SSJ_MAX_FRAMES = 4;

	private V1Movement() {}

	private static boolean wasActive;

	/** Whether V1 physics drove the player on the last check (no side effects; vanilla step sounds are muted then). */
	public static boolean isActive() {
		return wasActive;
	}

	/** Whether V1 physics drives the player this tick; vanilla handles water, ladders, vehicles, flying. */
	public static boolean active(ClientPlayerEntity p) {
		// (water too: V1 does not swim in ULTRAKILL, it sinks and jumps - water() below)
		boolean a = !p.getAbilities().flying && !p.hasVehicle() && !p.isSpectator() && !p.isFallFlying()
			&& !p.isClimbing() && !p.isInLava() && !V1.INSTANCE.dead;
		if (!a && wasActive) deactivate(p);
		wasActive = a;
		return a;
	}

	/** Leaving V1 physics (water, vehicle...): end the transient states so nothing is left half-way. */
	private static void deactivate(ClientPlayerEntity p) {
		V1 s = V1.INSTANCE;
		if (s.sliding) stopSlide(p, s);
		s.boost = false;
		s.boostLeft = 0;
		s.heavyFall = false;
		s.slamStorage = false;
		s.hookSlam = false;
		s.camDropTarget = 0;
		s.crouching = false;
		s.slowMode = false;
		s.standing = true;
		// Hand vanilla the current velocity in its units.
		p.setVelocity(s.vel.multiply(V1.SCALE / 20.0));
		s.vel = Vec3d.ZERO;
	}

	/**
	 * A tick under vanilla movement (water, ladders...): what V1's Update would still wind down. The camera shake
	 * (an explosion's, a landing's) wore off only in V1's own update, so in water it stayed on and shook the view; a
	 * rocket ride ends (V1 left it).
	 */
	public static void inactiveTick() {
		V1 s = V1.INSTANCE;
		if (s.shake > 0) s.shake = Math.max(0, s.shake - 0.05 * 3);
		if (s.ridingRocket >= 0) endRide(s);
		if (s.onGasoline) { s.onGasoline = false; com.ultracraft.client.UKSound.stop("v1.oilslide"); }
	}

	/** Called from the LivingEntity.travel mixin for the local player; replaces vanilla movement for this tick. */
	public static void travel(ClientPlayerEntity p) {
		V1 s = V1.INSTANCE;
		// (the dash key is sprint: sprinting under water put Minecraft's player into its low swimming pose)
		if (p.isSwimming()) p.setSwimming(false);
		// TimeController.TrueStop (parry freeze): nothing moves
		if (s.frozen()) { p.setVelocity(net.minecraft.util.math.Vec3d.ZERO); return; }
		// Minecraft's portal swirl and wobble (ClientPlayerEntity.updateNausea) run off the in-portal flag a move's block
		// collisions set; standing in a portal, V1 sets it every tick itself (the effect did not play)
		net.minecraft.util.math.BlockPos portalAt = netherPortalAt(p);
		if (portalAt != null) {
			p.setInNetherPortal(portalAt);
			if (Tune.on("debug.weapons") && p.age % 10 == 0) com.ultracraft.UltraCraft.LOG.info("[portal] in portal at {}: nausea {} -> {}", portalAt, p.prevNauseaIntensity, p.nauseaIntensity);
		}
		MinecraftClient client = MinecraftClient.getInstance();
		boolean canInput = client.currentScreen == null && !s.dead;
		s.accumulator += 0.05;
		int steps = 0;
		while (s.accumulator >= V1.DT - 1e-9 && steps < 8) {
			s.accumulator -= V1.DT;
			steps++;
			s.time += V1.DT;
			// Key edges are per tick; only the first sub-step of a tick sees them.
			boolean first = steps == 1;
			sense(p, s);
			update(p, s, first, canInput);
			fixedUpdate(p, s, first);
			integrate(p, s);
			rideCatch(p, s);
		}
		// Let Minecraft (fall distance, network, animations) see a consistent per-tick velocity.
		p.setVelocity(s.vel.multiply(V1.SCALE / 20.0));
		p.fallDistance = 0;
		p.setSprinting(false);
		s.camDrop = MathHelper.lerp(0.35, s.camDrop, s.camDropTarget);
		if (Tune.on("debug.telemetry")) {
			com.ultracraft.UltraCraft.LOG.info(String.format("V1 t=%.2f pos=(%.2f %.2f %.2f) v=(%.1f %.1f %.1f) |h|=%.1f u/s ground=%b wall=%b boost=%b slide=%b slam=%b fall=%b jump=%b stam=%.0f wj=%d",
				s.time, p.getX(), p.getY(), p.getZ(), s.vel.x, s.vel.y, s.vel.z, Math.hypot(s.vel.x, s.vel.z), s.onGround, s.onWall, s.boost, s.sliding, s.heavyFall, s.falling, s.jumping, s.boostCharge, s.currentWallJumps));
		}
	}

	static {
		Tune.def("water.buoyancy", 0.85, "water push up, x gravity (by eye: the user's pick; the game: Water.ApplyWaterForces 0.75)");
		Tune.def("water.limit", 0.2, "sinking speed the water eases toward, x gravity (from the game: 0.2 = 8 u/s; with gravity on top V1 settles near 12.5 u/s)");
		Tune.def("water.ease", 10, "how hard the water slows a faster fall, per second (from the game: 10)");
		Tune.def("water.depth", 0.5, "the water pushes V1 only while it is in up to this share of its height (by eye: with just its feet in at the surface it floated; the game's trigger counts any touch)");
		Tune.def("water.jumpMult", 0.75, "jump strength in water (by eye: the user's pick; the game has no change, 1)");
		Tune.def("lava.bounceForce", 500, "ULTRAKILL lava option: the lava HurtZone's bounceForce (Launch x 8 on 100 kg: 500 = 40 u/s)");
		Tune.def("lava.cooldown", 1, "ULTRAKILL lava option: seconds between bounces (HurtZone.hurtCooldown 1)");
		Tune.def("water.skipMinFall", 6, "sliding onto water skips off it when falling faster than this, u/s (a one-block drop ~12; the bank's 1/9-block drop ~4 sinks: user)");
		Tune.def("water.skipUp", 10, "the skip's push up, u/s (GroundCheck.BounceOnWater: 10)");
		Tune.def("water.skipDip", 0.15, "the skip comes this far under the water's surface, blocks: V1 touches the water (user)");
		Tune.def("water.speedMult", 0.8, "V1's walking speed and air control in water (by eye: the user's pick; the game has no change, 1)");
	}

	private static final double TELEMETRY = Tune.def("debug.telemetry", 0, "1 = log V1 position/velocity/state every tick (for playtests)");

	// ---------------------------------------------------------------- sensing (GroundCheck / WallCheck)

	private static void sense(ClientPlayerEntity p, V1 s) {
		Box box = p.getBoundingBox();
		// GroundCheck: a trigger reaching ~0.28 u (0.14 blocks) below the feet.
		Box feet = new Box(box.minX + 0.05, box.minY - 0.14, box.minZ + 0.05, box.maxX - 0.05, box.minY + 0.01, box.maxZ - 0.05);
		boolean touching = !p.getWorld().isSpaceEmpty(p, feet);
		s.onGround = touching && s.vel.y <= 0.5;
		s.slopeGround = p.isOnGround();
		if (s.onGround) s.sinceLastGrounded = 0; else s.sinceLastGrounded += V1.DT;
		// WallCheck: a sphere around the body touching a wall.
		double r = 0.06;
		Vec3d n = Vec3d.ZERO;
		boolean wall = false;
		if (!p.getWorld().isSpaceEmpty(p, new Box(box.maxX, box.minY + 0.3, box.minZ + 0.05, box.maxX + r, box.maxY - 0.3, box.maxZ - 0.05))) { wall = true; n = n.add(-1, 0, 0); }
		if (!p.getWorld().isSpaceEmpty(p, new Box(box.minX - r, box.minY + 0.3, box.minZ + 0.05, box.minX, box.maxY - 0.3, box.maxZ - 0.05))) { wall = true; n = n.add(1, 0, 0); }
		if (!p.getWorld().isSpaceEmpty(p, new Box(box.minX + 0.05, box.minY + 0.3, box.maxZ, box.maxX - 0.05, box.maxY - 0.3, box.maxZ + r))) { wall = true; n = n.add(0, 0, -1); }
		if (!p.getWorld().isSpaceEmpty(p, new Box(box.minX + 0.05, box.minY + 0.3, box.minZ - r, box.maxX - 0.05, box.maxY - 0.3, box.minZ))) { wall = true; n = n.add(0, 0, 1); }
		s.onWall = wall;
		s.wallNormal = wall && n.lengthSquared() > 0 ? n.normalize() : Vec3d.ZERO;
	}

	private static boolean groundWithin(ClientPlayerEntity p, double units) {
		Box box = p.getBoundingBox();
		double d = units * V1.SCALE;
		return !p.getWorld().isSpaceEmpty(p, new Box(box.minX + 0.1, box.minY - d, box.minZ + 0.1, box.maxX - 0.1, box.minY, box.maxZ - 0.1));
	}

	/**
	 * The camera's drop while sliding or crouched: ULTRAKILL's camera.slideDrop, and under a low ceiling (V1 at crawl
	 * height, PlayerCrawlMixin) far enough that the eye stays camera.crawlClear under it.
	 */
	private static double slideDrop(ClientPlayerEntity p) {
		double drop = CAM_SLIDE_DROP * V1.SCALE;
		Box box = p.getBoundingBox();
		double eye = p.getStandingEyeHeight();
		double clear = 3.5 * V1.SCALE;
		for (double h = 0.6; h <= 3.5 * V1.SCALE; h += 0.05) {
			if (!p.getWorld().isSpaceEmpty(p, new Box(box.minX + 0.05, box.minY, box.minZ + 0.05, box.maxX - 0.05, box.minY + h, box.maxZ - 0.05))) { clear = h - 0.05; break; }
		}
		// (the user: while sliding or crouched the camera sits just under one block, so a one-block gap never cuts
		// through it - ULTRAKILL's own slide drops it further than its 0.625 u; ours lerps in, so it has to be low already)
		// crawling (PlayerCrawlMixin) the eye itself is already down inside the half-block box: the camera stays on it
		if (p.getPose() == net.minecraft.entity.EntityPose.SWIMMING && !p.isTouchingWater()) return Math.max(0, eye - Tune.get("camera.slideEye"));
		drop = Math.max(drop, eye - Tune.get("camera.slideEye"));
		return Math.max(drop, eye - (clear - Tune.get("camera.crawlClear")));
	}

	private static boolean inNetherPortal(ClientPlayerEntity p) {
		return netherPortalAt(p) != null;
	}

	/** A Nether portal block V1's box is in, or null. */
	private static net.minecraft.util.math.BlockPos netherPortalAt(ClientPlayerEntity p) {
		Box b = p.getBoundingBox().contract(1e-5);
		for (net.minecraft.util.math.BlockPos pos : net.minecraft.util.math.BlockPos.iterate(MathHelper.floor(b.minX), MathHelper.floor(b.minY), MathHelper.floor(b.minZ),
			MathHelper.floor(b.maxX), MathHelper.floor(b.maxY), MathHelper.floor(b.maxZ)))
			if (p.getWorld().getBlockState(pos).isOf(net.minecraft.block.Blocks.NETHER_PORTAL)) return pos.toImmutable();
		return null;
	}

	private static boolean ceilingLow(ClientPlayerEntity p) {
		Box box = p.getBoundingBox();
		double standing = 3.5 * V1.SCALE;
		return !p.getWorld().isSpaceEmpty(p, new Box(box.minX + 0.05, box.minY, box.minZ + 0.05, box.maxX - 0.05, box.minY + standing, box.maxZ - 0.05));
	}

	// ---------------------------------------------------------------- Update()

	private static void update(ClientPlayerEntity p, V1 s, boolean first, boolean canInput) {
		MinecraftClient client = MinecraftClient.getInstance();
		// input direction, camera relative, horizontal
		double f = 0, r = 0;
		if (canInput) {
			if (UKKeys.FORWARD.held) f += 1;
			if (UKKeys.BACK.held) f -= 1;
			if (UKKeys.RIGHT.held) r += 1;
			if (UKKeys.LEFT.held) r -= 1;
		}
		float yaw = p.getYaw();
		Vec3d fwd = Vec3d.fromPolar(0, yaw);
		Vec3d right = Vec3d.fromPolar(0, yaw + 90);
		Vec3d in = fwd.multiply(f).add(right.multiply(r));
		s.inputDir = in.lengthSquared() > 1 ? in.normalize() : in;
		s.inputF = f;
		s.inputR = r;

		if (s.onGround) {
			s.fallTime = 0;
			s.clingFade = 0;
		} else {
			if (s.fallTime < 1) s.fallTime += V1.DT * 5; else s.falling = true;
			double vy = s.vel.y;
			if (vy < -2) s.fallSpeed = vy;
			if (vy < -TERMINAL) s.vel = new Vec3d(s.vel.x, -TERMINAL, s.vel.z);
		}
		if (first) woosh(s);
		if (first) gasoline(p, s);
		// (before the landing: landing on the enemy ends the slam)
		if (s.heavyFall) stomp(p, s, first && canInput);
		if (s.falling) checkLanding(p, s);
		// (the wind-up takes no input: PlatformerMovement.Update returns before it)
		if (canInput && !s.aboutToSlam) handleInputs(p, s, first);
		if (!s.onGround) cling(p, s);
		else V1Sounds.wallScrape(p, null);

		if (s.heavyFall) {
			if (!s.slamStorage) s.vel = new Vec3d(0, -SLAM_SPEED, 0);
			s.slamForce += V1.DT * 5;
		}
		if (s.aboutToSlam) {
			// PlatformerMovement.Update while aboutToSlam: velocity = up x 5 / (windUp x 10), the slam once windUp reaches 0.5
			s.slamWindup += V1.DT;
			s.vel = new Vec3d(0, 5 / (s.slamWindup * 10), 0);
			if (s.slamWindup >= 0.5) {
				s.aboutToSlam = false;
				slam(s);
			}
		}
		// GroundCheck: a jump within 0.15 s of landing on an enemy still bounces off it
		if (s.bounceChance > 0) {
			s.bounceChance = Math.max(0, s.bounceChance - V1.DT);
			if (first && canInput && UKKeys.JUMP.press && s.bounceChance > 0) { s.bounceChance = 0; bounce(p, s, s.bouncePos); }
		}
		if (s.stillHolding && first && UKKeys.SLIDE.release) s.stillHolding = false;
		handleSlideState(p, s, first);
		s.walking = s.inputDir.lengthSquared() > 1e-6 && !s.sliding && s.onGround;
		V1Sounds.walkTick(p, s.walking, s.onGround, s.vel.length(), V1.DT);
		if (s.hurtInvincibility > 0 && s.hp > 0) s.hurtInvincibility = Math.max(0, s.hurtInvincibility - V1.DT);
		if (s.hurtAlpha > 0) s.hurtAlpha -= V1.DT;
		if (s.safeExplosionLaunchCooldown > 0) s.safeExplosionLaunchCooldown = Math.max(0, s.safeExplosionLaunchCooldown - V1.DT);
		if (s.boostCharge != STAMINA_MAX && !s.sliding && !s.slowMode) {
			s.boostCharge = Math.min(STAMINA_MAX, s.boostCharge + STAMINA_REGEN * V1.DT);
		}
		if (s.slamCooldown > 0) s.slamCooldown = Math.max(0, s.slamCooldown - V1.DT);
		if (s.explosionLaunchResistance > 0) s.explosionLaunchResistance = Math.max(0, s.explosionLaunchResistance - V1.DT);
		// hard damage decay (rank-dependent speed-up comes with the style system)
		if (s.antiHpCooldown > 0) s.antiHpCooldown = Math.max(0, s.antiHpCooldown - V1.DT);
		else if (s.antiHp > 0) { s.cantInstaHeal = false; s.antiHp = Math.max(0, s.antiHp - 15 * V1.DT); }
		// GroundCheck timers (slam jump windows)
		if (s.superJumpChance > 0) {
			s.superJumpChance = Math.max(0, s.superJumpChance - V1.DT);
			if (s.superJumpChance == 0) {
				// GroundCheck: the slam's PhysicalShockwavePlayer (75 u/s, 12 u, force 5000 × slamForce × 2.25) if slide is still held
				if (com.ultracraft.weapon.Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] slam landed: stillHolding={} slamForce={}", s.stillHolding, s.slamForce);
				if (s.stillHolding) {
					com.ultracraft.client.weapon.ShockwaveFx.spawn(p.getPos(), 75f, 12f);
					s.shake = Math.max(s.shake, 0.75);
					com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.SHOCKWAVE, 0, Math.round((float) s.slamForce * 100), p.getPos(), new Vec3d(0, 1, 0)));
				}
				s.extraJumpChance = 0.306;
				s.stillHolding = false;
			}
		}
		if (s.extraJumpChance > 0) {
			s.extraJumpChance = Math.max(0, s.extraJumpChance - V1.DT);
			if (s.extraJumpChance <= 0 && s.superJumpChance <= 0) s.slamForce = 0;
		}
		if (s.jumpingTimer > 0) { s.jumpingTimer -= V1.DT; if (s.jumpingTimer <= 0) s.jumping = false; }
		if (s.jumpCooldownTimer > 0) { s.jumpCooldownTimer -= V1.DT; if (s.jumpCooldownTimer <= 0) s.jumpCooldown = false; }
		s.lastJump += V1.DT;
		// the short hop (mod option): a plain jump whose key comes up within jump.hopWindow is cut to jump.hopKeep
		if (s.hopCuttable) {
			if (s.lastJump > Tune.get("jump.hopWindow") || s.vel.y <= 0) s.hopCuttable = false;
			else if (!UKKeys.JUMP.held && s.lastJump >= Tune.get("jump.hopMin") && com.ultracraft.UkConfig.shortHop()) {
				s.vel = new Vec3d(s.vel.x, Math.min(s.vel.y, Tune.get("jump.hopKeep")), s.vel.z);
				s.hopCuttable = false;
			}
		}
		// camera tilt from strafe (CameraController, cameraTilt on): -movementHor degrees, x5 while dashing
		double target = -r * (s.boost ? 5 : 1);
		double rate = (s.boost ? 100 : 25) * V1.DT;
		s.tiltZ = MathHelper.stepTowards((float) s.tiltZ, (float) target, (float) (rate * (Math.abs(s.tiltZ - target) + 0.01)));
		if (s.shake > 0) s.shake -= V1.DT * 3;
	}

	private static void cling(ClientPlayerEntity p, V1 s) {
		if (!s.onWall || s.wallNormal == Vec3d.ZERO) { V1Sounds.wallScrape(p, null); return; }
		boolean pushing = s.inputDir.dotProduct(s.wallNormal) < -0.3;
		double vy = s.vel.y;
		if (!s.sliding && pushing && !s.heavyFall && vy < -1) {
			double vx = MathHelper.clamp(s.vel.x, -1, 1), vz = MathHelper.clamp(s.vel.z, -1, 1);
			s.vel = new Vec3d(vx, -2 * s.clingFade, vz);
			s.clingFade = Math.min(CLING_MAX, s.clingFade + V1.DT * 4);
			// Cling: CreateWallScrape - the wall's scrape and its sounds while V1 slides down it
			V1Sounds.wallScrape(p, s.wallNormal);
		} else V1Sounds.wallScrape(p, null);
	}

	private static void handleInputs(ClientPlayerEntity p, V1 s, boolean first) {
		if (!first) return;
		boolean canJumpNormal = !s.falling;
		tickRocketRide(p, s);
		if (s.ridingRocket >= 0) return;
		if (UKKeys.JUMP.press && !s.jumpCooldown && canJumpNormal) jump(p, s);
		// (while the whiplash pulls V1 the key only stores a slam: no slide, no camera drop - the user's call)
		if (UKKeys.SLIDE.press && !com.ultracraft.client.weapon.WhiplashClient.INSTANCE.blocksCrouch()) {
			boolean groundNear = groundWithin(p, 2);
			if ((s.onGround || s.sinceLastGrounded < 0.06 || s.lastJump < 0.06 || groundNear) && (!s.slowMode || s.crouching) && !s.sliding) {
				startSlide(p, s);
			}
		}
		if (!s.slowMode && UKKeys.DASH.press) tryDash(p, s);
		if (!s.onGround) {
			if (UKKeys.JUMP.press && s.currentWallJumps < WALLJUMPS && !s.jumpCooldown && s.onWall && s.wallNormal != Vec3d.ZERO) wallJump(p, s);
			if (UKKeys.SLIDE.press) tryStartSlam(p, s);
		}
	}

	private static void tryDash(ClientPlayerEntity p, V1 s) {
		if (s.boostCharge < DASH_COST) {
			V1Sounds.staminaFail(p);
			return;
		}
		s.dashedFromGround = s.onGround;
		if (s.sliding) stopSlide(p, s);
		s.boostLeft = 100;
		s.dashStorage = 1;
		s.boost = true;
		Vec3d fwd = Vec3d.fromPolar(0, p.getYaw());
		s.dodgeDirection = s.inputDir.lengthSquared() < 1e-6 ? fwd : s.inputDir;
		s.boostCharge -= DASH_COST;
		s.dodgeDirectionKind = dodgeKind(s.dodgeDirection, fwd);
		V1Sounds.dodge(p);
		SpeedFx.dash(p, s.dodgeDirection);
		if (s.heavyFall) { s.fallSpeed = 0; s.heavyFall = false; }
	}

	private static int dodgeKind(Vec3d dir, Vec3d fwd) {
		double d = dir.dotProduct(fwd);
		if (d > 0.9) return 0;
		if (d < -0.9) return 1;
		return 2;
	}

	private static void tryStartSlam(ClientPlayerEntity p, V1 s) {
		if (s.dashedFromGround && s.boost && !s.sliding) {
			s.dashedFromGround = false;
			s.windState = 0.5;
			s.boost = false;
		}
		if (com.ultracraft.weapon.Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] slam try: ground3 {} fallTime {} cooldown {} heavyFall {} pulled {}",
			groundWithin(p, 3), s.fallTime, s.slamCooldown, s.heavyFall, com.ultracraft.client.weapon.WhiplashClient.INSTANCE.beingPulled());
		if (!groundWithin(p, 3) && s.fallTime > 0.5 && s.slamCooldown == 0 && !s.heavyFall) {
			if (s.boost) { s.boostLeft = 0; s.boost = false; }
			if (s.sliding) stopSlide(p, s);
			if (!MinecraftClient.getInstance().options.getPerspective().isFirstPerson() && Tune.on("clash.slamWindup")) {
				// clash mode (PlatformerMovement): a 0.5 s wind-up first (SlamStart, and slamReadySound: QuickWhoosh, 0.5, pitch 2)
				s.aboutToSlam = true;
				s.clashSlam = true;
				s.slamWindup = 0;
				com.ultracraft.client.UKSound.at(p.getPos(), "uk.movement.quickwhoosh", 0.5f, 2f);
				return;
			}
			s.clashSlam = false;
			slam(s);
		}
	}

	/** NewMovement / PlatformerMovement.Slam: straight down at 100 u/s. */
	private static void slam(V1 s) {
		// TryStartSlam while the hook pulls V1: slam storage (the pull goes on, the slam charges, it lands as a slam).
		// The game's straight-down kick lasts until the hook's next 50 Hz step; the pull here is set once a 20 Hz tick,
		// so the kick would drop V1 up to 2.5 blocks first (to the ground, low down): a stored slam leaves V1's speed be.
		boolean pulled = com.ultracraft.client.weapon.WhiplashClient.INSTANCE.beingPulled();
		s.falling = true;
		s.fallSpeed = -SLAM_SPEED;
		if (!pulled) s.vel = new Vec3d(0, -SLAM_SPEED, 0);
		s.stillHolding = true;
		s.heavyFall = true;
		s.slamForce = 1;
		s.slamHits.clear();
		if (pulled) {
			s.slamStorage = true;
			s.hookSlam = true;
		}
		if (com.ultracraft.weapon.Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] slam start");
	}

	/**
	 * GroundCheck.OnTriggerEnter while heavyFall: an enemy under the feet (no environment between) takes the slam, once
	 * per slam (server: 2 damage); Jump held bounces off it at once, otherwise a jump in the next 0.15 s still does.
	 */
	private static void stomp(ClientPlayerEntity p, V1 s, boolean input) {
		// the crush zone: V1's footprint padded on every side, from a little above the feet to STOMP_DEPTH under them;
		// every mob in it is crushed
		Box b = p.getBoundingBox();
		double pad = Tune.get("slam.stompPad"), depth = Tune.get("slam.stompDepth");
		Box feet = new Box(b.minX - pad, b.minY - depth, b.minZ - pad, b.maxX + pad, b.minY + 0.2, b.maxZ + pad);
		boolean bounceNow = false;
		Vec3d top = p.getPos().add(0, 1 * Damage.SCALE_BLOCKS, 0);
		for (net.minecraft.entity.Entity e : p.getWorld().getOtherEntities(p, feet, en -> en instanceof net.minecraft.entity.LivingEntity le && le.isAlive() && !(en instanceof net.minecraft.entity.player.PlayerEntity))) {
			if (s.slamHits.contains(e.getId())) continue;
			Vec3d c = e.getBoundingBox().getCenter();
			if (p.getWorld().raycast(new net.minecraft.world.RaycastContext(top, c, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, p)).getType() != net.minecraft.util.hit.HitResult.Type.MISS) continue;
			s.slamHits.add(e.getId());
			com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.STOMP, 0, e.getId(), p.getEyePos(), new Vec3d(0, 1, 0)));
			// one the crush kills stops being solid now, not when the server's death arrives a tick or two later
			if (((net.minecraft.entity.LivingEntity) e).getHealth() <= Tune.get("slam.stompDamage") * Damage.hpPerDamage())
				com.ultracraft.weapon.WeaponServer.clientCrushed.put(e.getId(), System.nanoTime() + 1_500_000_000L);
			if (com.ultracraft.weapon.Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] slam crushed {}", e.getId());
			if (input && UKKeys.JUMP.held) bounceNow = true;
			else if (s.bounceChance <= 0) { s.bouncePos = p.getPos(); s.bounceChance = 0.15; }
		}
		if (bounceNow) bounce(p, s, p.getPos());
	}

	private static int wooshTier = -1;

	/**
	 * NewMovement.Update: the WallCheck's audWoosh (Whoosh, a 2D loop at volume 0) while falling - under 20 u/s down it is
	 * silent, faster its pitch is the fall speed / 120 and its volume / 80; windState above 0 (a rocket jump, a dash jump)
	 * makes it full pitch and min(windState, 1) loud; not falling, silent. Riding a rocket V1's Rigidbody stands still
	 * (Grenade zeroes it), so only windState sounds there. Below Minecraft's 0.5 pitch it plays from the octave- or
	 * two-octaves-down copy.
	 */
	private static void woosh(V1 s) {
		float pitch = 0, vol = 0;
		if (s.falling && !s.onGround && !s.dead) {
			double vy = s.ridingRocket >= 0 ? 0 : s.vel.y;
			if (vy < -20) {
				pitch = (float) (Math.abs(s.fallSpeed) / 120);
				vol = (float) (Math.abs(s.fallSpeed) / 80);
			}
			if (s.windState > 0) {
				vol = (float) Math.min(s.windState, 1);
				pitch = 1;
			}
		}
		vol = Math.min(1, vol);
		int tier = vol <= 0.001f || pitch <= 0.01f ? -1 : pitch >= 0.75f ? 0 : pitch >= 0.35f ? 1 : 2;
		if (tier != wooshTier) {
			for (int t = 0; t < 3; t++) com.ultracraft.client.UKSound.stop("v1.woosh." + t);
			wooshTier = tier;
		}
		if (tier < 0) return;
		float mul = tier == 0 ? 1 : tier == 1 ? 2 : 4;
		com.ultracraft.client.UKSound.loopAs("v1.woosh." + tier, "uk.movement.whoosh" + (tier == 0 ? "" : tier == 1 ? "_d2" : "_d4"), vol, Math.max(0.5f, Math.min(2f, pitch * mul)));
	}

	/**
	 * NewMovement.CheckForGasoline (Update, on the ground): Firestarter gasoline on a floor round V1's feet takes the ground
	 * friction to 0 (it slides on in the air movement) and plays the OilSlide loop, volume 0-0.85 and pitch 1.75-2.75 with
	 * the speed up to 35 u/s (Minecraft's pitch stops at 2: its octave-up copy at half). Off the ground the loop stops and
	 * the last check holds.
	 */
	private static void gasoline(ClientPlayerEntity p, V1 s) {
		if (s.onGround && !s.dead) s.onGasoline = com.ultracraft.client.weapon.GasolineClient.onGasoline(p.getPos());
		// (the loop is kept through short gaps - a step off the ground mid-slide, a voxel edge - instead of stopping and
		// starting again, which made it come and go; oil.grace s, by eye)
		if (s.onGasoline && s.onGround && !s.dead) oilGrace = (float) Tune.get("oil.grace");
		else oilGrace -= 0.05f;
		if (oilGrace <= 0) {
			com.ultracraft.client.UKSound.stop("v1.oilslide");
			return;
		}
		float n = (float) (Math.min(35, s.vel.length()) / 35);
		com.ultracraft.client.UKSound.loopAs("v1.oilslide", "uk.movement.oilslide_x2", 0.85f * n, MathHelper.lerp(n, 1.75f, 2.75f) / 2);
	}

	private static float oilGrace;
	static {
		Tune.def("oil.grace", 0.25, "s the oil slide sound keeps going after V1 last stood on gasoline (by eye: it came and went)");
	}


	/** GroundCheck.Bounce: off the enemy with a jump, the enemy-step resets (wall jumps, rocket rides, cling), slam cooldown 0.25. */
	private static void bounce(ClientPlayerEntity p, V1 s, Vec3d at) {
		s.heavyFall = false;
		p.setPosition(at.x, at.y, at.z);
		jump(p, s);
		s.currentWallJumps = 0;
		s.rocketJumps = 0;
		s.hammerJumps = 0;
		s.clingFade = 0;
		s.rocketRides = 0;
		s.slamCooldown = 0.25;
		if (!s.hasImpacted) landingImpact(p, s);
	}

	private static void checkLanding(ClientPlayerEntity p, V1 s) {
		if (!s.onGround) return;
		if (s.heavyFall && s.clashSlam) {
			// PlatformerMovement.SlamEnd: no landing sound of V1's own, a camera shake of 0.5 and the impactDust shockwave
			// (PhysicalShockwavePlayer: 75 u/s, 12 u, force 5000) every time - no super jump window, no slide-held rule. The
			// shockwave plays its soundEffect, SlamSound: Impacts_PROCESSED_001 at volume 0.5, pitch 0.75
			s.clashSlam = false;
			com.ultracraft.client.UKSound.play("uk.impacts.impacts_processed_001", (float) Tune.get("clash.slamVolume"), (float) Tune.get("clash.slamPitch"));
			s.shake = Math.max(s.shake, 0.5);
			com.ultracraft.client.weapon.ShockwaveFx.spawn(p.getPos(), 75f, 12f);
			com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.SHOCKWAVE, 1, 100, p.getPos(), new Vec3d(0, 1, 0)));
			s.landBob = 1;
			s.slamForce = 0;
			s.stillHolding = false;
			if (!s.jumpCooldown) s.falling = false;
			s.fallSpeed = 0;
			s.slamStorage = false;
			s.heavyFall = false;
			return;
		}
		if (s.fallSpeed > -50) {
			V1Sounds.land(p, 0.5 + s.fallSpeed * -0.01);
		} else {
			s.hasImpacted = true;
			landingImpact(p, s);
		}
		s.landBob = Math.min(1, Math.abs(s.fallSpeed / 100));
		if (s.heavyFall) {
			// GroundCheck.OnTriggerEnter while heavyFall: slam landed -> super jump window; shockwave 0.1 s later if still holding
			s.superJumpChance = 0.1;
		}
		if (!s.jumpCooldown) s.falling = false;
		s.fallSpeed = 0;
		s.slamStorage = false;
		s.heavyFall = false;
	}

	/**
	 * Grenade.FixedUpdate: a falling player within 2.25 u of a rocket's nose starts riding it; jump leaves it (a jump),
	 * slide leaves it. Grenade.LateUpdate: the Move input turns the rocket (sent to the server, which flies it) and V1
	 * sits at the rocket + its up x 2 + its forward (NewMovement's transform: the capsule centre).
	 */
	private static void tickRocketRide(ClientPlayerEntity p, V1 s) {
		var world = p.getWorld();
		if (s.ridingRocket >= 0) {
			var e = world.getEntityById(s.ridingRocket);
			if (!(e instanceof com.ultracraft.entity.UKProjectileEntity r) || !r.isAlive()) { endRide(s); return; }
			if (UKKeys.JUMP.press) { endRide(s); jump(p, s); return; }
			if (UKKeys.SLIDE.press) { endRide(s); return; }
			com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.ROCKET, 6, r.getId(), Vec3d.ZERO, new Vec3d(s.inputR, s.inputF, 0)));
			double yaw = Math.toRadians(r.getYaw()), pitch = Math.toRadians(r.getPitch());
			Vec3d fwd = new Vec3d(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
			Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
			Vec3d up = right.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : right.normalize().crossProduct(fwd);
			Vec3d centre = r.getPos().add(0, r.getBoundingBox().getYLength() * 0.5, 0);
			Vec3d top = centre.add(up.multiply(2 * V1.SCALE)).add(fwd.multiply(V1.SCALE)).subtract(0, 0.9, 0);
			// (the first tick on it: V1 lands there at once - its in-between-frames place still ran from where it jumped,
			// and the rocket, drawn from that place, flashed off to the side for a moment)
			boolean first = s.rideOffset == null;
			p.setPosition(top.x, top.y, top.z);
			if (first) {
				p.prevX = p.lastRenderX = top.x;
				p.prevY = p.lastRenderY = top.y;
				p.prevZ = p.lastRenderZ = top.z;
			}
			s.rideOffset = top.subtract(r.getPos());
			// Grenade.LateUpdate: CameraController.CameraShake(0.1) every frame while riding
			s.shake = Math.max(s.shake, 0.1);
			// (a frozen rocket stands still: its velocity is kept for when it goes again)
			s.vel = r.isFrozen() ? Vec3d.ZERO : r.getVelocity().multiply(20 / Damage.SCALE_BLOCKS);
			s.falling = false;
			return;
		}
	}

	/**
	 * Grenade.FixedUpdate, every physics step (V1's 0.008 s, as the game's 125 Hz): V1's feet (the GroundCheck) within
	 * 2.25 u of the rocket's position + its forward (1 u along its facing), falling, off the ground, starts the ride.
	 * Checked once a Minecraft tick a fast fall or slam passed through that ball between checks: hit or miss.
	 */
	private static void rideCatch(ClientPlayerEntity p, V1 s) {
		if (s.ridingRocket >= 0 || s.onGround || s.vel.y > 0 || s.dead) return;
		var world = p.getWorld();
		Box near = p.getBoundingBox().expand(2.25 * Damage.SCALE_BLOCKS + 1);
		for (var e : world.getEntitiesByClass(com.ultracraft.entity.UKProjectileEntity.class, near, r -> r.kind() == com.ultracraft.entity.ProjectileKind.ROCKET && r.isAlive())) {
			double yaw = Math.toRadians(e.getYaw()), pitch = Math.toRadians(e.getPitch());
			Vec3d fwd = new Vec3d(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
			Vec3d nose = e.getPos().add(fwd.multiply(Damage.SCALE_BLOCKS));
			if (com.ultracraft.weapon.Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] ride candidate {} dist={} py={} ry={}", e.getId(), p.getPos().distanceTo(nose), p.getY(), e.getY());
			if (p.getPos().distanceTo(nose) < 2.25 * Damage.SCALE_BLOCKS) {
				s.ridingRocket = e.getId();
				s.rocketRides++;
				s.heavyFall = false;
				// (dir.x carries rocketRides: past 3 a fresh rocket starts with more downpull)
				com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.ROCKET, 4, e.getId(), p.getPos(), new Vec3d(s.rocketRides, 1, 0)));
				// Grenade.PlayerRideStart: Instantiate(playerRideSound) - RocketRideStart (AssaultRifle_PickUp_004, 0.75, pitch 0.5)
				com.ultracraft.client.UKSound.play("uk.weapons.mechanism_-_assaultrifle_pickup_004", 0.75f, 0.5f);
				return;
			}
		}
	}

	private static void endRide(V1 s) {
		if (s.ridingRocket >= 0) com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.ROCKET, 5, s.ridingRocket, Vec3d.ZERO, Vec3d.ZERO));
		s.ridingRocket = -1;
		s.rideOffset = null;
	}

	private static void landingImpact(ClientPlayerEntity p, V1 s) {
		V1Sounds.impact(p);
		s.shake = Math.max(s.shake, 0.75);
	}

	// ---------------------------------------------------------------- slide

	private static void handleSlideState(ClientPlayerEntity p, V1 s, boolean first) {
		if (s.sliding) {
			if ((first && UKKeys.SLIDE.release) || (s.slowMode && !s.crouching)) stopSlide(p, s);
			s.standing = false;
			s.slideLength += V1.DT;
			s.camDropTarget = slideDrop(p);
			if (s.slideSafety > 0) s.slideSafety -= V1.DT * 5;
			if (s.onGround || s.onWall) s.shake = Math.max(s.shake, 0.1);
			if (first && s.sliding) V1Sounds.slideTick(p);
			return;
		}
		if (s.crouching || !s.standing) {
			if (ceilingLow(p)) {
				s.crouching = true;
				s.slowMode = true;
				s.camDropTarget = slideDrop(p);
				return;
			}
			s.crouching = false;
			s.slowMode = false;
			s.camDropTarget = 0;
			s.standing = true;
		} else if (s.camDropTarget != 0) {
			s.camDropTarget = 0;
		}
	}

	private static void startSlide(ClientPlayerEntity p, V1 s) {
		s.slideSafety = 1;
		s.sliding = true;
		s.boost = true;
		s.slideTimestamp = s.time;
		Vec3d fwd = Vec3d.fromPolar(0, p.getYaw());
		s.dodgeDirection = s.inputDir.lengthSquared() < 1e-6 ? fwd : s.inputDir;
		s.dodgeDirectionKind = dodgeKind(s.dodgeDirection, fwd);
		s.crouching = true;
		V1Sounds.slideStart(p);
	}

	public static void stopSlide(ClientPlayerEntity p, V1 s) {
		V1Sounds.slideStop(p);
		s.sliding = false;
		s.slideEnding = true;
		s.slideLength = 0;
		s.framesSinceSlide = 0;
		s.velocityAfterSlide = s.dodgeDirection.normalize().multiply(Math.max(SLIDE_SPEED, s.preDashSpeed.length()));
	}

	// ---------------------------------------------------------------- FixedUpdate()

	private static void fixedUpdate(ClientPlayerEntity p, V1 s, boolean first) {
		if (s.sliding) {
			if (s.slideSafety <= 0) {
				double hs = Math.hypot(s.vel.x, s.vel.z);
				if (hs < 10) {
					s.slideSafety = Math.max(-0.1, s.slideSafety - V1.DT);
					if (s.slideSafety <= -0.1) stopSlide(p, s);
				} else s.slideSafety = 0;
			}
			if (s.onWall && s.vel.y < 0) s.vel = s.vel.add(0, GRAVITY * 0.4 * V1.DT, 0);
		}
		if (!s.sliding) {
			s.framesSinceSlide++;
			if (s.heavyFall) {
				s.preSlideDelay = 0.2;
				s.preSlideSpeed = s.slamForce;
			} else if (!s.boost && s.falling && s.vel.length() / 24 > s.preSlideSpeed) {
				s.preSlideSpeed = s.vel.length() / 24;
				s.preSlideDelay = 0.2;
			} else {
				s.preSlideDelay = Math.max(0, s.preSlideDelay - V1.DT);
				if (s.preSlideDelay <= 0) {
					s.preSlideDelay = 0.2;
					s.preSlideSpeed = s.vel.length() / 24;
				}
			}
		}
		s.windState -= V1.DT;
		if (s.boost) dodge(p, s); else move(p, s);
		if (!s.boost || s.boostLeft <= 0) s.preDashSpeed = s.vel;
		// (only while V1 is in the water up to water.depth of its height: with just its feet in at the surface it floated
		// there, the water pushing it up nearly as hard as gravity pulls - out of a jump it should drop back in)
		if (p.isTouchingWater() && s.ridingRocket < 0 && waterAt(p, Tune.get("water.depth"))) water(s);
		skipOnWater(p, s);
	}

	private static long lastLavaNanos;

	/**
	 * The ULTRAKILL lava option: the level lava's HurtZone (bounceForce 500, hurtCooldown 1) - on touching it V1 is
	 * Launched away from its nearest point, (V1 - closest).normalized × 500 × 8 on 100 kg = 40 u/s, at most once a second,
	 * and the server takes the 20 damage (LavaServer). Checked on the box V1 sweeps this tick, so it is thrown out before
	 * Minecraft's lava swallows it.
	 */
	public static void lavaBounce(ClientPlayerEntity p) {
		// (a dying V1's body still lies in the lava: it neither bounces nor burns again)
		if (!com.ultracraft.UkConfig.lavaBounce() || p.isSpectator() || p.getAbilities().flying || p.hasVehicle() || V1.INSTANCE.dead || !p.isAlive()) return;
		long now = System.nanoTime();
		if (now - lastLavaNanos < (long) (Tune.get("lava.cooldown") * 1e9)) return;
		V1 s = V1.INSTANCE;
		var world = p.getWorld();
		Box b = p.getBoundingBox();
		Box swept = b.stretch(wasActive ? s.vel.multiply(V1.SCALE / 20.0) : p.getVelocity());
		Vec3d centre = b.getCenter(), closest = null;
		double best = Double.MAX_VALUE;
		for (net.minecraft.util.math.BlockPos bp : net.minecraft.util.math.BlockPos.iterate((int) Math.floor(swept.minX), (int) Math.floor(swept.minY), (int) Math.floor(swept.minZ),
			(int) Math.floor(swept.maxX), (int) Math.floor(swept.maxY), (int) Math.floor(swept.maxZ))) {
			var fs = world.getFluidState(bp);
			if (!fs.isIn(net.minecraft.registry.tag.FluidTags.LAVA)) continue;
			Box lava = new Box(bp.getX(), bp.getY(), bp.getZ(), bp.getX() + 1, bp.getY() + fs.getHeight(world, bp), bp.getZ() + 1);
			if (!lava.intersects(swept)) continue;
			Vec3d c = new Vec3d(MathHelper.clamp(centre.x, lava.minX, lava.maxX), MathHelper.clamp(centre.y, lava.minY, lava.maxY), MathHelper.clamp(centre.z, lava.minZ, lava.maxZ));
			double d = c.squaredDistanceTo(centre);
			if (d < best) { best = d; closest = c; }
		}
		if (closest == null) return;
		lastLavaNanos = now;
		if (com.ultracraft.weapon.Damage.debug) com.ultracraft.UltraCraft.LOG.info("[lava] touch: burn={} perspective={} creative={}", V1Burn.wants(p),
			MinecraftClient.getInstance().options.getPerspective(), p.isCreative());
		// clash mode (PlatformerMovement): a Burn hurt zone does not bounce V1, it burns it to ash
		if (V1Burn.wants(p)) {
			V1Burn.start(p);
			return;
		}
		Vec3d away = centre.subtract(closest);
		Vec3d dir = away.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : away.normalize();
		launch(s, dir.multiply(Tune.get("lava.bounceForce")), 8);
		p.setVelocity(s.vel.multiply(V1.SCALE / 20.0));
		com.ultracraft.net.Net.toServer(new com.ultracraft.net.FirePayload(com.ultracraft.net.FirePayload.LAVA, 0, 0, p.getEyePos(), dir));
	}

	/**
	 * GroundCheck.BounceOnWater: sliding and falling, out of the water, meeting water sets vy to 0 and adds 10 u/s up,
	 * with a small splash at 0.65; the slide keeps its speed and steering, so V1 skips on until the slide ends and it
	 * sinks. The game's ray reaches as far down as the fall speed (|vy| u), so a long fall bounced in mid-air blocks
	 * above the water: here the skip comes as the feet reach the surface (user). It only starts past
	 * water.skipMinFall u/s down, so a slide off the bank, level with the water, sinks (user).
	 */
	private static void skipOnWater(ClientPlayerEntity p, V1 s) {
		if (!s.sliding || s.vel.y >= -Tune.get("water.skipMinFall") || s.ridingRocket >= 0) return;
		var world = p.getWorld();
		// the skip line: water.skipDip under the surface, so V1 touches the water, almost going under (user)
		double dip = Tune.get("water.skipDip");
		// (the feet's path over this tick)
		double y = p.getY(), reach = y + s.vel.y * V1.SCALE / 20.0;
		net.minecraft.util.math.BlockPos.Mutable bp = new net.minecraft.util.math.BlockPos.Mutable();
		for (int by = (int) Math.floor(y + dip); by >= (int) Math.floor(reach - dip); by--) {
			bp.set(p.getX(), by, p.getZ());
			var fs = world.getFluidState(bp);
			if (by <= Math.floor(y) && !world.getBlockState(bp).getCollisionShape(world, bp).isEmpty()) return; // ground first
			if (!fs.isIn(net.minecraft.registry.tag.FluidTags.WATER)) continue;
			double line = by + fs.getHeight(world, bp) - dip;
			if (line > y + 0.01) return; // already under it: in the water
			if (line < reach) return; // not reached this tick
			// down to the line this tick, then the bounce
			p.setPosition(p.getX(), line, p.getZ());
			s.vel = new Vec3d(s.vel.x, Tune.get("water.skipUp"), s.vel.z);
			com.ultracraft.client.UKSound.play("uk.environment.watersmallsplash", 0.65f, 1f);
			return;
		}
	}

	/**
	 * Water.ApplyWaterForces, every physics step a body with gravity touches the water: sinking faster than the water's
	 * limit (gravity x 0.2 = -8 u/s) its fall is eased toward it at 10 x |vy + 8.5| u/s a second; otherwise the water
	 * pushes up with 0.75 of gravity (+30 u/s^2), so V1 sinks slowly, about -10 u/s^2 to -8 u/s. It does not swim: a jump
	 * off the bottom carries it up only so far, a slide or dash jump further.
	 */
	/** Whether the water reaches `share` of the way up V1's body. */
	private static boolean waterAt(ClientPlayerEntity p, double share) {
		Box b = p.getBoundingBox();
		double y = b.minY + (b.maxY - b.minY) * share;
		net.minecraft.util.math.BlockPos bp = net.minecraft.util.math.BlockPos.ofFloored(p.getX(), y, p.getZ());
		var fs = p.getWorld().getFluidState(bp);
		return fs.isIn(net.minecraft.registry.tag.FluidTags.WATER) && bp.getY() + fs.getHeight(p.getWorld(), bp) >= y;
	}

	private static void water(V1 s) {
		double limit = -GRAVITY * Tune.get("water.limit");
		double vy = s.vel.y;
		if (vy < limit) {
			double step = V1.DT * Tune.get("water.ease") * Math.abs(vy - limit + 0.5);
			vy = vy + Math.min(step, limit - vy);
		} else vy += GRAVITY * Tune.get("water.buoyancy") * V1.DT;

		s.vel = new Vec3d(s.vel.x, vy, s.vel.z);
	}

	private static void move(ClientPlayerEntity p, V1 s) {
		s.slideEnding = false;
		if (s.onGround && !s.jumping) {
			s.currentWallJumps = 0;
			s.rocketJumps = 0;
			s.hammerJumps = 0;
			s.rocketRides = 0;
		}
		boolean noInput = s.inputDir.lengthSquared() < 1e-6;
		// (on gasoline the friction is 0: NewMovement.Move takes the air branch on the ground as well)
		if (s.onGround && !s.onGasoline && !s.jumping) {
			double vy = s.vel.y;
			boolean gravityOff = s.slopeGround && noInput;
			if (gravityOff) vy = 0;
			double speed = (s.slowMode ? SLOW : WALK) * (p.isTouchingWater() ? Tune.get("water.speedMult") : 1);
			Vec3d target = s.inputDir.multiply(speed).add(0, vy, 0);
			s.vel = lerp(s.vel, target, GROUND_LERP);
			if (!gravityOff) s.vel = s.vel.add(0, -GRAVITY * V1.DT, 0);
			return;
		}
		// air
		s.vel = s.vel.add(0, -GRAVITY * V1.DT, 0);
		double inWater = p.isTouchingWater() ? Tune.get("water.speedMult") : 1;
		Vec3d wish = s.inputDir.multiply((s.slowMode ? SLOW : WALK) * inWater);
		Vec3d hv = new Vec3d(s.vel.x, 0, s.vel.z);
		double wind = s.windState > 0 ? 1 : (s.windState > -0.25 ? MathHelper.getLerpProgress(s.windState, 0, -0.25) : 0);
		double accelMult = MathHelper.lerp(wind, 1, 2);
		Vec3d airDirection = Vec3d.ZERO;
		if (!noInput) {
			double accel = AIR_ACCEL * MathHelper.lerp(wind, 1, 4);
			Vec3d right = Vec3d.fromPolar(0, p.getYaw() + 90);
			Vec3d fwd = Vec3d.fromPolar(0, p.getYaw());
			Vec3d sideIn = project(s.inputDir, right);
			double sideCur = hv.dotProduct(sideIn.lengthSquared() > 0 ? sideIn.normalize() : Vec3d.ZERO);
			double sideAdd = accel;
			if (sideCur + sideAdd > AIR_CAP * inWater) sideAdd = Math.max(AIR_CAP * inWater - sideCur, 0);
			Vec3d fwdIn = project(s.inputDir, fwd);
			double fwdCur = hv.dotProduct(fwdIn.lengthSquared() > 0 ? fwdIn.normalize() : Vec3d.ZERO);
			double fwdAdd = accel;
			if (fwdCur + fwdAdd > AIR_CAP * inWater) fwdAdd = Math.max(AIR_CAP * inWater - fwdCur, 0);
			Vec3d add = sideIn.multiply(sideAdd).add(fwdIn.multiply(fwdAdd));
			Vec3d velDir = hv.length() > 0.001 ? hv.normalize() : s.inputDir;
			Vec3d perp = add.subtract(project(add, velDir));
			Vec3d along = project(add, velDir);
			double againstMult = MathHelper.lerp(1 - wind, 0.1, 0.4);
			boolean sameWay = along.dotProduct(velDir) > 0;
			boolean fast = hv.length() > AIR_CAP * inWater;
			airDirection = airDirection.add(perp).add(along.multiply(sameWay && fast ? againstMult : 1));
			double f = 1 - airDirection.length() / (accel + 0.0001);
			f = f * f * f;
			double maxLength = AIR_ACCEL * accelMult * f;
			Vec3d redirect = s.inputDir.multiply(hv.length()).subtract(hv);
			Vec3d redirectAlong = project(redirect, velDir);
			redirect = redirect.subtract(redirectAlong);
			if (redirectAlong.dotProduct(hv) < 0) redirect = redirect.add(redirectAlong.multiply(MathHelper.lerp(1 - wind, 0.4, 1)));
			else redirect = redirect.add(redirectAlong);
			if (redirect.length() > maxLength && maxLength > 0) redirect = redirect.normalize().multiply(maxLength);
			else if (maxLength <= 0) redirect = Vec3d.ZERO;
			airDirection = airDirection.add(redirect);
		} else if (com.ultracraft.UkConfig.airBrake() && hv.length() > 1e-4 && hv.length() <= WALK * Tune.get("move.airBrakeMax")) {
			// the air brake (mod option): no key held, the sideways drift dies away so V1 drops onto the spot below
			Vec3d braked = hv.multiply(Math.exp(-Tune.get("move.airBrakeRate") * V1.DT));
			s.vel = new Vec3d(braked.x, s.vel.y, braked.z);
		}
		s.vel = s.vel.add(airDirection);
	}

	private static void dodge(ClientPlayerEntity p, V1 s) {
		if (s.sliding) {
			double num = 1;
			if (s.preSlideSpeed > 1) {
				if (s.preSlideSpeed > 3) s.preSlideSpeed = 3;
				num = s.preSlideSpeed;
				if (s.onGround && !s.onGasoline) s.preSlideSpeed -= V1.DT * s.preSlideSpeed;
				s.preSlideDelay = 0;
			}
			double vy = s.vel.y - GRAVITY * V1.DT;
			Vec3d v = new Vec3d(s.dodgeDirection.x, 0, s.dodgeDirection.z).normalize().multiply(SLIDE_SPEED * num).add(0, vy, 0);
			if (s.boostLeft > 0) {
				s.dashStorage = Math.max(0, s.dashStorage - V1.DT);
				if (s.dashStorage <= 0) s.boostLeft = 0;
			}
			double strafe = 0;
			if (UKKeys.RIGHT.held) strafe += 1;
			if (UKKeys.LEFT.held) strafe -= 1;
			Vec3d side = Vec3d.fromPolar(0, p.getYaw() + 90).multiply(strafe * SLIDE_STRAFE);
			s.vel = v.add(side);
			return;
		}
		if (s.framesSinceSlide <= 3) return;
		double keepY = s.slideEnding ? s.vel.y : 0;
		if (s.onGround) s.dashedFromGround = true;
		Vec3d target = s.dodgeDirection.multiply(WALK).add(0, keepY, 0);
		if (s.slideEnding) {
			s.slideEnding = false;
			if (!s.onGround || s.onGasoline) { s.boost = false; return; }
		}
		if (s.boostLeft > 0) {
			s.vel = target.multiply(DASH_SPEED_MULT);
			s.boostLeft -= DASH_DRAIN;
			return;
		}
		// (a dash ending on gasoline keeps its speed: no friction to take it back to walking)
		if (!s.onGround || !s.onGasoline) s.vel = target;
		s.boost = false;
	}

	// ---------------------------------------------------------------- jumps

	/** NewMovement.LaunchFromPoint(position, strength, maxDistance): explosions pushing V1 (positions in blocks, speeds in u/s). */
	/**
	 * Explosion.Collide's player branch: not while explosionLaunchResistance runs, a 0-damage push not within the 0.5 s
	 * safeExplosionLaunchCooldown of the last; a rocket's push wave is 100 / ((rocketJumps + 3) / 3) strong (rocketJumps
	 * counts up, back to 0 on landing) with windState 0.75; an explosion less than 0.25 u off V1's axis launches from V1's
	 * own position (straight up, full maxSize).
	 */
	public static void explosionLaunch(V1 s, Vec3d playerPos, Vec3d origin, double strength, double maxDistance, boolean rocketPush) {
		if (s.explosionLaunchResistance > 0) return;
		if (rocketPush && s.safeExplosionLaunchCooldown > 0) return;
		if (rocketPush) {
			strength = Math.round(100f / ((s.rocketJumps + 3) / 3f));
			s.rocketJumps++;
			s.windState = 0.75;
		}
		double fx = (origin.x - playerPos.x) / V1.SCALE, fz = (origin.z - playerPos.z) / V1.SCALE;
		Vec3d from = fx * fx + fz * fz < 0.0625 ? playerPos : origin;
		launchFromPoint(s, playerPos, from, strength, maxDistance);
		if (rocketPush) s.safeExplosionLaunchCooldown = 0.5;
	}

	/**
	 * Explosion.Collide for a boosted blast: LaunchFromPointAtSpeed(from, max(60, V1's speed)) - away from the blast
	 * (straight up when it is less than 0.25 u off V1's axis) at that speed, with up to half of it added upward, the
	 * velocity set outright (Launch, ignoreMass). Not while explosionLaunchResistance runs.
	 */
	public static void boostedLaunch(V1 s, Vec3d playerPos, Vec3d origin, double minSpeed) {
		if (s.explosionLaunchResistance > 0) return;
		double fx = (origin.x - playerPos.x) / V1.SCALE, fz = (origin.z - playerPos.z) / V1.SCALE;
		double speed = Math.max(minSpeed, s.vel.length());
		Vec3d dir = fx * fx + fz * fz < 0.0625 ? new Vec3d(0, 1, 0) : playerPos.subtract(origin).normalize();
		Vec3d v = dir.multiply(speed);
		double up = Math.max(0, 0.5 * speed - v.y);
		launch(s, v.add(0, up, 0), 100); // (launch divides by the 100 kg mass: ignoreMass)
	}

	public static void launchFromPoint(V1 s, Vec3d playerPos, Vec3d position, double strength, double maxDistance) {
		Vec3d dir = playerPos.subtract(position);
		dir = dir.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : dir.normalize();
		Vec3d v;
		if (s.jumping) {
			v = dir.multiply(maxDistance * strength);
			Vec3d flat = new Vec3d(v.x, 0, v.z);
			double a = s.vel.y;
			v = flat.add(0, Math.max(a, 0.5 * maxDistance * strength), 0);
		} else {
			double num = Math.max(0, maxDistance - playerPos.distanceTo(position) / V1.SCALE);
			v = dir.multiply(num * strength);
			Vec3d flat = new Vec3d(v.x, 0, v.z);
			double a = s.vel.y;
			v = flat.add(0, Math.max(a, 0.5 * num * strength), 0);
		}
		launch(s, v, 8);
	}

	/** NewMovement.Launch: impulse `direction` (clamped to 1000) × multiplier on a 100 kg body → u/s. */
	public static void launch(V1 s, Vec3d direction, double multiplier) {
		s.jumping = true;
		s.jumpingTimer = 0.5;
		s.jumpCooldown = true;
		s.jumpCooldownTimer = 0.2;
		s.boost = false;
		if (s.heavyFall) { s.fallSpeed = 0; s.heavyFall = false; }
		double len = direction.length();
		if (len <= 0) return;
		Vec3d d = len > 1000 ? direction.multiply(1000 / len) : direction;
		s.vel = d.multiply(multiplier / 100.0);
		s.falling = true;
	}

	public static void jump(ClientPlayerEntity p, V1 s) {
		s.fallTime = 0;
		s.lastJump = 0;
		s.jumping = true;
		s.jumpingTimer = 0.25;
		s.falling = true;
		s.jumpTimestamp = s.time;
		V1Sounds.jump(p, s.superJumpChance > 0 ? 2f : 1f);
		s.vel = new Vec3d(s.vel.x, 0, s.vel.z);
		double up;
		boolean plain = false;
		if (s.sliding) {
			up = JUMP_UNIT * (s.slowMode ? 1 : SLIDE_JUMP_MULT);
			stopSlide(p, s);
		} else if (s.boost && s.jumpTimestamp - s.slideTimestamp > V1.DT * SSJ_MAX_FRAMES) {
			if (s.boostCharge >= DASH_COST) {
				s.boostCharge -= DASH_COST;
				V1Sounds.dashJump(p);
			} else {
				s.vel = new Vec3d(s.inputDir.x * WALK, 0, s.inputDir.z * WALK);
				V1Sounds.staminaFail(p);
			}
			up = JUMP_UNIT * (s.slowMode ? 0.75 : DASH_JUMP_MULT);
		} else if (s.slowMode) {
			up = JUMP_UNIT * SLOW_JUMP_MULT;
			plain = true;
		} else if (s.superJumpChance > 0 || s.extraJumpChance > 0) {
			up = JUMP_UNIT * (s.slamForce < 5.5 ? (3 + (s.slamForce - 1)) : 12.5);
		} else {
			up = JUMP_UNIT * JUMP_MULT;
			plain = true;
		}
		// (only the plain jump can be a short hop: slide, dash and super jumps stay whole)
		s.hopCuttable = plain;
		if (p.isTouchingWater()) up *= Tune.get("water.jumpMult");
		s.vel = s.vel.add(0, up, 0);
		trySSJ(s, s.dodgeDirection.normalize(), 0.5, frame -> 1.0 / Math.pow(2, frame - 1));
		s.jumpCooldown = true;
		s.jumpCooldownTimer = 0.2;
		s.boost = false;
		s.heavyFall = false;
	}

	private interface SpeedLoss { double at(int frame); }

	private static void trySSJ(V1 s, Vec3d direction, double speedMultiplier, SpeedLoss loss) {
		double num = s.jumpTimestamp - s.slideTimestamp;
		if (!(num > 0)) return;
		int frames = (int) (num / V1.DT);
		if (frames == 0 || frames >= SSJ_MAX_FRAMES) return;
		double gain = speedMultiplier * WALK * 3 * loss.at(frames);
		double y = s.vel.y;
		Vec3d v = s.velocityAfterSlide.add(direction.multiply(gain));
		v = new Vec3d(v.x, y, v.z);
		if (v.length() > 100) v = v.normalize().multiply(100);
		s.vel = v;
	}

	private static void wallJump(ClientPlayerEntity p, V1 s) {
		s.jumping = true;
		s.jumpingTimer = 0.25;
		s.currentWallJumps++;
		if (s.heavyFall) s.slamStorage = true;
		V1Sounds.wallJump(p, s.currentWallJumps, s.wallNormal);
		Vec3d away = s.wallNormal;
		if (s.sliding || s.time - s.slideTimestamp < SSJ_MAX_FRAMES * V1.DT) {
			Vec3d dir = reflect(s.dodgeDirection.normalize(), away);
			dir = new Vec3d(dir.x, 0, dir.z).normalize();
			dir = dir.add(away.multiply(0.35)).normalize();
			s.dodgeDirection = dir;
			s.vel = dir.multiply(s.vel.length());
			trySSJ(s, dir, 0.75, frame -> (SSJ_MAX_FRAMES - frame + 1) / SSJ_MAX_FRAMES);
			double a = s.vel.y;
			s.vel = new Vec3d(s.vel.x, Math.max(a, 15), s.vel.z);
			s.windState = 0.5;
		} else {
			s.boost = false;
			s.vel = new Vec3d(away.x * WALLJUMP_V, WALLJUMP_V, away.z * WALLJUMP_V);
		}
		s.jumpCooldown = true;
		s.jumpCooldownTimer = 0.1;
	}

	// ---------------------------------------------------------------- integration

	private static void integrate(ClientPlayerEntity p, V1 s) {
		Vec3d before = p.getPos();
		Vec3d delta = s.vel.multiply(V1.SCALE * V1.DT);
		// Entity.move drops a step shorter than ~0.0003 blocks (lengthSquared <= 1e-7) without moving: read below as a
		// landing it zeroed the speed, so a slow start (sinking from a standstill in water) never got going and V1 hung
		// in the water. A step that short is taken here when there is room, the speed kept.
		if (delta.lengthSquared() <= 1e-7) {
			if (delta.lengthSquared() > 0 && p.getWorld().isSpaceEmpty(p, p.getBoundingBox().offset(delta))) p.setPosition(before.add(delta));
			return;
		}
		p.move(MovementType.SELF, delta);
		// the portal stop (user): running, jumping or dashing into a Nether portal, V1 stops dead in it and drops to the
		// frame's floor, where Minecraft's own portal wait takes it through (at V1's speed it crossed the portal in a tick)
		boolean portal = Tune.on("move.portalStop") && inNetherPortal(p);
		if (portal && !s.inPortal) {
			s.vel = new Vec3d(0, Math.min(s.vel.y, 0), 0);
			s.boost = false;
			s.boostLeft = 0;
			if (s.sliding) stopSlide(p, s);
			s.inPortal = true;
			return;
		}
		s.inPortal = portal;
		Vec3d moved = p.getPos().subtract(before);
		double eps = 1e-5;
		double vx = s.vel.x, vy = s.vel.y, vz = s.vel.z;
		if (Math.abs(moved.x - delta.x) > eps) vx = 0;
		if (Math.abs(moved.z - delta.z) > eps) vz = 0;
		if (Math.abs(moved.y - delta.y) > eps) {
			// landed or hit a ceiling; a step-up keeps the horizontal speed
			if (delta.y < 0 && moved.y > delta.y) vy = 0;
			else if (delta.y > 0 && moved.y < delta.y) vy = 0;
		}
		s.vel = new Vec3d(vx, vy, vz);
	}

	private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
		return a.add(b.subtract(a).multiply(t));
	}

	private static Vec3d project(Vec3d v, Vec3d onto) {
		double l = onto.lengthSquared();
		if (l < 1e-12) return Vec3d.ZERO;
		return onto.multiply(v.dotProduct(onto) / l);
	}

	private static Vec3d reflect(Vec3d v, Vec3d n) {
		return v.subtract(n.multiply(2 * v.dotProduct(n)));
	}
}
