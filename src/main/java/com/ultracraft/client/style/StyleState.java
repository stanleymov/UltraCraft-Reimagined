package com.ultracraft.client.style;

import com.ultracraft.UltraCraft;
import com.ultracraft.client.Tune;
import com.ultracraft.client.UKSound;
import com.ultracraft.client.v1.V1;
import com.ultracraft.client.weapon.WeaponClient;
import com.ultracraft.item.RevolverItem;
import com.ultracraft.net.HitPayload;
import com.ultracraft.net.Net;
import com.ultracraft.net.PointsPayload;
import com.ultracraft.net.PointsSyncPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of StyleHUD + StyleCalculator (+ the bits of GunControl / NewMovement that feed them): the rank
 * meter, freshness per weapon, the style entry list, the air-time multiplier, multikills, headshot
 * combos, and P (StatsManager.stylePoints). Runs on the client at 20 Hz; P deltas go to the server to be
 * persisted (see WeaponServer.onPoints).
 */
public final class StyleState {
	public static final StyleState INSTANCE = new StyleState();
	public static final Identifier FONT = new Identifier(UltraCraft.MOD_ID, "vcr");
	public static final Style HUD_STYLE = Style.EMPTY.withFont(FONT);
	private static final float DT = 0.05f;

	/** StyleHUD.ranks from the Player prefab: meter size and drain speed (×15 per second). */
	public record Rank(String name, String sprite, int maxMeter, float drainSpeed) {}
	public static final Rank[] RANKS = {
		new Rank("D", "rankd", 200, 1f), new Rank("C", "rankc", 300, 1.25f), new Rank("B", "rankb", 400, 1.5f),
		new Rank("A", "ranka", 500, 2f), new Rank("S", "ranks", 700, 3f), new Rank("SS", "rankss", 850, 4f),
		new Rank("SSS", "ranksss", 1000, 6f), new Rank("ULTRAKILL", "ranku", 1500, 8f)};

	/** StyleFreshnessData: state, text, multiplier, min, max. */
	public record Freshness(String name, String text, float mult, float min, float max) {
		float justAboveMin() {
			return min + Math.abs(max - min) * 0.05f;
		}
	}
	public static final Freshness DULL = new Freshness("DULL", "DULL: 0.00x", 0f, 0f, 1f);
	public static final Freshness STALE = new Freshness("STALE", "STALE: 0.50x", 0.5f, 1f, 5f);
	public static final Freshness USED = new Freshness("USED", "USED: 1.00x", 1f, 5f, 7.5f);
	public static final Freshness FRESH = new Freshness("FRESH", "FRESH: 1.50x", 1.5f, 7.5f, 10f);
	private static final Freshness[] FRESHNESS = {DULL, STALE, USED, FRESH};

