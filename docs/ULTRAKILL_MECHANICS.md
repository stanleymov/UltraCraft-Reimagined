# ULTRAKILL mechanics, as the code does them

Read from the decompiled `Assembly-CSharp` (AssetRipper export in `work/ripped/`, never committed)
and the serialized prefab values (`work/dump/`). Units: Unity units (u), seconds. Physics runs at a
**fixed 0.008 s step (125 Hz)**, gravity **40 u/s² down**, player rigidbody **mass 100**, capsule
radius 0.5 / height 3.5 (crouch/slide 1.25), camera 1.4 u above the body origin.
Steve scale for UltraCraft: **1 u = 0.5143 blocks** (3.5 u → 1.8 blocks).

`Time.deltaTime` inside FixedUpdate is 0.008, so `walkSpeed * Time.deltaTime * 2.75` = 750·0.008·2.75
= **16.5 u/s**. Forces use `AddForce(F)` (ForceMode.Force) applied for one step: Δv = F·0.008/100.

## Movement (NewMovement)
| thing | value |
|---|---|
| ground speed | 16.5 u/s target; velocity lerps to target by 0.25·friction per step; slow (crouch) 7.5 u/s |
| air control | acceleration 6000·0.008/100 = 0.48 u/s per step along input, capped so the velocity component along each input axis never exceeds 16.5 u/s; extra "wind" boost (windState>0) ×4 accel |
| jump | Δv = 90·1500·2.6·0.008/100 = **28.08 u/s** up (height ≈ 9.9 u); horizontal velocity kept; `jumping` for 0.25 s, jump cooldown 0.2 s |
| slide-jump | 2.0× instead of 2.6× → **21.6 u/s** up, slide speed kept |
| dash-jump | 1.5× → **16.2 u/s** up; costs 100 stamina unless you have none (then horizontal velocity reset to 16.5 and the fail sound) |
| crouch jump | slow mode 1.25× → 13.5 u/s |
| slam-storage jump | (3 + (slamForce−1))× up to 12.5× (135 u/s) when jumping within 0.1 s of a slam landing (`superJumpChance`), + extra jump window 0.306 s |
| dash (dodge) | velocity = 49.5 u/s (3× 16.5) in the dodge direction for 25 steps (`boostLeft` 100, −4/step) = **0.2 s, 9.9 u**; then velocity 16.5 u/s; gravity kept; **i-frames** (layer 15) while `boostLeft>0`; costs 100 stamina; FOV −5 % forward / +10 % backward; air dash keeps you airborne |
| stamina | 300 max (3 bars), regen **70/s** (×1.5 on Lenient, ×2 on Harmless), no regen while sliding; refilled by a parry |
| slide | starts if grounded or within 0.06 s of ground / jump or ground within 2 u below; velocity = 24 u/s × preSlideSpeed (1–3, from fall speed/24 or slam) in the slide direction; strafe ±5 u/s; slide ends on key release or when speed < 10 u/s for 0.1 s; collider shrinks to 1.25, camera drops 0.625 u; slide-dash i-frames via `dashStorage` 1 s |
| slide speed store | `preSlideSpeed = |v|/24` while falling (not dashing), decays after 0.2 s; a slam sets it to `slamForce` (max 3 → 72 u/s slide) |
| ground slam | air + no ground within 3 u + falling > 0.5 s: velocity = 100 u/s down, `slamForce` 1 + 5/s while falling; landing with fallSpeed ≤ −50 = heavy impact (dust, gibs, shake); shockwave if jump held on landing (`PhysicalShockwavePlayer`: speed 75, size 12, force 5000 × slamForce·2.25); slam on an enemy: damage 2 (instakill when stomping), bounce (jump) if jump pressed within 0.15 s |
| terminal velocity | 100 u/s down |
| wall jump | needs the WallCheck sphere touching a wall, ≤3 per airtime (`currentWallJumps`), velocity zeroed then force 2000·150 along (away-from-wall + up): **24 u/s out and 24 u/s up**; third one plays `finalWallJump`; sliding/within 0.032 s of slide → reflect the slide direction instead (SSJ) |
| wall cling | on a wall, falling, not sliding: horizontal velocity clamped to ±1, fall speed = 2·clingFade (clingFade 0→50 at 4/s): slow slide down that speeds up |
| coyote | slide allowed 0.06 s after leaving ground; jump allowed while `!falling` (`fallTime` 0→1 at 5/s ⇒ 0.2 s) |
| enemy step | jump off an enemy (GroundCheck.canJump, layer 12 within 40 u): resets wall jumps / rocket jumps |
| fall | `fallTime` += 5/s; `falling` after 0.2 s; woosh sound pitch = |fallSpeed|/120 above 20 u/s |
| HUD motion | HUD offset −v/1000, hud camera offset v/350 clamped 0.2 |

Camera (CameraController): pitch clamp ±90°, sensitivity = pref/10 per input unit; dash FOV; walk bob
(camera y oscillates by −0.1 u, speed 0.5 u/s scaled by min(|v|,15)/15); tilt = −strafe input (°),
×5 while dashing, smoothed 25°/s; shake random ±amount on right/up, decays 3/s; hurt shake damage/20.
Default FOV pref (prefab) 72.49, hud camera FOV 90. Zoom = FOV/2 (railcannon alt fire, shop).

## Health (NewMovement.GetHurt / GetHealth)
- hp 100 (200 while supercharged). Overheal cap 200 on Harmless, or Lenient after 2 restarts.
- Hurt: red flash 0.5 s (0.8 if damage ≥ 50) = invincibility window (layer 15), shake damage/20.
  Style −points: `RemovePoints`, rank descends if scoreLossMultiplier > 0.5; stylePoints −damage·5.
