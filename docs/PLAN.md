# UltraCraft — plan

ULTRAKILL's V1 inside Minecraft Forge 1.20.1 as a **native port**: one game running, ULTRAKILL's real
models/textures/sounds/animations converted from the player's own install, ULTRAKILL's real numbers
(read from its code, see `ULTRAKILL_SOURCE.md`) re-implemented on Minecraft's entities and blocks.

## Decisions (user, 2026-10-16)
- Scope 1.0: the player only. V1 with every weapon + variation + alternate weapon, both fists, Whiplash,
  the whole movement set, parry/projectile boost, coins, style meter + ranks, ULTRAKILL HUD, blood heal,
  retro look options. Minecraft mobs are the targets. No ULTRAKILL enemies or levels yet.
- Always V1, but with the normal Minecraft inventory and hotbar: blocks/items work as usual.
- Weapons are items; `E` cycles the held weapon's variation, `Q` last weapon.
- Default keys = ULTRAKILL's (`Shift` dash, `Ctrl` slide/slam, `F` punch, `G` switch arm, `R` whiplash,
  `E` variation, `Q` last weapon, `1-5`/scroll weapons). Minecraft's sneak/inventory/drop/offhand move
  to free keys while the mod is installed; everything rebindable.
- Unlocks: base weapons (Piercer, Core Eject, Attractor, Electric, Freezeframe) + Feedbacker from the
  start. Recipes only remake base gear and craft the **Terminal**. Variations, Knuckleblaster, Whiplash
  and alternate weapons are bought with **P** earned from style points.
