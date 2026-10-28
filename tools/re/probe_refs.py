"""Why do cross-bundle PPtrs not resolve? Try reading the revolver Animator's controller/avatar and a
SkinnedMeshRenderer's mesh/bones through UnityPy, printing the externals table."""
import sys, traceback
sys.path.insert(0, "tools/extract")
from ukenv import env_for_key

key = sys.argv[1] if len(sys.argv) > 1 else "Assets/Prefabs/Weapons/Revolver Pierce.prefab"
env, entries = env_for_key(key)
print("files in env:", len(env.files))
for name, f in list(env.files.items())[:40]:
    print("  ", name, type(f).__name__)
iid = entries[0].internal_id
root = None
for p, o in env.container.items():
    if (p == iid or p.endswith(iid)) and o.type.name == "GameObject":
        root = o
        break
print("root", root)
sf = root.assetsfile
print("serialized file:", sf.name if hasattr(sf, 'name') else sf)
print("externals:")
for i, ex in enumerate(sf.externals):
    print("  ", i + 1, ex.name, getattr(ex, 'path', ''))
# walk components
go = root.read()
def comps(go, depth=0):
    for cp in go.m_Components:
        c = cp.read()
        tn = c.object_reader.type.name
        if tn in ("Animator", "SkinnedMeshRenderer"):
            print("  " * depth, go.m_Name, tn)
            for attr in ("m_Controller", "m_Avatar", "m_Mesh"):
                pp = getattr(c, attr, None)
                if pp is None:
                    continue
                try:
                    o = pp.read()
                    print("  " * depth, "   ", attr, "->", o.object_reader.type.name, getattr(o, "m_Name", ""))
                except Exception as e:
                    print("  " * depth, "   ", attr, "FAILED", pp.file_id, pp.path_id, repr(e)[:160])
            if tn == "SkinnedMeshRenderer":
                try:
                    names = [b.read().m_GameObject.read().m_Name for b in c.m_Bones[:6]]
                    print("  " * depth, "    bones:", len(c.m_Bones), names)
                except Exception as e:
                    print("  " * depth, "    bones FAILED", repr(e)[:160])
    if depth < 6:
        for ch in go.m_Transform.read().m_Children:
            comps(ch.read().m_GameObject.read(), depth + 1)
comps(go)
