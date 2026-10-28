"""Where do the skin weights live for the revolver/arm meshes whose MeshHandler gave none?

    python tools/re/probe_skin.py "Assets/Prefabs/Weapons/Revolver Pierce.prefab"
"""
import sys

sys.path.insert(0, "tools/extract")
from UnityPy.helpers.MeshHelper import MeshHandler  # noqa: E402

from rigs import load_rig  # noqa: E402

key = sys.argv[1] if len(sys.argv) > 1 else "Assets/Prefabs/Weapons/Revolver Pierce.prefab"
rig = load_rig(key)
for go_name, path, smr in rig.skinned:
    mesh = smr.m_Mesh.read()
    mh = MeshHandler(mesh)
    mh.process()
    vd = mesh.m_VertexData
    skin = getattr(mesh, "m_Skin", None)
    print(f"== {go_name} mesh={mesh.m_Name} verts={vd.m_VertexCount} bones={len(smr.m_Bones)} bindposes={len(mesh.m_BindPose)}")
    print("   channels:", [(i, c.dimension, c.format, c.stream, c.offset) for i, c in enumerate(vd.m_Channels) if c.dimension])
    print("   m_Skin:", type(skin).__name__, (len(skin) if skin else skin), (skin[0] if skin else None))
    for a in ("m_BoneWeights", "m_BoneIndices", "m_Skin"):
        v = getattr(mh, a, None)
        print(f"   mh.{a}:", type(v).__name__, (len(v) if hasattr(v, "__len__") and v is not None else v), (v[0] if v else None))
    print("   streams:", [(s.channelMask, s.offset, s.stride, s.dividerOp) for s in getattr(vd, "m_Streams", [])] if hasattr(vd, "m_Streams") else "n/a",
          "datasize", len(vd.m_DataSize) if hasattr(vd, "m_DataSize") else None)
    print("   mesh attrs:", [a for a in dir(mesh) if "kin" in a.lower() or "eight" in a.lower() or "lend" in a.lower()])
