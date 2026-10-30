"""Assemble the UltraCraft Reimagined download: release/UltraCraft Reimagined Setup/ (and a zip of it).

Only our own work and freely licensed tools go in - never any ULTRAKILL files, and not FMOD (the setup downloads it):
  Setup UltraCraft Reimagined.exe   launcher (installer/launcher, Rust)
  runtime/        Python (PSF) + UnityPy, TypeTreeGeneratorAPI, fmod_toolkit's Python code, pyfmodex, texture2ddecoder,
                  etcpak, astc-encoder-py, tpk_ar (MIT) + Pillow (MIT-CMU) + lz4, attrs, fsspec (BSD / MIT) + brotli (MIT)
                  + archspec (MIT / Apache-2.0) + tkinter (Tcl/Tk licence)
  setup/          setup.pyw, engine.py, version.json, data/, tools/extract (our exporters)
  bin/            ffmpeg (GPL-3, portable)
  payload/        UltraCraft-Reimagined-<version>-code.jar: the mod's code (ours), no ULTRAKILL files
  license/        all of the above licences + CREDITS

    python installer/build_package.py [--zip]        (build the mod first: ./gradlew build codeJar)
"""
import json
import shutil
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
NAME = "UltraCraft Reimagined"
OUT = REPO / "release" / f"{NAME} Setup"
PY = Path(sys.base_prefix)
SITE = PY / "Lib" / "site-packages"
# (distribution, its top-level folders / files in site-packages)
PACKAGES = [("unitypy", ["UnityPy"]), ("typetreegeneratorapi", ["TypeTreeGeneratorAPI"]), ("fmod_toolkit", ["fmod_toolkit"]),
            ("pyfmodex", ["pyfmodex"]), ("pillow", ["PIL"]), ("lz4", ["lz4"]), ("brotli", ["brotli.py", "_brotli.cp312-win_amd64.pyd"]),
            ("etcpak", ["etcpak"]), ("texture2ddecoder", ["texture2ddecoder"]), ("astc_encoder_py", ["astc_encoder"]),
            ("fsspec", ["fsspec"]), ("attrs", ["attr", "attrs"]), ("tpk_ar", ["tpk_ar"]), ("archspec", ["archspec"])]
STDLIB_SKIP = {"site-packages", "test", "idlelib", "ensurepip", "turtledemo", "lib2to3", "pydoc_data", "__pycache__"}
# every exporter (they import each other), and nothing else from tools/
TOOLS = sorted(f"extract/{f.name}" for f in (REPO / "tools" / "extract").glob("*.py"))
FFMPEG = next((Path.home() / "AppData/Local/Microsoft/WinGet/Packages").glob("Gyan.FFmpeg*/ffmpeg-*_build"))


def copytree(src, dst, skip=()):
    for p in src.rglob("*"):
        rel = p.relative_to(src)
        if any(part in skip for part in rel.parts):
            continue
        q = dst / rel
        if p.is_dir():
            q.mkdir(parents=True, exist_ok=True)
        else:
            q.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, q)


def dist_info(dist):
    hits = [d for d in SITE.glob("*.dist-info") if d.name.split("-")[0].lower().replace("-", "_") == dist]
    if not hits:
        raise SystemExit(f"{dist} is not installed in {SITE}")
    return hits[0]


def runtime():
    rt = OUT / "runtime"
    rt.mkdir(parents=True, exist_ok=True)
    for f in ["python.exe", "pythonw.exe", "python3.dll", "python312.dll", "vcruntime140.dll", "vcruntime140_1.dll",
              "LICENSE.txt"]:
        shutil.copy2(PY / f, rt / f)
    copytree(PY / "DLLs", rt / "DLLs")
    copytree(PY / "tcl", rt / "tcl", skip={"demos"})
    copytree(PY / "Lib", rt / "Lib", skip=STDLIB_SKIP)
    sp = rt / "Lib" / "site-packages"
    for dist, tops in PACKAGES:
        for t in tops:
            src = SITE / t
            if src.is_dir():
                # FMOD's own library (fmod.dll) is not ours to pass on: the setup downloads it
                copytree(src, sp / t, skip={"__pycache__", "tests", "libfmod"})
            else:
                shutil.copy2(src, sp / t)
        copytree(dist_info(dist), sp / dist_info(dist).name)
    if list((sp / "fmod_toolkit").rglob("*.dll")):
        raise SystemExit("fmod.dll must not be in the package")


def setup_files():
    s = OUT / "setup"
    s.mkdir(parents=True, exist_ok=True)
    for f in ("setup.pyw", "engine.py", "version.json"):
        shutil.copy2(REPO / "installer" / f, s / f)
    copytree(REPO / "installer" / "data", s / "data")   # CurseForge's Forge loader entry for new profiles
    for t in TOOLS:
        (s / "tools" / t).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / "tools" / t, s / "tools" / t)


def binaries():
    b = OUT / "bin"
    b.mkdir(parents=True, exist_ok=True)
    shutil.copy2(FFMPEG / "bin" / "ffmpeg.exe", b / "ffmpeg.exe")


