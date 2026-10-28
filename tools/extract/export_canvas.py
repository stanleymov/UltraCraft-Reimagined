"""Export a prefab's UI canvas (RectTransforms, Images, TMP texts, Buttons, ShopButtons, VariationInfos, MoneyTexts) as a
layout JSON the mod draws and drives itself, plus every sprite it uses as a cropped PNG with its 9-slice border.

    python tools/extract/export_canvas.py "<prefab key>" <root name> <canvas path under root> <out.json> <sprite dir>

Layout-driven values (layout groups, content size fitters) are serialized in the prefab as Unity last computed them, so
the stored anchoredPosition / sizeDelta place every element without running the layout components.
"""
import json
import os
import sys

from PIL import Image

from ukenv import env_for_key, script_name


def r2(v):
    return round(float(v), 4)


def vec(d, keys):
    return [r2(d[k]) for k in keys]


def comps(go):
    for c in go.m_Components:
        yield c.component.read() if hasattr(c, "component") else c.read()


def find_root(env, root_name):
    for o in env.objects:
        if o.type.name != "GameObject":
            continue
        g = o.read()
        if g.m_Name != root_name:
            continue
        for c in comps(g):
            if c.object_reader.type.name in ("Transform", "RectTransform") and c.m_Father.path_id == 0:
                return c
    return None


def child(tr, name):
    for ch in tr.m_Children:
        c = ch.read()
        if c.m_GameObject.read().m_Name == name:
            return c
    return None


