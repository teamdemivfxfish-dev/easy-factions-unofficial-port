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

/**
 * Claim capacity a faction has LOST to enemy kills, keyed by faction name.
 *
 * A faction's effective cap is {@code (Easy Factions cap + bought bonus) - penalty}, assembled in
 * {@link top.leonx.territory.integration.EasyFactionsBridge#factionCapFor}. Every time one of a faction's
 * members is killed by a rival, the penalty grows, so the faction's ceiling drops. The killer's faction is
 * paid a share into {@link PurchasedClaims}, which is the same collective pool the Buy Claims button feeds.
 *
 * The penalty REGENERATES: {@link #regenAll(int)} shaves points off every faction on a timer so a faction
 * that gets farmed for one bad night recovers instead of being permanently crippled. That is what keeps
 * this a tug-of-war rather than a death spiral.
 *
 * Mirrors {@link PurchasedClaims}, and is deliberately the opposite sign so the two can never be confused.
 */
public class ClaimPenalties extends SavedData {

    private static final String FILE = "territory_claim_penalties";

    private final Map<String, Integer> penalty = new HashMap<>();

    /** Server ticks counted since the last regeneration step. Persisted so a restart can't reset the timer. */
    private int regenTicks;

    public static ClaimPenalties get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ClaimPenalties::new, ClaimPenalties::load), FILE);
    }

    /** Claim slots {@code factionName} is currently down by (0 if none). */
    public int getPenalty(String factionName) {
        if (factionName == null) return 0;
        return penalty.getOrDefault(factionName, 0);
    }

    /**
     * Add {@code amount} lost slots, clamped to {@code [0, max]}. {@code max} is the faction's natural cap,
     * so a faction can be ground down to zero capacity but never past it (an unbounded penalty would take
     * hours of regeneration to work off and would feel like a permanent ban from claiming).
     *
     * @return how much penalty was ACTUALLY applied, which is what the killer's share is calculated from.
     */
    public int addPenalty(String factionName, int amount, int max) {
        if (factionName == null || amount <= 0) return 0;
        int before = getPenalty(factionName);
        int after = Math.min(Math.max(0, max), before + amount);
        if (after == before) return 0;
        if (after == 0) penalty.remove(factionName);
        else penalty.put(factionName, after);
        setDirty();
        return after - before;
    }

    /** Drop a faction's record entirely (call when a faction disbands so the name can't hold a stale debt). */
    public void clear(String factionName) {
        if (factionName != null && penalty.remove(factionName) != null) setDirty();
    }

    /**
     * Advance the regeneration timer by one server tick and, once {@code intervalTicks} have passed, refund
     * one lost slot to every penalised faction.
     *
     * @return true if a regeneration step actually happened (the caller may want to resync open maps)
     */
    public boolean regenAll(int intervalTicks) {
        if (intervalTicks <= 0 || penalty.isEmpty()) return false;
        if (++regenTicks < intervalTicks) {
            setDirty();
            return false;
        }
        regenTicks = 0;
        List<String> emptied = new ArrayList<>();
        penalty.replaceAll((faction, value) -> value - 1);
        penalty.forEach((faction, value) -> {
            if (value <= 0) emptied.add(faction);
        });
        emptied.forEach(penalty::remove);
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag map = new CompoundTag();
        penalty.forEach(map::putInt);
        tag.put("penalty", map);
        tag.putInt("regenTicks", regenTicks);
        return tag;
    }

    public static ClaimPenalties load(CompoundTag tag, HolderLookup.Provider registries) {
        ClaimPenalties data = new ClaimPenalties();
        CompoundTag map = tag.getCompound("penalty");
        for (String key : map.getAllKeys()) data.penalty.put(key, map.getInt(key));
        data.regenTicks = tag.getInt("regenTicks");
        return data;
    }
}
