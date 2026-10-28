"""Sprite metadata for HUD art: rect, 9-slice border, pixelsPerUnit, pivot, texture size.

    python tools/re/probe_sprites.py Assets/Textures/UI/Controls/Round_FillLarge.png [more keys]
"""
import sys

sys.path.insert(0, "tools/extract")
from ukenv import env_for_key  # noqa: E402

for key in sys.argv[1:]:
    try:
        env, entries = env_for_key(key)
    except Exception as ex:
        print(key, "->", ex)
        continue
    iid = entries[0].internal_id
    for p, o in env.container.items():
        if not (p == iid or p.endswith(iid)):
            continue
        if o.type.name == "Sprite":
            s = o.read()
            r = s.m_Rect
            b = s.m_Border
            tex = s.m_RD.texture.read()
            print(f"{key}: sprite {s.m_Name} rect=({r.x:.0f},{r.y:.0f},{r.width:.0f},{r.height:.0f}) border(l,b,r,t)=({b.x:.0f},{b.y:.0f},{b.z:.0f},{b.w:.0f}) ppu={s.m_PixelsToUnits} pivot=({s.m_Pivot.x:.2f},{s.m_Pivot.y:.2f}) tex={tex.m_Name} {tex.m_Width}x{tex.m_Height}")
        elif o.type.name == "Texture2D":
            t = o.read()
            print(f"{key}: texture {t.m_Name} {t.m_Width}x{t.m_Height} format={t.m_TextureFormat}")
