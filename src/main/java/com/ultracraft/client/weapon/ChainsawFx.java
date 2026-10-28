package com.ultracraft.client.weapon;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The Sawed-On's launched chainsaw stays chained to the gun: Chainsaw.cs keeps a LineRenderer from
 * `lineStartTransform` (the gun's ChainSawAttachPoint) to the saw, and its StoppedAudio plays the ChainsawLoop
 * (pitch 0.85 / volume 0.5 in flight, 1.1 / 0.75 while it is stopped in an enemy).
 */
public final class ChainsawFx {
	/** Chainsaw's LineRenderer: material Gradient ('gradient', a bright band across the width), colour 0.66 grey. */
	private static final Identifier WIRE = new Identifier(UltraCraft.MOD_ID, "textures/uk/gradient.png");
	/** View-space (Unity camera units) position of the Sawed-On's ChainSawAttachPoint this frame, or null; [1] the off-hand gun's. */
	public static Vector3f attachView, attachViewOff;
	private static boolean looping;

	static {
		Tune.def("fx.chainWidth", 0.1, "chainsaw chain width, u (LineRenderer widthMultiplier 0.1)");
	}

	private ChainsawFx() {}

	/** The attach point as the displays found it (the off-hand gun's mirrored to the left, where it is drawn). */
	static void attach(Matrix4f apm) {
		Vector3f v = apm == null ? null : apm.getTranslation(new Vector3f());
		if (!OffhandGun.running()) { attachView = v; return; }
		if (v != null) v.x = -v.x;
		attachViewOff = v;
	}

	private static List<UKProjectileEntity> ownSaws(MinecraftClient c) {
		return c.world.getEntitiesByClass(UKProjectileEntity.class, c.player.getBoundingBox().expand(96), e -> e.kind() == ProjectileKind.CHAINSAW && e.isAlive());
	}

	/** One of our chainsaws is out (WeaponCharges.shoSawAmount > 0). */
	public static boolean anyOut() {
		MinecraftClient c = MinecraftClient.getInstance();
		return c.world != null && c.player != null && !ownSaws(c).isEmpty();
	}

	/** Client tick: the saw's loop while one of ours is out. */
	public static void tick(MinecraftClient c) {
		if (c.world == null || c.player == null) { if (looping) { UKSound.stop("uk.weapons.chainsawloop"); looping = false; } return; }
		List<UKProjectileEntity> saws = ownSaws(c);
		if (saws.isEmpty()) {
			if (looping) { UKSound.stop("uk.weapons.chainsawloop"); looping = false; }
			return;
		}
		boolean stopped = saws.stream().anyMatch(UKProjectileEntity::isStuck);
		if (!looping) { UKSound.loop("uk.weapons.chainsawloop", 0.5f, 0.85f); looping = true; }
		UKSound.setLoop("uk.weapons.chainsawloop", stopped ? 0.75f : 0.5f, stopped ? 1.1f : 0.85f);
	}

	public static void onRenderLevel(RenderLevelStageEvent e) {
		if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.world == null || c.player == null) return;
		List<UKProjectileEntity> saws = ownSaws(c);
		if (saws.isEmpty()) return;
		Camera cam = e.getCamera();
		Vec3d camPos = cam.getPos();
		// each saw's chain starts at the attach point of the gun that threw it while that Sawed-On is in hand (first
		// person), else at the eye (Shotgun.OnDisable: lineStartTransform = the player)
		Vec3d eye = c.player.getEyePos().add(c.player.getRotationVec(e.getPartialTick()).multiply(0.3));
		boolean fp = c.options.getPerspective().isFirstPerson();
		Vec3d mainStart = fp && attachView != null && sawedOn(c.player.getMainHandStack()) ? HandRenderer.viewToWorld(attachView) : eye;
		Vec3d offStart = fp && attachViewOff != null && OffhandGun.active(c.player) && sawedOn(c.player.getOffHandStack()) ? HandRenderer.viewToWorld(attachViewOff) : eye;
		MatrixStack ms = e.getPoseStack();
		VertexConsumerProvider.Immediate buffers = c.getBufferBuilders().getEntityVertexConsumers();
		ms.push();
		ms.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f m = ms.peek().getPositionMatrix();
		VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(WIRE));
		for (UKProjectileEntity saw : saws) {
			Vec3d start = saw.offHand() ? offStart : mainStart;
			Vec3d end = saw.getLerpedPos(e.getPartialTick());
			Vec3d dir = end.subtract(start);
			double len = dir.length();
			if (len < 1e-4) continue;
			dir = dir.multiply(1 / len);
			Vec3d toCam = camPos.subtract(start);
			Vec3d side = dir.crossProduct(toCam);
			if (side.lengthSquared() < 1e-6) side = dir.crossProduct(new Vec3d(0, 1, 0));
			side = side.normalize().multiply(0.5 * Tune.get("fx.chainWidth") * Damage.SCALE_BLOCKS);
			// the LineRenderer has just two points (attach point and saw): a taut, straight chain
			quad(vc, m, start.add(side), start.subtract(side), end.subtract(side), end.add(side), 0, 1);
		}
		ms.pop();
		buffers.draw();
	}

	private static boolean sawedOn(net.minecraft.item.ItemStack s) {
		return UKWeaponItem.kindOf(s) == WeaponKind.SHOTGUN && UKWeaponItem.variation(s) == 2;
	}

	private static void quad(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, Vec3d c, Vec3d d, float u0, float u1) {
		// 'gradient' runs dark-bright-dark across v: v along the chain's width, u (unused) along it
		int g = 168; // 0.66
		vc.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(g, g, g, 255).texture(0.5f, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(g, g, g, 255).texture(0.5f, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(g, g, g, 255).texture(0.5f, 1).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
		vc.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(g, g, g, 255).texture(0.5f, 0).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 1, 0).next();
	}
}
