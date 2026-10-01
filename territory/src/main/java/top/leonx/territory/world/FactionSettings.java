package top.leonx.territory.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FactionSettings extends SavedData {

    public static final int MEMBERS_ONLY = 0;
    public static final int ALLIES = 1;
    public static final int EVERYONE = 2;

    public static final int DEPOSIT_MEMBERS = 0;
    public static final int DEPOSIT_OFFICERS = 1;
    public static final int DEPOSIT_OWNER = 2;

    private static final String FILE = "holdfast_faction_settings";

    private final Map<String, int[]> levels = new HashMap<>();
    private final Map<String, Map<UUID, Long>> contributions = new HashMap<>();

    public static FactionSettings get(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactionSettings::new, FactionSettings::load), FILE);
    }

    private int[] slot(String faction) {
        return levels.computeIfAbsent(faction, k -> new int[3]);
    }

    public int doors(String faction) {
        int[] s = faction == null ? null : levels.get(faction);
        return s == null ? MEMBERS_ONLY : s[0];
    }

    public int utility(String faction) {
        int[] s = faction == null ? null : levels.get(faction);
        return s == null ? MEMBERS_ONLY : s[1];
    }

    public int deposit(String faction) {
        int[] s = faction == null ? null : levels.get(faction);
        return s == null ? DEPOSIT_MEMBERS : s[2];
    }

    public void setDoors(String faction, int level) {
        if (faction == null) return;
        slot(faction)[0] = Math.max(MEMBERS_ONLY, Math.min(EVERYONE, level));
        setDirty();
    }

    public void setUtility(String faction, int level) {
        if (faction == null) return;
        slot(faction)[1] = Math.max(MEMBERS_ONLY, Math.min(EVERYONE, level));
        setDirty();
    }

    public void setDeposit(String faction, int level) {
        if (faction == null) return;
        slot(faction)[2] = Math.max(DEPOSIT_MEMBERS, Math.min(DEPOSIT_OWNER, level));
        setDirty();
    }

    public void addContribution(String faction, UUID player, long value) {
        if (faction == null || player == null || value <= 0L) return;
        contributions.computeIfAbsent(faction, k -> new HashMap<>()).merge(player, value, Long::sum);
        setDirty();
    }

    public Map<UUID, Long> contributions(String faction) {
        Map<UUID, Long> m = faction == null ? null : contributions.get(faction);
        return m == null ? Map.of() : Collections.unmodifiableMap(m);
    }

    public void clear(String faction) {
        if (faction == null) return;
        boolean changed = levels.remove(faction) != null;
        changed |= contributions.remove(faction) != null;
        if (changed) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag levelTag = new CompoundTag();
        levels.forEach((faction, s) -> levelTag.putIntArray(faction, s));
        tag.put("levels", levelTag);
        CompoundTag contribTag = new CompoundTag();
        contributions.forEach((faction, byPlayer) -> {
            CompoundTag entry = new CompoundTag();
            byPlayer.forEach((id, value) -> entry.putLong(id.toString(), value));
            contribTag.put(faction, entry);
        });
        tag.put("contributions", contribTag);
        return tag;
    }

    public static FactionSettings load(CompoundTag tag, HolderLookup.Provider registries) {
        FactionSettings data = new FactionSettings();
        CompoundTag levelTag = tag.getCompound("levels");
        for (String faction : levelTag.getAllKeys()) {
            int[] s = levelTag.getIntArray(faction);
            int[] fixed = new int[3];
            System.arraycopy(s, 0, fixed, 0, Math.min(3, s.length));
            data.levels.put(faction, fixed);
        }
        CompoundTag contribTag = tag.getCompound("contributions");
        for (String faction : contribTag.getAllKeys()) {
            CompoundTag entry = contribTag.getCompound(faction);
            Map<UUID, Long> byPlayer = new HashMap<>();
            for (String id : entry.getAllKeys()) {
                try {
                    byPlayer.put(UUID.fromString(id), entry.getLong(id));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (!byPlayer.isEmpty()) data.contributions.put(faction, byPlayer);
        }
        return data;
    }
}
