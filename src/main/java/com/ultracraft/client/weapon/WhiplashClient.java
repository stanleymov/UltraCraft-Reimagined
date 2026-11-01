package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UKKeys;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.client.v1.V1;
import com.ultracraft.net.FirePayload;
import com.ultracraft.net.Net;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;

/**
 * Port of HookArm.cs (the Whiplash). The hook flies at 250 u/s from the camera along the look direction
 * (sphere cast radius min(dist/15, 5) u, 300 u of line), catches the first enemy; releasing the key pulls:
 * light enemies (max health ≤ `whiplash.lightHp`) come to V1 at 60 u/s, heavy ones pull V1 to them at
 * 60 u/s with hard damage 66/s (≤ 50). Jump while being pulled: |v| ≤ 30 and +15 up. Afterwards the hook
 * returns at (100 + returnDistance/2) u/s and the Catch clip plays. V1 owns its own velocity, so the
 * client runs the whole state machine and tells the server what to damage / pull.
 */
public final class WhiplashClient {
	public static final WhiplashClient INSTANCE = new WhiplashClient();
	public enum State { READY, THROWING, CAUGHT, PULLING }
	/** PULLING toward a grapple orb block rather than a caught entity, and that orb. */
	private boolean pointHook;
	private BlockPos orbPos;
	private static final float DT = 0.05f;

	public State state = State.READY;
	private String lastDebug;

	/** HookArm.beingPulled: V1 itself is being pulled (to a heavy enemy or a grapple point). */
	public boolean beingPulled() {
		return state == State.PULLING && (!lightTarget || pointHook);
	}

	/**
	 * The slide key only stores a slam (no crouch, no slide, no camera drop - the user's call): from the moment the hook
	 * holds a heavy enemy or a grapple point, through the pull, until a slam stored on the way has landed.
	 */
	public boolean blocksCrouch() {
		V1 v = V1.INSTANCE;
		if (v.hookSlam && (v.onGround || !v.heavyFall)) v.hookSlam = false;
		return ((state == State.CAUGHT || state == State.PULLING) && (!lightTarget || pointHook)) || v.hookSlam;
	}
	public boolean returning;
	public float cooldown;
	public Vec3d hookPoint = Vec3d.ZERO, throwDirection = new Vec3d(0, 0, 1);
	public Entity caught;
	private Vec3d caughtOffset = Vec3d.ZERO;
	/** The pull's progress: the hooked mob's place last tick, the smallest gap so far (u) and how long it has not shrunk. */
	private Vec3d pullLast;
	private double pullBestGap;
	private float pullStall;
	public boolean lightTarget;
	private float returnDistance;
	/** A light enemy's pull, run here (u/s), and where V1 was last tick (the enemy is carried along with V1's own moves). */
	private double pullSpeed;
	private Vec3d pullPlayerPrev;
	/** True while the server's position updates for the hooked light enemy are being set aside (this side moves it). */
	public static boolean ownPullPosition;
	private float throwWarp;
	public boolean modelActive;
	/** The pull loop's pitch, and the seconds left of its fade-out once the grab is over (-1 = not fading). */
	private float pullPitch = 1, pullFade = -1;

	private WhiplashClient() {
		Tune.def("whip.ropeScreen", 1, "1 = the rope starts exactly where the hand is on screen (the arm's projection cast back through the camera); 0 = the plain conversion");
		Tune.def("whiplash.pullBase", 80, "light enemy pull speed, u/s, plus half the distance it was caught at (HookArm: the hook's return is 100 + returnDistance / 2; 80 by the player's choice)");
		Tune.def("whiplash.catchGap", 1.0, "u between a pulled light enemy and V1 at which the pull counts as caught (the game: it touches V1, 0.25)");
		Tune.def("whiplash.stuckTime", 0.5, "seconds a pull may go without getting closer before the hook lets go (0 = never; the mod's own safety: Minecraft's mobs and square body can hold a pull the game's never stalls)");
		Tune.def("whiplash.teleportDist", 2.5, "blocks the hooked mob may move in one tick before the hook lets go of it (an Enderman's teleport, which Minecraft shows spread over 3 ticks; walking is ~0.3, knockback ~1; 0 = never)");
		Tune.def("whiplash.hardDamage", 0, "1 = pulling builds hard damage, 66/s up to 50 (HookArm; the game's default, an assist option turns it off)");
		Tune.def("whiplash.lightHp", 30, "mobs with max health up to this are pulled to V1; heavier ones pull V1 (HookArm.lightEnemies)");
		Tune.def("whiplash.enabled", 1, "1 = the Whiplash is owned (R throws it)");
		Tune.def("whiplash.pointRelease", 1.5, "u from a grapple orb at which the pull lets go");
		Tune.def("grapple.catchRadius", 2, "u, a grapple orb's catch sphere (from the game: GrapplePoint's trigger SphereCollider), plus the hook's own sphere cast");
		Tune.def("whiplash.pullFade", 0.3, "s the pull loop fades out over once nothing is grabbed any more (the game keeps it until the hook is back)");
	}

