"""Dump an addressable prefab (or any GameObject key) as a JSON tree with every component's
serialized fields, PPtrs resolved to "Type:name" where possible.

usage: python -I dump_prefab.py "Assets/Prefabs/Player/Player.prefab" [out.json] [--depth N]
"""
from __future__ import annotations

import json
import os
import sys

from ukenv import env_for_key, script_name

MAX_LIST = 64


def pptr_label(pp):
    try:
        if pp.path_id == 0:
            return None
        o = pp.read()
        tn = o.object_reader.type.name
        name = getattr(o, "m_Name", None) or ""
        if tn == "MonoBehaviour":
            tn = "MB:" + script_name(o)
            if not name:
                try:
                    name = o.m_GameObject.read().m_Name
                except Exception:
                    pass
        elif tn in ("Transform", "RectTransform"):
            try:
                name = o.m_GameObject.read().m_Name
            except Exception:
                pass
        return f"{tn}:{name}"
    except Exception as e:  # unresolved (other bundle) or unreadable
        return f"pptr({pp.file_id},{pp.path_id})"


def simplify(v, depth=0):
    """Make typetree values JSON-friendly and compact."""
    if isinstance(v, dict):
        if set(v) == {"m_FileID", "m_PathID"}:
            return f"pptr({v['m_FileID']},{v['m_PathID']})"
        return {k: simplify(x, depth + 1) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        out = [simplify(x, depth + 1) for x in v[:MAX_LIST]]
        if len(v) > MAX_LIST:
            out.append(f"... {len(v) - MAX_LIST} more")
        return out
    if isinstance(v, bytes):
        return f"<{len(v)} bytes>"
    if hasattr(v, "path_id") and hasattr(v, "file_id"):
        return pptr_label(v)
    if hasattr(v, "__dict__"):
        return {k: simplify(x, depth + 1) for k, x in vars(v).items() if not k.startswith("_")}
    return v


def component_dict(c):
    tn = c.object_reader.type.name
    d = {"type": tn}
    if tn == "MonoBehaviour":
        d["script"] = script_name(c)
    try:
        tt = c.object_reader.read_typetree()
    except Exception as e:
        d["error"] = repr(e)[:200]
        return d
    skip = {"m_GameObject", "m_ObjectHideFlags", "m_CorrespondingSourceObject", "m_PrefabInstance",
            "m_PrefabAsset", "m_EditorHideFlags", "m_EditorClassIdentifier", "m_Script", "m_Name"}
    for k, v in tt.items():
        if k in skip:
            continue
        if tn in ("Transform", "RectTransform") and k in ("m_Children", "m_Father"):
            continue
        d[k] = simplify(v)
    if tn == "MonoBehaviour":
        # typetree holds raw PPtr dicts; resolve the ones we can through the parsed object
        try:
            for k in list(d):
                if isinstance(d[k], str) and d[k].startswith("pptr(") and hasattr(c, k):
                    d[k] = pptr_label(getattr(c, k)) or d[k]
        except Exception:
            pass
    return d


def go_dict(go, depth, maxdepth):
    node = {"name": go.m_Name, "active": bool(getattr(go, "m_IsActive", 1)),
            "layer": getattr(go, "m_Layer", None), "tag": getattr(go, "m_TagString", None),
            "components": []}
    tr = None
    for cp in go.m_Components:
        try:
            c = cp.read()
        except Exception as e:
            node["components"].append({"type": "?", "error": repr(e)[:100]})
            continue
        if c.object_reader.type.name in ("Transform", "RectTransform"):
            tr = c
        node["components"].append(component_dict(c))
    if tr is not None and depth < maxdepth:
        kids = []
        for ch in tr.m_Children:
            try:
                kids.append(go_dict(ch.read().m_GameObject.read(), depth + 1, maxdepth))
            except Exception as e:
                kids.append({"name": "?", "error": repr(e)[:100]})
        if kids:
            node["children"] = kids
    return node


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    maxdepth = 99
    if "--depth" in sys.argv:
        maxdepth = int(sys.argv[sys.argv.index("--depth") + 1])
    key = args[0]
    out = args[1] if len(args) > 1 else None
    env, entries = env_for_key(key)
    go_entry = next((e for e in entries if e.resource_type.endswith("GameObject")), None)
    if go_entry is None:
        # Not a prefab: a ScriptableObject / MonoBehaviour asset (e.g. WeaponDescriptor). Dump every
        # object the bundle stores under that internal id.
        iid = entries[0].internal_id
        dumped = []
        for path, obj in env.container.items():
            if path.endswith(iid) or path == iid:
                o = obj.read()
                if obj.type.name == "MonoBehaviour":
                    dumped.append(component_dict(o) | {"name": getattr(o, "m_Name", "")})
                else:
                    dumped.append({"type": obj.type.name, "name": getattr(o, "m_Name", "")})
        text = json.dumps(dumped, indent=1, ensure_ascii=False, default=str)
        if out:
            os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
            open(out, "w", encoding="utf-8").write(text)
            print(f"wrote {out} ({len(text)} chars)")
        else:
            print(text)
        return
    # The bundle's container maps internal id -> object; find the root GameObject of that asset.
    root = None
    for path, obj in env.container.items():
        if path.endswith(go_entry.internal_id) or path == go_entry.internal_id:
            if obj.type.name == "GameObject":
                root = obj.read()
                break
    if root is None:  # fall back: name match on a root transform
        want = os.path.splitext(os.path.basename(key))[0]
        for obj in env.objects:
            if obj.type.name == "GameObject":
                go = obj.read()
                if go.m_Name == want and go.m_Transform.read().m_Father.path_id == 0:
                    root = go
                    break
    if root is None:
        sys.exit(f"root GameObject not found for {key}")
    tree = go_dict(root, 0, maxdepth)
    text = json.dumps(tree, indent=1, ensure_ascii=False, default=str)
    if out:
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"wrote {out} ({len(text)} chars)")
    else:
        print(text)


if __name__ == "__main__":
    main()
