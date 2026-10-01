# Core patches

The faction core (factions, alliances, claims, objectives) is taken from a released jar and renamed by `territory/tools/RenameCore.java` during the build. Its original source is not in this repository.

The core classes that this project changes are kept as source under `patches/`, in the renamed package `dev.xmat5.holdfast`. The build compiles them and uses them in place of the renamed originals:

- `ClaimCommands`: the `/claim` commands that claim or unclaim land are removed. `/claim whereAmI` and `/claim resetUnclaimedChunks` stay.
- `ClaimEventHandler`: the PvP kill-steals-land handler is removed. Safezones are no longer enforced at a position inside a Jake's World Guard zone, by asking `FactionsBridge.handsOff` first.
- `ServerConfig`, `ObjectiveConfig` and `ClientConfig`: their config handlers read values while NeoForge unloads the config, which threw an error every time a world was left or a server stopped. They now return early on the unload event.
- `ChunkClaimSelectionManager` only served those commands, so it is dropped from the final jar.

These sources were decompiled from the released jar, so they carry no comments. The core is licensed under the Mozilla Public License 2.0, see the `LICENSE` file in the repository root.