- **Hard damage**: Standard+ only: `antiHp += damage·0.35` (cap 99); cooldown 1 s (2 s on Violent)
  + damage/20, then decays 15/s (rank ≥ S: rank·10/s; at ULTRAKILL rank it clears instantly).
  Whiplash pulling an enemy adds 66/s up to 50.
- Heal (`GetHealth(h)`): hp += h up to `100 − antiHp`; blood splatter on screen; from enemy blood
  (Bleeder/BloodsplatterManager) — gore hits heal the player when the blood reaches them.
- **Parry**: full heal (999), full stamina, +100 style "PARRY", time flash.
- Death: ragdoll, weapons off.

## Damage model (EnemyIdentifier → Zombie/Machine/… GetHurt)
`DeliverDamage(target, force, point, multiplier, tryForExplode, critMultiplier, weapon)`:
head hit → `health -= multiplier·(1 + crit)`, limb → `multiplier·(1 + 0.5·crit)`, body → `multiplier`.
Revolver beams have crit 1 (head = 2×, limb = 1.5×), super beam crit 1.5, coins crit 1, railcannon
and projectiles crit 0. Enemy health is small numbers (fodder 1–3, see ULTRAKILL_SOURCE.md →
enemy health). UltraCraft maps 1 UK damage = `damageScale` Minecraft HP (live setting; default from
the enemy-health table so that a Filth ≈ zombie).

## Fists (Punch, FistControl)
| | Feedbacker (Standard, blue) | Knuckleblaster (Heavy, red) |
|---|---|---|
| damage | 1.0 | 2.5 |
| force | 25 (×1000 to DeliverDamage) | 100 |
| cooldown | `fistCooldown = cost·0.25` = 0.5 s; punch stamina −1 (of 2, regen 1.25/s) | 0.75 s; stamina −1.5 |
| active frames | 6 physics frames (0.048 s) after the anim event | same |
| hit | ray 4 u + sphere 1 u radius 4 u ahead on Enemies; heavy hit sound, hitstop 0.1 s, shake 0.5 | shake ×2, `tryForExplode` (gibs), breaks walls, shatters glass |
| parry | `Projectile Parry Zone` box + sphere 0.01 at camera: enemy projectile → speed ×2, friendly, aimed at look target, **Parry()**; player's own projectile within the first frame → **projectile boost** (+90 style, 4× radius, orange, no self damage); also cannonballs, landmines, chainsaws (hitstop), sawblades (redirect), thrown swords | cannonball → break + shockwave damage; chainsaw → saw |
| shockwave (red) | — | `PunchBlast` → `Explosion Wave Knuckleblaster`: damage 10, size 12, push ×1.5 |
| coin punch | hits a doubled coin → `Punchflection` (+50 "FISTFUL OF DOLLAR") | — |
| style | kill 30, crit (head/limb) 60, big fistkill 150 (big enemy), disrespect 60 (big, alive), hit 20 | same hitter table |
Switching arm: `G` (`ChangeFist`); arms spawned from Arm Blue / Arm Red prefabs; `F`/dedicated keys
punch with a specific arm.

## Revolver (Revolver, RevolverBeam) — slot 1
Common: fire rate 200/s charge → **0.5 s per shot**; hitscan radius 0.4 u (0.6 when piercing),
range 1000 u; FOV kick +defaultFov/40 (charged /20); quickdraw (+50) if fired during the PickUp anim;
headshots: `gc.headshots` combo window 3 s → +headshots·20 "HEADSHOT COMBO".
Beams (`Revolver Beam` damage 1, force 5000, 1 hit; `Revolver Beam Super` damage 1, crit ×1.5,
pierces 3 bodies, max 3 hits/target, splitcoinable; `Revolver Beam Sharp` damage 1, 999 pierce,
ricochet 2, can hit projectiles; alternates ×1.25 damage, 2 hits, `strongAlt`).
- **Piercer (0, blue)**: hold Fire2 charges `pierceShotCharge` 175/s (0.57 s); charged shot = Super
  beam, costs the 100 `pierceCharge` which regens 40/s (2.5 s) (alt: 20/s). Battery screen textures
  full/mid/low + chargeback states. Recoil shake.
- **Marksman (1, green)**: Fire2 throws a **Coin** (needs rev1charge ≥ 100 of 400, regen 25/s):
  spawned 0.5 u below camera, velocity = forward 20 + up 15 + player velocity, gravity. Coin: power 2
  (+1 per chain), `TripleTime` 0.35–0.417 s and `DoubleTime` after 1 s = split (hitTimes 2) windows;
  shot coin reflects after 0.1 s to: another coin → enemy head (weakPoint) → explosive → glass →
  random; ricoshot +50 (+50 ULTRA, +50 counter, +15/ricochet); enemy coin damage = power, crit 1.
  Coin auto-deletes after 5 s or when resting.
- **Sharpshooter (2, red)**: hold Fire2 spins (charge 75/s, alt 750/s), release ≥25 → Sharp beam with
  `ricochetAmount = min(3, charge/25)`; costs 100 of rev2charge 300 (regen 15/s, alt 35/s); the alt
  gains +400 u/s² upward thrust while spinning in air.
- Alternate (Minos) revolver: slower pickup (`revaltpickupcharges` 2 s), Shoot anim ×0.5, beam
  alternates above; `InstaClick` quick twirl on coin double-hit.

## Shotgun (Shotgun) — slot 2
12 pellets (`Shotgun Projectile`: speed 75±10, damage 1 → DeliverDamage damage/4 = 0.25 each, spread
±10° (Core Eject/Sawed-on), range unlimited), plus a **point-blank zone**: enemies' Body within 4 u
in front take 4 damage ("shotgunzone", OVERKILL 100 on kill). Shake 1, player nudged +1 u/s forward.
Fire rate: anim-driven (`ReadyGun` event; the pump sequence `FireWithReload`).
- **Core Eject (0, blue)**: hold Fire2 charges `grenadeForce` 0→60 at 60/s; release ejects a
  `Grenade` (core) at velocity force+10 with up-tilt force·0.002; explodes `Explosion` (damage 35,
  size 6, speed 1, ignite) — shot midair with a hitscan → "Projectile boost"/super explosion
  (`Explosion Super`: damage 35, enemy ×2, size 12). Core costs the heat (`resettingCores`), shoSawCharge.