def main():
    key, root_name, path, out, sprite_dir = sys.argv[1:6]
    env, _ = env_for_key(key)
    root = find_root(env, root_name)
    canvas = root
    chain = [root]
    for part in path.split("/"):
        canvas = child(canvas, part)
        chain.append(canvas)
    os.makedirs(sprite_dir, exist_ok=True)
    nodes, by_go, sprites = [], {}, {}

    def tt(o):
        return o.object_reader.read_typetree()

    # the canvas's transform from the prefab root (root first), for placing the screen on the model
    mount = []
    for tr in chain:
        d = tt(tr)
        mount.append({"name": tr.m_GameObject.read().m_Name, "pos": vec(d["m_LocalPosition"], "xyz"),
                      "rot": vec(d["m_LocalRotation"], "xyzw"), "scale": vec(d["m_LocalScale"], "xyz")})

    def sprite_of(img):
        try:
            sp = img.m_Sprite.read()
        except Exception:  # noqa: BLE001
            return None
        name = sp.m_Name
        if name in sprites:
            return name
        r, b = sp.m_Rect, sp.m_Border
        stem = name.lower().replace(" ", "_")
        try:
            im = sp.image
            # an Alpha8 texture is sampled as white with that alpha (the weapon icons): saved white, not black
            try:
                fmt = int(sp.m_RD.texture.read().m_TextureFormat)
            except Exception:  # noqa: BLE001
                fmt = -1
            if fmt == 1:
                a = im.convert("RGBA").getchannel("A")
                im = Image.new("RGBA", im.size, (255, 255, 255, 0))
                im.putalpha(a)
            im.save(os.path.join(sprite_dir, stem + ".png"))
        except Exception as e:  # noqa: BLE001
            print("sprite", name, "not saved:", e)
        sprites[name] = {"file": stem, "w": r2(r.width), "h": r2(r.height), "border": [r2(b.x), r2(b.y), r2(b.z), r2(b.w)],
                         "ppu": r2(sp.m_PixelsToUnits)}
        return name

    def walk(tr, parent):
        go = tr.m_GameObject.read()
        d = tt(tr)
        idx = len(nodes)
        n = {"name": go.m_Name, "parent": parent, "active": bool(go.m_IsActive)}
        if "m_AnchorMin" in d:
            n.update({"amin": vec(d["m_AnchorMin"], "xy"), "amax": vec(d["m_AnchorMax"], "xy"), "pos": vec(d["m_AnchoredPosition"], "xy"),
                      "size": vec(d["m_SizeDelta"], "xy"), "pivot": vec(d["m_Pivot"], "xy")})
        n["z"] = r2(d["m_LocalPosition"]["z"])
        n["scale"] = vec(d["m_LocalScale"], "xyz")
        n["rot"] = vec(d["m_LocalRotation"], "xyzw")
        nodes.append(n)
        by_go[tr.m_GameObject.path_id] = idx
        for c in comps(go):
            t = c.object_reader.type.name
            if t != "MonoBehaviour":
                continue
            sn = script_name(c)
            m = tt(c)
            if sn == "Image":
                n["image"] = {"sprite": sprite_of(c), "color": vec(m["m_Color"], "rgba"), "type": m.get("m_Type", 0),
                              "fillMethod": m.get("m_FillMethod", 0), "fillAmount": r2(m.get("m_FillAmount", 1)),
                              "fillOrigin": m.get("m_FillOrigin", 0), "ppuMult": r2(m.get("m_PixelsPerUnitMultiplier", 1)),
                              "preserveAspect": bool(m.get("m_PreserveAspect", 0)), "enabled": bool(m.get("m_Enabled", 1)),
                              # Graphic.raycastTarget: the GraphicRaycaster's pointer stops at the topmost one
                              "raycast": bool(m.get("m_RaycastTarget", 1))}
            elif sn in ("TextMeshProUGUI", "TextMeshPro", "Text"):
                n["text"] = {"text": m.get("m_text", m.get("m_Text", "")), "size": r2(m.get("m_fontSize", m.get("m_FontData", {}).get("m_FontSize", 14))),
                             "color": vec(m.get("m_fontColor", m.get("m_Color", {"r": 1, "g": 1, "b": 1, "a": 1})), "rgba"),
                             "h": m.get("m_HorizontalAlignment", 1), "v": m.get("m_VerticalAlignment", 256),
                             "auto": bool(m.get("m_enableAutoSizing", 0)), "min": r2(m.get("m_fontSizeMin", 0)), "max": r2(m.get("m_fontSizeMax", 0)),
                             "wrap": bool(m.get("m_enableWordWrapping", 1)), "enabled": bool(m.get("m_Enabled", 1)),
                             "raycast": bool(m.get("m_RaycastTarget", 1)),
                             # TMP's margins: left, top, right, bottom
                             "margin": [r2(m.get("m_margin", {}).get(k, 0)) for k in ("x", "y", "z", "w")]}
            elif sn == "Button":
                n["button"] = {"interactable": bool(m.get("m_Interactable", 1))}
            elif sn == "ShopButton":
                n["shop"] = {"activate": [p.path_id for p in c.toActivate], "deactivate": [p.path_id for p in c.toDeactivate],
                             "variation": c.variationInfo.path_id if c.variationInfo else 0,
                             "deactivated": bool(c.deactivated), "failure": bool(c.failure)}
            elif sn == "VariationInfo":
                def ref(p):
                    return p.path_id if p is not None and p.path_id else 0
                n["variation"] = {"cost": int(c.cost), "weapon": c.weaponName, "varPage": ref(c.varPage), "costText": ref(c.costText),
                                  "buyButton": ref(c.buyButton), "equipText": ref(c.equipText), "equipButtons": ref(c.equipButtons),
                                  "equipStatusButton": ref(c.equipStatusButton), "icon": ref(c.icon), "orderButtons": ref(c.orderButtons)}
            elif sn == "MoneyText":
                n["money"] = True
            elif sn == "Mask":
                n["mask"] = True
            elif sn == "ShopCategory":
                n["category"] = True
            elif sn in ("VerticalLayoutGroup", "HorizontalLayoutGroup"):
                pd = m.get("m_Padding", {})
                n["group"] = {"kind": "v" if sn.startswith("Vertical") else "h",
                              "padding": [pd.get("m_Left", 0), pd.get("m_Right", 0), pd.get("m_Top", 0), pd.get("m_Bottom", 0)],
                              "spacing": r2(m.get("m_Spacing", 0)), "align": m.get("m_ChildAlignment", 0),
                              "controlW": bool(m.get("m_ChildControlWidth", 0)), "controlH": bool(m.get("m_ChildControlHeight", 0)),
                              "expandW": bool(m.get("m_ChildForceExpandWidth", 0)), "expandH": bool(m.get("m_ChildForceExpandHeight", 0)),
                              "reverse": bool(m.get("m_ReverseArrangement", 0)), "enabled": bool(m.get("m_Enabled", 1))}
            elif sn == "GridLayoutGroup":
                pd = m.get("m_Padding", {})
                n["group"] = {"kind": "grid", "padding": [pd.get("m_Left", 0), pd.get("m_Right", 0), pd.get("m_Top", 0), pd.get("m_Bottom", 0)],
                              "cell": vec(m["m_CellSize"], "xy"), "spacingXY": vec(m["m_Spacing"], "xy"), "align": m.get("m_ChildAlignment", 0),
                              "startCorner": m.get("m_StartCorner", 0), "startAxis": m.get("m_StartAxis", 0),
                              "constraint": m.get("m_Constraint", 0), "count": m.get("m_ConstraintCount", 1), "enabled": bool(m.get("m_Enabled", 1))}
            elif sn == "LayoutElement":
                n["element"] = {"ignore": bool(m.get("m_IgnoreLayout", 0)), "minW": r2(m.get("m_MinWidth", -1)), "minH": r2(m.get("m_MinHeight", -1)),
                                "prefW": r2(m.get("m_PreferredWidth", -1)), "prefH": r2(m.get("m_PreferredHeight", -1)),
                                "flexW": r2(m.get("m_FlexibleWidth", -1)), "flexH": r2(m.get("m_FlexibleHeight", -1))}
            elif sn == "ContentSizeFitter":
                n["fitter"] = {"h": m.get("m_HorizontalFit", 0), "v": m.get("m_VerticalFit", 0)}
            elif sn == "HudOpenEffect":
                # the window's opening: its scale from 0.05 grown out on x, then y (YFirst: y first), when it is enabled
                n["open"] = {"speed": r2(m.get("speed", 30)), "skip": bool(m.get("skip", 0)), "reverse": bool(m.get("reverse", 0)),
                             "yFirst": bool(m.get("YFirst", 0)), "noScale": bool(m.get("dontUseScale", 0)), "enabled": bool(m.get("m_Enabled", 1))}
        for ch in tr.m_Children:
            walk(ch.read(), idx)

    walk(canvas, -1)

    # references held as GameObject / component path ids -> node indices (a component's own GameObject)
    comp_go = {}
    for o in env.objects:
        if o.type.name == "MonoBehaviour":
            try:
                comp_go[o.path_id] = o.read().m_GameObject.path_id
            except Exception:  # noqa: BLE001
                pass

    def to_node(pid):
        if not pid:
            return -1
        if pid in by_go:
            return by_go[pid]
        g = comp_go.get(pid)
        return by_go.get(g, -1)

    for n in nodes:
        if "shop" in n:
            s = n["shop"]
            s["activate"] = [to_node(p) for p in s["activate"]]
            s["deactivate"] = [to_node(p) for p in s["deactivate"]]
            s["variation"] = to_node(s["variation"])
        if "variation" in n:
            v = n["variation"]
            for k in ("varPage", "costText", "buyButton", "equipText", "equipButtons", "equipStatusButton", "icon", "orderButtons"):
                v[k] = to_node(v[k])
    json.dump({"source": key, "mount": mount, "nodes": nodes, "sprites": sprites}, open(out, "w", encoding="utf-8"), indent=0)
    print(f"wrote {out}: {len(nodes)} nodes, {len(sprites)} sprites")


if __name__ == "__main__":
    main()