	// prefab values (StyleHUD)
	private static final float DECAY_PER_MOVE = 0.7f, DECAY_PER_SEC = 0f, REGEN_PER_MOVE = 0.5f, REGEN_PER_SEC = 0.1f;
	private static final Map<String, Float> DECAY_MULT = Map.ofEntries(
		Map.entry("ultrakill.shotgunhit", 0.15f), Map.entry("ultrakill.nailhit", 0.1f), Map.entry("ultrakill.explosionhit", 0.75f),
		Map.entry("ultrakill.exploded", 1.25f), Map.entry("ultrakill.kill", 0.3f), Map.entry("ultrakill.firehit", 0f),
		Map.entry("ultrakill.quickdraw", 0f), Map.entry("ultrakill.projectileboost", 0f), Map.entry("ultrakill.doublekill", 0.1f),
		Map.entry("ultrakill.triplekill", 0.1f), Map.entry("ultrakill.multikill", 0.1f), Map.entry("ultrakill.arsenal", 0f),
		Map.entry("ultrakill.drillhit", 0.025f), Map.entry("ultrakill.drillpunch", 1f), Map.entry("ultrakill.drillpunchkill", 1f),
		Map.entry("ultrakill.hammerhit", 3f), Map.entry("ultrakill.hammerhitheavy", 6f), Map.entry("ultrakill.hammerhitred", 8f),
		Map.entry("ultrakill.hammerhityellow", 5f), Map.entry("ultrakill.hammerhitgreen", 3f));
	private static final Map<String, String> NAMES = new HashMap<>();
	static {
		String[][] n = {
			{"ultrakill.kill", "KILL"}, {"ultrakill.doublekill", "<color=orange>DOUBLE KILL</color>"}, {"ultrakill.triplekill", "<color=orange>TRIPLE KILL</color>"},
			{"ultrakill.bigkill", "BIG KILL"}, {"ultrakill.bigfistkill", "BIG FISTKILL"}, {"ultrakill.headshot", "HEADSHOT"},
			{"ultrakill.bigheadshot", "BIG HEADSHOT"}, {"ultrakill.headshotcombo", "<color=#00ffffff>HEADSHOT COMBO</color>"},
			{"ultrakill.criticalpunch", "CRITICAL PUNCH"}, {"ultrakill.ricoshot", "<color=#00ffffff>RICOSHOT</color>"}, {"ultrakill.limbhit", "LIMB HIT"},
			{"ultrakill.quickdraw", "<color=#00ffffff>QUICKDRAW</color>"}, {"ultrakill.interruption", "<color=green>INTERRUPTION</color>"},
			{"ultrakill.fistfullofdollar", "<color=#00ffffff>FISTFUL OF DOLLAR</color>"}, {"ultrakill.projectileboost", "<color=green>PROJECTILE BOOST</color>"},
			{"ultrakill.parry", "<color=green>PARRY</color>"}, {"ultrakill.chargeback", "CHARGEBACK"}, {"ultrakill.disrespect", "DISRESPECT"},
			{"ultrakill.groundslam", "GROUND SLAM"}, {"ultrakill.overkill", "OVERKILL"}, {"ultrakill.friendlyfire", "FRIENDLY FIRE"},
			{"ultrakill.exploded", "EXPLODED"}, {"ultrakill.fried", "FRIED"}, {"ultrakill.multikill", "<color=orange>MULTIKILL</color>"},
			{"ultrakill.airshot", "<color=#00ffffff>AIRSHOT</color>"}, {"ultrakill.airslam", "<color=#00ffffff>AIR SLAM</color>"},
			{"ultrakill.hammerhit", ""}, {"ultrakill.hammerhitheavy", "BLASTING AWAY"}, {"ultrakill.hammerhitred", "FULL IMPACT"}, {"ultrakill.hammerhityellow", "HEAVY HITTER"}, {"ultrakill.hammerhitgreen", "BLUNT FORCE"},
			{"ultrakill.shotgunhit", ""}, {"ultrakill.nailhit", ""}, {"ultrakill.explosionhit", ""}, {"ultrakill.firehit", ""}, {"ultrakill.zapperhit", ""}};
		for (String[] e : n) NAMES.put(e[0], e[1]);
	}

	/** One line of the style list ("+ KILL"), newest first. */
	public static final class Entry {
		public final Text text;
		public float age;
		Entry(Text text) {
			this.text = text;
		}
	}

	// meter
	public float currentMeter;
	public int rankIndex;
	public boolean comboActive;
	public float rankShaking, rankScale;
	private float resetFreshnessIn = -1;
	public int maxReachedRank;
	// calculator
	public float airTime = 1;
	public float multikillTimer;
	public int multikillCount;
	public int headshots;
	public float headShotComboTime;
	// freshness
	private final Map<String, Float> weaponFreshness = new HashMap<>();
	private final Set<String> ownedWeapons = new LinkedHashSet<>();
	public String currentWeapon;
	public float freshnessSliderValue = 10;
	// entries
	public final List<Entry> entries = new ArrayList<>();
	private final List<Text> queue = new ArrayList<>();
	private float queueWait;
	// P
	public int stylePoints;
	private int pendingPoints;
	private float sendTimer;
	private float lastHealth = -1;

	private StyleState() {
		Tune.def("style.bigHp", 50, "mobs with at least this max health count as big enemies (BIG KILL etc.)");
		Tune.def("style.lossMultiplier", 1, "NewMovement.scoreLossMultiplier: >0.5 drops a rank on damage (Standard), 0 = none (Harmless)");
	}

