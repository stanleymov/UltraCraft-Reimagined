# UltraCraft Reimagined

ULTRAKILL inside Minecraft (Forge 1.20.1): you play as V1, with ULTRAKILL's own movement, weapons and every
variation, arms, parries, style meter and shop, its first-person animations, sounds and HUD, converted from your own
copy of ULTRAKILL.

> **In beta testing.** No release yet: the setup is built and the first release follows the beta.

> **Unofficial fan mod.** Not affiliated with or endorsed by New Blood Interactive, Arsi "Hakita" Patala or Mojang.
> ULTRAKILL © New Blood Interactive. Minecraft © Mojang Studios / Microsoft. Not related to the other Minecraft mods
> called UltraCraft.

## What's in

- **V1's movement**: ULTRAKILL's physics (0.008 s steps), dash with stamina, slide (low enough to slide under a block),
  slam with its shockwave, wall jumps and wall cling, slide and slam jumps, rocket riding. Optional: an air brake to land
  on a single block, and a short hop.
- **Every weapon and variation**, standard and alternate: revolvers (Piercer, Marksman, Sharpshooter), shotguns and the
  Jackhammer (Core Eject, Pump Charge, Sawed-On), nailguns and sawblade launchers (Attractor, Overheat, Jump Start),
  railcannons (Electric, Screwdriver, Malicious) and rocket launchers (Freezeframe, S.R.S. Cannon, Firestarter), with
  their on-gun displays, coins and ricochets.
- **Arms**: the Feedbacker (parries, projectile boosts), the Knuckleblaster and the Whiplash.
- **Parrying** melee hits and projectiles, with a warning star before a ranged mob's parryable shot.
- **Dual wield**: an ULTRAKILL weapon in the off-hand fires with the main one, mirrored like ULTRAKILL's dual wield.
- **Style meter and P**: style ranks, bonuses and freshness; points bank into P when a fight ends.
- **The Terminal**: a craftable shop block with ULTRAKILL's shop screen, music and arm, to buy weapons and variations
  with P.
- **Fire**: gasoline, the Firestarter and the Malicious railcannon set the world on fire; shots burn through leaves and
  plants.
- **Damage tuned to Minecraft**: armour by piece and material, big mobs and bosses on ULTRAKILL enemies' health and
  weaknesses (the Warden, the Wither and the Ender Dragon), boss kill rewards.
- **Third person** shows V1 itself.

## Installing

You need **ULTRAKILL** (Steam; the base game, no DLC needed), **Minecraft Java Edition** and a modding launcher:
**CurseForge**, **Prism Launcher** or **MultiMC**.

1. Download `UltraCraft Reimagined Setup <version>.zip` from Releases and extract the whole zip.
2. Close CurseForge if it is open.
3. Run `Setup UltraCraft Reimagined.exe`. It finds ULTRAKILL in your Steam libraries and your Forge 1.20.1 profiles,
   then lets you pick: a new **UltraCraft Reimagined** profile (CurseForge, Prism Launcher or MultiMC), one of your
   own Forge 1.20.1 profiles, or any mods folder.
4. It builds the mod from your ULTRAKILL in a few minutes (command windows may flash; that's normal) and puts the jar
   in that profile. Play it from your launcher: a new profile downloads Minecraft and Forge the first time.

The setup is also the updater: run a newer one over your install. When an update brings no new ULTRAKILL files, it
only replaces the code and doesn't need ULTRAKILL.

Keys: sprint = dash, sneak = slide (ground slam in the air), mouse back = punch, mouse forward = whiplash, G = change
arm, C = next variation (or press the weapon's number key again), R = last weapon. Craft a Terminal to buy weapons,
variations and arms with P.

## No game files here: bring your own

This repository and the setup hold **code only**: no ULTRAKILL files and nothing converted from them. The setup builds
the mod's ULTRAKILL content (models, animations, textures, sounds, fonts, the shop screen) on your PC from **your own**
ULTRAKILL install, with the scripts in `tools/extract/`. The sound library FMOD isn't ours to pass on either, so the
setup downloads it from PyPI while it runs. The jar it builds contains ULTRAKILL's content, so it is for you only:
please don't share it; share the setup instead.

## Made and tested by people and Claude, not by generative AI slop

- **No generative AI was used for any visual or audio asset.** Every model, animation, texture, sound and HUD element
  in UltraCraft Reimagined is ULTRAKILL's own, converted from your own install.
- **The code and this description were written with [Claude Code](https://claude.com/claude-code)** (Anthropic),
  under the author's direction, and go through intensive human testing and tuning.

## What's in here

| Folder | What |
|---|---|
| `src/` | the mod (Forge 1.20.1, Java): V1's movement, weapons, arms, rigs and animation player, HUD, style meter, shop, damage |
| `tools/extract/` | the extraction pipeline the setup runs on your ULTRAKILL install: Addressables catalog, rigs and animation clips, meshes, textures, sounds, fonts, the shop canvas |
| `tools/re/` | scripts that read ULTRAKILL's game data and code layout to look up the game's own values |
| `installer/` | the setup: wizard and engine (Python), launcher (Rust), package builder |
| `docs/` | notes on ULTRAKILL's mechanics and its files, and the plan |

Building from source: `./gradlew build codeJar` (JDK 17). The mod needs the converted ULTRAKILL content in
`src/main/resources/assets/ultracraft/` (git-ignored), which `tools/extract/build_assets.py` builds from an ULTRAKILL
install. `python installer/build_package.py --zip` then makes the setup download from the code-only jar.

Code: all rights reserved, StanleyMOV.
