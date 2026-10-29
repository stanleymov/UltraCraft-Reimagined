"""Run every exporter and lay the results out inside the mod's resources (git-ignored `uk` folders).

usage: python tools/extract/build_assets.py [--skip-export]

Layout written under src/main/resources/assets/ultracraft/:
  uk/rigs/<rig>.json, uk/clips/<rig>/<clip>.json        (export_rig / export_clips)
  textures/uk/<lowercase name>.png                      (textures the rigs reference + HUD art)
  sounds/uk/<folder>/<lowercase name>.ogg + sounds.json  (events: ultracraft:uk.<folder>.<name>)
Minecraft identifiers must be lowercase [a-z0-9/._-], so names are lowercased and spaces become '_'.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
# (the setup builds into its own work and output folders: UK_WORK, UK_RES; ffmpeg from UK_FFMPEG)
WORK = os.environ.get("UK_WORK") or os.path.join(ROOT, "work", "assets")
RES = os.environ.get("UK_RES") or os.path.join(ROOT, "src", "main", "resources", "assets", "ultracraft")
FFMPEG = os.environ.get("UK_FFMPEG") or shutil.which("ffmpeg")
PY = sys.executable

RIGS = {
    # rig name: (prefab key, animator GameObject name or None)
    "revolver": ("Assets/Prefabs/Weapons/Revolver Pierce.prefab", None),
    "revolver_alt": ("Assets/Prefabs/Weapons/Alternative Revolver Pierce.prefab", None),
    "feedbacker": ("Assets/Prefabs/Weapons/Arm Blue.prefab", None),
    "knuckleblaster": ("Assets/Prefabs/Weapons/Arm Red.prefab", None),
    # the variations of a weapon share mesh and textures (the variation colour is a runtime tint), so one rig each
    "shotgun": ("Assets/Prefabs/Weapons/Shotgun Saw.prefab", None),
    "nailgun": ("Assets/Prefabs/Weapons/Nailgun Magnet.prefab", None),
    "railcannon": ("Assets/Prefabs/Weapons/Railcannon Electric.prefab", None),
    "rocket": ("Assets/Prefabs/Weapons/Rocket Launcher Freeze.prefab", None),
    # the Whiplash lives inside the Player prefab (Main Camera/Punch/Hook Arm)
    "whiplash": ("Assets/Prefabs/Player/Player.prefab", "Hook Arm"),
    # (the Saw prefab: the same hammer plus the Sawed-On's chainsaw on ChainSawAttachPoint, hidden on the other two)
    "hammer": ("Assets/Prefabs/Weapons/Hammer Saw.prefab", None),
    # V1 itself (third person): the 2.5D platformer's Player (v1_combined + V1Platformer movement clips)
    "v1": ("Assets/Prefabs/Player/PlatformerController.prefab", "Player"),
    "sawblade": ("Assets/Prefabs/Weapons/Sawblade Launcher Magnet.prefab", None),
}
# static meshes (projectiles, effects) exported by export_mesh.py into the same rig format
MESHES = {
    "proj_pellet": "Assets/Prefabs/Attacks and Projectiles/Shotgun Projectile.prefab",
    "proj_nail": "Assets/Prefabs/Attacks and Projectiles/Nails/Nail.prefab",
    "proj_grenade": "Assets/Prefabs/Attacks and Projectiles/Grenade.prefab",
    "proj_rocket": "Assets/Prefabs/Attacks and Projectiles/Rocket.prefab",
    "proj_cannonball": "Assets/Prefabs/Attacks and Projectiles/Cannonball.prefab",
    "proj_harpoon": "Assets/Prefabs/Attacks and Projectiles/Harpoon.prefab",
    "fx_explosion": "Assets/Prefabs/Attacks and Projectiles/Explosions/Explosion.prefab",
    "proj_sawblade": "Assets/Prefabs/Attacks and Projectiles/NailAlt.prefab",
    "proj_coin": "Assets/Prefabs/Attacks and Projectiles/Coin.prefab",
    "proj_shell": "Assets/Particles/ShotgunShell.prefab",
    "grapple_point": "Assets/Prefabs/Levels/Interactive/GrapplePoint.prefab",
    "proj_chainsaw": "Assets/Prefabs/Attacks and Projectiles/ChainsawProjectile.prefab",
    # the Terminal (Shop.prefab: ShopTerminal + its drawer; ShopTerminalProxy is the shadow stand-in, hidden in the mod)
    "shop_terminal": "Assets/Prefabs/Levels/Shop.prefab",
}
# ProBuilder parts left out of a mesh (export_mesh --skip): the saw's hub cylinder is drawn by ProjectileRenderer.hub,
# and the launched chainsaw is drawn without the Cube body (as tested before export_mesh learnt ProBuilder)
MESH_SKIP = {"proj_sawblade": "Cylinder", "proj_chainsaw": "Cube"}
TEXTURE_PREFIXES = ["Assets/Models/V1/", "Assets/Models/Weapons/", "Assets/Textures/UI/", "Assets/Textures/Revolver/", "Assets/Textures/smokesprite.png", "Assets/Textures/Dev/BonusWhite.jpg",
                    "Assets/Textures/Sprites/", "Assets/Textures/Arms/",
                    # projectile / effect materials
                    "Assets/Textures/Dev/skull2 compressed.jpg", "Assets/Textures/Dev/Basic Color - Dark Grey",
                    "Assets/Textures/floortiles1b", "Assets/Textures/Explosions/", "Assets/Textures/shockwavetex",
                    "Assets/Textures/White.png", "Assets/Textures/Dev/Black.png", "Assets/Textures/Dev/Basic Color - Yellow", "Assets/Textures/Sprites/charge.png", "Assets/Textures/Dev/Bonus.jpg",
                    "Assets/Textures/WhiplashWire.png", "Assets/Models/Objects/Terminals/", "Assets/Textures/sawblade.png", "Assets/Textures/sawblade 1.png", "Assets/Textures/Dev/Basic Color - Orange",
                    # effects: muzzle flashes, blood, sparks, lightning, beams, the Sharpshooter splash
                    "Assets/Textures/Sprites/muzzleflash", "Assets/Textures/Blood/", "Assets/Textures/Dev/spark", "Assets/Textures/Sprites/lightning", "Assets/Textures/Sprites/projectilebeam", "Assets/Textures/Sprites/splash"]
# icons used as item textures (the HUD draws the same sprites at their own aspect)
EMISSIVE_ALPHA = ["T_PistolNew_Emissive", "v1_wingcolour_emissive_colorless", "T_MinosRevolver_128_Emissive", "T_Railcannon_Emission", "T_Nailgun_New_Glow",
                  # (the hammer's dial glows stay as they are: coloured on black, added on)
                  "T_ImpactHammer_Glow"]
# textures the game tints through an ID mask (the shader's CUSTOM_COLORS: _IDTex red channel -> _CustomColor1): baked
# here with the colour the game sets (ColorBlindGet: the variation colour); (albedo, mask, colour, output name)
CUSTOM_TINT = [("T_Chainsaw", "T_Chainsaw_ID", (1.0, 0.2352941, 0.2352941), "t_chainsaw_red")]
# sprites the game tints at draw time: stored white so the tint colour is the colour (the smoke sprite is grey)
WHITE_ALPHA = ["smokesprite"]
# hotbar icon size: one texture pixel per screen pixel of a slot at GUI scale 4
ICON_PX = 64
ITEM_ICONS = ["SingleRevolver", "RevolverAltSingle", "Shotgun", "Shotgun1", "Hammer", "HammerPump", "Nailgun2", "SawbladeLauncher", "Railcannon", "rocketlauncher"]
SOUND_PREFIXES = ["Assets/Sounds/Weapons/", "Assets/Sounds/Movement/", "Assets/Sounds/Gore/", "Assets/Sounds/Impacts/",
                  "Assets/Sounds/UI/", "Assets/Sounds/risingbloop",
                  # UnderwaterController: WaterUnderLoop / WaterSurfacing (and the other water clips)
                  "Assets/Sounds/Environment/Water",
                  # FireSimple's loop (FireSmall) on burning gasoline, and the other fire clips
                  "Assets/Sounds/Environment/Fire",
                  # the grapple point's hum (GrapplePoint's AudioSource: loop, volume 0.35)
                  "Assets/Sounds/Environment/CheckpointAmbiance",
                  # the Impact Hammer's overheat steam (ShotgunHammer.overheatAud: steamLoop3, 0.75, pitch 0.75)
                  "Assets/Sounds/Environment/steamLoop3"]


def ident(name: str) -> str:
    s = name.lower().replace(" ", "_")
    s = re.sub(r"[^a-z0-9/._-]", "_", s)
    return s


def run(*args):
    print(">", " ".join(args))
    subprocess.run([PY, *args], check=True, cwd=ROOT)


# the HUD's VCR OSD Mono; the Terminal screen's pixel Tahoma (TMP "fs-tahoma-8px-v2 SDF"'s source font)
FONTS = ["Assets/Fonts/VCR_OSD_MONO_1.001.ttf", "Assets/Fonts/Terminal/fs-tahoma-8px-v2.ttf"]


def export_all():
    run(os.path.join(HERE, "export_fonts.py"), os.path.join(WORK, "fonts"), *FONTS)
    for name, key in MESHES.items():
        run(os.path.join(HERE, "export_mesh.py"), key, os.path.join(WORK, "rigs", name + ".json"), *(["--skip", MESH_SKIP[name]] if name in MESH_SKIP else []))
    run(os.path.join(HERE, "export_textures.py"), WORK, *TEXTURE_PREFIXES)
    run(os.path.join(HERE, "export_sounds.py"), os.path.join(WORK, "sounds"), *SOUND_PREFIXES, *(["--ffmpeg", FFMPEG] if FFMPEG else []))
    # the Terminal's screen: the Shop prefab's canvas as a layout JSON + its sprites
    run(os.path.join(HERE, "export_canvas.py"), "Assets/Prefabs/Levels/Shop.prefab", "Shop", "Canvas",
        os.path.join(WORK, "shop", "shop_canvas.json"), os.path.join(WORK, "shop", "sprites"))
    for rig, (key, anim) in RIGS.items():
        args = [os.path.join(HERE, "export_rig.py"), key, os.path.join(WORK, "rigs", rig + ".json")]
        if anim:
            args.append(anim)
        run(*args)
        args = [os.path.join(HERE, "export_clips.py"), key, os.path.join(WORK, "clips", rig)]
        if anim:
            args.append(anim)
        run(*args)


def texture_index():
    """asset name (file stem) -> png path, preferring the non-sprite full texture."""
    idx = {}
    # the UI sprites win name clashes (Textures/UI/Railcannon.png is the weapon icon; Models/Weapons/Railcannon/Railcannon.png the model texture)
    roots = [os.path.join(WORK, "Assets", "Textures", "UI"), os.path.join(WORK, "Assets")]
    for root in roots:
        for dp, _, fs in os.walk(root):
            for f in fs:
                if f.endswith(".png") and not f.endswith(".sprite.png"):
                    idx.setdefault(f[:-4], os.path.join(dp, f))
    return idx


def layout():
    tex_idx = texture_index()
    # rigs + clips + their textures
    rig_dst = os.path.join(RES, "uk", "rigs")
    clip_dst = os.path.join(RES, "uk", "clips")
    tex_dst = os.path.join(RES, "textures", "uk")
    os.makedirs(rig_dst, exist_ok=True)
    os.makedirs(tex_dst, exist_ok=True)
    used_tex = set()
    for rig in list(RIGS) + list(MESHES):
        src = os.path.join(WORK, "rigs", rig + ".json")
        if not os.path.isfile(src):
            print("missing rig:", src)
            continue
        data = json.load(open(src, encoding="utf-8"))
        for m in data["meshes"]:
            for sm in m["submeshes"]:
                t = sm.get("texture") or m.get("texture")
                if t:
                    sm["texture"] = ident(t)
                    used_tex.add(t)
            if m.get("texture"):
                used_tex.add(m["texture"])
                m["texture"] = ident(m["texture"])
        json.dump(data, open(os.path.join(rig_dst, rig + ".json"), "w", encoding="utf-8"), separators=(",", ":"))
        cdir = os.path.join(WORK, "clips", rig)
        out = os.path.join(clip_dst, rig)
        if not os.path.isdir(cdir):
            continue
        os.makedirs(out, exist_ok=True)
        for f in os.listdir(cdir):
            shutil.copyfile(os.path.join(cdir, f), os.path.join(out, ident(f)))
    missing = []
    for t in sorted(used_tex):
        p = tex_idx.get(t)
        if not p:
            missing.append(t)
            continue
        shutil.copyfile(p, os.path.join(tex_dst, ident(t) + ".png"))
    # HUD / UI art we draw ourselves (copied by name, lowercased)
    for name in ["RankD", "RankC", "RankB", "RankA", "RankS", "RankSS", "RankSSS", "RankU", "SingleRevolver", "SingleRevolverGlow",
                 "SingleRevolverSimple", "RevolverSpecial", "RevolverSpecialGlow", "RevolverSharp", "RevolverSharpGlow", "ArmFeedbacker", "ArmKnuckleblaster", "ArmWhiplash", "FistIcon", "circlethick", "coin01_3", "targetSquare", "meter 7", "meter", "gradient", "lineglow16", "charge2", "HeatsinkMeterFilled 1", "HeatsinkMeterFilled 2", "HeatsinkMeter", "dot", "smokesprite", "RageEffectWhite",
                 "HeatsinkMeterFilled", "meter 2", "batteryfull", "batterymid", "batterylow", "batterycharging1", "batterycharging2",
                 "batterycharging3",
                 # the Jump Start display (recharge clock, warning X) and the zapper's pulse orb
                 "ZapperTimer", "pixelx 1", "charge",
                 # HUD panel sprites (9-sliced in the game; borders in docs/ULTRAKILL_SOURCE.md -> HUD)
                 "Round_FillLarge", "Round_FillSmall", "Round_Meter", "Round_MeterLeft", "Round_MeterRight", "Round_AntiHealth",
                 "lightningbolt", "lightningboltbig", "lightningboltbigvector", "lightningboltsmall",
                 # weapon icons (WeaponDescriptor icon / glowIcon)
                 "Shotgun", "ShotgunGlow", "Shotgun1", "Shotgun1Glow", "ShotgunSaw", "ShotgunSawGlow",
                 "Nailgun", "NailgunGlow", "Nailgun2", "NailgunGlow2", "NailgunOverheat", "NailgunOverheatGlow", "NailgunZapper", "NailgunZapperGlow",
                 "Railcannon", "RailcannonGlow", "railcannonscrew", "railcannonscrewglow", "railcannonmalicious", "railcannonmaliciousglow",
                 "rocketlauncher", "rocketlauncherglow", "rocketlaunchercannon", "rocketlaunchercannonglow", "rocketlaunchergasoline", "rocketlaunchergasolineglow",
                 "RevolverAltSingle", "RevolverAltSingleGlow", "RevolverAltSpecial", "RevolverAltSpecialGlow", "RevolverAltSharp", "RevolverAltSharpGlow",
                 "Hammer", "HammerGlow", "HammerPump", "HammerPumpGlow", "HammerSaw", "HammerSawGlow",
                 "SawbladeLauncher", "SawbladeLauncherGlow", "SawbladeLauncherOverheat", "SawbladeLauncherOverheatGlow", "SawbladeLauncherZapper", "SawbladeLauncherZapperGlow",
                 "WhiplashWire", "sawblade 1",
                 # effect sprites
                 "muzzleflash", "muzzleflashblue", "muzzleflashnailgun", "muzzleflashshotgun", "muzzleflashwhite", "blooddrop", "blooddroptrail", "spark",
                 "lightning", "lightning2", "projectilebeam", "projectilebeam2", "splash", "splash 1", "splash 2",
                 # the Rocket Launcher's HologramDisplay (clock) and the Freezeframe rocket's FreezeEffect
                 "verticalgradient", "verticalgradientslowfalloff",
                 # GasolineStain's splat atlas (5 x 32 px: green = the puddle, magenta = its rim and drops)
                 "Bloodsplatter_Atlas 1",
                 # the grapple point's glow square (Square: Sprites-Default)
                 "glow 2",
                 # the Impact Hammer: its glow (lights), the dial's tier glows, the secondary meter's radial mask and the
                 # motor's swirl sprite
                 "T_ImpactHammer_Glow", "T_DialGlow1", "T_DialGlow2", "T_DialGlow3", "T_DialMask", "SpinSwirl",
                 # the Terminal's emission (M_ShopTerminal _EmissiveTex: the lit STORE sign and the status lights), added on
                 "T_ShopTerminal_Emission"]:
        p = tex_idx.get(name)
        if p:
            shutil.copyfile(p, os.path.join(tex_dst, ident(name) + ".png"))
        else:
            missing.append(name)
    if missing:
        print("missing textures:", missing)
    # additive effect sprites: alpha = brightness, so Minecraft's alpha blending matches the game's additive look
    try:
        from PIL import Image
        for name in ["shockwavetex"]:
            p = os.path.join(tex_dst, ident(name) + ".png")
            if os.path.isfile(p):
                im = Image.open(p).convert("RGBA")
                px = im.load()
                for yy in range(im.height):
                    for xx in range(im.width):
                        r, g, b, _ = px[xx, yy]
                        px[xx, yy] = (r, g, b, max(r, g, b))
                im.save(p)
    except ImportError:
        pass
    # gasoline stain masks from the splat atlas (Bloodsplatter_Atlas 1, the stain material's _SplatterAtlas): alpha is the
    # hard splat shape, red (= blue) its bright outline, green a soft blob inside. The body is the shape (alpha), the rim
    # the outline inside it, both white with that alpha for the mod to tint (the game's CompositeGasoline colours them)
    try:
        from PIL import Image
        p = os.path.join(tex_dst, ident("Bloodsplatter_Atlas 1") + ".png")
        if os.path.isfile(p):
            src = Image.open(p).convert("RGBA")
            for out, pick in (("gasoline_body", lambda r, g, b, a: 255), ("gasoline_rim", lambda r, g, b, a: r)):
                im = Image.new("RGBA", src.size)
                sp, dp = src.load(), im.load()
                for yy in range(src.height):
                    for xx in range(src.width):
                        r, g, b, a = sp[xx, yy]
                        dp[xx, yy] = (255, 255, 255, pick(r, g, b, a) * a // 255)
                im.save(os.path.join(tex_dst, out + ".png"))
    except ImportError:
        pass
    # item-model copies of the weapon icons: Minecraft stretches item textures square, so pad them square first (<name>_sq.png).
    # The game's 700-1000 px outline art is then made slot-sized: Minecraft samples a texture pixel per screen pixel, and a
    # 10 px line in a 1000 px icon fell between the samples in a 64 px slot (spotty outlines). The lines are thickened a
    # little (a 7 px max filter), the icon shrunk smoothly to ICON_PX and its alpha raised so the thin lines stay solid.
    try:
        from PIL import Image, ImageFilter
        for name in ITEM_ICONS:
            p = os.path.join(tex_dst, ident(name) + ".png")
            if not os.path.isfile(p):
                continue
            im = Image.open(p).convert("RGBA")
            side = max(im.size)
            sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
            sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
            alpha = sq.split()[3].filter(ImageFilter.MaxFilter(7))
            sq.putalpha(alpha)
            sq = sq.resize((ICON_PX, ICON_PX), Image.LANCZOS)
            a2 = sq.split()[3].point(lambda v: min(255, int(v * 1.5)))
            white = Image.new("RGBA", sq.size, (255, 255, 255, 0))
            white.putalpha(a2)
            white.save(os.path.join(tex_dst, ident(name) + "_sq.png"))
    except ImportError:
        print("Pillow missing: item icons not squared")
    try:
        from PIL import Image
        for albedo, mask, col, out in CUSTOM_TINT:
            a, m = tex_idx.get(albedo), tex_idx.get(mask)
            if not a or not m:
                print("missing tint source:", albedo, mask)
                continue
            im = Image.open(a).convert("RGBA")
            mk = Image.open(m).convert("RGBA").resize(im.size)
            px, mp = im.load(), mk.load()
            for yy in range(im.height):
                for xx in range(im.width):
                    r, g, b, al = px[xx, yy]
                    k = mp[xx, yy][0] / 255.0
                    px[xx, yy] = (round(r * (1 - k + k * col[0])), round(g * (1 - k + k * col[1])), round(b * (1 - k + k * col[2])), al)
            im.save(os.path.join(tex_dst, out + ".png"))
    except ImportError:
        print("Pillow missing: tinted textures not baked")
    # emissive maps as alpha masks (white, alpha = brightness) so they can be drawn tinted over the mesh
    try:
        from PIL import Image
        for name in EMISSIVE_ALPHA:
            src = tex_idx.get(name)
            if not src:
                print("missing emissive:", name)
                continue
            im = Image.open(src).convert("RGBA")
            px = im.load()
            for yy in range(im.height):
                for xx in range(im.width):
                    r, g, b, _ = px[xx, yy]
                    px[xx, yy] = (255, 255, 255, max(r, g, b))
            im.save(os.path.join(tex_dst, ident(name) + ".png"))
        for name in WHITE_ALPHA:
            src = tex_idx.get(name)
            if not src:
                continue
            im = Image.open(src).convert("RGBA")
            px = im.load()
            for yy in range(im.height):
                for xx in range(im.width):
                    r, g, b, a = px[xx, yy]
                    px[xx, yy] = (255, 255, 255, a)
            im.save(os.path.join(tex_dst, ident(name) + ".png"))
        # the grapple orb item icon: the game's `dot` sprite in the orb's green
        dot = tex_idx.get("dot")
        if dot:
            im = Image.open(dot).convert("RGBA").resize((16, 16), Image.NEAREST)
            px = im.load()
            for yy in range(16):
                for xx in range(16):
                    r, g, b, a = px[xx, yy]
                    px[xx, yy] = (int(0.65 * r), int(0.94 * g), int(0.34 * b), a)
            im.save(os.path.join(tex_dst, "grapple_orb_icon.png"))
    except ImportError:
        print("Pillow missing: emissive masks not built")
    # the HUD font (VCR OSD Mono, the game's TMP font source) -> font/vcr_osd_mono.ttf (font/vcr.json is in git)
    font_src = os.path.join(WORK, "fonts", "VCR_OSD_MONO_1.001.ttf")
    if os.path.isfile(font_src):
        os.makedirs(os.path.join(RES, "font"), exist_ok=True)
        shutil.copyfile(font_src, os.path.join(RES, "font", "vcr_osd_mono.ttf"))
    else:
        print("missing font:", font_src)
    # the Terminal screen's font -> font/fs_tahoma_8px.ttf (font/terminal.json is in git)
    font_src = os.path.join(WORK, "fonts", "fs-tahoma-8px-v2.ttf")
    if os.path.isfile(font_src):
        shutil.copyfile(font_src, os.path.join(RES, "font", "fs_tahoma_8px.ttf"))
    else:
        print("missing font:", font_src)
    # sounds
    snd_src = os.path.join(WORK, "sounds", "Assets", "Sounds")
    snd_dst = os.path.join(RES, "sounds", "uk")
    events = {}
    if os.path.isdir(snd_src):
        for dp, _, fs in os.walk(snd_src):
            for f in fs:
                if not f.endswith(".ogg"):
                    continue
                rel = os.path.relpath(os.path.join(dp, f), snd_src).replace("\\", "/")
                folder = ident(os.path.dirname(rel)) if os.path.dirname(rel) else "misc"
                stem = ident(f[:-4])
                dst = os.path.join(snd_dst, folder, stem + ".ogg")
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copyfile(os.path.join(dp, f), dst)
                ev = "uk." + folder.replace("/", ".") + "." + stem
                events[ev] = {"sounds": [{"name": "ultracraft:uk/" + folder + "/" + stem, "stream": False}]}
    # Minecraft caps pitch at 2: the clips the game plays higher get a copy an octave up (<stem>_x2), which
    # UKSound plays at half the pitch
    ffmpeg = FFMPEG
    for rel in PITCH_UP:
        src = os.path.join(snd_dst, rel + ".ogg")
        if not os.path.isfile(src) or not ffmpeg:
            print("octave-up copy not made:", rel)
            continue
        dst = os.path.join(snd_dst, rel + "_x2.ogg")
        if not os.path.isfile(dst):
            rate = ogg_rate(src)
            subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", src, "-af", f"asetrate={int(rate) * 2},aresample={rate}", "-c:a", "libvorbis", "-q:a", "5", dst], check=True)
        folder, stem = os.path.dirname(rel), os.path.basename(rel)
        events["uk." + folder.replace("/", ".") + "." + stem + "_x2"] = {"sounds": [{"name": "ultracraft:uk/" + rel + "_x2", "stream": False}]}
    for rel in PITCH_DOWN:
        src = os.path.join(snd_dst, rel + ".ogg")
        if not os.path.isfile(src) or not ffmpeg:
            print("octave-down copy not made:", rel)
            continue
        for div in (2, 4):
            dst = os.path.join(snd_dst, rel + f"_d{div}.ogg")
            if not os.path.isfile(dst):
                rate = ogg_rate(src)
                subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", src, "-af", f"asetrate={int(rate) // div},aresample={rate}", "-c:a", "libvorbis", "-q:a", "5", dst], check=True)
            folder, stem = os.path.dirname(rel), os.path.basename(rel)
            events["uk." + folder.replace("/", ".") + "." + stem + f"_d{div}"] = {"sounds": [{"name": "ultracraft:uk/" + rel + f"_d{div}", "stream": False}]}
    # Minecraft places only mono sounds in the world (a stereo one plays at the listener, wherever it was started):
    # every stereo clip gets a mono copy (<stem>_m) that UKSound plays for sounds with a place (at, loopAt)
    for ev, spec in list(events.items()):
        rel = spec["sounds"][0]["name"].split("ultracraft:uk/", 1)[1]
        src = os.path.join(snd_dst, rel + ".ogg")
        if not ffmpeg or not os.path.isfile(src) or ogg_channels(src) < 2:
            continue
        dst = os.path.join(snd_dst, rel + "_m.ogg")
        if not os.path.isfile(dst):
            subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", src, "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", dst], check=True)
        events[ev + "_m"] = {"sounds": [{"name": "ultracraft:uk/" + rel + "_m", "stream": False}]}
    # the game's AudioSource ranges for positional loops Minecraft would fade out over its default 16 blocks
    for ev, blocks in ATTENUATION.items():
        for e in (ev, ev + "_m"):
            if e in events:
                for snd in events[e]["sounds"]:
                    snd["attenuation_distance"] = blocks
    json.dump(events, open(os.path.join(RES, "sounds.json"), "w", encoding="utf-8"), indent=0)
    print(f"layout: {len(RIGS)} rigs, {len(used_tex)} rig textures, {len(events)} sound events")


# AudioSource linear rolloff ranges (maxDistance in u x 1.8 / 3.5 blocks per u): FireSimple's FireSmall carries 100 u
# and the grapple point's hum 35 u (played from its octave-down copy)
ATTENUATION = {"uk.environment.firesmall": 51, "uk.environment.checkpointambiance_d2": 18}


def ogg_rate(path):
    """The sample rate from an Ogg Vorbis file's identification header."""
    with open(path, "rb") as fh:
        head = fh.read(256)
    i = head.find(b"vorbis")
    return int.from_bytes(head[i + 11:i + 15], "little")


