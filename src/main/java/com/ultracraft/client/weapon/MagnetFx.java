package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.ultracraft.weapon.Damage;
import com.ultracraft.weapon.MagnetField;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A stuck magnet's TimeBomb on this side: Beep() at the start and then each time a sixth of the time left has gone
 * (so they come faster and faster, every frame at the end), each beep resetting the Beeper (the muzzleflashnailgun
 * sprite, Additive _TintColor 0.25, at the harpoon's root: the spike's tail) to beeperSize 200 and playing Beep_high
 * (0.2, pitch 0.65); every frame it shrinks toward nothing (Lerp x 5 dt). The server's fuse is the clock (FUSE_AT).
 */
public final class MagnetFx {
	private static final Identifier SPRITE = new Identifier(UltraCraft.MOD_ID, "textures/uk/muzzleflashnailgun.png");
	/** The same star in white, for a tinted beeper (magnet.beepWhite). */
	private static final Identifier SPRITE_WHITE = new Identifier(UltraCraft.MOD_ID, "textures/uk/muzzleflashwhite.png");

	private static final class Beeper {
		int beeps;
		float scale;
	}

	private static final Map<Integer, Beeper> beepers = new HashMap<>();
	private static long lastNanos;

	static {
		Tune.def("magnet.beepSize", 200, "beeper size at each beep (TimeBomb.beeperSize; the 32 px sprite at 100 px/u under the 0.1 root)");
		Tune.def("magnet.beepBright", 1, "beeper brightness (Additive 2 x _TintColor 0.25 = 0.5; by eye 1, the game's alpha is doubled too)");
		Tune.def("magnet.beepWhite", 0, "1 = the white star instead of muzzleflashnailgun (still green to red by load)");
		Tune.def("magnet.beepBillboard", 0, "1 = beeper faces the camera; 0 = flat across the top of the spike like the game's sprite");
		Tune.def("magnet.beepVolume", 0.2, "beep volume (Beeper AudioSource 0.2)");
	}

	private MagnetFx() {}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != com.ultracraft.client.render.UkLayers.depthlessStage()) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null) { beepers.clear(); return; }
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		if (ViewModel.paused() || com.ultracraft.client.v1.V1.INSTANCE.frozen()) dt = 0;
		float td = e.getPartialTick();
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		MatrixStack ms = com.ultracraft.client.render.UkLayers.worldStack(e);
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vc = null;
		Set<Integer> seen = new HashSet<>();
		double fuse = MagnetField.FUSE;
		for (Entity en : c.world.getEntities()) {
			if (!(en instanceof UKProjectileEntity m) || m.kind() != ProjectileKind.MAGNET || !m.isStuck() || m.fuseAt() < 0) continue;
			int id = m.getId();
			seen.add(id);
			double left = fuse - (c.world.getTime() - m.fuseAt() + td) * 0.05;
			// the k-th beep comes when the time left is 12 x (5/6)^k
			int due = left <= 0.001 ? Integer.MAX_VALUE : 1 + (int) Math.floor(Math.log(left / fuse) / Math.log(5.0 / 6.0));
			Beeper b = beepers.computeIfAbsent(id, k -> new Beeper());
			Vec3d root = m.getLerpedPos(td);
			if (due > b.beeps && dt > 0) {
				b.beeps = due;
				// Magnet.Update: size x (1 + load), pitch load / 2 + 0.25 (Minecraft plays nothing under 0.5)
				b.scale = (float) Tune.get("magnet.beepSize") * (1 + m.magnetLoadShown());
				// (aud.Play() on the one Beeper AudioSource: a beep cuts off the one before)
				UKSound.onceAt("magnetbeep" + id, root, "uk.ui.beep_high", (float) Tune.get("magnet.beepVolume"), m.magnetLoadShown() / 2 + 0.25f);
			}
			b.scale *= Math.max(0, 1 - 5 * dt);
			if (b.scale < 0.5f) continue;
			if (vc == null) vc = buffers.getBuffer(com.ultracraft.client.render.UkLayers.glow(Tune.on("magnet.beepWhite") ? SPRITE_WHITE : SPRITE));
			// 0.32 u sprite x scale under the root's 0.1
			float half = (float) (0.32 * b.scale * 0.1 * Damage.SCALE_BLOCKS / 2);
			Vec3d f = MagnetField.forward(m.getYaw(), m.getPitch());
			Vec3d a, up;
			if (Tune.on("magnet.beepBillboard")) {
				org.joml.Vector3f h = cam.getHorizontalPlane(), v = cam.getVerticalPlane();
				up = new Vec3d(v.x, v.y, v.z);
				a = new Vec3d(h.x, h.y, h.z).crossProduct(up).normalize();
			} else {
				Vec3d t = Math.abs(f.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
				a = f.crossProduct(t).normalize();
				up = f.crossProduct(a).normalize();
			}
			// Magnet.Update: beeperColor = Color.Lerp(green, red, load)
			float br = (float) Tune.get("magnet.beepBright"), load = m.magnetLoadShown();
			float r = br * load, g = br * (1 - load), bl = 0;
			ms.push();
			ms.translate(root.x - camPos.x, root.y - camPos.y, root.z - camPos.z);
			Matrix4f mat = ms.peek().getPositionMatrix();
			Vec3d ax = a.multiply(half), ux = up.multiply(half);
			v(vc, mat, ax.negate().subtract(ux), 0, 1, r, g, bl);
			v(vc, mat, ax.subtract(ux), 1, 1, r, g, bl);
			v(vc, mat, ax.add(ux), 1, 0, r, g, bl);
			v(vc, mat, ax.negate().add(ux), 0, 0, r, g, bl);
			ms.pop();
		}
		beepers.keySet().removeIf(id -> {
			if (seen.contains(id)) return false;
			UKSound.stop("magnetbeep" + id);
			return true;
		});
		if (vc != null) buffers.draw();
	}

	private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float u, float w, float r, float g, float b) {
		vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, 1f).texture(u, w).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
