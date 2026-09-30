package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * What players were charged, per chunk, while claiming a chunk cost money. Kept only to give it back.
 *
 * <h2>Why this is still here with nothing writing to it</h2>
 * Land is bought at the Buy Claims button now and placed for nothing, so no new entry is ever recorded.
 * What is recorded is the old rule's takings, sitting in a save file on every world that ran it, and that
 * money belongs to the players it came out of. {@link ClaimPriceMigration} drains this once at server start,
 * puts every owner's total in {@link ClaimRefunds} and leaves the file empty for good.
 *
 * Keyed by dimension and chunk, and holding the OWNER the payment was made on behalf of: a faction NAME for
 * faction land, a player UUID string for a personal claim. Emptying it is what makes this safe to run every
 * boot, since a drained ledger has nothing left to pay out twice.
 */
public class ClaimLedger extends SavedData {

    private static final String FILE = "territory_claim_ledger";

    /** One paid-for chunk: who paid, and how much. {@code owner} is a faction name or a player UUID string. */
    public record Entry(String owner, long price) {}

    private final Map<String, Map<Long, Entry>> byDim = new HashMap<>();

    public static ClaimLedger get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ClaimLedger::new, ClaimLedger::load), FILE);
    }

    /** Whether anything is still recorded as paid. */
    public boolean isEmpty() {
        return byDim.isEmpty();
    }

    /**
     * Everything still recorded, totalled per owner, forgetting all of it in the same breath.
     *
     * Totalled rather than handed back chunk by chunk because a refund is one amount to one party: the
     * chunks it was taken over are already claimed, and the payer does not care which of them cost what.
     */
    public Map<String, Long> drainAll() {
        Map<String, Long> totals = new HashMap<>();
        for (Map<Long, Entry> map : byDim.values()) {
            for (Entry entry : map.values()) {
                if (entry == null || entry.owner() == null || entry.price() <= 0L) continue;
                totals.merge(entry.owner(), entry.price(), Long::sum);
            }
        }
        if (!byDim.isEmpty()) {
            byDim.clear();
            setDirty();
        }
        return totals;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag dims = new CompoundTag();
        byDim.forEach((dim, map) -> {
            ListTag list = new ListTag();
            map.forEach((chunk, entry) -> {
                CompoundTag one = new CompoundTag();
                one.putLong("c", chunk);
                one.putString("o", entry.owner());
                one.putLong("p", entry.price());
                list.add(one);
            });
            dims.put(dim, list);
        });
        tag.put("dims", dims);
        return tag;
    }

    public static ClaimLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        ClaimLedger data = new ClaimLedger();
        CompoundTag dims = tag.getCompound("dims");
        for (String dim : dims.getAllKeys()) {
            ListTag list = dims.getList(dim, Tag.TAG_COMPOUND);
            Map<Long, Entry> map = new HashMap<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                map.put(one.getLong("c"), new Entry(one.getString("o"), one.getLong("p")));
            }
            if (!map.isEmpty()) data.byDim.put(dim, map);
        }
        return data;
    }
}
