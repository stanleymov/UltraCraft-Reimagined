"""Probe what UnityPy gives us for a skinned model + its animation clips (v1_combined.fbx)."""
import sys

sys.path.insert(0, "tools/extract")
from ukenv import env_for_key  # noqa: E402

key = sys.argv[1] if len(sys.argv) > 1 else "Assets/Models/V1/v1_combined.fbx"
env, entries = env_for_key(key)
iid = entries[0].internal_id
print("internal id", iid, "types", sorted({e.resource_type for e in entries}))
hits = [(p, o) for p, o in env.container.items() if p == iid or p.endswith(iid) or key in p]
print("container hits", len(hits))
for p, o in hits[:60]:
    d = o.read()
    name = getattr(d, "m_Name", "")
    print(f"  {o.type.name:<18} {name}")
    if o.type.name == "Mesh":
        try:
            from UnityPy.helpers.MeshHelper import MeshHandler
            mh = MeshHandler(d)
            mh.process()
            print("     verts", len(mh.m_Vertices or []), "normals", bool(mh.m_Normals), "uv0", bool(mh.m_UV0),
                  "indices", len(mh.m_IndexBuffer or []), "skin", len(d.m_Skin or []), "bindposes", len(d.m_BindPose or []),
                  "submeshes", len(d.m_SubMeshes or []), "boneNameHashes", len(getattr(d, 'm_BoneNameHashes', []) or []))
        except Exception as ex:
            print("     mesh helper failed:", repr(ex)[:200])
    if o.type.name == "AnimationClip":
        tt = o.read_typetree()
        print("     keys:", [k for k in tt.keys() if not k.startswith("m_Name")][:30])
        print("     legacy", tt.get("m_Legacy"), "sampleRate", tt.get("m_SampleRate"),
              "rot curves", len(tt.get("m_RotationCurves", [])), "pos curves", len(tt.get("m_PositionCurves", [])),
              "euler", len(tt.get("m_EulerCurves", [])), "float", len(tt.get("m_FloatCurves", [])))
        mc = tt.get("m_MuscleClip", {})
        clip = mc.get("m_Clip", {}) if isinstance(mc, dict) else {}
        print("     muscle clip keys:", list(mc.keys())[:20] if isinstance(mc, dict) else mc)
        if clip:
            print("     clip:", {k: (len(v) if isinstance(v, (list, dict)) else v) for k, v in clip.items()})
            for part in ("m_StreamedClip", "m_DenseClip", "m_ConstantClip"):
                pc = clip.get(part)
                if isinstance(pc, dict):
                    print(f"       {part}:", {k: (len(v) if isinstance(v, (list, bytes)) else v) for k, v in pc.items()})
        cbc = tt.get("m_ClipBindingConstant", {})
        if cbc:
            gb = cbc.get("genericBindings", [])
            print("     bindings", len(gb), "first", gb[:3])
    if o.type.name == "Avatar":
        tt = o.read_typetree()
        av = tt.get("m_Avatar", {})
        tos = tt.get("m_TOS", [])
        print("     TOS entries", len(tos), "sample", tos[:5])
