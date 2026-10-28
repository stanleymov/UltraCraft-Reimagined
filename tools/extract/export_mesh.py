"""Export a prefab's static meshes (MeshFilter + MeshRenderer, not skinned) as a rig JSON that the mod's
RigInstance can draw: one bone per node, every mesh rigid to its node (weight 1), bind poses identity.
Unity built-in primitives (Sphere / Cube / Quad / Plane / Capsule / Cylinder, which live in Unity's default
resources and not in the bundles) come out as `"builtin": "<name>"` meshes for the mod to generate.
ProBuilder objects (no stored Mesh: ProBuilder builds it at load from its own MonoBehaviour) are rebuilt from that
component's m_Positions / m_Faces / m_Textures0, with flat face normals (smoothing group 0 = hard edges).

    python tools/extract/export_mesh.py "<addressable prefab key>" <out.json> [--skip name,name]
"""
import json
import os
import sys

from UnityPy.helpers.MeshHelper import MeshHandler

from rigs import _root_gameobject
from ukenv import env_for_key

BUILTIN = {10207: "sphere", 10202: "cube", 10210: "quad", 10209: "plane", 10208: "capsule", 10206: "cylinder"}


def material_texture(mat):
    try:
        for name, env in mat.m_SavedProperties.m_TexEnvs:
            if name in ("_MainTex", "_BaseMap", "_BaseColorMap") and env.m_Texture.path_id:
                return env.m_Texture.read().m_Name
        for name, env in mat.m_SavedProperties.m_TexEnvs:
            if env.m_Texture.path_id:
                return env.m_Texture.read().m_Name
    except Exception:  # noqa: BLE001
        pass
    return None


def material_color(mat):
    try:
        for name, col in mat.m_SavedProperties.m_Colors:
            if name in ("_Color", "_BaseColor", "_TintColor"):
                return [col.r, col.g, col.b, col.a]
    except Exception:  # noqa: BLE001
        pass
    return None


def probuilder(obj):
    """A ProBuilderMesh component's geometry as (vertices, normals, uvs, indices), or None."""
    try:
        t = obj.read_typetree()
    except Exception:  # noqa: BLE001
        return None
    if "m_Positions" not in t or "m_Faces" not in t:
        return None
    pos = [(p["x"], p["y"], p["z"]) for p in t["m_Positions"]]
    uv = [(u["x"], u["y"]) for u in t.get("m_Textures0") or []]
    if len(uv) != len(pos):
        uv = [(0.0, 0.0)] * len(pos)
    nrm = [[0.0, 0.0, 0.0] for _ in pos]
    tris = []
    for f in t["m_Faces"]:
        idx = list(f["m_Indexes"])
        tris.extend(idx)
        for i in range(0, len(idx) - 2, 3):
            a, b, c = (pos[j] for j in idx[i:i + 3])
            u = [b[k] - a[k] for k in range(3)]
            v = [c[k] - a[k] for k in range(3)]
            n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]]
            for j in idx[i:i + 3]:
                for k in range(3):
                    nrm[j][k] += n[k]
    for n in nrm:
        ln = (n[0] ** 2 + n[1] ** 2 + n[2] ** 2) ** 0.5 or 1.0
        n[:] = [x / ln for x in n]
    return pos, nrm, uv, tris


def flatten(vecs, n):
    out = []
    for v in vecs:
        out.extend(float(x) for x in v[:n])
    return out


