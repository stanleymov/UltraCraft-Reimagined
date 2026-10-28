"""Export AudioClips from ULTRAKILL's addressables as OGG (via WAV from UnityPy's FMOD decoder + ffmpeg).

usage: python export_sounds.py <out_dir> <key prefix> [<key prefix> ...] [--ffmpeg path]
  e.g. python export_sounds.py work/assets/sounds "Assets/Sounds/Weapons/" "Assets/Sounds/Player/"

Files keep the original Assets/... path with the extension replaced by .ogg. WAVs that already exist are
skipped, so re-running only converts what is new.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys

from ukenv import catalog, load_bundles


def find_ffmpeg(explicit=None):
    if explicit:
        return explicit
    p = shutil.which("ffmpeg")
    if p:
        return p
    return None


def export(out_dir, prefixes, ffmpeg=None):
    ffmpeg = find_ffmpeg(ffmpeg)
    cat = catalog()
    wanted = [e for e in cat.entries if e.provider == "BundledAssetProvider" and e.resource_type.endswith("AudioClip")
              and any(k.startswith(p) for p in prefixes for k in e.keys if isinstance(k, str))]
    by_bundle = {}
    for e in wanted:
        b = cat.bundle_path(e)
        if b:
            by_bundle.setdefault(b, []).append(e)
    print(f"{len(wanted)} audio clips in {len(by_bundle)} bundles; ffmpeg: {ffmpeg}")
    n_ok = n_fail = n_skip = 0
    for bundle, entries in by_bundle.items():
        env = load_bundles([bundle])
        for path, obj in env.container.items():
            if obj.type.name != "AudioClip":
                continue
            entry = next((e for e in entries if path in e.keys or path.endswith(e.internal_id)), None)
            if entry is None:
                continue
            key = next((k for k in entry.keys if isinstance(k, str) and k.startswith("Assets/")), entry.primary_key)
            rel = os.path.splitext(str(key))[0]
            wav = os.path.join(out_dir, rel + ".wav")
            ogg = os.path.join(out_dir, rel + ".ogg")
            if os.path.exists(ogg):
                n_skip += 1
                continue
            try:
                clip = obj.read()
                samples = clip.samples  # {name: wav bytes}
                if not samples:
                    raise RuntimeError("no samples")
                os.makedirs(os.path.dirname(wav), exist_ok=True)
                data = next(iter(samples.values()))
                with open(wav, "wb") as f:
                    f.write(data)
                if ffmpeg:
                    r = subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", ogg], capture_output=True, text=True)
                    if r.returncode != 0:
                        raise RuntimeError(r.stderr.strip()[:200])
                    os.remove(wav)
                n_ok += 1
            except Exception as ex:  # noqa: BLE001
                n_fail += 1
                print(f"FAIL {key}: {ex!r}"[:200])
    print(f"exported {n_ok}, skipped {n_skip}, failed {n_fail}")


if __name__ == "__main__":
    args = sys.argv[1:]
    ff = None
    if "--ffmpeg" in args:
        i = args.index("--ffmpeg")
        ff = args[i + 1]
        del args[i:i + 2]
    export(args[0], args[1:], ff)