	public void reset() {
		currentMeter = 0;
		rankIndex = 0;
		comboActive = false;
		airTime = 1;
		multikillCount = 0;
		multikillTimer = 0;
		headshots = 0;
		entries.clear();
		queue.clear();
		weaponFreshness.clear();
		lastHealth = -1;
	}

	public Rank rank() {
		return RANKS[rankIndex];
	}

	/** The P earned and not yet reported goes to the server now (before a purchase is weighed against the total). */
	public void flushPoints() {
		if (pendingPoints != 0) {
			Net.toServer(new PointsPayload(pendingPoints, false));
			pendingPoints = 0;
			sendTimer = 0;
		}
	}

	public void onPointsSync(PointsSyncPayload msg) {
		stylePoints = msg.total();
	}

	// ------------------------------------------------------------------ tick

	public void tick(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null) return;
		com.ultracraft.weapon.Damage.bigHp = Tune.get("style.bigHp");
		V1 v = V1.INSTANCE;
		// V1's hp is the player's health ×5 (100 = 20 HP); absorption shows as overheal
		v.hp = Math.round((p.getHealth() + p.getAbsorptionAmount()) * 5);
		if (lastHealth >= 0 && p.getHealth() < lastHealth - 0.01f) hurt(Math.round((lastHealth - p.getHealth()) * 5));
		lastHealth = p.getHealth();

