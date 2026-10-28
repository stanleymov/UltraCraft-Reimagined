# ULTRAKILL source facts (what the game files actually contain)

Everything here was read from the player's own install with the scripts in `tools/extract/`.
Numbers are the game's; where a value is interpreted (units, formulas) the code location is named.
Nothing from the game is committed: `work/` holds the extracted data and is git-ignored.

## Install / engine
- Steam app **1229490**, depot 1229491, build 22957324 (2026-10-16). 3.6 GB. No DLC depot installed.
- Unity **2022.3.29f1**, Mono (not IL2CPP). `ULTRAKILL_Data/Managed/Assembly-CSharp.dll` (2.8 MB,
  2289 types, 13510 methods). Game code is plain C#; REA inspects it type-filtered (the whole
  assembly exceeds REA's 64 MB metadata budget).
- Plugins: steam_api64, discord_game_sdk, burst, MaskedOcclusionCulling. No anti-cheat.

## Where the assets are (Addressables)
`ULTRAKILL_Data/StreamingAssets/aa/`:
- `catalog.json` — Addressables catalog: 20 767 keys, 12 100 entries, 81 bundles. Keys are the
  original editor paths (`Assets/Prefabs/Player/Player.prefab`). Parsed by `tools/extract/catalog.py`
  (key data / bucket data / entry data layout documented in the file). An asset's *dependency key*
  names a bucket of bundle entries; the first one is the asset's own bundle.
- `StandaloneWindows64/assets_assets_assets/*.bundle` — the shared asset bundles:
  textures (3 362 entries), models (2 806), materials (2 501), sounds (1 170), shaders, animations,
  fonts, physicsmaterials, rendertextures, videos.
- `gameprefabs_assets_all.bundle` (40 MB, 1 378 prefabs, 278 k objects) — Player, weapons, arms,
  projectiles, enemies. Container names are **GUIDs**, the same GUIDs that `GunSetter` /
  `FistControl` AssetReferences use.
- `other_assets_all.bundle` (497 MB, 407 entries) — ScriptableObjects (e.g. `WeaponDescriptor`),
  big data.
- `monoscript_monoscripts.bundle` — MonoScript stubs; load it alongside any bundle so
  MonoBehaviour class names resolve.
- `music_assets_layer*.bundle`, `campaign_scenes_level*.bundle`, `specialscenes_*` — music and
  levels (not needed for 1.0 except sounds referenced from them).
- Unity's `resources.assets`/`sharedassets0` are tiny; everything real is addressable.

Reading: UnityPy 1.25 + `TypeTreeGeneratorAPI` (typetrees generated from the game's own
`Managed/*.dll`), see `tools/extract/ukenv.py`. `dump_prefab.py <key> out.json` writes the full
component tree with values; also handles ScriptableObject keys.

## Player prefab (`Assets/Prefabs/Player/Player.prefab`, GUID e35e045b18da725458dd3a2147fe861f)
Root `Player`: CapsuleCollider, Rigidbody, **NewMovement**, ClimbStep, WallCheckGroup, ...
- NewMovement serialized: `walkSpeed 750`, `airAcceleration 6000`, `jumpPower 90`,
  `wallJumpPower 150`, `hp 100`, `boostCharge 300` (stamina: 3 dashes of 100), `modForcedFrictionMultip 1`.
  Fields of interest (REA): dash = `boost/boostLeft/dashStorage/dodgeDirection`, slide =
  `sliding/slideLength/preSlideSpeed/slideSafety`, slam = `slamForce/slamStorage/slamCooldown`,
  wall jump = `currentWallJumps/wallJumpPos/clingFade`, hard damage = `antiHp/antiHpCooldown`,
  `hurtInvincibility`, `rocketJumps/hammerJumps/explosionLaunchResistance`, `ridingRocket`,
  `ssjMaxFrames` (slam storage jump), `ONE_PHYSICS_FRAME` const.
- Children: `GroundCheck` (+ child **`v1_combined`** = third-person V1: Animator + PlayerAnimations,
  SkinnedMeshRenderer `v1_mdl`, rig `metarig/spine…`, 8 wing chains `wing_N_1..3`, every weapon
  model parented under `hand.R` for the third-person view, `Seasonal Hats`), `WallCheck`,
  `SlopeCheck`, `Agent` (footsteps + a second simple rig), `Main Camera`.
