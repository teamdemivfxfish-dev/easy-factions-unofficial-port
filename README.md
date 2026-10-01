# Holdfast Factions (source)

Holdfast Factions is a NeoForge 1.21.1 faction and territory mod. It is one mod in one jar with the mod id `holdfast_factions`.

Maintained by Xmat5 and Barunn. The Territory Table is based on MineTerritory by Leon (leon_mout) and cnlimiter, released under the GNU General Public License v3.0: https://github.com/leon-o/MineTerritory

## How land works

- The Territory Table is the only way to claim. There are no commands that claim or unclaim land.
- A claim has to touch the owner's existing land in that dimension. The first claim, the home base, has to be near the player.
- Each faction has one core table, the first one a member places or deposits into. Members put Create: Numismatics coins into it. Numismatics is optional: without it, ores and gems are accepted instead. Both item lists and their values are configurable. Like a Rust tool cupboard, the table just uses up what is inside: items are used whole, cheapest first, no change is given back, and any value beyond what is due stays in the table as credit that counts toward the next payments. The items are physical: breaking the table drops them, and the stored credit is lost.
- The table's Vault tab has real inventory slots for depositing coins. Everything there is shown as time, not as coin totals: how long the core covers, what each item is worth (in the list, and when you hover an item), and how much time the items in the slots add. Right-clicking the table holding coins works too.
- Once per interval of real time (24 hours by default) the faction pays 4 spurs per chunk from its core.
- If it cannot pay it gets a 5 day grace period (configurable) to deposit coins. After that, land it cannot pay for is released outermost first.
- Only faction land costs upkeep. Safezones and warzones are never counted, charged or released, and personal claims are free too.
- Killing a player no longer takes land.
- If a faction's core table is blown up, every claim the faction holds is released at once, in every dimension, and its members are told. Breaking the table by hand does not do this: that keeps the 5 day grace to place a new core. Disbanding a faction also releases all of its faction claims. Personal claims belong to their players and are kept. Explosions are always allowed in faction and personal claims, whatever the config lists, so a core can always be blown up. Safezones and warzones never allow explosions (a safezone's Damage switch can allow them).
- PvP is never switched off by a claim. Faction and personal claims cannot stop players hurting each other, whatever the config lists. Only a safezone can, through its Attack switch, and a warzone always has PvP except between members of one faction.
- Mounts can be ridden inside claims, whoever owns the land, including safezones (`allowMounts`, on by default). Only a plain mount attempt gets through: sneaking, or holding a saddle, horse armor, food, a lead or a name tag, still follows the claim rules.
- Low upkeep warning: when the core can only cover `warnHours` more hours (24 by default), every online member gets a message, again every `warnRepeatMinutes`, and again when they log in. The Faction tab shows the same state in red.
- Faction access: owners and officers get a Permissions tab with three switches. Who may use doors, trapdoors and fence gates (members only, allies too, or everyone), who may use crafting tables and other screens that store nothing (same three levels), and who may add to the faction bank (all members by default, officers, or owner only). Chests and other containers always stay members only. Allies are the factions you set to Friendly.
- The Vault tab lists the top contributors and what you have added yourself, as time.
- Wars come from WarNTaxes (Minecolonies: War 'N Taxes). While a WarNTaxes war is in its fighting phase, the factions of the people on each side are at war: they can open chests, use doors, crafting tables and other blocks in each other's claims,. Breaking and placing blocks stay locked unless you turn on allowBreak or allowPlace. Nobody loses land and a war never transfers a claim. It needs WarNTaxes installed and does nothing without it. The `[war]` config section turns each part on or off.
- Warzones have no natural mob spawns at all (natural spawns, patrols, chunk generation; spawners still work), controlled by `[warzone] blockNaturalSpawns`.
- Safezones stop hostile mobs from spawning inside them (natural spawns, spawners, patrols and chunk generation). Spawn eggs, commands, dispensers, breeding and spell summons are never touched. `blockAllMobSpawns` stops every mob. Inside a Jake's World Guard zone, World Guard's own flags decide.
- Operators (and creative mode, if `adminRequiresCreative` is on) get two more claim types in the table: Safezone and Warzone. Nobody else can claim land inside either.
- A Safezone is always lime green on every map, and no faction, personal claim or warzone can use that green (the server refuses or shifts any colour in the lime range). It is protected by Holdfast Factions through its permission switches, except inside a zone you create in it with Jake's World Guard (`jakesworldguard`). Inside a World Guard zone Holdfast stops enforcing anything, so World Guard's flags, members and priorities decide there. Outside those zones the safezone keeps following its switches. Without World Guard installed the whole safezone follows its switches.
- A Warzone is an area that nobody can claim. It is always a dark red on every map, and nothing else can use that shade: the colour picker offers a brighter red for factions, and the server refuses or shifts any colour too close to the warzone red. A warzone has no Permissions entry and its rules cannot be edited: blocks cannot be broken or placed, explosions are off, doors, chests and entities can be used, and trusted players get no exceptions. PvP is always on, except between members of the same faction. Allies can fight each other there. World Guard is not involved. Warzone PvP needs `pvp=true` in `server.properties`.

## What is in this repository

| Directory | What it is |
|---|---|
| [`territory/`](territory) | The Gradle project. It holds the Territory Table, upkeep, the map screen and the glue to the faction core, and it builds the single released jar. |
| [`core/`](core) | Sources of the faction core classes this project changes, in `core/patches`. |

The faction core (factions, alliances, claims, objectives) comes from a released jar whose original source is not in this repository. The build renames it with `territory/tools/RenameCore.java`, replaces the classes in `core/patches`, and adds the Territory code. The Territory Table is registered under the mod id, as `holdfast_factions:territory_table`. The Territory config file (`territory-server.toml`) and its personal-names save file keep their earlier names so their values carry over.

### Changes to the core

- `ClaimCommands`: the `/claim` commands that claim or unclaim land are removed. `/claim whereAmI` and `/claim resetUnclaimedChunks` stay.
- `ClaimEventHandler`: the PvP kill-steals-land handler is removed. Safezones are no longer enforced at a position inside a Jake's World Guard zone, by asking `FactionsBridge.handsOff` first.
- `ChunkClaimSelectionManager` only served those commands, so it is dropped from the released jar.

The patched sources are decompiled from the released jar, so they carry no comments.

### Personal claim protection

Two behaviours of the core's claim handler are worth flagging to anyone reading this code.

`ClaimEventHandler.playerHasPermission` decides a CORE (personal) claim by checking whether the chunk belongs to the claim's owner, which is true for any claimed chunk, instead of comparing the owner against the player performing the action. The effect is that personal claims permit every player to do everything in them. Faction and admin claims are not affected. This project compensates in `ClaimProtectionHandler`, which runs after the core's handler and refuses interactions by anyone who is not the claim's owner. Which interactions are protected at all is still decided by the `coreClaimRestrictions` config, and operators still bypass it.

`ClaimManager.deleteFactionData` removes a disbanded faction from its index maps but leaves that faction's entries in the claim map. Since no player's faction name can match a faction that no longer exists, those chunks stay protected against everyone with no way to release them. This project releases them explicitly when a faction is disbanded.

## Licensing

Files keep the license they came with. The faction core and the patched core classes are Mozilla Public License 2.0, see [`LICENSE`](LICENSE). The Territory code is GNU General Public License v3.0 only, see [`territory/LICENSE`](territory/LICENSE). MPL-2.0 allows its code to be combined with GPL-3.0 code in one distribution.

## Building

The project targets NeoForge 1.21.1 on Java 21 and builds with Gradle 8.8.

```
cd territory
gradle build
```

The build needs the released core jar at `territory/libs/core-released.jar`. That directory is not committed.

It produces one file, `territory/build/libs/holdfast_factions-neoforge-<version>.jar`. That is the only jar to install. Remove any older jars of this mod from the `mods` folder first, or NeoForge will report the same mod twice.

## Updating from an earlier release

Only claims start over, on purpose, so that no existing base is touched by upkeep or by the new claim rules.

- Factions and alliances carry over unchanged: members, ranks, relations, colours and abbreviations. So do personal territory names and colours, and the King-of-the-Hill objective data.
- Claims start empty, including admin territories. Factions claim their land again from the Territory Table, and admins draw their territories again. The earlier claims file stays in the world's `data` folder untouched and is simply never read.
- Every placed Territory Table is reset. The table now has the id `holdfast_factions:territory_table`, so tables from earlier releases no longer exist: the placed ones disappear from the world, and any held in inventories or chests vanish too. Everything else players built stays as it is. Each faction crafts a new table, and the first one a member places or deposits into becomes that faction's core.
- One internal save file, the objectives one, keeps its earlier spelling so that its data carries over. Players never see it.
- Config files are named after the mod id, so the old ones are ignored and the new ones start at their defaults. To keep tuned settings, copy the values across by hand into the new `holdfast_factions-*.toml` files. `territory-server.toml` keeps its name and its values.
- The keybind for the faction menu resets once.
- Maps and Quills (modId `warpaint`) still requires the old Easy Factions mod id and reads its classes by their old package, so it cannot run next to Holdfast Factions until its author updates it.
