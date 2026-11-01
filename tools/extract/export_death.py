"""Export ULTRAKILL's death screen texts from the HUD canvas: the DeathSequence's diagnostic log (TextAppearByLines: the
lines and their delay) and the BlackScreen's "you are dead" text, as a JSON the mod's death screen reads.

    python tools/extract/export_death.py <out.json>
"""
import json
import os
import sys

from ukenv import env_for_key, script_name

KEY = "Assets/Prefabs/UI/Canvas.prefab"


def comps(go):
    for c in go.m_Components:
        yield c.component.read() if hasattr(c, "component") else c.read()


def find(env, name):
    for o in env.objects:
        if o.type.name == "GameObject" and o.read().m_Name == name:
            return o.read()
    return None


def walk(go):
    yield go
    for c in comps(go):
        if c.object_reader.type.name in ("Transform", "RectTransform"):
            for ch in c.m_Children:
                yield from walk(ch.read().m_GameObject.read())


def main():
    out = sys.argv[1]
    env, _ = env_for_key(KEY)
    data = {}
    seq = find(env, "DeathSequence")
    for go in walk(seq):
        for c in comps(go):
            if c.object_reader.type.name != "MonoBehaviour":
                continue
            tree = c.object_reader.read_typetree()
            name = script_name(c)
            if name == "TextAppearByLines":
                data["lineDelay"] = tree.get("delay", 0.05)
            if "m_text" in tree and tree["m_text"]:
                data["lines"] = tree["m_text"].split("\n")
                data["linesFontSize"] = tree.get("m_fontSize", 36)
    black = find(env, "BlackScreen")
    for go in walk(black):
        if go.m_Name != "YouDiedText":
            continue
        for c in comps(go):
            if c.object_reader.type.name != "MonoBehaviour":
                continue
            tree = c.object_reader.read_typetree()
            if "m_Text" in tree:
                parts = tree["m_Text"].split("\n")
                data["deadText"] = parts[0]
                # (the whole text: best fit sizes it with its blank lines, the last line is the restart prompt)
                data["deadLines"] = parts
                data["deadFontSize"] = tree.get("m_FontData", {}).get("m_FontSize", 14)
    if "lines" not in data or "deadText" not in data:
        sys.exit("death screen texts not found")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("death screen:", len(data["lines"]), "lines, delay", data["lineDelay"])


if __name__ == "__main__":
    main()
