package com.ultracraft.client.shop;

import com.ultracraft.block.TerminalBlock;
import com.ultracraft.block.TerminalBlockEntity;
import com.ultracraft.client.Tune;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.uk.UkAssets;
import com.ultracraft.client.uk.UkRig;
import com.ultracraft.client.uk.ViewModel;
import com.ultracraft.weapon.Damage;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The Terminal: Shop.prefab's ShopTerminal and drawer (the prefab's own transforms, its placement in its level dropped)
 * at V1's scale, standing on the block and turned so the screen faces the way the block faces, and the screen
 * (ShopScreen) where the prefab puts its Canvas.
 */
public final class TerminalRenderer implements BlockEntityRenderer<TerminalBlockEntity> {
	private static RigInstance model;
	/** the model's bounds in the prefab root's space (posed): min x y z, max x y z */
	private static float[] bounds;

	static {
		Tune.def("shop.scale", Damage.SCALE_BLOCKS, "blocks per ULTRAKILL unit for the Terminal (from the game: 1.8 / 3.5, V1's height to Steve's)");
		Tune.def("shop.yaw", 0, "extra turn of the Terminal, degrees (if its screen faces the wrong way)");
	}

	public TerminalRenderer(BlockEntityRendererFactory.Context ctx) {}

	static RigInstance model() {
		if (model == null) {
			UkRig r = UkAssets.rig("shop_terminal");
			if (r == null) return null;
			model = new RigInstance("shop_terminal", r);
			for (int i = 0; i < r.meshes.size(); i++) if (r.meshes.get(i).name.equals("ShopTerminalProxy")) model.hiddenMeshes.add(i);
			// M_ShopTerminal's emission (the lit STORE sign, the status lights): _EmissiveColor white, added on
			for (String mesh : new String[] {"ShopTerminal", "ShopTerminal_Drawer"}) model.setEmissiveOverlay(mesh, "t_shopterminal_emission", 1f, 1f, 1f, 1f, true);
			model.poseAtOrigin();
			bounds = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (UkRig.Mesh m : r.meshes) {
				if (m.name.equals("ShopTerminalProxy") || m.bones.length == 0) continue;
				Matrix4f w = model.boneWorld(r.bones.get(m.bones[0]).path());
				if (w == null) continue;
				for (int i = 0; i + 2 < m.vertices.length; i += 3) {
					Vector4f v = new Vector4f(m.vertices[i], m.vertices[i + 1], m.vertices[i + 2], 1).mul(w);
					bounds[0] = Math.min(bounds[0], v.x); bounds[1] = Math.min(bounds[1], v.y); bounds[2] = Math.min(bounds[2], v.z);
					bounds[3] = Math.max(bounds[3], v.x); bounds[4] = Math.max(bounds[4], v.y); bounds[5] = Math.max(bounds[5], v.z);
				}
			}
		}
		return model;
	}

	/**
	 * The prefab root's space (posed: root rotation and scale kept) into the block's (origin at its corner): the model
	 * centred on the block, standing on its floor, mirrored into Minecraft's axes, and turned so the screen (the canvas
	 * faces its -z) looks the way the block faces.
	 */
	public static Matrix4f rootToBlock(BlockState state) {
		if (model() == null || !ShopScreen.ready()) return null;
		float k = (float) Tune.get("shop.scale");
		Matrix4f mirror = new Matrix4f().scale(k, k, -k).translate(-(bounds[0] + bounds[3]) / 2, -bounds[1], -(bounds[2] + bounds[5]) / 2);
		Vector3f n = new Matrix4f(mirror).mul(ShopScreen.canvasFromRoot).transformDirection(new Vector3f(0, 0, -1));
		Direction facing = state.contains(TerminalBlock.FACING) ? state.get(TerminalBlock.FACING) : Direction.SOUTH;
		float want = (float) Math.atan2(facing.getOffsetX(), facing.getOffsetZ()), have = (float) Math.atan2(n.x, n.z);
		return new Matrix4f().translate(0.5f, 0, 0.5f).rotateY(want - have + (float) Math.toRadians(Tune.get("shop.yaw"))).mul(mirror);
	}

	/** The screen's canvas space into the block's. */
	public static Matrix4f canvasToBlock(BlockState state) {
		Matrix4f m = rootToBlock(state);
		return m == null ? null : m.mul(ShopScreen.canvasFromRoot);
	}