def tracked_resources():
    import subprocess
    r = subprocess.run(["git", "-C", str(REPO), "ls-files", "src/main/resources"], capture_output=True, text=True, check=True)
    return {f[len("src/main/resources/"):] for f in r.stdout.splitlines()}


def payload():
    p = OUT / "payload"
    p.mkdir(parents=True, exist_ok=True)
    version = json.loads((REPO / "installer" / "version.json").read_text(encoding="utf-8"))["version"]
    jar = REPO / "build" / "libs" / f"UltraCraft-Reimagined-{version}-code.jar"
    if not jar.exists():
        raise SystemExit(f"{jar} is missing: set mod_version={version} in gradle.properties and run ./gradlew build codeJar")
    # the code jar must never carry ULTRAKILL files: every resource in it is one of ours in git
    ours = tracked_resources()
    with zipfile.ZipFile(jar) as z:
        res = [n for n in z.namelist() if not n.endswith("/") and not n.endswith(".class") and not n.startswith("META-INF/")
               and n.split("/")[0] in ("assets", "data")]
        bad = [n for n in res if n not in ours]
        if bad:
            raise SystemExit(f"the code jar holds files that are not our tracked resources: {bad[:5]}")
        # every mixin config's refmap must be in the jar, or the mixins fail outside the dev run ("No refMap loaded")
        names = set(z.namelist())
        for cfg in [n for n in names if n.endswith(".mixins.json")]:
            refmap = json.loads(z.read(cfg)).get("refmap")
            if refmap and refmap not in names:
                raise SystemExit(f"{cfg} names {refmap}, which is not in the code jar")
    shutil.copy2(jar, p / jar.name)


def licences():
    d = OUT / "license"
    d.mkdir(parents=True, exist_ok=True)
    shutil.copy2(REPO / "installer" / "licenses" / "CREDITS.txt", d / "CREDITS.txt")
    shutil.copy2(PY / "LICENSE.txt", d / "Python-LICENSE.txt")
    for dist, _ in PACKAGES:
        di = dist_info(dist)
        for f in [*di.glob("LICENSE*"), *di.glob("COPYING*"), *di.glob("NOTICE*"), *(di / "licenses").rglob("*")]:
            if f.is_file():
                shutil.copy2(f, d / f"{dist}-{f.name}{'' if f.suffix else '.txt'}")
    shutil.copy2(FFMPEG / "LICENSE", d / "ffmpeg-GPL-3.0.txt")
    shutil.copy2(FFMPEG / "README.txt", d / "ffmpeg-build-README.txt")
    (d / "ffmpeg-source.txt").write_text(
        "bin/ffmpeg.exe is FFmpeg, the unmodified static Windows build from www.gyan.dev (GPL v3; build configuration in\n"
        "ffmpeg-build-README.txt). Source: https://ffmpeg.org/download.html; the external libraries' sources are linked\n"
        "from https://www.gyan.dev/ffmpeg/builds/. It is portable: the setup runs it from bin/ while converting the\n"
        "sounds - it is not installed on your PC and the mod never uses it.\n", encoding="utf-8")


def launcher():
    exe = REPO / "installer" / "launcher" / "target" / "release" / "ultracraft_reimagined_setup.exe"
    if exe.exists():
        shutil.copy2(exe, OUT / f"Setup {NAME}.exe")
    else:
        print("!! launcher not built (cargo +stable-x86_64-pc-windows-gnu build --release in installer/launcher)")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for child in OUT.iterdir():
        shutil.rmtree(child) if child.is_dir() else child.unlink()
    runtime()
    setup_files()
    binaries()
    payload()
    licences()
    launcher()
    shutil.copy2(REPO / "installer" / "icon" / "ultracraft.ico", OUT / "ultracraft.ico")   # wizard window
    (OUT / "READ ME FIRST.txt").write_text(
        f"{NAME} - setup\n\n"
        f"Double-click \"Setup {NAME}.exe\". You need ULTRAKILL (Steam; the base game, no DLC needed) installed and\n"
        f"Minecraft Java Edition. The setup builds the {NAME} mod from YOUR copy of ULTRAKILL - no ULTRAKILL files\n"
        "are included here - and puts it into a Minecraft Forge 1.20.1 profile (yours, or a new one it makes in\n"
        "CurseForge, Prism Launcher or MultiMC). It needs the internet once, for FMOD (the sound library ULTRAKILL uses).\n\n"
        "The jar it builds contains ULTRAKILL's models, art, animations and sounds from your own game: it is for you\n"
        "only. Please don't share it; share this setup instead.\n\n"
        "Command windows may flash while it runs; that is normal.\n", encoding="utf-8")
    size = sum(f.stat().st_size for f in OUT.rglob("*") if f.is_file()) / 1e6
    print(f"{OUT}: {size:.0f} MB")
    if "--zip" in sys.argv:
        version = json.loads((REPO / "installer" / "version.json").read_text(encoding="utf-8"))["version"]
        z = OUT.parent / f"{NAME} Setup {version}.zip"
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            for f in OUT.rglob("*"):
                if f.is_file():
                    zf.write(f, f.relative_to(OUT.parent))
        print(f"{z}: {z.stat().st_size / 1e6:.0f} MB")


if __name__ == "__main__":
    main()