def ogg_channels(path):
    """The channel count from an Ogg Vorbis file's identification header (1 if it cannot be read)."""
    with open(path, "rb") as fh:
        head = fh.read(256)
    i = head.find(b"vorbis")
    return head[i + 11] if i >= 0 and len(head) > i + 11 else 1


def export_digit_strip():
    """0-9 from the game's VCR OSD Mono font as one white-on-clear strip (digits.png), for numbers drawn on gun displays."""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print("Pillow missing: digit strip not made")
        return
    src = os.path.join(WORK, "fonts", "VCR_OSD_MONO_1.001.ttf")
    if not os.path.isfile(src):
        print("missing font for the digit strip:", src)
        return
    font = ImageFont.truetype(src, 96)
    ascent, descent = font.getmetrics()
    cw = int(round(font.getlength("0")))
    ch = ascent + descent
    strip = Image.new("RGBA", (cw * 10, ch), (255, 255, 255, 0))
    d = ImageDraw.Draw(strip)
    for i in range(10):
        d.text((i * cw, 0), str(i), font=font, fill=(255, 255, 255, 255))
    strip.save(os.path.join(RES, "textures", "uk", "digits.png"))
    print("digit strip", cw, "x", ch)
    # the letters too, for the words on gun displays (GunDisplays.GLYPHS has the same order)
    chars = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.:-"
    strip = Image.new("RGBA", (cw * len(chars), ch), (255, 255, 255, 0))
    d = ImageDraw.Draw(strip)
    for i, c in enumerate(chars):
        d.text((i * cw, 0), c, font=font, fill=(255, 255, 255, 255))
    strip.save(os.path.join(RES, "textures", "uk", "glyphs.png"))
    print("glyph strip", len(chars), "glyphs")


