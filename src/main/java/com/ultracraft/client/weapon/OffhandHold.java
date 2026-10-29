package com.ultracraft.client.weapon;

import com.ultracraft.UkConfig;
import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.item.UKWeaponItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * An item in the off-hand slot, held the way ULTRAKILL's arm holds a picked-up item (Punch: the Holding bool, the arm's
 * Hold clip, the item parented to the hand's "Holder (1)"): the arm stays up holding it, jabs become JabHolding and
 * coin flips CoinFlipHolding, and the item is drawn at the holder. In third person V1 holds it in its left hand. An
 * ULTRAKILL weapon there is a second gun instead (OffhandGun) unless that option is off.
 */
public final class OffhandHold {
	private OffhandHold() {}

	static {
		Tune.def("hold.debug", 0, "1 = log the arm's Holder and Hand positions each frame (to place the off-hand item)");
		Tune.def("hold.fpX", 0, "off-hand item in first person: offset from the arm's Holder, +x (game units; by eye)");
		Tune.def("hold.fpY", 0, "off-hand item in first person: offset +y (game units; by eye)");
		Tune.def("hold.fpZ", 0, "off-hand item in first person: offset +z (game units; by eye)");
		Tune.def("hold.fpRX", 0, "off-hand item in first person: turn about x, degrees (by eye)");
		Tune.def("hold.fpRY", 90, "off-hand item in first person: turn about y, degrees (by eye: its face to the camera)");
		Tune.def("hold.fpRZ", 0, "off-hand item in first person: turn about z, degrees (by eye)");
		Tune.def("hold.fpScale", 0.45, "off-hand item in first person: size (by eye: an item fits the open hand)");
		Tune.def("hold.fpMode", 2, "off-hand item in first person: 2 = every item's plain model (no per-item pose), all facing the same way, turned by hold.all*; 1 = Minecraft's own left-hand pose for each item (keys hold.mc*); 0 = the old pose (the item-frame pose, turned with the hand, keys hold.fp*); 1 and 2 sit at the arm's Holder facing the view");
		Tune.def("hold.allX", 0.08, "off-hand item, same pose for all (hold.fpMode 2): offset from the arm's Holder, +x right (game units; by eye: on the open palm)");
		Tune.def("hold.allY", -0.04, "off-hand item, same pose for all: offset +y up (game units; by eye)");
		Tune.def("hold.allZ", 0.15, "off-hand item, same pose for all: offset +z toward the camera (game units; by eye)");
		Tune.def("hold.allRX", 0, "off-hand item, same pose for all: turn about x, degrees (one turn for every item; by eye)");
		Tune.def("hold.allRY", 0, "off-hand item, same pose for all: turn about y, degrees (by eye)");
		Tune.def("hold.allRZ", 0, "off-hand item, same pose for all: turn about z, degrees (by eye)");
		Tune.def("hold.allScale", 0.6, "off-hand item, same pose for all: size (a plain model is one block; by eye)");
		for (String k : new String[] {"block", "trident", "shield", "lectern"}) {
			String what = k.equals("block") ? "3D models (blocks, anvils, fences, beds, shulker boxes ...: not flat icons; not heads, banners, shields, the lectern, chests)" : "the " + k;
			Tune.def("hold." + k + "X", 0, "off-hand item, same pose for all, extra for " + what + ": move +x right (game units, after hold.all*; by eye)");
			Tune.def("hold." + k + "Y", 0, "off-hand item, extra for " + what + ": move +y up (game units; by eye)");
			Tune.def("hold." + k + "Z", 0, "off-hand item, extra for " + what + ": move +z toward the camera (game units; by eye)");
			Tune.def("hold." + k + "RX", 0, "off-hand item, extra for " + what + ": turn about x, degrees (by eye)");
			Tune.def("hold." + k + "RY", 0, "off-hand item, extra for " + what + ": turn about y, degrees (by eye)");
			Tune.def("hold." + k + "RZ", 0, "off-hand item, extra for " + what + ": turn about z, degrees (by eye)");
			Tune.def("hold." + k + "Scale", 1, "off-hand item, extra for " + what + ": size, times hold.allScale (by eye)");
		}
		Tune.def("hold.mcX", 0.08, "off-hand item, Minecraft pose (hold.fpMode 1): offset from the arm's Holder, +x right (game units; by eye: on the open palm)");
		Tune.def("hold.mcY", -0.04, "off-hand item, Minecraft pose: offset +y up (game units; by eye: on the open palm)");
		Tune.def("hold.mcZ", 0.15, "off-hand item, Minecraft pose: offset +z toward the camera (game units; by eye: on the open palm)");
		Tune.def("hold.mcRX", 0, "off-hand item, Minecraft pose: extra turn about x, degrees (by eye)");
		Tune.def("hold.mcRY", 0, "off-hand item, Minecraft pose: extra turn about y, degrees (by eye)");
		Tune.def("hold.mcRZ", 0, "off-hand item, Minecraft pose: extra turn about z, degrees (by eye)");
		Tune.def("hold.mcScale", 1, "off-hand item, Minecraft pose: size (by eye)");
		Tune.def("hold.hideLights", 1, "1 = a torch or lantern in the off-hand is carried, not held: the arm stays down and it is not drawn (it still lights, e.g. a shader pack's held light; user)");
		Tune.def("hold.redSpeed", 0.2, "Knuckleblaster holding an off-hand item: speed of its Hold sway, x the game's (user: 0.2, by eye)");
		Tune.def("hold.follow", 1, "how much the off-hand item turns with the palm when the arm moves (parry, coin flip ...): 1 = fully, 0 = it keeps facing the view");
		Tune.def("hold.tpShow", 0, "1 = draw the off-hand item on the third-person V1 too (user: off, like the off-hand guns)");
		Tune.def("hold.tpX", 0, "off-hand item on the third-person V1: offset from its hand.L bone, +x (model units; by eye)");
		Tune.def("hold.tpY", 0, "off-hand item on the third-person V1: offset +y (model units; by eye)");
		Tune.def("hold.tpZ", 0, "off-hand item on the third-person V1: offset +z (model units; by eye)");
		Tune.def("hold.tpRX", 0, "off-hand item on the third-person V1: turn about x, degrees (by eye)");
		Tune.def("hold.tpRY", 0, "off-hand item on the third-person V1: turn about y, degrees (by eye)");
		Tune.def("hold.tpRZ", 0, "off-hand item on the third-person V1: turn about z, degrees (by eye)");
		Tune.def("hold.tpScale", 1, "off-hand item on the third-person V1: size (by eye)");
	}

