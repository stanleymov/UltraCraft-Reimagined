"""Resolve PPtr path ids seen in a prefab dump to asset names (Material / AudioClip / Texture2D / Mesh).

usage: python tools/extract/resolve_pptr.py <addressable key> <path_id> [<path_id> ...]
Prints each object's type and name; for a Material also its shader, texture names and colours.
"""
import sys
import ukenv


def main():
    key = sys.argv[1]
    want = {int(x) for x in sys.argv[2:]}
    env, _ = ukenv.env_for_key(key)
    for obj in env.objects:
        if obj.path_id not in want:
            continue
        try:
            d = obj.read_typetree()
        except Exception as e:  # noqa: BLE001
            print(obj.path_id, obj.type.name, "unreadable", e)
            continue
        name = d.get("m_Name")
        print(f"{obj.path_id} {obj.type.name} {name!r}")
        if obj.type.name == "Material":
            sp = d.get("m_SavedProperties", {})
            texs = {k: v for k, v in sp.get("m_TexEnvs", [])} if isinstance(sp.get("m_TexEnvs"), list) else sp.get("m_TexEnvs", {})
            for tn, tv in (texs.items() if isinstance(texs, dict) else texs):
                tex = tv.get("m_Texture", {}) if isinstance(tv, dict) else {}
                pid = tex.get("m_PathID")
                tname = None
                if pid:
                    for o2 in env.objects:
                        if o2.path_id == pid:
                            try:
                                tname = o2.read_typetree().get("m_Name")
                            except Exception:  # noqa: BLE001
                                tname = "?"
                            break
                print("   tex", tn, tname)
            cols = sp.get("m_Colors", [])
            for cn, cv in (cols.items() if isinstance(cols, dict) else cols):
                print("   col", cn, cv)
            fl = sp.get("m_Floats", [])
            for fn, fv in (fl.items() if isinstance(fl, dict) else fl):
                if fn in ("_Mode", "_Cutoff", "_Glossiness", "_Metallic"):
                    continue
                print("   flt", fn, fv)
            shader = d.get("m_Shader", {})
            print("   shader pptr", shader)


if __name__ == "__main__":
    main()