# sounds the game plays above Minecraft's pitch cap of 2 (folder/stem under sounds/uk): Bell_10_b (Jump Start ready, 3),
# harpoonPierce (ZapperAttachSound, 2.5), click (NailgunNoAmmo, 3)
PITCH_UP = ["ui/bell_10_b", "weapons/harpoonpierce", "weapons/revolver/click", "weapons/scifi_glit_fx_007",
            # NewMovement.CheckForGasoline: the oil slide loop at 1.75-2.75
            "movement/oilslide",
            # GunControl's AudioSource on every weapon / variant switch: pitch 3
            "impacts/impacts_simple_003",
            # RailcannonMaliciousFire: Bluezone-Autobots-shutdown-003 at pitch 3
            "weapons/bluezone-autobots-shutdown-003"]
# and the clips it plays lower than Minecraft's 0.5: a copy an octave down (<stem>_d2), played at twice the pitch
# (the rocket's loop: pitch 0.5, then down to about half that with its Doppler 3 as it flies off)
# (the grapple point's hum: 0.5, 0.25 with V1 close by, 0.75 hooked)
# (the Impact Hammer's motor loop: pitch = the dial's speed, 0..1)
PITCH_DOWN = ["weapons/rocketloop", "movement/whoosh", "environment/checkpointambiance", "weapons/hammermotorloop"]
# and two octaves down (<stem>_d4): the rocket's ULTRAKILL Doppler takes it to about 0.06 when flying off


