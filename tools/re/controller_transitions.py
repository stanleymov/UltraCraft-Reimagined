"""Print an AnimatorController's states and exit-time transitions (AssetRipper YAML in work/ripped).

    python tools/re/controller_transitions.py "work/ripped/ExportedProject/Assets/Punch.controller" [...]
"""
import re
import sys

for path in sys.argv[1:]:
    s = open(path, encoding="utf-8", errors="replace").read()
    docs = re.split(r"^--- !u!(\d+) &(-?\d+)\s*$", s, flags=re.M)
    names, trans, defaults = {}, {}, []
    for i in range(1, len(docs), 3):
        cls, fid, body = docs[i], docs[i + 1], docs[i + 2]
        m = re.search(r"m_Name: (.*)", body)
        name = m.group(1).strip() if m else "?"
        if cls == "1102":
            names[fid] = name
        elif cls == "1101":
            dst = re.search(r"m_DstState: \{fileID: (-?\d+)\}", body)
            het = re.search(r"m_HasExitTime: (\d)", body)
            et = re.search(r"m_ExitTime: ([\d.e-]+)", body)
            dur = re.search(r"m_TransitionDuration: ([\d.e-]+)", body)
            conds = re.findall(r"m_ConditionEvent: (\w+)", body)
            trans[fid] = (dst.group(1) if dst else None, het.group(1) if het else "?", et.group(1) if et else "?", dur.group(1) if dur else "?", conds)
        elif cls == "1107":
            d = re.search(r"m_DefaultState: \{fileID: (-?\d+)\}", body)
            if d:
                defaults.append(d.group(1))
    print(f"== {path}: default state {[names.get(d, d) for d in defaults]}")
    for i in range(1, len(docs), 3):
        cls, fid, body = docs[i], docs[i + 1], docs[i + 2]
        if cls != "1102":
            continue
        speed = re.search(r"m_Speed: ([\d.e-]+)", body)
        print(f"  state {names[fid]} speed={speed.group(1) if speed else '?'}")
        if "m_Transitions:" in body:
            seg = body.split("m_Transitions:")[1].split("m_StateMachineBehaviours")[0]
            for tid in re.findall(r"- \{fileID: (-?\d+)\}", seg):
                t = trans.get(tid)
                if t:
                    print(f"     -> {names.get(t[0], '(exit)')} hasExit={t[1]} exit={t[2]} dur={t[3]} conds={t[4]}")