- **Pump Charge (1, green)**: Fire2 pumps `primaryCharge` 0→3 (pellets 10/16/24, spread ×0.67/1/2,
  pitch down); charge 3 = overpump: self explosion (`Explosion` ×1.5 size, damage 50) at the muzzle.
  Warning beep at 3.
- **Sawed-On (2, red)**: Fire2 revs the chainsaw (`sawZone` HurtZone, breaks after 40/60 non-lethal
  hits), release launches `ChainsawProjectile` (damage 1.5, 3 hits, 105 u/s return when punched) on a
  rope; one saw charge (regen 0.25/s, 0.125/s while one is out).
- **Jackhammer** (alt, `ShotgunHammer.cs`, `Hammer *.prefab`): hold Fire1 to charge the swing
  (`swingCharge` +2/s, needs 1), release = `ImpactRoutine`, `hammerCooldown` 0.5 s. The **tier** follows
  your speed: `currentSpeed = |vel| / 60` eased, tier 2 above 0.66, tier 1 above 0.33, 0.5 s grace before
  dropping a tier. Hit volume: an **8 u ray** on enemies + environment; if no enemy, the enemy closest to a
  **2.5 u sphere 2.5 u ahead**, then a 2.5 u sphere cast from 2.5 u to 5.5 u ahead (line of sight needed).
  Damage: a 4-point "hammerzone" body hit, then **3 / 6 / 10** by tier, force `50000·tier + 50000`; a
  wall (or a dead enemy) launches you backwards at `(100·tier + 300) / ((hammerJumps + 3) / 3)` u/s
  (`explosionLaunchResistance` 0.5), an enemy hit with `launchPlayer` at `300·tier + 100`. Hitstop
  0.05 / 0.25 / 0.5 s by tier. Overheat (7 s, `IdleCooldown`): any tier-2 hit, or the third yellow (tier 1)
  hit within 3 s. Variations: Core (Fire2 = `NadeSpawn`, `shoAltNadeCharge` regen 0.5/s, the core is
  primed for the swing), Pump (Fire2 pumps `primaryCharge`; a charged impact adds `Explosion Hammer Weak`
  — damage 35, size 4 — or `Explosion Super` at 3 pumps), Saw (Fire2 revs / launches the saw, swing hits
  a flying saw at 105 u/s). Style: hammerhit `40·(tier+1)`, hammerhitheavy 120, kills hammerhitgreen 80 /
  yellow 120 / red 240 (BLUNT FORCE / HEAVY HITTER / FULL IMPACT, BLASTING AWAY for the pump blast).

## Nailgun / Sawblade Launcher (Nailgun) — slot 3
Nails: `Nail` damage **0.185** (→ fodder boost ×2 Filth/Stray, ×1.5 Schism/Soldier/Stalker), speed
**200 u/s**, stick in enemies for 5 s (nailbomb fodder), heated nails ignite. Fire rate field
`fireRate` 4 (prefab) interpreted as `fireCooldown` ticked down 100/s → cooldown/100 s between shots
(≈0.04 s = 25 nails/s at base; `+3.5 − heatUp·3.5` for magnet/zapper).
- **Attractor (0 → variation 1 in code, "Magnet")**: ammo `naiAmmo` 100 (regen 5/s when not firing);
  Fire2 throws a **Magnet** (`magnetNail`, 100 u/s; `naiMagnetCharge` 3, regen 3/s); nails curve to
  magnets (`strength` 300, `maxWeight` 15, health 3); attractor kill with 2 magnets = BIPOLAR 120,
  1 magnet = ATTRAPTOR 60.
- **Overheat (1 → variation 0, "Overheat")**: holding fire heats `heatUp` +0.55/s; Fire2 dumps a heat
  sink (2 sinks, regen 0.125/s) → `burnOut` burst of heated nails (fire rate −2.5, spread ×2) until
  heat is gone (−1/s).
- **JumpStart (2, "Zapper")**: Fire2 fires a Zapper (100 u/s, needs `naiZapperRecharge` 5, regen 1/s)
  that tethers an enemy; nails hitting it charge it; HUD: READY / OUT OF RANGE / NO TARGET / DISTANCE.
- **Sawblade Launcher** (alt): `NailAlt` sawblade damage 0.6, `hitAmount` 2.9 (−1 per enemy, −0.125 per
  bounce, breaks below 1), bounces off walls; heated `NailAltHeated` damage 1, 20.9 hits, up to 3 hits on
  one enemy (`multiHit`), bounces to the surface normal, burns; speed 200 u/s, rigidbody mass 0.5, no
  gravity. Prefab: root scale 0.1, a `Quad` (scale 2, rotated 90° X, material `Sawblade1` colour 0.5
  grey, texture `sawblade`, `Spin` script) + a ProBuilder `Cylinder` centre (scale 15, texture
  `sawblade 1`, mesh built at runtime — not exported), TrailRenderer 0.5 s width 2. `naiSaws` 10 ammo
  (regen 0.5/s), fire rate `4 + (heat − 0.5)·65` ticks (no sink +50), heat +0.125 per blade (Overheat),
  burnOut 3; SuperSaw (Fire2 on overheat) uses the one saw heat sink and fires `multiHit = round(heat·3)`.
Barrel spin 250 + heat·2250 °/s.

