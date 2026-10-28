"""Probe UnityPy's MeshHandler for skin weights/indices on v1_mdl, and list texture sizes."""
import os
import sys

sys.path.insert(0, "tools/extract")
from ukenv import env_for_key  # noqa: E402

env, entries = env_for_key("Assets/Models/V1/v1_combined.fbx")
iid = entries[0].internal_id
for p, o in env.container.items():
    if (p == iid or p.endswith(iid)) and o.type.name == "Mesh":
        d = o.read()
        from UnityPy.helpers.MeshHelper import MeshHandler
        mh = MeshHandler(d)
        mh.process()
        print("handler attrs:", [a for a in dir(mh) if not a.startswith("_")])
        for a in ("m_BoneWeights", "m_BoneIndices", "m_Skin", "m_BlendIndices", "m_BlendWeights", "m_Tangents", "m_Colors", "m_UV1"):
            v = getattr(mh, a, None)
            print(f"  {a}: {type(v).__name__} len={len(v) if hasattr(v, '__len__') and v is not None else v}")
        vd = d.m_VertexData
        print("vertex count", vd.m_VertexCount, "channels:", [(i, c.dimension, c.format, c.stream) for i, c in enumerate(vd.m_Channels) if c.dimension])
        print("submeshes:", [(sm.firstByte, sm.indexCount, sm.firstVertex, sm.vertexCount) for sm in d.m_SubMeshes])
        print("bindposes", len(d.m_BindPose), "boneNameHashes", list(d.m_BoneNameHashes)[:5], "rootBoneHash", d.m_RootBoneNameHash)
        skin = getattr(d, "m_Skin", None)
        print("m_Skin", type(skin).__name__, len(skin) if skin else skin)
        if skin:
            print("  first", skin[0])
        bw = getattr(mh, "m_BoneWeights", None) or getattr(mh, "m_Skin", None)
        if bw:
            print("  first weights", bw[0] if not hasattr(bw[0], "weight") else (bw[0].weight, bw[0].boneIndex))
        break

from PIL import Image  # noqa: E402
root = r"work\assets\Assets"
for sub in (r"Models\V1", r"Models\V1\Arms", r"Textures\UI"):
    print("===", sub)
    for f in sorted(os.listdir(os.path.join(root, sub))):
        fp = os.path.join(root, sub, f)
        if os.path.isfile(fp):
            try:
                with Image.open(fp) as im:
                    print(f"  {f:<52} {im.width}x{im.height} {im.mode}")
            except Exception as ex:
                print("  ", f, "?", ex)
