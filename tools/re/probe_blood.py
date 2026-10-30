"""ULTRAKILL's blood splatter prefabs (BloodsplatterManager's head / limb / body / small / smallest / splatter ...):
each one's Bloodsplatter.hpAmount, hpOnParticleCollision, its SphereCollider (the 0.25 s heal trigger) and scale, and the
particle burst (max particles, burst count).

    python tools/re/probe_blood.py
"""
import sys

sys.path.insert(0, "tools/extract")
from ukenv import env_for_key, script_name  # noqa: E402

from rigs import _root_gameobject  # noqa: E402


def walk(go, out, depth=0):
    for cp in go.m_Components:
        try:
            obj = cp.deref(); tn = obj.type.name
            if tn == "MonoBehaviour" and script_name(obj.read()) == "Bloodsplatter":
                t = obj.read_typetree()
                out["hpAmount"] = t.get("hpAmount"); out["hpOnParticleCollision"] = t.get("hpOnParticleCollision")
                out["halfChance"] = t.get("halfChance")
            elif tn == "SphereCollider":
                t = obj.read_typetree(); out.setdefault("spheres", []).append((go.m_Name, round(t["m_Radius"], 3), t["m_IsTrigger"], t.get("m_Enabled")))
            elif tn == "Transform" and depth == 0:
                t = obj.read_typetree(); s = t["m_LocalScale"]; out["scale"] = round(s["x"], 3)
            elif tn == "ParticleSystem" and "particles" not in out:
                t = obj.read_typetree()
                b = t["EmissionModule"]["m_Bursts"]
                out["particles"] = {"on": go.m_Name, "max": t["InitialModule"]["maxNumParticles"],
                                    "rate": round(t["EmissionModule"]["rateOverTime"]["scalar"], 1),
                                    "bursts": [round(x["countCurve"]["scalar"], 1) for x in b],
                                    "duration": round(t["lengthInSec"], 3)}
        except Exception as ex:  # noqa: BLE001
            out.setdefault("err", str(ex)[:80])
    for ch in go.m_Transform.read().m_Children:
        walk(ch.read().m_GameObject.read(), out, depth + 1)


for name in ["BS Head", "BS Limb", "BS Body", "BS Body Small", "BS Body Smallest", "BS Splatter", "BS Spray", "BS Underwater"]:
    key = f"Assets/Particles/Blood/{name}.prefab"
    env, entries = env_for_key(key)
    out = {}
    walk(_root_gameobject(env, entries), out)
    print(f"{name:18} {out}")
