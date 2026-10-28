package com.ultracraft.client.weapon;

import com.ultracraft.client.UKSound;
import com.ultracraft.entity.ProjectileKind;
import com.ultracraft.entity.UKProjectileEntity;
import com.ultracraft.weapon.Damage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The rockets' own sounds, client side: Rocket_0's Rocket AudioSource (RocketLoop, looping from the moment it is
 * fired, volume 0.2, pitch 0.5 - 1 once levelled up -, dopplerLevel 3, out to 40 u) following each rocket, and its
 * LevelUpEffect's Audio Source (scifi_glit_fx_007, volume 0.33, pitch 3) once a rocket frozen 1 s levels up.
 *
 * ULTRAKILL runs its sources through its own VirtualAudioFilter, Doppler included (UpdateVelocityBurst): the factor
 * is max(1e-6, (340 - dopplerFactor 1 x dopplerLevel x v) / 340), v the rocket's Rigidbody velocity "less" the
 * listener's along the line from the listener to it - the listener's comes out reversed (GetPositionDelta), so the two
 * add -; a factor at or under 0.1 is not taken (the last one holds), and one more than 2 away is approached at 2 a
 * second. A rocket flying off at 100 u/s drops to about 0.12 of its pitch; the one V1 rides, below and behind the
 * camera, goes up to about 1.3 and wanders as it turns and sinks (the user's recording: 0.75 to 1.3).
 */
public final class RocketSounds {
	/** rocket id -> {doppler pitch, which copy plays: 0 the clip, 1 an octave down, 2 two octaves down} */
	private static final Map<Integer, float[]> STATE = new HashMap<>();
	private static final Set<Integer> LEVELLED = new HashSet<>();
	private static Vec3d lastCam;
	// (the AudioSource's 0.2, measured against the user's recording: there the ride's loop sits 18 dB under a rocket shot,
	// here it sat 28 dB under - VirtualAudioFilter sums the two channels before panning, our mono copies average them)
	private static final double LOOP_VOLUME = com.ultracraft.client.Tune.def("proj.rocketLoopVolume", 0.62, "rocket thruster loop volume (game 0.2; 0.62 matches the user's ULTRAKILL recording's balance against the shot)");

	private RocketSounds() {}

	public static void tick(MinecraftClient client) {
		if (client.world == null) {
			for (int id : STATE.keySet()) stopLoops(id);
			STATE.clear();
			LEVELLED.clear();
			lastCam = null;
			return;
		}
		double toU = 20 / Damage.SCALE_BLOCKS;
		Vec3d cam = client.gameRenderer.getCamera().getPos();
		// the listener's velocity: V1's own (u/s; riding, the rocket's - the ridden one keeps its pitch), else the move since
		// the last tick (the camera's place sampled once a tick jittered, and the hum warbled with it)
		Vec3d vl;
		if (com.ultracraft.client.v1.V1Movement.isActive()) vl = com.ultracraft.client.v1.V1.INSTANCE.vel;
		else vl = lastCam == null ? Vec3d.ZERO : cam.subtract(lastCam).multiply(toU);
		lastCam = cam;
		Set<Integer> seen = new HashSet<>();
		for (Entity e : client.world.getEntities()) {
			if (!(e instanceof UKProjectileEntity r) || r.kind() != ProjectileKind.ROCKET || !r.isAlive()) continue;
			if (r.isLevelledUp() && LEVELLED.add(r.getId())) UKSound.at(r.getPos(), "uk.weapons.scifi_glit_fx_007", 0.33f, 3f);
			seen.add(r.getId());
			float[] st = STATE.computeIfAbsent(r.getId(), k -> new float[] {1, 0});
			// a frozen rocket's Rigidbody stands still (Grenade: velocity zero)
			Vec3d vs = r.isFrozen() ? Vec3d.ZERO : r.getVelocity().multiply(toU);
			Vec3d d = r.getPos().subtract(cam);
			double dist = d.length();
			// (VirtualAudioManager.GetPositionDelta returns (last - now) / dt: the listener's velocity comes out reversed,
			// so the game's "source minus listener" is source PLUS listener - riding, the two add up instead of cancelling)
			double radial = dist > 0 ? vs.add(vl).dotProduct(d) / dist : 0;
			float num = (float) Math.max(1e-6, (340 - radial * 3) / 340);
			if (Math.abs(num) > 0.1f) {
				if (Math.abs(st[0] - num) > 2) {
					float step = 2f * 0.05f;
					if (Math.abs(num - st[0]) <= step) st[0] += Math.signum(num - st[0]) * step;
				} else st[0] = num;
			}
			// pitch 0.5, 1 once levelled up (the LevelUpEffect's ObjectActivator: AudioPitchSetter.SetPitch(1)); it plays on
			// while frozen (nothing in Grenade stops it)
			float pitch = (r.isLevelledUp() ? 1f : 0.5f) * st[0];
			// Minecraft plays 0.5 to 2: the clip itself, an octave-down or a two-octaves-down copy (x 2, x 4), switched with
			// some overlap so a pitch near a border does not flip between them every tick
			int tier = (int) st[1];
			if (tier == 0 && pitch < 0.7f) tier = pitch < 0.35f ? 2 : 1;
			else if (tier == 1 && (pitch > 0.8f || pitch < 0.35f)) tier = pitch > 0.8f ? 0 : 2;
			else if (tier == 2 && pitch > 0.4f) tier = pitch > 0.8f ? 0 : 1;
			if (tier != (int) st[1]) stopLoops(r.getId());
			st[1] = tier;
			float mul = tier == 0 ? 1 : tier == 1 ? 2 : 4;
			UKSound.loopAt(key(r.getId(), tier), r.getPos(), "uk.weapons.rocketloop" + (tier == 0 ? "" : tier == 1 ? "_d2" : "_d4"), (float) com.ultracraft.client.Tune.get("proj.rocketLoopVolume"), Math.max(0.5f, Math.min(2f, pitch * mul)));
		}
		STATE.keySet().removeIf(id -> {
			if (seen.contains(id)) return false;
			stopLoops(id);
			return true;
		});
		// (gone rockets drop out of the level-up list too; a frozen one keeps its place there)
		LEVELLED.removeIf(id -> !(client.world.getEntityById(id) instanceof UKProjectileEntity r) || !r.isAlive());
	}

	private static void stopLoops(int id) {
		for (int tier = 0; tier < 3; tier++) UKSound.stop(key(id, tier));
	}

	private static String key(int id, int tier) {
		return "rocket." + id + "." + tier;
	}
}
