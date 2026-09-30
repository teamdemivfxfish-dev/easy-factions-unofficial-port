package top.leonx.territory.integration;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.claim.IChunkClaimData;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.permissions.Rank;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Soft-dependency bridge to MineColonies, for the one rule that outranks every claim this mod draws:
 * <b>a colony's chunks answer to the colony.</b>
 *
 * <h2>Why this exists</h2>
 * Easy Factions and MineColonies claim the same chunks in complete ignorance of each other. Nothing stopped
 * a faction painting its claim straight over somebody's colony, and once it had, the colony's own owners
 * were the trespassers: Easy Factions refused them their own land, and this mod's un-cancelling could hand
 * their chests to the faction that had just fenced them in. A colony being older, larger and built by hand
 * counted for nothing. So colony ownership is now checked first and wins outright, both when land is claimed
 * and when somebody reaches for a block inside it.
 *
 * <h2>Why nothing here returns a MineColonies type</h2>
 * Every public method answers in {@code int}, {@code boolean}, {@link String} or a record of those. The JVM
 * resolves the classes a method mentions when that method first runs, so keeping MineColonies types out of
 * every signature and every field is what lets this class load, and {@link #loaded()} answer, on a server
 * that has never heard of MineColonies. The MineColonies-typed work is confined to the private helpers,
 * which nothing calls until {@link #loaded()} has returned true.
 *
 * <h2>What counts as belonging to a colony</h2>
 * Not {@code IPermissions.isColonyMember}, which is a bare {@code players.containsKey} and therefore answers
 * "yes" for somebody the colony has explicitly ranked HOSTILE. The test used here is the player's RANK: the
 * owner, and anyone at a rank that is neither neutral nor hostile. A colony's declared enemy does not get
 * the run of it. {@code getRank(UUID)} answers from the stored player map and falls back to neutral for a
 * stranger, so this works for offline players too, which the overlap sweep needs.
 */
public final class MineColoniesBridge {

    private MineColoniesBridge() {}

    public static final String MODID = "minecolonies";

    private static final Logger LOG = LoggerFactory.getLogger("territory-colonies");

    /** No colony. MineColonies uses 0 as "unowned" in {@code IChunkClaimData.getOwningColony()}. */
    public static final int NO_COLONY = 0;

    /**
     * Set the first time a MineColonies call throws, after which every method here answers "no colony".
     *
     * MineColonies on this server is a snapshot build and its API does move. A version whose signatures have
     * drifted must not take the server down or, far worse, silently start reporting every chunk as free to
     * claim halfway through a sweep: one warning, then this integration stands down as a whole and the mod
     * behaves exactly as it did before colonies were understood at all.
     */
    private static volatile boolean apiBroken = false;

    /** Whether MineColonies is installed. Touches no MineColonies type, so it is always safe to call. */
    public static boolean loaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** Whether colony lookups can be trusted: installed, and no earlier call has blown up. */
    public static boolean usable() {
        return loaded() && !apiBroken;
    }

    /** Whether the integration stood itself down after an API failure, for the startup and diagnose reports. */
    public static boolean brokenApi() {
        return apiBroken;
    }

    /** A colony's identity, flattened so callers never touch a MineColonies class. */
    public record ColonyRef(int id, String name) {
        public String display() {
            return name == null || name.isBlank() ? "colony #" + id : name;
        }
    }

    // ---- chunk ownership -------------------------------------------------------------------------------

    /**
     * The id of the colony that owns {@code pos}, or {@link #NO_COLONY}.
     *
     * Answers from MineColonies' in-memory claim map, which is keyed by chunk and loaded with the save, so
     * this neither loads nor keeps alive the chunk it is asked about. That matters twice over: it is called
     * on the hot path of every block break, and the overlap sweep walks thousands of chunks that are not
     * loaded and must not become loaded.
     */
    public static int owningColony(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos) {
        if (!usable() || server == null || dim == null || pos == null) return NO_COLONY;
        try {
            return owningColonyUnsafe(dim, pos);
        } catch (Throwable t) {
            standDown(t);
            return NO_COLONY;
        }
    }

    /**
     * Every colony with a stake in {@code pos}: the owning colony, plus colonies holding a standing claim
     * through a building that reaches into the chunk.
     *
     * The wider list is what the claiming gate asks, because a chunk a colony's outlying building sits in is
     * still that colony's land in every sense the player cares about, even while MineColonies records the
     * chunk's owner as somebody else or nobody.
     */
    public static List<Integer> claimingColonies(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos) {
        if (!usable() || server == null || dim == null || pos == null) return List.of();
        try {
            return claimingColoniesUnsafe(dim, pos);
        } catch (Throwable t) {
            standDown(t);
            return List.of();
        }
    }

    /** The colony owning {@code pos} with its name resolved, or null when the chunk is not colony land. */
    public static ColonyRef colonyAt(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos) {
        int id = owningColony(server, dim, pos);
        return id == NO_COLONY ? null : new ColonyRef(id, colonyName(server, dim, id));
    }

    /** A colony's name, or a blank string when it cannot be resolved. */
    public static String colonyName(MinecraftServer server, ResourceKey<Level> dim, int colonyId) {
        if (!usable() || colonyId == NO_COLONY || dim == null) return "";
        try {
            IColony colony = colonyById(dim, colonyId);
            return colony == null || colony.getName() == null ? "" : colony.getName();
        } catch (Throwable t) {
            standDown(t);
            return "";
        }
    }

    // ---- membership ------------------------------------------------------------------------------------

    /**
     * Whether {@code uuid} belongs to colony {@code colonyId} closely enough to be left alone inside it.
     *
     * True for the owner and for any rank that is neither neutral nor hostile, so an officer, a friend and a
     * colony's own custom rank all pass while a stranger and a declared enemy do not. Works for an offline
     * player, since it reads the colony's stored roster rather than anything about a live entity.
     */
    public static boolean trusted(MinecraftServer server, ResourceKey<Level> dim, int colonyId, UUID uuid) {
        if (!usable() || uuid == null || colonyId == NO_COLONY || dim == null) return false;
        try {
            return trustedUnsafe(dim, colonyId, uuid);
        } catch (Throwable t) {
            standDown(t);
            return false;
        }
    }

    /**
     * Whether {@code uuid} is trusted by every colony with a stake in {@code pos}.
     *
     * False when the chunk is not colony land at all: the question this answers is "is this person one of
     * the people this colony belongs to", and where there is no colony there is nobody for it to be true of.
     * Callers test {@link #owningColony} first when they need to tell the two apart.
     */
    public static boolean trustedAt(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos, UUID uuid) {
        return trustedInAll(server, dim, claimingColonies(server, dim, pos), uuid);
    }

    /**
     * The same question against a colony list the caller already has.
     *
     * The protection path asks whether a chunk is colony land and who it belongs to for every block broken
     * inside a claim, and there is no reason to look the chunk up twice to answer both halves.
     */
    public static boolean trustedInAll(MinecraftServer server, ResourceKey<Level> dim, List<Integer> colonies,
                                       UUID uuid) {
        if (colonies == null || colonies.isEmpty()) return false;
        for (int id : colonies) {
            if (!trusted(server, dim, id, uuid)) return false;
        }
        return true;
    }

    // ---- the claiming gate -----------------------------------------------------------------------------

    /**
     * The name of the colony that stops {@code claimant} claiming {@code pos}, or null when nothing does.
     *
     * A colony blocks the claim unless the claimant belongs to it. Their own colony is left claimable on
     * purpose: fencing your own town off behind a faction border is the reasonable thing the feature is for,
     * and it is only somebody else's colony that this is protecting.
     */
    public static String claimRefusal(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos, UUID claimant) {
        if (!usable()) return null;
        List<Integer> colonies = claimingColonies(server, dim, pos);
        if (colonies.isEmpty()) return null;
        for (int id : colonies) {
            if (!trusted(server, dim, id, claimant)) {
                String name = colonyName(server, dim, id);
                return name.isBlank() ? "colony #" + id : name;
            }
        }
        return null;
    }

    // ---- the map ---------------------------------------------------------------------------------------

    /**
     * One chunk of colony land as the Territory Table draws it: where it is, which town it belongs to, and
     * whether the player looking at the map is one of that town's own people.
     */
    public record ColonyCell(int x, int z, int colonyId, String name, boolean trusted) {}

    /**
     * Every chunk in a square region that belongs to a colony.
     *
     * <h2>Why the claim map has to show these</h2>
     * Colony land cannot be claimed, and until now the only way to discover that was to drag a selection
     * across it and read the refusal. From the Territory Table a town is invisible: Easy Factions has no
     * idea it is there, so the map draws open ground over somebody's houses. Sending the colony chunks with
     * the claims is what turns "you cannot claim a town you are not part of" from an error message into a
     * border the player can see before they start.
     *
     * The wider question is asked, matching the claiming gate exactly: a chunk an outlying building holds a
     * standing claim on refuses a claim, so it has to be drawn as taken, or the map would be telling the
     * player something the server will not honour.
     *
     * Answers from MineColonies' in-memory chunk index, so a region scan neither loads chunks nor keeps
     * them loaded. Colony names and memberships are resolved once per colony rather than once per chunk.
     */
    public static List<ColonyCell> colonyCellsIn(MinecraftServer server, ResourceKey<Level> dim,
                                                 ChunkPos center, int radius, UUID viewer) {
        List<ColonyCell> out = new ArrayList<>();
        if (!usable() || server == null || dim == null || center == null) return out;
        java.util.Map<Integer, String> names = new java.util.HashMap<>();
        java.util.Map<Integer, Boolean> trust = new java.util.HashMap<>();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                List<Integer> colonies = claimingColonies(server, dim, pos);
                if (colonies.isEmpty()) continue;
                int id = colonies.get(0);
                String name = names.computeIfAbsent(id, k -> colonyName(server, dim, k));
                boolean trusted = viewer != null
                        && trust.computeIfAbsent(id, k -> trusted(server, dim, k, viewer));
                out.add(new ColonyCell(x, z, id, name.isBlank() ? "Colony #" + id : name, trusted));
            }
        }
        return out;
    }

    /**
     * Put one real question to MineColonies at startup and report whether it answered.
     *
     * Compiling against an API is not the same as running against it, and this integration is aimed at a
     * MineColonies SNAPSHOT whose signatures do move between builds. Without this, a moved method would first
     * surface as a claim quietly going through over somebody's town, in a call whose failure is caught and
     * turned into "no colony here" precisely so it cannot crash the server. Asking once, at boot, is what
     * turns that into a line in the log before anybody has lost anything.
     *
     * The answer itself is discarded. What is being tested is that the call resolves and returns.
     */
    public static boolean selfTest(MinecraftServer server) {
        if (!usable() || server == null) return false;
        try {
            claimingColoniesUnsafe(Level.OVERWORLD, new ChunkPos(0, 0));
            colonyById(Level.OVERWORLD, 1);
            return true;
        } catch (Throwable t) {
            standDown(t);
            return false;
        }
    }

    // ---- MineColonies-typed internals ------------------------------------------------------------------
    // Nothing above calls into here until loaded() has answered true, which is what keeps this class
    // loadable on a server without MineColonies.

    private static int owningColonyUnsafe(ResourceKey<Level> dim, ChunkPos pos) {
        IChunkClaimData data = IColonyManager.getInstance().getClaimData(dim, pos);
        return data == null ? NO_COLONY : data.getOwningColony();
    }

    private static List<Integer> claimingColoniesUnsafe(ResourceKey<Level> dim, ChunkPos pos) {
        IChunkClaimData data = IColonyManager.getInstance().getClaimData(dim, pos);
        if (data == null) return List.of();
        Set<Integer> ids = new LinkedHashSet<>();
        int owner = data.getOwningColony();
        if (owner != NO_COLONY) ids.add(owner);
        List<Integer> statics = data.getStaticClaimColonies();
        if (statics != null) {
            for (Integer id : statics) {
                if (id != null && id != NO_COLONY) ids.add(id);
            }
        }
        return ids.isEmpty() ? List.of() : new ArrayList<>(ids);
    }

    private static boolean trustedUnsafe(ResourceKey<Level> dim, int colonyId, UUID uuid) {
        IColony colony = colonyById(dim, colonyId);
        if (colony == null) return false;
        IPermissions perms = colony.getPermissions();
        if (perms == null) return false;
        if (uuid.equals(perms.getOwner())) return true;
        Rank rank = perms.getRank(uuid);
        // getRank falls back to the neutral rank for anyone not on the roster, so a stranger lands here
        return rank != null && !rank.isHostile() && rank.getId() != IPermissions.NEUTRAL_RANK_ID;
    }

    private static IColony colonyById(ResourceKey<Level> dim, int colonyId) {
        return IColonyManager.getInstance().getColonyByDimension(colonyId, dim);
    }

    /** One warning, then this integration is off for the rest of the run rather than answering wrongly. */
    private static void standDown(Throwable t) {
        if (apiBroken) return;
        apiBroken = true;
        LOG.error("MineColonies claim lookup failed; colony priority over faction claims is now DISABLED for "
                + "this run. Claims will not be able to tell colony land apart from open ground. This is "
                + "usually a MineColonies version whose API has moved.", t);
    }
}
