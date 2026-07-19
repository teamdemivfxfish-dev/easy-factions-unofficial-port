package top.leonx.territory.integration;

import com.jpreiss.easy_factions.server.ServerConfig;
import com.jpreiss.easy_factions.server.alliance.Alliance;
import com.jpreiss.easy_factions.server.alliance.AllianceStateManager;
import com.jpreiss.easy_factions.server.claims.ClaimManager;
import com.jpreiss.easy_factions.server.claims.model.ClaimData;
import com.jpreiss.easy_factions.server.claims.model.ClaimType;
import com.jpreiss.easy_factions.server.faction.Faction;
import com.jpreiss.easy_factions.server.faction.FactionStateManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.world.AdminTerritories;
import top.leonx.territory.world.ClaimPenalties;
import top.leonx.territory.world.PurchasedClaims;
import top.leonx.territory.world.TerritoryNames;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "brain" swap: every claim/faction action the Territory Table GUI performs is routed through Easy
 * Factions here. ALL Easy Factions types stay inside this class and run only after {@link #loaded()}, so
 * the rest of the mod never hard-links EF and the GUI degrades gracefully if EF is somehow absent.
 *
 * Claim model mapping (verified against the EF jar):
 *  - Personal = EF ClaimType.CORE, owner key = player UUID string, colour = ServerConfig.coreClaimColor,
 *    cap = ServerConfig.coreChunkAmount, count = ClaimManager.getCoreChunkCount(uuid).
 *  - Faction  = EF ClaimType.FACTION, owner key = faction name, colour = faction colour,
 *    cap = factionBaseClaimLimit + factionAdditionalClaimLimitPerMember * members,
 *    count = ClaimManager.getFactionClaimCount(name).
 */
public final class EasyFactionsBridge {

    private EasyFactionsBridge() {}

    public static final String MODID = "easy_factions";

    // kind codes for a region claim entry sent to the GUI
    public static final int KIND_MINE_CORE = 0;
    public static final int KIND_MINE_FACTION = 1;
    public static final int KIND_OTHER = 2;
    public static final int KIND_ADMIN = 3;

    // claim types the GUI can commit
    public static final int TYPE_PERSONAL = 0;
    public static final int TYPE_FACTION = 1;
    public static final int TYPE_ADMIN = 2;

    /**
     * Fallback colour for admin claims painted before per-territory colours existed, and the swatch the GUI
     * starts on. Admins now choose a colour per territory; the chosen value is stored in
     * {@link AdminTerritories} because Easy Factions overwrites admin claim colours on world load.
     */
    public static final int ADMIN_COLOR = 0xC056C0;

    public static boolean loaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * Admin claiming is gated to operators (permission level 2), server-verified and never trusted from the
     * client. Creative mode is additionally required only when {@code territory.adminRequiresCreative} is on.
     *
     * That extra creative requirement used to be unconditional, and it fails silently: the Admin entry simply
     * never appears in the GUI's type cycle, with nothing telling an op in survival why. It is now opt-in.
     */
    public static boolean canAdminClaim(ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) return false;
        return !TerritoryConfig.adminRequiresCreative() || player.isCreative();
    }

    /** Everything the GUI needs to know about the player's claim standing, in plain JDK types. */
    public record Ctx(boolean efLoaded, boolean inFaction, boolean canFactionClaim, boolean canAdminClaim,
                      String factionName, int factionColor,
                      int coreCap, int coreUsed, int factionCap, int factionUsed) {
        public static Ctx empty() {
            return new Ctx(false, false, false, false, "", 0xFFFFFF, 0, 0, 0, 0);
        }
    }

    public static Ctx gatherContext(ServerPlayer player) {
        if (!loaded() || player == null) return Ctx.empty();
        MinecraftServer server = player.getServer();
        if (server == null) return Ctx.empty();
        UUID uuid = player.getUUID();

        FactionStateManager fsm = FactionStateManager.get(server);
        ClaimManager cm = ClaimManager.get(server);

        Faction f = fsm.getFactionByPlayer(uuid);
        boolean inFaction = f != null;
        boolean canFactionClaim = inFaction && fsm.playerIsOwnerOrOfficer(uuid);
        String factionName = inFaction ? f.getName() : "";
        int factionColor = inFaction ? (f.getColor() & 0xFFFFFF) : 0xFFFFFF;

        int coreCap = ServerConfig.coreChunkAmount;
        int coreUsed = cm.getCoreChunkCount(uuid);

        int factionCap = inFaction ? factionCapFor(server, f) : 0;
        int factionUsed = inFaction ? cm.getFactionClaimCount(factionName) : 0;

        return new Ctx(true, inFaction, canFactionClaim, canAdminClaim(player), factionName, factionColor,
                coreCap, coreUsed, factionCap, factionUsed);
    }

    /**
     * A faction's cap BEFORE war losses: the Easy Factions cap (base + perMember * members) plus any extra
     * slots bought via the Buy Claims button or won by killing rivals (both in {@link PurchasedClaims}).
     * This is the ceiling a war penalty is measured against, so a faction can be ground down to zero
     * capacity but never past it.
     */
    public static int naturalCapFor(MinecraftServer server, Faction f) {
        if (f == null) return 0;
        int members = memberCount(f);
        int base = ServerConfig.factionBaseClaimLimit + ServerConfig.factionAdditionalClaimLimitPerMember * members;
        return base + PurchasedClaims.get(server).getBonus(f.getName());
    }

    /** A faction's effective claim cap: its natural cap minus capacity lost to enemy kills. */
    public static int factionCapFor(MinecraftServer server, Faction f) {
        if (f == null) return 0;
        return Math.max(0, naturalCapFor(server, f) - ClaimPenalties.get(server).getPenalty(f.getName()));
    }

    /**
     * Whether {@code f} has enough members to claim land at all. Deliberately checked only when ADDING
     * claims: a faction that falls below the threshold keeps what it holds and can still unclaim, so land
     * can never be stranded by a member leaving.
     */
    public static boolean meetsMemberRequirement(Faction f) {
        return f != null && memberCount(f) >= TerritoryConfig.minFactionMembers();
    }

    // ---- conquest: killing a rival costs them land ----------------------------------------------------

    /** Outcome of one kill. {@code applied} is false when the kill did not qualify (same faction, allied,
     *  either side factionless), in which case every other field is meaningless. */
    public record ConquestResult(boolean applied, String victimFaction, String killerFaction,
                                 int slotsLost, int slotsGained, int chunksTaken) {
        public static final ConquestResult NONE = new ConquestResult(false, "", "", 0, 0, 0);
    }

    /**
     * Apply the tug-of-war for one PvP kill: the victim's faction loses claim capacity, the killer's faction
     * is paid a share into its collective pool, and if the victim is now holding more land than its reduced
     * ceiling allows, the excess comes off the map STARTING NEAREST THE DEATH SITE.
     *
     * That last rule is the whole point: to push a faction off a piece of land you have to go and win fights
     * on that land, not grind kills anywhere on the server. Chunks are only ever taken in the dimension the
     * victim died in, so a faction's holdings elsewhere are safe from a fight they were not part of.
     */
    public static ConquestResult applyKill(ServerPlayer victim, ServerPlayer killer) {
        if (!loaded() || victim == null || killer == null) return ConquestResult.NONE;
        MinecraftServer server = victim.getServer();
        if (server == null) return ConquestResult.NONE;

        FactionStateManager fsm = FactionStateManager.get(server);
        Faction victimFaction = fsm.getFactionByPlayer(victim.getUUID());
        Faction killerFaction = fsm.getFactionByPlayer(killer.getUUID());
        if (victimFaction == null || killerFaction == null) return ConquestResult.NONE;

        String victimName = victimFaction.getName();
        String killerName = killerFaction.getName();
        if (victimName.equals(killerName)) return ConquestResult.NONE;         // no friendly fire farming

        Alliance alliance = AllianceStateManager.get(server).getAllianceByFaction(killerName);
        if (alliance != null && alliance.getMembers().contains(victimName)) return ConquestResult.NONE;

        // 1. shrink the victim's ceiling, clamped so it can reach zero but never go negative
        ClaimPenalties penalties = ClaimPenalties.get(server);
        int lost = penalties.addPenalty(victimName, TerritoryConfig.claimsLostPerKill(),
                naturalCapFor(server, victimFaction));

        // 2. pay the killer's FACTION (not the player) a share of what was actually taken
        int gained = TerritoryConfig.killerShareOf(lost);
        if (gained > 0) PurchasedClaims.get(server).addBonus(killerName, gained);

        // 3. only once the ceiling drops below what they actually hold does land come off the map
        int taken = takeExcessLand(server, victim, victimFaction);
        return new ConquestResult(true, victimName, killerName, lost, gained, taken);
    }

    /** Unclaim the victim faction's chunks nearest {@code victim}'s death site until it is back under cap. */
    private static int takeExcessLand(MinecraftServer server, ServerPlayer victim, Faction victimFaction) {
        ClaimManager cm = ClaimManager.get(server);
        String victimName = victimFaction.getName();
        int excess = cm.getFactionClaimCount(victimName) - factionCapFor(server, victimFaction);
        if (excess <= 0) return 0;

        ResourceKey<Level> dimKey = victim.level().dimension();
        Map<ResourceKey<Level>, Set<Long>> byDim = cm.getFactionChunks(victimName);
        Set<Long> here = byDim != null ? byDim.get(dimKey) : null;
        // Nothing of theirs in this dimension: the ceiling still dropped, but their land elsewhere is safe.
        if (here == null || here.isEmpty()) return 0;

        ChunkPos death = victim.chunkPosition();
        List<Long> nearestFirst = new ArrayList<>(here);
        nearestFirst.sort((a, b) -> Long.compare(distSq(a, death), distSq(b, death)));

        List<Long> doomed = new ArrayList<>(nearestFirst.subList(0, Math.min(excess, nearestFirst.size())));
        HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
        rm.put(dimKey.location(), doomed);
        cm.unclaimChunks(rm, server);      // self-syncing: pushes the unclaim to every tracking client
        return doomed.size();
    }

    /** Squared chunk distance from a packed chunk long to {@code from}, in chunks (never overflows). */
    private static long distSq(long packed, ChunkPos from) {
        ChunkPos p = new ChunkPos(packed);
        long dx = (long) p.x - from.x;
        long dz = (long) p.z - from.z;
        return dx * dx + dz * dz;
    }

    /** Advance penalty regeneration by one tick. Returns true when a slot was actually refunded. */
    public static boolean regenPenalties(MinecraftServer server, int intervalTicks) {
        if (!loaded() || server == null) return false;
        return ClaimPenalties.get(server).regenAll(intervalTicks);
    }

    /** Easy Factions' own kill-steals-land strength, so we can warn when it is left on and double-dips. */
    public static int efPointsPerKill() {
        return loaded() ? ServerConfig.pointsPerKill : 0;
    }

    private static int memberCount(Faction f) {
        // must match the roster union in factionInfo() so the cap basis == the displayed member count
        Set<UUID> all = new HashSet<>();
        if (f.getOwner() != null) all.add(f.getOwner());
        if (f.getOfficers() != null) all.addAll(f.getOfficers());
        if (f.getMembers() != null) all.addAll(f.getMembers());
        return all.size();
    }

    /** One claimed chunk for the GUI: coords + KIND + the claim's EF colour + the label to draw on it. */
    public record ClaimCell(int x, int z, int kind, int color, String label) {}

    /** EF's default colour for personal (CORE) claims, used when a player hasn't picked their own. */
    public static int defaultCoreColor() {
        return ServerConfig.coreClaimColor & 0xFFFFFF;
    }

    /**
     * Claims in a square region around {@code center}, classified for the GUI as
     * {@link #KIND_MINE_CORE} / {@link #KIND_MINE_FACTION} / {@link #KIND_OTHER}, with each claim's colour
     * and the label to draw on it: a personal territory's NAME if its owner set one, else the owner's name
     * (faction claims use the faction name; admin claims read "Admin"). Unclaimed chunks are omitted.
     */
    public static List<ClaimCell> regionClaims(ServerPlayer player, ResourceKey<Level> dim, ChunkPos center, int radius) {
        List<ClaimCell> out = new ArrayList<>();
        if (!loaded() || player == null) return out;
        MinecraftServer server = player.getServer();
        if (server == null) return out;

        ClaimManager cm = ClaimManager.get(server);
        FactionStateManager fsm = FactionStateManager.get(server);
        TerritoryNames names = TerritoryNames.get(server);
        AdminTerritories adminZones = AdminTerritories.get(server);
        Faction f = fsm.getFactionByPlayer(player.getUUID());
        String myFaction = f != null ? f.getName() : null;
        String myUuid = player.getUUID().toString();

        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (!cm.isClaimed(dim, pos)) continue;
                ClaimData data = cm.getClaim(dim, pos);
                if (data == null) continue;
                int kind = KIND_OTHER;
                if (data.type == ClaimType.ADMIN) kind = KIND_ADMIN;
                else if (data.type == ClaimType.CORE && myUuid.equals(data.owner)) kind = KIND_MINE_CORE;
                else if (data.type == ClaimType.FACTION && myFaction != null && myFaction.equals(data.owner)) kind = KIND_MINE_FACTION;

                int color = data.color & 0xFFFFFF;
                String label;
                if (data.type == ClaimType.ADMIN) {
                    // OUR record wins: EF force-resets every admin claim's colour to ServerConfig.adminClaimColor
                    // on world load, so data.color is unreliable for admin claims after a restart.
                    AdminTerritories.Zone zone = adminZones.getZone(dim, pos.toLong());
                    label = zone != null && !zone.name().isEmpty() ? zone.name() : "Admin";
                    if (zone != null) color = zone.color();
                } else {
                    label = claimLabel(server, names, data);
                }
                out.add(new ClaimCell(x, z, kind, color, label));
            }
        }
        return out;
    }

    private static String claimLabel(MinecraftServer server, TerritoryNames names, ClaimData data) {
        if (data.type == ClaimType.FACTION) return data.owner;     // faction name is its label
        if (data.type == ClaimType.CORE) {
            try {
                UUID id = UUID.fromString(data.owner);
                String tname = names.getName(id);
                return !tname.isEmpty() ? tname : nameOf(server, id);
            } catch (IllegalArgumentException e) {
                return data.owner;
            }
        }
        return "Admin";
    }

    /**
     * Apply the GUI's claim diff through Easy Factions. {@code claimType} is TYPE_PERSONAL / TYPE_FACTION /
     * TYPE_ADMIN; {@code coreColor} is the player's chosen personal colour (negative = EF default). Enforces,
     * on the SERVER, that the player only touches chunks they may; personal/faction claims must stay
     * CONNECTED and respect caps, while ADMIN claims (op+creative only) are unrestricted. Returns a short
     * status string (empty = success).
     */
    public static String commit(ServerPlayer player, int claimType, List<ChunkPos> add, List<ChunkPos> remove,
                                int coreColor, String adminName, int adminColor) {
        if (!loaded()) return "Easy Factions is not installed.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";

        ClaimManager cm = ClaimManager.get(server);
        FactionStateManager fsm = FactionStateManager.get(server);
        UUID uuid = player.getUUID();
        ResourceKey<Level> dimKey = player.level().dimension();
        ResourceLocation dim = dimKey.location();

        boolean admin = claimType == TYPE_ADMIN;
        boolean faction = claimType == TYPE_FACTION;
        ClaimType type = admin ? ClaimType.ADMIN : (faction ? ClaimType.FACTION : ClaimType.CORE);

        Faction f = null;
        String myOwnerKey;
        if (admin) {
            if (!canAdminClaim(player)) return "Admin claiming requires operator + creative mode.";
            myOwnerKey = "Admin";
        } else if (faction) {
            f = fsm.getFactionByPlayer(uuid);
            if (f == null) return "You are not in a faction.";
            if (!fsm.playerIsOwnerOrOfficer(uuid)) return "Only the faction owner or an officer can claim for the faction.";
            myOwnerKey = f.getName();
        } else {
            myOwnerKey = uuid.toString();
        }

        // NEVER trust the client: keep only removes of this type the player controls, and adds that are free.
        Set<Long> removeOk = new HashSet<>();
        for (ChunkPos cp : remove) {
            ClaimData d = cm.getClaim(dimKey, cp);
            if (d != null && d.type == type && (admin || myOwnerKey.equals(d.owner))) removeOk.add(cp.toLong());
        }
        List<ChunkPos> safeAdd = new ArrayList<>();
        Set<Long> addSet = new HashSet<>();
        for (ChunkPos cp : add) {
            if (!cm.isClaimed(dimKey, cp) && addSet.add(cp.toLong())) safeAdd.add(cp);
        }

        // contiguity + caps apply to personal/faction; admin claims are unrestricted
        if (!admin) {
            Set<Long> ownerNow = new HashSet<>(ownerChunks(cm, dimKey, faction, myOwnerKey, uuid));
            Set<Long> finalSet = new HashSet<>(ownerNow);
            finalSet.removeAll(removeOk);
            finalSet.addAll(addSet);
            if (componentCount(finalSet) > Math.max(1, componentCount(ownerNow))) {
                return "Claims must be connected to each other.";
            }
            if (!safeAdd.isEmpty()) {
                if (faction) {
                    // hard member gate, checked only on ADD so a shrinking faction is never stranded
                    if (!meetsMemberRequirement(f)) {
                        return "Your faction needs at least " + TerritoryConfig.minFactionMembers()
                                + " members to claim land.";
                    }
                    int cap = factionCapFor(server, f);   // bought bonus slots, minus capacity lost in war
                    if (cm.getFactionClaimCount(f.getName()) - removeOk.size() + safeAdd.size() > cap) return "Faction claim limit reached.";
                } else {
                    if (cm.getCoreChunkCount(uuid) - removeOk.size() + safeAdd.size() > ServerConfig.coreChunkAmount) return "Personal claim limit reached.";
                }
            }
        }

        if (!removeOk.isEmpty()) {
            HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
            rm.put(dim, new ArrayList<>(removeOk));
            cm.unclaimChunks(rm, server);
            if (admin) {
                // drop the name/colour with the claim, or a later admin claim here inherits a stale label
                AdminTerritories zones = AdminTerritories.get(server);
                for (long chunk : removeOk) zones.clearZone(dimKey, chunk);
            }
        }
        if (!safeAdd.isEmpty()) {
            HashMap<ResourceLocation, List<ChunkPos>> ad = new HashMap<>();
            ad.put(dim, safeAdd);
            if (admin) {
                int rgb = adminColor < 0 ? TerritoryConfig.adminDefaultColor() : (adminColor & 0xFFFFFF);
                cm.claimChunks(ad, ClaimType.ADMIN, "Admin", rgb, server);
                // EF will forget this colour on the next world load, so keep our own authoritative copy
                AdminTerritories zones = AdminTerritories.get(server);
                for (ChunkPos cp : safeAdd) zones.setZone(dimKey, cp.toLong(), adminName, rgb);
            } else if (faction) {
                cm.claimChunks(ad, ClaimType.FACTION, f.getName(), f.getColor(), server);
            } else {
                int color = coreColor < 0 ? ServerConfig.coreClaimColor : (coreColor & 0xFFFFFF);
                cm.claimChunks(ad, ClaimType.CORE, uuid.toString(), color, server);
            }
        }
        return "";
    }

    /** Re-claim ALL of the player's personal (CORE) chunks in their current dim with {@code rgb}. */
    public static void recolorPersonal(ServerPlayer player, int rgb) {
        if (!loaded()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ClaimManager cm = ClaimManager.get(server);
        ResourceKey<Level> dimKey = player.level().dimension();
        ResourceLocation dim = dimKey.location();
        Set<Long> mine = cm.getPlayerCoreChunks(player.getUUID()).getOrDefault(dimKey, Set.of());
        if (mine.isEmpty()) return;
        List<ChunkPos> chunks = new ArrayList<>();
        for (long l : mine) chunks.add(new ChunkPos(l));
        HashMap<ResourceLocation, List<ChunkPos>> map = new HashMap<>();
        map.put(dim, chunks);
        cm.claimChunks(map, ClaimType.CORE, player.getUUID().toString(), rgb & 0xFFFFFF, server);
    }

    /** Recolour the player's faction (and all its claims) — owner/officer only. */
    public static String recolorFaction(ServerPlayer player, int rgb) {
        if (!loaded()) return "Easy Factions is not installed.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";
        FactionStateManager fsm = FactionStateManager.get(server);
        ClaimManager cm = ClaimManager.get(server);
        UUID uuid = player.getUUID();
        Faction f = fsm.getFactionByPlayer(uuid);
        if (f == null) return "You are not in a faction.";
        if (!fsm.playerIsOwnerOrOfficer(uuid)) return "Only the faction owner or an officer can set the colour.";
        f.setColor(rgb & 0xFFFFFF);
        fsm.setDirty();
        cm.changeFactionColor(f.getName(), rgb & 0xFFFFFF, server);   // recolours all faction claims
        return "";
    }

    private static Set<Long> ownerChunks(ClaimManager cm, ResourceKey<Level> dimKey, boolean faction,
                                         String factionName, UUID uuid) {
        Map<ResourceKey<Level>, Set<Long>> map = faction
                ? cm.getFactionChunks(factionName) : cm.getPlayerCoreChunks(uuid);
        Set<Long> set = map != null ? map.get(dimKey) : null;
        return set != null ? set : Set.of();
    }

    /** Number of orthogonally-connected components in a set of packed chunk longs (0 if empty). */
    private static int componentCount(Set<Long> set) {
        if (set.isEmpty()) return 0;
        Set<Long> seen = new HashSet<>();
        int comps = 0;
        for (long start : set) {
            if (!seen.add(start)) continue;
            comps++;
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(start);
            while (!q.isEmpty()) {
                long c = q.poll();
                for (long n : neighbours(c)) {
                    if (set.contains(n) && seen.add(n)) q.add(n);
                }
            }
        }
        return comps;
    }

    private static long[] neighbours(long packed) {
        int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed);
        return new long[]{
                ChunkPos.asLong(x + 1, z), ChunkPos.asLong(x - 1, z),
                ChunkPos.asLong(x, z + 1), ChunkPos.asLong(x, z - 1)
        };
    }

    // ---- faction tab ---------------------------------------------------------------------------

    /** One faction member for the GUI roster: resolved name + role (0 member, 1 officer, 2 owner). */
    public record Member(String name, int role) {}

    /** A relationship this faction has set toward another faction. */
    public record Relation(String faction, String status) {}

    /** Everything the Faction tab shows, in plain JDK types. {@code invitesForViewer} = factions that
     *  invited the viewer when NOT in a faction, OR the players this faction has invited when in one. */
    public record FactionInfo(boolean efLoaded, boolean inFaction, String name, int color, String abbreviation,
                              boolean isOwner, boolean isOfficer, boolean friendlyFire, String ownerName,
                              List<Member> members, List<String> invitesForViewer, List<Relation> relations,
                              int factionCap, int factionUsed, int bonusClaims) {
        public static FactionInfo empty() {
            return new FactionInfo(false, false, "", 0xFFFFFF, "", false, false, false, "",
                    List.of(), List.of(), List.of(), 0, 0, 0);
        }
    }

    public static FactionInfo factionInfo(ServerPlayer player) {
        if (!loaded() || player == null) return FactionInfo.empty();
        MinecraftServer server = player.getServer();
        if (server == null) return FactionInfo.empty();
        UUID uuid = player.getUUID();

        FactionStateManager fsm = FactionStateManager.get(server);
        Faction f = fsm.getFactionByPlayer(uuid);
        if (f == null) {
            List<String> invites = fsm.getInvitesForPlayer(uuid);
            return new FactionInfo(true, false, "", 0xFFFFFF, "", false, false, false, "",
                    List.of(), invites != null ? invites : List.of(), List.of(), 0, 0, 0);
        }

        Set<UUID> officers = f.getOfficers() != null ? f.getOfficers() : Set.of();
        boolean isOwner = uuid.equals(f.getOwner());
        boolean isOfficer = officers.contains(uuid);

        LinkedHashSet<UUID> all = new LinkedHashSet<>();
        if (f.getOwner() != null) all.add(f.getOwner());
        all.addAll(officers);
        if (f.getMembers() != null) all.addAll(f.getMembers());

        List<Member> members = new ArrayList<>();
        for (UUID id : all) {
            int role = id.equals(f.getOwner()) ? 2 : (officers.contains(id) ? 1 : 0);
            members.add(new Member(nameOf(server, id), role));
        }

        // players this faction has invited (for the Invites sub-tab's Revoke list)
        List<String> invited = new ArrayList<>();
        if (f.getInvited() != null) for (UUID id : f.getInvited()) invited.add(nameOf(server, id));

        // relationships this faction has set toward others (for the Relations sub-tab)
        List<Relation> relations = new ArrayList<>();
        if (f.getOutgoingRelations() != null) {
            for (var en : f.getOutgoingRelations().entrySet()) {
                relations.add(new Relation(en.getKey(), en.getValue().name()));
            }
        }

        int cap = factionCapFor(server, f);
        int used = ClaimManager.get(server).getFactionClaimCount(f.getName());
        int bonus = PurchasedClaims.get(server).getBonus(f.getName());
        return new FactionInfo(true, true, f.getName(), f.getColor() & 0xFFFFFF,
                f.getAbbreviation() != null ? f.getAbbreviation() : "",
                isOwner, isOfficer, f.getFriendlyFire(), nameOf(server, f.getOwner()), members, invited, relations,
                cap, used, bonus);
    }

    /**
     * The "Buy Claims" button: the faction OWNER pays to permanently raise the faction's claim cap by
     * {@code claimsPerPurchase}. Default currency is SDM Economy ({@code costSdm}); if SDM is absent OR the
     * buyer can't cover it, it falls back to emeralds at the configured exchange rate ({@code costEmeralds}).
     * Server-authoritative: detects the player's funds and consumes them here, never trusting the client.
     * Returns a player-facing message (always non-empty) describing success or why it failed.
     */
    public static String buyClaims(ServerPlayer player) {
        if (!loaded()) return "Easy Factions is not installed.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";
        if (!TerritoryConfig.buyEnabled()) return "Buying claims is disabled on this server.";

        FactionStateManager fsm = FactionStateManager.get(server);
        UUID uuid = player.getUUID();
        Faction f = fsm.getFactionByPlayer(uuid);
        if (f == null) return "You are not in a faction.";
        if (!fsm.playerOwnsFaction(uuid)) return "Only the faction owner can buy claims.";

        long costSdm = TerritoryConfig.costSdm();
        int costEmeralds = TerritoryConfig.costEmeralds();
        int amount = TerritoryConfig.claimsPerPurchase();

        // SDM first (the default). Only charge if they can actually cover the full price.
        String paidWith = null;
        if (SdmBridge.isLoaded()) {
            String key = SdmBridge.resolveKey(player, TerritoryConfig.sdmCurrencyKey());
            if (key != null && SdmBridge.balance(player, key) >= costSdm) {
                if (SdmBridge.withdraw(player, key, costSdm)) paidWith = costSdm + " " + key;
            }
        }
        // Fall back to emeralds if SDM was absent or the buyer didn't have enough SDM.
        if (paidWith == null) {
            if (countEmeralds(player) >= costEmeralds) {
                removeEmeralds(player, costEmeralds);
                paidWith = costEmeralds + " emeralds";
            }
        }
        if (paidWith == null) {
            // Quote the SDM price on SDM servers; only mention emeralds where SDM is not installed.
            String price = SdmBridge.isLoaded() ? costSdm + " SDM" : costEmeralds + " emeralds";
            return "You cannot afford this. It costs " + price + ".";
        }

        int newBonus = PurchasedClaims.get(server).addBonus(f.getName(), amount);
        int newCap = factionCapFor(server, f);
        return "Bought " + amount + " claims (paid " + paidWith + "). Faction cap is now " + newCap
                + " (+" + newBonus + " purchased).";
    }

    private static int countEmeralds(ServerPlayer player) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(Items.EMERALD)) n += st.getCount();
        }
        return n;
    }

    /** Remove exactly {@code count} emeralds from the player's inventory (caller has verified they have enough). */
    private static void removeEmeralds(ServerPlayer player, int count) {
        int remaining = count;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.is(Items.EMERALD)) continue;
            int take = Math.min(remaining, st.getCount());
            st.shrink(take);
            remaining -= take;
        }
        inv.setChanged();
    }

    /** Revoke a pending invitation by player name (owner/officer only). */
    public static String revokeInvite(ServerPlayer player, String targetName) {
        if (!loaded()) return "Easy Factions is not installed.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";
        FactionStateManager fsm = FactionStateManager.get(server);
        UUID uuid = player.getUUID();
        Faction f = fsm.getFactionByPlayer(uuid);
        if (f == null) return "You are not in a faction.";
        if (!fsm.playerIsOwnerOrOfficer(uuid)) return "Only the owner or an officer can revoke invites.";
        if (f.getInvited() != null) {
            for (UUID id : f.getInvited()) {
                if (nameOf(server, id).equalsIgnoreCase(targetName)) {
                    try {
                        fsm.revokeInvitation(player, id);
                    } catch (RuntimeException e) {
                        return e.getMessage() != null ? e.getMessage() : "Could not revoke invite.";
                    }
                    return "";
                }
            }
        }
        return "No pending invite for " + targetName + ".";
    }

    /** Disband the player's faction (Easy Factions has no disband command, so we gate + call the API). */
    public static String disband(ServerPlayer player) {
        if (!loaded()) return "Easy Factions is not installed.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";
        FactionStateManager fsm = FactionStateManager.get(server);
        if (!fsm.playerOwnsFaction(player.getUUID())) return "Only the faction owner can disband.";
        Faction f = fsm.getFactionByPlayer(player.getUUID());
        if (f == null) return "You are not in a faction.";
        String name = f.getName();
        fsm.disbandFaction(name, server);
        PurchasedClaims.get(server).clear(name);   // don't let bought slots haunt a future faction of the same name
        return "";
    }

    /** Display string for whoever owns the chunk the Territory Table sits in (for the floating BE text). */
    public static String chunkOwnerDisplay(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos) {
        if (!loaded() || server == null) return "";
        ClaimManager cm = ClaimManager.get(server);
        if (!cm.isClaimed(dim, pos)) return "";
        ClaimData d = cm.getClaim(dim, pos);
        if (d == null) return "";
        if (d.type == ClaimType.FACTION) return d.owner;
        if (d.type == ClaimType.CORE) {
            try {
                return nameOf(server, UUID.fromString(d.owner));
            } catch (IllegalArgumentException e) {
                return d.owner;
            }
        }
        return "Admin";
    }

    /**
     * A square grid of claim colours around {@code center} for the floating-map preview, row-major
     * (index = j*span + i, span = 2*radius+1). 0 = unclaimed; otherwise {@code 0xFF000000 | rgb}.
     */
    public static int[] claimColorGrid(MinecraftServer server, ResourceKey<Level> dim, ChunkPos center, int radius) {
        int span = 2 * radius + 1;
        int[] grid = new int[span * span];
        if (!loaded() || server == null) return grid;
        ClaimManager cm = ClaimManager.get(server);
        int idx = 0;
        for (int j = 0; j < span; j++) {
            for (int i = 0; i < span; i++) {
                ChunkPos cp = new ChunkPos(center.x - radius + i, center.z - radius + j);
                if (cm.isClaimed(dim, cp)) {
                    ClaimData d = cm.getClaim(dim, cp);
                    grid[idx] = d != null ? (0xFF000000 | (d.color & 0xFFFFFF)) : 0;
                }
                idx++;
            }
        }
        return grid;
    }

    /** A name label to stamp on the floating map: chunk offset from {@code center} + the owner/territory name. */
    public record MapLabel(int dx, int dz, String name) {}

    /** Cluster the claimed chunks around {@code center} by owner/territory name and return one centred label
     *  per contiguous group (capped) — for the floating BE map to mirror the in-GUI labels. */
    public static List<MapLabel> claimLabels(MinecraftServer server, ResourceKey<Level> dim, ChunkPos center, int radius) {
        List<MapLabel> out = new ArrayList<>();
        if (!loaded() || server == null) return out;
        ClaimManager cm = ClaimManager.get(server);
        TerritoryNames names = TerritoryNames.get(server);
        Map<Long, String> labelByChunk = new HashMap<>();
        for (int j = -radius; j <= radius; j++) {
            for (int i = -radius; i <= radius; i++) {
                ChunkPos cp = new ChunkPos(center.x + i, center.z + j);
                if (!cm.isClaimed(dim, cp)) continue;
                ClaimData d = cm.getClaim(dim, cp);
                if (d != null) labelByChunk.put(ChunkPos.asLong(cp.x, cp.z), claimLabel(server, names, d));
            }
        }
        Set<Long> seen = new HashSet<>();
        for (Map.Entry<Long, String> en : labelByChunk.entrySet()) {
            long sk = en.getKey();
            if (!seen.add(sk)) continue;
            String label = en.getValue();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(sk);
            double sumX = 0, sumZ = 0;
            int count = 0;
            while (!q.isEmpty()) {
                long c = q.poll();
                sumX += ChunkPos.getX(c);
                sumZ += ChunkPos.getZ(c);
                count++;
                for (long n : neighbours(c)) {
                    if (label.equals(labelByChunk.get(n)) && seen.add(n)) q.add(n);
                }
            }
            int dx = (int) Math.round(sumX / count) - center.x;
            int dz = (int) Math.round(sumZ / count) - center.z;
            out.add(new MapLabel(dx, dz, label));
            if (out.size() >= 16) break;
        }
        return out;
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        if (id == null) return "?";
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (server.getProfileCache() != null) {
            var gp = server.getProfileCache().get(id);
            if (gp.isPresent()) return gp.get().getName();
        }
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }
}
