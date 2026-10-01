package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

public class FactionCores extends SavedData {

    public record Core(String dimension, long pos) {}

    private static final String FILE = "holdfast_faction_cores";

    private final Map<String, Core> cores = new HashMap<>();
    private final Map<String, Long> nextDue = new HashMap<>();
    private final Map<String, Long> graceUntil = new HashMap<>();

    public static FactionCores get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactionCores::new, FactionCores::load), FILE);
    }

    public Core getCore(String faction) {
        return faction == null ? null : cores.get(faction);
    }

    public void setCore(String faction, String dimension, long pos) {
        if (faction == null) return;
        cores.put(faction, new Core(dimension, pos));
        setDirty();
    }

    public void clearCore(String faction) {
        if (faction != null && cores.remove(faction) != null) setDirty();
    }

    public void clearCoreAt(String dimension, long pos) {
        if (cores.values().removeIf(c -> c.pos() == pos && c.dimension().equals(dimension))) setDirty();
    }

    public String factionAt(String dimension, long pos) {
        for (Map.Entry<String, Core> e : cores.entrySet()) {
            if (e.getValue().pos() == pos && e.getValue().dimension().equals(dimension)) return e.getKey();
        }
        return null;
    }

    public long getNextDue(String faction) {
        return faction == null ? 0L : nextDue.getOrDefault(faction, 0L);
    }

    public void setNextDue(String faction, long tick) {
        if (faction == null) return;
        nextDue.put(faction, tick);
        setDirty();
    }

    public void clearDue(String faction) {
        if (faction == null) return;
        boolean changed = nextDue.remove(faction) != null;
        changed |= graceUntil.remove(faction) != null;
        if (changed) setDirty();
    }

    public long getGraceUntil(String faction) {
        return faction == null ? 0L : graceUntil.getOrDefault(faction, 0L);
    }

    public void setGraceUntil(String faction, long tick) {
        if (faction == null) return;
        if (tick == 0L) {
            if (graceUntil.remove(faction) != null) setDirty();
        } else {
            graceUntil.put(faction, tick);
            setDirty();
        }
    }

    public void clear(String faction) {
        if (faction == null) return;
        boolean changed = cores.remove(faction) != null;
        changed |= nextDue.remove(faction) != null;
        changed |= graceUntil.remove(faction) != null;
        if (changed) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag coreTag = new CompoundTag();
        cores.forEach((faction, core) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("dim", core.dimension());
            entry.putLong("pos", core.pos());
            coreTag.put(faction, entry);
        });
        tag.put("cores", coreTag);
        CompoundTag dueTag = new CompoundTag();
        nextDue.forEach(dueTag::putLong);
        tag.put("due", dueTag);
        CompoundTag graceTag = new CompoundTag();
        graceUntil.forEach(graceTag::putLong);
        tag.put("grace", graceTag);
        return tag;
    }

    public static FactionCores load(CompoundTag tag, HolderLookup.Provider registries) {
        FactionCores data = new FactionCores();
        CompoundTag coreTag = tag.getCompound("cores");
        for (String faction : coreTag.getAllKeys()) {
            CompoundTag entry = coreTag.getCompound(faction);
            data.cores.put(faction, new Core(entry.getString("dim"), entry.getLong("pos")));
        }
        CompoundTag dueTag = tag.getCompound("due");
        for (String faction : dueTag.getAllKeys()) data.nextDue.put(faction, dueTag.getLong(faction));
        CompoundTag graceTag = tag.getCompound("grace");
        for (String faction : graceTag.getAllKeys()) data.graceUntil.put(faction, graceTag.getLong(faction));
        return data;
    }
}