	/** The orb at `pos` is the one being pulled to (HookPoint.hooked: between Hooked() and Unhooked()). */
	public boolean hooking(BlockPos pos) {
		return pointHook && state == State.PULLING && pos.equals(orbPos);
	}

	public void reset() {
		state = State.READY;
		returning = false;
		caught = null;
		modelActive = false;
		cooldown = 0;
	}

	/** HookArm.Cancel (a punch starts): let go of everything, no return trip, sounds off, the arm away. */
	public void cancel() {
		if (state == State.READY && !returning && !modelActive) return;
		// a light enemy being pulled flies on with its momentum and stops once it reaches V1 (server)
		if (caught != null && lightTarget && (state == State.PULLING || state == State.CAUGHT) && MinecraftClient.getInstance().player != null) {
			ClientPlayerEntity p = MinecraftClient.getInstance().player;
			Net.toServer(new FirePayload(FirePayload.HOOK, 4, caught.getId(), p.getEyePos(), p.getRotationVec(1f)));
		}
		state = State.READY;
		returning = false;
		caught = null;
		lightTarget = false;
		pointHook = false;
		throwWarp = 0;
		modelActive = false;
		pullFade = -1;
		UKSound.stop("uk.weapons.whiplash_throw_loop");
		UKSound.stop("uk.weapons.whiplash_pull_loop");
		UKSound.stop("uk.weapons.whiplash_whoosh");
		RigInstance rig = rig();
		if (rig != null) rig.play("Idle", 1, 0);
	}

	private void pullLoop() {
		pullPitch = UKSound.range(0.9f, 1.1f);
		pullFade = -1;
		UKSound.loop("uk.weapons.whiplash_pull_loop", 0.8f, pullPitch);
	}

	public RigInstance rig() {
		return ViewModel.instance("whiplash");
	}

	public boolean active() {
		return modelActive;
	}