	/** Whether the off-hand is held as an item (anything but an ULTRAKILL weapon drawn as a second gun). */
	public static boolean holdsItem(PlayerEntity p) {
		if (p == null) return false;
		ItemStack s = p.getOffHandStack();
		if (s.isEmpty() || (carriedLight(s) && Tune.on("hold.hideLights"))) return false;
		return !(UKWeaponItem.kindOf(s) != null && UkConfig.offhandWeapons());
	}

	/** Torches and lanterns: what is carried in the off-hand to light the way while exploring. */
	private static boolean carriedLight(ItemStack s) {
		return s.isOf(net.minecraft.item.Items.TORCH) || s.isOf(net.minecraft.item.Items.SOUL_TORCH) || s.isOf(net.minecraft.item.Items.REDSTONE_TORCH)
			|| s.isOf(net.minecraft.item.Items.LANTERN) || s.isOf(net.minecraft.item.Items.SOUL_LANTERN);
	}

	/** Per arm rig: the holder's turn once the arm has settled in Hold (the pose the item's live turn was set in). */
	private static final java.util.Map<String, Quaternionf> restTurn = new java.util.HashMap<>();
	private static String settlingRig;
	private static long holdSince = -1;

	/**
	 * The palm's turn away from its settled Hold pose, in the view's frame: the item turns with it (a parry's JabHolding,
	 * CoinFlipHolding, the Hold clip's own sway). The rest pose is taken per rig once the arm has been in Hold for 0.6 s.
	 */
	private static Quaternionf palmTurn(RigInstance fist, Matrix4f holder) {
		Quaternionf q = holder.getNormalizedRotation(new Quaternionf());
		q = new Quaternionf(-q.x, -q.y, q.z, q.w);
		long now = System.nanoTime();
		String rig = fist.rigName();
		if (!"Hold".equalsIgnoreCase(fist.currentClip()) || !rig.equals(settlingRig)) { holdSince = -1; settlingRig = rig; }
		else if (holdSince < 0) holdSince = now;
		else if (now - holdSince > 600_000_000L) restTurn.putIfAbsent(rig, new Quaternionf(q));
		Quaternionf rest = restTurn.get(rig);
		if (rest == null) return new Quaternionf();
		Quaternionf d = q.mul(rest.invert(new Quaternionf()), new Quaternionf());
		return new Quaternionf().slerp(d, (float) Math.max(0, Math.min(1, Tune.get("hold.follow"))));
	}

