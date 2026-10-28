"""For each weapon prefab: the animator's clips, its skinned meshes + textures, the mount chain, and the
weapon script's serialized fields (projectile prefabs, sounds, numbers).

    python tools/re/probe_weapons.py "Assets/Prefabs/Weapons/Shotgun Pump.prefab" [...]
"""
import sys

sys.path.insert(0, "tools/extract")
from rigs import controller_clips, load_rig  # noqa: E402

SCRIPTS = {"Shotgun", "Nailgun", "Railcannon", "RocketLauncher", "Revolver", "Punch", "HookArm"}

for key in sys.argv[1:]:
    rig = load_rig(key)
    print(f"== {key}: root {rig.root_name}, {len(rig.bones)} bones, mount {[(m['name'], [round(v, 3) for v in m['pos']], [round(v, 3) for v in m['scale']]) for m in rig.mount]}")
    for go_name, path, smr in rig.skinned:
        mats = []
        for pp in smr.m_Materials:
            try:
                m = pp.read()
                tex = None
                for name, env in m.m_SavedProperties.m_TexEnvs:
                    if name in ("_MainTex", "_BaseMap") and env.m_Texture.path_id:
                        tex = env.m_Texture.read().m_Name
                        break
                mats.append((m.m_Name, tex))
            except Exception as ex:  # noqa: BLE001
                mats.append(("?", str(ex)[:40]))
        print(f"   mesh {go_name} ({path}) mats {mats}")
    try:
        clips = controller_clips(rig.controller)
        print("   clips:", [(c.m_Name, round(float(c.m_MuscleClip.m_StopTime) if hasattr(c, 'm_MuscleClip') else 0, 3)) for c in clips])
    except Exception as ex:  # noqa: BLE001
        print("   clips: ?", ex)
    # weapon script fields on the prefab root
    root = None
    try:
        t = rig.animator.m_GameObject.read().m_Transform.read()
        while t.m_Father and t.m_Father.path_id:
            t = t.m_Father.read()
        root = t.m_GameObject.read()
    except Exception as ex:  # noqa: BLE001
        print("   root ?", ex)
    if root is not None:
        for comp in root.m_Components:
            try:
                c = comp.read()
                if c.type.name != "MonoBehaviour":
                    continue
                script = c.m_Script.read().m_Name if c.m_Script and c.m_Script.path_id else "?"
                if script not in SCRIPTS:
                    continue
                print(f"   [{script}]")
                for k, v in c.__dict__.items():
                    if k.startswith("m_") or k in ("object_reader", "assets_file", "reader", "version", "platform", "byte_size", "path_id", "type"):
                        continue
                    if isinstance(v, (int, float, str, bool)):
                        print(f"      {k} = {v}")
                    elif hasattr(v, "path_id") and v.path_id:
                        try:
                            o = v.read()
                            print(f"      {k} -> {o.type.name if hasattr(o, 'type') else type(o).__name__}:{getattr(o, 'm_Name', '?')}")
                        except Exception:  # noqa: BLE001
                            print(f"      {k} -> (unresolved)")
                    elif isinstance(v, list) and v and hasattr(v[0], "path_id"):
                        names = []
                        for pp in v[:8]:
                            try:
                                names.append(getattr(pp.read(), "m_Name", "?"))
                            except Exception:  # noqa: BLE001
                                names.append("?")
                        print(f"      {k} -> {names}")
            except Exception as ex:  # noqa: BLE001
                print("   comp ?", ex)
