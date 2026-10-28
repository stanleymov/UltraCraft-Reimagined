"""Resolve the HUD AudioSources' clips (style entry tick, stamina ready flash, heal flash) by name.

    python tools/re/probe_hud_audio.py
"""
import sys

sys.path.insert(0, "tools/extract")
from ukenv import env_for_key  # noqa: E402

WANTED = {"StyleCanvas", "StatsPanel", "GunPanel", "Stamina Slider 1", "Stamina Slider 2", "GreenHPFlash", "WarningHPFlash", "Panel (2)"}
env, entries = env_for_key("Assets/Prefabs/Player/Player.prefab")
for obj in env.objects:
    if obj.type.name != "AudioSource":
        continue
    try:
        a = obj.read()
        go = a.m_GameObject.read()
        if go.m_Name not in WANTED:
            continue
        clip = a.m_audioClip.read() if a.m_audioClip and a.m_audioClip.path_id else None
        print(f"{go.m_Name:18} clip={clip.m_Name if clip else None} vol={a.m_Volume} pitch={a.m_Pitch} playOnAwake={a.m_PlayOnAwake} loop={a.Loop if hasattr(a, 'Loop') else '?'}")
    except Exception as ex:
        print("?", ex)
