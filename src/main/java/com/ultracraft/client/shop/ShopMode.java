package com.ultracraft.client.shop;

import com.ultracraft.block.TerminalBlockEntity;
import com.ultracraft.client.UKKeys;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.uk.RigInstance;
import com.ultracraft.client.v1.V1;
import com.ultracraft.client.weapon.WeaponClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/**
 * V1 at a Terminal (ShopZone / ScreenZone): inside the shop's trigger box (3 x 3 x 4 u, centred (0, -1.5, 2) in the
 * prefab root's space) the guns go away (GunControl.NoWeapon) and the arm points (FistControl.ShopMode: the arm's
 * shop layer, ShopIdle; a click plays ShopTap and presses the button under the crosshair; holding Fire2 zooms to half the
 * FOV). The music: the startup jingle (smileOS2Startup, 0.5) the first time in, then after 2.5 s the shop music (0.6,
 * looping) winding up from pitch 0 to 1 a second, and back down on the way out; Minecraft's music is held off meanwhile.
 */
public final class ShopMode {
	private static TerminalBlockEntity current;
	private static final Set<BlockPos> jingled = new HashSet<>();
	private static float musicPitch, musicWait;
	private static boolean musicOn, zooming;
	private static float idlePhase;

	private ShopMode() {}

	public static boolean active() {
		return current != null;
	}

	public static TerminalBlockEntity current() {
		return current;
	}

	/**
	 * VariationInfo: drawer.Play("Open") when a weapon's window comes on (OnEnable) and when its equipment changes
	 * (ChangeEquipment), and on a purchase (user, from the game); the Open clip's event at 0 s plays the drawer's
	 * ShopDrawerOpen (3D AudioSource, volume 0.25).
	 */
	public static void openDrawer() {
		TerminalBlockEntity be = current;
		if (be == null) return;
		be.drawerNanos = System.nanoTime();
		com.ultracraft.client.UKSound.at(be.getPos().toCenterPos(), "uk.shop.shopdraweropen", 0.25f, 1f);
	}

	/** Client tick. */
	public static void tick(MinecraftClient c) {
		TerminalBlockEntity in = null;
		if (c.player != null && c.world != null) {
			Vector3f body = new Vector3f((float) c.player.getX(), (float) (c.player.getY() + c.player.getHeight() / 2), (float) c.player.getZ());
			// (a world left behind - a dimension change, a respawn - keeps its terminals, never marked removed: the same
			// spot loaded again then held two, and the stale one, never drawn, could take the zone)
			TerminalBlockEntity.CLIENT.removeIf(be -> be.isRemoved() || be.getWorld() != c.world);
			for (TerminalBlockEntity be : TerminalBlockEntity.CLIENT) {
				if (be.getPos().getSquaredDistance(c.player.getPos()) > 64) continue;
				if (inZone(be, body)) { in = be; break; }
			}
		}
		if (in != current) {
			if (current != null) exit();
			if (in != null) enter(in);
			current = in;
		}
		float dt = 0.05f;
		if (current != null) {
			if (musicWait > 0) musicWait -= dt;
			else {
				musicOn = true;
				musicPitch = Math.min(1, musicPitch + dt);
			}
			c.getMusicTracker().stop();
			if (c.currentScreen == null && UKKeys.ATTACK.press) {
				RigInstance f = WeaponClient.INSTANCE.fistRig();
				if (f != null) f.play("ShopTap", 1, 0.041f); // Any State -> ShopTap: 0.041 s
				ShopScreen.State st = (ShopScreen.State) current.screen;
				if (st != null) ShopScreen.click(st, st.hover);
			}
			// FistControl: Fire2 held while shopping zooms to defaultFov / 2
			boolean z = c.currentScreen == null && UKKeys.USE.held;
			if (z != zooming) { zooming = z; V1.INSTANCE.zoomHeld = z; }
			// FistControl.ShopMode keeps the arm's shop layer up the whole time in the zone: back to the pointing pose
			// after a tap, or whenever anything else (a zoom, a fist swap) put the arm back to Idle
			RigInstance f = WeaponClient.INSTANCE.fistRig();
			if (f != null) {
				f.restClip = idleClip(f);
				String cur = f.currentClip();
				// the pointing pose is a slow 12 s loop: a tap returns to where it had got to (it kept going meanwhile), not
				// to its first frame, which sits lower (the arm came back and then dropped)
				com.ultracraft.client.uk.UkClip ic = com.ultracraft.client.uk.UkAssets.clip(f.rigName(), idleClip(f));
				if (cur != null && cur.equalsIgnoreCase(idleClip(f))) idlePhase = f.clipTime();
				else {
					com.ultracraft.client.uk.UkTransitions.State st = com.ultracraft.client.uk.UkTransitions.state(f.rigName(), idleClip(f));
					idlePhase += dt * (st != null ? st.speed() : 1f);
				}
				if (ic != null && ic.length > 0) idlePhase %= ic.length;
				f.restTime = idlePhase;
				boolean tapping = cur != null && cur.equalsIgnoreCase("ShopTap") && !f.finished();
				if (!tapping && (cur == null || !cur.equalsIgnoreCase(idleClip(f)))) f.play(idleClip(f), 1, 0.1f, idlePhase);
			}
		} else if (musicOn) {
			musicPitch = Math.max(0, musicPitch - dt);
			if (musicPitch <= 0) { musicOn = false; UKSound.stop("shop.music"); }
		}
		if (musicOn && musicPitch > 0) UKSound.music("shop.music", "uk.shop.shopmusic", 0.6f, musicPitch);
	}

	private static boolean inZone(TerminalBlockEntity be, Vector3f body) {
		Matrix4f m = TerminalRenderer.rootToBlock(be.getCachedState());
		if (m == null || ShopScreen.rootRS == null) return false;
		Matrix4f toLocal = new Matrix4f().translate(be.getPos().getX(), be.getPos().getY(), be.getPos().getZ()).mul(m).mul(ShopScreen.rootRS).invert();
		Vector3f l = toLocal.transformPosition(new Vector3f(body));
		return Math.abs(l.x) <= 1.5f && Math.abs(l.y + 1.5f) <= 1.5f && Math.abs(l.z - 2f) <= 2f;
	}

	/** The arm's pointing pose: the Feedbacker's ShopIdle, the Knuckleblaster's ShopPoint. */
	private static String idleClip(RigInstance f) {
		return "knuckleblaster".equals(f.rigName()) ? "ShopPoint" : "ShopIdle";
	}

	private static void enter(TerminalBlockEntity be) {
		RigInstance f = WeaponClient.INSTANCE.fistRig();
		if (f != null) f.play(idleClip(f), 1, 0.2f);
		idlePhase = 0;
		// the jingle only the first time this terminal is walked up to (ScreenZone.hasEntered)
		if (jingled.add(be.getPos().toImmutable())) {
			UKSound.play("uk.ui.smileos2startup", 0.5f, 1f);
			musicWait = 2.5f;
		} else musicWait = 0;
	}

	private static void exit() {
		// both arms: the fist may have been swapped while at the screen
		for (String r : new String[] {"feedbacker", "knuckleblaster"}) {
			RigInstance f = com.ultracraft.client.uk.ViewModel.instance(r);
			if (f != null) { f.restClip = null; f.restTime = 0; }
		}
		RigInstance f = WeaponClient.INSTANCE.fistRig();
		if (f != null) f.play("Idle", 1, 0.2f);
		if (zooming) { zooming = false; V1.INSTANCE.zoomHeld = false; }
	}
}
