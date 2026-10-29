"""UltraCraft Reimagined setup engine: finds ULTRAKILL through Steam, checks it, finds the player's Minecraft Forge 1.20.1 profiles in
a modding launcher (or makes a new "UltraCraft Reimagined" one in CurseForge / Prism / MultiMC), and builds the mod jar from the
player's own ULTRAKILL files with the bundled tools (setup GUI: setup.pyw).

Package layout (installer/build_package.py makes it):
    Setup UltraCraft Reimagined.exe   launcher -> runtime\\pythonw.exe setup\\setup.pyw
    runtime\\              Python 3.12 + UnityPy and its decoders, TypeTreeGeneratorAPI, Pillow, numpy, tkinter
    setup\\                this engine, the GUI, version.json, tools\\extract (our exporters)
    bin\\                  ffmpeg (GPL, portable), licences
    payload\\              UltraCraft-Reimagined-<version>-code.jar: the mod's code with no ULTRAKILL files in it

FMOD (the audio library UnityPy reads ULTRAKILL's sounds with) may not be passed on, so the setup downloads it from PyPI
(the fmod_toolkit wheel) while it runs. The finished jar contains New Blood's models, textures, animations and sounds
from the player's own copy of the game: it is for that player only and must not be shared. Nothing from ULTRAKILL is in
the download.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ULTRAKILL_APP = "1229490"
NO_WINDOW = 0x08000000
MINECRAFT = "1.20.1"
FORGE = "47.4.10"
NAME = "UltraCraft Reimagined"   # (another mod is called UltraCraft; the mod id stays "ultracraft")
PROFILE_NAME = NAME
JAR_PREFIX = "UltraCraft-Reimagined-"
# FMOD Engine (fmod.dll) inside fmod_toolkit's Windows wheel: downloaded, checked, only fmod.dll is used
FMOD_WHEEL = ("https://files.pythonhosted.org/packages/4e/6b/608e1f9467fa54e370b9643d25d9be21e10cc1370cd4261f828a2c5e932a/"
              "fmod_toolkit-0.1.3-cp38-abi3-win_amd64.whl")
FMOD_SHA256 = "33256c13ab84deadc443c6fd428938827469b0df964261c314863b430239dee2"
FMOD_DLL = "fmod_toolkit/libfmod/Windows/x64/fmod.dll"
# ULTRAKILL builds the exporters were made against (Assembly-CSharp.dll): another build is tried, with a warning
KNOWN_BUILDS = {"816a334fa4bbd78181c1fdf20b2edc5ffc83a61b3ddf0bf71fb7ec925471d21d": "Steam build 22957324"}
# what ULTRAKILL must have
GAME_FILES = ["ULTRAKILL.exe", "ULTRAKILL_Data/Managed/Assembly-CSharp.dll",
              "ULTRAKILL_Data/StreamingAssets/aa/catalog.json",
              "ULTRAKILL_Data/StreamingAssets/aa/StandaloneWindows64/monoscript_monoscripts.bundle"]

HERE = Path(__file__).resolve().parent            # <package>/setup  (development: <repo>/installer)
PACKAGE = HERE.parent
TOOLS = HERE / "tools"
BIN = PACKAGE / "bin"
PAYLOAD = PACKAGE / "payload"
# python.exe beside the GUI's pythonw.exe: the tools print progress, which needs a console-type Python
PY = Path(sys.executable).with_name("python.exe") if Path(sys.executable).with_name("python.exe").exists() else Path(sys.executable)
if not TOOLS.exists():                            # development: run from the repo
    TOOLS = PACKAGE / "tools"
    BIN = PACKAGE / "installer" / "dev_bin"
    PAYLOAD = PACKAGE / "build" / "libs"


# ------------------------------------------------------------------------------------- Steam / ULTRAKILL

def steam_root():
    try:
        import winreg
        for hive, key, val in ((winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
                               (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam", "InstallPath"),
                               (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam", "InstallPath")):
            try:
                with winreg.OpenKey(hive, key) as k:
                    p = Path(winreg.QueryValueEx(k, val)[0])
                    if p.exists():
                        return p
            except OSError:
                pass
    except ImportError:
        pass
    p = Path("C:/Program Files (x86)/Steam")
    return p if p.exists() else None


def steam_libraries():
    root = steam_root()
    libs = []
    if root:
        libs.append(root)
        vdf = root / "steamapps" / "libraryfolders.vdf"
        if vdf.exists():
            for m in re.finditer(r'"path"\s+"([^"]+)"', vdf.read_text(encoding="utf-8", errors="ignore")):
                p = Path(m.group(1).replace("\\\\", "\\"))
                if p not in libs:
                    libs.append(p)
    return [p for p in libs if p.exists()]


def find_ultrakill():
    """ULTRAKILL's folder from Steam's manifests (every Steam library), or None."""
    for lib in steam_libraries():
        acf = lib / "steamapps" / f"appmanifest_{ULTRAKILL_APP}.acf"
        if acf.exists():
            m = re.search(r'"installdir"\s+"([^"]+)"', acf.read_text(encoding="utf-8", errors="ignore"))
            if m:
                p = lib / "steamapps" / "common" / m.group(1)
                if p.exists():
                    return Path(os.path.realpath(p))
    return None


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_ultrakill(path):
    """(ok, message, warning): ok when the setup can build from it; warning when it is a build it was not made for."""
    if path is None:
        return False, ("ULTRAKILL was not found. UltraCraft Reimagined is built from YOUR copy of ULTRAKILL (Steam): install it, "
                       "then press Check again - or point to its folder with Browse."), False
    p = Path(path)
    missing = [f for f in GAME_FILES if not (p / f).exists()]
    if missing:
        return False, (f"No {missing[0]} in {p}. Is this the ULTRAKILL folder (the one with ULTRAKILL.exe and "
                       "ULTRAKILL_Data)? If it is, let Steam verify the game files."), False
    build = KNOWN_BUILDS.get(sha256(p / GAME_FILES[1]))
    if build:
        return True, f"Found: {p}  ({build})", False
    return True, (f"Found: {p}. This is a newer (or older) ULTRAKILL than the one this setup was made for. The setup will "
                  "try it; if a part of the game moved, it stops and says so."), True


# ------------------------------------------------------------------------------------- Minecraft profiles
# UltraCraft Reimagined only ever goes into a modding launcher's profile (CurseForge, Prism Launcher, MultiMC) or a folder the
# player picks - never the Minecraft Launcher.

def appdata():
    return Path(os.environ.get("APPDATA", Path.home() / "AppData/Roaming"))


def curseforge_instances():
    for root in (Path.home() / "curseforge/minecraft/Instances", Path(os.environ.get("USERPROFILE", Path.home())) / "curseforge/minecraft/Instances"):
        if root.exists():
            return root
    return None


def curseforge_installed():
    local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local"))
    return curseforge_instances() is not None or (local / "Programs" / "CurseForge Windows").exists()


def prism_roots():
    out = []
    for name in ("PrismLauncher", "MultiMC"):
        r = appdata() / name
        if (r / "instances").exists() or (r / "prismlauncher.cfg").exists() or (r / "multimc.cfg").exists():
            out.append(r)
    return out


def launcher_name(root):
    return "Prism Launcher" if root.name == "PrismLauncher" else "MultiMC"


def launchers():
    """The modding launchers on this PC that a new UltraCraft Reimagined profile can be made in."""
    out = []
    if curseforge_installed():
        out.append("CurseForge")
    out += [launcher_name(r) for r in prism_roots()]
    return out


def curseforge_exe():
    local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local"))
    exe = local / "Programs" / "CurseForge Windows" / "CurseForge.exe"
    return exe if exe.exists() else None


def curseforge_running():
    """CurseForge keeps its profiles in memory and writes them back: it must be closed while the setup runs."""
    try:
        out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq CurseForge.exe", "/NH"], capture_output=True, text=True,
                             creationflags=NO_WINDOW).stdout
        return "CurseForge.exe" in out
    except OSError:
        return False


def close_curseforge(timeout=8.0):
    """End CurseForge (its main process and the helpers it started). Returns True once it is gone."""
    import time
    try:
        subprocess.run(["taskkill", "/F", "/T", "/IM", "CurseForge.exe"], capture_output=True, creationflags=NO_WINDOW)
    except OSError:
        return False
    end = time.time() + timeout
    while time.time() < end:
        if not curseforge_running():
            return True
        time.sleep(0.3)
    return not curseforge_running()


def find_profiles():
    """Modding-launcher profiles that run Forge 1.20.1: [{"name", "launcher", "mods": Path, "dir": Path}]."""
    found = []

    def add(name, launcher, inst_dir, game_dir):
        mods = Path(game_dir) / "mods"
        if not any(f["mods"] == mods for f in found):
            found.append({"name": name, "launcher": launcher, "mods": mods, "dir": Path(inst_dir)})

    root = curseforge_instances()
    for inst in sorted(root.glob("*/minecraftinstance.json")) if root else []:
        try:
            d = json.loads(inst.read_text(encoding="utf-8"))
            loader = str((d.get("baseModLoader") or {}).get("name", ""))
            if d.get("gameVersion") == MINECRAFT and loader.startswith("forge-"):
                add(d.get("name") or inst.parent.name, "CurseForge", inst.parent, inst.parent)
        except (OSError, ValueError):
            pass
    for r in prism_roots():
        for pack in sorted((r / "instances").glob("*/mmc-pack.json")):
            try:
                comps = {c.get("uid"): c.get("version") for c in json.loads(pack.read_text(encoding="utf-8")).get("components", [])}
                if comps.get("net.minecraft") == MINECRAFT and "net.minecraftforge" in comps:
                    game = pack.parent / ".minecraft"
                    if not game.exists() and (pack.parent / "minecraft").exists():
                        game = pack.parent / "minecraft"
                    name = pack.parent.name
                    cfg = pack.parent / "instance.cfg"
                    if cfg.exists():
                        m = re.search(r"^name=(.*)$", cfg.read_text(encoding="utf-8", errors="ignore"), re.M)
                        if m:
                            name = m.group(1).strip()
                    add(name, launcher_name(r), pack.parent, game)
            except (OSError, ValueError):
                pass
    return found


def uc_profile(launcher):
    """The UltraCraft Reimagined profile this setup made in `launcher` before, or None."""
    for p in find_profiles():
        if p["launcher"] == launcher and p["name"] == PROFILE_NAME:
            return p
    return None


def new_profile_mods(launcher):
    if launcher == "CurseForge":
        root = curseforge_instances() or Path.home() / "curseforge/minecraft/Instances"
        return root / PROFILE_NAME / "mods"
    root = next((r for r in prism_roots() if launcher_name(r) == launcher), appdata() / "PrismLauncher")
    return root / "instances" / PROFILE_NAME / ".minecraft" / "mods"


# ------------------------------------------------------------------------------------- misc

def setup_version():
    v = json.loads((HERE / "version.json").read_text(encoding="utf-8"))
    return {"version": str(v["version"]), "content": int(v.get("content", 1))}


def free_gb(path):
    p = Path(path)
    while not p.exists() and p.parent != p:
        p = p.parent
    return shutil.disk_usage(p).free / 1e9


def start_thread(fn):
    t = threading.Thread(target=fn, daemon=True)
    t.start()
    return t


def jar_name():
    return f"{JAR_PREFIX}{setup_version()['version']}.jar"


def ours(jar):
    """True for any UltraCraft Reimagined jar, whatever its file name (the dev builds were UltraCraft-<version>.jar),
    and never for someone else's mod that is called UltraCraft too: it holds our own classes."""
    try:
        with zipfile.ZipFile(jar) as z:
            return "com/ultracraft/weapon/ParryServer.class" in set(z.namelist())
    except (OSError, zipfile.BadZipFile):
        return False