	public void tick(MinecraftClient client, boolean inputOk) {
		ClientPlayerEntity p = client.player;
		if (p == null || !Tune.on("whiplash.enabled")) return;
		V1 v = V1.INSTANCE;
		if (Damage.debug && (state != State.READY || v.hookSlam)) {
			// (only when something changes)
			String d = String.format("whip %s light %s pose %s sneaking %s eye %.2f slideHeld %s heavyFall %s sliding %s crouching %s",
				state, lightTarget, p.getPose(), p.isSneaking(), p.getStandingEyeHeight(), UKKeys.SLIDE.held, v.heavyFall, v.sliding, v.crouching);
			if (!d.equals(lastDebug)) com.ultracraft.UltraCraft.LOG.info("[weapons] {}", d);
			lastDebug = d;
		}
		RigInstance rig = rig();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1f);
		if (cooldown > 0) cooldown = Math.max(0, cooldown - DT);
		if (throwWarp > 0) throwWarp = Math.max(0, throwWarp - DT * 6.5f);
		if (pullFade >= 0) {
			pullFade -= DT;
			if (pullFade <= 0) { pullFade = -1; UKSound.stop("uk.weapons.whiplash_pull_loop"); }
			else UKSound.setLoop("uk.weapons.whiplash_pull_loop", 0.8f * pullFade / (float) Math.max(0.01, Tune.get("whiplash.pullFade")), pullPitch);
		}
		// HookArm.Update: the key
		if (inputOk && UKKeys.HOOK.press) {
			if (state == State.PULLING) stopThrow(p, 0);
			else if (cooldown <= 0 && state == State.READY && !returning) {
				cooldown = 0.5f;
				modelActive = true;
				WeaponClient w = WeaponClient.INSTANCE;
				if (w.fistCooldown > 0.1f) w.fistCooldown = 0.1f;
				// from where the camera was drawn (the crosshair's line): the tick's eye is up to a tick of V1's move off it
				Vec3d camPos = client.gameRenderer.getCamera().getPos();
				hookPoint = client.options.getPerspective().isFirstPerson() && camPos.distanceTo(eye) < 4 ? camPos : eye;
				throwDirection = look;
				returning = false;
				state = State.THROWING;
				lightTarget = false;
				throwWarp = 1f;
				caught = null;
				if (rig != null) rig.play("Throw", 1, 0);
				UKSound.play("uk.weapons.whiplash_throw_start", 1f, 1f);
				UKSound.loop("uk.weapons.whiplash_throw_loop", 0.8f, UKSound.range(0.9f, 1.1f));
			}
		}
		// jump while being pulled by a heavy target
		if (state == State.PULLING && !lightTarget && inputOk && UKKeys.JUMP.press) {
			if (v.vel.y < 1) v = V1.INSTANCE;
			Vec3d vel = v.vel;
			if (vel.y < 1) vel = new Vec3d(vel.x, 1, vel.z);
			if (vel.length() > 30) vel = vel.normalize().multiply(30);
			v.vel = vel;
			if (!v.onGround) v.vel = v.vel.add(0, 15, 0);
			stopThrow(p, 1);
		}
		// HookArm.FixedUpdate (four 50 Hz steps per tick where distances matter)
		if (returning) {
			double step = DT * (100 + returnDistance / 2) * Damage.SCALE_BLOCKS;
			Vec3d target = eye.add(look.multiply(1.5 * Damage.SCALE_BLOCKS));
			if (hookPoint.distanceTo(target) <= step) {
				hookPoint = target;
				returning = false;
				if (rig != null) rig.play("Catch", 1, 0);
				UKSound.play("uk.weapons.whiplash_catch", 1f, 1f);
				UKSound.stop("uk.weapons.whiplash_pull_loop");
				UKSound.stop("uk.weapons.whiplash_throw_loop");
			} else hookPoint = hookPoint.add(target.subtract(hookPoint).normalize().multiply(step));
		}
		switch (state) {
			case THROWING -> {
				if (!UKKeys.HOOK.held && cooldown <= 0.1f) { stopThrow(p, 0); break; }
				double dist = 250 * DT * Damage.SCALE_BLOCKS;
				Vec3d end = hookPoint.add(throwDirection.multiply(dist));
				BlockHitResult block = client.world.raycast(new RaycastContext(hookPoint, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
				boolean hitWall = block.getType() != HitResult.Type.MISS;
				if (hitWall) end = block.getPos();
				double radius = Math.min(hookPoint.distanceTo(eye) / Damage.SCALE_BLOCKS / 15, 5) * Damage.SCALE_BLOCKS;
				// grapple orbs (HookPoint: a 2 u trigger sphere on layer 22, which the hook's sphere cast takes): the nearest
				// one along the step before any wall, if it is more than 5 u from V1
				BlockPos orb = null;
				double orbD = Double.MAX_VALUE;
				double reach = Tune.get("grapple.catchRadius") * Damage.SCALE_BLOCKS + radius, segLen = hookPoint.distanceTo(end);
				for (BlockPos bp : BlockPos.iterate(BlockPos.ofFloored(Math.min(hookPoint.x, end.x) - reach, Math.min(hookPoint.y, end.y) - reach, Math.min(hookPoint.z, end.z) - reach),
						BlockPos.ofFloored(Math.max(hookPoint.x, end.x) + reach, Math.max(hookPoint.y, end.y) + reach, Math.max(hookPoint.z, end.z) + reach))) {
					if (client.world.getBlockState(bp).getBlock() != com.ultracraft.block.UKBlocks.GRAPPLE_ORB.get()) continue;
					Vec3d c = Vec3d.ofCenter(bp);
					double t = MathHelper.clamp(c.subtract(hookPoint).dotProduct(throwDirection), 0, segLen);
					if (c.distanceTo(hookPoint.add(throwDirection.multiply(t))) <= reach && c.distanceTo(eye) / Damage.SCALE_BLOCKS > 5 && t < orbD) {
						orb = bp.toImmutable();
						orbD = t;
					}
				}
				Entity best = null;
				double bestD = Double.MAX_VALUE;
				Vec3d bestPoint = null;
				// coins in the path get bounced (Coin.Bounce: a fresh coin thrown 25 u/s up) and the hook flies on
				for (Entity e : client.world.getOtherEntities(p, new Box(hookPoint, end).expand(0.6), ent -> ent instanceof com.ultracraft.entity.CoinEntity c && !c.isShot())) {
					if (e.getBoundingBox().expand(0.6).raycast(hookPoint, end).isPresent() || e.getBoundingBox().expand(0.6).contains(hookPoint)) {
						Net.toServer(new FirePayload(FirePayload.HOOK, 3, e.getId(), eye, look));
						UKSound.at(e.getPos(), "uk.weapons.whiplash_clink", 0.6f, 1.4f);
					}
				}
				// enemies and dropped items can be caught (HookArm: EnemyIdentifier / ItemIdentifier)
				for (Entity e : client.world.getOtherEntities(p, new Box(hookPoint, end).expand(radius + 0.5), ent -> (ent instanceof LivingEntity || ent instanceof net.minecraft.entity.ItemEntity) && ent.isAlive())) {
					// Box.raycast needs an entry face: a step that starts inside the (expanded) box is a hit right there. The
					// mob is drawn between its last two places: both count
					Box now = e.getBoundingBox();
					Box bb = now.union(now.offset(e.prevX - e.getX(), e.prevY - e.getY(), e.prevZ - e.getZ())).expand(radius);
					Optional<Vec3d> hit = bb.contains(hookPoint) ? Optional.of(hookPoint) : bb.raycast(hookPoint, end);
					if (hit.isPresent() && hit.get().squaredDistanceTo(hookPoint) < bestD) {
						bestD = hit.get().squaredDistanceTo(hookPoint);
						best = e;
						bestPoint = hit.get();
					}
				}
				if (Damage.debug) UltraCraft.LOG.info("[weapons] hook step {} -> {} wall={} radius={} best={}", hookPoint, end, hitWall, radius, best == null ? "-" : best.getType() + "#" + best.getId());
				if (best != null && (orb == null || bestD <= orbD * orbD)) {
					// a jockey (a rider and its mount) comes as one, whichever of them the hook caught: the stack's bottom is
					// moved (a rider is held to its mount's place every tick), light only if every one of them is
					caught = best.getRootVehicle();
					hookPoint = best.getBoundingBox().getCenter();
					caughtOffset = hookPoint.subtract(caught.getPos());
					lightTarget = caught.streamSelfAndPassengers().allMatch(en -> !(en instanceof LivingEntity le) || le.getMaxHealth() <= Tune.get("whiplash.lightHp"));
					state = State.CAUGHT;
					UKSound.stop("uk.weapons.whiplash_throw_loop");
					UKSound.at(bestPoint, "uk.weapons.hookpointgrab", 1f, 1f);
					Net.toServer(new FirePayload(FirePayload.HOOK, 0, best.getId(), eye, look));
				} else if (orb != null) {
					// a grapple point (HookPoint, Normal): the hook latches on and V1 is pulled to it right away
					pointHook = true;
					orbPos = orb;
					caught = null;
					lightTarget = false;
					hookPoint = Vec3d.ofCenter(orb);
					state = State.PULLING;
					pullStall = -1;
					pullLast = null;
					UKSound.stop("uk.weapons.whiplash_throw_loop");
					UKSound.at(hookPoint, "uk.weapons.hookpointgrab", 1f, 1f);
					UKSound.play("uk.weapons.whiplash_pull_start", 1f, 1f);
					pullLoop();
					if (rig != null) rig.play("Pull", 1, 0);
					if (client.world.getBlockEntity(orb) instanceof com.ultracraft.block.GrappleOrbBlockEntity be) be.grabbedNanos = System.nanoTime();
				} else if (hitWall) {
					UKSound.at(end, "uk.weapons.whiplash_clink", 1f, 1f);
					hookPoint = end;
					stopThrow(p, 0);
				} else if (hookPoint.distanceTo(eye) / Damage.SCALE_BLOCKS > 300) stopThrow(p, 0);
				else hookPoint = end;
			}
			case CAUGHT -> {
				if (caught == null || !caught.isAlive()) { stopThrow(p, 0); break; }
				hookPoint = caught.getPos().add(caughtOffset);
				if (!UKKeys.HOOK.held) {
					state = State.PULLING;
					pullStall = -1;
					pullLast = null;
					if (rig != null) rig.play("Pull", 1, 0);
					UKSound.play("uk.weapons.whiplash_pull_start", 1f, 1f);
					// HookArm: currentWoosh = Instantiate(wooshSound), destroyed again in StopThrow / Cancel
					UKSound.once("uk.weapons.whiplash_whoosh", 0.8f, 1f);
					pullLoop();
					if (lightTarget && !v.onGround) v.vel = new Vec3d(v.vel.x, 15, v.vel.z);
					// HookArm: a light enemy comes back with the hook, 100 + returnDistance / 2 u/s
					double distU = caught.getBoundingBox().getCenter().distanceTo(p.getPos().add(0, 0.9, 0)) / Damage.SCALE_BLOCKS;
					pullSpeed = Tune.get("whiplash.pullBase") + distU / 2;
					pullPlayerPrev = p.getPos();
				}
			}
			case PULLING -> {
				if (pointHook) {
					if (pullBlocked(client, p, eye)) { release("blocks in the way"); pointHook = false; stopThrow(p, 1); break; }
					// HookArm on a HookPoint: straight at it at 60 u/s until V1 is there, then let go keeping the momentum
					Vec3d center = p.getPos().add(0, 0.9, 0);
					double gap = center.distanceTo(hookPoint) / Damage.SCALE_BLOCKS;
					if (gap < Tune.get("whiplash.pointRelease")) { pointHook = false; stopThrow(p, 1); break; }
					if (stalled(gap)) { release("no closer"); pointHook = false; stopThrow(p, 1); break; }
					Vec3d dir = hookPoint.subtract(center).normalize();
					v.vel = dir.multiply(60);
					v.falling = true;
					v.onGround = false;
					break;
				}
				if (caught == null || !caught.isAlive()) { stopThrow(p, 1); break; }
				// (the mod's own: a mob V1 is pulled to that jumps away in one tick - an Enderman's teleport - is let go; a
				// light one is moved by the pull itself)
				double tp = Tune.get("whiplash.teleportDist");
				if (!lightTarget && tp > 0 && pullLast != null && caught.getPos().distanceTo(pullLast) > tp) { release("teleported"); stopThrow(p, 1); break; }
				pullLast = caught.getPos();
				hookPoint = caught.getPos().add(caughtOffset);
				if (pullBlocked(client, p, eye)) { release("blocks in the way"); stopThrow(p, 1); break; }
				Vec3d center = p.getPos().add(0, 0.9, 0);
				// hard damage while pulling an enemy (both directions), 66/s up to 50
				// (the game's assist option disableWhiplashHardDamage; off here by the player's choice: whiplash.hardDamage)
				if (Tune.on("whiplash.hardDamage")) {
					if (v.antiHp + DT * 66 <= 50) v.antiHp += DT * 66;
					else if (v.antiHp <= 50) v.antiHp = 50;
				}
				// HookArm: a raycast on the enemy layers from V1 to the hooked enemy's centre - another enemy in between is
				// what V1 reaches (the arrival is measured to it)
				Box target = caught.getBoundingBox();
				double nearest = Double.MAX_VALUE;
				if (!lightTarget) {
					Vec3d to = target.getCenter();
					for (Entity e : client.world.getOtherEntities(p, new Box(center, to).expand(1), e -> e != caught && e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
						Box eb = e.getBoundingBox();
						Optional<Vec3d> hit = eb.contains(center) ? Optional.of(center) : eb.raycast(center, to);
						if (hit.isPresent() && hit.get().distanceTo(center) < nearest) { nearest = hit.get().distanceTo(center); target = eb; }
					}
				}
				Vec3d closest = closest(target, center);
				double gap = closest.distanceTo(closest(p.getBoundingBox(), closest)) / Damage.SCALE_BLOCKS;
				if (stalled(gap)) { release("no closer"); stopThrow(p, 1); break; }
				// (the light enemy's position here is a tick or so behind the server's: whiplash.catchGap of room)
				// (V1 moves 1.5 blocks a tick here, the game checks at 50 Hz: an enemy in the way within this tick's move
				// counts as reached, or V1 flies through it between two checks)
				boolean inTheWay = target != caught.getBoundingBox() && nearest <= v.vel.length() * Damage.SCALE_BLOCKS * DT + 0.25 * Damage.SCALE_BLOCKS;
				if (inTheWay || gap < (lightTarget ? Tune.get("whiplash.catchGap") : 0.25) || (!lightTarget && closest.distanceTo(center.add(v.vel.multiply(Damage.SCALE_BLOCKS * DT))) / Damage.SCALE_BLOCKS < 0.25 + 0.6)) {
					release(target == caught.getBoundingBox() ? "arrived" : "arrived at an enemy in the way");
					Net.toServer(new FirePayload(FirePayload.HOOK, catchMode(), caught.getId(), eye, look));
					stopThrow(p, 1);
					double dy = hookPoint.y - center.y;
					if (!v.onGround) v.vel = new Vec3d(v.vel.x * 0.2, center.y < hookPoint.y ? 15 + dy / Damage.SCALE_BLOCKS : 15, v.vel.z * 0.2);
					break;
				}
				if (lightTarget) {
					// (moved after the world's tick: afterTick)
					pullDue = true;
				} else if (!v.boost || v.sliding) {
					// NewMovement velocity straight at the hook point at 60 u/s
					Vec3d dir = hookPoint.subtract(center).normalize();
					v.vel = dir.multiply(60);
					v.falling = true;
				}
			}
			default -> {}
		}
	}

	/**
	 * The pull's safety net (the mod's own, whiplash.stuckTime): the gap to what V1 is pulled to must keep shrinking; a
	 * pull that gets no closer for that long (a mob walking off as fast, pushing back, a body caught on something) lets go.
	 */
	private static void release(String why) {
		if (Damage.debug) com.ultracraft.UltraCraft.LOG.info("[weapons] whip let go: {}", why);
	}

	private boolean stalled(double gap) {
		double limit = Tune.get("whiplash.stuckTime");
		if (pullStall < 0 || gap < pullBestGap - 0.1) {
			pullBestGap = gap;
			pullStall = 0;
			return false;
		}
		pullStall += DT;
		return limit > 0 && pullStall >= limit;
	}

	/**
	 * HookArm.FixedUpdate while Pulling: anything of the level between V1 and the hook point (Physics.Raycast to it on
	 * the environment) lets go at once. Without it V1 hung on a block's edge for seconds with the pull still pushing
	 * (Minecraft's square body catches where the game's capsule slides off). The ray stops just short of the hook
	 * point, which can sit on a floor or wall.
	 */
	private static boolean pullBlocked(MinecraftClient client, ClientPlayerEntity p, Vec3d eye) {
		Vec3d to = INSTANCE.hookPoint.subtract(eye);
		double len = to.length();
		if (len < 0.1) return false;
		Vec3d end = eye.add(to.multiply((len - 0.05) / len));
		return client.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p)).getType() != HitResult.Type.MISS;
	}

