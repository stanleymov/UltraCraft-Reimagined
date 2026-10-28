"""List every AudioClip a prefab references: AudioSource clips and AudioClip / GameObject(sound bubble)
fields of its MonoBehaviours, resolved through UnityPy attribute access.

usage: python tools/re/probe_sounds.py "<prefab key>"
"""
import sys
sys.path.insert(0, "tools/extract")
from ukenv import env_for_key, script_name

key = sys.argv[1]
env, entries = env_for_key(key)
iid = entries[0].internal_id
root = None
for p, o in env.container.items():
    if (p == iid or p.endswith(iid)) and o.type.name == "GameObject":
        root = o.read()
        break
seen = set()


def clip_of_gameobject(go):
    """An AudioSource clip on a referenced GameObject (sound bubble prefabs)."""
    try:
        for cp in go.m_Components:
            c = cp.read()
            if c.object_reader.type.name == "AudioSource":
                cl = c.m_audioClip.read()
                return cl.m_Name
    except Exception:
        return None
    return None


def walk(go, path=""):
    p = path + "/" + go.m_Name
    for cp in go.m_Components:
        c = cp.read()
        tn = c.object_reader.type.name
        if tn == "AudioSource":
            try:
                cl = c.m_audioClip.read()
                print(f"{p}: AudioSource clip={cl.m_Name} vol={c.m_Volume:.2f} pitch={c.m_Pitch:.2f} loop={bool(c.m_Loop)} playOnAwake={bool(c.m_PlayOnAwake)}")
            except Exception:
                print(f"{p}: AudioSource (no clip) vol={c.m_Volume:.2f}")
        elif tn == "MonoBehaviour":
            sn = script_name(c)
            for k in dir(c):
                if k.startswith("_") or k.startswith("m_"):
                    continue
                v = getattr(c, k, None)
                items = v if isinstance(v, list) else [v]
                for it in items:
                    if hasattr(it, "path_id") and hasattr(it, "file_id") and it.path_id != 0:
                        try:
                            o = it.read()
                        except Exception:
                            continue
                        t = o.object_reader.type.name
                        if t == "AudioClip":
                            print(f"{p}: {sn}.{k} -> AudioClip {o.m_Name}")
                        elif t == "GameObject":
                            cl = clip_of_gameobject(o)
                            if cl:
                                print(f"{p}: {sn}.{k} -> GameObject {o.m_Name} (AudioSource {cl})")
                        elif t == "AudioSource":
                            try:
                                print(f"{p}: {sn}.{k} -> AudioSource {o.m_audioClip.read().m_Name}")
                            except Exception:
                                pass
    for ch in go.m_Transform.read().m_Children:
        walk(ch.read().m_GameObject.read(), p)


walk(root)
