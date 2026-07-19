# Easy Factions - Unofficial Port (source)

Source for the CurseForge project [Easy Factions - Unofficial Port](https://www.curseforge.com/minecraft/mc-mods/overwatters-easy-factions-forked), a NeoForge 1.21.1 build that ships three mods in a single jar.

This repository exists to make the modified sources available as the upstream licenses require. The player-facing guide lives in [easy-factions-warborn-wiki](https://github.com/teamdemivfxfish-dev/easy-factions-warborn-wiki).

## Upstream and credit

**Easy Factions** was created by **OverWatter** and released under the Mozilla Public License 2.0.

- Upstream repository: https://github.com/kard3n/easy_factions
- Upstream on CurseForge: https://www.curseforge.com/minecraft/mc-mods/easy-factions

This is an unofficial port. It is not affiliated with or endorsed by the upstream author, and it keeps the MPL-2.0 license.

**MineTerritory** was created by **Leon** (leon_mout) with **cnlimiter**, released under the GNU General Public License v3.0.

- Upstream repository: https://github.com/leon-o/MineTerritory

## What is in this repository

| Directory | Mod id | License | Status |
|---|---|---|---|
| [`efwarborn/`](efwarborn) | `efwarborn` | MPL-2.0 | Complete |
| [`territory/`](territory) | `territory` | GPL-3.0-only | Complete |
| [`easy_factions/`](easy_factions) | `easy_factions` | MPL-2.0 | See note below |

`efwarborn` adds `/factionbuy`, which lets a faction at its claim cap buy the chunk it is standing on with in-game currency.

`territory` is a NeoForge 1.21.1 revival of MineTerritory, reworked to read claims from Easy Factions rather than banner power. It adds the Territory Table block and its claim-map screen, named and coloured admin territories, a minimum-member gate on faction claiming, and a kill-costs-capacity conquest system.

### Note on the `easy_factions` port sources

The port of Easy Factions itself was carried out by a collaborator, and those sources are being collected from them for publication here. They are not yet in this repository. This is a gap on our side, not a refusal, and it will be filled in.

Anyone who needs the upstream sources in the meantime should use [kard3n/easy_factions](https://github.com/kard3n/easy_factions), which is the origin of all the code under `com.jpreiss.easy_factions`.

## Licensing

The three bundled mods stay under separate licenses. Bundling them into one jar is a packaging decision for ease of installation and does not merge them into a single work.

- `easy_factions` and `efwarborn`: Mozilla Public License 2.0, see [`LICENSE`](LICENSE).
- `territory`: GNU General Public License v3.0 only, see [`territory/LICENSE`](territory/LICENSE).

If you redistribute this, please keep the upstream attribution for both projects.

## Building

Each directory is an independent Gradle project targeting NeoForge 1.21.1 on Java 21.

```
cd territory
./gradlew build
```

Both projects expect their compile-time dependency jars in a local `libs/` directory, which is not committed:

- `territory` and `efwarborn` both need the Easy Factions port jar.
- Both optionally compile against `sdmeconomy-neoforge-1.21.1-2.4.0.jar` for currency support.

The released file is assembled by merging the compiled `efwarborn` and `territory` classes into the Easy Factions port jar and declaring all three mods in a single `neoforge.mods.toml`.

## Known issues

The `JourneyMapCompat` class in the port is a no-op stub, so the JourneyMap claim overlay described in older versions of the CurseForge page never functioned. The references have been removed from the project description, and the dead optional dependency will be dropped from the mod metadata in the next build. Claim overlays are drawn by the Territory Table screen instead.
