"""UltraCraft Reimagined setup wizard (tkinter). The work itself: engine.py."""
import os
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, str(Path(__file__).resolve().parent))
import engine  # noqa: E402

BG = "#14161a"
PANEL = "#1f2228"
FG = "#e6e2d6"
DIM = "#9a978c"
ACCENT = "#c8261e"       # ULTRAKILL red
GREEN = "#7bc043"
RED = "#d0453a"
WARN = "#e0a33a"
TITLE_FONT = ("Segoe UI", 20, "bold")
BODY_FONT = ("Segoe UI", 10)
SMALL_FONT = ("Segoe UI", 9)
NEW = "__new__"
OTHER = "__other__"


class Wizard(tk.Tk):
    def __init__(self):
        super().__init__()
        self.version = engine.setup_version()["version"]
        self.title(f"UltraCraft Reimagined {self.version} Setup")
        self.geometry("780x600")
        self.minsize(720, 560)
        self.configure(bg=BG)
        icon = engine.PACKAGE / "ultracraft.ico"
        if icon.exists():
            try:
                self.iconbitmap(str(icon))
            except tk.TclError:
                pass
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TFrame", background=BG)
        style.configure("Panel.TFrame", background=PANEL)
        style.configure("TLabel", background=BG, foreground=FG, font=BODY_FONT)
        style.configure("Panel.TLabel", background=PANEL, foreground=FG, font=BODY_FONT)
        style.configure("Title.TLabel", background=BG, foreground=FG, font=TITLE_FONT)
        style.configure("Dim.TLabel", background=BG, foreground=DIM, font=SMALL_FONT)
        style.configure("TButton", font=BODY_FONT, padding=(14, 6), background="#2f333b", foreground=FG, bordercolor="#4a4f59")
        style.map("TButton", background=[("active", "#3b4049"), ("disabled", "#23262c")], foreground=[("disabled", "#666a72")])
        style.configure("Accent.TButton", background=ACCENT, foreground="white")
        style.map("Accent.TButton", background=[("active", "#e0372e"), ("disabled", "#4a2422")])
        style.configure("TRadiobutton", background=PANEL, foreground=FG, font=BODY_FONT, indicatorcolor=PANEL)
        style.map("TRadiobutton", background=[("active", PANEL)], indicatorcolor=[("selected", ACCENT)],
                  foreground=[("disabled", "#666a72")])
        style.configure("red.Horizontal.TProgressbar", troughcolor=PANEL, background=ACCENT, bordercolor=PANEL,
                        lightcolor=ACCENT, darkcolor=ACCENT)

        self.game = engine.find_ultrakill()
        self.profiles = engine.find_profiles()
        self.launchers = engine.launchers()
        self.choice = tk.StringVar(value=(NEW + self.launchers[0]) if self.launchers else
                                   (str(self.profiles[0]["mods"]) if self.profiles else OTHER))
        self.other = tk.StringVar(value="")
        self.installer = None
        self.jar = None

        self.body = ttk.Frame(self, padding=(28, 22, 28, 8))
        self.body.pack(fill="both", expand=True)
        bar = ttk.Frame(self, padding=(28, 8, 28, 18))
        bar.pack(fill="x", side="bottom")
        self.back_btn = ttk.Button(bar, text="< Back", command=self.back)
        self.next_btn = ttk.Button(bar, text="Next >", style="Accent.TButton", command=self.next)
        self.cancel_btn = ttk.Button(bar, text="Cancel", command=self.cancel)
        self.cancel_btn.pack(side="right")
        self.next_btn.pack(side="right", padx=(0, 10))
        self.back_btn.pack(side="right", padx=(0, 10))
        self.pages = [self.page_welcome, self.page_game, self.page_profile, self.page_install, self.page_done]
        self.page = 0
        self.protocol("WM_DELETE_WINDOW", self.cancel)
        self.show()

    # ------------------------------------------------------------------ navigation
    def clear(self):
        for w in self.body.winfo_children():
            w.destroy()

    def show(self):
        self.clear()
        self.back_btn.state(["!disabled"] if 0 < self.page < 3 else ["disabled"])
        self.next_btn.state(["!disabled"])
        self.next_btn.configure(text="Next >")
        self.pages[self.page]()

    def next(self):
        if self.page == 2 and not self.target_ok():
            return
        if self.page == len(self.pages) - 1:
            self.destroy()
            return
        self.page += 1
        self.show()

    def back(self):
        if self.page > 0:
            self.page -= 1
            self.show()

    def cancel(self):
        if self.installer and self.page == 3:
            if not messagebox.askyesno("UltraCraft Reimagined Setup", "Stop the setup? No jar will be made."):
                return
            self.installer.cancel()
        self.destroy()

    def text(self, parent, s, style="TLabel", wrap=700, **kw):
        lab = ttk.Label(parent, text=s, style=style, wraplength=wrap, justify="left")
        lab.pack(anchor="w", **kw)
        return lab

    # ------------------------------------------------------------------ pages
    def page_welcome(self):
        ttk.Label(self.body, text=f"UltraCraft Reimagined {self.version}", style="Title.TLabel").pack(anchor="w")
        self.text(self.body, "Play as V1 in Minecraft: ULTRAKILL's movement, arms, weapons, style meter and shop "
                             "(Forge 1.20.1).", pady=(2, 14))
        self.text(self.body, (
            "This setup builds the mod on your PC from YOUR OWN copy of ULTRAKILL - no ULTRAKILL files come with the "
            "download. You need ULTRAKILL (Steam; the base game is enough, no DLC) and Minecraft Java Edition. The setup "
            "puts the finished UltraCraft Reimagined mod into a Forge 1.20.1 profile of your modding launcher (CurseForge, Prism "
            "Launcher or MultiMC): a new UltraCraft Reimagined profile, or one you already have. It takes a few minutes, needs an "
            "internet connection for one small download (FMOD, the sound library ULTRAKILL uses) and about 2 GB of free "
            "space while it works."), pady=(0, 12))
        warn = ttk.Frame(self.body, style="Panel.TFrame", padding=14)
        warn.pack(fill="x", pady=(0, 12))
        ttk.Label(warn, text="Heads up: command windows", style="Panel.TLabel", font=("Segoe UI", 10, "bold"),
                  foreground=WARN).pack(anchor="w")
        ttk.Label(warn, style="Panel.TLabel", wraplength=680, justify="left", text=(
            "While it works, the setup runs small helper tools that read ULTRAKILL's files. You may see black command "
            "windows open or flash for a moment. That is normal.")).pack(anchor="w", pady=(4, 0))
        if engine.curseforge_running():
            self.text(self.body, ("CurseForge is open: the setup will ask you to close it before it installs (CurseForge "
                                  "must stay closed while the setup runs)."), style="Dim.TLabel", pady=(0, 8))
        self.text(self.body, (
            "The jar the setup builds contains ULTRAKILL's models, art, animations and sounds from your own game, so it is for "
            "you only: please don't share it. Share this setup instead - everyone builds their own."), style="Dim.TLabel")

    def page_game(self):
        ttk.Label(self.body, text="ULTRAKILL", style="Title.TLabel").pack(anchor="w", pady=(0, 12))
        ok, msg, newer = engine.check_ultrakill(self.game)
        installed = engine.installed_jars()
        self.game_ok = ok
        # ULTRAKILL itself (green when found). Not found is only a problem without an UltraCraft Reimagined to update.
        box = ttk.Frame(self.body, style="Panel.TFrame", padding=14)
        box.pack(fill="x", pady=(0, 10))
        head = ttk.Frame(box, style="Panel.TFrame")
        head.pack(fill="x")
        # Green when found; red with an X when it's needed (no UltraCraft Reimagined to update); plain when updating.
        mark, colour = ("✔ ", GREEN) if ok else ("", FG) if installed else ("✖ ", RED)
        ttk.Label(head, text=mark + "ULTRAKILL", style="Panel.TLabel", font=("Segoe UI", 12, "bold"),
                  foreground=colour).pack(side="left")
        ttk.Button(head, text="Browse...", command=self.browse_game).pack(side="right")
        if not ok and installed:
            msg = ("Not found - that's fine for updating: ULTRAKILL is only needed for a first install, or when an "
                   "update brings new ULTRAKILL files.")
        ttk.Label(box, text=msg, style="Panel.TLabel", wraplength=660, justify="left",
                  foreground=WARN if newer or not (ok or installed) else FG).pack(anchor="w", pady=(6, 0))
        # UltraCraft Reimagined already installed somewhere (accent): the update keeps its ULTRAKILL files.
        if installed:
            box2 = ttk.Frame(self.body, style="Panel.TFrame", padding=14)
            box2.pack(fill="x", pady=(0, 10))
            ttk.Label(box2, text="✔ UltraCraft Reimagined", style="Panel.TLabel", font=("Segoe UI", 12, "bold"),
                      foreground=ACCENT).pack(anchor="w")
            lines = [f"UltraCraft Reimagined {i['version']} is already installed on your PC ({i['profile']}, {i['launcher']})."
                     for i in installed]
            lines.append("No need for ULTRAKILL: updating keeps its ULTRAKILL files and only replaces the mod's code.")
            ttk.Label(box2, text="\n".join(lines), style="Panel.TLabel", wraplength=660, justify="left").pack(anchor="w", pady=(6, 0))
        row = ttk.Frame(self.body)
        row.pack(fill="x", pady=(4, 0))
        ttk.Button(row, text="Check again", command=self.recheck).pack(side="left")
        if not ok and not installed:
            self.text(row, "  Install ULTRAKILL through Steam first, then press Check again.", style="Dim.TLabel", side="left")
        self.next_btn.state(["!disabled"] if ok or installed else ["disabled"])

    def recheck(self):
        self.game = engine.find_ultrakill() or self.game
        self.show()

    def browse_game(self):
        d = filedialog.askdirectory(title="ULTRAKILL folder (the one with ULTRAKILL.exe and ULTRAKILL_Data)")
        if d:
            self.game = Path(d)
            self.show()

    def page_profile(self):
        ttk.Label(self.body, text="Where should UltraCraft Reimagined go?", style="Title.TLabel").pack(anchor="w", pady=(0, 12))
        box = ttk.Frame(self.body, style="Panel.TFrame", padding=14)
        box.pack(fill="x")
        # 1. an UltraCraft Reimagined profile in each modding launcher on this PC (made new, or updated)
        for launcher in self.launchers:
            verb = "Update" if engine.uc_profile(launcher) else "Create"
            ttk.Radiobutton(box, text=f"{verb} UltraCraft Reimagined ({launcher})", value=NEW + launcher, variable=self.choice,
                            command=self.update_note).pack(anchor="w", pady=(2, 2))
        # 2. the player's own Forge 1.20.1 profiles
        for p in self.profiles:
            if p["name"] == engine.PROFILE_NAME:
                continue
            ttk.Radiobutton(box, text=f"Use {p['name']} ({p['launcher']})", value=str(p["mods"]), variable=self.choice,
                            command=self.update_note).pack(anchor="w", pady=(2, 2))
        # 3. anywhere else
        row = ttk.Frame(box, style="Panel.TFrame")
        row.pack(fill="x", pady=(2, 0))
        ttk.Radiobutton(row, text="Choose another location", value=OTHER, variable=self.choice,
                        command=self.update_note).pack(side="left")
        e = tk.Entry(row, textvariable=self.other, font=BODY_FONT, bg=BG, fg=FG, insertbackground=FG, relief="flat")
        e.pack(side="left", fill="x", expand=True, ipady=4, padx=(8, 0))
        e.bind("<FocusIn>", lambda _e: (self.choice.set(OTHER), self.update_note()))
        ttk.Button(row, text="Browse...", command=self.browse_mods).pack(side="left", padx=(8, 0))
        if not self.launchers:
            self.text(self.body, ("No modding launcher was found on this PC. Install CurseForge (curseforge.com) or Prism "
                                  "Launcher (prismlauncher.org), start it once, then run this setup again - or choose a "
                                  "mods folder yourself."), style="Dim.TLabel", pady=(10, 0))
        self.what = self.text(self.body, "", pady=(12, 0))
        self.note = self.text(self.body, "", pady=(4, 0))
        self.note.configure(foreground=GREEN)
        self.update_note()

    def browse_mods(self):
        d = filedialog.askdirectory(title="The mods folder to put UltraCraft Reimagined in")
        if d:
            self.other.set(str(Path(d)))
            self.choice.set(OTHER)
            self.update_note()

    def target(self):
        """(mods folder, launcher to make / update the UltraCraft Reimagined profile in, or None)"""
        c = self.choice.get()
        if c.startswith(NEW):
            launcher = c[len(NEW):]
            prof = engine.uc_profile(launcher)
            return (prof["mods"] if prof else engine.new_profile_mods(launcher)), launcher
        if c == OTHER:
            return (Path(self.other.get()) if self.other.get().strip() else None), None
        return Path(c), None

    def update_note(self):
        if not (getattr(self, "note", None) and self.note.winfo_exists()):
            return
        mods, launcher = self.target()
        c = self.choice.get()
        if launcher:
            if engine.uc_profile(launcher):
                what = f"The UltraCraft Reimagined mod will be updated in your UltraCraft Reimagined profile in {launcher}."
            else:
                what = f"The UltraCraft Reimagined mod will be installed in a new {launcher} profile called UltraCraft Reimagined."
        elif c == OTHER:
            what = ("The UltraCraft Reimagined mod will be installed into the folder above." if mods else
                    "Pick a mods folder with Browse.")
        else:
            p = next((p for p in self.profiles if str(p["mods"]) == c), None)
            what = f"The UltraCraft Reimagined mod will be installed into your existing {p['launcher']} profile {p['name']}." if p else ""
        self.what.configure(text=what)
        old = sorted(Path(mods).glob(f"{engine.JAR_PREFIX}*.jar")) if mods and Path(mods).exists() else []
        reuse = engine.reusable_jar(mods)
        if reuse:
            self.note.configure(text=f"✔ {reuse.name} is already there: only the mod's code is updated, its ULTRAKILL "
                                     "files are kept (ULTRAKILL isn't needed).")
            self.next_btn.configure(text="Update")
        elif old:
            self.note.configure(text=f"✔ {old[-1].name} is already there: it will be rebuilt from ULTRAKILL "
                                     "(this update brings new ULTRAKILL files).")
            self.next_btn.configure(text="Update")
        else:
            self.note.configure(text="")
            self.next_btn.configure(text="Install")

    def ask_close_curseforge(self):
        """CurseForge must be closed while the setup changes its profile: offer to close it and carry on."""
        dlg = tk.Toplevel(self)
        dlg.withdraw()
        dlg.title("UltraCraft Reimagined Setup")
        dlg.configure(bg=BG)
        dlg.resizable(False, False)
        dlg.transient(self)
        frame = ttk.Frame(dlg, padding=18)
        frame.pack(fill="both", expand=True)
        self.text(frame, "CurseForge is open", style="Title.TLabel", wrap=440, pady=(0, 8))
        self.text(frame, "CurseForge keeps its profiles in memory and would block some of the installer features, like the "
                         "custom profile picture. Please close CurseForge to continue the installation.", wrap=440, pady=(0, 14))
        status = self.text(frame, "", style="Dim.TLabel", wrap=440)
        result = {"ok": False}
        buttons = ttk.Frame(frame)
        buttons.pack(anchor="e", pady=(6, 0))

        def finished(ok):
            if ok:
                result["ok"] = True
                dlg.destroy()
            else:
                status.configure(text="CurseForge didn't close. Please exit it yourself (right-click its icon by the clock "
                                      "and choose Exit), then press Install again.")
                close_btn.state(["!disabled"])
                cancel_btn.state(["!disabled"])

        def close_and_go():
            close_btn.state(["disabled"])
            cancel_btn.state(["disabled"])
            status.configure(text="Closing CurseForge...")
            # Ends the process in the background, so the window stays responsive.
            threading.Thread(target=lambda: self.after(0, finished, engine.close_curseforge()), daemon=True).start()

        close_btn = ttk.Button(buttons, text="Close CurseForge and continue", style="Accent.TButton", command=close_and_go)
        close_btn.pack(side="left", padx=(0, 8))
        cancel_btn = ttk.Button(buttons, text="Cancel", command=dlg.destroy)
        cancel_btn.pack(side="left")
        # Centred over the setup window.
        dlg.update_idletasks()
        x = self.winfo_rootx() + (self.winfo_width() - dlg.winfo_reqwidth()) // 2
        y = self.winfo_rooty() + (self.winfo_height() - dlg.winfo_reqheight()) // 2
        dlg.geometry(f"+{max(0, x)}+{max(0, y)}")
        dlg.deiconify()
        dlg.grab_set()
        self.wait_window(dlg)
        return result["ok"]

    def target_ok(self):
        mods, launcher = self.target()
        p = next((p for p in self.profiles if str(p["mods"]) == self.choice.get()), None)
        if not getattr(self, "game_ok", True) and engine.reusable_jar(mods) is None:
            messagebox.showerror("UltraCraft Reimagined Setup", "ULTRAKILL is needed to install UltraCraft Reimagined here: there is no "
                                 "UltraCraft Reimagined in this profile to update (or this update brings new ULTRAKILL files).\n\n"
                                 "Install ULTRAKILL through Steam, go back and press Check again.")
            return False
        if (launcher == "CurseForge" or (p and p["launcher"] == "CurseForge")) and engine.curseforge_running():
            if not self.ask_close_curseforge():
                return False
        if launcher:
            return True
        if mods is None or not mods.is_absolute():
            messagebox.showerror("UltraCraft Reimagined Setup", "Please pick a profile, or choose a full mods folder path.")
            return False
        if mods.name.lower() != "mods" and not messagebox.askyesno(
                "UltraCraft Reimagined Setup", f"{mods}\n\nThat folder isn't called \"mods\". Put the mod there anyway?"):
            return False
        try:
            mods.mkdir(parents=True, exist_ok=True)
            probe = mods / "_write_test.tmp"
            probe.write_text("ok")
            probe.unlink()
        except OSError as e:
            messagebox.showerror("UltraCraft Reimagined Setup", f"Windows won't let the setup write to\n{mods}\n\n({e.strerror})")
            return False
        return True

    def page_install(self):
        ttk.Label(self.body, text="Building UltraCraft Reimagined", style="Title.TLabel").pack(anchor="w", pady=(0, 10))
        self.step_lbl = self.text(self.body, "Starting...")
        self.prog = ttk.Progressbar(self.body, style="red.Horizontal.TProgressbar", maximum=1000)
        self.prog.pack(fill="x", pady=(8, 10), ipady=3)
        self.text(self.body, "Command windows may flash while the helper tools run - that's normal.", style="Dim.TLabel",
                  pady=(0, 6))
        frame = ttk.Frame(self.body)
        frame.pack(fill="both", expand=True)
        self.logbox = tk.Text(frame, height=12, bg="#0c0d10", fg=DIM, font=("Consolas", 9), relief="flat", wrap="none")
        sb = ttk.Scrollbar(frame, command=self.logbox.yview)
        self.logbox.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.logbox.pack(fill="both", expand=True)
        self.back_btn.state(["disabled"])
        self.next_btn.state(["disabled"])
        mods, launcher = self.target()
        # An update (an UltraCraft Reimagined jar was already there) gets a shorter last page: no key list.
        self.was_update = bool(mods and Path(mods).exists() and list(Path(mods).glob(f"{engine.JAR_PREFIX}*.jar")))
        self.installer = engine.Installer(self.game, mods, launcher,
                                          on_progress=lambda f, s: self.after(0, self.progress, f, s),
                                          on_log=lambda line: self.after(0, self.add_log, line))
        engine.start_thread(self.run_install)

    def progress(self, frac, step):
        if frac is not None:
            self.prog["value"] = frac * 1000
        self.step_lbl.configure(text=step)

    def add_log(self, line):
        self.logbox.insert("end", line + "\n")
        if int(self.logbox.index("end-1c").split(".")[0]) > 4000:
            self.logbox.delete("1.0", "1000.0")
        self.logbox.see("end")

    def run_install(self):
        try:
            self.jar = self.installer.install()
            self.after(0, self.finished, None)
        except engine.Cancelled:
            pass
        except Exception as e:  # noqa: BLE001
            self.after(0, self.finished, e)

    def finished(self, error):
        if error is None:
            self.page = 4
            self.show()
            return
        self.step_lbl.configure(text=f"Setup stopped: {error}", foreground=RED)
        self.add_log(f"ERROR: {error}")
        self.add_log("The full log: %LOCALAPPDATA%\\UltraCraft Reimagined_setup_log.txt")
        self.cancel_btn.configure(text="Close")
        self.installer = None

    def page_done(self):
        title = "UltraCraft Reimagined is updated" if getattr(self, "was_update", False) else "UltraCraft Reimagined is ready"
        ttk.Label(self.body, text=title, style="Title.TLabel").pack(anchor="w", pady=(0, 12))
        self.text(self.body, f"Built: {self.jar}", pady=(0, 10))
        mods, launcher = self.target()
        updated = getattr(self, "was_update", False)
        if launcher and not updated:
            how = (f"Open {launcher} (restart it if it was already open) and play the UltraCraft Reimagined profile. The first "
                   "start downloads Minecraft and Forge, which takes a minute.")
        elif launcher:
            how = f"Open {launcher} (restart it if it was already open) and play the UltraCraft Reimagined profile."
        else:
            how = "Start that profile as usual: UltraCraft Reimagined is in its mods folder."
        self.text(self.body, how, pady=(0, 10 if not updated else 14))
        if not updated:
            # First install only: the keys to get started.
            self.text(self.body, (
                "Keys: sprint = dash, sneak = slide (and ground slam in the air), mouse back = punch, mouse forward = "
                "whiplash, G = change arm, C = next variation (or press the weapon's number key again), R = last weapon. "
                "Craft a Terminal to buy weapons, variations and arms with the P you earn. Change keys under Options > "
                "Controls > Key Binds > UltraCraft Reimagined."),
                style="Dim.TLabel", pady=(0, 14))
        row = ttk.Frame(self.body)
        row.pack(anchor="w")
        p = next((p for p in self.profiles if str(p["mods"]) == self.choice.get()), None)
        if (launcher == "CurseForge" or (p and p["launcher"] == "CurseForge")) and engine.curseforge_exe():
            def open_curseforge():
                os.startfile(str(engine.curseforge_exe()))
                self.destroy()  # the setup is done: CurseForge takes over

            ttk.Button(row, text="Open CurseForge", style="Accent.TButton", command=open_curseforge).pack(side="left", padx=(0, 8))
        ttk.Button(row, text="Open the mods folder", command=lambda: os.startfile(str(Path(self.jar).parent))).pack(side="left")
        self.back_btn.state(["disabled"])
        self.next_btn.configure(text="Finish")
        self.cancel_btn.state(["disabled"])


if __name__ == "__main__":
    Wizard().mainloop()