	/**
	 * ShopTerminal_Drawer's animator (ShopTerminal_Drawer.controller): Idle holds it shut at -89.98 deg about x; Open
	 * (ShopDrawerOpen.anim, 1 s, then back to Idle) swings it out to -49.98 by 1/6 s, bounces to -55 and back by 1/3 s,
	 * stays out until 2/3 s and shuts by 1 s; flat tangents, so each stretch eases in and out.
	 */
	private static final float[] DRAWER_T = {0f, 1f / 6f, 0.25f, 1f / 3f, 2f / 3f, 1f};
	private static final float[] DRAWER_X = {-89.98f, -49.98f, -55f, -49.98f, -49.98f, -89.98f};
	private static final String DRAWER = "ShopTerminal/ShopTerminal_Drawer";

	/** The drawer's turn about x, degrees, `t` seconds into Open (shut outside it). */
	static float drawerAngle(float t) {
		if (t <= 0 || t >= 1) return DRAWER_X[0];
		int k = 0;
		while (k + 2 < DRAWER_T.length && t >= DRAWER_T[k + 1]) k++;
		float u = (t - DRAWER_T[k]) / (DRAWER_T[k + 1] - DRAWER_T[k]);
		float h = u * u * (3 - 2 * u);
		return DRAWER_X[k] + (DRAWER_X[k + 1] - DRAWER_X[k]) * h;
	}

	/** Poses the shared model with the drawer `drawerNanos` into its Open (0: shut). */
	static void poseDrawer(long drawerNanos) {
		float t = drawerNanos == 0 ? -1 : (System.nanoTime() - drawerNanos) / 1e9f;
		float a = drawerAngle(t);
		// the bind pose is the shut turn (-89.98 about x): the extra turn on top of it about the same axis
		model.setExtraRotation(DRAWER, a == DRAWER_X[0] ? null : new org.joml.Quaternionf().rotateX((float) Math.toRadians(a - DRAWER_X[0])));
		model.poseAtOrigin();
	}

	@Override
	public void render(TerminalBlockEntity be, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light, int overlay) {
		Matrix4f m = rootToBlock(be.getCachedState());
		if (m == null) return;
		ms.push();
		ms.multiplyPositionMatrix(m);
		poseDrawer(be.drawerNanos);
		drawModel(ms, buffers, light);
		ms.multiplyPositionMatrix(ShopScreen.canvasFromRoot);
		if (be.screen == null) be.screen = ShopScreen.newState();
		ShopScreen.State st = (ShopScreen.State) be.screen;
		// ShopZone.TurnOn / TurnOff: the canvas is only on while V1 is in the zone (its windows open again each time)
		if (st != null && ShopMode.current() == be) {
			st.hover = pointer(be, m);
			ShopScreen.draw(st, ms, buffers);
		} else ShopScreen.off(st);
		ms.pop();
	}

	/**
	 * The model in the prefab root's space: RigInstance draws Unity's z mirrored on its own and rootToBlock mirrors
	 * too, so that z is turned back first (or the terminal comes out mirrored, its screen side at the back).
	 */
	static void drawModel(MatrixStack ms, VertexConsumerProvider buffers, int light) {
		ms.push();
		ms.scale(1, 1, -1);
		model.render(ms, buffers, light, OverlayTexture.DEFAULT_UV, ViewModel.TEXTURES, false);
		ms.pop();
	}

	/** ControllerPointer: the button the camera's centre ray meets on the screen, or -1. */
	private static int pointer(TerminalBlockEntity be, Matrix4f rootToBlock) {
		MinecraftClient c = MinecraftClient.getInstance();
		Vec3d cam = c.gameRenderer.getCamera().getPos();
		Vector3f look = new Vector3f(c.gameRenderer.getCamera().getHorizontalPlane());
		Matrix4f toCanvas = new Matrix4f().translate(be.getPos().getX(), be.getPos().getY(), be.getPos().getZ()).mul(rootToBlock).mul(ShopScreen.canvasFromRoot).invert();
		Vector3f o = toCanvas.transformPosition(new Vector3f((float) cam.x, (float) cam.y, (float) cam.z));
		Vector3f d = toCanvas.transformDirection(new Vector3f(look));
		if (Math.abs(d.z) < 1e-6f) return -1;
		float t = -o.z / d.z;
		if (t <= 0) return -1;
		return ShopScreen.hit((ShopScreen.State) be.screen, o.x + d.x * t, o.y + d.y * t);
	}

	@Override
	public boolean rendersOutsideBoundingBox(TerminalBlockEntity be) {
		return true;
	}
}