	/** A light enemy's pull step owed this tick (run once V1 and the world have moved). */
	private boolean pullDue;

	/**
	 * After the world's tick: the hooked light enemy moved here, along with V1's own move this tick, then toward V1 at the
	 * pull speed, no further than its body (the server takes this place: what it has of V1 and of the enemy is a tick or
	 * two old). Moved after the world's tick so the move is drawn as a glide from where it was (the enemy's own tick would
	 * otherwise make both ends of it the new place) and from where V1 is now.
	 */
	private static final double SOLID_AFTER = Tune.def("whip.solidAfter", 0.5, "seconds mobs stay solid to V1 after the whiplash ends (the user's call)");
	private long lastOutNanos;
	/**
	 * The mob just caught, for whip.solidAfter: the server's copy of V1 is a tick behind, so the server's positions could
	 * put it through V1; it keeps moving (AI on) but is put back out of V1 on this side.
	 */
	private Entity heldMob;
	private long heldUntil;
	private boolean bypass;

	public void afterTick(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		// mobs stay solid a moment after the whiplash too, so a pulled mob does not slide past V1 moving forward
		if (p != null && state != State.READY) lastOutNanos = System.nanoTime();
		com.ultracraft.weapon.WeaponServer.whiplashOut = p != null && System.nanoTime() - lastOutNanos < (long) (Tune.get("whip.solidAfter") * 1e9);
		if (heldMob != null) holdCaught(client, p);
		if (!pullDue || p == null || caught == null || !caught.isAlive() || state != State.PULLING || !lightTarget) { pullDue = false; return; }
		pullDue = false;
		Vec3d center = p.getPos().add(0, 0.9, 0);
		Vec3d move = pullPlayerPrev == null ? Vec3d.ZERO : p.getPos().subtract(pullPlayerPrev);
		pullPlayerPrev = p.getPos();
		if (move.lengthSquared() > 6 * 6) move = Vec3d.ZERO;
		Box mb = caught.getBoundingBox().offset(move);
		Vec3d to = center.subtract(mb.getCenter());
		double step = Math.min(pullSpeed * Damage.SCALE_BLOCKS * DT, boxGap(mb, p.getBoundingBox()));
		Vec3d old = caught.getPos();
		Vec3d want = move.add(to.lengthSquared() > 1e-9 ? to.normalize().multiply(step) : Vec3d.ZERO);
		// against the blocks (it slides along the ground and stops at walls instead of sinking through them)
		Vec3d next = old.add(Entity.adjustMovementForCollisions(caught, want, caught.getBoundingBox(), client.world, java.util.List.of()));
		caught.setPosition(next);
		caught.prevX = caught.lastRenderX = old.x;
		caught.prevY = caught.lastRenderY = old.y;
		caught.prevZ = caught.lastRenderZ = old.z;
		ownPullPosition = false;
		caught.updateTrackedPositionAndAngles(next.x, next.y, next.z, caught.getYaw(), caught.getPitch(), 1, false);
		ownPullPosition = true;
		ownPulled();
		Net.toServer(new FirePayload(FirePayload.HOOK, 5, caught.getId(), next, to));
	}