# AudioClips with no addressable key of their own, read from a prefab that uses them: (prefab key, clip name, path under Sounds)
EMBEDDED_SOUNDS = [("Assets/Prefabs/Weapons/Railcannon Malicious.prefab", "Throat Drone Stereo", "Weapons/Throat Drone Stereo"),
                   ("Assets/Particles/Breaks/BreakParticleMetal.prefab", "Bluezone_BC0236_metal_debris_097", "Impacts/Bluezone_BC0236_metal_debris_097"),
                   # the S.R.S. cannonball's Break (BreakParticleBig)
                   ("Assets/Particles/Breaks/BreakParticleBig.prefab", "boulder_impact_on_stones_14", "Impacts/boulder_impact_on_stones_14"),
                   # the S.R.S. cannon's charge (RocketLauncher.chargeSound on its HologramDisplay: loop, volume 0.4)
                   ("Assets/Prefabs/Weapons/Rocket Launcher Cannonball.prefab", "MachinePumpLoop", "Weapons/MachinePumpLoop"),
                   # a rocket frozen 1 s levels up: its LevelUpEffect's Audio Source (volume 0.33, pitch 3)
                   ("Assets/Prefabs/Weapons/Rocket Launcher Freeze.prefab", "scifi_glit_fx_007", "Weapons/scifi_glit_fx_007"),
                   # the Jump Start's zapper: its surge loop, the cable's tighten loop and snap, the pulse orb's loop
                   ("Assets/Prefabs/Attacks and Projectiles/Zapper.prefab", "ElectricitySurgeContinuous", "Weapons/ElectricitySurgeContinuous"),
                   ("Assets/Prefabs/Attacks and Projectiles/Zapper.prefab", "RopeTighten", "Weapons/RopeTighten"),
                   ("Assets/Prefabs/Attacks and Projectiles/Zapper.prefab", "RopeSnap", "Weapons/RopeSnap"),
                   ("Assets/Prefabs/Attacks and Projectiles/Zapper.prefab", "Electromagnetic Device, Moving File Data, Glitch, Click, Squeal", "Weapons/ElectromagneticDeviceSqueal"),
                   # the Terminal (Shop.prefab): ScreenZone's music (loop, 0.6), the drawer opening on a purchase (0.25) and
                   # the light's hum (0.75)
                   ("Assets/Prefabs/Levels/Shop.prefab", "shopmusic", "Shop/shopmusic"),
                   ("Assets/Prefabs/Levels/Shop.prefab", "ShopDrawerOpen", "Shop/ShopDrawerOpen"),
                   ("Assets/Prefabs/Levels/Shop.prefab", "125064__everydaysounds__faulty-fluorescent-light-starter-hum", "Shop/lighthum")]


