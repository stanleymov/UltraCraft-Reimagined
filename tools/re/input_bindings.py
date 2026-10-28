"""Print ULTRAKILL's default keyboard/mouse bindings from the generated InputActions.cs (AssetRipper export)."""
import json
import re
import sys

src = open(sys.argv[1], encoding="utf-8").read()
import ast
m = re.search(r'FromJson\((@?)"(.*?)"\);', src, re.S)
if not m:
    i = src.find("<Keyboard>/")
    print("no FromJson; context:", src[max(0, i - 400):i + 400])
    raise SystemExit
raw = m.group(2)
text = raw.replace('""', '"') if m.group(1) == "@" else ast.literal_eval('"' + raw + '"')
d = json.loads(text)
print("maps:", [mp["name"] for mp in d["maps"]])
for mp in d["maps"]:
    print("=== map", mp["name"])
    for a in mp["actions"]:
        binds = [b for b in mp["bindings"] if b["action"] == a["name"]]
        direct = [b["path"] for b in binds if not b.get("isComposite") and not b.get("isPartOfComposite")
                  and ("Keyboard" in b["path"] or "Mouse" in b["path"])]
        parts = [f"{b.get('name')}={b['path']}" for b in binds if b.get("isPartOfComposite")
                 and ("Keyboard" in b["path"] or "Mouse" in b["path"])]
        inter = a.get("interactions", "")
        print(f"  {a['name']:<22} {a.get('type', '')[:6]:<6} {direct or parts} {inter}")
