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
 * Per-player cosmetic settings for personal (Easy Factions CORE) territories: a NAME (EF doesn't name core
 * claims) and a border COLOUR the player chooses to manage borders. Keyed by player UUID, persisted in the
 * overworld's data storage. Faction territories use the faction's own name + colour instead.
 */
public class TerritoryNames extends SavedData {

    private static final String FILE = "territory_personal_names";
    /** Sentinel meaning "no personal colour set yet" -> fall back to EF's default core colour. */
    public static final int NO_COLOR = Integer.MIN_VALUE;

    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Integer> colors = new HashMap<>();

    public static TerritoryNames get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TerritoryNames::new, TerritoryNames::load), FILE);
    }

    public String getName(UUID id) {
        return names.getOrDefault(id, "");
    }

    public void setName(UUID id, String name) {
        if (name == null || name.isBlank()) {
            names.remove(id);
        } else {
            names.put(id, name.length() > 48 ? name.substring(0, 48) : name);
        }
        setDirty();
    }

    /** @return the player's chosen personal colour, or {@link #NO_COLOR} if unset. */
    public int getColor(UUID id) {
        return colors.getOrDefault(id, NO_COLOR);
    }

    public void setColor(UUID id, int rgb) {
        colors.put(id, rgb & 0xFFFFFF);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag nameMap = new CompoundTag();
        names.forEach((k, v) -> nameMap.putString(k.toString(), v));
        tag.put("names", nameMap);
        CompoundTag colorMap = new CompoundTag();
        colors.forEach((k, v) -> colorMap.putInt(k.toString(), v));
        tag.put("colors", colorMap);
        return tag;
    }

    public static TerritoryNames load(CompoundTag tag, HolderLookup.Provider registries) {
        TerritoryNames data = new TerritoryNames();
        CompoundTag nameMap = tag.getCompound("names");
        for (String key : nameMap.getAllKeys()) {
            try {
                data.names.put(UUID.fromString(key), nameMap.getString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        CompoundTag colorMap = tag.getCompound("colors");
        for (String key : colorMap.getAllKeys()) {
            try {
                data.colors.put(UUID.fromString(key), colorMap.getInt(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return data;
    }
}