def export_embedded_sounds():
    """Each EMBEDDED_SOUNDS clip as WORK/sounds/Assets/Sounds/<path>.ogg, where layout() picks the sounds up."""
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import ukenv
    except ImportError as e:
        print("embedded sounds not exported:", e)
        return
    ffmpeg = FFMPEG
    for key, name, rel in EMBEDDED_SOUNDS:
        ogg = os.path.join(WORK, "sounds", "Assets", "Sounds", rel + ".ogg")
        if os.path.exists(ogg):
            continue
        env, _ = ukenv.env_for_key(key)
        for o in env.objects:
            if o.type.name != "AudioClip":
                continue
            clip = o.read()
            if getattr(clip, "m_Name", "") != name:
                continue
            samples = clip.samples
            if not samples or not ffmpeg:
                print("embedded sound", name, "not converted (samples or ffmpeg missing)")
                break
            os.makedirs(os.path.dirname(ogg), exist_ok=True)
            wav = ogg[:-4] + ".wav"
            with open(wav, "wb") as f:
                f.write(next(iter(samples.values())))
            subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", ogg], check=True)
            os.remove(wav)
            print("embedded sound", name, "->", rel)
            break


# Textures with no addressable key of their own, read from a prefab that uses them: (prefab key, texture name, output stem)
EMBEDDED_TEXTURES = [("Assets/Prefabs/Attacks and Projectiles/Zapper.prefab", "floordecoration3", "floordecoration3"),
                     ("Assets/Prefabs/Attacks and Projectiles/Zapper.prefab", "floortiles2c", "floortiles2c"),
                     # the Rocket Launcher's HologramDisplay: Fade and Inner Ring
                     ("Assets/Prefabs/Weapons/Rocket Launcher Freeze.prefab", "fadetowhite", "fadetowhite"),
                     ("Assets/Prefabs/Weapons/Rocket Launcher Freeze.prefab", "ShockwaveFilled", "shockwavefilled"),
                     # the rocket: its stripes once levelled up (ChangeMaterials -> Projectile 2) and its exhaust flame
                     # (the muzzleflash sprite at its tail)
                     ("Assets/Prefabs/Attacks and Projectiles/Rocket.prefab", "skull2 compressed 2", "skull2_compressed_2"),
                     ("Assets/Prefabs/Attacks and Projectiles/Rocket.prefab", "muzzleflashshotgun 1", "muzzleflashshotgun1"),
                     # FireSimple (burning gasoline): the 60-frame fire sprite sheet its Animator plays (Fire32, 12 fps)
                     ("Assets/Particles/FireSimple.prefab", "fire1_32", "fire1_32"),
                     # Fire (the burning enemies' fire): the same sheet at 64 px a frame
                     ("Assets/Particles/Fire.prefab", "fire1_64", "fire1_64"),
                     # the grapple point's arches (Metal Decoration 1B Orange: its vertex blend is unpainted, so the base texture shows)
                     ("Assets/Prefabs/Levels/Interactive/GrapplePoint.prefab", "floordecoration1b", "floordecoration1b")]