def export(key: str, out: str, skip=()):
    env, entries = env_for_key(key)
    root = _root_gameobject(env, entries)
    data = {"source": key, "root": root.m_Name, "mount": [], "bones": [], "meshes": []}

    def walk(go, parent, prefix):
        tr = go.m_Transform.read()
        path = (prefix + "/" + go.m_Name) if prefix else (go.m_Name if parent >= 0 else "")
        idx = len(data["bones"])
        p, r, s = tr.m_LocalPosition, tr.m_LocalRotation, tr.m_LocalScale
        data["bones"].append({"name": go.m_Name, "path": path, "parent": parent,
                              "pos": [p.x, p.y, p.z], "rot": [r.x, r.y, r.z, r.w], "scale": [s.x, s.y, s.z]})
        if go.m_Name not in skip:
            mesh_pp, mats, disabled, pb = None, [], False, None
            for comp in go.m_Components:
                if comp.deref().type.name == "MonoBehaviour":
                    pb = pb or probuilder(comp.deref())
                    continue
                try:
                    c = comp.read()
                except Exception:  # noqa: BLE001
                    continue
                tn = c.object_reader.type.name if hasattr(c, "object_reader") else type(c).__name__
                if tn == "MeshFilter":
                    mesh_pp = c.m_Mesh
                elif tn == "MeshRenderer":
                    # a renderer the prefab has switched off is not drawn in the game (the rocket's own outer mesh hid most of
                    # its stripes)
                    if not getattr(c, "m_Enabled", True):
                        mesh_pp = None
                        disabled = True
                    for pp in c.m_Materials:
                        try:
                            m = pp.read()
                            mats.append({"name": m.m_Name, "texture": material_texture(m), "color": material_color(m)})
                        except Exception:  # noqa: BLE001
                            mats.append({"name": None, "texture": None, "color": None})
            if pb is not None and not disabled and mats:
                pos, nrm, uv, tris = pb
                n = len(pos)
                data["meshes"].append({"name": go.m_Name, "node": path, "nodeBone": idx, "bones": [idx],
                                       "bindposes": [[1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]],
                                       "texture": mats[0]["texture"], "color": mats[0]["color"],
                                       "vertices": flatten(pos, 3), "normals": flatten(nrm, 3), "uvs": flatten(uv, 2),
                                       "boneIndices": [0, 0, 0, 0] * n, "boneWeights": [1.0, 0.0, 0.0, 0.0] * n, "indices": tris,
                                       "submeshes": [{"first": 0, "count": len(tris), "material": mats[0]["name"], "texture": mats[0]["texture"]}]})
                print(f"  probuilder {go.m_Name}: {n} verts, {len(tris) // 3} tris, tex {mats[0]['texture']}")
            elif mesh_pp is not None and mesh_pp.path_id and not disabled:
                entry = {"name": go.m_Name, "node": path, "nodeBone": idx, "bones": [idx], "bindposes": [[1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]],
                         "texture": mats[0]["texture"] if mats else None, "color": mats[0]["color"] if mats else None}
                if mesh_pp.file_id != 0 and mesh_pp.path_id in BUILTIN and mesh_pp.file_id == 8 or (mesh_pp.path_id in BUILTIN and not _resolves(mesh_pp)):
                    # a primitive without a textured material is a collider / parry / interrupt helper (invisible in game)
                    if not mats or not mats[0]["texture"]:
                        entry = None
                    else:
                        entry.update({"builtin": BUILTIN[mesh_pp.path_id], "vertices": [], "normals": [], "uvs": [], "boneIndices": [], "boneWeights": [], "indices": [], "submeshes": []})
                else:
                    try:
                        mesh = mesh_pp.read()
                        mh = MeshHandler(mesh)
                        mh.process()
                        verts = mh.m_Vertices or []
                        n = len(verts)
                        tris, subs = [], []
                        for si, sm in enumerate(mesh.m_SubMeshes):
                            first = len(tris)
                            start = sm.firstByte // (2 if mh.m_Use16BitIndices else 4)
                            seg = mh.m_IndexBuffer[start:start + sm.indexCount]
                            tris.extend(int(x) for x in seg)
                            subs.append({"first": first, "count": len(seg), "material": mats[si]["name"] if si < len(mats) else None,
                                         "texture": mats[si]["texture"] if si < len(mats) else None})
                        entry.update({"vertices": flatten(verts, 3), "normals": flatten(mh.m_Normals or [], 3), "uvs": flatten(mh.m_UV0 or [], 2),
                                      "boneIndices": [0, 0, 0, 0] * n, "boneWeights": [1.0, 0.0, 0.0, 0.0] * n, "indices": tris, "submeshes": subs})
                        print(f"  mesh {go.m_Name}: {n} verts, {len(tris) // 3} tris, tex {entry['texture']}")
                    except Exception as ex:  # noqa: BLE001
                        print(f"  mesh {go.m_Name}: unreadable ({ex})")
                        entry = None
                if entry is not None:
                    data["meshes"].append(entry)
        for ch in tr.m_Children:
            try:
                walk(ch.read().m_GameObject.read(), idx, path)
            except Exception as ex:  # noqa: BLE001
                print("  child ?", ex)

    walk(root, -1, "")
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"wrote {out}: {len(data['bones'])} nodes, {len(data['meshes'])} meshes, {os.path.getsize(out) // 1024} KB")


def _resolves(pp):
    try:
        pp.read()
        return True
    except Exception:  # noqa: BLE001
        return False


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    skip = ()
    if "--skip" in sys.argv:
        skip = tuple(sys.argv[sys.argv.index("--skip") + 1].split(","))
        args = [a for a in args if a != sys.argv[sys.argv.index("--skip") + 1]]
    if len(args) < 2:
        print(__doc__)
        sys.exit(1)
    export(args[0], args[1], skip)
