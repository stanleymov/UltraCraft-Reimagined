"""Resolve the weapons' effect prefabs' AudioSource clips (muzzle flashes, charge bubbles, clunks …) by
GameObject name, across the weapon prefab bundles.

    python tools/re/probe_weapon_audio.py
"""
import sys

sys.path.insert(0, "tools/extract")
from ukenv import env_for_key  # noqa: E402

WANTED = {"NailgunMuzzleFlash", "NailgunMuzzleFlash 2", "ShotgunMuzzleFlash", "RocketLauncherFire", "RailcannonFire", "RailcannonMaliciousFire",
          "GrenadeLauncherSound", "ElectricChargeBubble", "PumpChargeSound", "RocketLauncherClunk", "WarningBeep", "NailgunNoAmmo",
          "NailgunLastShot", "FullCharge", "NailgunMagnetShot", "MagnetShotSound", "ZapperAttachSound", "RocketRideStart", "BulletSpark",
          "LaserHitParticle", "Explosion", "Explosion Super", "Explosion Rocket Harmless", "CoinHit", "Ricochet", "RevolverHit"}
seen = set()
for key in ["Assets/Prefabs/Weapons/Shotgun Pump.prefab", "Assets/Prefabs/Weapons/Nailgun Magnet.prefab", "Assets/Prefabs/Weapons/Railcannon Electric.prefab", "Assets/Prefabs/Weapons/Railcannon Malicious.prefab",
            "Assets/Prefabs/Weapons/Rocket Launcher Freeze.prefab", "Assets/Prefabs/Attacks and Projectiles/Explosions/Explosion.prefab",
            "Assets/Prefabs/Attacks and Projectiles/Explosions/Explosion Super.prefab", "Assets/Prefabs/Attacks and Projectiles/Explosions/Explosion Rocket Harmless.prefab",
            "Assets/Prefabs/Attacks and Projectiles/Hitscan Beams/Revolver Beam.prefab"]:
    try:
        env, entries = env_for_key(key)
    except Exception as ex:  # noqa: BLE001
        print(key, "?", ex)
        continue
    for obj in env.objects:
        if obj.type.name != "AudioSource":
            continue
        try:
            a = obj.read()
            go = a.m_GameObject.read()
            if go.m_Name not in WANTED or (go.m_Name, obj.path_id) in seen:
                continue
            seen.add((go.m_Name, obj.path_id))
            clip = a.m_audioClip.read().m_Name if a.m_audioClip and a.m_audioClip.path_id else None
            print(f"{go.m_Name:28} clip={clip} vol={round(float(a.m_Volume), 3)} pitch={round(float(a.m_Pitch), 3)} playOnAwake={a.m_PlayOnAwake}")
        except Exception:  # noqa: BLE001
            pass

# every AudioSource under the railcannons' fire-sound objects (children included), with their parents' path
def tree(go, path=""):
    path = f"{path}/{go.m_Name}"
    for c in go.m_Components:
        try:
            o = c.read()
        except Exception:  # noqa: BLE001
            continue
        if o.object_reader.type.name == "AudioSource":
            clip = o.m_audioClip.read().m_Name if o.m_audioClip and o.m_audioClip.path_id else None
            print(f"  {path:50} clip={clip} vol={round(float(o.m_Volume), 3)} pitch={round(float(o.m_Pitch), 3)} playOnAwake={o.m_PlayOnAwake} enabled={o.m_Enabled}")
        elif o.object_reader.type.name in ("Transform", "RectTransform"):
            for ch in o.m_Children:
                tree(ch.read().m_GameObject.read(), path)


for key in ["Assets/Prefabs/Weapons/Railcannon Electric.prefab", "Assets/Prefabs/Weapons/Railcannon Malicious.prefab"]:
    env, entries = env_for_key(key)
    for obj in env.objects:
        if obj.type.name == "GameObject":
            g = obj.read()
            if g.m_Name in ("RailcannonFire", "RailcannonMaliciousFire"):
                print(key, g.m_Name)
                tree(g)