def export_embedded_textures():
    """Each EMBEDDED_TEXTURES texture as textures/uk/<stem>.png."""
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import ukenv
    except ImportError as e:
        print("embedded textures not exported:", e)
        return
    tex_dst = os.path.join(RES, "textures", "uk")
    for key, name, stem in EMBEDDED_TEXTURES:
        out = os.path.join(tex_dst, stem + ".png")
        if os.path.exists(out):
            continue
        env, _ = ukenv.env_for_key(key)
        for o in env.objects:
            if o.type.name != "Texture2D":
                continue
            t = o.read()
            if getattr(t, "m_Name", "") != name:
                continue
            t.image.save(out)
            print("embedded texture", name, "->", stem)
            break
        else:
            print("embedded texture not found:", name)


# Texture2DArrays (their pixels are streamed from the bundle's .resS): (a prefab that loads them, array name, output stem)
TEXTURE_ARRAYS = [("Assets/Prefabs/Weapons/Railcannon Electric.prefab", "T_ElectricArcs", "electricarcs")]


def export_texture_arrays():
    """Each slice of a Texture2DArray as <stem><n>.png (R8G8B8A8, rows bottom-up per slice)."""
    try:
        from PIL import Image
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import ukenv
        from UnityPy.helpers.ResourceReader import get_resource_data
    except ImportError as e:
        print("texture arrays not exported:", e)
        return
    tex_dst = os.path.join(RES, "textures", "uk")
    for key, name, stem in TEXTURE_ARRAYS:
        env, _ = ukenv.env_for_key(key)
        for o in env.objects:
            if o.type.name != "Texture2DArray":
                continue
            d = o.read()
            if getattr(d, "m_Name", "") != name:
                continue
            w, h, depth = d.m_Width, d.m_Height, d.m_Depth
            sd = d.m_StreamData
            data = get_resource_data(sd.path, o.assets_file, sd.offset, sd.size) if sd.size else bytes(d.image_data)
            per = w * h * 4
            if len(data) < per * depth:
                print("texture array", name, "has an unexpected size", len(data))
                break
            for i in range(depth):
                im = Image.frombytes("RGBA", (w, h), bytes(data[i * per:(i + 1) * per])).transpose(Image.FLIP_TOP_BOTTOM)
                im.save(os.path.join(tex_dst, f"{stem}{i}.png"))
            print("texture array", name, "->", depth, "frames")
            break


