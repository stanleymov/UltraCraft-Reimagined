"""Export Texture2D / Sprite assets from ULTRAKILL's addressables to PNG, keeping the original
Assets/... folder layout.

usage: python export_textures.py <out_dir> <key prefix> [<key prefix> ...]
  e.g. python export_textures.py work/assets "Assets/Models/V1/" "Assets/Textures/UI/"

Sprites are cut out of their atlas with their rect (and un-rotated) so UI icons come out as single
images; the source Texture2D is exported too.
"""
from __future__ import annotations

import os
import sys

from ukenv import catalog, load_bundles


def safe_path(key: str) -> str:
    return key.replace(":", "_")


def export(out_dir: str, prefixes: list[str], game=None) -> None:
    cat = catalog() if game is None else catalog(game)
    wanted = [e for e in cat.entries
              if e.provider == "BundledAssetProvider"
              and e.resource_type.split(".")[-1] in ("Texture2D", "Sprite")
              and any(k.startswith(p) for p in prefixes for k in e.keys if isinstance(k, str))]
    by_bundle: dict[str, list] = {}
    for e in wanted:
        b = cat.bundle_path(e)
        if b:
            by_bundle.setdefault(b, []).append(e)
    print(f"{len(wanted)} texture/sprite entries in {len(by_bundle)} bundles")
    n_ok = n_fail = 0
    for bundle, entries in by_bundle.items():
        env = load_bundles([bundle])
        ids = {e.internal_id: e for e in entries}
        for path, obj in env.container.items():
            iid = path.rsplit("/", 1)[-1] if "/" in path else path
            entry = ids.get(path) or ids.get(iid)
            if entry is None:
                # container paths in the shared bundles are the original asset paths
                entry = next((e for e in entries if path in e.keys), None)
            if entry is None or obj.type.name not in ("Texture2D", "Sprite"):
                continue
            key = next((k for k in entry.keys if isinstance(k, str) and k.startswith("Assets/")), entry.primary_key)
            rel = os.path.splitext(safe_path(str(key)))[0]
            try:
                data = obj.read()
                img = data.image
                if getattr(data, "m_TextureFormat", None) == 1:  # Alpha8: Unity samples it as white + alpha (UI icons tinted at runtime)
                    from PIL import Image
                    a = img.convert("L") if img.mode != "RGBA" else img.getchannel("A")
                    white = Image.new("L", img.size, 255)
                    img = Image.merge("RGBA", (white, white, white, a))
                dest = os.path.join(out_dir, rel + (".sprite.png" if obj.type.name == "Sprite" else ".png"))
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                img.save(dest)
                n_ok += 1
            except Exception as ex:  # noqa: BLE001 - report and keep going
                n_fail += 1
                print(f"FAIL {key}: {ex!r}"[:200])
    print(f"exported {n_ok}, failed {n_fail}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    export(sys.argv[1], sys.argv[2:])