	private void holdCaught(MinecraftClient client, ClientPlayerEntity p) {
		if (p == null || !heldMob.isAlive() || System.nanoTime() > heldUntil || state == State.PULLING || client.world == null) {
			heldMob = null;
			return;
		}
		// it moves on its own (AI, the server's positions); only where it would be inside V1 it is put back out beside V1,
		// along the side it overlaps least, so it cannot pass through
		Box mb = heldMob.getBoundingBox(), vb = p.getBoundingBox();
		if (!mb.intersects(vb)) return;
		double ox = Math.min(mb.maxX - vb.minX, vb.maxX - mb.minX), oz = Math.min(mb.maxZ - vb.minZ, vb.maxZ - mb.minZ);
		Vec3d push = ox < oz
			? new Vec3d(mb.getCenter().x >= vb.getCenter().x ? vb.maxX - mb.minX : vb.minX - mb.maxX, 0, 0)
			: new Vec3d(0, 0, mb.getCenter().z >= vb.getCenter().z ? vb.maxZ - mb.minZ : vb.minZ - mb.maxZ);
		Vec3d adj = Entity.adjustMovementForCollisions(heldMob, push, mb, client.world, java.util.List.of());
		Vec3d next = heldMob.getPos().add(adj);
		heldMob.setPosition(next);
		bypass = true;
		heldMob.updateTrackedPositionAndAngles(next.x, next.y, next.z, heldMob.getYaw(), heldMob.getPitch(), 1, false);
		bypass = false;
	}

