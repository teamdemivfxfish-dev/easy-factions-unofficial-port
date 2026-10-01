package top.leonx.territory.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;

public class AdminTerritories extends SavedData {
   private static final String FILE = "holdfast_admin_zones";
   public static final int MAX_NAME = 48;
   private final Map<String, Map<String, AdminTerritories.Territory>> territories = new HashMap<>();
   private final Map<String, Map<Long, String>> chunkAt = new HashMap<>();

   public static AdminTerritories get(MinecraftServer server) {
      ServerLevel level = server.overworld();
      return level.getDataStorage().computeIfAbsent(new Factory<>(AdminTerritories::new, AdminTerritories::load), FILE);
   }

   private static String key(ResourceKey<Level> dim) {
      return dim.location().toString();
   }

   public static String clean(String name) {
      String s = name == null ? "" : name.strip();
      return s.length() > 48 ? s.substring(0, 48) : s;
   }

   public AdminTerritories.Territory get(ResourceKey<Level> dim, String name) {
      Map<String, AdminTerritories.Territory> dimTerritories = this.territories.get(key(dim));
      return dimTerritories != null ? dimTerritories.get(clean(name)) : null;
   }

   public List<AdminTerritories.Territory> list(ResourceKey<Level> dim) {
      Map<String, AdminTerritories.Territory> dimTerritories = this.territories.get(key(dim));
      if (dimTerritories == null) {
         return List.of();
      } else {
         List<AdminTerritories.Territory> out = new ArrayList<>(dimTerritories.values());
         out.sort(Comparator.comparing(AdminTerritories.Territory::name, String.CASE_INSENSITIVE_ORDER));
         return out;
      }
   }

   public String nameAt(ResourceKey<Level> dim, long chunk) {
      Map<Long, String> map = this.chunkAt.get(key(dim));
      String name = map != null ? map.get(chunk) : null;
      return name != null ? name : "";
   }

   public AdminTerritories.Territory governing(ResourceKey<Level> dim, long chunk) {
      String name = this.nameAt(dim, chunk);
      return name.isEmpty() ? null : this.get(dim, name);
   }

   public boolean isTrusted(ResourceKey<Level> dim, long chunk, UUID player) {
      if (player == null) {
         return false;
      } else {
         AdminTerritories.Territory t = this.governing(dim, chunk);
         return t != null && t.members().contains(player);
      }
   }

   public boolean isWarzoneAt(ResourceKey<Level> dim, long chunk) {
      AdminTerritories.Territory t = this.governing(dim, chunk);
      return t != null && t.warzone();
   }

   public Set<Long> chunksOf(ResourceKey<Level> dim, String name) {
      Map<Long, String> map = this.chunkAt.get(key(dim));
      if (map == null) {
         return Set.of();
      } else {
         String want = clean(name);
         Set<Long> out = new HashSet<>();
         map.forEach((chunk, owner) -> {
            if (owner.equals(want)) {
               out.add(chunk);
            }
         });
         return out;
      }
   }

   public AdminTerritories.Territory ensure(ResourceKey<Level> dim, String name, int color, int perms, boolean warzone) {
      String clean = clean(name);
      Map<String, AdminTerritories.Territory> dimTerritories = this.territories.computeIfAbsent(key(dim), k -> new HashMap<>());
      AdminTerritories.Territory existing = dimTerritories.get(clean);
      if (existing != null) {
         return existing;
      } else {
         AdminTerritories.Territory made = new AdminTerritories.Territory(clean, color & 16777215, perms, Set.of(), warzone);
         dimTerritories.put(clean, made);
         this.setDirty();
         return made;
      }
   }

   private void put(ResourceKey<Level> dim, AdminTerritories.Territory t) {
      this.territories.computeIfAbsent(key(dim), k -> new HashMap<>()).put(t.name(), t);
      this.setDirty();
   }

   public void assign(ResourceKey<Level> dim, long chunk, String name, int color, int perms, boolean warzone) {
      String clean = clean(name);
      this.ensure(dim, clean, color, perms, warzone);
      this.chunkAt.computeIfAbsent(key(dim), k -> new HashMap<>()).put(chunk, clean);
      this.setDirty();
   }

   public void clearChunk(ResourceKey<Level> dim, long chunk) {
      Map<Long, String> map = this.chunkAt.get(key(dim));
      if (map != null) {
         String removed = map.remove(chunk);
         if (removed != null) {
            if (map.isEmpty()) {
               this.chunkAt.remove(key(dim));
            }

            if (this.chunksOf(dim, removed).isEmpty()) {
               this.removeTerritory(dim, removed);
            }

            this.setDirty();
         }
      }
   }

   private void removeTerritory(ResourceKey<Level> dim, String name) {
      Map<String, AdminTerritories.Territory> dimTerritories = this.territories.get(key(dim));
      if (dimTerritories != null) {
         if (dimTerritories.remove(clean(name)) != null) {
            if (dimTerritories.isEmpty()) {
               this.territories.remove(key(dim));
            }

            this.setDirty();
         }
      }
   }