## Railcannon (Railcannon) — slot 4
One shot when `raicharge` = 5 (regen **0.25/s = 20 s**, shared by all three), Fire2 zooms (FOV/2).
`Railcannon Beam`: damage 2, force 50000, pierces 999, 4 hits/target, radius 1.2, attribute
Electricity (zaps water), shake 2. Harpoon (variation 1): rigidbody `Harpoon` at 250 u/s with a Magnet
(strength 300). Malicious (2): `Railcannon Beam Malicious` → `Explosion Malicious Railcannon`
(damage 50, enemy ×1.25, size 13.5, unblockable). Full-charge sound + idle hum; emissive pips show
charge (5 pips).

## Rocket Launcher (RocketLauncher, Grenade) — slot 5
`rateOfFire` 1 s cooldown (shared `rocketcharge`); rocket `Rocket` speed **100 u/s** (0.65× while
ridden, nosediving over 4.5 s), explosion `Explosion` (damage 35, size 6), harmless variant when hitting
walls, **super** explosion when the enemy is airborne / rocket is frozen+levelled (`Explosion Super`
×1.5 size, damage 35 enemy ×2). Rocket jump: explosion pushes the player (200 force /
rocketJumps falloff: 100/((n+3)/3)). **Rocket riding**: approach a rocket from above while falling
(within 2.25 u) → ride; steer 165°/s; jump off = Jump + slide-speed store.
- **Freezeframe (0, blue)**: Fire2 freezes all rockets for up to 5 s (`rocketFreezeTime`, refills at
  0.5/s), frozen 1 s = levelled up (super explosion).
- **S.R.S. Cannon (1, green)**: hold Fire2 charges `cbCharge` 0→1 in 1 s (needs
  `rocketCannonballCharge` 1, regen 0.125/s); cannonball velocity = (1−charge)·playerVel +
  forward·max(15, charge·150); `Cannonball` damage 5, speed 150, 1 bounce, durability 2;
  CANNONBALLED 75 / DUNKED / CANNONBOOST.
- **Firestarter (2, red)**: hold Fire2 sprays napalm (`rocketNapalmFuel` 1, −0.015 per 0.02 s,
  regen 0.125/s; needs ≥0.25 to start), 150 u/s gasoline projectiles; rockets ignite gasoline.

## Explosions (Explosion.cs, prefabs under Attacks and Projectiles/Explosions)
- The root is scaled ×12; `Sphere_8` (collider radius 0.2236) grows `localScale += 0.05·speed` per physics
  tick (50 Hz) until `lossyScale·0.2236 > maxSize` → so **`maxSize` is the final world radius in u**
  (initial radius 2.68 u, +6.7·speed u/s; size 6 takes ≈0.5 s), then it stops hurting and fades 2/s.
- Enemies: `DeliverDamage(…, damage / 10 · enemyDamageMultiplier)`; once the radius passes `maxSize / 2`
  the damage is ÷1.5 (`halved`). Prefabs: `Explosion` 35 / ×1 / size 6 / ignite; `Explosion Super` 35 /
  ×2 / 12 / unblockable (speed 1.75); `Explosion Big` 50 / 13.5; `Explosion Malicious Railcannon` 50 /
  ×1.25 / 13.5 (speed 1.5); `Explosion Rocket Harmless` and `Explosion Harmless` keep damage 35 for
  enemies — "harmless" only concerns the player (rockets on surfaces = rocket jumps without damage).
  Pump Charge overpump: `Explosion` with damage 50 and maxSize ×1.5. Knuckleblaster wave: 10 / 12.
- Player: `LaunchFromPoint(origin, 200, maxSize)` — impulse `(maxSize − distance)·200` along the
  away-vector, vertical at least half of that, clamped to 1000, ×8 on the 100 kg body (≤ 80 u/s); rocket
  jumps (rocket explosion with damage 0 for the player) use `100 / ((rocketJumps + 3) / 3)` instead and
  count up `rocketJumps`; `GetHurt(damage)` when the explosion has damage for the player (35 = 35 hp).
  Camera shake 1.5 within 3·maxSize. Sounds: `Explosion 1/2`, harmless variant `explosion_harmless`.

## WeaponCharges (regen per second, `Charge(dt)`)
rev0 (Piercer battery) +40 (alt 20) to 100; rev1 (coins) +25 to 400; rev2 +15 (alt 35) to 300;
shoSawCharge +0.25 to 1 (0.125 while a saw is out); naiHeatsinks +0.125 to 2; naiZapperRecharge +1 to 5;
raicharge +0.25 to 5 (snaps to 5 at 4 and plays the full-charge sound); rocketcharge −1 to 0 (the 1 s
rate of fire); rocketCannonballCharge +0.125 to 1; rocketNapalmFuel +0.125 to 1 (−0.015 per 0.02 s
spraying); naiAmmo +5 to 100 and naiSaws +0.5 to 10 unless firing; naiMagnetCharge +3 to 3 (minus
magnets out); rocketFreezeTime −1/s frozen, +0.5/s otherwise, auto-unfreeze at 0; punchStamina +1.25 to 2.

## Projectile prefabs (numbers used by UltraCraft's UKProjectileEntity)
- `Shotgun Projectile`: Projectile speed 75 ±10, damage 1 (→ ÷4 on enemies), sphere 0.5 at scale 0.2,
  5 s life, no gravity, yellow light (1, 0.747, 0). The mesh is Unity's built-in sphere.
- `Nail` 0.185, `NailFodder` 0.205 + fodder boost (Attractor), `NailHeated` 0.185 + burn; all 200 u/s,
  hitAmount 3.9, stick 5 s; prefab scale 0.16; hits "Ricochet" (wall) / "Impacts_SIMPLE_003" (enemy).
- `Grenade` (core): capsule 0.4×1.5 at scale 0.5, mass 1 with gravity, 30 s life, `hitterWeapon shotgun0`.
- `Rocket`: capsule r 1 h 6 at scale 0.35, `rocketSpeed` 100 (×0.65 ridden), `Model` mesh child scale
  2.857; frozen 1 s → `levelledUp` → super explosion; surfaces → `Explosion Rocket Harmless`.