	/** The arm's clip for a punch / coin flip while holding (only the Feedbacker has a JabHolding). */
	public static String holding(RigInstance fist, String clip) {
		if (fist == null || !holdsItem(MinecraftClient.getInstance().player)) return clip;
		if (clip.startsWith("Jab")) return com.ultracraft.client.uk.UkAssets.clip(fist.rigName(), "JabHolding") != null ? "JabHolding" : clip;
		if (clip.equals("CoinFlip")) return com.ultracraft.client.uk.UkAssets.clip(fist.rigName(), "CoinFlipHolding") != null ? "CoinFlipHolding" : clip;
		return clip;
	}

	/**
	 * Client tick: the arm comes up into Hold while the off-hand holds an item (the cross-fade from Idle, which is
	 * below the frame, is the raise) and goes back down when it is emptied; one-shots return to Hold.
	 */
	public static void tick(PlayerEntity p, RigInstance fist) {
		if (fist == null || com.ultracraft.client.shop.ShopMode.active()) return;
		boolean hold = holdsItem(p);
		fist.restClip = hold ? "Hold" : null;
		String cur = fist.currentClip();
		if (hold && (cur == null || cur.equalsIgnoreCase("Idle"))) fist.play("Hold", 1, 0.25f);
		else if (!hold && "Hold".equalsIgnoreCase(cur)) fist.play("Idle", 1, 0.25f);
		// the Knuckleblaster's Hold sways up and down at hold.redSpeed of the game's rate (user: too fast, 0.2; live)
		if ("knuckleblaster".equals(fist.rigName()) && "Hold".equalsIgnoreCase(fist.currentClip())) {
			var st = com.ultracraft.client.uk.UkTransitions.state(fist.rigName(), "Hold");
			fist.setSpeed((st != null ? st.speed() : 1f) * (float) Tune.get("hold.redSpeed"));
		}
	}

	/**
	 * Onto a Unity (left-handed) bone frame the way RigInstance draws it, z mirrored, as a right-handed Minecraft frame:
	 * the mirror on both sides of the bone's rotation, its scale dropped.
	 */
	static void frame(MatrixStack ms, Matrix4f bone) {
		Vector3f t = bone.getTranslation(new Vector3f());
		Quaternionf q = bone.getNormalizedRotation(new Quaternionf());
		ms.translate(t.x, t.y, -t.z);
		ms.multiply(new Quaternionf(-q.x, -q.y, q.z, q.w));
	}