		updateWeapons(p);
		// StyleCalculator.Update: air time multiplier
		if (!v.onGround || v.sliding) airTime = moveTowards(airTime, 3f, DT * 2f);
		else if (!v.boost) airTime = moveTowards(airTime, 1f, DT * 10f);
		if (multikillTimer > 0) multikillTimer -= DT * 10f;
		else if (multikillCount != 0) { multikillTimer = 0; multikillCount = 0; }
		// GunControl.Update: headshot combo window
		if (headShotComboTime > 0) headShotComboTime -= DT;
		else headshots = 0;
		// StyleHUD.UpdateMeter
		if (currentMeter > 0 && !comboActive) comboStart();
		if (currentMeter < 0) descendRank();
		else currentMeter -= DT * (rank().drainSpeed * 15f);
		// UpdateFreshness
		if (comboActive) {
			for (String w : ownedWeapons) addFreshness(w, w.equals(currentWeapon) ? -DECAY_PER_SEC * DT : REGEN_PER_SEC * DT);
		}
		if (resetFreshnessIn > 0) {
			resetFreshnessIn -= DT;
			if (resetFreshnessIn <= 0) resetFreshness();
		}
		// UpdateHUD
		if (currentWeapon != null) freshnessSliderValue = lerp(freshnessSliderValue, freshness(currentWeapon), Math.min(1, 30f * DT));
		if (rankShaking > 0) rankShaking -= DT * 5f;
		if (rankScale > 0) rankScale -= DT;
		// UpdateItems: one queued entry per 0.05 s, each lives 3 s
		if (queueWait > 0) queueWait -= DT;
		if (!queue.isEmpty() && queueWait <= 0) {
			entries.add(0, new Entry(queue.remove(0)));
			UKSound.play("uk.ui.countingclick", 0.35f, 2f); // StyleCanvas AudioSource: countingClick, pitch 3 (clamped to 2 by the engine)
			queueWait = 0.05f;
		}
		for (int i = entries.size() - 1; i >= 0; i--) {
			Entry e = entries.get(i);
			e.age += DT;
			if (e.age >= 3f) entries.remove(i);
		}
		// P to the server
		sendTimer += DT;
		if (pendingPoints != 0 && sendTimer >= 1f) {
			Net.toServer(new PointsPayload(pendingPoints, false));
			pendingPoints = 0;
			sendTimer = 0;
		}
	}

	/** gc.allWeapons: every ULTRAKILL weapon (variation) the player carries; fists are not weapons here. */
	private void updateWeapons(ClientPlayerEntity p) {
		ownedWeapons.clear();
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			com.ultracraft.item.WeaponKind k = com.ultracraft.item.UKWeaponItem.kindOf(s);
			if (k != null) ownedWeapons.add(k.id(com.ultracraft.item.UKWeaponItem.variation(s)) + (RevolverItem.alternate(s) ? "a" : ""));
		}
		for (String w : ownedWeapons) weaponFreshness.putIfAbsent(w, 10f);
		ItemStack held = p.getMainHandStack();
		com.ultracraft.item.WeaponKind hk = com.ultracraft.item.UKWeaponItem.kindOf(held);
		currentWeapon = hk == null ? null : hk.id(com.ultracraft.item.UKWeaponItem.variation(held)) + (RevolverItem.alternate(held) ? "a" : "");
	}

	// ------------------------------------------------------------------ events

	/** StyleCalculator.HitCalculator + the per-weapon extras (headshot combo, ricoshot, parry). */
	public void onHit(HitPayload h) {
		boolean dead = h.kind() == HitPayload.KILL;
		String weapon = switch (h.hitter()) { case HitPayload.PUNCH, HitPayload.HEAVYPUNCH, HitPayload.GROUNDSLAM -> null; default -> currentWeapon; };
		boolean big = h.big();
		String limb = h.head() ? "head" : "body";
		switch (h.kind()) {
			case HitPayload.ENEMY_HIT, HitPayload.KILL -> {
				switch (h.hitter()) {
					case HitPayload.PUNCH, HitPayload.HEAVYPUNCH -> {
						if (dead) {
							if (limb.equals("head")) addPoints(60, "ultrakill.criticalpunch", null, -1, "");
							else if (big) addPoints(150, "ultrakill.bigfistkill", null, -1, "");
							else addPoints(30, "ultrakill.kill", null, -1, "");
						} else if (big) addPoints(60, "ultrakill.disrespect", null, -1, "");
						else addPoints(20, "", null, -1, "");
					}
					case HitPayload.GROUNDSLAM -> {
						if (dead) addPoints(60, "ultrakill.groundslam", null, -1, "");
						else addPoints(20, "", null, -1, "");
					}
					case HitPayload.COIN -> {
						// a coin's reflected beam: Coin.cs delivers damage with crit 1 and the ricoshot entry (RICOSHOT payload)
						if (dead) {
							if (limb.equals("head") && big) addPoints(150, "ultrakill.bigheadshot", weapon, -1, "");
							else if (limb.equals("head")) addPoints(80, "ultrakill.headshot", weapon, -1, "");
							else if (big) addPoints(100, "ultrakill.bigkill", weapon, -1, "");
							else addPoints(30, "ultrakill.kill", weapon, -1, "");
						}
					}
					case HitPayload.SHOTGUN -> {
						if (dead) addPoints(big ? 100 : 45, big ? "ultrakill.bigkill" : "ultrakill.kill", weapon, -1, "");
						else addPoints(4, "ultrakill.shotgunhit", weapon, -1, "");
					}
					case HitPayload.SHOTGUNZONE -> {
						if (dead) addPoints(big ? 125 : 100, big ? "ultrakill.bigkill" : "ultrakill.overkill", weapon, -1, "");
					}
					case HitPayload.HAMMER -> {
						// ShotgunHammer.DeliverDamage: kill → green 80 / yellow 120 / red 240 by tier; hit → hammerhit 40·(tier+1), tier 2 → hammerhitheavy 120
						int tier = h.tier();
						if (dead) addPoints(tier == 2 ? 240 : tier == 1 ? 120 : 80, tier == 2 ? "ultrakill.hammerhitred" : tier == 1 ? "ultrakill.hammerhityellow" : "ultrakill.hammerhitgreen", weapon, -1, "");
						else if (tier == 2) addPoints(120, "ultrakill.hammerhitheavy", weapon, -1, "");
						else addPoints(40 * (tier + 1), "ultrakill.hammerhit", weapon, -1, "");
					}
					case HitPayload.SAWBLADE -> {
						if (dead) addPoints(big ? 100 : 45, big ? "ultrakill.bigkill" : "ultrakill.kill", weapon, -1, "");
						else addPoints(2, "ultrakill.nailhit", weapon, -1, "");
					}
					case HitPayload.NAIL, HitPayload.SAW -> {
						if (dead) addPoints(big ? 100 : 45, big ? "ultrakill.bigkill" : "ultrakill.kill", weapon, -1, "");
						else addPoints(2, "ultrakill.nailhit", weapon, -1, "");
					}
					case HitPayload.RAILCANNON -> {
						if (dead) addPoints(big ? 100 : 45, big ? "ultrakill.bigkill" : "ultrakill.kill", weapon, -1, "");
						else addPoints(15, "", weapon, -1, "");
					}
					case HitPayload.EXPLOSION -> {
						if (dead) addPoints(45, "ultrakill.exploded", weapon, -1, "");
						else addPoints(15, "ultrakill.explosionhit", weapon, -1, "");
					}
					case HitPayload.CANNONBALL -> {
						if (dead) addPoints(75, "ultrakill.cannonballed", weapon, -1, "");
						else addPoints(25, "", weapon, -1, "");
					}
					case HitPayload.HARPOON -> {
						if (dead) addPoints(big ? 100 : 45, big ? "ultrakill.bigkill" : "ultrakill.kill", weapon, -1, "");
					}
					case HitPayload.DRILL -> {
						if (dead) addPoints(120, "SCREWED", weapon, -1, "");
						else addPoints(4, "ultrakill.drillhit", weapon, -1, "");
					}
					default -> { // revolver
						if (dead) {
							if (limb.equals("head") && big) addPoints(150, "ultrakill.bigheadshot", weapon, -1, "");
							else if (limb.equals("head")) addPoints(80, "ultrakill.headshot", weapon, -1, "");
							else if (big) addPoints(100, "ultrakill.bigkill", weapon, -1, "");
							else addPoints(30, "ultrakill.kill", weapon, -1, "");
						} else if (limb.equals("head")) addPoints(25, "", weapon, -1, "");
						else addPoints(10, "", weapon, -1, "");
						// RevolverBeam: headshot combo on live head hits, 3 s window
						if (!dead && limb.equals("head")) {
							headshots++;
							headShotComboTime = 3f;
						} else if (!limb.equals("head")) {
							headshots = 0;
							headShotComboTime = 0;
						}
						if (headshots > 1) addPoints(headshots * 20, "ultrakill.headshotcombo", weapon, headshots, "");
					}
				}
				if (dead) addToMultiKill(weapon);
			}
			// boosting your own pellets (arg 1) is no parry of anything hostile: no points
			case HitPayload.PARRY -> { if (h.arg() != 1) addPoints(100, "ultrakill.parry", null, -1, ""); }
			case HitPayload.RICOSHOT -> {
				// Coin.RicoshotPointsCheck: 50, +50 ULTRA, +15 per ricochet past the first
				int points = 50;
				String prefix = "";
				if ((h.flags() & HitPayload.ULTRA) != 0) { prefix = "<color=orange>ULTRA</color>"; points += 50; }
				if (h.arg() > 1) points += h.arg() * 15;
				addPoints(points, "ultrakill.ricoshot", weapon, h.arg(), prefix);
			}
			default -> {}
		}
	}

	/** NewMovement.GetHurt's style part (Standard: drop a rank; P −damage·5). */
	public void hurt(int damage) {
		float loss = Tune.getF("style.lossMultiplier");
		if (loss > 0.5f) {
			removePoints(0);
			descendRank();
		} else if (loss > 0) removePoints(damage);
		// (NewMovement.GetHurt also takes damage x 5 off the level's style points; here P only ever grows - the user's
		// rule: points are banked when the meter runs out, and only the shop spends them)
	}

	private void addToMultiKill(String weapon) {
		multikillCount++;
		multikillTimer = 5f;
		switch (multikillCount) {
			case 0, 1 -> {}
			case 2 -> addPointsRaw(25, "ultrakill.doublekill", weapon, -1, "");
			case 3 -> addPointsRaw(50, "ultrakill.triplekill", weapon, -1, "");
			default -> addPointsRaw(100, "ultrakill.multikill", weapon, multikillCount, "");
		}
	}

	/** StyleCalculator.AddPoints: scaled by the air-time multiplier. */
	private void addPoints(int points, String id, String weapon, int count, String prefix) {
		int num = Math.round(points * airTime - points);
		addPointsRaw(points + num, id, weapon, count, prefix);
	}

	/** StyleHUD.AddPoints. A hitter without a weapon in the freshness table (fists) is treated as FRESH, like the game. */
	public void addPointsRaw(int points, String id, String weapon, int count, String prefix) {
		if (points > 0) {
			float num = points;
			Freshness state = weapon == null ? FRESH : freshnessState(weapon);
			num *= state.mult();
			if (weapon != null) decayFreshness(weapon, id);
			stylePoints += Math.round(num);
			pendingPoints += Math.round(num);
			currentMeter += num;
			rankScale = 0.2f;
		}
		String name = NAMES.getOrDefault(id, id);
		if (!name.isEmpty()) {
			String line = "+ " + prefix + name + (count >= 0 ? " x" + count : "");
			queue.add(Rich.parse(line, HUD_STYLE));
		}
		if (currentMeter >= rank().maxMeter && rankIndex < 7) ascendRank();
		else if (currentMeter > rank().maxMeter) currentMeter = rank().maxMeter;
	}

	public void removePoints(int points) {
		rankShaking = 5f;
		currentMeter -= points;
	}

	private void comboStart() {
		resetFreshnessIn = -1;
		currentMeter = Math.max(currentMeter, rank().maxMeter / 4f);
		comboActive = true;
	}

	private void comboOver() {
		currentMeter = 0;
		rankIndex = 0;
		resetFreshnessIn = 10f;
		comboActive = false;
	}

	private void ascendRank() {
		while (currentMeter >= rank().maxMeter) {
			currentMeter -= rank().maxMeter;
			rankIndex = Math.min(rankIndex + 1, RANKS.length - 1);
			if (rankIndex + 1 == RANKS.length - 1) break;
		}
		currentMeter = Math.max(currentMeter, rank().maxMeter / 4f);
		maxReachedRank = Math.max(maxReachedRank, rankIndex);
	}

	public void descendRank() {
		if (!comboActive) return;
		if (rankIndex > 0) {
			rankIndex--;
			currentMeter = rank().maxMeter - rank().maxMeter / 4f;
		} else comboOver();
	}

	// ------------------------------------------------------------------ freshness

	public float freshness(String weapon) {
		return weaponFreshness.getOrDefault(weapon, 10f);
	}

	public Freshness freshnessState(String weapon) {
		float v = freshness(weapon);
		Freshness r = DULL;
		for (Freshness f : FRESHNESS) if (v >= f.min()) r = f;
		return r;
	}

	/** StyleHUD.ClampFreshness: the floor depends on how many weapons you carry (one weapon never goes stale). */
	private float minFreshness() {
		int count = ownedWeapons.size();
		if (count <= 1) return FRESH.max();
		return switch (count) {
			case 2 -> USED.justAboveMin();
			case 3, 4 -> STALE.justAboveMin();
			default -> DULL.justAboveMin();
		};
	}

	private void setFreshness(String weapon, float amt) {
		weaponFreshness.put(weapon, Math.max(minFreshness(), Math.min(10f, amt)));
	}

	private void addFreshness(String weapon, float amt) {
		setFreshness(weapon, freshness(weapon) + amt);
	}

	private void decayFreshness(String weapon, String id) {
		if (!weaponFreshness.containsKey(weapon)) return;
		float num = DECAY_PER_MOVE;
		Float mult = DECAY_MULT.get(id);
		if (mult != null) num *= mult;
		setFreshness(weapon, freshness(weapon) - num);
		// other slots regenerate per move (every other weapon here is in another slot: variations share slot 1,
		// so revolver variations do not regenerate each other)
		for (String w : ownedWeapons) {
			if (w.equals(weapon) || slot(w) == slot(weapon)) continue;
			float num4 = REGEN_PER_MOVE;
			if (mult != null && mult > 0) num4 *= mult;
			addFreshness(w, num4);
		}
	}

	private static int slot(String weapon) {
		if (weapon.startsWith("revolver")) return 1;
		if (weapon.startsWith("shotgun")) return 2;
		if (weapon.startsWith("nailgun")) return 3;
		if (weapon.startsWith("railcannon")) return 4;
		if (weapon.startsWith("rocket")) return 5;
		return 0;
	}

	private void resetFreshness() {
		for (String w : ownedWeapons) weaponFreshness.put(w, 10f);
	}

	public static float moveTowards(float a, float b, float d) {
		return Math.abs(b - a) <= d ? b : a + Math.signum(b - a) * d;
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static float airTimeOf() {
		return INSTANCE.airTime;
	}
}