- `Cannonball`: sphere r 1, damage 5 (+2 after its one bounce), speed 150, durability 2, mass 1 gravity.
- `Harpoon` (Attractor magnet): box 3×3×40 at scale 0.1, mass 100 gravity, Magnet child strength 300
  maxWeight 15 health 3; `Harpoon Malicious` (Screwdriver): drill, damage 3, 116 drill hits of 0.0625
  every 0.05 s (7.25 total over 5.8 s), 8 s TimeBomb; sounds harpoonShoot/Pierce/Stop/Charged.
- `Zapper`: damage 10, maxDistance 20, 100 u/s; `GasolineProjectile` 150 u/s, scale 0.5, leaves a stain.
- Weapon prefabs: Shotgun root at (0.42, −0.6, 1.49) scale (0.289, 0.289, 0.33) like the Revolver; the
  Nailgun / Railcannon / Rocket Launcher roots at the origin with the model child offset (0.395, −0.435,
  0.586) / (0.6, −0.85, 1.4) / (0.35, −0.5, 0.25). Clips: Shotgun FireWithReload / FireWithPump / Pump2 /
  FireWithThrowReload / Equip / Idle / IdleSawing (ReadyGun, Pump1Sound, Pump2Sound, ClickSound, Smack
  events); Nailgun Equip (CanShoot) / Idle / Shoot; Railcannon Equip / Fire; Rocket Launcher Equip / Idle /
  Fire (Clunk ×2) / Spray. Icons: WeaponDescriptor `icon` / `glowIcon` (`Assets/Data/Weapons/*.asset`).

## Whiplash (HookArm)
`R` (`Hook`) throws the hook forward at **250 u/s** (max 300 u line), cooldown 0.5 s, sphere cast
radius grows with distance (≤5). Hits: environment → clink sparks, returns; enemy → `Caught`:
light enemies (Filth, Stray, Schism, Soldier, Streetcleaner, Drone) are **pulled to you at 60 u/s**,
heavy enemies pull **you** to them at 60 u/s (release key starts the pull; `beingPulled`, ground
checks off, hard damage +66/s while pulling up to 50); items/hook points/rockets (ride) /
cannonballs can be grabbed; coins get bounced; pulling into something within 0.25 u ends it with an
upward 15 u/s hop. Jump while pulling: stops, clamps |v| to 30, +15 u/s up. Rope has 'throwWarp'
wobble; sounds Whiplash Throw/Pull loops, HookArmThrow/Hit/Pull/PullDone/Catch/Error/Woosh/Clink.
Hook sphere radius `min(distance / 15, 5)` u; return speed `100 + returnDistance / 2` u/s. "Light" in
UltraCraft = max health ≤ `whiplash.lightHp` (30 HP, live key). The arm is `Player/Main Camera/Punch/
Hook Arm` (`GreenArmFinal` at (−0.75, −0.5, −0.2) scale 5, rotated 10° about Y; meshes `Arm` + `Hook`,
texture T_GreenArm; 21 bones; clips Idle / Throw 0.5 s / Pull 1.5 s / Catch 1 s with the `CatchOver`
event at 0.80 s / Inspect). The wire is a world-space LineRenderer, `widthMultiplier` **0.1 u**, 9 points
from the `Wire Start` bone in the hand to the hook, material WhiplashWire (texture mode tile).

---
## Animator controllers (what happens after a one-shot clip)
Read from the AssetRipper `.controller` YAML with `tools/re/controller_transitions.py`; the mod's
`client/uk/UkTransitions.java` holds the table. Each one-shot state has a speed multiplier and an
exit-time transition back to `Idle` (normalized exit time, cross-fade seconds); states without a
transition hold their last frame, like the game.

| controller | state | speed | exit at | fade |
|---|---|---|---|---|
| Punch (Feedbacker) | Jab / Jab2 / JabHolding | 1 | 0.6455 | 0.3481 |
| | Hook | 1 | 0.6627 | 0.3354 |
| | CoinFlip | 1 | 0.75 | 0.15 |
| PunchRed (Knuckleblaster) | Jab (plays `Punch`) | 0.85 | 0.95 | 0.25 |
| Revolver Heavy | PickUp | 1 | 0.625 | 0.25 |
| | Shoot / Shoot2 / Shoot3 | 1.2 | ≈0.82 | ≈0.12 |
| ShotgunNew | Equip | 1 | 0.9834 | 0.0166 |
| | FireWithThrowReload | 1.5 | 0.9063 | 0.25 |
| | Pump2 | 1.65 | 0.9769 | 0 |
| | FireWithPump | 1.1 | 0.8171 | 0.25 |
| | FireWithReload | 1.1 | 0.9861 | 0.0159 |
| | IdleSawing | 5 | loops | |
| Nailgun New | Equip | 1.45 | 0.9165 | 0.0686 |
| | Shoot | 1 | 1.0 | 0.0358 |
| RocketLauncher | Equip / Fire | 1 | 1 | 0 |
| | Spray | 0.66 | 0.9771 | 0.0055 |
| Railcannon | Equip / Fire | 1 | none (holds) | |
| GreenArm (Whiplash) | Idle default; Throw / Pull / Catch are played by `HookArm` itself | | | |
| Hammer (Jackhammer) | Fire / FireStrong | 1 | 0.9777 / 0.9828 | 0.022 |
| | FireStrongest → IdleCooldown | 1 | 0.9617 | 0.0498 |
| | Equip / NadeSpawn / Pump / SawingShot | 1 | ≈1.0 | 0 |
| | Idle 0.5, IdleCooldown 1, IdleSawing 10 | | loop | |
| Nailgun2 (Sawblade Launcher) | Equip | 1 | 0.9486 | 0.0514 |
| | Shoot / ShootSuper | 1 | 0.9143 / 0.9318 | 0.057 / 0.032 |
| | Idle | 0.5 | loop | |
| V1Platformer (third-person V1) | Jump → Falling 0.75 / 0.25; Landing → Idle/Running 0.25 / 0.25; Dash (speed 2.5) → Idle/Running ≈0.88 / 0.12; SlamEnd → Idle 0.98; Idle / Running / Falling / Sliding / Spin loop | | | |

