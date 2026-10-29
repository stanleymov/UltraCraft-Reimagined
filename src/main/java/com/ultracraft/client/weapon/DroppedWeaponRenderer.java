package com.ultracraft.client.weapon;

import com.ultracraft.client.Tune;
import com.ultracraft.client.UltraCraftClient;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * A dropped weapon (Q) as its 3D weapon model instead of the flat icon: the first-person rig at its Idle pose, without
 * the arm, centred and sized from its own bounds, bobbing and turning like any dropped item.
 */
public final class DroppedWeaponRenderer {
	private static final double MODEL = Tune.def("drop.model", 1, "1 = dropped weapons show their 3D weapon model, 0 = the flat item icon");
	private static final double SIZE = Tune.def("drop.size", 0.7, "dropped weapon: its longest side in blocks");
	private static final double LIFT = Tune.def("drop.lift", 0.12, "dropped weapon: height of its centre above the item's bob point, blocks");

	/** One posed rig per weapon model (separate from the animated first-person ones) and its gun-only bounds. */
	private static final Map<String, RigInstance> RIGS = new HashMap<>();
	private static final Map<String, float[]> BOUNDS = new HashMap<>();

	private DroppedWeaponRenderer() {}

	/** Draws the item if it is a weapon; false for anything else (the vanilla renderer goes on). */
	public static boolean render(ItemEntity item, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
		if (!Tune.on("drop.model")) return false;
		ItemStack stack = item.getStack();
		WeaponKind kind = UKWeaponItem.kindOf(stack);
		if (kind == null || !UkAssets.available()) return false;
		int variation = UKWeaponItem.variation(stack);
		boolean alt = UKWeaponItem.alternate(stack);
		String name = kind.rig(alt);
		RigInstance gun = rig(name);
		if (gun == null) return false;
		// what shows: the gun without the arm, the screens or the Sawed-On's chainsaw (unless the Sawed-On itself)
		gun.hiddenMeshes.clear();
		for (int i = 0; i < gun.rig.meshes.size(); i++) if (hidden(gun.rig.meshes.get(i).name, kind, variation, alt)) gun.hiddenMeshes.add(i);
		float[] b = BOUNDS.computeIfAbsent(name + "|" + gun.hiddenMeshes, k -> gun.boundsOfVisible());
		if (b == null) return false;
		float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
		float longest = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
		if (longest < 1e-5f) return false;
		float s = (float) (Tune.get("drop.size") / longest);
		// the variation colour on the revolvers' lights, as in hand
		int rgb = UltraCraftClient.VARIATION_COLORS[Math.min(2, Math.max(0, variation))];
		float vr = ((rgb >> 16) & 255) / 255f, vg = ((rgb >> 8) & 255) / 255f, vb = (rgb & 255) / 255f;
		if (kind == WeaponKind.REVOLVER && !alt) {
			gun.setEmissiveOverlay("Revolver_Body", "t_pistolnew_emissive", vr, vg, vb);
			gun.setEmissiveOverlay("Revolver_Cylinder", "t_pistolnew_emissive", vr, vg, vb);
		} else if (kind == WeaponKind.REVOLVER) {
			for (String m : new String[] {"MinosRevolver", "MinosRevolver_Cyllinder", "MinosRevolver_Hammer"})
				gun.setEmissiveOverlay(m, "t_minosrevolver_128_emissive", vr, vg, vb);
		}
		// ItemEntityRenderer: bob 0.1 + 0.1 sin, turning with the item's own rotation
		float age = item.getItemAge() + tickDelta;
		float bob = MathHelper.sin(age / 10f + item.uniqueOffset) * 0.1f + 0.1f;
		ms.push();
		ms.translate(0, bob + Tune.get("drop.lift"), 0);
		ms.multiply(RotationAxis.POSITIVE_Y.rotation(item.getRotation(tickDelta)));
		ms.scale(s, s, s);
		// the rig draws Unity z mirrored (x, y, -z): centre it in that space
		ms.translate(-cx, -cy, cz);
		gun.render(ms, buffers, light, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
		ms.pop();
		return true;
	}

	private static boolean hidden(String mesh, WeaponKind kind, int variation, boolean alt) {
		String n = mesh.toLowerCase();
		if (n.contains("arm")) return true;
		if (n.startsWith("monitor")) return true;
		// the nailgun's "Barrel_x (1)" heat shells (Basic Color - Orange at _Color.a = heat): a dropped gun is cold
		// (drawn whole they were red barrels: user)
		if (kind == WeaponKind.NAILGUN && !alt && mesh.endsWith("(1)")) return true;
		if (n.startsWith("chainsaw")) return !(kind == WeaponKind.SHOTGUN && !alt && variation == 2);
		return false;
	}

	private static RigInstance rig(String name) {
		if (RIGS.containsKey(name)) return RIGS.get(name);
		UkRig r = UkAssets.rig(name);
		RigInstance inst = r == null ? null : new RigInstance(name, r);
		if (inst != null) {
			// the Idle pose under the first-person holder chain: barrel forward, upright as in the hand
			// (the railcannon has no Idle state: it holds Equip's last frame)
			if (UkAssets.clip(name, "Idle") != null) inst.play("Idle", 0f, 0f);
			else if (UkAssets.clip(name, "Equip") != null) inst.play("Equip", 0f, 0f, UkAssets.clip(name, "Equip").length);
			inst.pose(ViewModel.mountMatrix(inst.rig, new Matrix4f()));
		}
		RIGS.put(name, inst);
		return inst;
	}

	/** Rigs are rebuilt when the assets reload. */
	public static void clear() {
		RIGS.clear();
		BOUNDS.clear();
	}
}