	/** The item at the arm's Holder, in the view model's space (`s`: game units to the view's). */
	public static void drawFirstPerson(PlayerEntity p, RigInstance fist, MatrixStack ms, VertexConsumerProvider buffers, int light, float s) {
		if (fist == null || !holdsItem(p)) return;
		Matrix4f holder = fist.boneWorldByName("Holder (1)");
		if (holder == null) return;
		if (Tune.on("hold.debug")) {
			Matrix4f hand = fist.boneWorldByName("Hand");
			com.ultracraft.UltraCraft.LOG.info("[hold] {} holder {} hand {} scale {}", fist.rigName(), holder.getTranslation(new Vector3f()), hand == null ? null : hand.getTranslation(new Vector3f()), s);
		}
		ms.push();
		ms.scale(s, s, s);
		int mode = (int) Tune.get("hold.fpMode");
		if (mode != 0) {
			// ULTRAKILL's items are modelled to sit in the hand at the holder unturned (Punch.ResetHeldItemPosition);
			// Minecraft's items say themselves how a left hand holds them (their first-person left-hand display), each
			// its own way (tools tilted, blocks at 45 degrees, lanterns upright...): mode 1 keeps that, mode 2 drops it
			// (NONE: the plain model, so one turn fits every item); facing the view, moved to the holder
			Vector3f t = holder.getTranslation(new Vector3f());
			ms.translate(t.x, t.y, -t.z);
			ms.multiply(palmTurn(fist, holder));
			place(ms, mode == 2 ? "all" : "mc");
			if (mode == 2) {
				// on top, for the items that are not flat icons: Minecraft's flat items (item/generated, handheld) are lit
				// from the front (gui_light front: not side-lit), its block models from the side; the ones drawn by code
				// (beds, chests, the trident) are 3D models too; heads, banners, shields, the lectern and chests keep the all pose (user: the
				// block turn has them the wrong way round)
				ItemStack st = p.getOffHandStack();
				var model = MinecraftClient.getInstance().getItemRenderer().getModel(st, p.getWorld(), p, p.getId());
				if (st.isOf(net.minecraft.item.Items.TRIDENT)) place(ms, "trident");
				else if (st.getItem() instanceof net.minecraft.item.ShieldItem) place(ms, "shield");
				else if (st.isOf(net.minecraft.item.Items.LECTERN)) place(ms, "lectern");
				else if ((model.isBuiltin() || model.isSideLit()) && !ownPose(st)) place(ms, "block");
			}
			MinecraftClient.getInstance().getItemRenderer().renderItem(p, p.getOffHandStack(), mode == 2 ? ModelTransformationMode.NONE : ModelTransformationMode.FIRST_PERSON_LEFT_HAND, true, ms, buffers, p.getWorld(), light, OverlayTexture.DEFAULT_UV, p.getId());
		} else {
			frame(ms, holder);
			place(ms, "fp");
			// (FIXED: the item centred on the holder, like ULTRAKILL's items sitting in the open hand)
			MinecraftClient.getInstance().getItemRenderer().renderItem(p, p.getOffHandStack(), ModelTransformationMode.FIXED, true, ms, buffers, p.getWorld(), light, OverlayTexture.DEFAULT_UV, p.getId());
		}
		ms.pop();
	}

	/** The item in the third-person V1's left hand (`hand` in the model's space, before RigInstance's z mirror). */
	public static void drawThirdPerson(PlayerEntity p, Matrix4f hand, MatrixStack ms, VertexConsumerProvider buffers, int light) {
		// (an ULTRAKILL weapon is never drawn on the third-person V1, in either hand)
		if (hand == null || p.getOffHandStack().isEmpty() || UKWeaponItem.kindOf(p.getOffHandStack()) != null) return;
		if (!Tune.on("hold.tpShow")) return;
		ms.push();
		frame(ms, hand);
		place(ms, "tp");
		MinecraftClient.getInstance().getItemRenderer().renderItem(p, p.getOffHandStack(), ModelTransformationMode.THIRD_PERSON_LEFT_HAND, true, ms, buffers, p.getWorld(), light, OverlayTexture.DEFAULT_UV, p.getId());
		ms.pop();
	}

	/** Heads, banners, shields, the lectern and chests (ender chests too): 3D, but not turned with the blocks (user's call, by eye). */
	private static boolean ownPose(ItemStack st) {
		var item = st.getItem();
		if (st.isOf(net.minecraft.item.Items.LECTERN)) return true;
		if (item instanceof net.minecraft.item.BannerItem || item instanceof net.minecraft.item.ShieldItem) return true;
		return item instanceof net.minecraft.item.BlockItem b && (b.getBlock() instanceof net.minecraft.block.AbstractSkullBlock || b.getBlock() instanceof net.minecraft.block.AbstractChestBlock);
	}

	private static void place(MatrixStack ms, String k) {
		ms.translate(Tune.get("hold." + k + "X"), Tune.get("hold." + k + "Y"), Tune.get("hold." + k + "Z"));
		ms.multiply(new Quaternionf().rotateXYZ((float) Math.toRadians(Tune.get("hold." + k + "RX")), (float) Math.toRadians(Tune.get("hold." + k + "RY")), (float) Math.toRadians(Tune.get("hold." + k + "RZ"))));
		float sc = (float) Tune.get("hold." + k + "Scale");
		ms.scale(sc, sc, sc);
	}
}