- `Main Camera`: Camera, **CameraController** (`minimumX -90, maximumX 90`), children:
  - `Guns`: WalkingBob, **GunControl**, GunSetter, ViewModelFlip, **WeaponCharges**. No children in
    the prefab: weapons are instantiated at runtime from GunSetter's GUID lists
    (`revolverPierce`, `revolverRicochet`, `revolverTwirl`, `shotgunGrenade/Pump/Red`,
    `nailMagnet/Overheat/Red`, `railCannon/Harpoon/Malicious`, `rocketBlue/Green/Red`; two GUIDs per
    revolver/shotgun/nailgun entry = standard + alternate weapon).
  - `Punch`: **FistControl** (`blueArm` 8772d0e7537f5364f83a09ddd004a0c0 = Arm Blue.prefab /
    Feedbacker, `redArm` aec1f06ceaf359b45956f7731aaa2258 = Arm Red.prefab / Knuckleblaster),
    children `Projectile Parry Zone`, `Hook Arm` (**HookArm** + LineRenderer, model `GreenArmFinal`,
    sounds HookArmThrow/Hit/Pull/PullDone/Catch/Error/Woosh/Clink, loops `Whiplash Throw Loop`,
    `Whiplash Pull Loop`).
  - `HUD Camera/HUD` (HudController): `GunCanvas` → SpeedometerPanel, StatsPanel (Health /
    AntiHealth / AfterHealth / Supercharge sliders, HP text, 3 × `Stamina Slider`, FistPanel,
    AltRailcannonPanel, RailcannonChargePanel), GunPanel (WeaponHUD); `FinishCanvas` (rank screen).
    Full tree: `work/dump/hud_tree.txt`.
  - `Virtual Camera`, `Shop Camera`, `HeatWaves`.
- WeaponCharges defaults: `rev0charge 100` (pierce), `rev1charge 400` (ricochet/coins), `rev2charge 300`
  (twirl), `naiHeatsinks 2`, `naiSawHeatsinks 1`, `naiAmmo 100`, `naiSaws 10`, `naiMagnetCharge 3`,
  `naiZapperRecharge 5`, `raicharge 5`, `rocketFreezeTime 5`, `rocketCannonballCharge 1`,
  `rocketNapalmFuel 1`, `punchStamina 2`, `shoSawCharge 1`, `shoAltNadeCharge 1`.
- GunControl: slots `slot1..slot6`, `variationMemory`, `killCharge/killMeter`, `headShotComboTime`.

