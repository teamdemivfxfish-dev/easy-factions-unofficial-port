package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Money owed back for land that stopped being somebody's, waiting to be handed over.
 *
 * <h2>Why it waits</h2>
 * The land is taken back by a colony that grew, by a sweep at server start, or by a faction disbanding, and
 * none of those wait for the people affected to be online. There is nowhere to put money at that moment: an
 * economy pays a player, and the player is asleep. So the amount is parked here under the party it belongs
 * to and paid out the next time they can receive it. Nobody has to be told to log in at the right moment,
 * and nothing is lost if a whole faction is offline for a week.
 *
 * <h2>Who it belongs to</h2>
 * A faction's land is the faction's, so its refunds are held under the faction NAME and paid to its owner,
 * who is the only one who could have bought claims for it in the first place. A personal claim's refund is
 * held under the claimant's UUID and paid to them. The two key spaces are kept apart by a prefix, so a
 * player named after a faction cannot collect its money.
 */
public class ClaimRefunds extends SavedData {

    private static final String FILE = "territory_refunds";

    private final Map<String, Long> owed = new HashMap<>();

    public static ClaimRefunds get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ClaimRefunds::new, ClaimRefunds::load), FILE);
    }

    /** The pool a faction's refunds are held in, collectable by its owner. */
    public static String factionKey(String factionName) {
        return "f:" + factionName;
    }

    /** The pool one player's refunds are held in. */
    public static String playerKey(UUID player) {
        return "p:" + player;
    }

    /** Whether a key names a faction pool rather than a player one. */
    public static boolean isFactionKey(String key) {
        return key != null && key.startsWith("f:");
    }

    /** The faction or player name behind a key, for messages. */
    public static String nameOf(String key) {
        return key == null || key.length() < 2 ? "" : key.substring(2);
    }

    public long amount(String key) {
        if (key == null) return 0L;
        return owed.getOrDefault(key, 0L);
    }

    /** Add to a pool. Returns the new total. */
    public long add(String key, long amount) {
        if (key == null || amount <= 0L) return amount(key);
        long next = amount(key) + amount;
        owed.put(key, next);
        setDirty();
        return next;
    }

    /** Hand over everything in a pool, emptying it. Returns what was in it. */
    public long take(String key) {
        if (key == null) return 0L;
        Long had = owed.remove(key);
        if (had == null) return 0L;
        setDirty();
        return had;
    }

    /**
     * Take part of a pool, leaving the rest.
     *
     * The emerald fallback pays in whole emeralds, so a pool worth two and a half emeralds pays two and
     * keeps the remainder rather than rounding it away from the player it belongs to.
     */
    public long takeUpTo(String key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long had = amount(key);
        long paid = Math.min(had, amount);
        if (paid <= 0L) return 0L;
        long left = had - paid;
        if (left <= 0L) owed.remove(key);
        else owed.put(key, left);
        setDirty();
        return paid;
    }

    /**
     * Move a pool to another key.
     *
     * A disbanded faction has no owner to collect from it and its name can be taken by somebody else, so
     * what it was owed goes to the person who was its owner instead of sitting under a name that no longer
     * means anything.
     */
    public void transfer(String fromKey, String toKey) {
        long had = take(fromKey);
        if (had > 0L) add(toKey, had);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag map = new CompoundTag();
        owed.forEach(map::putLong);
        tag.put("owed", map);
        return tag;
    }

    public static ClaimRefunds load(CompoundTag tag, HolderLookup.Provider registries) {
        ClaimRefunds data = new ClaimRefunds();
        CompoundTag map = tag.getCompound("owed");
        for (String key : map.getAllKeys()) {
            long value = map.getLong(key);
            if (value > 0L) data.owed.put(key, value);
        }
        return data;
    }
}
