package com.ultracraft.client.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultracraft.UltraCraft;
import com.ultracraft.client.render.UkLayers;
import com.ultracraft.client.style.StyleState;
import com.ultracraft.item.UKWeaponItem;
import com.ultracraft.item.WeaponKind;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Terminal's screen: Shop.prefab's Canvas (exported by tools/extract/export_canvas.py) laid out with Unity's
 * RectTransform rules and drawn in canvas units (y up), driven as the game drives it: ShopButtons switch windows on and
 * off (toActivate / toDeactivate), VariationInfo panels show a price, "Already Owned" or "Unavailable" and, owned, the
 * equipment button - here STANDARD / ALTERNATE (the user's rule: our weapons are hotbar items), MoneyText shows the P.
 * The canvas's layout-driven sizes are stored in the prefab as Unity last computed them, so no layout group is run.
 */
public final class ShopScreen {
	private ShopScreen() {}

	static final class Img {
		String sprite;
		float r, g, b, a, ppuMult;
		int type;
		boolean preserveAspect, enabled;
		/** Graphic.raycastTarget: it catches the pointer (the GraphicRaycaster stops at the topmost one) */
		boolean raycast = true;
	}

	static final class Txt {
		String text;
		float size, r, g, b, a, min, max;
		/** TMP's margins: left, top, right, bottom */
		float[] margin;
		int h, v;
		boolean auto, wrap, enabled;
		boolean raycast = true;
	}

	static final class Btn {
		int[] activate, deactivate;
		int variation;
		boolean deactivated, failure;
	}

	static final class Var {
		int cost, varPage, costText, buyButton, equipText, equipButtons, equipStatusButton, icon, orderButtons;
		String weapon;
		WeaponKind kind; // null: an arm (not sold here)
		int variation;
	}

	static final class Node {
		String name;
		int parent;
		boolean active;
		float[] amin = {0, 0}, amax = {0, 0}, pos = {0, 0}, size = {0, 0}, pivot = {0.5f, 0.5f};
		float sx = 1, sy = 1, rotZ;
		Img img;
		Txt txt;
		Btn shop;
		Var var;
		boolean button, interactable = true, money;
		int[] children = new int[0];
		Grp group;
		Elem element;
		/** HudOpenEffect's speed (opens from scale 0.05: x, then y; yFirst: y, then x), or 0 for none */
		float openSpeed;
		boolean openYFirst;
		/** the equipment buttons of this variation panel (a click toggles standard / alternate), or -1 */
		int equipOf = -1;
		/** the variation panel this is the buy button of (VariationInfo.Start: buyButton.variationInfo = this), or -1 */
		int buyOf = -1;
	}

	/** HorizontalLayoutGroup / VerticalLayoutGroup ("h" / "v") or GridLayoutGroup ("grid"). */
	static final class Grp {
		String kind;
		float[] padding; // left, right, top, bottom
		float spacing, sx, sy, cw, ch;
		int align, startCorner, startAxis, constraint, count;
		boolean controlW, controlH, expandW, expandH, reverse, enabled;
	}

	/** LayoutElement (-1 = not set). */
	static final class Elem {
		boolean ignore;
		float minW, minH, prefW, prefH, flexW, flexH;
	}

	static final class Sprite {
		Identifier tex;
		float w, h, ppu;
		float[] border;
	}

	private static List<Node> nodes;
	private static Map<String, Sprite> sprites;
	/** The canvas from the prefab root (Unity units): root rotation and scale, then the Canvas's own transform. */
	public static Matrix4f canvasFromRoot;
	/** The prefab root's own rotation and scale (its local space -> the posed model's), for its trigger box (ShopZone). */
	public static Matrix4f rootRS;
	private static boolean loadFailed;

	/** One terminal's screen: which elements are on, and the element under the pointer. */
	public static final class State {
		final boolean[] active;
		public int hover = -1;
		/** HudOpenEffect: each element's x / y scale while it opens, and whether it was on screen last frame */
		final float[] openX, openY;
		boolean[] wasOn, onNow;
		long lastNanos;

		State() {
			active = new boolean[nodes.size()];
			openX = new float[nodes.size()];
			openY = new float[nodes.size()];
			java.util.Arrays.fill(openX, 1);
			java.util.Arrays.fill(openY, 1);
			wasOn = new boolean[nodes.size()];
			onNow = new boolean[nodes.size()];
			for (int i = 0; i < active.length; i++) active[i] = nodes.get(i).active;
			// the parts this shop does not have (the bestiary, the Cyber Grind, the level, the sandbox): their buttons do nothing
			for (Node n : nodes)
				if (n.shop != null && (n.name.equals("EnemiesButton") || n.name.equals("CyberGrindButton") || n.name.equals("ReturnButton") || n.name.equals("SandboxButton")))
					n.shop.deactivated = true;
		}
	}

	public static boolean ready() {
		if (nodes == null && !loadFailed) load();
		return nodes != null;
	}

	public static State newState() {
		return ready() ? new State() : null;
	}

	/**
	 * The screen off (ShopZone.TurnOff: the canvas deactivated): every window opens again (HudOpenEffect.OnEnable) the
	 * next time it is drawn. The windows that were open stay open.
	 */
	public static void off(State st) {
		if (st == null) return;
		java.util.Arrays.fill(st.wasOn, false);
		st.lastNanos = 0;
	}

	private static float f(JsonElement e) {
		return e.getAsFloat();
	}

	private static float[] fa(JsonObject o, String k, float[] def) {
		if (!o.has(k)) return def;
		JsonArray a = o.getAsJsonArray(k);
		float[] r = new float[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = f(a.get(i));
		return r;
	}

	private static int[] ia(JsonArray a) {
		int[] r = new int[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
		return r;
	}

	private static void load() {
		try {
			Identifier id = new Identifier(UltraCraft.MOD_ID, "uk/shop/shop_canvas.json");
			var res = MinecraftClient.getInstance().getResourceManager().getResource(id);
			if (res.isEmpty()) { loadFailed = true; UltraCraft.LOG.warn("shop: no {} (run the asset setup)", id); return; }
			JsonObject root;
			try (var in = new InputStreamReader(res.get().getInputStream())) {
				root = JsonParser.parseReader(in).getAsJsonObject();
			}
			Map<String, Sprite> sp = new HashMap<>();
			for (var e : root.getAsJsonObject("sprites").entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				Sprite s = new Sprite();
				s.tex = new Identifier(UltraCraft.MOD_ID, "textures/uk/shop/" + o.get("file").getAsString() + ".png");
				s.w = f(o.get("w"));
				s.h = f(o.get("h"));
				s.ppu = f(o.get("ppu"));
				s.border = fa(o, "border", new float[4]);
				sp.put(e.getKey(), s);
			}
			List<Node> ns = new ArrayList<>();
			for (JsonElement el : root.getAsJsonArray("nodes")) {
				JsonObject o = el.getAsJsonObject();
				Node n = new Node();
				n.name = o.get("name").getAsString();
				n.parent = o.get("parent").getAsInt();
				n.active = o.get("active").getAsBoolean();
				n.amin = fa(o, "amin", n.amin);
				n.amax = fa(o, "amax", n.amax);
				n.pos = fa(o, "pos", n.pos);
				n.size = fa(o, "size", n.size);
				n.pivot = fa(o, "pivot", n.pivot);
				float[] sc = fa(o, "scale", new float[] {1, 1, 1});
				n.sx = sc[0];
				n.sy = sc[1];
				float[] q = fa(o, "rot", new float[] {0, 0, 0, 1});
				n.rotZ = (float) Math.atan2(2 * (q[3] * q[2] + q[0] * q[1]), 1 - 2 * (q[1] * q[1] + q[2] * q[2]));
				if (o.has("image")) {
					JsonObject m = o.getAsJsonObject("image");
					Img i = new Img();
					i.sprite = m.get("sprite").isJsonNull() ? null : m.get("sprite").getAsString();
					float[] c = fa(m, "color", new float[] {1, 1, 1, 1});
					i.r = c[0]; i.g = c[1]; i.b = c[2]; i.a = c[3];
					i.type = m.get("type").getAsInt();
					i.ppuMult = f(m.get("ppuMult"));
					i.preserveAspect = m.get("preserveAspect").getAsBoolean();
					i.enabled = m.get("enabled").getAsBoolean();
					i.raycast = !m.has("raycast") || m.get("raycast").getAsBoolean();
					n.img = i;
				}
				if (o.has("text")) {
					JsonObject m = o.getAsJsonObject("text");
					Txt t = new Txt();
					t.text = m.get("text").getAsString();
					t.size = f(m.get("size"));
					float[] c = fa(m, "color", new float[] {1, 1, 1, 1});
					t.r = c[0]; t.g = c[1]; t.b = c[2]; t.a = c[3];
					t.h = m.get("h").getAsInt();
					t.v = m.get("v").getAsInt();
					t.auto = m.get("auto").getAsBoolean();
					t.min = f(m.get("min"));
					t.max = f(m.get("max"));
					t.wrap = m.get("wrap").getAsBoolean();
					t.enabled = m.get("enabled").getAsBoolean();
					t.raycast = !m.has("raycast") || m.get("raycast").getAsBoolean();
					t.margin = fa(m, "margin", new float[4]);
					n.txt = t;
				}
				if (o.has("button")) {
					n.button = true;
					n.interactable = o.getAsJsonObject("button").get("interactable").getAsBoolean();
				}
				if (o.has("shop")) {
					JsonObject m = o.getAsJsonObject("shop");
					Btn b = new Btn();
					b.activate = ia(m.getAsJsonArray("activate"));
					b.deactivate = ia(m.getAsJsonArray("deactivate"));
					b.variation = m.get("variation").getAsInt();
					b.deactivated = m.get("deactivated").getAsBoolean();
					b.failure = m.get("failure").getAsBoolean();
					n.shop = b;
				}
				if (o.has("variation")) {
					JsonObject m = o.getAsJsonObject("variation");
					Var v = new Var();
					v.cost = m.get("cost").getAsInt();
					v.weapon = m.get("weapon").getAsString();
					v.varPage = m.get("varPage").getAsInt();
					v.costText = m.get("costText").getAsInt();
					v.buyButton = m.get("buyButton").getAsInt();
					v.equipText = m.get("equipText").getAsInt();
					v.equipButtons = m.get("equipButtons").getAsInt();
					v.equipStatusButton = m.get("equipStatusButton").getAsInt();
					v.icon = m.get("icon").getAsInt();
					v.orderButtons = m.get("orderButtons").getAsInt();
					weaponOf(v);
					n.var = v;
				}
				n.money = o.has("money");
				if (o.has("open")) {
					JsonObject m = o.getAsJsonObject("open");
					if (m.get("enabled").getAsBoolean() && !m.get("skip").getAsBoolean() && !m.get("reverse").getAsBoolean() && !m.get("noScale").getAsBoolean()) {
						n.openSpeed = f(m.get("speed"));
						n.openYFirst = m.get("yFirst").getAsBoolean();
					}
				}
				if (o.has("group")) {
					JsonObject m = o.getAsJsonObject("group");
					Grp g = new Grp();
					g.kind = m.get("kind").getAsString();
					g.padding = fa(m, "padding", new float[4]);
					g.align = m.get("align").getAsInt();
					g.enabled = m.get("enabled").getAsBoolean();
					if (g.kind.equals("grid")) {
						float[] c = fa(m, "cell", new float[] {100, 100}), sp2 = fa(m, "spacingXY", new float[2]);
						g.cw = c[0]; g.ch = c[1]; g.sx = sp2[0]; g.sy = sp2[1];
						g.startCorner = m.get("startCorner").getAsInt();
						g.startAxis = m.get("startAxis").getAsInt();
						g.constraint = m.get("constraint").getAsInt();
						g.count = m.get("count").getAsInt();
					} else {
						g.spacing = f(m.get("spacing"));
						g.controlW = m.get("controlW").getAsBoolean();
						g.controlH = m.get("controlH").getAsBoolean();
						g.expandW = m.get("expandW").getAsBoolean();
						g.expandH = m.get("expandH").getAsBoolean();
						g.reverse = m.get("reverse").getAsBoolean();
					}
					n.group = g;
				}
				if (o.has("element")) {
					JsonObject m = o.getAsJsonObject("element");
					Elem e = new Elem();
					e.ignore = m.get("ignore").getAsBoolean();
					e.minW = f(m.get("minW")); e.minH = f(m.get("minH"));
					e.prefW = f(m.get("prefW")); e.prefH = f(m.get("prefH"));
					e.flexW = f(m.get("flexW")); e.flexH = f(m.get("flexH"));
					n.element = e;
				}
				ns.add(n);
			}
			// children lists, and each variation panel's equipment buttons
			List<List<Integer>> ch = new ArrayList<>();
			for (int i = 0; i < ns.size(); i++) ch.add(new ArrayList<>());
			for (int i = 0; i < ns.size(); i++) if (ns.get(i).parent >= 0) ch.get(ns.get(i).parent).add(i);
			for (int i = 0; i < ns.size(); i++) ns.get(i).children = ch.get(i).stream().mapToInt(Integer::intValue).toArray();
			for (int i = 0; i < ns.size(); i++) {
				Var v = ns.get(i).var;
				if (v != null && v.equipButtons >= 0) markEquip(ns, v.equipButtons, i);
				if (v != null && v.buyButton >= 0) ns.get(v.buyButton).buyOf = i;
			}
			// the canvas from the prefab root: mount[0] is the root (its rotation and scale; its place in its level dropped)
			JsonArray mount = root.getAsJsonArray("mount");
			Matrix4f m = new Matrix4f();
			for (int i = 0; i < mount.size(); i++) {
				JsonObject t = mount.get(i).getAsJsonObject();
				float[] p = fa(t, "pos", new float[3]), r = fa(t, "rot", new float[] {0, 0, 0, 1}), s = fa(t, "scale", new float[] {1, 1, 1});
				if (i == 0) { p = new float[3]; rootRS = new Matrix4f().rotate(new org.joml.Quaternionf(r[0], r[1], r[2], r[3])).scale(s[0], s[1], s[2]); }
				// the canvas is a RectTransform under a plain Transform: its x / y come from its anchoredPosition (the
				// stored localPosition x / y are stale), its z from localPosition
				if (i == mount.size() - 1) {
					float[] ap = fa(root.getAsJsonArray("nodes").get(0).getAsJsonObject(), "pos", new float[] {p[0], p[1]});
					p = new float[] {ap[0], ap[1], p[2]};
				}
				m.translate(p[0], p[1], p[2]).rotate(new org.joml.Quaternionf(r[0], r[1], r[2], r[3])).scale(s[0], s[1], s[2]);
			}
			canvasFromRoot = m;
			trim(ns);
			nodes = ns;
			sprites = sp;
		} catch (Exception e) {
			loadFailed = true;
			UltraCraft.LOG.warn("shop: layout not loaded: {}", e.toString());
		}
	}

	/**
	 * The user's cut of the shop: it boots straight into the Weapons menu (the WeaponsButton's own switches, the main
	 * menu off) with no Back button; no Info / Color buttons; the equipment button without its arrow row, centred.
	 */
	private static void trim(List<Node> ns) {
		for (int i = 0; i < ns.size(); i++) {
			Node n = ns.get(i);
			if (n.name.equals("WeaponsButton") && n.shop != null) {
				for (int a : n.shop.activate) if (a >= 0) ns.get(a).active = true;
				for (int d : n.shop.deactivate) if (d >= 0) ns.get(d).active = false;
				int menu = ns.get(n.parent).parent;
				if (menu >= 0) ns.get(menu).active = false;
			}
			if (n.name.equals("Info and Color Panel")) n.active = false;
			if (n.parent >= 0 && ns.get(n.parent).name.equals("Equipment")) {
				if (n.name.equals("Buttons")) n.active = false;
				if (n.name.equals("Equipment Status")) n.pos = new float[] {n.pos[0], 0};
			}
			if (n.name.equals("BackButton") && n.parent >= 0 && ns.get(n.parent).parent >= 0 && ns.get(ns.get(n.parent).parent).name.equals("Weapons Panel")) n.active = false;
		}
	}

	private static void markEquip(List<Node> ns, int i, int varNode) {
		ns.get(i).equipOf = varNode;
		for (int c : ns.get(i).children == null ? new int[0] : ns.get(i).children) markEquip(ns, c, varNode);
	}

	/**
	 * VariationInfo.weaponName -> our weapon and variation. The game names its revolvers rev0 (blue), rev2 (green
	 * Marksman), rev1 (red Sharpshooter); the others go blue 0, green 1, red 2; armN are the arms.
	 */
	private static void weaponOf(Var v) {
		String w = v.weapon;
		int d = w.isEmpty() ? 0 : Character.getNumericValue(w.charAt(w.length() - 1));
		if (w.startsWith("rev")) { v.kind = WeaponKind.REVOLVER; v.variation = d == 2 ? 1 : d == 1 ? 2 : 0; }
		else if (w.startsWith("sho")) { v.kind = WeaponKind.SHOTGUN; v.variation = d; }
		else if (w.startsWith("nai")) { v.kind = WeaponKind.NAILGUN; v.variation = d; }
		else if (w.startsWith("rai")) { v.kind = WeaponKind.RAILCANNON; v.variation = d; }
		else if (w.startsWith("rock")) { v.kind = WeaponKind.ROCKET; v.variation = d; }
		else v.kind = null;
		if (v.variation < 0 || v.variation > 2) v.variation = 0;
	}

	// ------------------------------------------------------------------ the live state (VariationInfo.UpdateMoney, MoneyText)

	/** What the frame shows over the prefab's own values: texts, image colours, switched-off parts. */
	private static final Map<Integer, String> textOverride = new HashMap<>();
	private static final Map<Integer, float[]> textColor = new HashMap<>(), imageColor = new HashMap<>();
	private static final Map<Integer, Boolean> activeOverride = new HashMap<>();
	private static final java.util.Set<Integer> failing = new java.util.HashSet<>();

	private static String money(int p) {
		if (p > 1000000000) return "LIKE, A LOT OF ";
		return String.format(java.util.Locale.ROOT, "%,d", p);
	}

	private static boolean carries(WeaponKind kind) {
		var p = MinecraftClient.getInstance().player;
		if (p == null) return false;
		for (int i = 0; i < p.getInventory().size(); i++) if (UKWeaponItem.kindOf(p.getInventory().getStack(i)) == kind) return true;
		return false;
	}

	private static boolean carriedAlt(WeaponKind kind, int variation) {
		var p = MinecraftClient.getInstance().player;
		if (p == null) return false;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (UKWeaponItem.kindOf(s) == kind) return UKWeaponItem.alternateOf(s, variation);
		}
		return false;
	}

	private static void update() {
		textOverride.clear();
		textColor.clear();
		imageColor.clear();
		activeOverride.clear();
		failing.clear();
		int points = StyleState.INSTANCE.stylePoints;
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			if (n.money) textOverride.put(i, money(points) + " <color=#FF4343>P</color>");
			Var v = n.var;
			if (v == null) continue;
			boolean owned = v.kind == null || ShopClient.owns(v.kind, v.variation);
			// ColorBlindGet on the icon: the variation's colour (the arms: Feedbacker blue, Knuckleblaster red, Whiplash green)
			if (v.icon >= 0) {
				int ci = v.kind != null ? v.variation : v.weapon.endsWith("1") ? 2 : v.weapon.endsWith("2") ? 1 : 0;
				int rgb = com.ultracraft.client.UltraCraftClient.VARIATION_COLORS[ci];
				imageColor.put(v.icon, new float[] {((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1});
			}
			if (v.orderButtons >= 0) activeOverride.put(v.orderButtons, false);
			// owned but not carried (lost on death): buyable again for 0 P
			if (owned && v.kind != null && !carries(v.kind)) {
				textOverride.put(v.costText, "0 <color=#FF4343>P</color>");
				buttonText(v.buyButton, "0 <color=#FF4343>P</color>");
				if (v.equipButtons >= 0) activeOverride.put(v.equipButtons, false);
				continue;
			}
			if (!owned) {
				int cost = com.ultracraft.weapon.Shop.price(v.kind, v.variation);
				String t = cost > points ? "<color=red>" + money(cost) + " P</color>" : money(cost) + " <color=#FF4343>P</color>";
				textOverride.put(v.costText, t);
				buttonText(v.buyButton, t);
				if (cost > points) { failing.add(v.buyButton); imageColor.put(v.buyButton, new float[] {1, 0, 0, 1}); }
				if (v.equipButtons >= 0) activeOverride.put(v.equipButtons, false);
			} else {
				textOverride.put(v.costText, "Already Owned");
				buttonText(v.buyButton, "Already Owned");
				failing.add(v.buyButton);
				boolean equip = v.kind != null && v.equipButtons >= 0;
				if (v.equipButtons >= 0) activeOverride.put(v.equipButtons, equip);
				if (equip && v.equipText >= 0) {
					boolean alt = v.kind.hasAlt() && carriedAlt(v.kind, v.variation);
					textOverride.put(v.equipText, alt ? "Alternate" : "Standard");
					textColor.put(v.equipText, alt ? new float[] {1, 0.3f, 0.3f, 1} : new float[] {1, 1, 1, 1});
				}
			}
		}
	}

	private static void buttonText(int button, String t) {
		if (button < 0) return;
		int c = firstText(button);
		if (c >= 0) textOverride.put(c, t);
	}

	private static int firstText(int i) {
		if (nodes.get(i).txt != null) return i;
		for (int c : nodes.get(i).children) {
			int r = firstText(c);
			if (r >= 0) return r;
		}
		return -1;
	}

	private static boolean on(State st, int i) {
		Boolean o = activeOverride.get(i);
		return o != null ? o : st.active[i];
	}

	// ------------------------------------------------------------------ layout (RectTransform)

	/** Each element's frame (canvas from its own pivot-centred rect) and size this frame; null where it is off. */
	private static Matrix3x2f[] frames;
	private static float[][] rects;

	private static void layout(State st) {
		int n = nodes.size();
		if (frames == null || frames.length != n) { frames = new Matrix3x2f[n]; rects = new float[n][]; }
		java.util.Arrays.fill(frames, null);
		long now = System.nanoTime();
		float dt = st.lastNanos == 0 ? 0 : Math.min(0.1f, (now - st.lastNanos) / 1e9f);
		st.lastNanos = now;
		java.util.Arrays.fill(st.onNow, false);
		openDt = dt;
		place(st, 0, new Matrix3x2f(), 0, 0, 0, 0, null);
		boolean[] t = st.wasOn;
		st.wasOn = st.onNow;
		st.onNow = t;
	}

	private static float openDt;

	/** HudOpenEffect.Update: Mathf.MoveTowards(v, 1, dt x ((|1 - v| + 0.1) x speed)), x first then y (or y first). */
	private static void open(State st, int i, Node nd) {
		if (!st.wasOn[i]) { st.openX[i] = 0.05f; st.openY[i] = 0.05f; } // OnEnable: ResetValues
		float sp = nd.openSpeed;
		if (nd.openYFirst && st.openY[i] != 1) st.openY[i] = toward(st.openY[i], openDt * ((Math.abs(1 - st.openY[i]) + 0.1f) * sp));
		else if (st.openX[i] != 1) st.openX[i] = toward(st.openX[i], openDt * ((Math.abs(1 - st.openX[i]) + 0.1f) * sp));
		else if (st.openY[i] != 1) st.openY[i] = toward(st.openY[i], openDt * ((Math.abs(1 - st.openY[i]) + 0.1f) * sp));
	}

	private static float toward(float v, float step) {
		return Math.abs(1 - v) <= step ? 1 : v + Math.signum(1 - v) * step;
	}

	/**
	 * Unity's RectTransform: anchors on the parent's rect, the pivot at the anchored position, then rotation and scale.
	 * `ov` replaces the element's anchors / position / size where its parent's layout group placed it (amin x y, amax x y,
	 * pos x y, size x y).
	 */
	private static void place(State st, int i, Matrix3x2f parentFrame, float pw, float ph, float ppx, float ppy, float[] ov) {
		Node nd = nodes.get(i);
		if (!on(st, i)) return;
		if (nd.openSpeed > 0) open(st, i, nd);
		st.onNow[i] = true;
		float sx = nd.sx * (nd.openSpeed > 0 ? st.openX[i] : 1), sy = nd.sy * (nd.openSpeed > 0 ? st.openY[i] : 1);
		float[] amin = ov != null ? new float[] {ov[0], ov[1]} : nd.amin, amax = ov != null ? new float[] {ov[2], ov[3]} : nd.amax;
		float[] pos = ov != null ? new float[] {ov[4], ov[5]} : nd.pos, size = ov != null ? new float[] {ov[6], ov[7]} : nd.size;
		Matrix3x2f m;
		float w, h;
		if (i == 0) {
			w = nd.size[0];
			h = nd.size[1];
			m = new Matrix3x2f();
		} else {
			float pminX = -ppx * pw, pminY = -ppy * ph;
			float ax0 = pminX + amin[0] * pw, ay0 = pminY + amin[1] * ph, ax1 = pminX + amax[0] * pw, ay1 = pminY + amax[1] * ph;
			w = ax1 - ax0 + size[0];
			h = ay1 - ay0 + size[1];
			float px = ax0 + (ax1 - ax0) * nd.pivot[0] + pos[0], py = ay0 + (ay1 - ay0) * nd.pivot[1] + pos[1];
			m = new Matrix3x2f(parentFrame).translate(px, py).rotate(nd.rotZ).scale(sx, sy);
		}
		frames[i] = m;
		rects[i] = new float[] {-nd.pivot[0] * w, -nd.pivot[1] * h, (1 - nd.pivot[0]) * w, (1 - nd.pivot[1]) * h};
		Map<Integer, float[]> laid = nd.group != null && nd.group.enabled ? arrange(st, nd, w, h) : null;
		for (int c : nd.children) place(st, c, m, w, h, nd.pivot[0], nd.pivot[1], laid == null ? null : laid.get(c));
	}

	/** The children a layout group places: active, not ignoring layout. */
	private static List<Integer> laidOut(State st, Node nd) {
		List<Integer> r = new ArrayList<>();
		for (int c : nd.children) if (on(st, c) && (nodes.get(c).element == null || !nodes.get(c).element.ignore)) r.add(c);
		return r;
	}

	/** LayoutUtility's min / preferred / flexible size of an element on an axis (0 x, 1 y). */
	private static float[] sizes(State st, int i, int axis) {
		Node n = nodes.get(i);
		float min = 0, pref = 0, flex = 0;
		if (n.group != null && n.group.enabled) {
			float[] g = groupSizes(st, n, axis);
			min = g[0]; pref = g[1]; flex = g[2];
		} else if (n.txt != null && n.txt.enabled) {
			TextRenderer tr = MinecraftClient.getInstance().textRenderer;
			String s = textOverride.getOrDefault(i, n.txt.text);
			float k = n.txt.size / FONT_PX;
			float[] mg = n.txt.margin;
			pref = axis == 0 ? tr.getWidth(rich(s, new float[] {1, 1, 1, 1})) * k + mg[0] + mg[2] : n.txt.size * LINE + mg[1] + mg[3];
		} else if (n.img != null && n.img.enabled && n.img.sprite != null && sprites.containsKey(n.img.sprite)) {
			Sprite sp = sprites.get(n.img.sprite);
			pref = (axis == 0 ? sp.w : sp.h) * pixel() * 100f / (sp.ppu * Math.max(1e-3f, n.img.type == 1 ? n.img.ppuMult : 1));
		}
		Elem e = n.element;
		if (e != null) {
			float em = axis == 0 ? e.minW : e.minH, ep = axis == 0 ? e.prefW : e.prefH, ef = axis == 0 ? e.flexW : e.flexH;
			if (em >= 0) min = em;
			if (ep >= 0) pref = ep;
			if (ef >= 0) flex = ef;
		}
		return new float[] {min, Math.max(min, pref), flex};
	}

	private static float[] groupSizes(State st, Node n, int axis) {
		Grp g = n.group;
		List<Integer> kids = laidOut(st, n);
		float pad = axis == 0 ? g.padding[0] + g.padding[1] : g.padding[2] + g.padding[3];
		if (g.kind.equals("grid")) {
			float cell = axis == 0 ? g.cw : g.ch, sp = axis == 0 ? g.sx : g.sy;
			int per = Math.max(1, g.constraint == 0 ? (int) Math.ceil(Math.sqrt(kids.size())) : g.count);
			int count = axis == 0 ? Math.min(per, kids.size()) : (int) Math.ceil(kids.size() / (float) per);
			float v = pad + count * cell + Math.max(0, count - 1) * sp;
			return new float[] {v, v, 0};
		}
		boolean along = (axis == 1) == g.kind.equals("v");
		boolean control = axis == 0 ? g.controlW : g.controlH, expand = axis == 0 ? g.expandW : g.expandH;
		float min = 0, pref = 0, flex = 0;
		for (int c : kids) {
			float[] cs = childSizes(st, c, axis, control, expand);
			if (along) { min += cs[0]; pref += cs[1]; flex += cs[2]; }
			else { min = Math.max(min, cs[0]); pref = Math.max(pref, cs[1]); flex = Math.max(flex, cs[2]); }
		}
		if (along && kids.size() > 1) { min += g.spacing * (kids.size() - 1); pref += g.spacing * (kids.size() - 1); }
		return new float[] {min + pad, pref + pad, flex};
	}

	/** HorizontalOrVerticalLayoutGroup.GetChildSizes. */
	private static float[] childSizes(State st, int c, int axis, boolean control, boolean expand) {
		float min, pref, flex;
		if (!control) {
			min = nodes.get(c).size[axis];
			pref = min;
			flex = 0;
		} else {
			float[] s = sizes(st, c, axis);
			min = s[0]; pref = s[1]; flex = s[2];
		}
		if (expand) flex = Math.max(flex, 1);
		return new float[] {min, pref, flex};
	}

	/** Where a layout group puts its children (SetChildAlongAxis: anchors top-left, inset from the left / top edge). */
	private static Map<Integer, float[]> arrange(State st, Node n, float w, float h) {
		Grp g = n.group;
		List<Integer> kids = laidOut(st, n);
		if (g.reverse) java.util.Collections.reverse(kids);
		Map<Integer, float[]> out = new HashMap<>();
		for (int c : kids) out.put(c, new float[] {0, 1, 0, 1, 0, 0, nodes.get(c).size[0], nodes.get(c).size[1]});
		float[] size = {w, h};
		if (g.kind.equals("grid")) {
			int per = Math.max(1, g.constraint == 1 ? g.count : g.constraint == 2 ? (int) Math.ceil(kids.size() / (float) Math.max(1, g.count))
				: Math.max(1, (int) ((w - g.padding[0] - g.padding[1] + g.sx) / (g.cw + g.sx))));
			int cols = Math.min(per, kids.size()), rows = (int) Math.ceil(kids.size() / (float) per);
			float reqX = cols * g.cw + Math.max(0, cols - 1) * g.sx, reqY = rows * g.ch + Math.max(0, rows - 1) * g.sy;
			float sx0 = startOffset(g, 0, reqX, size), sy0 = startOffset(g, 1, reqY, size);
			for (int k = 0; k < kids.size(); k++) {
				int col = k % per, row = k / per;
				set(out.get(kids.get(k)), nodes.get(kids.get(k)), 0, sx0 + col * (g.cw + g.sx), g.cw);
				set(out.get(kids.get(k)), nodes.get(kids.get(k)), 1, sy0 + row * (g.ch + g.sy), g.ch);
			}
			return out;
		}
		boolean vertical = g.kind.equals("v");
		for (int axis = 0; axis < 2; axis++) {
			boolean along = (axis == 1) == vertical;
			boolean control = axis == 0 ? g.controlW : g.controlH, expand = axis == 0 ? g.expandW : g.expandH;
			float pad = axis == 0 ? g.padding[0] + g.padding[1] : g.padding[2] + g.padding[3];
			float inner = size[axis] - pad;
			float align = axis == 0 ? (g.align % 3) * 0.5f : (g.align / 3) * 0.5f;
			if (!along) {
				for (int c : kids) {
					float[] cs = childSizes(st, c, axis, control, expand);
					float req = Math.max(cs[0], Math.min(inner, cs[2] > 0 ? size[axis] : cs[1]));
					float start = startOffset(g, axis, req, size);
					if (control) set(out.get(c), nodes.get(c), axis, start, req);
					else set(out.get(c), nodes.get(c), axis, start + (req - nodes.get(c).size[axis]) * align, nodes.get(c).size[axis]);
				}
				continue;
			}
			float[] tot = groupSizes(st, n, axis); // with padding
			float totalMin = tot[0], totalPref = tot[1], totalFlex = 0;
			List<float[]> each = new ArrayList<>();
			for (int c : kids) { float[] cs = childSizes(st, c, axis, control, expand); each.add(cs); totalFlex += cs[2]; }
			float lerp = totalPref == totalMin ? (size[axis] > totalMin ? 1 : 0) : Math.max(0, Math.min(1, (size[axis] - totalMin) / (totalPref - totalMin)));
			float flexMul = size[axis] > totalPref && totalFlex > 0 ? (size[axis] - totalPref) / totalFlex : 0;
			float pos = axis == 0 ? g.padding[0] : g.padding[2];
			if (flexMul == 0) pos = startOffset(g, axis, totalPref - pad, size);
			for (int k = 0; k < kids.size(); k++) {
				int c = kids.get(k);
				float[] cs = each.get(k);
				float cs2 = cs[0] + (cs[1] - cs[0]) * lerp + cs[2] * flexMul;
				if (control) set(out.get(c), nodes.get(c), axis, pos, cs2);
				else set(out.get(c), nodes.get(c), axis, pos + (cs2 - nodes.get(c).size[axis]) * align, nodes.get(c).size[axis]);
				pos += cs2 + g.spacing;
			}
		}
		return out;
	}

	/** LayoutGroup.GetStartOffset: the padding plus the share of the surplus the alignment puts before the children. */
	private static float startOffset(Grp g, int axis, float requiredNoPad, float[] size) {
		float pad = axis == 0 ? g.padding[0] + g.padding[1] : g.padding[2] + g.padding[3];
		float surplus = size[axis] - (requiredNoPad + pad);
		float align = axis == 0 ? (g.align % 3) * 0.5f : (g.align / 3) * 0.5f;
		return (axis == 0 ? g.padding[0] : g.padding[2]) + surplus * align;
	}

	/** RectTransform.SetInsetAndSizeFromParentEdge (from the left for x, from the top for y). */
	private static void set(float[] ov, Node n, int axis, float inset, float sz) {
		if (axis == 0) { ov[0] = 0; ov[2] = 0; ov[4] = inset + sz * n.pivot[0]; ov[6] = sz; }
		else { ov[1] = 1; ov[3] = 1; ov[5] = -(inset + sz * (1 - n.pivot[1])); ov[7] = sz; }
	}

	// ------------------------------------------------------------------ drawing

	private static final float Z_STEP = 0.05f;
	private static float z;

	/**
	 * Draws the screen with `ms` in canvas space (Unity canvas units, y up, the screen facing -z). `light` is unused:
	 * the screen glows on its own.
	 */
	public static void draw(State st, MatrixStack ms, VertexConsumerProvider buffers) {
		if (!ready() || st == null) return;
		update();
		layout(st);
		z = 0;
		drawNode(st, 0, ms, buffers);
	}

	private static void drawNode(State st, int i, MatrixStack ms, VertexConsumerProvider buffers) {
		Matrix3x2f fr = frames[i];
		if (fr == null) return;
		Node n = nodes.get(i);
		float[] r = rects[i];
		if (n.img != null && n.img.enabled && n.img.sprite != null) {
			Sprite sp = sprites.get(n.img.sprite);
			float[] c = imageColor.getOrDefault(i, new float[] {n.img.r, n.img.g, n.img.b, n.img.a});
			float cr = c[0], cg = c[1], cb = c[2], ca = c[3];
			// a Button's colour tint: highlighted under the pointer (ColorBlock: normal 1, highlighted 0.96 by default)
			if (st.hover == i && n.button && n.interactable && !failing.contains(i)) { cr *= 0.85f; cg *= 0.85f; cb *= 0.85f; }
			if (sp != null && ca > 0.003f) {
				z -= Z_STEP;
				if (n.img.type == 1 && (sp.border[0] > 0 || sp.border[1] > 0 || sp.border[2] > 0 || sp.border[3] > 0)) sliced(ms, buffers, fr, sp, n.img.ppuMult, r, cr, cg, cb, ca);
				else {
					float x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3];
					if (n.img.preserveAspect) {
						float aspect = sp.w / sp.h, w = x1 - x0, h = y1 - y0;
						if (w / h > aspect) { float nw = h * aspect; x0 += (w - nw) / 2; x1 = x0 + nw; }
						else { float nh = w / aspect; y0 += (h - nh) / 2; y1 = y0 + nh; }
					}
					quad(ms, buffers, sp.tex, fr, x0, y0, x1, y1, 0, 0, 1, 1, cr, cg, cb, ca);
				}
			}
		}
		if (n.txt != null && n.txt.enabled) {
			String s = textOverride.getOrDefault(i, n.txt.text);
			if (!s.isEmpty()) {
				z -= Z_STEP;
				float[] tc = textColor.getOrDefault(i, new float[] {n.txt.r, n.txt.g, n.txt.b, n.txt.a});
				text(ms, buffers, fr, r, n.txt, s, tc);
			}
		}
		for (int c : n.children) drawNode(st, c, ms, buffers);
	}

	private static void vtx(VertexConsumer vc, Matrix4f m, Matrix3x2f fr, float x, float y, float u, float v, float r, float g, float b, float a) {
		Vector2f p = fr.transformPosition(x, y, new Vector2f());
		vc.vertex(m, p.x, p.y, z).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, -1).next();
	}

	/** u/v with v = 0 at the image's top; x/y with y up. */
	private static void quad(MatrixStack ms, VertexConsumerProvider buffers, Identifier tex, Matrix3x2f fr, float x0, float y0, float x1, float y1,
							 float u0, float v0, float u1, float v1, float r, float g, float b, float a) {
		VertexConsumer vc = buffers.getBuffer(UkLayers.screen(tex));
		Matrix4f m = ms.peek().getPositionMatrix();
		vtx(vc, m, fr, x0, y0, u0, v1, r, g, b, a);
		vtx(vc, m, fr, x1, y0, u1, v1, r, g, b, a);
		vtx(vc, m, fr, x1, y1, u1, v0, r, g, b, a);
		vtx(vc, m, fr, x0, y1, u0, v0, r, g, b, a);
	}

	/**
	 * Canvas units per sprite pixel at ppu 100 (by eye, from the layout: the 16 px title buttons fill 40 units and the
	 * windows' content starts 45 units down, under a 17 px title bar).
	 */
	private static float pixel() {
		return (float) com.ultracraft.client.Tune.get("shop.pixel");
	}

	static {
		com.ultracraft.client.Tune.def("shop.pixel", 2.5, "Terminal screen: canvas units per sprite pixel (window bars, button borders; by eye from the layout: 16 px buttons fill 40 units)");
	}

	/** Unity's Sliced Image: borders (left, bottom, right, top texture pixels) shown at border / (ppu x multiplier) x 100 units. */
	private static void sliced(MatrixStack ms, VertexConsumerProvider buffers, Matrix3x2f fr, Sprite sp, float ppuMult, float[] r,
							   float cr, float cg, float cb, float ca) {
		float k = pixel() * 100f / (sp.ppu * Math.max(1e-3f, ppuMult));
		float bl = sp.border[0] * k, bb = sp.border[1] * k, br = sp.border[2] * k, bt = sp.border[3] * k;
		float w = r[2] - r[0], h = r[3] - r[1];
		// borders bigger than the rect shrink in proportion (Unity does the same)
		if (bl + br > w && bl + br > 0) { float s = w / (bl + br); bl *= s; br *= s; }
		if (bb + bt > h && bb + bt > 0) { float s = h / (bb + bt); bb *= s; bt *= s; }
		float[] xs = {r[0], r[0] + bl, r[2] - br, r[2]}, ys = {r[1], r[1] + bb, r[3] - bt, r[3]};
		float[] us = {0, sp.border[0] / sp.w, 1 - sp.border[2] / sp.w, 1};
		float[] vs = {1, 1 - sp.border[1] / sp.h, sp.border[3] / sp.h, 0}; // v from the top: bottom row first
		for (int ix = 0; ix < 3; ix++)
			for (int iy = 0; iy < 3; iy++) {
				if (xs[ix + 1] - xs[ix] <= 1e-4f || ys[iy + 1] - ys[iy] <= 1e-4f) continue;
				quad(ms, buffers, sp.tex, fr, xs[ix], ys[iy], xs[ix + 1], ys[iy + 1], us[ix], vs[iy + 1], us[ix + 1], vs[iy], cr, cg, cb, ca);
			}
	}

	// ------------------------------------------------------------------ text (TextMeshPro in our VCR font)

	private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z]+)(?:=([^>]*))?>");
	/**
	 * TMP font size -> Minecraft font units. The screen's font is the game's "fs-tahoma-8px-v2 SDF" (its source TTF in
	 * font/terminal.json): TMP draws it at its face scale 1.5, so an em is 1.5 x the size; Minecraft sizes a TTF by
	 * ascent - descent (832 of 1024 units), so size 39 = 32 x 1.5 x 832 / 1024 gives that em at k = size / 32.
	 */
	private static final float FONT_PX = 32f;
	/** a line's height, x the font size: the font asset's lineHeight 73.125 at point size 90, x its scale 1.5 */
	private static final float LINE = 73.125f / 90f * 1.5f;
	private static final Style TERMINAL_STYLE = Style.EMPTY.withFont(new Identifier(UltraCraft.MOD_ID, "terminal"));

	/** TMP rich text with <color> (named or #RRGGBB[AA]) into styled Text; other tags dropped. */
	private static MutableText rich(String s, float[] base) {
		MutableText out = Text.empty();
		java.util.ArrayDeque<Integer> colors = new java.util.ArrayDeque<>();
		int baseRgb = rgb(base);
		colors.push(baseRgb);
		Matcher m = TAG.matcher(s);
		int at = 0;
		while (m.find()) {
			if (m.start() > at) out.append(Text.literal(s.substring(at, m.start())).setStyle(style(colors.peek())));
			at = m.end();
			if (m.group(2).equalsIgnoreCase("color")) {
				if (!m.group(1).isEmpty()) { if (colors.size() > 1) colors.pop(); }
				else colors.push(parseColor(m.group(3), baseRgb));
			}
		}
		if (at < s.length()) out.append(Text.literal(s.substring(at)).setStyle(style(colors.peek())));
		return out;
	}

	private static Style style(int rgb) {
		return TERMINAL_STYLE.withColor(TextColor.fromRgb(rgb));
	}

	private static int rgb(float[] c) {
		return ((int) (c[0] * 255) << 16) | ((int) (c[1] * 255) << 8) | (int) (c[2] * 255);
	}

	private static int parseColor(String v, int def) {
		if (v == null) return def;
		v = v.replace("\"", "").trim().toLowerCase(java.util.Locale.ROOT);
		switch (v) {
			case "red": return 0xFF0000;
			case "white": return 0xFFFFFF;
			case "yellow": return 0xFFFF00;
			case "orange": return 0xFFA500;
			case "grey": case "gray": return 0x808080;
			case "green": return 0x00FF00;
			case "blue": return 0x0000FF;
			case "black": return 0;
			default:
		}
		if (v.startsWith("#") && v.length() >= 7) {
			try { return Integer.parseInt(v.substring(1, 7), 16); } catch (NumberFormatException e) { return def; }
		}
		return def;
	}

	private static void text(MatrixStack ms, VertexConsumerProvider buffers, Matrix3x2f fr, float[] r, Txt t, String s, float[] color) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		MutableText txt = rich(s, color);
		// TMP's margins inset the text area
		float[] mg = t.margin;
		r = new float[] {r[0] + mg[0], r[1] + mg[3], r[2] - mg[2], r[3] - mg[1]};
		float w = r[2] - r[0], h = r[3] - r[1];
		float size = t.size;
		// TMP auto size: the biggest size from min to max at which the text fits the width
		if (t.auto && t.max > 0) {
			size = t.max;
			float natural = tr.getWidth(txt) / FONT_PX;
			if (natural * size > w && natural > 0) size = Math.max(t.min, w / natural);
		}
		float k = size / FONT_PX;
		List<OrderedText> lines = t.wrap && w > 0 ? tr.wrapLines(txt, Math.max(1, (int) (w / k))) : List.of(txt.asOrderedText());
		float lineH = size * LINE;
		float total = lines.size() * lineH;
		// vertical alignment: Top 256, Middle 512 (and Midline), Bottom 1024 (and Baseline)
		float top;
		if ((t.v & (512 | 4096)) != 0) top = r[1] + h / 2 + total / 2;
		else if ((t.v & (1024 | 2048)) != 0) top = r[1] + total;
		else top = r[3];
		int argb = ((int) (Math.max(0, Math.min(1, color[3])) * 255) << 24) | rgb(color);
		for (int li = 0; li < lines.size(); li++) {
			OrderedText line = lines.get(li);
			float lw = tr.getWidth(line) * k;
			// horizontal: Left 1, Center 2 and Geometry 32 (centred), Right 4 (Justified / Flush as left)
			float x = (t.h & (2 | 32)) != 0 ? r[0] + (w - lw) / 2 : (t.h & 4) != 0 ? r[2] - lw : r[0];
			float y = top - li * lineH;
			Vector2f p = fr.transformPosition(x, y, new Vector2f());
			ms.push();
			Matrix4f m = ms.peek().getPositionMatrix();
			// the frame's rotation and scale on the glyphs, y down for the font
			Matrix4f g = new Matrix4f(m).translate(p.x, p.y, z)
				.mul(new Matrix4f(fr.m00, fr.m01, 0, 0, fr.m10, fr.m11, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1))
				.scale(k, -k, 1);
			tr.draw(line, 0, 0, argb, false, g, buffers, TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
			ms.pop();
		}
	}

	// ------------------------------------------------------------------ the pointer (ControllerPointer / ShopButton)

	/**
	 * The element a click at a canvas point goes to, or -1. GraphicRaycaster: the topmost graphic that is a raycast target
	 * under the point (drawn last = on top) takes the pointer, and ExecuteEvents hands the click to the first element up
	 * its parents with a click handler. An open window's own panel catches the pointer, so the buttons behind it can no
	 * longer be clicked through it.
	 */
	public static int hit(State st, float x, float y) {
		if (frames == null || st == null) return -1;
		int top = -1;
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			boolean target = (n.img != null && n.img.enabled && n.img.raycast) || (n.txt != null && n.txt.enabled && n.txt.raycast) || n.button || n.shop != null;
			if (frames[i] == null || !target) continue;
			Vector2f l = new Matrix3x2f(frames[i]).invert().transformPosition(x, y, new Vector2f());
			float[] r = rects[i];
			if (l.x >= r[0] && l.x <= r[2] && l.y >= r[1] && l.y <= r[3]) top = i;
		}
		for (int i = top; i >= 0; i = nodes.get(i).parent) {
			Node n = nodes.get(i);
			if (n.button || n.shop != null) return i;
		}
		return -1;
	}

	/** Whether node `a` is a VariationInfo panel or holds one (turning it on enables the panel). */
	private static boolean opensVariation(int a) {
		for (int i = 0; i < nodes.size(); i++) {
			if (nodes.get(i).var == null) continue;
			for (int k = i; k >= 0; k = nodes.get(k).parent) if (k == a) return true;
		}
		return false;
	}

	/** The purchase button last pressed (its window closes when the server says the purchase went through). */
	private static int lastBuy = -1;

	/**
	 * A purchase went through (ShopSuccess): the variation's info window closes, as its own Back button closes it
	 * (user: no need to press Back after buying). The game leaves it open.
	 */
	public static void bought(State st) {
		int i = lastBuy;
		lastBuy = -1;
		if (st == null || i < 0 || i >= nodes.size()) return;
		for (int w = nodes.get(i).parent; w >= 0; w = nodes.get(w).parent) {
			for (int k = 0; k < nodes.size(); k++) {
				Btn b = nodes.get(k).shop;
				if (b == null || !within(k, w)) continue;
				boolean closes = false;
				for (int d : b.deactivate) if (d == w) closes = true;
				if (!closes) continue;
				for (int a : b.activate) if (a >= 0) st.active[a] = true;
				for (int d : b.deactivate) if (d >= 0) st.active[d] = false;
				return;
			}
		}
	}

	/** Node `k` is `w` or inside it. */
	private static boolean within(int k, int w) {
		for (; k >= 0; k = nodes.get(k).parent) if (k == w) return true;
		return false;
	}

	/** ShopButton.OnPointerClick (or a plain Button): windows on and off, a purchase, the equipment toggle. */
	public static void click(State st, int i) {
		if (i < 0) return;
		Node n = nodes.get(i);
		boolean fail = failing.contains(i) || (n.button && !n.interactable);
		if (n.equipOf >= 0) {
			Var v = nodes.get(n.equipOf).var;
			if (v != null && v.kind != null && v.kind.hasAlt()) { com.ultracraft.client.UKSound.play("uk.ui.smileos2click", 1f, 1f); ShopClient.toggleAlt(v.kind, v.variation); ShopMode.openDrawer(); }
			else com.ultracraft.client.UKSound.play("uk.ui.smileos2error", 1f, 1f);
			return;
		}
		Btn b = n.shop;
		if (b == null) return;
		if (b.deactivated) return;
		if (fail || b.failure) {
			com.ultracraft.client.UKSound.play("uk.ui.smileos2error", 1f, 1f);
			return;
		}
		for (int a : b.activate) if (a >= 0) st.active[a] = true;
		for (int d : b.deactivate) if (d >= 0) st.active[d] = false;
		// a weapon's window coming on (VariationInfo.OnEnable): the drawer opens
		for (int a : b.activate) if (a >= 0 && opensVariation(a)) { ShopMode.openDrawer(); break; }
		// the buy button: VariationInfo.WeaponBought (the server takes the P and answers with ShopSuccess / ShopFail)
		if (n.buyOf >= 0 && nodes.get(n.buyOf).var.kind != null) {
			Var v = nodes.get(n.buyOf).var;
			if (!ShopClient.owns(v.kind, v.variation) || !carries(v.kind)) { lastBuy = i; ShopClient.buy(v.kind, v.variation); return; }
		}
		com.ultracraft.client.UKSound.play("uk.ui.smileos2click", 1f, 1f);
	}
}