---
## V1 model (third person)
`Assets/Prefabs/Player/PlatformerController.prefab` → `Player` (scale 0.8, Animator V1Platformer) →
`v1_combined` (scale 3) → `metarig` (rotated −90° X, Blender z-up): mesh `v1_mdl` 6441 verts / 2708 tris /
59 skinning bones of 99, texture `v1colour_tex` (V2 = `v2_combined`, `v2colour_tex`, same skeleton);
height 1.6 × 2.4 = 3.84 u. Bones of note: `hand.L` (+ `Holder`, scale 0.4), `hand.R` (V2 hangs its
EnemyRevolver here at local (−0.01, 0.056, 0.022), rot (0.059, −0.84, −0.182, 0.508), scale 0.05),
`spine.006` head, `wing_1_1 … wing_2_1` wings. Clips: Idle 2 s, Running 1 s, Falling 1 s, Jump 1 s,
Landing 0.333 s, Dash 1 s, Sliding 0.5 s, Spin 0.333 s, SlamStart / SlamEnd 0.667 s (60 tracks each).

## Style (StyleCalculator, StyleHUD)
- Ranks (meter, drain/s): D 200/1, C 300/1.25, B 400/1.5, A 500/2, S 700/3, SS 850/4, SSS 1000/6,
  ULTRAKILL 1500/8 (`drainSpeed·15` per second). Points over a rank's meter → next rank, keeping at
  least ¼; descending keeps ¾. Combo ends at 0 → "ComboOver" and freshness reset after 10 s.
- Points: `StyleCalculator.HitCalculator` table (kill 30 revolver/punch, 45 shotgun/nail/rail,
  headshot 80, limb 60, big kill 100, overkill 100, bigfistkill 150, criticalpunch 60, parry 100,
  projectile boost 90, ricoshot 50+, fistful 50, quickdraw 50, chargeback 400, exploded 45, friendly
  fire 200/250, double 25 / triple 50 / multikill 100 (5 s window), hits: revolver 10/15 limb/25 head,
  shotgun 4, nail 2, rail 15, zapper 60, lightning 200/250/300, slam 60, interruption 100, …).
  **Air time multiplier**: ×1→3 (+2/s while airborne or sliding, back to 1 at 10/s on ground), shown
  as "x1.00…x3.00" (orange ≥2, red at 3): points·airTime.
- **Freshness** per weapon (10 max): FRESH ×1.5 (7.5–10), USED ×1.0 (5–7.5), STALE ×0.5 (1–5), DULL
  ×0 (0–1). Each scoring move −0.7 (×id multiplier: shotgunhit 0.15, nailhit 0.1, kill 0.3,
  explosionhit 0.75, exploded 1.25, hammer hits 3–8, quickdraw/projectile boost/arsenal 0), other
  weapons in other slots +0.5 per move and +0.1/s; bosses ×1.5 gain and decay.
- Multikill: `multikillTimer = 5`, drained at 10/s → a **0.5 s** window; 2nd kill +25 DOUBLE KILL, 3rd +50
  TRIPLE KILL, then +100 MULTIKILL xN. Headshot combo: `gc.headshots++` on each live head hit (3 s window,
  reset by a body hit); from the 2nd, +headshots·20 "HEADSHOT COMBO xN".
- Fists are not in `gc.allWeapons`, so their hits always score as FRESH (×1.5) and never decay.
- Hurt (NewMovement.GetHurt): `scoreLossMultiplier` > 0.5 (Standard+) → `RemovePoints(0)` (rank shake) and
  `DescendRank()`; Lenient (0.5) → `RemovePoints(damage)`; P −damage·5·mult (−1000·mult if damage > 200).
- HUD tick for a new style entry: `countingClick` at pitch 3.
- P (shop currency) = `StatsManager.stylePoints` accumulated (minus damage taken ×5).

## Input (defaults, InputActions.cs — printed by `tools/re/input_bindings.py`)
See `docs/ULTRAKILL_SOURCE.md` → Input.

## HUD (Player prefab, `HUD Camera/HUD`)
GunCanvas: Speedometer ("+{n}u/s" style), StatsPanel (Health slider + AfterHealth (overheal) +
AntiHealth (hard damage, black) + Supercharge, HP text, 3 stamina sliders (blue, ReadyFlash),
FistPanel icon with fill, AltRailcannonPanel, RailcannonChargePanel 5 pips), GunPanel (WeaponHUD
icon + variation colour), style panel (rank sprite, meter slider, scrolling "+ KILL" list, freshness
slider with FRESH/USED/STALE/DULL text). Font: VCR OSD Mono. Variation colours from
`ColorBlindSettings.variationColors` (blue/green/red, user-adjustable).

## Parry (Punch.cs, Zombie.cs, NewMovement.Parry, TimeController.ParryFlash)
- Feedbacker (`FistType.Standard`) only. `ActiveFrame(firstFrame)` each active frame: OverlapSphere at the
  camera (layer 16384 = projectiles) → `TryParryProjectile`, then a 4 u ray / 0.3 box cast, then the
  `ProjectileParryZone` trigger (closest projectile in it). `TryParryProjectile`: a `Projectile` that is not
  `unparryable` / `undeflectable` and is either an enemy's or (`!alreadyBoostedProjectile & firstFrame`) the
  player's own → `ParryProjectile`; a launchable `Cannonball` → launched at the look target; a
  `ParryReceiver` → its `onParry` (`parryHeal` → `Parry()`).
