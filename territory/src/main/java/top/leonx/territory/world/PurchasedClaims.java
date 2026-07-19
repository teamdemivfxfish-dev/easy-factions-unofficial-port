package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Extra faction claim slots bought with the "Buy Claims" button in the Faction tab. A faction's effective
 * claim cap is its Easy Factions cap (base + perMember * members) PLUS the bonus stored here. Keyed by
 * faction NAME (the same key Easy Factions uses to own faction claims), persisted in the overworld's data
 * storage. Mirrors {@link TerritoryNames}.
 */
public class PurchasedClaims extends SavedData {

    private static final String FILE = "territory_purchased_claims";

    private final Map<String, Integer> bonus = new HashMap<>();

    public static PurchasedClaims get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PurchasedClaims::new, PurchasedClaims::load), FILE);
    }

    /** Bought bonus claim slots for {@code factionName} (0 if none). */
    public int getBonus(String factionName) {
        if (factionName == null) return 0;
        return bonus.getOrDefault(factionName, 0);
    }

    /** Add {@code amount} bonus slots to a faction (clamped at 0). Returns the new total. */
    public int addBonus(String factionName, int amount) {
        if (factionName == null) return 0;
        int next = Math.max(0, getBonus(factionName) + amount);
        if (next == 0) bonus.remove(factionName);
        else bonus.put(factionName, next);
        setDirty();
        return next;
    }

    /** Drop a faction's record entirely (call when a faction disbands so the name can't leak slots). */
    public void clear(String factionName) {
        if (factionName != null && bonus.remove(factionName) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag map = new CompoundTag();
        bonus.forEach(map::putInt);
        tag.put("bonus", map);
        return tag;
    }

    public static PurchasedClaims load(CompoundTag tag, HolderLookup.Provider registries) {
        PurchasedClaims data = new PurchasedClaims();
        CompoundTag map = tag.getCompound("bonus");
        for (String key : map.getAllKeys()) data.bonus.put(key, map.getInt(key));
        return data;
    }
}
