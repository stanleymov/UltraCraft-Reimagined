package com.ultracraft.client.weapon;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Spent shotgun shells: Punch.Eject on the Knuckleblaster's PunchBlast spawns two ShotgunShell prefabs at the
 * ShellEjector with (forward/1.75 + up/2 + worldUp/1.75) × 8..12 u/s (the ejector AudioSource plays fistReload);
 * ShotgunShell.cs plays shellImpact when a shell first touches the environment, turns it into a gib after 0.2 s
 * and removes it after 2 s unless it has come to rest. Drawn with the game's shotgunshell mesh (v2_armtex).
 */
public final class ShellFx {
	private static final class Shell {
		Vec3d pos, vel;
		float life, spin, spinSpeed, ax, ay, az;
		boolean landed;
	}

	private static final List<Shell> shells = new ArrayList<>();
	private static final Random rnd = new Random();
	private static long lastNanos;
	private static RigInstance rig;
	private static boolean rigTried;

	static {
		Tune.def("fx.shells", 1, "0 = no ejected shells");
		Tune.def("fx.shellScale", 1.0, "multiplier on the ejected shell size");
		Tune.def("fx.shellLife", 20, "seconds a landed shell stays");
		Tune.def("fx.shellGravity", 2.0, "ejected shell gravity in g");
		Tune.def("fx.shellRight", 0.22, "ejector offset from the camera: right (blocks)");
		Tune.def("fx.shellDown", 0.18, "ejector offset from the camera: down (blocks)");
		Tune.def("fx.shellForward", 0.45, "ejector offset from the camera: forward (blocks)");
	}

	private ShellFx() {}

	/** Punch.Eject: `count` shells out of the red arm's ejector (camera-relative, the arm sits lower right). */
	public static void eject(int count) {
		if (!Tune.on("fx.shells")) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.player == null || c.gameRenderer == null) return;
		Camera cam = c.gameRenderer.getCamera();
		Vec3d fwd = new Vec3d(cam.getHorizontalPlane());
		Vec3d up = new Vec3d(cam.getVerticalPlane());
		Vec3d right = fwd.crossProduct(up).normalize();
		Vec3d origin = cam.getPos().add(right.multiply(Tune.get("fx.shellRight"))).subtract(up.multiply(Tune.get("fx.shellDown"))).add(fwd.multiply(Tune.get("fx.shellForward")));
		Vec3d ejFwd = fwd, ejUp = up, ejRight = right;
		if (HandRenderer.ejectorPos != null) {
			// the arm's ShellEjector as drawn this frame (Punch.Eject: shellEjector.position / forward / up / right)
			origin = HandRenderer.viewToWorld(HandRenderer.ejectorPos);
			ejFwd = HandRenderer.viewDirToWorld(HandRenderer.ejectorFwd);
			ejUp = HandRenderer.viewDirToWorld(HandRenderer.ejectorUp);
			ejRight = HandRenderer.viewDirToWorld(HandRenderer.ejectorRight);
		}
		double s = Damage.SCALE_BLOCKS;
		for (int i = 0; i < count; i++) {
			Shell sh = new Shell();
			sh.pos = origin.add(ejRight.multiply((i == 0 ? 0.075 : -0.075) * s));
			double speed = (8 + rnd.nextDouble() * 4) * s;
			sh.vel = ejFwd.multiply(1 / 1.75).add(ejUp.multiply(0.5)).add(0, 1 / 1.75, 0).multiply(speed)
				.add((rnd.nextDouble() - 0.5) * 0.4 * s, 0, (rnd.nextDouble() - 0.5) * 0.4 * s);
			sh.life = 2f;
			sh.spinSpeed = 540 + rnd.nextFloat() * 720;
			Vec3d axis = new Vec3d(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
			sh.ax = (float) axis.x; sh.ay = (float) axis.y; sh.az = (float) axis.z;
			shells.add(sh);
		}
		if (shells.size() > 120) shells.subList(0, shells.size() - 120).clear();
	}

	public static void clear() {
		shells.clear();
	}

	private static RigInstance rig() {
		if (!rigTried) {
			rigTried = true;
			UkRig r = UkAssets.rig("proj_shell");
			if (r != null) rig = new RigInstance("proj_shell", r);
		}
		return rig;
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null || shells.isEmpty()) return;
		RigInstance inst = rig();
		if (inst == null) return;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (ViewModel.paused()) dt = 0;
		double g = 9.81 * Tune.get("fx.shellGravity") * Damage.SCALE_BLOCKS;
		Vec3d camPos = e.getCamera().getPos();
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		float scale = (float) (Damage.SCALE_BLOCKS * Tune.get("fx.shellScale"));
		for (Iterator<Shell> it = shells.iterator(); it.hasNext(); ) {
			Shell sh = it.next();
			if (dt > 0) {
				sh.life -= dt;
				if (!sh.landed || sh.vel.lengthSquared() > 1e-4) {
					Vec3d next = sh.pos.add(sh.vel.multiply(dt));
					BlockHitResult hit = c.world.raycast(new RaycastContext(sh.pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, c.player));
					if (hit.getType() != HitResult.Type.MISS) {
						Vec3d n = Vec3d.of(hit.getSide().getVector());
						double vn = sh.vel.dotProduct(n);
						Vec3d tangent = sh.vel.subtract(n.multiply(vn));
						sh.vel = tangent.multiply(0.6).subtract(n.multiply(vn * 0.35));
						sh.pos = hit.getPos().add(n.multiply(0.02));
						if (!sh.landed) {
							sh.landed = true;
							UKSound.at(sh.pos, "uk.weapons.shellimpact", 0.5f, 0.85f + rnd.nextFloat() * 0.3f);
							sh.life = (float) Tune.get("fx.shellLife");
						}
						if (n.y > 0.5 && sh.vel.lengthSquared() < 0.02) sh.vel = Vec3d.ZERO;
						sh.spinSpeed *= 0.5f;
					} else {
						sh.pos = next;
						sh.vel = sh.vel.add(0, -g * dt, 0);
					}
					sh.spin += sh.spinSpeed * dt;
				}
			}
			if (sh.life <= 0 || sh.pos.y < c.world.getBottomY() - 10) { it.remove(); continue; }
			ms.push();
			ms.translate(sh.pos.x - camPos.x, sh.pos.y - camPos.y, sh.pos.z - camPos.z);
			ms.multiply(RotationAxis.of(new org.joml.Vector3f(sh.ax, sh.ay, sh.az)).rotationDegrees(sh.spin));
			ms.scale(scale, scale, scale);
			int light = c.world.getLightLevel(net.minecraft.util.math.BlockPos.ofFloored(sh.pos)) * 16;
			inst.poseAtOrigin(); // the prefab root sits at z 251.6 in its file
			inst.render(ms, buffers, net.minecraft.client.render.LightmapTextureManager.pack(light / 16, light / 16), OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
			ms.pop();
		}
		buffers.draw();
	}
}
