"""Export Font assets (TTF/OTF bytes) from the game's bundles.

    python tools/extract/export_fonts.py <out dir> <addressable key> [more keys]

e.g.  python tools/extract/export_fonts.py work/assets/fonts "Assets/Fonts/VCR_OSD_MONO_1.001.ttf"
Writes <out dir>/<font name>.ttf (or .otf when the data starts with 'OTTO').
"""
import os
import sys

from ukenv import env_for_key


def export(out_dir: str, keys):
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for key in keys:
        env, entries = env_for_key(key)
        iid = entries[0].internal_id
        for p, o in env.container.items():
            if not (p == iid or p.endswith(iid)) or o.type.name != "Font":
                continue
            f = o.read()
            data = bytes(f.m_FontData)
            ext = ".otf" if data[:4] == b"OTTO" else ".ttf"
            path = os.path.join(out_dir, f.m_Name + ext)
            with open(path, "wb") as fh:
                fh.write(data)
            print(f"wrote {path} ({len(data) // 1024} KB)")
            written.append(path)
    return written


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    export(sys.argv[1], sys.argv[2:])