- `ParryProjectile`: `friendly = parried = true`, `speed *= 2`, no homing, explosion effect = the
  "Explosion" prefab, portal type player projectile. Player bullet (projectile boost): `boosted = true`,
  SphereCollider radius ×4, damage 0, colour (1, 0.35, 0) on mesh + trail.
- Melee: enemies set `parryable` from their attack animation (`ParryableCheck`), shown as the yellow flash.
  `Zombie.GotHit` with hitter "punch" while `attacking` → health −5 (−4 if `parryFramesLeft`), `attacking =
  false`, `currentPunch.Parry()`; hitter "shotgunzone" / "hammerzone" while attacking → damage ×2 and
  `NewMovement.Parry`. `Punch.Parry`: `parriedSomething`, `activeFrames = 0`, optional `anim.Play("Hook", 0,
  0.065)`, punch sound at pitch 0.7–0.8, then `NewMovement.Parry`: `ParryFlash` (white flash 0.1 s, TrueStop
  0.25 s, CameraShake 0.5), `GetHealth(999)`, `FullStamina`, +100 "PARRY".
- UltraCraft: a mob's direct melee hit opens a 7-tick window (`ParryServer`) instead of landing; the mob is
  tinted yellow through the overlay texture; the Feedbacker / shotgun zone parry it. Projectile parry over 3
  server ticks with a velocity look-ahead; pellet boost on the first.

## Core Eject heat (Shotgun.cs)
- `heatSinkSMR.materials[3]` = HeatSinkHeat, `_TintColor` (1, 0.2397, 0); `Shoot()` sets alpha 1,
  `ShootSinks()` sets 0, the `ReleaseHeat` animation event (FireWithReload 0.42 s, FireWithThrowReload 0.42 s)
  starts `releasingHeat` (alpha −2.5/s), plays the `heatReleaseParticles` (Smoke2 / smokesprite systems under
  `GunArmature/Shell L` and `Shell R`: cone 25°, speed 2–5, size 0.3–0.5, life 0.5 s, rate 100 → 0 over 1 s,
  colour white → 0.8 grey α 0.75) and the sink AudioSource ("Mechanism - Designed Mega Steam Lever-002",
  volume 0.25).

## Knuckleblaster shells (Punch.Eject, ShotgunShell.cs)
- PunchBlast clip events: ResetFistRotation 0.13, **Eject 0.78**, ReadyToPunch 1.39. `Eject`: the
  `ShellEjector` AudioSource (clip `fistReload`, volume 0.5) plays and two `ShotgunShell` prefabs
  (Assets/Particles/ShotgunShell.prefab: mesh shotgunshell, texture v2_armtex) spawn at `shellEjector.position
  + right × 0.075` (the second −0.15 right), rolled ±45° about forward, with VelocityChange `(forward / 1.75 +
  up / 2 + Vector3.up / 1.75) × Random(8, 12)`. `ShotgunShell`: collider on after 0.2 s, `shellImpact` (pitch
  0.85–1.15) on the first environment contact, removed after 2 s unless resting (then parented to the gib zone).

## Revolver canvases (Revolver Ricochet / Twirl prefabs, `Revolver_Bone/Canvas`, 15×15)
- Marksman: `Panel` (black, meter 1 sprite) with `Panel`, `Panel (1)`, `Panel (2)`, `Panel (3)` — `dot` sprites 5×5
  at (±2, ±2), Image type Filled, Radial360, origin 0, clockwise. `CheckCoinCharges`: fillAmount = charge/100 − i;
  colour red while < 1, the variation colour once full; the canvas AudioSource (`Bell_10_b`, 0.15) plays at pitch
  1 + i/2 when a panel completes. The cylinder's AudioSource is `click` (0.2), the monitor's `BeepBeep_high`.
- Sharpshooter: `Background 1-3` (3.75×11 at x −1 / −5.625 / −10.25, y 2; colour (0.2075, 0.042, 0.042), sprite
  meter 7) under `Meter 1-3` (Filled, vertical, origin bottom): grey while filling, (1, 0.235, 0.235) once full.
- Revolver.Update: `anim.SetBool("Spinning", …)` while charging the Sharpshooter (the Twirl loop clip, 0.333 s,
  the hand swings) and `twirlBone` (= Revolver_Bone) rotates about its forward by `latestTwirlRotation`
  (1200 × (twirlLevel/3 + 0.5) °/s); `ShootTwirl` on release.

## Clash mode (PlatformerMovement.Spin, SpinZone)
- Fire1 / Fire2 / Punch → `anim.Play("Spin")`, `spinning` for `spinJuice` 1 s (−3/s), V1 moves along
  `spinDirection` at walkSpeed × 8.25 × dt while boosting (5 × preSlideSpeed when sliding); the SpinZone trigger
  spins enemies it touches, breaks Breakables and Glass.

## Grapple points (HookPoint, GrapplePoint.prefab)
- Types Normal / Slingshot (`slingShotForce`) / Switch; `reactivationTime` 6 s. Model: `Core/Sphere_12 (1)`
  (scale 5, material Bonus 4 (0, 0.75, 0)) inside `LookAtCamera/Sphere_12` (scale 3.5, Bonus 5
  (0.65, 0.94, 0.34)), two spinning `Arch` rings (Metal Decoration 1B Orange), a Light and `hookPointGrab`.
  HookArm: a caught HookPoint pulls V1 straight to it; Slingshot multiplies the velocity by `slingShotForce` on
  release.

## Surface footsteps (PlayerFootsteps, Player Footstep Set)
- NewMovement.CheckLanding (fallSpeed > −50): landingSound at 0.5 + fallSpeed × −0.01, pitch 0.9–1.1, then
  `Footstep(0.5, force, 0)` and `Footstep(0.5, force, 0.05)`. LandingImpact (harder): `Footstep(1, …)` twice.
  One AudioSource, so the delayed step replaces the first: one step 0.05 s after touchdown.