   public boolean setPerms(ResourceKey<Level> dim, String name, int perms) {
      AdminTerritories.Territory t = this.get(dim, name);
      if (t == null) {
         return false;
      } else {
         this.put(dim, t.withPerms(perms));
         return true;
      }
   }

   public boolean addMember(ResourceKey<Level> dim, String name, UUID player) {
      AdminTerritories.Territory t = this.get(dim, name);
      if (t != null && player != null) {
         Set<UUID> next = new LinkedHashSet<>(t.members());
         if (!next.add(player)) {
            return true;
         } else {
            this.put(dim, t.withMembers(next));
            return true;
         }
      } else {
         return false;
      }
   }

   public boolean removeMember(ResourceKey<Level> dim, String name, UUID player) {
      AdminTerritories.Territory t = this.get(dim, name);
      if (t != null && player != null) {
         Set<UUID> next = new LinkedHashSet<>(t.members());
         if (!next.remove(player)) {
            return true;
         } else {
            this.put(dim, t.withMembers(next));
            return true;
         }
      } else {
         return false;
      }
   }

   public CompoundTag save(CompoundTag tag, Provider registries) {
      CompoundTag dims = new CompoundTag();

      for (Entry<String, Map<String, AdminTerritories.Territory>> dimEntry : this.territories.entrySet()) {
         CompoundTag dimTag = new CompoundTag();
         CompoundTag defs = new CompoundTag();

         for (AdminTerritories.Territory t : dimEntry.getValue().values()) {
            CompoundTag def = new CompoundTag();
            def.putInt("color", t.color());
            def.putInt("perms", t.perms());
            def.putBoolean("warzone", t.warzone());
            ListTag members = new ListTag();

            for (UUID id : t.members()) {
               members.add(StringTag.valueOf(id.toString()));
            }

            def.put("members", members);
            defs.put(t.name(), def);
         }

         dimTag.put("territories", defs);
         dimTag.put("chunks", saveChunkMap(this.chunkAt.get(dimEntry.getKey())));
         dims.put(dimEntry.getKey(), dimTag);
      }

      tag.put("dims", dims);
      tag.putInt("version", 3);
      return tag;
   }

   private static CompoundTag saveChunkMap(Map<Long, String> map) {
      CompoundTag out = new CompoundTag();
      if (map != null) {
         map.forEach((chunk, name) -> out.putString(Long.toString(chunk), name));
      }

      return out;
   }

   public static AdminTerritories load(CompoundTag tag, Provider registries) {
      AdminTerritories data = new AdminTerritories();
      CompoundTag dims = tag.getCompound("dims");

      for (String dim : dims.getAllKeys()) {
         CompoundTag dimTag = dims.getCompound(dim);
         Map<String, AdminTerritories.Territory> defs = new HashMap<>();
         CompoundTag defsTag = dimTag.getCompound("territories");

         for (String name : defsTag.getAllKeys()) {
            CompoundTag def = defsTag.getCompound(name);
            if (!def.getString("parent").isEmpty()) {
               continue;
            }

            Set<UUID> members = new LinkedHashSet<>();
            ListTag list = def.getList("members", 8);

            for (int i = 0; i < list.size(); i++) {
               try {
                  members.add(UUID.fromString(list.getString(i)));
               } catch (IllegalArgumentException var12) {
               }
            }

            defs.put(
               name,
               new AdminTerritories.Territory(name, def.getInt("color"), def.getInt("perms"), Collections.unmodifiableSet(members), def.getBoolean("warzone"))
            );
         }

         if (!defs.isEmpty()) {
            data.territories.put(dim, defs);
         }

         CompoundTag chunks = dimTag.contains("chunks") ? dimTag.getCompound("chunks") : dimTag.getCompound("parentAt");
         loadChunkMap(chunks, data.chunkAt, dim, defs);
      }

      return data;
   }

   private static void loadChunkMap(CompoundTag src, Map<String, Map<Long, String>> dest, String dim, Map<String, AdminTerritories.Territory> defs) {
      Map<Long, String> map = new HashMap<>();

      for (String chunk : src.getAllKeys()) {
         try {
            String owner = src.getString(chunk);
            if (defs.containsKey(owner)) {
               map.put(Long.parseLong(chunk), owner);
            }
         } catch (NumberFormatException var8) {
         }
      }

      if (!map.isEmpty()) {
         dest.put(dim, map);
      }
   }

   public static record Territory(String name, int color, int perms, Set<UUID> members, boolean warzone) {
      public AdminTerritories.Territory withPerms(int mask) {
         return new AdminTerritories.Territory(this.name, this.color, mask, this.members, this.warzone);
      }

      public AdminTerritories.Territory withMembers(Set<UUID> next) {
         return new AdminTerritories.Territory(this.name, this.color, this.perms, Set.copyOf(next), this.warzone);
      }
   }
}
