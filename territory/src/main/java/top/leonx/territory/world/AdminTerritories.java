package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Name + border colour for ADMIN territories, keyed by dimension and packed chunk long.
 *
 * Easy Factions gives admin claims no identity of their own: it stores the claiming admin's UUID as the
 * claim owner and the map just labels every one of them "Admin". This store lets an admin type a name
 * (and pick a colour) BEFORE painting, so each admin region shows up as "Spawn" / "Arena" / "Event
 * Grounds" instead of a wall of identical purple.
 *
 * WHY THE COLOUR LIVES HERE AND NOT IN EASY FACTIONS: {@code ClaimManager.load()} re-derives every claim's
 * colour by type on world load and force-sets ADMIN claims to {@code ServerConfig.adminClaimColor}. A colour
 * written into the EF claim therefore survives until the next restart and then silently reverts. Ours is
 * authoritative and is applied over EF's value when the map region is built.
 *
 * Mirrors {@link TerritoryNames} (personal claims) and {@link PurchasedClaims} (faction claim slots).
 */
public class AdminTerritories extends SavedData {

    private static final String FILE = "territory_admin_zones";

    /** Longest admin territory name we keep, matching {@link TerritoryNames}' limit. */
    public static final int MAX_NAME = 48;

    /** One admin region's identity. {@code color} is RGB, already masked to 24 bits. */
    public record Zone(String name, int color) {}

    /** dimension id -> packed chunk long -> zone. */
    private final Map<String, Map<Long, Zone>> zones = new HashMap<>();

    public static AdminTerritories get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AdminTerritories::new, AdminTerritories::load), FILE);
    }

    private static String key(ResourceKey<Level> dim) {
        return dim.location().toString();
    }

    /** The zone covering this chunk, or {@code null} if the chunk has no admin identity recorded. */
    public Zone getZone(ResourceKey<Level> dim, long chunk) {
        Map<Long, Zone> dimZones = zones.get(key(dim));
        return dimZones != null ? dimZones.get(chunk) : null;
    }

    /** Record {@code chunk} as part of an admin region called {@code name} drawn in {@code color}. */
    public void setZone(ResourceKey<Level> dim, long chunk, String name, int color) {
        String clean = name == null ? "" : name.strip();
        if (clean.length() > MAX_NAME) clean = clean.substring(0, MAX_NAME);
        zones.computeIfAbsent(key(dim), k -> new HashMap<>()).put(chunk, new Zone(clean, color & 0xFFFFFF));
        setDirty();
    }

    /** Forget a chunk's admin identity. Call whenever an admin chunk is unclaimed so names can't leak. */
    public void clearZone(ResourceKey<Level> dim, long chunk) {
        Map<Long, Zone> dimZones = zones.get(key(dim));
        if (dimZones == null) return;
        if (dimZones.remove(chunk) != null) {
            if (dimZones.isEmpty()) zones.remove(key(dim));
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag dims = new CompoundTag();
        zones.forEach((dim, chunkMap) -> {
            CompoundTag chunks = new CompoundTag();
            chunkMap.forEach((chunk, zone) -> {
                CompoundTag z = new CompoundTag();
                z.putString("name", zone.name());
                z.putInt("color", zone.color());
                chunks.put(Long.toString(chunk), z);
            });
            dims.put(dim, chunks);
        });
        tag.put("zones", dims);
        return tag;
    }

    public static AdminTerritories load(CompoundTag tag, HolderLookup.Provider registries) {
        AdminTerritories data = new AdminTerritories();
        CompoundTag dims = tag.getCompound("zones");
        for (String dim : dims.getAllKeys()) {
            CompoundTag chunks = dims.getCompound(dim);
            Map<Long, Zone> chunkMap = new HashMap<>();
            for (String chunk : chunks.getAllKeys()) {
                try {
                    CompoundTag z = chunks.getCompound(chunk);
                    chunkMap.put(Long.parseLong(chunk), new Zone(z.getString("name"), z.getInt("color")));
                } catch (NumberFormatException ignored) {
                    // a hand-edited or corrupt key: drop that chunk rather than fail the whole world load
                }
            }
            if (!chunkMap.isEmpty()) data.zones.put(dim, chunkMap);
        }
        return data;
    }
}