- PlayFootstepClip: random clip from the surface set (never the same index twice), pitch 0.9–1.1.
- Surface: SceneHelper.TryGetSurfaceData (raycast down); Wet while touching water but not submerged.
- Player Footstep Set: Generic = footstep_heavy1–4; Carpet, Crunchy, Dirt, Flesh, Glass (1, 3, 4), Grass, Gravel,
  Hologram, Marble, MetalGrill, MetalHigh, MetalLow, MetalStumpy (footstep_Metal), MetalThin, Rubble, Sand,
  Snow (1, 3), Wet, Wood, WoodThin. Minecraft blocks map to these by sound group (client/v1/Surfaces).
- Walking (PlayerFootsteps.Update): a step at volume 0.25 each time a timer of 1 runs out at
  3 × min(speed, 15) / 15 per second; wall jumps also play the wall's surface step. Not ported yet.

## Slide scrape (SlideScrape.prefab, NewMovement.HandleSlideState)
- Spawned by CreateSlideScrape at position + dodgeDirection × 2, LookRotation(−dodgeDirection); then every frame
  HandleSlideState moves it to transform.position + v̂ (velocity projected on the ground plane) and sets its forward
  to −v̂. On the ground only; off the ground it is parked at (5000, 5000, 5000) unless on a wall.
- transform.position is the player root: capsule height 3.5 centred at y 0.25 (feet 1.5 u below), Main Camera at
  local y 1.4. StartSlide shrinks the capsule to 1.25 and lowers the root 1.125 u; the camera target drops 0.625.
- Root scale 0.25; Sparks child scale 4 with Hierarchy scaling (net × 1): 80/s, 60° cone, 10 u/s, 0.5 s,
  size 0.1–0.5 → 0, gravity 0.5 × Physics.gravity (−40), Local simulation; trails 0.5 × size, (1, 0.83, 0)
  alpha 0.2 → 0. Dust child: smoke, 100/s, grey alpha 0.2. Per-surface variants in the footstep set's slideParticles.

## Punch stamina and the fist icon (WeaponCharges, Punch, HudController)
- punchStamina max 2, regen 1.25/s; Punch.Update needs fistCooldown ≤ 0 and punchStamina ≥ 1; PunchStart sets
  fistCooldown = cooldownCost × 0.25 and spends cooldownCost / 2 (Feedbacker 2 → 1: two punches per charge;
  Knuckleblaster 3 → 1.5). fistCooldown falls at 2/s.
- HudController.Update: fistFill (Filled, Vertical, from the bottom) = punchStamina / 2, colour × 0.6 under 1;
  fistBackground = colour × 0.2. UpdateFistIcon colour = variationColors[arm] (the mod uses the stamina blue).

## Crosshair HUD stamina (Canvas / Crosshair / StaminaBars)
- StaminaSlider (1)–(3): 32 × 32 "circlethick", Filled Radial 360, origin bottom, counter-clockwise, z rotation
  3 / 62.5 / 122°. SliderToFillAmount: fill = (value − min) / (max − min) × maxFill 0.15, copies the bar colour.
  Canvas: CanvasScaler 1280 × 720, Expand.

## Marksman coin (Coin.prefab, Revolver.ThrowCoin, Coin.cs)
- ThrowCoin: spawned at the camera − up × 0.5 with the camera's rotation; AddForce(forward × 20 + up × 15 + player
  velocity, VelocityChange); falls with Physics.gravity (0, −40, 0) (or the player's custom gravity magnitude).
- Model: Unity Cylinder at 0.1 × 0.1 × 0.01, material CoinUnlit (coin01_2, white, unlit).
- TrailRenderer: time 0.5 s, width 0.2 → 0, white alpha 0.49 → 0, min vertex distance 0.1, material Additive.
- Coin.Start invokes TripleTime 0.35 s (CoinFlash: muzzleflash sprite 32 px / 100 ppu × 4, random spin, shrink and
  fade 1/s, RemoveOnTime 0.5 s, "coinflip" 0.5 vol at pitch 1.35 ± 0.05, point light (1, 0.94, 0.67) × 20 range 3),
  TripleTimeEnd 0.417, DoubleTime 1 s (CoinCharge: lasting glow with inward sparks), GetDeleted 5 s.
- Gun screen: four 5 × 5 dots anchored 2 in from each corner of the 15 × 15 canvas (centres ±3), Radial 360
  from the bottom clockwise on a (−x, −y) canvas = clockwise from the top on screen.
- Coin hit detection: Coin.prefab has a trigger SphereCollider radius 1 u (layer 10 Limb) and a solid BoxCollider
  0.2 × 0.2 × 0.02. RevolverBeam.Shoot sphere-casts radius 0.4 (single hit; beamType 3: 0.1), 0.6 piercing,
  1.2 for beamType 1, against enemyLayerMask (Start adds layers 2, 10, 11, + 14 if canHitProjectiles) with
  queriesHitTriggers on: a beam takes the coin when its line passes within radius + 1 u of the centre.
- GetDeleted (on any environment collision, or 5 s after the throw): spawns CoinBreak ("319630__vehicle__coinflip-01",
  0.5), swaps to uselessMaterial "Coin" (coin01_3, lit), destroys the AudioSources, TrailRenderer, trigger sphere,
  Zappable and the Coin script; the Rigidbody + BoxCollider stay, so it falls and lies there; RemoveOnTime 5 s.
- Coin look in flight (checked side by side against the game, 2026-10-19): no spin (Coin.cs never rotates it; the
  coin keeps camObj.rotation from Instantiate, so it faces the thrower), about 0.2 u across. Flight on screen
  (elevation per 0.1 s) matches the game. ULTRAKILL's camera is fieldOfView 105 (vertical); auto-aim
  (CameraFrustumTargeter, prefs autoAim / autoAimAmount) defaults off.
