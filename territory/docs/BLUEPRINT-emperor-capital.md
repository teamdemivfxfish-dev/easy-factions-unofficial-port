# BLUEPRINT — the Emperor and the capital

**Status: DESIGN ONLY. Nothing in this file is implemented, and nothing in the codebase depends on it.**
It is written down now so that whoever picks this up later builds the thing that was actually wanted, rather
than re-deriving it from a one-line request. Do not treat any name here as an existing symbol.

## The scene it has to produce

There is an admin territory called **King's Landing**: the player hub, the capital of the server. It is
built and owned by the server, not by a faction.

One player at a time holds the title of **Emperor**. While they hold it, they may treat the whole capital as
theirs: build it, tear it down, redesign it as they see fit. They are not an operator and they hold no claim
of their own over it. When the title passes to someone else, the old Emperor's power over the capital ends
that instant and the new one's begins, with no chunk of land changing hands.

The one exception is the **child plots** inside the capital. Those are the shops, embassies and player plots
handed out to individuals. The Emperor rules the city; he does not get to bulldoze the plots inside it.

## Why the existing pieces already almost do this

Everything except "who is the Emperor" is built and shipped:

- `AdminTerritories.Territory` already carries a **member list**, and `AdminTerritories.isTrusted` already
  answers "may this player act on this chunk". Membership already flows downhill from a parent to its
  children.
- `EasyFactionsBridge.adminDecision` already asks that question before it looks at any permission switch,
  so a trusted player is exempt from a territory's rules today.
- Child plots already exist, already sit inside their parent by construction, and already answer for their
  own permissions before the parent does.

So an Emperor is, mechanically, **a member of the parent territory who is NOT a member of any child**, plus
a rule that says his membership of the parent must not flow down into the children.

That last part is the only behaviour that does not exist yet. Today parent membership deliberately DOES flow
downhill, because a moderator over spawn should be able to work inside a plot in spawn. The Emperor is the
opposite case, so the flag has to be per-member, not per-territory.

## The shape to build

### 1. Membership gains a role

Replace the bare `Set<UUID> members` on `Territory` with a small map of UUID to role:

- `TRUSTED` — today's behaviour. Exempt here, and exempt in every child (a moderator).
- `RULER` — exempt in this territory ONLY. Explicitly not exempt in any child of it (the Emperor).

`isTrusted` then takes the chunk into account: a `RULER` of the parent is refused on a chunk that belongs to
a child, while a `TRUSTED` member is not. Persist the role beside the UUID in `AdminTerritories.save`, and
read a missing role as `TRUSTED` so existing worlds keep behaving exactly as they do now.

The Permissions tab needs one more control per member row for this — a role toggle, in the same fixed slot
pattern the switches already use.

### 2. The title is not stored here

Do NOT add an "emperor" field to this mod. The title belongs to War 'n Nobility, which already owns titles,
succession, and every rule about how a title is won and lost. This mod must not grow a second, competing
idea of who rules.

Instead: **War 'n Nobility tells MineTerritory who holds the title, and MineTerritory keeps exactly one
`RULER` member in sync with it.**

### 3. The seam to War 'n Nobility

The bridge should be one small, optional, reflection-based class in `integration/`, mirroring the way
`SdmBridge` treats SDM Economy: no hard dependency, no compile-time link, and a `ModList.isLoaded` guard, so
a server without War 'n Nobility keeps working and a server with it gets the feature for free.

What it needs from War 'n Nobility:

- a way to ask **who currently holds a given title** (UUID or null), and
- an **event or callback when that changes**, so the swap is immediate rather than polled.

What it does with that:

- config maps a title to a territory, e.g. `emperorTerritory = "King's Landing"`, so the capital's name is
  not hardcoded and a server can point the title at a different city;
- on a title change: remove the old holder's `RULER` entry from that territory, add the new holder's;
- on server start and on the territory being renamed or deleted, re-sync once so a restart cannot leave a
  deposed Emperor holding the keys.

Keep the direction of the dependency this way round. MineTerritory reads the title and reacts; it never
tells War 'n Nobility who the Emperor is.

### 4. What must stay true

- The capital stays an **admin claim**. It is never converted to a faction claim, and the Emperor never
  "owns" it in Easy Factions' data. Losing the title has to cost nothing and move nothing.
- **Plots stay invisible outside the Territory Table**, as they are now. The map, the atlas, Here Be Doodles
  and the War Frame all keep showing one territory called King's Landing.
- An **operator is still above all of this**. Ops bypass every check before any of it is consulted.
- The Emperor must not be able to grant himself plots. Editing membership stays operator-only, which it
  already is: every path into `setAdminMember` re-checks `canAdminClaim`.

## Open questions to settle before writing code

1. Should the Emperor be able to edit the capital's **permission switches** (deciding what ordinary players
   may do in the city), or only to build in it himself? The design above gives him building rights only.
2. Should a plot's own member list be visible to the Emperor in the Permissions tab, or is the tab still
   operator-only? Operator-only is assumed above.
3. What happens to the capital during an **interregnum**, when nobody holds the title? Assumed: no `RULER`
   entry exists, so the territory's ordinary permission switches apply to everyone, which is the safe
   default.

## Related

- `docs/` has no other design notes yet; the implemented behaviour is described in `PROJECT_STATUS.md`.
- The child-plot model and the reasons behind it are documented at the top of
  `world/AdminTerritories.java`. Read that first.
