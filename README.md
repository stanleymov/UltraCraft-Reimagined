# UltraCraft

ULTRAKILL inside Minecraft (Forge 1.20.1): you play as V1, with ULTRAKILL's own movement, weapons and every
variation, arms, parries, style meter and shop, its first-person animations, sounds and HUD, converted from your own
copy of ULTRAKILL.

> **Work in progress: in beta testing.** There is no release yet; the setup that builds the mod from your ULTRAKILL
> install comes with the first release.

> **Unofficial fan mod.** Not affiliated with or endorsed by New Blood Interactive, Arsi "Hakita" Patala or Mojang.
> ULTRAKILL © New Blood Interactive. Minecraft © Mojang Studios / Microsoft.

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
- **Damage tuned to Minecraft**: armour by piece and material, big mobs and bosses on ULTRAKILL enemies' health and
  weaknesses (the Warden, the Wither and the Ender Dragon), boss kill rewards.
- **Third person** shows V1 itself.

## No game files here: bring your own

This repository holds **code only**: no ULTRAKILL files and nothing converted from them. The setup will build the
mod's ULTRAKILL content (models, animations, textures, sounds, fonts, the shop screen) on your PC from **your own**
ULTRAKILL install, with the scripts in `tools/extract/`. The jar it builds contains ULTRAKILL's content, so it is for
you only: please don't share it; share the setup instead.

## Made and tested by people and Claude, not by generative AI slop

- **No generative AI was used for any visual or audio asset.** Every model, animation, texture, sound and HUD element
  in UltraCraft is ULTRAKILL's own, converted from your own install.
- **The code and this description were written with [Claude Code](https://claude.com/claude-code)** (Anthropic),
  under the author's direction, and go through intensive human testing and tuning.

## What's in here

| Folder | What |
|---|---|
| `src/` | the mod (Forge 1.20.1, Java): V1's movement, weapons, arms, rigs and animation player, HUD, style meter, shop, damage |
| `tools/extract/` | the extraction pipeline the setup runs on your ULTRAKILL install: Addressables catalog, rigs and animation clips, meshes, textures, sounds, fonts, the shop canvas |
| `tools/re/` | scripts that read ULTRAKILL's game data and code layout to look up the game's own values |
| `docs/` | notes on ULTRAKILL's mechanics and its files, and the plan |

Building from source: `./gradlew build` (JDK 17). The mod needs the converted ULTRAKILL content in
`src/main/resources/assets/ultracraft/` (git-ignored), which `tools/extract/build_assets.py` builds from an ULTRAKILL
install.

Code: all rights reserved, StanleyMOV.
