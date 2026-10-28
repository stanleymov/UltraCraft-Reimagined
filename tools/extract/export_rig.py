"""Export a prefab's animated rig (bones + skinned meshes + bind poses + material textures) to JSON.

usage: python export_rig.py "<prefab key>" out.json [animator GameObject name]

JSON (Unity units, Unity's left-handed Y-up; the Java renderer mirrors X):
{ "root": name, "bones": [{name, path, parent, pos[3], rot[4] xyzw, scale[3]}],
  "meshes": [{ "name", "texture": "Assets/.../T_x.png", "bones": [bone index per mesh bone],
               "bindposes": [16 floats column-major, per mesh bone],
               "vertices": [x,y,z,...], "normals": [...], "uvs": [u,v,...],
               "boneIndices": [4 per vertex], "boneWeights": [4 per vertex],
               "indices": [...] (triangles, mesh-local vertex indices),
               "submeshes": [{"first": i, "count": n, "texture": ...}] }] }
"""
from __future__ import annotations

import json
import os
import sys

from UnityPy.helpers.MeshHelper import MeshHandler

from rigs import load_rig


def material_texture(mat):
    """Main texture asset name of a material (the file our texture export wrote)."""
    try:
        for name, env in mat.m_SavedProperties.m_TexEnvs:
            if name in ("_MainTex", "_BaseMap", "_BaseColorMap"):
                tex = env.m_Texture.read()
                return tex.m_Name
        for name, env in mat.m_SavedProperties.m_TexEnvs:
            try:
                return env.m_Texture.read().m_Name
            except Exception:
                continue
    except Exception:
        pass
    return None


def flatten(vecs, n):
    out = []
    for v in vecs:
        out.extend(float(x) for x in v[:n])
    return out


# static meshes inside rigs that are effects the game switches on itself (muzzle flashes, charge glows)
# (StoneChunk*: the Hammer Saw's BreakParticleMetalSaw debris, spawned only when the chainsaw breaks)
SKIP_STATIC = {"ChargeEffect", "MuzzleFlash", "Flash", "Glow", "StoneChunk1", "StoneChunk2", "StoneChunk3"}


def export(key: str, out: str, animator_name=None):
    rig = load_rig(key, animator_name)
    by_pid = rig.bone_index_by_path_id()
    data = {"source": key, "root": rig.root_name, "mount": rig.mount,
            "bones": [{"name": b.name, "path": b.path, "parent": b.parent, "pos": list(b.position), "rot": list(b.rotation), "scale": list(b.scale)} for b in rig.bones],
            "meshes": []}
    for go_name, path, smr in rig.skinned:
        mesh = smr.m_Mesh.read()
        mh = MeshHandler(mesh)
        mh.process()
        bone_map = []
        for pp in smr.m_Bones:
            try:
                bone_map.append(by_pid.get(pp.read().object_reader.path_id, -1))
            except Exception:
                bone_map.append(-1)
        mats = []
        for pp in smr.m_Materials:
            try:
                m = pp.read()
                mats.append({"name": m.m_Name, "texture": material_texture(m)})
            except Exception:
                mats.append({"name": None, "texture": None})
        bind = []
        for bp in mesh.m_BindPose:
            bind.append([float(getattr(bp, f"e{r}{c}")) for c in range(4) for r in range(4)])
        verts = mh.m_Vertices or []
        n = len(verts)
        bi, bw = [], []
        if mh.m_BoneIndices and mh.m_BoneWeights:
            for i in range(n):
                ii = list(mh.m_BoneIndices[i]) + [0, 0, 0, 0]
                ww = list(mh.m_BoneWeights[i]) + [0, 0, 0, 0]
                bi.extend(int(x) for x in ii[:4])
                bw.extend(float(x) for x in ww[:4])
        elif mh.m_BoneIndices:
            # BlendIndices channel only (no BlendWeight): every vertex is rigid to one bone (the revolver,
            # the arm meshes) -- weight 1 on that bone
            for i in range(n):
                ii = list(mh.m_BoneIndices[i]) + [0, 0, 0, 0]
                bi.extend(int(x) for x in ii[:4])
                bw.extend([1.0, 0.0, 0.0, 0.0])
        subs = []
        tris = []
        for si, sm in enumerate(mesh.m_SubMeshes):
            first = len(tris)
            idx = mh.m_IndexBuffer
            start = sm.firstByte // (2 if mh.m_Use16BitIndices else 4)
            seg = idx[start:start + sm.indexCount]
            # topology 0 = triangles
            tris.extend(int(x) for x in seg)
            subs.append({"first": first, "count": len(seg), "material": mats[si]["name"] if si < len(mats) else None,
                         "texture": mats[si]["texture"] if si < len(mats) else None})
        data["meshes"].append({
            "name": go_name, "node": path, "nodeBone": by_pid.get(smr.m_GameObject.read().m_Transform.read().object_reader.path_id, -1),
            "bones": bone_map, "bindposes": bind,
            "vertices": flatten(verts, 3), "normals": flatten(mh.m_Normals or [], 3), "uvs": flatten(mh.m_UV0 or [], 2),
            "boneIndices": bi, "boneWeights": bw, "indices": tris, "submeshes": subs,
            "texture": mats[0]["texture"] if mats else None})
        print(f"  mesh {go_name}: {n} verts, {len(tris)//3} tris, {len(bone_map)} bones, tex {mats[0]['texture'] if mats else None}")
    # rigid MeshRenderer meshes under the rig (one bone = their node, identity bind pose)
    from export_mesh import BUILTIN, _resolves, material_color
    for go_name, path, idx, mf, mr in rig.static:
        if go_name in SKIP_STATIC:
            continue
        mesh_pp = mf.m_Mesh
        if not mesh_pp.path_id:
            continue
        mats = []
        for pp in mr.m_Materials:
            try:
                m = pp.read()
                mats.append({"name": m.m_Name, "texture": material_texture(m), "color": material_color(m)})
            except Exception:  # noqa: BLE001
                mats.append({"name": None, "texture": None, "color": None})
        entry = {"name": go_name, "node": path, "nodeBone": idx, "bones": [idx], "bindposes": [[1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]],
                 "texture": mats[0]["texture"] if mats else None, "color": mats[0]["color"] if mats else None}
        if mesh_pp.path_id in BUILTIN and (mesh_pp.file_id == 8 or not _resolves(mesh_pp)):
            if not mats or not mats[0]["texture"]:
                continue  # untextured primitive = helper / effect
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
            except Exception as ex:  # noqa: BLE001
                print(f"  static {go_name}: unreadable ({ex})")
                continue
        data["meshes"].append(entry)
        print(f"  static {go_name} @ {path}: tex {entry['texture']}" + (" builtin " + entry["builtin"] if "builtin" in entry else ""))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"wrote {out}: {len(rig.bones)} bones, {len(data['meshes'])} meshes, {os.path.getsize(out)//1024} KB")


if __name__ == "__main__":
    export(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