def bake_fire():
    """FireSimple's look baked: its FireMaterial (ULTRAKILL/Additive, but blended SrcAlpha / OneMinusSrcAlpha) has
    _ColorMode 1, which ADDS the sprite colour (1, 0.3125, 0) to the texture instead of multiplying - Minecraft only
    multiplies, so the sum is stored (fire1_32_lit.png, drawn with a white vertex colour)."""
    try:
        from PIL import Image
    except ImportError:
        return
    for stem in ("fire1_32", "fire1_64"):
        src = os.path.join(RES, "textures", "uk", stem + ".png")
        if not os.path.isfile(src):
            continue
        im = Image.open(src).convert("RGBA")
        px = im.load()
        add = (255, round(0.3125 * 255), 0)
        for yy in range(im.height):
            for xx in range(im.width):
                r, g, b, a = px[xx, yy]
                px[xx, yy] = (min(255, r + add[0]), min(255, g + add[1]), min(255, b + add[2]), a)
        im.save(os.path.join(RES, "textures", "uk", stem + "_lit.png"))
        print("fire sprite baked:", stem)


def layout_shop():
    """The Terminal's screen layout -> uk/shop/shop_canvas.json, its sprites -> textures/uk/shop/."""
    src = os.path.join(WORK, "shop")
    if not os.path.isfile(os.path.join(src, "shop_canvas.json")):
        return
    os.makedirs(os.path.join(RES, "uk", "shop"), exist_ok=True)
    shutil.copyfile(os.path.join(src, "shop_canvas.json"), os.path.join(RES, "uk", "shop", "shop_canvas.json"))
    dst = os.path.join(RES, "textures", "uk", "shop")
    os.makedirs(dst, exist_ok=True)
    for f in os.listdir(os.path.join(src, "sprites")):
        shutil.copyfile(os.path.join(src, "sprites", f), os.path.join(dst, f))
    print("shop screen laid out")


if __name__ == "__main__":
    if "--skip-export" not in sys.argv:
        export_all()
    export_embedded_sounds()
    layout()
    layout_shop()
    export_texture_arrays()
    export_embedded_textures()
    bake_fire()
    export_digit_strip()