# Written into every jar the setup builds: which version made it and which ULTRAKILL files it holds.
JAR_INFO = "META-INF/ultracraft_setup.json"


def jar_content(jar):
    """The ULTRAKILL-files version ("content") inside an UltraCraft Reimagined jar the setup built, or None."""
    try:
        with zipfile.ZipFile(jar) as z:
            names = set(z.namelist())
            if "assets/ultracraft/sounds.json" not in names or JAR_INFO not in names:
                return None
            return int(json.loads(z.read(JAR_INFO)).get("content", 0)) or None
    except (OSError, zipfile.BadZipFile, ValueError):
        return None


def code_jar():
    jars = sorted(PAYLOAD.glob(f"{JAR_PREFIX}*-code.jar"))
    return jars[-1] if jars else None


def installed_jars():
    """UltraCraft Reimagined jars already in this PC's modding-launcher profiles that an update can reuse (their ULTRAKILL files
    match this setup): [{"jar": Path, "version": "1.0", "profile": name, "launcher": name}]."""
    found = []
    for p in find_profiles():
        jar = reusable_jar(p["mods"])
        if jar:
            found.append({"jar": jar, "version": jar.stem[len(JAR_PREFIX):], "profile": p["name"],
                          "launcher": p["launcher"]})
    return found


