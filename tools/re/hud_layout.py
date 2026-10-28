"""Print the HUD layout from the Player prefab dump: RectTransform anchors/offsets, Image sprite +
colour, Slider ranges, TMP text/size/colour — for GunCanvas and StyleCanvas.

    python tools/re/hud_layout.py [work/dump/Player.json] > work/dump/hud_layout.txt
"""
import json
import sys

src = sys.argv[1] if len(sys.argv) > 1 else "work/dump/Player.json"
d = json.load(open(src, encoding="utf-8"))


def v(x, keys=("x", "y", "z", "w")):
    if isinstance(x, dict):
        return "(" + ", ".join(f"{x[k]:.4g}" if isinstance(x.get(k), (int, float)) else str(x.get(k)) for k in keys if k in x) + ")"
    return str(x)


def col(c):
    if isinstance(c, dict) and "r" in c:
        return f"rgba({c['r']:.3f},{c['g']:.3f},{c['b']:.3f},{c['a']:.3f})"
    return str(c)


def describe(n, depth, out):
    comps = n.get("components", [])
    name = n.get("name")
    active = n.get("active", True)
    line = "  " * depth + f"{name}" + ("" if active else "  [INACTIVE]")
    extra = []
    for c in comps:
        t = c.get("type")
        s = c.get("script")
        if t == "RectTransform":
            extra.append(f"anchor {v(c.get('m_AnchorMin'))}-{v(c.get('m_AnchorMax'))} pos {v(c.get('m_AnchoredPosition'))} size {v(c.get('m_SizeDelta'))} pivot {v(c.get('m_Pivot'))} scale {v(c.get('m_LocalScale'))} rot {v(c.get('m_LocalRotation'))}")
        elif t == "Image":
            extra.append(f"Image sprite={c.get('m_Sprite')} color={col(c.get('m_Color'))} type={c.get('m_Type')} fill={c.get('m_FillMethod')}/{c.get('m_FillAmount')} preserve={c.get('m_PreserveAspect')}")
        elif t == "RawImage":
            extra.append(f"RawImage tex={c.get('m_Texture')} color={col(c.get('m_Color'))}")
        elif t == "Slider":
            extra.append(f"Slider dir={c.get('m_Direction')} min={c.get('m_MinValue')} max={c.get('m_MaxValue')} value={c.get('m_Value')} fill={c.get('m_FillRect')}")
        elif t == "TextMeshProUGUI" or s == "TextMeshProUGUI":
            extra.append(f"TMP text={c.get('m_text')!r} size={c.get('m_fontSize')} min/max={c.get('m_fontSizeMin')}/{c.get('m_fontSizeMax')} auto={c.get('m_enableAutoSizing')} align={c.get('m_HorizontalAlignment') or c.get('m_textAlignment')} color={col(c.get('m_fontColor'))} font={c.get('m_fontAsset')} style={c.get('m_fontStyle')} wrap={c.get('m_enableWordWrapping')} overflow={c.get('m_overflowMode')}")
        elif t == "Text":
            extra.append(f"Text text={c.get('m_Text')!r} size={c.get('m_FontData', {}).get('m_FontSize')} color={col(c.get('m_Color'))}")
        elif t == "CanvasScaler":
            extra.append(f"CanvasScaler mode={c.get('m_UiScaleMode')} ref={v(c.get('m_ReferenceResolution'))} match={c.get('m_MatchWidthOrHeight')}")
        elif t == "Canvas":
            extra.append(f"Canvas renderMode={c.get('m_RenderMode')} planeDistance={c.get('m_PlaneDistance')} sortingOrder={c.get('m_SortingOrder')}")
        elif t == "CanvasGroup":
            extra.append(f"CanvasGroup alpha={c.get('m_Alpha')}")
        elif s and s not in ("CanvasRenderer",):
            fields = {k: val for k, val in c.items() if k not in ("type", "script", "m_Enabled") and not isinstance(val, (dict, list))}
            refs = {k: val for k, val in c.items() if isinstance(val, str) and (val.startswith("GameObject:") or val.startswith("Sprite:") or val.startswith("Texture") or "pptr" in val)}
            extra.append(f"{s} {fields} {refs}" if fields or refs else s)
    out.append(line)
    for e in extra:
        out.append("  " * depth + "    - " + e)
    for ch in n.get("children", []):
        describe(ch, depth + 1, out)


def find(n, path):
    if n.get("name") == path[0]:
        if len(path) == 1:
            return n
        for ch in n.get("children", []):
            r = find(ch, path[1:])
            if r:
                return r
    return None


out = []
for target in (("Player", "Main Camera", "HUD Camera", "HUD", "GunCanvas"), ("Player", "Main Camera", "HUD Camera", "HUD", "StyleCanvas"),
               ("Player", "Main Camera", "HUD Camera"), ("Player", "Canvas")):
    node = find(d, target)
    if node is None:
        out.append(f"== {'/'.join(target)}: not found")
        continue
    out.append(f"== {'/'.join(target)}")
    if target[-1] in ("HUD Camera", "Canvas"):
        # just the node itself + one level
        describe({**node, "children": [] if target[-1] == "HUD Camera" else [{**c, "children": []} for c in node.get("children", [])]}, 0, out)
    else:
        describe(node, 0, out)
print("\n".join(out))
