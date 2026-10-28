"""Compact an REA inspect_analysis_view 'fields' page (saved JSON) into 'Type.field: type' lines.

usage: python -I rea_fields.py page1.json page2.json ... > fields.txt
"""
import json
import sys
from collections import defaultdict

by = defaultdict(list)
for path in sys.argv[1:]:
    d = json.load(open(path, encoding="utf-8"))
    for r in d["normalized_result"]["page"]["rows"]:
        sig = r.get("signature") or {}
        ft = sig.get("field_type") or sig.get("raw_sha256", "?")[:8]
        flags = r.get("flags")
        by[r["declaring_type"]].append((r["name"], ft, flags))

for t in sorted(by):
    print(f"== {t} ({len(by[t])})")
    for name, ft, flags in by[t]:
        print(f"   {name}: {ft}  [{flags}]")