def reusable_jar(mods):
    """An UltraCraft Reimagined jar in `mods` whose ULTRAKILL files are what this setup would build: an update then only needs the
    new code, not ULTRAKILL."""
    if not mods or not Path(mods).exists():
        return None
    want = setup_version()["content"]
    for jar in sorted(Path(mods).glob(f"{JAR_PREFIX}*.jar"), key=lambda p: p.stat().st_mtime, reverse=True):
        if jar_content(jar) == want:
            return jar
    return None


# ------------------------------------------------------------------------------------- install

class Cancelled(Exception):
    pass


class Installer:
    """Runs the steps in a thread; `on_progress(fraction, step text)` and `on_log(line)` are called from that
    thread (the GUI marshals them). `mods` is the mods folder to put the jar in; with `new_profile` (a launcher name:
    "CurseForge", "Prism Launcher" or "MultiMC"), the setup first makes (or reuses) the UltraCraft Reimagined profile in that
    launcher and `mods` is that profile's."""

    def __init__(self, game, mods, new_profile, on_progress, on_log):
        self.game = Path(game) if game else None
        self.new_profile = new_profile
        self.mods = Path(mods) if mods else new_profile_mods(new_profile)
        self.work = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "UCRSetup"
        self.on_progress = on_progress
        self.on_log = on_log
        self.cancelled = False
        self.proc = None
        self.log_file = None

    # -------------------------------------------------- plumbing
    def log(self, line):
        self.on_log(line)
        if self.log_file:
            self.log_file.write(line + "\n")
            self.log_file.flush()

    def step(self, frac, text):
        self.check()
        self.on_progress(frac, text)
        self.log(f"== {text}")

    def check(self):
        if self.cancelled:
            raise Cancelled()

    def cancel(self):
        self.cancelled = True
        if self.proc and self.proc.poll() is None:
            try:
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(self.proc.pid)], creationflags=NO_WINDOW,
                               capture_output=True)
            except OSError:
                pass

    def run(self, cmd, env=None, cwd=None, on_line=None):
        self.check()
        self.proc = subprocess.Popen([str(c) for c in cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                     encoding="utf-8", errors="replace", env=env, cwd=cwd, creationflags=NO_WINDOW)
        for line in self.proc.stdout:
            line = line.rstrip()
            if line:
                self.log("   " + line)
                if on_line:
                    on_line(line)
            if self.cancelled:
                break
        code = self.proc.wait()
        self.check()
        if code != 0:
            raise RuntimeError(f"{Path(str(cmd[1] if len(cmd) > 1 else cmd[0])).name} stopped with an error (code {code}); "
                               "see the log above")

    def download(self, url, dest, what):
        self.log(f"   downloading {what} from {url}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(url, headers={"User-Agent": "UltraCraft-Reimagined-setup"})
        with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            got = 0
            while True:
                self.check()
                chunk = r.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)
                got += len(chunk)
            self.log(f"   {got / 1e6:.1f} MB" + (f" of {total / 1e6:.1f} MB" if total else ""))
        return dest

    # -------------------------------------------------- steps
    def install(self):
        self.mods.parent.mkdir(parents=True, exist_ok=True)
        if self.work.exists():
            shutil.rmtree(self.work, ignore_errors=True)
        self.work.mkdir(parents=True, exist_ok=True)
        log_path = self.work.parent / "UltraCraft_Reimagined_setup_log.txt"
        self.log_file = open(log_path, "w", encoding="utf-8")
        self.log(f"{NAME} setup {setup_version()['version']}  {datetime.now():%Y-%m-%d %H:%M}")
        self.log(f"ULTRAKILL: {self.game}")
        self.log(f"Jar goes to: {self.mods}")
        if code_jar() is None:
            raise RuntimeError(f"payload\\{JAR_PREFIX}code.jar is missing - please extract the whole download again.")
        reuse = reusable_jar(self.mods)
        if reuse is None and not (self.game and check_ultrakill(self.game)[0]):
            raise RuntimeError("ULTRAKILL is needed to install UltraCraft Reimagined here (there is no UltraCraft Reimagined with this "
                               "version's ULTRAKILL files to update).")
        if self.new_profile:
            self.make_profile()
        if reuse is not None:
            # Code-only update: the ULTRAKILL files come from the jar that's already there.
            self.log(f"Updating {reuse.name}: its ULTRAKILL files are kept, ULTRAKILL isn't needed")
            self.step(0.5, f"Updating {NAME}")
            pack = self.work / "pack"
            code = set(zipfile.ZipFile(code_jar()).namelist())
            with zipfile.ZipFile(reuse) as z:
                for info in z.infolist():
                    if info.filename.startswith("assets/") and not info.is_dir() and info.filename not in code:
                        z.extract(info, pack)
            jar = self.build_jar(pack)
        else:
            tools = self.tools()
            pack = self.extract(tools)
            jar = self.build_jar(pack)
            if self.new_profile == "CurseForge":
                self.profile_icon(pack)
        self.step(0.98, "Cleaning up")
        shutil.rmtree(self.work, ignore_errors=True)
        self.step(1.0, "Done")
        self.log(f"Built {jar}")
        self.log_file.close()
        self.log_file = None
        try:
            shutil.copy2(log_path, self.mods.parent / log_path.name)
        except OSError:
            pass
        return jar

    def tools(self):
        self.step(0.01, "Getting the tools ready")
        t = {"ffmpeg": BIN / "ffmpeg.exe"}
        if not t["ffmpeg"].exists():
            w = shutil.which("ffmpeg")
            if not w:
                raise RuntimeError("bin\\ffmpeg.exe is missing - please extract the whole download again.")
            t["ffmpeg"] = Path(w)
        # FMOD, for reading ULTRAKILL's sounds: not ours to pass on, so it comes from PyPI (fmod_toolkit's wheel)
        wheel = self.download(FMOD_WHEEL, self.work / "fmod_toolkit.whl", "FMOD (the sound library ULTRAKILL uses)")
        if sha256(wheel) != FMOD_SHA256:
            raise RuntimeError("the FMOD download did not match the expected file; please try again later")
        dll = self.work / "fmod" / "fmod.dll"
        dll.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(wheel) as z, open(dll, "wb") as f:
            f.write(z.read(FMOD_DLL))
        t["fmod"] = dll
        return t

    def extract(self, t):
        """Run the exporters (tools/extract/build_assets.py) on the player's ULTRAKILL into the work folder's pack."""
        pack = self.work / "pack"
        env = dict(os.environ)
        env.update({"UK_GAME": str(self.game), "UK_WORK": str(self.work / "extract"),
                    "UK_RES": str(pack / "assets" / "ultracraft"), "UK_FFMPEG": str(t["ffmpeg"]),
                    "PYFMODEX_DLL_PATH": str(t["fmod"]), "PYTHONUNBUFFERED": "1", "PYTHONIOENCODING": "utf-8"})
        # build_assets runs one exporter per "> " line: fonts, 13 meshes, textures, sounds, the shop screen, 12 rigs
        # with their animations; then it lays everything out for Minecraft
        names = {"export_fonts.py": "Reading ULTRAKILL's fonts", "export_mesh.py": "Exporting projectiles, coins and the Terminal",
                 "export_textures.py": "Exporting textures and the HUD", "export_sounds.py": "Converting the sounds",
                 "export_canvas.py": "Exporting the shop screen", "export_rig.py": "Exporting V1, the arms and the weapons",
                 "export_clips.py": "Exporting the animations"}
        total = 41
        seen = [0]

        def on_line(line):
            if line.startswith("> "):
                seen[0] += 1
                script = next((n for n in names if n in line), None)
                self.on_progress(0.05 + 0.80 * min(1.0, seen[0] / total), names.get(script, "Exporting"))
            elif line.startswith("layout:") or line.startswith("shop screen"):
                self.on_progress(0.87, "Laying out the files for Minecraft")

        self.step(0.04, "Building ULTRAKILL's files for Minecraft")
        self.run([PY, TOOLS / "extract" / "build_assets.py"], env=env, cwd=str(TOOLS.parent), on_line=on_line)
        for need in ("sounds.json", "uk/rigs/revolver.json", "uk/rigs/v1.json", "font/vcr_osd_mono.ttf"):
            if not (pack / "assets" / "ultracraft" / need).exists():
                raise RuntimeError(f"{need} was not built; see the log above")
        return pack

    def build_jar(self, pack):
        """The mod's code jar + the built ULTRAKILL files -> <mods>/UltraCraft-Reimagined-<version>.jar."""
        self.step(0.92, f"Building {jar_name()}")
        base = code_jar()
        self.mods.mkdir(parents=True, exist_ok=True)
        out = self.mods / jar_name()
        tmp = self.work / out.name
        added = set()
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
            with zipfile.ZipFile(base) as zin:
                for info in zin.infolist():
                    zout.writestr(info, zin.read(info.filename))
                    added.add(info.filename)
            assets = pack / "assets"
            for f in sorted(assets.rglob("*")):
                if f.is_file():
                    name = f.relative_to(pack).as_posix()
                    if name not in added:     # the code jar's own files (lang, models, recipes ...) win
                        zout.write(f, name)
                        added.add(name)
            zout.writestr(JAR_INFO, json.dumps(setup_version()))
        # older UltraCraft Reimagined jars in that folder would load twice
        for old in self.mods.glob("*.jar"):
            if old.name != out.name and ours(old):
                self.log(f"   removing the older {old.name}")
                try:
                    old.unlink()
                except OSError as e:
                    self.log(f"   (could not remove it: {e.strerror}; please delete it yourself)")
        shutil.move(str(tmp), str(out))
        self.log(f"   {out}  ({out.stat().st_size / 1e6:.1f} MB)")
        return out

    def profile_icon(self, pack):
        """The CurseForge profile's picture: ULTRAKILL's U rank, made from the player's own files (so it stays on this
        PC), on black."""
        try:
            from PIL import Image
            rank = Image.open(pack / "assets/ultracraft/textures/uk/ranku.png").convert("RGBA")
            r = rank.crop(rank.getchannel("A").getbbox())
            k = 220 / max(r.size)
            r = r.resize((round(r.width * k), round(r.height * k)), Image.LANCZOS)
            icon = Image.new("RGBA", (256, 256), (0, 0, 0, 255))
            icon.alpha_composite(r, ((256 - r.width) // 2, (256 - r.height) // 2))
            inst = self.mods.parent
            icon.save(inst / "ultracraft.png")
            mi = inst / "minecraftinstance.json"
            data = json.loads(mi.read_text(encoding="utf-8"))
            data["profileImagePath"] = str(inst / "ultracraft.png")
            mi.write_text(json.dumps(data, indent=2), encoding="utf-8")
        except Exception as e:  # noqa: BLE001 - only cosmetic
            self.log(f"   (profile picture skipped: {e})")

    def make_profile(self):
        """The UltraCraft Reimagined profile (Forge 1.20.1) in the chosen modding launcher. The launcher downloads Minecraft and
        Forge itself the first time the profile is played. An existing UltraCraft Reimagined profile is kept as it is."""
        if uc_profile(self.new_profile):
            self.mods = uc_profile(self.new_profile)["mods"]
            return
        self.step(0.02, f"Making the {NAME} profile in {self.new_profile}")
        if self.new_profile == "CurseForge":
            root = curseforge_instances() or Path.home() / "curseforge/minecraft/Instances"
            inst = root / PROFILE_NAME
            (inst / "mods").mkdir(parents=True, exist_ok=True)
            loader = json.loads((HERE / "data" / f"curseforge_forge-{MINECRAFT}-{FORGE}.json").read_text(encoding="utf-8"))
            import uuid
            now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f0Z")
            data = {
                "baseModLoader": loader, "isUnlocked": True, "javaArgsOverride": None, "lastPlayed": "0001-01-01T00:00:00",
                "playedCount": 0, "timePlayed": 0, "manifest": None, "fileDate": "0001-01-01T00:00:00", "installedModpack": None,
                "projectID": 0, "fileID": 0, "customAuthor": None, "modpackOverrides": [], "isMemoryOverride": False,
                "allocatedMemory": 4096, "profileImagePath": None, "groupId": None, "isVanilla": False, "guid": str(uuid.uuid4()),
                "gameTypeID": 432, "installPath": str(inst) + "\\", "name": PROFILE_NAME, "cachedScans": [], "isValid": True,
                "lastPreviousMatchUpdate": "0001-01-01T00:00:00", "lastRefreshAttempt": "0001-01-01T00:00:00", "isEnabled": True,
                "gameVersion": MINECRAFT, "gameVersionFlavor": None, "gameVersionTypeId": None, "preferenceAlternateFile": False,
                "preferenceAutoInstallUpdates": False, "preferenceDeleteOrphanedDependencies": False,
                "preferenceDeleteSavedVariables": False, "preferenceReleaseType": 1, "preferenceModdingFolderPath": None,
                "installDate": now, "installedAddons": [], "installedGamePrerequisites": [], "wasNameManuallyChanged": False,
                "wasGameVersionTypeIdManuallyChanged": False}
            (inst / "minecraftinstance.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
            self.mods = inst / "mods"
        else:
            r = next((r for r in prism_roots() if launcher_name(r) == self.new_profile), appdata() / "PrismLauncher")
            inst = r / "instances" / PROFILE_NAME
            (inst / ".minecraft" / "mods").mkdir(parents=True, exist_ok=True)
            (inst / "instance.cfg").write_text(f"InstanceType=OneSix\nname={PROFILE_NAME}\n", encoding="utf-8")
            pack = {"formatVersion": 1, "components": [
                {"uid": "net.minecraft", "version": MINECRAFT, "important": True},
                {"uid": "net.minecraftforge", "version": FORGE}]}
            (inst / "mmc-pack.json").write_text(json.dumps(pack, indent=2), encoding="utf-8")
            self.mods = inst / ".minecraft" / "mods"
        # (no options.txt: the mod sets its own key defaults the first time it starts)
        self.log(f"   profile '{PROFILE_NAME}' -> {self.mods.parent}")
