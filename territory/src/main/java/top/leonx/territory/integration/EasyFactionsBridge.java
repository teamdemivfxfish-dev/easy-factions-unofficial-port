package top.leonx.territory.integration;

import com.jpreiss.easy_factions.server.ServerConfig;
import com.jpreiss.easy_factions.server.alliance.Alliance;
import com.jpreiss.easy_factions.server.alliance.AllianceStateManager;
import com.jpreiss.easy_factions.server.claims.ChunkInteractionType;
import com.jpreiss.easy_factions.server.claims.ClaimManager;
import com.jpreiss.easy_factions.server.claims.model.ClaimData;
import com.jpreiss.easy_factions.server.claims.model.ClaimType;
import com.jpreiss.easy_factions.server.faction.Faction;
import com.jpreiss.easy_factions.server.faction.FactionStateManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.world.AdminPerm;
import top.leonx.territory.world.AdminTerritories;
import top.leonx.territory.world.ClaimPenalties;
import top.leonx.territory.world.ClaimRefunds;
import top.leonx.territory.world.Interaction;
import top.leonx.territory.world.PersonalPenalties;
import top.leonx.territory.world.PurchasedClaims;
import top.leonx.territory.world.TerritoryNames;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    /** A child plot inside an admin territory: our own overlay, not an Easy Factions claim of its own. */
    public static final int TYPE_CHILD = 3;

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
                      boolean canPersonalClaim,
                      String factionName, int factionColor,
                      int coreCap, int coreUsed, int factionCap, int factionUsed) {
        public static Ctx empty() {
            return new Ctx(false, false, false, false, true, "", 0xFFFFFF, 0, 0, 0, 0);
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

        int coreCap = personalCapFor(server, uuid);
        int coreUsed = cm.getCoreChunkCount(uuid);

        int factionCap = inFaction ? factionCapFor(server, f) : 0;
        int factionUsed = inFaction ? cm.getFactionClaimCount(factionName) : 0;

        return new Ctx(true, inFaction, canFactionClaim, canAdminClaim(player), canPersonalClaim(server, uuid),
                factionName, factionColor, coreCap, coreUsed, factionCap, factionUsed);
    }

    /**
     * A player's personal claim cap: Easy Factions' {@code coreChunks} minus whatever they have lost to
     * being killed. Regenerates back to full over time — see {@link PersonalPenalties}.
     */
    public static int personalCapFor(MinecraftServer server, UUID player) {
        int base = ServerConfig.coreChunkAmount;
        if (server == null || player == null) return base;
        return Math.max(0, base - PersonalPenalties.get(server).getPenalty(player));
    }

    /**
     * Whether {@code player} may hold personal claims at all.
     *
     * A faction LEADER may not: when he founded the faction his personal land became the faction's land, and
     * from then on he claims for the faction. That is the whole shape of the mode — his members are fighting
     * to extend his territory and to keep it from being taken, and a leader with a private set of chunks on
     * the side would be sitting outside the thing everyone else is contesting. Ordinary members and officers
     * are unaffected and keep their own personal claims.
     */
    public static boolean canPersonalClaim(MinecraftServer server, UUID player) {
        if (!TerritoryConfig.leaderClaimsBecomeFaction()) return true;
        if (server == null || player == null) return true;
        return !FactionStateManager.get(server).playerOwnsFaction(player);
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

    // ---- who counts as one of yours -------------------------------------------------------------------

    /**
     * Whether {@code a} and {@code b} are in one faction that has friendly fire switched off.
     *
     * The faction's own setting is the whole answer, so a faction that wants to brawl still can. Easy
     * Factions applies this to one player hitting another and to nothing else; everything a player brings
     * with them, from a tamed wolf to a summoned skeleton, is a stranger as far as that check is concerned.
     * This is the question those cases ask.
     */
    public static boolean sameSideNoFriendlyFire(MinecraftServer server, UUID a, UUID b) {
        if (!loaded() || server == null || a == null || b == null || a.equals(b)) return false;
        FactionStateManager fsm = FactionStateManager.get(server);
        Faction fa = fsm.getFactionByPlayer(a);
        if (fa == null) return false;
        Faction fb = fsm.getFactionByPlayer(b);
        if (fb == null) return false;
        if (fa.getName().equals(fb.getName())) return !fa.getFriendlyFire();
        if (!TerritoryConfig.protectAllies()) return false;
        // an alliance counts only when BOTH sides have said so: one faction declaring peace at another is a
        // statement about itself, not a shared health bar
        return friendlyToward(fa, fb.getName()) && friendlyToward(fb, fa.getName());
    }

    private static boolean friendlyToward(Faction from, String towards) {
        if (from.getOutgoingRelations() == null) return false;
        var relation = from.getOutgoingRelations().get(towards);
        return relation != null && "FRIENDLY".equals(relation.name());
    }

    /** The faction {@code player} belongs to, or an empty string. */
    public static String factionNameOf(MinecraftServer server, UUID player) {
        if (!loaded() || server == null || player == null) return "";
        Faction f = FactionStateManager.get(server).getFactionByPlayer(player);
        return f == null ? "" : f.getName();
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
        // Land lost in war is lost and no money changes hands: that is what losing means. The capacity it
        // stood on has already gone to the winner, so there is nothing else left to move.
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

    // ---- conquest: personal claims -------------------------------------------------------------------

    /** Outcome of one kill against a player's PERSONAL claims. */
    public record PersonalResult(boolean applied, int slotsLost, int chunksTaken) {
        public static final PersonalResult NONE = new PersonalResult(false, 0, 0);
    }

    /**
     * Whether a kill is one the land rules should react to at all: not suicide, not a team-mate, not an ally.
     * Two players with no faction between them DO qualify — a solo player still has land to lose.
     */
    private static boolean hostileKill(MinecraftServer server, ServerPlayer victim, ServerPlayer killer) {
        if (victim == null || killer == null || victim.getUUID().equals(killer.getUUID())) return false;
        FactionStateManager fsm = FactionStateManager.get(server);
        Faction vf = fsm.getFactionByPlayer(victim.getUUID());
        Faction kf = fsm.getFactionByPlayer(killer.getUUID());
        if (vf == null || kf == null) return true;                       // at least one side is unaffiliated
        if (vf.getName().equals(kf.getName())) return false;             // no friendly-fire farming
        Alliance alliance = AllianceStateManager.get(server).getAllianceByFaction(kf.getName());
        return alliance == null || !alliance.getMembers().contains(vf.getName());
    }

    /**
     * The solo player's half of the tug of war: being killed costs you personal claim capacity, and once
     * your ceiling falls below what you hold, the chunk NEAREST WHERE YOU DIED is released.
     *
     * The "unless you still have claims in stock" rule falls straight out of doing it this way: if you are
     * holding fewer chunks than your cap, the kill only takes a slot you had not spent yet and nothing
     * disappears from the map. It costs you the next outpost, not the one you live in.
     *
     * A faction LEADER is skipped: his land is the faction's land, so the faction penalty in
     * {@link #applyKill} has already taken his loss and charging him twice would be double-dipping.
     */
    public static PersonalResult applyPersonalKill(ServerPlayer victim, ServerPlayer killer) {
        if (!loaded() || victim == null || killer == null) return PersonalResult.NONE;
        MinecraftServer server = victim.getServer();
        if (server == null) return PersonalResult.NONE;
        if (!hostileKill(server, victim, killer)) return PersonalResult.NONE;

        UUID uuid = victim.getUUID();
        if (FactionStateManager.get(server).playerOwnsFaction(uuid)) return PersonalResult.NONE;

        int lost = PersonalPenalties.get(server).addPenalty(uuid, TerritoryConfig.personalClaimsLostPerKill(),
                ServerConfig.coreChunkAmount);
        int taken = takeExcessPersonalLand(server, victim);
        if (lost == 0 && taken == 0) return PersonalResult.NONE;
        return new PersonalResult(true, lost, taken);
    }

    /** Release the victim's personal chunks nearest their death site until they are back under cap. */
    private static int takeExcessPersonalLand(MinecraftServer server, ServerPlayer victim) {
        ClaimManager cm = ClaimManager.get(server);
        UUID uuid = victim.getUUID();
        int excess = cm.getCoreChunkCount(uuid) - personalCapFor(server, uuid);
        if (excess <= 0) return 0;

        ResourceKey<Level> dimKey = victim.level().dimension();
        Map<ResourceKey<Level>, Set<Long>> byDim = cm.getPlayerCoreChunks(uuid);
        Set<Long> here = byDim != null ? byDim.get(dimKey) : null;
        // nothing of theirs where they died: the ceiling still dropped, land elsewhere is safe
        if (here == null || here.isEmpty()) return 0;

        List<Long> doomed = nearestFirst(here, victim.chunkPosition(), excess);
        HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
        rm.put(dimKey.location(), doomed);
        cm.unclaimChunks(rm, server);
        return doomed.size();
    }

    /** The {@code limit} chunks of {@code from} closest to {@code centre}, nearest first. */
    private static List<Long> nearestFirst(Set<Long> from, ChunkPos centre, int limit) {
        List<Long> sorted = new ArrayList<>(from);
        sorted.sort((a, b) -> Long.compare(distSq(a, centre), distSq(b, centre)));
        return new ArrayList<>(sorted.subList(0, Math.min(Math.max(0, limit), sorted.size())));
    }

    /** Advance personal penalty regeneration by one tick. True when a slot was actually refunded. */
    public static boolean regenPersonalPenalties(MinecraftServer server, int intervalTicks) {
        if (!loaded() || server == null) return false;
        return PersonalPenalties.get(server).regenAll(intervalTicks);
    }

    // ---- faction lifecycle: the leader's land IS the faction's land -----------------------------------

    /**
     * Founding a faction converts the founder's personal claims into faction claims, in place: same chunks,
     * same shape, new owner. Nothing is released and nothing has to be re-painted.
     *
     * @return how many chunks changed hands
     */
    public static int convertPersonalToFaction(MinecraftServer server, ServerPlayer founder, String factionName) {
        if (!loaded() || server == null || founder == null) return 0;
        if (!TerritoryConfig.leaderClaimsBecomeFaction()) return 0;

        FactionStateManager fsm = FactionStateManager.get(server);
        Faction f = fsm.getFactionByName(factionName);
        if (f == null) return 0;

        ClaimManager cm = ClaimManager.get(server);
        UUID uuid = founder.getUUID();
        Map<ResourceKey<Level>, Set<Long>> mine = cm.getPlayerCoreChunks(uuid);
        if (mine == null || mine.isEmpty()) return 0;

        int room = factionCapFor(server, f) - cm.getFactionClaimCount(factionName);
        int converted = 0;
        // snapshot first: claiming mutates the very maps we are walking
        Map<ResourceKey<Level>, List<Long>> snapshot = new HashMap<>();
        for (Map.Entry<ResourceKey<Level>, Set<Long>> e : mine.entrySet()) {
            if (!ServerConfig.factionClaimDimensions.contains(e.getKey().location().toString())) continue;
            snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
        }

        for (Map.Entry<ResourceKey<Level>, List<Long>> e : snapshot.entrySet()) {
            List<ChunkPos> take = new ArrayList<>();
            List<Long> takeLongs = new ArrayList<>();
            for (long chunk : e.getValue()) {
                if (room <= 0) break;
                take.add(new ChunkPos(chunk));
                takeLongs.add(chunk);
                room--;
            }
            if (take.isEmpty()) continue;

            // RELEASE FIRST. Easy Factions' claimChunk overwrites the claim map entry but only ever ADDS to
            // the index for the new type, so claiming straight over a CORE chunk would leave it counted in
            // the player's core index forever: his personal claim count would never drop, and he would be
            // unable to claim personally again even after the faction was gone.
            HashMap<ResourceLocation, List<Long>> release = new HashMap<>();
            release.put(e.getKey().location(), takeLongs);
            cm.unclaimChunks(release, server);

            HashMap<ResourceLocation, List<ChunkPos>> claim = new HashMap<>();
            claim.put(e.getKey().location(), take);
            cm.claimChunks(claim, ClaimType.FACTION, factionName, f.getColor(), server);
            converted += take.size();
        }
        // his personal debt died with his personal claims; he is on the faction's ledger now
        if (converted > 0) PersonalPenalties.get(server).clear(uuid);
        return converted;
    }

    /** What a disband gave back to the ex-leader, for the message he gets. */
    public record DisbandResult(int kept, int released) {
        public static final DisbandResult NONE = new DisbandResult(0, 0);
    }

    /**
     * Undo the conversion when a faction ends: the ex-leader gets personal claims back, but never more than
     * the personal cap allows, and everything above that is released.
     *
     * The clamp is the point. A leader whose faction held four hundred chunks does NOT walk away with four
     * hundred personal claims — he keeps the personal cap's worth around where he is standing and re-claims
     * the rest by hand if he wants it. The kept chunks are grown outwards from the one nearest him so he
     * keeps a connected block of land rather than a scatter.
     *
     * This ALSO cleans up after Easy Factions. {@code ClaimManager.deleteFactionData} only drops the faction
     * from its index maps and leaves the claims themselves sitting in the claim map, owned by a faction that
     * no longer exists. Nobody's faction name can ever match, so those chunks are protected against everyone
     * forever, and no command can release them because the faction they belong to is gone. Releasing them
     * here is what stops a disband from bricking that land permanently.
     */
    public static DisbandResult revertFactionToPersonal(MinecraftServer server, String factionName, UUID ownerId) {
        if (!loaded() || server == null || factionName == null) return DisbandResult.NONE;

        ClaimManager cm = ClaimManager.get(server);
        Map<ResourceKey<Level>, Set<Long>> chunks = cm.getFactionChunks(factionName);
        if (chunks == null || chunks.isEmpty()) return DisbandResult.NONE;

        // snapshot: everything below mutates the live maps
        Map<ResourceKey<Level>, Set<Long>> snapshot = new HashMap<>();
        chunks.forEach((dim, set) -> snapshot.put(dim, new HashSet<>(set)));

        ServerPlayer owner = ownerId != null ? server.getPlayerList().getPlayer(ownerId) : null;
        int keepBudget = 0;
        ResourceKey<Level> keepDim = null;
        if (ownerId != null && TerritoryConfig.leaderClaimsBecomeFaction()) {
            keepBudget = Math.max(0, personalCapFor(server, ownerId) - cm.getCoreChunkCount(ownerId));
            keepDim = chooseKeepDimension(snapshot, owner);
        }

        List<Long> keep = List.of();
        if (keepBudget > 0 && keepDim != null) {
            ChunkPos anchor = owner != null && owner.level().dimension().equals(keepDim)
                    ? owner.chunkPosition() : new ChunkPos(snapshot.get(keepDim).iterator().next());
            keep = growFrom(snapshot.get(keepDim), anchor, keepBudget);
        }

        // Release EVERYTHING the faction held, kept chunks included: a chunk claimed straight over would
        // stay indexed under the dead faction (see convertPersonalToFaction), and the ones nobody keeps have
        // to come off the map anyway or Easy Factions leaves them claimed by a faction that no longer exists.
        int released = 0;
        for (Map.Entry<ResourceKey<Level>, Set<Long>> e : snapshot.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
            rm.put(e.getKey().location(), new ArrayList<>(e.getValue()));
            cm.unclaimChunks(rm, server);
            released += e.getValue().size();
        }
        released -= keep.size();

        if (!keep.isEmpty()) {
            List<ChunkPos> back = new ArrayList<>();
            for (long chunk : keep) back.add(new ChunkPos(chunk));
            HashMap<ResourceLocation, List<ChunkPos>> claim = new HashMap<>();
            claim.put(keepDim.location(), back);
            int color = TerritoryNames.get(server).getColor(ownerId);
            if (color == TerritoryNames.NO_COLOR) color = ServerConfig.coreClaimColor;
            cm.claimChunks(claim, ClaimType.CORE, ownerId.toString(), color & 0xFFFFFF, server);
        }

        // The faction's money has to land somewhere: the faction is gone, its name is free for anybody to
        // take, and a pool held under a dead name would either be lost or picked up by whoever registers it
        // next. Whatever it was still owed goes to the person who was its owner. The claims it bought are
        // not sold back: they were the faction's ceiling, and the faction is what just ended.
        if (ownerId != null) {
            ClaimRefunds.get(server).transfer(ClaimRefunds.factionKey(factionName),
                    ClaimRefunds.playerKey(ownerId));
        }
        return new DisbandResult(keep.size(), released);
    }

    /**
     * Which dimension the ex-leader keeps land in: the one he is standing in when it is both allowed for
     * personal claims and actually holds some, otherwise whichever allowed dimension holds the most.
     */
    private static ResourceKey<Level> chooseKeepDimension(Map<ResourceKey<Level>, Set<Long>> chunks, ServerPlayer owner) {
        ResourceKey<Level> best = null;
        int bestCount = 0;
        for (Map.Entry<ResourceKey<Level>, Set<Long>> e : chunks.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            // personal claims are dimension-restricted in Easy Factions; do not hand back land it forbids
            if (!ServerConfig.coreClaimDimensions.contains(e.getKey().location().toString())) continue;
            if (owner != null && owner.level().dimension().equals(e.getKey())) return e.getKey();
            if (e.getValue().size() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue().size();
            }
        }
        return best;
    }

    /**
     * Up to {@code limit} chunks of {@code available}, grown outwards from the one nearest {@code anchor} so
     * the result is a single connected block wherever the shape allows it. Falls back to the next-nearest
     * chunk when the blob runs out of neighbours, so a disconnected holding still fills the budget.
     */
    private static List<Long> growFrom(Set<Long> available, ChunkPos anchor, int limit) {
        List<Long> byDistance = nearestFirst(available, anchor, available.size());
        Set<Long> taken = new LinkedHashSet<>();
        for (long seed : byDistance) {
            if (taken.size() >= limit) break;
            if (taken.contains(seed)) continue;
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(seed);
            while (!q.isEmpty() && taken.size() < limit) {
                long c = q.poll();
                if (!available.contains(c) || !taken.add(c)) continue;
                for (long n : neighbours(c)) {
                    if (available.contains(n) && !taken.contains(n)) q.add(n);
                }
            }
        }
        return new ArrayList<>(taken);
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

    /**
     * One claimed chunk for the GUI: coords + KIND + the claim's EF colour + the label to draw on it, plus
     * the CHILD plot covering it if there is one.
     *
     * The child fields are populated for admin claims only and are sent to the Territory Table and nowhere
     * else. Everything that renders the world map — Easy Factions' own overlay, Here Be Doodles, War 'n
     * Nobility's War Frame, any atlas — reads Easy Factions' claim data, which never knows children exist.
     * A player looking at the map sees "King's Landing", not the twenty plots inside it.
     */
    public record ClaimCell(int x, int z, int kind, int color, String label,
                            String childName, int childColor) {
        public ClaimCell(int x, int z, int kind, int color, String label) {
            this(x, z, kind, color, label, "", 0);
        }
    }

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
                String childName = "";
                int childColor = 0;
                if (data.type == ClaimType.ADMIN) {
                    // OUR record wins: EF force-resets every admin claim's colour to ServerConfig.adminClaimColor
                    // on world load, so data.color is unreliable for admin claims after a restart.
                    String parent = adminZones.parentNameAt(dim, pos.toLong());
                    AdminTerritories.Territory zone = parent.isEmpty() ? null : adminZones.get(dim, parent);
                    label = zone != null && !zone.name().isEmpty() ? zone.name() : "Admin";
                    if (zone != null) color = zone.color();
                    String child = adminZones.childNameAt(dim, pos.toLong());
                    if (!child.isEmpty()) {
                        AdminTerritories.Territory ct = adminZones.get(dim, child);
                        if (ct != null) {
                            childName = ct.name();
                            childColor = ct.color();
                        }
                    }
                } else {
                    label = claimLabel(server, names, data);
                }
                out.add(new ClaimCell(x, z, kind, color, label, childName, childColor));
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
            if (!canPersonalClaim(server, uuid)) {
                return "Your faction's land is your land now - claim for the faction instead.";
            }
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

        // A colony outranks a faction. Ground somebody else's town stands on is not free land, however empty
        // the Easy Factions map looks, and the two mods claim chunks in complete ignorance of each other.
        // Refused as a whole rather than by quietly dropping the offending chunks, so the player is told which
        // town stopped them instead of watching half a drag-selection fail to appear. Admin claiming skips
        // this: an operator painting spawn over a colony is doing it on purpose.
        if (!admin && TerritoryConfig.respectColonyClaims() && MineColoniesBridge.usable()) {
            for (ChunkPos cp : safeAdd) {
                String colony = MineColoniesBridge.claimRefusal(server, dimKey, cp, uuid);
                if (colony != null) {
                    return "Chunk " + cp.x + ", " + cp.z + " belongs to the colony " + colony
                            + ". A colony outranks a claim: you cannot claim a town you are not part of.";
                }
            }
        }

        // A border that touches a rival's border is not a border, it is a siege line. Claiming the ring of
        // chunks around somebody's land takes nothing worth having and makes their land worth less, which is
        // the whole point of doing it, so a gap of open ground is required between two different owners.
        // Checked on ADD only: land that is already touching stays where it is and can still be released.
        if (!admin && TerritoryConfig.claimBufferChunks() > 0) {
            String tooClose = bufferRefusal(server, cm, fsm, dimKey, safeAdd, addSet, uuid,
                    faction ? f.getName() : null);
            if (tooClose != null) return tooClose;
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
                    if (cm.getFactionClaimCount(f.getName()) - removeOk.size() + safeAdd.size() > cap) {
                        return "Faction claim limit reached (" + cap + "). Buy more claims from the Faction"
                                + " tab of the Territory Table, then place them on the map for free.";
                    }
                } else {
                    // the personal cap shrinks when you are killed, so read it rather than the raw config
                    int cap = personalCapFor(server, uuid);
                    if (cm.getCoreChunkCount(uuid) - removeOk.size() + safeAdd.size() > cap) {
                        return "Personal claim limit reached (" + cap + ").";
                    }
                }
            }
        }

        // Nothing left can refuse this, so the land moves. No money is taken here and none is handed back:
        // land is bought at the Buy Claims button and placed for nothing. Charging at both ends billed a
        // player twice for one chunk, once for the right to hold it and once for holding it.
        if (!removeOk.isEmpty()) {
            HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
            rm.put(dim, new ArrayList<>(removeOk));
            cm.unclaimChunks(rm, server);
            // Releasing land IS the refund: the slot it stood on goes back to the owner's ceiling, to be
            // spent on ground somewhere else. Nothing was paid for the chunk, so nothing is paid back.
            if (admin) {
                // drop the identity with the claim, or a later admin claim here inherits a stale label,
                // stale permissions and a stale member list. Also takes the chunk out of any child plot.
                AdminTerritories zones = AdminTerritories.get(server);
                for (long chunk : removeOk) zones.clearChunk(dimKey, chunk);
            }
        }
        if (!safeAdd.isEmpty()) {
            HashMap<ResourceLocation, List<ChunkPos>> ad = new HashMap<>();
            ad.put(dim, safeAdd);
            if (admin) {
                int rgb = adminColor < 0 ? TerritoryConfig.adminDefaultColor() : (adminColor & 0xFFFFFF);
                cm.claimChunks(ad, ClaimType.ADMIN, "Admin", rgb, server);
                // EF will forget this colour on the next world load, so keep our own authoritative copy.
                // A brand-new territory starts at NOT_SET, i.e. exactly EF's global admin behaviour, until
                // an operator changes something in the Permissions tab.
                AdminTerritories zones = AdminTerritories.get(server);
                for (ChunkPos cp : safeAdd) {
                    zones.assignParent(dimKey, cp.toLong(), adminName, rgb, AdminPerm.NOT_SET);
                }
            } else if (faction) {
                cm.claimChunks(ad, ClaimType.FACTION, f.getName(), f.getColor(), server);
            } else {
                int color = coreColor < 0 ? ServerConfig.coreClaimColor : (coreColor & 0xFFFFFF);
                cm.claimChunks(ad, ClaimType.CORE, uuid.toString(), color, server);
            }
        }
        return claimReceipt(safeAdd.size(), removeOk.size());
    }

    /**
     * What the player is told after a claim goes through: what changed hands.
     *
     * There is no price on this line any more because no money moves on it. Land is paid for once, at the
     * Buy Claims button, and what happens here is only ground arriving or leaving.
     */
    private static String claimReceipt(int added, int removed) {
        StringBuilder msg = new StringBuilder();
        if (added > 0) msg.append("Claimed ").append(added).append(added == 1 ? " chunk" : " chunks");
        if (removed > 0) {
            if (msg.length() > 0) msg.append(", released ").append(removed);
            else msg.append("Released ").append(removed).append(removed == 1 ? " chunk" : " chunks");
        }
        if (msg.length() == 0) return "";
        return msg.append(".").toString();
    }

    /**
     * The claim, if any, that is too close to one of the chunks being taken, as a message.
     *
     * Everything the claimant already has standing counts as theirs: their faction's land, their own
     * personal claims, and their own members' personal claims, so a faction is never pushed away from its
     * own territory by this. Admin claims are ignored in both directions, since spawn and an arena are meant
     * to be built up against.
     */
    private static String bufferRefusal(MinecraftServer server, ClaimManager cm, FactionStateManager fsm,
                                        ResourceKey<Level> dimKey, List<ChunkPos> adds, Set<Long> addSet,
                                        UUID claimant, String factionName) {
        int buffer = TerritoryConfig.claimBufferChunks();
        for (ChunkPos cp : adds) {
            for (int dx = -buffer; dx <= buffer; dx++) {
                for (int dz = -buffer; dz <= buffer; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    ChunkPos near = new ChunkPos(cp.x + dx, cp.z + dz);
                    if (addSet.contains(near.toLong())) continue;
                    if (!cm.isClaimed(dimKey, near)) continue;
                    ClaimData d = cm.getClaim(dimKey, near);
                    if (d == null || d.type == ClaimType.ADMIN) continue;
                    if (friendlyClaim(fsm, d, claimant, factionName)) continue;
                    return "Too close to " + ownerDisplay(server, d) + ". Claims must stay " + buffer
                            + (buffer == 1 ? " chunk" : " chunks") + " clear of another owner's land.";
                }
            }
        }
        return null;
    }

    /** Whether a claim belongs to the claimant, to their faction, or to one of their own members. */
    private static boolean friendlyClaim(FactionStateManager fsm, ClaimData claim, UUID claimant,
                                         String factionName) {
        if (claim.type == ClaimType.FACTION) return factionName != null && factionName.equals(claim.owner);
        if (claim.type == ClaimType.CORE) {
            UUID owner = parseUuid(claim.owner);
            if (owner == null) return false;
            if (owner.equals(claimant)) return true;
            if (factionName == null) return false;
            Faction theirs = fsm.getFactionByPlayer(owner);
            return theirs != null && factionName.equals(theirs.getName());
        }
        return true;
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

    // ---- protection: who may do what, where ----------------------------------------------------------

    /** What this mod has to say about one interaction. DEFER = nothing to add, let Easy Factions decide. */
    public enum Decision { ALLOW, DENY, DEFER }

    /**
     * The ruling for {@code player} performing {@code interaction} on {@code pos}.
     *
     * <h2>Personal claims (CORE) — the bug this exists to fix</h2>
     * Easy Factions' own check reads:
     * <pre>
     *   Set&lt;Long&gt; coreChunks = claimManager.getPlayerCoreChunks(UUID.fromString(claim.owner)).get(dimension);
     *   if (coreChunks != null) return coreChunks.contains(pos.toLong());
     * </pre>
     * It asks whether the chunk belongs to the claim's OWNER, which is true by definition for any chunk that
     * owner has claimed — the interacting player is never looked at. So every personal claim on every server
     * grants permission to everybody, which is precisely the "personal claims do nothing" report. We compare
     * against the player instead. Operators still pass, and which interactions are protected at all is still
     * Easy Factions' {@code coreClaimRestrictions} config, so servers keep the settings they already tuned.
     *
     * <h2>Faction claims — correct in Easy Factions, and enforced again here anyway</h2>
     * Easy Factions' faction branch is sound: it compares the acting player's faction name against the claim
     * owner. It is re-checked here regardless, because being logically correct is not the same as running.
     * Three things switch it off in the field with nothing in the log to show for it: EF's restriction list
     * being empty in the save's own copy of the config, every player sitting at permission level 2, and any
     * other mod un-cancelling the event after EF cancelled it. Ruling here, last, survives all three.
     *
     * <h2>Admin claims</h2>
     * Easy Factions has one global restriction list for every admin claim on the server. A territory with
     * per-territory switches ({@link AdminPerm}) answers for itself instead, a child plot answers before its
     * parent, and members of either are exempt. A territory that has never been customised returns DEFER and
     * behaves exactly as it did before.
     */
    public static Decision decide(Player player, ResourceKey<Level> dim, ChunkPos pos, Interaction interaction) {
        if (!loaded() || player == null || dim == null || pos == null || interaction == null) return Decision.DEFER;
        if (!TerritoryConfig.protectionEnabled()) return Decision.DEFER;
        MinecraftServer server = player.getServer();
        if (server == null) return Decision.DEFER;

        ClaimManager cm = ClaimManager.get(server);
        if (!cm.isClaimed(dim, pos)) return Decision.DEFER;
        ClaimData claim = cm.getClaim(dim, pos);
        if (claim == null) return Decision.DEFER;
        // Operators bypass, as in EF — but at a level the server picks. EF hardcodes 2, which is worth nothing
        // on a server that grants level 2 to a rank, and that alone reads as "claims stopped working".
        if (player.hasPermissions(TerritoryConfig.bypassPermissionLevel())) return Decision.DEFER;

        return colonyPriority(server, player, dim, pos, claim, interaction,
                claimDecision(server, player, claim, dim, pos, interaction));
    }

    /** What the claim alone says, before a colony standing on the same chunk is taken into account. */
    private static Decision claimDecision(MinecraftServer server, Player player, ClaimData claim,
                                          ResourceKey<Level> dim, ChunkPos pos, Interaction interaction) {
        if (claim.type == ClaimType.CORE) {
            if (!TerritoryConfig.enforcePersonalClaims()) return Decision.DEFER;
            if (!restricts(ClaimType.CORE, interaction)) return relax(ClaimType.CORE, interaction);
            // DEFER, not ALLOW, for the owner: where a claim DOES protect something, this exists to add a
            // restriction Easy Factions is missing, never to hand out permission. Returning ALLOW here would
            // un-cancel any OTHER protection mod that had already refused the same interaction.
            return player.getUUID().toString().equals(claim.owner) ? Decision.DEFER : Decision.DENY;
        }
        if (claim.type == ClaimType.FACTION) {
            if (!TerritoryConfig.enforceFactionClaims()) return Decision.DEFER;
            if (!restricts(ClaimType.FACTION, interaction)) return relax(ClaimType.FACTION, interaction);
            Faction f = FactionStateManager.get(server).getFactionByPlayer(player.getUUID());
            // no faction at all is the common case for a raider, and EF denies it too
            return f != null && f.getName().equals(claim.owner) ? Decision.DEFER : Decision.DENY;
        }
        if (claim.type == ClaimType.ADMIN) {
            return adminDecision(server, player.getUUID(), dim, pos.toLong(), interaction);
        }
        return Decision.DEFER;
    }

    /**
     * A colony outranks whatever claim has been painted over it.
     *
     * <h2>The colony's own people</h2>
     * They are freed, not merely left alone. By the time this runs Easy Factions has already refused them,
     * so returning DEFER would leave a colony's founder unable to break a block in the town they built the
     * moment a rival drew a border round it. That refusal is undone the same NARROW way {@code relax} undoes
     * one: only where Easy Factions' own config accounts for the interaction, so a cancellation from any
     * other protection mod is left standing.
     *
     * <h2>Everybody else</h2>
     * ALLOW becomes DEFER, and nothing else changes. This is the half that answers "the faction claim is
     * overruling the colony claim": un-cancelling is indiscriminate, it un-cancels for whoever is asking,
     * and MineColonies' own refusal is not this mod's to reverse. A claim may still ADD a refusal here, so
     * an outsider standing in a colony inside a rival claim is refused by both and pleases neither.
     *
     * <h2>Admin territories are exempt</h2>
     * An operator painting spawn, an arena or a warzone over a colony has said what they meant, and the
     * per-territory permission switches are how they said it.
     */
    private static Decision colonyPriority(MinecraftServer server, Player player, ResourceKey<Level> dim,
                                           ChunkPos pos, ClaimData claim, Interaction interaction,
                                           Decision base) {
        if (claim.type == ClaimType.ADMIN || !MineColoniesBridge.usable()) return base;
        List<Integer> colonies = MineColoniesBridge.claimingColonies(server, dim, pos);
        if (colonies.isEmpty()) return base;
        if (TerritoryConfig.colonyMembersKeepTheirLand()
                && MineColoniesBridge.trustedInAll(server, dim, colonies, player.getUUID())) {
            // ALLOW undoes an Easy Factions refusal; DEFER where there is none to undo. Either way the claim
            // never gets to refuse them, including where OUR list is the wider of the two and EF would have
            // let it through: at home, a rival border is not allowed to be the thing that stops them.
            if (efRestricts(efListFor(claim.type), interaction)) return Decision.ALLOW;
            return base == Decision.DENY ? Decision.DEFER : base;
        }
        return base == Decision.ALLOW ? Decision.DEFER : base;
    }

    /**
     * Whether a claim of {@code type} protects against {@code interaction}.
     *
     * Reads our own list by default rather than Easy Factions'. EF's lists come from a NeoForge SERVER config,
     * which lives in {@code <world>/serverconfig/} and not in {@code config/}, so the file an admin actually
     * edited is frequently not the file being read. An empty list there disables protection outright and logs
     * nothing, which is indistinguishable from the mod being broken.
     */
    private static boolean restricts(ClaimType type, Interaction interaction) {
        if (TerritoryConfig.useOwnRestrictions()) {
            // containers answer to their own switch, since Easy Factions cannot tell a chest from a door
            if (interaction == Interaction.CONTAINER) return TerritoryConfig.protectContainers();
            return TerritoryConfig.restrictedInteractions().contains(interaction);
        }
        return efRestricts(efListFor(type), interaction);
    }

    /**
     * What to say about an interaction a claim does NOT protect against.
     *
     * Easy Factions has already had its say by the time this runs, and its own restriction list is far wider
     * than the default one here: leave it at DEFER and taking RIGHT_CLICK_BLOCK out of our list changes
     * nothing whatsoever, because EF cancelled the click before we were asked. So where EF refused something
     * our list deliberately permits, that refusal is undone.
     *
     * Undone NARROWLY, and only ever inside a claimed chunk: the refusal must be one Easy Factions' own
     * config accounts for. A cancellation coming from anywhere else — a spawn-protection mod, a minigame, a
     * region plugin — is left exactly where it is, because ALLOW un-cancels for everybody at once and this
     * mod has no business overruling a decision it did not cause.
     */
    private static Decision relax(ClaimType type, Interaction interaction) {
        if (!TerritoryConfig.overrideEasyFactions() || !TerritoryConfig.useOwnRestrictions()) return Decision.DEFER;
        return efRestricts(efListFor(type), interaction) ? Decision.ALLOW : Decision.DEFER;
    }

    private static Set<ChunkInteractionType> efListFor(ClaimType type) {
        return switch (type) {
            case FACTION -> ServerConfig.factionClaimRestrictions;
            case CORE -> ServerConfig.coreClaimRestrictions;
            case ADMIN -> ServerConfig.adminClaimRestrictions;
        };
    }

    /** The admin-territory ruling for a chunk, with no player-specific check beyond membership. */
    private static Decision adminDecision(MinecraftServer server, UUID player, ResourceKey<Level> dim,
                                          long chunk, Interaction interaction) {
        AdminTerritories zones = AdminTerritories.get(server);
        AdminTerritories.Territory governing = zones.governing(dim, chunk);
        if (governing == null) return Decision.DEFER;
        // membership is checked FIRST and independently of the switches: trusting a player must work even in
        // a territory that never customised its permissions, or "I gave them the plot and nothing changed"
        if (player != null && zones.isTrusted(dim, chunk, player)) return Decision.ALLOW;
        if (governing.perms() == AdminPerm.NOT_SET) return Decision.DEFER;
        AdminPerm perm = AdminPerm.forInteraction(interaction);
        if (perm == null) return Decision.DEFER;
        return perm.allowedIn(governing.perms()) ? Decision.ALLOW : Decision.DENY;
    }

    /**
     * The ruling for an interaction with no player behind it: explosions, mob griefing, pistons.
     *
     * <h2>Why a faction claim gets an answer here at all</h2>
     * "Not even explosions should be stopped" was the other half of the ask that made claims stop at
     * breaking and placing, and it never worked, because this used to return DEFER for everything that was
     * not an admin territory. Easy Factions restricts explosions in its own default list and had already
     * cancelled by the time we were asked, so taking EXPLOSION_DAMAGE out of our list did nothing at all,
     * for exactly the reason {@link #relax} exists. Personal and faction claims are now ruled on the same
     * way as everything else: our list decides, and where it permits something Easy Factions refused, that
     * refusal is undone.
     *
     * Put EXPLOSION_DAMAGE, MOB_GRIEFING_DAMAGE or PISTON_MOVE back into {@code restrictedInteractions} to
     * have claims stop them again.
     */
    public static Decision decideAmbient(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos,
                                         Interaction interaction) {
        if (!loaded() || server == null || dim == null || pos == null) return Decision.DEFER;
        ClaimManager cm = ClaimManager.get(server);
        if (!cm.isClaimed(dim, pos)) return Decision.DEFER;
        ClaimData claim = cm.getClaim(dim, pos);
        if (claim == null) return Decision.DEFER;
        if (claim.type == ClaimType.ADMIN) return adminDecision(server, null, dim, pos.toLong(), interaction);
        if (!TerritoryConfig.protectionEnabled()) return Decision.DEFER;
        if (claim.type == ClaimType.FACTION && !TerritoryConfig.enforceFactionClaims()) return Decision.DEFER;
        if (claim.type == ClaimType.CORE && !TerritoryConfig.enforcePersonalClaims()) return Decision.DEFER;
        // there is no player to exempt: an explosion inside a claim is the same explosion whoever lit it
        return restricts(claim.type, interaction) ? Decision.DENY : relax(claim.type, interaction);
    }

    /**
     * Whether EASY FACTIONS' list restricts {@code interaction} — the question of what EF itself did, which
     * is not the same question as whether the interaction ought to be allowed.
     *
     * Asked about the Easy Factions equivalent, because our CONTAINER has no counterpart over there: EF only
     * ever saw a right click on a block, so that is the entry that decided whether it cancelled.
     */
    private static boolean efRestricts(Set<ChunkInteractionType> restrictions, Interaction interaction) {
        if (restrictions == null) return false;
        try {
            return restrictions.contains(
                    ChunkInteractionType.valueOf(interaction.easyFactionsEquivalent().name()));
        } catch (IllegalArgumentException e) {
            // our enum has drifted from Easy Factions': treat as unrestricted rather than guess
            return false;
        }
    }

    // ---- colony overlaps: claims already sitting on somebody else's town --------------------------------

    /**
     * One Easy Factions claim standing on colony land that its owner has no part in.
     *
     * Prevention only helps the towns nobody has taken yet. A server that has been running without this rule
     * already has claims sitting on colonies, and the people they were taken from cannot undo it themselves:
     * releasing a claim is the claim owner's privilege, and the claim owner is the one who took the land.
     */
    public record ColonyOverlap(ResourceKey<Level> dim, long chunk, ClaimType type, String owner,
                                String ownerDisplay, int colonyId, String colonyName) {
        public int chunkX() { return ChunkPos.getX(chunk); }
        public int chunkZ() { return ChunkPos.getZ(chunk); }

        public String describe() {
            String town = colonyName == null || colonyName.isBlank() ? "colony #" + colonyId : colonyName;
            return "chunk " + chunkX() + ", " + chunkZ() + " in " + dim.location()
                    + " - " + type + " claim of " + ownerDisplay + " on " + town;
        }
    }

    /**
     * Every claim on the server standing on a colony its owner does not belong to.
     *
     * Reads the whole claim map, which is held in memory and keyed by chunk, so nothing here loads a chunk
     * or a colony. Admin claims are never reported: an operator painting over a colony meant to.
     */
    public static List<ColonyOverlap> colonyOverlaps(MinecraftServer server) {
        List<ColonyOverlap> out = new ArrayList<>();
        if (!loaded() || server == null || !MineColoniesBridge.usable()) return out;
        ClaimManager cm = ClaimManager.get(server);
        for (Map.Entry<ResourceKey<Level>, Map<Long, ClaimData>> perDim : cm.getClaimMap().entrySet()) {
            ResourceKey<Level> dim = perDim.getKey();
            if (perDim.getValue() == null) continue;
            for (Map.Entry<Long, ClaimData> entry : perDim.getValue().entrySet()) {
                ClaimData claim = entry.getValue();
                if (claim == null || claim.type == ClaimType.ADMIN) continue;
                ChunkPos pos = new ChunkPos(entry.getKey());
                List<Integer> colonies = MineColoniesBridge.claimingColonies(server, dim, pos);
                if (colonies.isEmpty()) continue;
                if (claimBelongsToColonies(server, dim, colonies, claim)) continue;
                int colonyId = colonies.get(0);
                out.add(new ColonyOverlap(dim, entry.getKey(), claim.type, claim.owner,
                        ownerDisplay(server, claim), colonyId,
                        MineColoniesBridge.colonyName(server, dim, colonyId)));
            }
        }
        return out;
    }

    /**
     * Release the listed claims, returning how many chunks actually came off the map.
     *
     * Re-checks each chunk against the live claim map before releasing it, because the list was gathered
     * before an operator read it and land can change hands in between. Goes through {@code unclaimChunks},
     * the same call the map GUI uses, so Easy Factions maintains its own per-owner indexes and pushes the
     * change to every client currently looking at the map.
     */
    public static int releaseColonyOverlaps(MinecraftServer server, List<ColonyOverlap> overlaps) {
        if (!loaded() || server == null || overlaps == null || overlaps.isEmpty()) return 0;
        ClaimManager cm = ClaimManager.get(server);
        Map<ResourceKey<Level>, List<Long>> byDim = new HashMap<>();
        for (ColonyOverlap o : overlaps) {
            ClaimData live = cm.getClaim(o.dim(), new ChunkPos(o.chunk()));
            if (live == null || live.type != o.type() || !java.util.Objects.equals(live.owner, o.owner())) continue;
            byDim.computeIfAbsent(o.dim(), k -> new ArrayList<>()).add(o.chunk());
        }
        int released = 0;
        for (Map.Entry<ResourceKey<Level>, List<Long>> e : byDim.entrySet()) {
            HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
            rm.put(e.getKey().location(), e.getValue());
            cm.unclaimChunks(rm, server);
            released += e.getValue().size();
        }
        return released;
    }

    /**
     * What one owner lost to one colony in a sweep.
     *
     * Aggregated per owner rather than per chunk because that is the sentence a player needs to read: four
     * chunks gone to Ravenhold, not four separate notices about chunks they would have to go and look at a
     * map to place.
     */
    public record OwnerLoss(boolean faction, String owner, String ownerDisplay, String colonyName,
                            int chunks) {
        public String refundKey() {
            return faction ? ClaimRefunds.factionKey(owner) : ClaimRefunds.playerKey(UUID.fromString(owner));
        }
    }

    /**
     * Hand every chunk a colony has grown underneath back to that colony, and pay for what was taken.
     *
     * <h2>Why a colony expanding takes the chunk rather than the claim keeping it</h2>
     * A colony outranks a claim, and that has to hold in both directions or it is not a rule, it is a race:
     * refusing to claim over a town while letting a claim that got there first stand would simply mean the
     * faction that claims early keeps the land forever. A town is a thing somebody built; a claim is a
     * rectangle somebody dragged. So the town wins, including when it grows into ground that was already
     * spoken for.
     *
     * <h2>Why nothing is paid for it</h2>
     * The claim slot goes back the moment the chunk does, and a slot is what the faction actually bought.
     * They are not out of pocket: they can put the same claim down on open ground the same minute.
     *
     * The claim map is re-checked chunk by chunk before anything is released, because the overlap list is
     * gathered first and land can change hands while it is being read.
     */
    public static List<OwnerLoss> sweepColonyOverlaps(MinecraftServer server) {
        List<ColonyOverlap> overlaps = colonyOverlaps(server);
        if (overlaps.isEmpty()) return List.of();

        ClaimManager cm = ClaimManager.get(server);

        Map<ResourceKey<Level>, List<Long>> byDim = new HashMap<>();
        Map<String, OwnerLoss> perOwner = new LinkedHashMap<>();

        for (ColonyOverlap o : overlaps) {
            ClaimData live = cm.getClaim(o.dim(), new ChunkPos(o.chunk()));
            if (live == null || live.type != o.type() || !java.util.Objects.equals(live.owner, o.owner())) continue;
            boolean faction = o.type() == ClaimType.FACTION;
            if (!faction && parseUuid(o.owner()) == null) continue;   // a CORE claim with no readable owner

            byDim.computeIfAbsent(o.dim(), k -> new ArrayList<>()).add(o.chunk());

            String key = (faction ? "f:" : "p:") + o.owner();
            OwnerLoss running = perOwner.get(key);
            perOwner.put(key, running == null
                    ? new OwnerLoss(faction, o.owner(), o.ownerDisplay(), o.colonyName(), 1)
                    : new OwnerLoss(faction, running.owner(), running.ownerDisplay(),
                            running.colonyName().equals(o.colonyName()) ? running.colonyName() : "",
                            running.chunks() + 1));
        }

        for (Map.Entry<ResourceKey<Level>, List<Long>> e : byDim.entrySet()) {
            HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
            rm.put(e.getKey().location(), e.getValue());
            cm.unclaimChunks(rm, server);
        }
        return List.copyOf(perOwner.values());
    }

    /** The players to tell about a loss: a faction's whole roster, or the one player who held the claim. */
    public static List<ServerPlayer> onlineOwners(MinecraftServer server, OwnerLoss loss) {
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null || loss == null) return out;
        if (loss.faction()) {
            Faction f = FactionStateManager.get(server).getFactionByName(loss.owner());
            if (f == null || f.getMembers() == null) return out;
            for (UUID member : f.getMembers()) {
                ServerPlayer sp = server.getPlayerList().getPlayer(member);
                if (sp != null) out.add(sp);
            }
            return out;
        }
        UUID owner = parseUuid(loss.owner());
        ServerPlayer sp = owner == null ? null : server.getPlayerList().getPlayer(owner);
        if (sp != null) out.add(sp);
        return out;
    }

    /** Whether {@code player} is the one who may draw on a pool: its player, or the faction's owner. */
    public static boolean mayCollect(ServerPlayer player, String refundKey) {
        if (player == null || refundKey == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        if (!ClaimRefunds.isFactionKey(refundKey)) {
            return refundKey.equals(ClaimRefunds.playerKey(player.getUUID()));
        }
        if (!loaded()) return false;
        Faction f = FactionStateManager.get(server).getFactionByPlayer(player.getUUID());
        return f != null && ClaimRefunds.factionKey(f.getName()).equals(refundKey)
                && FactionStateManager.get(server).playerOwnsFaction(player.getUUID());
    }

    /** The pools {@code player} may collect right now: their own, and their faction's if they own it. */
    public static List<String> collectableKeys(ServerPlayer player) {
        List<String> keys = new ArrayList<>();
        if (player == null) return keys;
        keys.add(ClaimRefunds.playerKey(player.getUUID()));
        MinecraftServer server = player.getServer();
        if (!loaded() || server == null) return keys;
        FactionStateManager fsm = FactionStateManager.get(server);
        Faction f = fsm.getFactionByPlayer(player.getUUID());
        if (f != null && fsm.playerOwnsFaction(player.getUUID())) keys.add(ClaimRefunds.factionKey(f.getName()));
        return keys;
    }

    /**
     * Whether a claim's owner has any standing in the colonies underneath it.
     *
     * A personal claim answers for one player. A faction claim answers for its whole roster: one member of
     * the town is enough, so a faction that grew out of a colony keeps the border it drew round its own home.
     * A faction name that no longer resolves is a claim left behind by a disband, which belongs to nobody and
     * can never be released by its owner, so it is always an overlap.
     */
    private static boolean claimBelongsToColonies(MinecraftServer server, ResourceKey<Level> dim,
                                                  List<Integer> colonies, ClaimData claim) {
        if (claim.type == ClaimType.CORE) {
            UUID owner = parseUuid(claim.owner);
            return owner != null && MineColoniesBridge.trustedInAll(server, dim, colonies, owner);
        }
        if (claim.type == ClaimType.FACTION) {
            Faction f = FactionStateManager.get(server).getFactionByName(claim.owner);
            if (f == null) return false;
            for (UUID member : f.getMembers()) {
                if (MineColoniesBridge.trustedInAll(server, dim, colonies, member)) return true;
            }
            return false;
        }
        return true;
    }

    /** A claim owner as a person reads it: the faction name, or the player behind a personal claim. */
    private static String ownerDisplay(MinecraftServer server, ClaimData claim) {
        if (claim.type != ClaimType.CORE) return claim.owner;
        UUID owner = parseUuid(claim.owner);
        if (owner == null) return claim.owner;
        return nameOf(server, owner);
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- diagnostics -----------------------------------------------------------------------------------

    /**
     * Everything that decides whether {@code player} may build where they are standing, as display lines.
     *
     * This exists because the failure mode being diagnosed is silent on every side: a stale per-save config,
     * a permission rank nobody remembers granting and a claim in an unexpected dimension all look identical
     * from in-game, and none of them writes anything to the log. One command that prints the inputs and the
     * verdict together turns "it doesn't work on our server" into a screenshot that names the cause.
     */
    public static List<String> diagnose(ServerPlayer player) {
        List<String> out = new ArrayList<>();
        if (!loaded()) {
            out.add("Easy Factions is NOT loaded. Claims cannot work at all.");
            return out;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return List.of("No server. Run this in game.");

        ResourceKey<Level> dim = player.level().dimension();
        ChunkPos pos = player.chunkPosition();
        ClaimManager cm = ClaimManager.get(server);
        Faction mine = FactionStateManager.get(server).getFactionByPlayer(player.getUUID());

        out.add("Chunk " + pos.x + ", " + pos.z + " in " + dim.location());
        out.add("You: perm level " + permissionLevel(player)
                + ", faction " + (mine == null ? "(none)" : mine.getName()));

        boolean claimed = cm.isClaimed(dim, pos);
        ClaimData claim = claimed ? cm.getClaim(dim, pos) : null;
        if (claim == null) {
            out.add("Claim: UNCLAIMED. Nothing here is protected by anyone.");
        } else {
            out.add("Claim: " + claim.type + " owned by " + claim.owner
                    + " (" + chunkOwnerDisplay(server, dim, pos) + ")");
            if (claim.type == ClaimType.ADMIN) {
                AdminTerritories.Territory t = AdminTerritories.get(server).governing(dim, pos.toLong());
                out.add("  admin territory: " + (t == null ? "(unnamed, EF global rules apply)"
                        : t.name() + (t.perms() == AdminPerm.NOT_SET ? " (perms not customised)" : " (custom perms)")));
            }
        }

        out.add("Config (territory-server.toml [protection]): enabled=" + TerritoryConfig.protectionEnabled()
                + " faction=" + TerritoryConfig.enforceFactionClaims()
                + " personal=" + TerritoryConfig.enforcePersonalClaims()
                + " ownList=" + TerritoryConfig.useOwnRestrictions()
                + " bypassLevel=" + TerritoryConfig.bypassPermissionLevel());
        out.add("  our restricted list: " + TerritoryConfig.restrictedInteractions()
                + " overrideEF=" + TerritoryConfig.overrideEasyFactions()
                + " containers=" + (TerritoryConfig.protectContainers() ? "protected" : "open to all"));
        if (!MineColoniesBridge.loaded()) {
            out.add("MineColonies: not installed. Colony priority is inactive.");
        } else if (MineColoniesBridge.brokenApi()) {
            out.add("MineColonies: installed, but its claim API FAILED this run. Colony priority is OFF."
                    + " See the server log for the error.");
        } else {
            List<Integer> colonies = MineColoniesBridge.claimingColonies(server, dim, pos);
            if (colonies.isEmpty()) {
                out.add("MineColonies: no colony owns this chunk.");
            } else {
                int id = colonies.get(0);
                String town = MineColoniesBridge.colonyName(server, dim, id);
                boolean trusted = MineColoniesBridge.trustedInAll(server, dim, colonies, player.getUUID());
                out.add("MineColonies: this chunk belongs to " + (town.isBlank() ? "colony #" + id : town)
                        + ". You are " + (trusted
                        ? "one of its people: the claim above cannot shut you out."
                        : "NOT one of its people: the colony decides here, and this mod will not undo its refusal."));
                out.add("  colony priority config: respectColonyClaims=" + TerritoryConfig.respectColonyClaims()
                        + " colonyMembersKeepTheirLand=" + TerritoryConfig.colonyMembersKeepTheirLand());
            }
        }
        out.add("Easy Factions live config: faction=" + nameSet(ServerConfig.factionClaimRestrictions)
                + " core=" + nameSet(ServerConfig.coreClaimRestrictions));
        out.add("  EF claim dimensions: faction=" + ServerConfig.factionClaimDimensions
                + " core=" + ServerConfig.coreClaimDimensions);
        List<String> overrides = TerritoryConfig.perWorldConfigOverrides(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        if (!overrides.isEmpty()) {
            out.add("  WARNING: this world overrides " + overrides + " from <world>/serverconfig/."
                    + " Edits made in config/ are being ignored.");
        }

        if (player.hasPermissions(TerritoryConfig.bypassPermissionLevel())) {
            out.add("VERDICT: you BYPASS protection at permission level "
                    + TerritoryConfig.bypassPermissionLevel() + ". Test as a normal player, or raise"
                    + " bypassPermissionLevel to 4.");
        }
        // ALLOW reads as "Easy Factions refused this and we put it back", which is the whole mechanism behind
        // a claim that only stops building, so it is worth spelling out rather than printing a bare verdict.
        for (Interaction i : new Interaction[]{Interaction.BREAK_BLOCK, Interaction.PLACE_BLOCK,
                Interaction.RIGHT_CLICK_BLOCK, Interaction.CONTAINER, Interaction.INTERACT_ENTITY}) {
            Decision d = decide(player, dim, pos, i);
            String note = switch (d) {
                case DENY -> " (blocked)";
                case ALLOW -> " (allowed - Easy Factions' refusal is being undone here)";
                case DEFER -> " (no opinion; Easy Factions' answer stands)";
            };
            out.add("VERDICT " + i + ": " + d + note);
        }
        return out;
    }

    /** Whether EF's own restriction lists have loaded and still cover building, for the startup report. */
    public static List<String> protectionWarnings() {
        List<String> out = new ArrayList<>();
        if (!loaded()) return out;
        if (ServerConfig.factionClaimRestrictions == null || ServerConfig.coreClaimRestrictions == null) {
            out.add("Easy Factions' restriction lists are null: its server config never loaded for this world.");
            return out;
        }
        if (TerritoryConfig.useOwnRestrictions()) {
            // The opposite failure to an empty list, and the one the looser default makes likely: our list
            // permits something, Easy Factions' list still forbids it, and nothing here is allowed to undo
            // that. Players then report an interaction being blocked that the config plainly permits.
            if (!TerritoryConfig.overrideEasyFactions()) {
                List<Interaction> stillBlocked = new ArrayList<>();
                for (Interaction i : Interaction.values()) {
                    if (i == Interaction.CONTAINER) continue;                 // covered by RIGHT_CLICK_BLOCK
                    if (TerritoryConfig.restrictedInteractions().contains(i)) continue;
                    if (efRestricts(ServerConfig.factionClaimRestrictions, i)
                            || efRestricts(ServerConfig.coreClaimRestrictions, i)) {
                        stillBlocked.add(i);
                    }
                }
                if (!stillBlocked.isEmpty()) {
                    out.add("overrideEasyFactions is off, so Easy Factions still blocks " + stillBlocked
                            + " inside claims even though restrictedInteractions permits them. Turn it on, or"
                            + " remove those entries from easy_factions-server.toml as well.");
                }
            }
            return out;                                        // EF's lists are not being consulted otherwise
        }
        for (ChunkInteractionType t : new ChunkInteractionType[]{
                ChunkInteractionType.BREAK_BLOCK, ChunkInteractionType.PLACE_BLOCK}) {
            if (!ServerConfig.factionClaimRestrictions.contains(t)) {
                out.add("Easy Factions' factionClaimRestrictions does not contain " + t
                        + ", so faction claims will NOT stop it.");
            }
            if (!ServerConfig.coreClaimRestrictions.contains(t)) {
                out.add("Easy Factions' coreClaimRestrictions does not contain " + t
                        + ", so personal claims will NOT stop it.");
            }
        }
        return out;
    }

    /**
     * The player's effective permission level, probed rather than read: {@code getPermissionLevel} is
     * protected, and probing is the more honest answer anyway because {@code hasPermissions} is what every
     * bypass check actually calls, including any permission mod that overrides it.
     */
    private static int permissionLevel(ServerPlayer player) {
        for (int level = 4; level >= 1; level--) {
            if (player.hasPermissions(level)) return level;
        }
        return 0;
    }

    private static String nameSet(Set<ChunkInteractionType> set) {
        if (set == null) return "(not loaded)";
        if (set.isEmpty()) return "[] EMPTY - protects nothing";
        return set.toString();
    }

    // ---- admin territories: permissions, members, child plots -----------------------------------------

    /**
     * The permission mask that matches Easy Factions' CURRENT global admin config. Shown as the starting
     * position of the switches for a territory that has never been customised, so flipping one switch does
     * not silently change the other five out from under the operator.
     */
    public static int defaultAdminPerms() {
        int mask = 0;
        for (AdminPerm perm : AdminPerm.values()) {
            boolean anyRestricted = false;
            for (Interaction i : perm.covers()) {
                if (efRestricts(ServerConfig.adminClaimRestrictions, i)) {
                    anyRestricted = true;
                    break;
                }
            }
            if (!anyRestricted) mask |= perm.mask();
        }
        return mask;
    }

    /**
     * One admin territory as the GUI needs it. {@code perms} is always the EFFECTIVE mask, so an untouched
     * territory shows the switches in the positions Easy Factions' global config is already enforcing rather
     * than a misleading row of OFF; {@code custom} says whether that mask is the territory's own.
     */
    public record AdminZoneInfo(String name, String parent, int color, int perms, boolean custom, int chunks,
                                List<String> members) {}

    /** Every admin territory in the player's dimension, parents each followed by their children. */
    public static List<AdminZoneInfo> adminZones(ServerPlayer player) {
        List<AdminZoneInfo> out = new ArrayList<>();
        if (!loaded() || player == null || !canAdminClaim(player)) return out;
        MinecraftServer server = player.getServer();
        if (server == null) return out;
        ResourceKey<Level> dim = player.level().dimension();
        AdminTerritories zones = AdminTerritories.get(server);
        int inherited = defaultAdminPerms();
        for (AdminTerritories.Territory t : zones.listOrdered(dim)) {
            List<String> members = new ArrayList<>();
            for (UUID id : t.members()) members.add(nameOf(server, id));
            int size = t.isChild() ? zones.chunksOfChild(dim, t.name()).size()
                    : zones.chunksOfParent(dim, t.name()).size();
            boolean custom = t.perms() != AdminPerm.NOT_SET;
            out.add(new AdminZoneInfo(t.name(), t.parent(), t.color(), custom ? t.perms() : inherited,
                    custom, size, members));
        }
        return out;
    }

    /** Flip one permission switch on an admin territory. Returns a status string (empty = success). */
    public static String setAdminPerm(ServerPlayer player, String territory, int permOrdinal, boolean allowed) {
        if (!loaded()) return "Easy Factions is not installed.";
        if (!canAdminClaim(player)) return "Operators only.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";
        if (permOrdinal < 0 || permOrdinal >= AdminPerm.values().length) return "Unknown permission.";

        ResourceKey<Level> dim = player.level().dimension();
        AdminTerritories zones = AdminTerritories.get(server);
        AdminTerritories.Territory t = zones.get(dim, territory);
        if (t == null) return "No admin territory called " + territory + " here.";

        // a never-customised territory starts from what the server config was already doing, so the five
        // switches nobody touched keep behaving the way they did a second ago
        int base = t.perms() == AdminPerm.NOT_SET ? defaultAdminPerms() : t.perms();
        zones.setPerms(dim, t.name(), AdminPerm.with(base, AdminPerm.values()[permOrdinal], allowed));
        return "";
    }

    /** Trust (or stop trusting) a player in an admin territory, by name. Empty return = success. */
    public static String setAdminMember(ServerPlayer player, String territory, String targetName, boolean add) {
        if (!loaded()) return "Easy Factions is not installed.";
        if (!canAdminClaim(player)) return "Operators only.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";
        if (targetName == null || targetName.isBlank()) return "Type a player name first.";

        ResourceKey<Level> dim = player.level().dimension();
        AdminTerritories zones = AdminTerritories.get(server);
        if (zones.get(dim, territory) == null) return "No admin territory called " + territory + " here.";

        UUID id = resolvePlayer(server, targetName);
        if (id == null) return "Never seen a player called " + targetName + ".";
        if (add) zones.addMember(dim, territory, id);
        else zones.removeMember(dim, territory, id);
        return "";
    }

    /** A player's UUID from their name: online first, then the server's profile cache for offline players. */
    private static UUID resolvePlayer(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        if (server.getProfileCache() != null) {
            var profile = server.getProfileCache().get(name);
            if (profile.isPresent()) return profile.get().getId();
        }
        return null;
    }

    /**
     * Paint a child plot: put {@code add} inside the child called {@code childName} and take {@code remove}
     * back out of it. Every chunk must already be part of the SAME parent territory, which is what keeps a
     * child inside its parent — a chunk the parent does not hold simply cannot be assigned.
     *
     * No Easy Factions claim changes hands here. The chunks stay exactly one admin claim owned by the parent,
     * so nothing outside the Territory Table can tell a child exists.
     */
    public static String commitChild(ServerPlayer player, String childName, List<ChunkPos> add,
                                     List<ChunkPos> remove, int color) {
        if (!loaded()) return "Easy Factions is not installed.";
        if (!canAdminClaim(player)) return "Admin claiming requires operator.";
        MinecraftServer server = player.getServer();
        if (server == null) return "No server.";
        String clean = AdminTerritories.clean(childName);
        if (clean.isEmpty() && !add.isEmpty()) return "Name the plot before painting it.";

        ResourceKey<Level> dim = player.level().dimension();
        AdminTerritories zones = AdminTerritories.get(server);

        for (ChunkPos cp : remove) {
            if (zones.childNameAt(dim, cp.toLong()).equals(clean)) zones.clearChild(dim, cp.toLong());
        }
        if (add.isEmpty()) return "";

        // a plot belongs to exactly one parent: the existing plot's, or the one under the first chunk painted
        AdminTerritories.Territory existing = zones.get(dim, clean);
        String parent = existing != null && existing.isChild()
                ? existing.parent() : zones.parentNameAt(dim, add.get(0).toLong());
        if (parent.isEmpty()) return "Plots can only be drawn inside an admin territory.";
        if (existing != null && !existing.isChild()) return "There is already a territory called " + clean + ".";

        int rgb = color < 0 ? TerritoryConfig.adminDefaultColor() : (color & 0xFFFFFF);
        AdminTerritories.Territory parentZone = zones.get(dim, parent);
        // a new plot starts life with its parent's rules, so an operator only changes what differs
        int perms = existing != null ? existing.perms()
                : (parentZone != null ? parentZone.perms() : AdminPerm.NOT_SET);

        int placed = 0;
        for (ChunkPos cp : add) {
            if (zones.assignChild(dim, cp.toLong(), clean, parent, rgb, perms)) placed++;
        }
        if (placed < add.size()) {
            return placed == 0
                    ? "Those chunks are not inside " + parent + "."
                    : "Only " + placed + " of those chunks are inside " + parent + ".";
        }
        return "";
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
     * The "Buy Claims" button: the faction OWNER buys claims, and placing them on the map is then free.
     *
     * <b>This is the only till.</b> A claim used to be charged for twice, once here for the capacity and
     * again at the map for the chunk, which is exactly the report this answers: money left the buyer's
     * balance when they put a claim down rather than when they bought it. What is bought is permanent
     * ceiling, so land released or lost comes back as a slot rather than as a repayment.
     *
     * The price climbs with how many claims the faction has already bought (see the chunkprice config
     * block), so a wide border is paid for in rising instalments rather than at one flat rate.
     *
     * <b>Emeralds are a substitute for an economy mod, never a second currency alongside one.</b> On a
     * server running SDM Economy the price is in SDM and emeralds are not accepted at all; emeralds are the
     * price only where SDM is absent. {@link ClaimEconomy} is what holds that rule.
     *
     * Server-authoritative: detects the buyer's funds and consumes them here, never trusting the client.
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

        int amount = TerritoryConfig.claimsPerPurchase();
        PurchasedClaims purchased = PurchasedClaims.get(server);
        long price = TerritoryConfig.purchaseRun(purchased.getBonus(f.getName()), amount);
        if (!ClaimEconomy.charge(player, price)) {
            return "You cannot afford this. " + amount + " claims cost "
                    + ClaimEconomy.describe(player, price) + " and you have "
                    + ClaimEconomy.describe(player, ClaimEconomy.balance(player)) + ".";
        }

        int newBonus = purchased.addBonus(f.getName(), amount);
        int newCap = factionCapFor(server, f);
        return "Bought " + amount + " claims for " + ClaimEconomy.describe(player, price)
                + ". Faction cap is now " + newCap + " (+" + newBonus + " purchased)."
                + " Placing them on the map costs nothing.";
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
        // everything else (land revert, bought slots, war debts) is handled on FactionDisbandEvent, which
        // Easy Factions also posts when an owner simply leaves - both routes must clean up identically
        fsm.disbandFaction(f.getName(), server);
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
        // admin land announces the parent territory, never a child plot inside it
        String parent = AdminTerritories.get(server).parentNameAt(dim, pos.toLong());
        return parent.isEmpty() ? "Admin" : parent;
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
        AdminTerritories adminZones = AdminTerritories.get(server);
        Map<Long, String> labelByChunk = new HashMap<>();
        for (int j = -radius; j <= radius; j++) {
            for (int i = -radius; i <= radius; i++) {
                ChunkPos cp = new ChunkPos(center.x + i, center.z + j);
                if (!cm.isClaimed(dim, cp)) continue;
                ClaimData d = cm.getClaim(dim, cp);
                if (d == null) continue;
                String label;
                if (d.type == ClaimType.ADMIN) {
                    // the floating map is a WORLD map: it names the parent territory and never its children
                    String parent = adminZones.parentNameAt(dim, ChunkPos.asLong(cp.x, cp.z));
                    label = parent.isEmpty() ? "Admin" : parent;
                } else {
                    label = claimLabel(server, names, d);
                }
                labelByChunk.put(ChunkPos.asLong(cp.x, cp.z), label);
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