## Weapon prefabs (`Assets/Prefabs/Weapons/…`, all in gameprefabs bundle)
Revolver Pierce/Ricochet/Twirl, Alternative Revolver ×3, Shotgun Grenade/Pump/Saw, Hammer ×3,
Nailgun Magnet/Overheat/Zapper, Sawblade Launcher ×3, Railcannon Electric/Harpoon/Malicious,
Rocket Launcher Freeze/Cannonball/Napalm, Arm Blue, Arm Red, Special/Spawner Arm, Old/*.
Each root has `<WeaponScript>`, AudioSource, **WeaponPos** (first-person hold offset, e.g. revolver
`middlePos (0.09, -0.90, 1.49)`, `middleRot (0, 93, 30)`), **WeaponIcon** → `WeaponDescriptor`
asset (`Assets/Data/Weapons/<name>.asset`: `weaponName`, `icon` Sprite, `glowIcon`, `variationColor`),
WeaponIdentifier. Model child = rigged arm + gun (`Revolver_Rerigged_Standard`: Armature → Upper
Arm/Forearm/Hand/fingers, `Revolver_Bone/Cylinder_Bone`, `ShootPoint`, `ChargeEffect`, `Monitor`),
SkinnedMeshRenderers `Revolver_Body`, `Revolver_Cylinder`, `RightArm` with **GunColorGetter**
(custom weapon colours).
Revolver values: `pierceCharge 100`, `recoilFOV 100`, battery textures `batteryfull/mid/low`,
sounds `BeepBeep_high` (charged), `Charging`.

## Style system (strings in StyleHUD/StyleCalculator)
Bonus ids `ultrakill.<id>` with HUD text: kill, doublekill/triplekill/multikill, bigkill, headshot,
bigheadshot, headshotcombo, limbhit, criticalpunch, bigfistkill, ricoshot, fistfullofdollar,
quickdraw, interruption, parry, projectileboost, chargeback, disrespect, groundslam, airslam, airshot,
fireworks(weak), juggle, catapulted, splattered, enraged, instakill, downtosize, finishedoff, halfoff,
overkill, exploded, explosionhit, friendlyfire, fried/firehit, nailhit, nailbombed(alive), zapperhit,
lightningbolt, shotgunhit, hammerhit (red/yellow/green/heavy), homerun, cannonballed/cannonboost,
roundtrip, rocketreturn, serve, landyours, iconoclasm, heartbreak, drillhit/drillpunch(kill),
terminalvelocity, mauriced, bipolar, attripator, strike, arsenal, secret, compressed,
insurrknockdown/insurrstomp, ssj ("+{1}u/s" speedometer). Rank text D/C/B/A/S/SS/SSS/ULTRAKILL
(StyleHUD). Freshness: `weaponFreshness` dict per weapon.

## HUD layout (Player prefab `Main Camera/HUD Camera/HUD`, dumped by `tools/re/hud_layout.py`)
- **HUD Camera**: perspective, vertical FOV 90, near 0.1, depth 0, culling mask layer 13 (HUD), at the main
  camera. Main Camera: FOV field 72.49 (physical camera; CameraController sets the `fieldOfView` pref,
  default 105, at runtime), near 0.1, far 4000. `Virtual Camera`: **orthographic**, near 0.01, far 5,
  depth 1, layer 19 (virtual screens). `Shop Camera`: FOV 105. The first-person weapons are drawn by the
  main camera itself, so at the player's FOV: `cam.fieldOfView = fieldOfView` (default 105) is Unity's
  vertical FOV; UltraCraft's `view.fov` defaults to 105 for the arms whatever Minecraft's FOV is.
- **GunCanvas**: world-space, 882×496 units, pivot centre, at local (−1.06, −0.53, 1.0), rotation yaw −30°,
  scale (0.0007, 0.001, 0.001). Children anchor to its bottom-left corner (−441, −248):
  - `StatsPanel` pos (−79, 0), 200×46, scale 4 (background `Round_FillLarge` black α0.49, sliced,
    pixelsPerUnitMultiplier 5.4). `Filler` inset 5. Health row `Panel (2)` y 23–41 (`Round_FillSmall`
    α0.502): sliders 190 wide, bar = rows 0.25–0.75 of 18 (9 high) with `Round_Meter` background α0.686;
    fills: AfterHealth orange (1, 0.392, 0), Health red, SuperAfterHealth/Supercharge (100–200) green
    (0, 1, 0.004), AntiHealth from the right `Round_AntiHealth` grey 0.349 (ColorBlindGet hct 0/1/2/3).
    `HP Symbol` "+" x 5 w 10; `HP Text` x 15, w 154.5, TMP 17.05 left-aligned, red ≤ 30.
    Stamina row `Panel (3)` y 0–16 (`Round_FillSmall` α0.49): sliders 61/62/61 wide (ranges 0–100,
    100–200, 200–300), bars 8 high, backgrounds `Round_MeterLeft` / none / `Round_MeterRight` α0.686,
    fills (0, 0.876, 1) / (0.207, 0.908, 1) / (0.324, 0.933, 1); slider 1 `redEmpty` → red when not full,
    others `staminaChargingColor` (0, 0.875, 1, 0.6); `ReadyFlash` white fading 1/s when a bar fills.
    `FistPanel` anchor right-middle pos (52, 0) 46×46 (`Round_FillLarge` α0.502), `FistIcon` 36×36:
    background tinted fistIconColor×0.2, fill vertical by punchStamina/2, ×0.6 when < 1.
    `RailcannonChargePanel` / `AltRailcannonPanel`: `lightningboltbigvector` 36×90 (later).
  - `GunPanel` pos (−79, 187), 200×100, scale 4; `Image` = weapon sprite (e.g. `SingleRevolver` 700×358,
    preserveAspect) in the variation colour, `Image (1)` = `…Glow` α0.749; `FistPanel (2)` alt layout.
  - `SpeedometerPanel` pos (−79, 590), 200×25, scale 4, text "SPEED: {0.00} u/s" TMP 16 centred.
- **StyleCanvas**: 882×496 at (1.3, 0.3, 1.0), yaw +30°, scale (0.001, **0.002**, 0.001) (twice as tall).
  `Panel (1)` stretch with sizeDelta (−440, 0) → 442 wide (`Round_FillLarge` α0.49). `Panel (2)` top 150:
  rank `Image` (RankD…RankU sprites 300×100) + `Slider` (0–1) at y −93 from its centre, 50 high, bar rows
  0.25–0.75 black/white `Round_FillLarge`. `Panel` (entries) pos (0, −90) size (0, −190): `Text` TMP 48
  left-aligned 437×236 from y 70 (newest line prepended, each removed after 3 s), `Text (1)` air-time
  multiplier TMP 48 right-aligned (`<size=60>` orange ≥ 2, `<size=72>` red at 3, jitter ±3 / ±6).
  Freshness `Panel` below: pos (0, −45) 40 high orange (1, 0.531, 0); four overlapping sliders Dull
  (−1..0, dark 0.123/0.065/0.065) Stale (2..5, 0.302/0.244/0.244) Normal (5..7, red) Fresh (7..10,
  1/0.726/0), handle 20, text "FRESH: 1.50x" TMP 40 bold centred.
- Sprites (`Assets/Textures/UI/Controls/`): `Round_FillLarge` 83² border 37, `Round_FillSmall` 50² border
  25, `Round_Meter` 64² border 31, `Round_MeterLeft/Right`, `Round_AntiHealth` 50² border 25; all ppu 100.
- Font: TMP `VCR_OSD_MONO_1` from `Assets/Fonts/VCR_OSD_MONO_1.001.ttf` (exported by
  `tools/extract/export_fonts.py` → `font/vcr_osd_mono.ttf`, provider `font/vcr.json` size 32).
- Sounds: style entry tick `countingClick` (vol 0.35, pitch 3), heal flash `HpGet`, HUD open `UI Startup`.
- ColorBlindSettings defaults (scene objects): variationColors (0.251, 0.906, 1) / (0.267, 1, 0.271) /
  (1, 0.235, 0.235) / (1, 0.876, 0.235); healthBar red, afterImage (1, 0.392, 0), antiHp 0.349 grey,
  overHeal (0, 1, 0.004), stamina (0, 0.875, 1), charging α0.6, empty red; railcannon full = variation 0.

## V1 model files
`Assets/Models/V1/v1_combined.fbx` (mesh + avatar + clips), `V1.mat`, `V1Wing.mat`, textures
`v1colour_tex.png`, `v1_wingcolour_tex.png`, `v1_wingcolour_emissive.png`; arms
`Arms/Feedbacker.fbx` + `T_Feedbacker.png`, `Arms/redarmlp.fbx` + RedArm mats, `Arms/GreenArmFinal.fbx`
+ `T_GreenArm_V2.png`, `Arms/Arm Blue.fbx`. Controllers `V1Platformer.controller` etc. (V2 files are
the enemy; not needed).

## Enemy health (EnemyIdentifier.health, for the damage scale)
Filth 0.5, Stray 1.5, Drone 2, Soldier 2.5, Stalker 3.5, Streetcleaner 4.5, Schism 5, Mannequin 6,
Virtue 10, Turret 12, Malicious Face 15, Cerberus 22, Guttertank 23, Gutterman 25, Mindflayer 30,
Swordsmachine 30, Ferryman 100 (Machine 30), Mass 60, Sisyphean Insurrectionist 75, Minotaur 80,
Idol 999. Revolver body shot = 1, headshot = 2.

## Input defaults (InputActions.cs)
Move WASD, Look mouse delta, Dodge `LeftShift`, Slide `LeftCtrl` (value/hold), Jump `Space`,
Punch `F`, Change Fist `G`, Hook `R`, Primary Fire LMB (value/hold), Secondary Fire RMB,
Next Variation `E`, Previous Variation `Q` (tap <0.25 s = last weapon if `LastWeapon` unbound),
Revolver `1`, Shotgun `2`, Nailgun `3`, Railcannon `4`, Rocket Launcher `5`, Spawner Arm `6`,
scroll = next/previous weapon (options: weapons / variations), Stats `Tab`, Pause `Esc`.
Holding a weapon key ≥ 0.25 s opens the weapon wheel.

## Extracted so far (`work/assets/Assets/...`, PNG)
`export_textures.py` on Models/V1, Models/Weapons, Textures/UI, Textures/Revolver, Textures/Sprites:
1 540 images (1 345 files, sprites cut from atlases as `*.sprite.png`).

## Still to read (next)
- Decompiled code (AssetRipper export → `work/ripped/`): NewMovement.FixedUpdate numbers (dash
  speed/duration, slide, slam, wall-jump, stamina regen), Revolver/Shotgun/Nailgun/Railcannon/
  RocketLauncher fire logic and damage, Punch/HookArm, StyleCalculator values, WeaponCharges regen.
- Weapon animation clips (which clip per action), muzzle flashes, projectile prefabs, blood.
- HUD textures/fonts; speedometer and rank art.