	/** The catch: hook mode 2 with V1 on the ground (the enemy stops dead), 7 in the air (HookArm pops it up, 15 u/s). */
	private static int catchMode() {
		return com.ultracraft.client.v1.V1.INSTANCE.onGround ? 2 : 7;
	}

	private static double boxGap(Box a, Box b) {
		double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX)), dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY)), dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** The entity whose server position updates this side sets aside (the light enemy it is pulling), else -1. */
	public int ownPulled() {
		boolean pulling = state == State.PULLING && lightTarget && caught != null;
		int id = -1;
		if (!bypass) {
			if (ownPullPosition && pulling) id = caught.getId();
		}
		com.ultracraft.weapon.WeaponServer.clientPulledId = id;
		com.ultracraft.weapon.WeaponServer.clientPullingId = pulling ? caught.getId() : -1;
		return id;
	}

	/** HookArm.StopThrow: the hook starts coming back; 0 = cancelled throw (pull sound), 1 = pull finished. */
	private void stopThrow(ClientPlayerEntity p, float animationTime) {
		pullLast = null;
		ownPullPosition = false;
		com.ultracraft.weapon.WeaponServer.clientPulledId = -1;
		if (animationTime == 0) {
			UKSound.play("uk.weapons.whiplash_pull_start", 1f, 1f);
			pullLoop();
		} else {
			UKSound.play("uk.weapons.whiplash_catch", 0.8f, 1.2f);
			// the grab is over: the pull loop fades out instead of running on until the hook is back
			pullFade = (float) Tune.get("whiplash.pullFade");
		}
		UKSound.stop("uk.weapons.whiplash_throw_loop");
		UKSound.stop("uk.weapons.whiplash_whoosh");
		if (caught != null && state == State.PULLING && lightTarget) {
			Net.toServer(new FirePayload(FirePayload.HOOK, catchMode(), caught.getId(), p.getEyePos(), p.getRotationVec(1f)));
			heldMob = caught;
			heldUntil = System.nanoTime() + (long) (Tune.get("whip.solidAfter") * 1e9);
		}
		caught = null;
		lightTarget = false;
		pointHook = false;
		state = State.READY;
		RigInstance rig = rig();
		if (rig != null) rig.play("Pull", 1, 0, animationTime);
		returnDistance = (float) Math.max(hookPoint.distanceTo(p.getEyePos()) / Damage.SCALE_BLOCKS, 25);
		returning = true;
		throwWarp = 0;
		ownPulled();
	}

	/** The Catch clip's CatchOver event: the arm model goes away. */
	public void catchOver() {
		if (state == State.READY && !returning) modelActive = false;
	}

	private static Vec3d closest(Box b, Vec3d p) {
		return new Vec3d(Math.max(b.minX, Math.min(b.maxX, p.x)), Math.max(b.minY, Math.min(b.maxY, p.y)), Math.max(b.minZ, Math.min(b.maxZ, p.z)));
	}

	// ------------------------------------------------------------------ rope + hook in the world

	private static final net.minecraft.util.Identifier WIRE = new net.minecraft.util.Identifier("ultracraft", "textures/uk/whiplashwire.png");

	/** View-space (Unity) point of a rig bone → world, through the camera. */
	private static Vec3d viewToWorld(Camera cam, Vector3f unityPos) {
		Vector3f f = cam.getHorizontalPlane(), u = cam.getVerticalPlane();
		Vector3f r = new Vector3f(f).cross(u).normalize();
		double s = Damage.SCALE_BLOCKS;
		Vec3d pos = cam.getPos();
		return pos.add(r.x * unityPos.x * s + u.x * unityPos.y * s + f.x * unityPos.z * s,
			r.y * unityPos.x * s + u.y * unityPos.y * s + f.y * unityPos.z * s,
			r.z * unityPos.x * s + u.z * unityPos.y * s + f.z * unityPos.z * s);
	}

	public void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
		if (state == State.READY && !returning) return;
		MinecraftClient c = MinecraftClient.getInstance();
		RigInstance rig = rig();
		if (rig == null || c.player == null) return;
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		// hand = the rig's "Wire Start" bone, posed as the hand renderer posed it
		Matrix4f wire = rig.boneWorld("GreenArmFinal/Armature/clavicle/wrist/hand/Wire Start");
		Vector3f hand = new Vector3f(0.3f, -0.25f, 0.6f);
		if (wire != null) {
			Vector4f hp = new Vector4f(0, 0, 0, 1).mul(wire);
			hand.set(hp.x, hp.y, hp.z);
		}
		// the bone is posed with the arm's mount (view.gunBack included) in the view model's units: the same
		// conversion as the arms' own drawing (V1.SCALE x view.scale, view.fov -> the world FOV), so the rope leaves
		// the hand where the hand is on screen (it used Damage.SCALE_BLOCKS and added gunBack a second time)
		Vec3d start = HandRenderer.viewToWorld(hand);
		// exact: the hand's point on screen (the arm's own pose and projection this frame), cast back into the world
		// through this frame's camera (its bobbing, tilt and FOV kick included) at the hand's depth
		Object[] lp = com.ultracraft.client.uk.ViewModel.lastPose.get(rig.rigName());
		if (lp != null && Tune.on("whip.ropeScreen")) {
			Matrix4f pose = (Matrix4f) lp[0], proj = (Matrix4f) lp[2];
			Vector4f pv = new Vector4f(hand.x, hand.y, -hand.z, 1).mul(pose);
			Vector4f clip = new Vector4f(pv).mul(proj);
			if (clip.w > 1e-5f && pv.z < -1e-4f) {
				float nx = clip.x / clip.w, ny = clip.y / clip.w;
				Matrix4f inv = new Matrix4f(e.getProjectionMatrix()).invert();
				Vector4f ray = new Vector4f(nx, ny, 1f, 1f).mul(inv);
				Vector3f dirV = new Vector3f(ray.x / ray.w, ray.y / ray.w, ray.z / ray.w);
				float depth = -pv.z;
				dirV.mul(depth / Math.max(1e-5f, -dirV.z));
				Matrix4f viewInv = new Matrix4f(e.getPoseStack().peek().getPositionMatrix()).invert();
				Vector4f wv = new Vector4f(dirV, 0).mul(viewInv);
				start = camPos.add(wv.x, wv.y, wv.z);
			}
		}
		// third person: the rope starts at the V1 model's left hand
		if (!c.options.getPerspective().isFirstPerson() && com.ultracraft.client.v1.V1Renderer.lastHandL != null
			&& System.nanoTime() - com.ultracraft.client.v1.V1Renderer.lastHandFrame < 200_000_000L) start = com.ultracraft.client.v1.V1Renderer.lastHandL;
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		// rope: a textured ribbon facing the camera, WhiplashWire tiled per 300 u
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(WIRE));
		Vec3d dir = hookPoint.subtract(start);
		double len = dir.length();
		if (len > 1e-4) {
			dir = dir.multiply(1 / len);
			Vec3d toCam = camPos.subtract(start);
			Vec3d side = dir.crossProduct(toCam);
			if (side.lengthSquared() < 1e-6) side = dir.crossProduct(new Vec3d(0, 1, 0));
			side = side.normalize().multiply(0.05 * Damage.SCALE_BLOCKS); // LineRenderer widthMultiplier 0.1 u
			int segs = 8;
			Vec3d upWarp = new Vec3d(0, 1, 0);
			Vec3d prev = start;
			float tiles = (float) (len / Damage.SCALE_BLOCKS / 300 * 10 + 1);
			for (int i = 1; i <= segs; i++) {
				Vec3d pt = start.lerp(hookPoint, (double) i / segs);
				if (i < segs) pt = pt.add(upWarp.multiply((i % 2 == 0 ? -3 : 3) * (1.0 / i) * throwWarp * Damage.SCALE_BLOCKS * 0.3));
				float u0 = tiles * (i - 1) / segs, u1 = tiles * i / segs;
				quad(vc, m, prev.add(side), prev.subtract(side), pt.subtract(side), pt.add(side), u0, u1);
				prev = pt;
			}
		}
		// the hook: the rig's Hook mesh moved from its bind spot to the hook point, up = throw direction
		int hookMesh = -1;
		for (int i = 0; i < rig.rig.meshes.size(); i++) if (rig.rig.meshes.get(i).name.equals("Hook")) hookMesh = i;
		Matrix4f hookBone = rig.boneWorld("GreenArmFinal/Armature/clavicle/wrist/Hook 1");
		if (hookMesh >= 0 && hookBone != null) {
			Vector4f hb = new Vector4f(0, 0, 0, 1).mul(hookBone);
			ms.push();
			ms.translate(hookPoint.x, hookPoint.y, hookPoint.z);
			// orient: model up (+y) → throw direction
			Vec3d d = throwDirection.normalize();
			float yaw = (float) Math.atan2(-d.x, d.z), pitch = (float) Math.asin(Math.max(-1, Math.min(1, d.y)));
			ms.multiply(new org.joml.Quaternionf().rotationY(yaw).rotateX((float) (Math.PI / 2 - pitch)));
			float s = (float) Damage.SCALE_BLOCKS;
			ms.scale(s, s, s);
			ms.translate(-hb.x, -hb.y, hb.z);
			java.util.Set<Integer> hidden = new java.util.HashSet<>(rig.hiddenMeshes);
			rig.hiddenMeshes.clear();
			for (int i = 0; i < rig.rig.meshes.size(); i++) if (i != hookMesh) rig.hiddenMeshes.add(i);
			rig.render(ms, buffers, 0xF000F0, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
			rig.hiddenMeshes.clear();
			rig.hiddenMeshes.addAll(hidden);
			ms.pop();
		}
		ms.pop();
		buffers.draw();
	}

	private static void quad(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, Vec3d c, Vec3d d, float u0, float u1) {
		vc.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(255, 255, 255, 255).texture(u0, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(255, 255, 255, 255).texture(u0, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(255, 255, 255, 255).texture(u1, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(255, 255, 255, 255).texture(u1, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
