package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Personal (CORE) claim capacity a PLAYER has lost to being killed, keyed by player UUID.
 *
 * The faction version of this is {@link ClaimPenalties}; this is the same idea one level down, so a player
 * with no faction is still playing the same game. Their effective personal cap is
 * {@code Easy Factions coreChunks - penalty}, and every time an enemy kills them the penalty grows by one,
 * so the ceiling drops and — once it falls below what they actually hold — the chunk nearest where they died
 * comes off the map. A player sitting under their cap simply loses a slot they were not using yet, which is
 * the "unless you still have claims in stock" rule.
 *
 * The penalty REGENERATES one slot at a time on a timer ({@code personalRegenSeconds}), so a bad night costs
 * you your outposts for a while, not permanently. Left alone, everyone climbs back to the full nine.
 */
public class PersonalPenalties extends SavedData {

    private static final String FILE = "territory_personal_penalties";

    private final Map<UUID, Integer> penalty = new HashMap<>();

    /** Server ticks counted since the last regeneration step. Persisted so a restart can't reset the timer. */
    private int regenTicks;

    public static PersonalPenalties get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PersonalPenalties::new, PersonalPenalties::load), FILE);
    }

    /** Personal claim slots {@code player} is currently down by (0 if none). */
    public int getPenalty(UUID player) {
        if (player == null) return 0;
        return penalty.getOrDefault(player, 0);
    }

    /**
     * Add {@code amount} lost slots, clamped to {@code [0, max]} so a player can be ground down to zero
     * personal claims but never past it (an unbounded penalty would take hours to work off and would read as
     * a permanent ban from claiming).
     *
     * @return how much penalty was ACTUALLY applied
     */
    public int addPenalty(UUID player, int amount, int max) {
        if (player == null || amount <= 0) return 0;
        int before = getPenalty(player);
        int after = Math.min(Math.max(0, max), before + amount);
        if (after == before) return 0;
        if (after == 0) penalty.remove(player);
        else penalty.put(player, after);
        setDirty();
        return after - before;
    }

    /** Wipe a player's debt outright (used when their land changes hands wholesale, e.g. founding a faction). */
    public void clear(UUID player) {
        if (player != null && penalty.remove(player) != null) setDirty();
    }

    /**
     * Advance the regeneration timer by one server tick and, once {@code intervalTicks} have passed, refund
     * one lost slot to every penalised player.
     *
     * @return true if a regeneration step actually happened
     */
    public boolean regenAll(int intervalTicks) {
        if (intervalTicks <= 0 || penalty.isEmpty()) return false;
        if (++regenTicks < intervalTicks) {
            setDirty();
            return false;
        }
        regenTicks = 0;
        List<UUID> emptied = new ArrayList<>();
        penalty.replaceAll((player, value) -> value - 1);
        penalty.forEach((player, value) -> {
            if (value <= 0) emptied.add(player);
        });
        emptied.forEach(penalty::remove);
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag map = new CompoundTag();
        penalty.forEach((id, value) -> map.putInt(id.toString(), value));
        tag.put("penalty", map);
        tag.putInt("regenTicks", regenTicks);
        return tag;
    }

    public static PersonalPenalties load(CompoundTag tag, HolderLookup.Provider registries) {
        PersonalPenalties data = new PersonalPenalties();
        CompoundTag map = tag.getCompound("penalty");
        for (String key : map.getAllKeys()) {
            try {
                data.penalty.put(UUID.fromString(key), map.getInt(key));
            } catch (IllegalArgumentException ignored) {
                // corrupt key: drop that entry rather than fail the world load
            }
        }
        data.regenTicks = tag.getInt("regenTicks");
        return data;
    }
}
