"""Print the scalar/vector fields of chosen MonoBehaviour scripts in dumped prefab JSONs.

usage: python scalars.py <glob of work/dump/*.json> Script1 Script2 ...
"""
import glob
import json
import os
import sys

pattern, scripts = sys.argv[1], set(sys.argv[2:])


def comps(n, out, path=""):
    p = path + "/" + n["name"]
    for c in n["components"]:
        if c.get("script") in scripts:
            out.append((p, c))
    for ch in n.get("children", []):
        comps(ch, out, p)


for f in sorted(glob.glob(pattern)):
    t = json.load(open(f, encoding="utf-8"))
    nodes = t if isinstance(t, list) else [t]
    out = []
    for n in nodes:
        if "components" in n:
            comps(n, out)
        elif n.get("script") in scripts:
            out.append(("/" + n.get("name", "?"), n))
    for p, c in out:
        sc = {k: v for k, v in c.items() if isinstance(v, (int, float, bool)) and k != "m_Enabled"}
        vec = {k: v for k, v in c.items() if isinstance(v, dict) and set(v) <= {"x", "y", "z", "w", "r", "g", "b", "a"} and any(v.values())}
        refs = {k: v for k, v in c.items() if isinstance(v, str) and ":" in v and not v.startswith("pptr(")}
        lists = {k: v for k, v in c.items() if isinstance(v, list) and v}
        print(f"== {os.path.basename(f)[:-5]} {p} [{c['script']}]")
        print("   ", json.dumps(sc))
        if vec:
            print("    vec", json.dumps(vec))
        if refs:
            print("    refs", json.dumps(refs))
        if lists:
            print("    lists", json.dumps(lists)[:600])
