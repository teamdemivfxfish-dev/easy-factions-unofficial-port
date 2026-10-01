package top.leonx.territory.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;

public class TerritoryNames extends SavedData {
   private static final String FILE = "territory_personal_names";
   public static final int NO_COLOR = Integer.MIN_VALUE;
   private final Map<UUID, String> names = new HashMap<>();
   private final Map<UUID, Integer> colors = new HashMap<>();

   public static TerritoryNames get(MinecraftServer server) {
      ServerLevel level = server.overworld();
      return level.getDataStorage().computeIfAbsent(new Factory<>(TerritoryNames::new, TerritoryNames::load), "territory_personal_names");
   }

   public String getName(UUID id) {
      return this.names.getOrDefault(id, "");
   }

   public void setName(UUID id, String name) {
      if (name != null && !name.isBlank()) {
         this.names.put(id, name.length() > 48 ? name.substring(0, 48) : name);
      } else {
         this.names.remove(id);
      }

      this.setDirty();
   }

   public int getColor(UUID id) {
      return this.colors.getOrDefault(id, Integer.MIN_VALUE);
   }

   public void setColor(UUID id, int rgb) {
      this.colors.put(id, rgb & 16777215);
      this.setDirty();
   }

   public void sanitizeColors() {
      boolean changed = false;

      for (Map.Entry<UUID, Integer> e : this.colors.entrySet()) {
         if (ZoneColor.isReserved(e.getValue())) {
            e.setValue(ZoneColor.safe(e.getValue()));
            changed = true;
         }
      }

      if (changed) {
         this.setDirty();
      }
   }

   public CompoundTag save(CompoundTag tag, Provider registries) {
      CompoundTag nameMap = new CompoundTag();
      this.names.forEach((k, v) -> nameMap.putString(k.toString(), v));
      tag.put("names", nameMap);
      CompoundTag colorMap = new CompoundTag();
      this.colors.forEach((k, v) -> colorMap.putInt(k.toString(), v));
      tag.put("colors", colorMap);
      return tag;
   }

   public static TerritoryNames load(CompoundTag tag, Provider registries) {
      TerritoryNames data = new TerritoryNames();
      CompoundTag nameMap = tag.getCompound("names");

      for (String key : nameMap.getAllKeys()) {
         try {
            data.names.put(UUID.fromString(key), nameMap.getString(key));
         } catch (IllegalArgumentException var9) {
         }
      }

      CompoundTag colorMap = tag.getCompound("colors");

      for (String key : colorMap.getAllKeys()) {
         try {
            data.colors.put(UUID.fromString(key), colorMap.getInt(key));
         } catch (IllegalArgumentException var8) {
         }
      }

      return data;
   }
}