- Scale: Steve-sized V1 — 1 Unity unit = `1.8 / 3.5 = 0.5143` blocks (V1's capsule is 3.5 u tall).
  All speeds and distances scale by the same factor; gravity 40 u/s² → 20.6 b/s² handled by our own
  motion code (we don't use vanilla gravity while in V1 physics).
- Single player first; logic split client/server from day one so multiplayer is a later step.
- No generative AI anywhere. No DLC assets. Game assets first, always.

## Architecture (Forge 1.20.1, Loom)
```
com.ultracraft
  UltraCraft            mod entry, registries, network
  player/               V1 physics on the local player (client-authoritative movement sent to server)
     V1Movement         port of NewMovement: ground/air/dash/slide/slam/jump/wall-jump/cling, stamina
     V1Health           hp/antiHp (hard damage), hurt invincibility, heal from blood, death
     V1Camera           FOV kick, tilt, shake, walk bob, slide lowering
     V1Input            key bindings + ULTRAKILL input semantics (hold/tap/was-performed)
  weapon/               items + logic (server), each weapon = one Java class mirroring the C# one
     revolver/ shotgun/ nailgun/ railcannon/ rocket/   + variations + alternates
     arm/               Feedbacker, Knuckleblaster, Whiplash
     projectile/        Coin, Nail/Sawblade, Shotgun pellet, Grenade/Rocket, Harpoon, Cannonball,
                        Magnet, Zapper, Explosion (entities)
     WeaponCharges      shared charge pool (rev0/1/2, shotgun, nailgun, rail, rocket, punch stamina)
  style/                StyleCalculator + StyleHUD (points, ranks, freshness, multikill, air time)
  shop/                 Terminal block + screen, P currency, unlock state (player capability)
  client/
     render/            V1 third-person model (converted skinned mesh + animations), first-person arms &
                        guns (converted rigs), beams, trails, coin flash, blood
     hud/               ULTRAKILL HUD (health/hard damage, stamina, weapon panel, style meter, rank)
     fx/                retro look: pixelation, colour compression, vertex warp (post-process; must
                        coexist with Oculus → fall back to off when a shader pack is active)
  mixin/                player movement hooks, camera, hotbar/HUD suppression, item use
```
Rules: `work/` (extracted game data) is never committed; the jar is built by the setup from the player's
install; converted assets land under `assets/ultracraft/uk/` (git-ignored). Every eye-tuned value is a
live setting (`config/ultracraft-client.toml`, reloads at runtime) with the game's value as default.

## Asset pipeline (repeatable, no manual steps)
`tools/extract/` (Python, UnityPy + TypeTreeGeneratorAPI):
1. `catalog.py` — key → bundle resolution.
2. `dump_prefab.py` — prefab/ScriptableObject values (already used for all numbers).
3. `export_textures.py` — Texture2D → PNG (V1, arms, weapons, HUD sprites via Sprite rects).
4. `export_meshes.py` — Mesh + SkinnedMeshRenderer bones/weights → our own JSON/binary skinned
   format; FBX avatars for bone hierarchy.
5. `export_anims.py` — AnimationClip curves (position/rotation/scale per bone) for V1 and weapons.
6. `export_sounds.py` — AudioClip (FSB) → OGG for Minecraft `sounds.json`.
7. `build_jar.py` — runs 3–6 into `src/main/resources/assets/ultracraft/uk/`, then Gradle.
The same scripts are what the installer runs (bundled Python like DishonoredCraft's setup).

## Milestones
1. **Movement slice** (first vertical slice): V1 physics on Steve's hitbox, stamina HUD, camera feel,
   dash/slide/slam/wall-jump/cling with the real numbers; live settings. Test world.
2. **Fists + Revolver** — done 2026-10-17: Feedbacker (parry Minecraft projectiles → heal + "PARRY"),
   Piercer with charge shot, Marksman coins, Sharpshooter spin, hitscan beams with the real damage
   model vs mobs, blood heal; rig/clip/sound exporters and the first-person rig renderer.
3. **Style system + HUD** — done 2026-10-17: points/ranks/freshness/multikills/headshot combos/air-time
   multiplier, P persisted per player, the real HUD (GunCanvas + StyleCanvas rebuilt from the prefab
   through a 90° HUD camera, VCR OSD Mono font, rank sprites, freshness bar, speedometer).
4. **Rest of the arsenal** — Shotgun ×3, Nailgun ×3, Railcannon ×3, Rocket Launcher ×3 done 2026-10-17
   (projectile entities, explosions, player launch, HUD charge panel); Whiplash, Knuckleblaster
   shockwave, Alternative Revolver, Jackhammer and Sawblade Launcher done 2026-10-17 (milestone 5, with
   the animator exit transitions and the 105° arm camera). Still open: rocket riding, zapper tether,
   Overheat burst, saw ropes, Whiplash grabbing objects / coins / hook points.
5. **Third-person V1** — done 2026-10-17 (milestone 6): the platformer's `v1_combined` model with the
   V1Platformer clips (idle/run/jump/fall/land/dash/slide/spin/slam), wings, weapon in `hand.R`. Open: head
   look, arms/fists on the model, other players' dash and slide.
   **Polish passes A–D** (2026-10-17/18, from the user's test lists): the 90° gun projection with its own depth
   range, on-gun displays from the prefab canvases, vanilla sprint/sneak as dash/slide, blood, slam shockwave,
   rocket riding, chainsaw, the parry system (melee parry windows with the yellow flash, projectile parry over
   the active frames, pellet projectile boost), Core Eject heat + steam, Knuckleblaster shells, Coin.prefab
   model, HUD bar heights + arm icons, the Forge config screen (speedometer, parry flash).
6. **Terminal shop + P**, recipes, unlock persistence.
7. **Retro look options**, polish, settings screens.
8. **Installer** (BYO files, CurseForge/Prism/MultiMC profiles, updater), GitHub repo (private → public
   on OK), README with screenshots.

## Risks / open questions
- Hitscan against mobs: ULTRAKILL has head/limb/body colliders; Minecraft mobs have one box. Plan:
  headshot = hit within the top 25% of the bounding box (configurable), limb hits not modelled in 1.0.
- Oculus + custom post-process: pixelation/palette must be a plain framebuffer pass; disable when
  Iris/Oculus shaders are on.
- Skinned-mesh rendering in Forge: custom `EntityRenderer` doing CPU skinning (V1 is ~few k verts) is
  the simplest robust route; GPU skinning later if needed.
