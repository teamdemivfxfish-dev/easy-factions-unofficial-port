package top.leonx.territory.integration;

import dev.xmat5.holdfast.common.RelationshipStatus;
import dev.xmat5.holdfast.server.ServerConfig;
import dev.xmat5.holdfast.server.alliance.Alliance;
import dev.xmat5.holdfast.server.alliance.AllianceStateManager;
import dev.xmat5.holdfast.server.claims.ChunkInteractionType;
import dev.xmat5.holdfast.server.claims.ClaimManager;
import dev.xmat5.holdfast.server.claims.model.ClaimData;
import dev.xmat5.holdfast.server.claims.model.ClaimType;
import dev.xmat5.holdfast.server.faction.Faction;
import dev.xmat5.holdfast.server.faction.FactionStateManager;
import com.mojang.authlib.GameProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.world.AdminPerm;
import top.leonx.territory.world.AdminTerritories;
import top.leonx.territory.world.FactionCores;
import top.leonx.territory.world.FactionSettings;
import top.leonx.territory.world.Interaction;
import top.leonx.territory.world.TerritoryNames;
import top.leonx.territory.world.ZoneColor;

public final class FactionsBridge {
   public static final String MODID = "holdfast_factions";
   public static final int KIND_MINE_CORE = 0;
   public static final int KIND_MINE_FACTION = 1;
   public static final int KIND_OTHER = 2;
   public static final int KIND_ADMIN = 3;
   public static final int TYPE_PERSONAL = 0;
   public static final int TYPE_FACTION = 1;
   public static final int TYPE_ADMIN = 2;
   public static final int TYPE_WARZONE = 3;
   public static final int WARZONE_COLOR = ZoneColor.WARZONE;
   public static final int SAFEZONE_COLOR = ZoneColor.SAFEZONE;
   public static final String WORLD_GUARD = "jakesworldguard";

   private FactionsBridge() {
   }

   public static boolean loaded() {
      return ModList.get().isLoaded("holdfast_factions");
   }

   public static boolean worldGuardLoaded() {
      return ModList.get().isLoaded(WORLD_GUARD);
   }

   public static boolean handsOff(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos) {
      if (worldGuardLoaded() && server != null && dim != null && pos != null) {
         AdminTerritories.Territory t = AdminTerritories.get(server).governing(dim, ChunkPos.asLong(pos));
         return t != null && !t.warzone() && WorldGuardLink.zoneAt(dim.location().toString(), pos);
      } else {
         return false;
      }
   }

   public static boolean inWarzone(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos) {
      if (loaded() && server != null && dim != null && pos != null) {
         return ClaimManager.get(server).isClaimed(dim, pos) && AdminTerritories.get(server).isWarzoneAt(dim, pos.toLong());
      } else {
         return false;
      }
   }

   public static boolean sameFaction(MinecraftServer server, UUID a, UUID b) {
      if (loaded() && server != null && a != null && b != null) {
         FactionStateManager fsm = FactionStateManager.get(server);
         Faction fa = fsm.getFactionByPlayer(a);
         Faction fb = fsm.getFactionByPlayer(b);
         return fa != null && fb != null && fa.getName().equals(fb.getName());
      } else {
         return false;
      }
   }

   public static int warzonePerms() {
      return AdminPerm.USE.mask() | AdminPerm.ENTITIES.mask() | AdminPerm.ATTACK.mask();
   }

   public static boolean canAdminClaim(ServerPlayer player) {
      return player != null && player.hasPermissions(2) ? !TerritoryConfig.adminRequiresCreative() || player.isCreative() : false;
   }

   public static FactionsBridge.Ctx gatherContext(ServerPlayer player) {
      if (loaded() && player != null) {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return FactionsBridge.Ctx.empty();
         } else {
            UUID uuid = player.getUUID();
            FactionStateManager fsm = FactionStateManager.get(server);
            ClaimManager cm = ClaimManager.get(server);
            Faction f = fsm.getFactionByPlayer(uuid);
            boolean inFaction = f != null;
            boolean canFactionClaim = inFaction && fsm.playerIsOwnerOrOfficer(uuid);
            String factionName = inFaction ? f.getName() : "";
            int factionColor = inFaction ? ZoneColor.safe(f.getColor()) : 16777215;
            int coreCap = personalCapFor(server, uuid);
            int coreUsed = cm.getCoreChunkCount(uuid);
            int factionCap = inFaction ? factionCapFor(server, f) : 0;
            int factionUsed = inFaction ? cm.getFactionClaimCount(factionName) : 0;
            return new FactionsBridge.Ctx(
               true,
               inFaction,
               canFactionClaim,
               canAdminClaim(player),
               canPersonalClaim(server, uuid),
               factionName,
               factionColor,
               coreCap,
               coreUsed,
               factionCap,
               factionUsed
            );
         }
      } else {
         return FactionsBridge.Ctx.empty();
      }
   }

   public static int personalCapFor(MinecraftServer server, UUID player) {
      return ServerConfig.coreChunkAmount;
   }

   public static boolean canPersonalClaim(MinecraftServer server, UUID player) {
      if (!TerritoryConfig.leaderClaimsBecomeFaction()) {
         return true;
      } else {
         return server != null && player != null ? !FactionStateManager.get(server).playerOwnsFaction(player) : true;
      }
   }

   public static int factionCapFor(MinecraftServer server, Faction f) {
      return f == null ? 0 : ServerConfig.factionBaseClaimLimit + ServerConfig.factionAdditionalClaimLimitPerMember * memberCount(f);
   }

   public static boolean meetsMemberRequirement(Faction f) {
      return f != null && memberCount(f) >= TerritoryConfig.minFactionMembers();
   }

   public static boolean sameSideNoFriendlyFire(MinecraftServer server, UUID a, UUID b) {
      if (loaded() && server != null && a != null && b != null && !a.equals(b)) {
         FactionStateManager fsm = FactionStateManager.get(server);
         Faction fa = fsm.getFactionByPlayer(a);
         if (fa == null) {
            return false;
         } else {
            Faction fb = fsm.getFactionByPlayer(b);
            if (fb == null) {
               return false;
            } else if (fa.getName().equals(fb.getName())) {
               return !fa.getFriendlyFire();
            } else {
               return !TerritoryConfig.protectAllies() ? false : friendlyToward(fa, fb.getName()) && friendlyToward(fb, fa.getName());
            }
         }
      } else {
         return false;
      }
   }

   private static boolean friendlyToward(Faction from, String towards) {
      if (from.getOutgoingRelations() == null) {
         return false;
      } else {
         RelationshipStatus relation = (RelationshipStatus)from.getOutgoingRelations().get(towards);
         return relation != null && "FRIENDLY".equals(relation.name());
      }
   }

   public static String factionNameOf(MinecraftServer server, UUID player) {
      if (loaded() && server != null && player != null) {
         Faction f = FactionStateManager.get(server).getFactionByPlayer(player);
         return f == null ? "" : f.getName();
      } else {
         return "";
      }
   }

   private static long distSq(long packed, ChunkPos from) {
      ChunkPos p = new ChunkPos(packed);
      long dx = (long)p.x - (long)from.x;
      long dz = (long)p.z - (long)from.z;
      return dx * dx + dz * dz;
   }

   private static List<Long> nearestFirst(Set<Long> from, ChunkPos centre, int limit) {
      List<Long> sorted = new ArrayList<>(from);
      sorted.sort((a, b) -> Long.compare(distSq(a, centre), distSq(b, centre)));
      return new ArrayList<>(sorted.subList(0, Math.min(Math.max(0, limit), sorted.size())));
   }

   public static int convertPersonalToFaction(MinecraftServer server, ServerPlayer founder, String factionName) {
      if (!loaded() || server == null || founder == null) {
         return 0;
      } else if (!TerritoryConfig.leaderClaimsBecomeFaction()) {
         return 0;
      } else {
         FactionStateManager fsm = FactionStateManager.get(server);
         Faction f = fsm.getFactionByName(factionName);
         if (f == null) {
            return 0;
         } else {
            ClaimManager cm = ClaimManager.get(server);
            UUID uuid = founder.getUUID();
            Map<ResourceKey<Level>, Set<Long>> mine = cm.getPlayerCoreChunks(uuid);
            if (mine != null && !mine.isEmpty()) {
               int room = factionCapFor(server, f) - cm.getFactionClaimCount(factionName);
               int converted = 0;
               Map<ResourceKey<Level>, List<Long>> snapshot = new HashMap<>();

               for (Entry<ResourceKey<Level>, Set<Long>> e : mine.entrySet()) {
                  if (ServerConfig.factionClaimDimensions.contains(e.getKey().location().toString())) {
                     snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
                  }
               }

               for (Entry<ResourceKey<Level>, List<Long>> ex : snapshot.entrySet()) {
                  List<ChunkPos> take = new ArrayList<>();
                  List<Long> takeLongs = new ArrayList<>();

                  for (long chunk : ex.getValue()) {
                     if (room <= 0) {
                        break;
                     }

                     take.add(new ChunkPos(chunk));
                     takeLongs.add(chunk);
                     room--;
                  }

                  if (!take.isEmpty()) {
                     HashMap<ResourceLocation, List<Long>> release = new HashMap<>();
                     release.put(ex.getKey().location(), takeLongs);
                     cm.unclaimChunks(release, server);
                     HashMap<ResourceLocation, List<ChunkPos>> claim = new HashMap<>();
                     claim.put(ex.getKey().location(), take);
                     cm.claimChunks(claim, ClaimType.FACTION, factionName, f.getColor(), server);
                     converted += take.size();
                  }
               }

               return converted;
            } else {
               return 0;
            }
         }
      }
   }

   public static FactionsBridge.DisbandResult revertFactionToPersonal(MinecraftServer server, String factionName, UUID ownerId) {
      if (loaded() && server != null && factionName != null) {
         ClaimManager cm = ClaimManager.get(server);
         Map<ResourceKey<Level>, Set<Long>> chunks = cm.getFactionChunks(factionName);
         if (chunks != null && !chunks.isEmpty()) {
            Map<ResourceKey<Level>, Set<Long>> snapshot = new HashMap<>();
            chunks.forEach((dim, set) -> snapshot.put((ResourceKey<Level>)dim, new HashSet<>(set)));
            ServerPlayer owner = ownerId != null ? server.getPlayerList().getPlayer(ownerId) : null;
            int keepBudget = 0;
            ResourceKey<Level> keepDim = null;
            if (ownerId != null && TerritoryConfig.leaderClaimsBecomeFaction()) {
               keepBudget = Math.max(0, personalCapFor(server, ownerId) - cm.getCoreChunkCount(ownerId));
               keepDim = chooseKeepDimension(snapshot, owner);
            }

            List<Long> keep = List.of();
            if (keepBudget > 0 && keepDim != null) {
               ChunkPos anchor = owner != null && owner.level().dimension().equals(keepDim)
                  ? owner.chunkPosition()
                  : new ChunkPos(snapshot.get(keepDim).iterator().next());
               keep = growFrom(snapshot.get(keepDim), anchor, keepBudget);
            }

            int released = 0;

            for (Entry<ResourceKey<Level>, Set<Long>> e : snapshot.entrySet()) {
               if (!e.getValue().isEmpty()) {
                  HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
                  rm.put(e.getKey().location(), new ArrayList<>(e.getValue()));
                  cm.unclaimChunks(rm, server);
                  released += e.getValue().size();
               }
            }

            released -= keep.size();
            if (!keep.isEmpty()) {
               List<ChunkPos> back = new ArrayList<>();

               for (long chunk : keep) {
                  back.add(new ChunkPos(chunk));
               }

               HashMap<ResourceLocation, List<ChunkPos>> claim = new HashMap<>();
               claim.put(keepDim.location(), back);
               int color = TerritoryNames.get(server).getColor(ownerId);
               if (color == Integer.MIN_VALUE) {
                  color = ServerConfig.coreClaimColor;
               }

               cm.claimChunks(claim, ClaimType.CORE, ownerId.toString(), color & 16777215, server);
            }

            return new FactionsBridge.DisbandResult(keep.size(), released);
         } else {
            return FactionsBridge.DisbandResult.NONE;
         }
      } else {
         return FactionsBridge.DisbandResult.NONE;
      }
   }

   private static ResourceKey<Level> chooseKeepDimension(Map<ResourceKey<Level>, Set<Long>> chunks, ServerPlayer owner) {
      ResourceKey<Level> best = null;
      int bestCount = 0;

      for (Entry<ResourceKey<Level>, Set<Long>> e : chunks.entrySet()) {
         if (!e.getValue().isEmpty() && ServerConfig.coreClaimDimensions.contains(e.getKey().location().toString())) {
            if (owner != null && owner.level().dimension().equals(e.getKey())) {
               return e.getKey();
            }

            if (e.getValue().size() > bestCount) {
               best = e.getKey();
               bestCount = e.getValue().size();
            }
         }
      }

      return best;
   }

   private static List<Long> growFrom(Set<Long> available, ChunkPos anchor, int limit) {
      List<Long> byDistance = nearestFirst(available, anchor, available.size());
      Set<Long> taken = new LinkedHashSet<>();

      for (long seed : byDistance) {
         if (taken.size() >= limit) {
            break;
         }

         if (!taken.contains(seed)) {
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(seed);

            while (!q.isEmpty() && taken.size() < limit) {
               long c = q.poll();
               if (available.contains(c) && taken.add(c)) {
                  for (long n : neighbours(c)) {
                     if (available.contains(n) && !taken.contains(n)) {
                        q.add(n);
                     }
                  }
               }
            }
         }
      }

      return new ArrayList<>(taken);
   }

   public static void notifyFaction(MinecraftServer server, String factionName, Component message) {
      if (loaded() && server != null) {
         Faction f = FactionStateManager.get(server).getFactionByName(factionName);
         if (f != null) {
            Set<UUID> all = new HashSet<>();
            if (f.getOwner() != null) {
               all.add(f.getOwner());
            }

            if (f.getOfficers() != null) {
               all.addAll(f.getOfficers());
            }

            if (f.getMembers() != null) {
               all.addAll(f.getMembers());
            }

            for (UUID id : all) {
               ServerPlayer online = server.getPlayerList().getPlayer(id);
               if (online != null) {
                  online.sendSystemMessage(message);
               }
            }
         }
      }
   }

   private record Ranked(ResourceKey<Level> dim, long chunk, int rank) {
   }

   public static HashMap<ResourceLocation, List<Long>> releaseMap(Map<ResourceKey<Level>, Set<Long>> held) {
      HashMap<ResourceLocation, List<Long>> release = new HashMap<>();
      if (held != null) {
         for (Entry<ResourceKey<Level>, Set<Long>> e : held.entrySet()) {
            if (!e.getValue().isEmpty()) {
               release.put(e.getKey().location(), new ArrayList<>(e.getValue()));
            }
         }
      }

      return release;
   }

   public static int releaseAllFactionClaims(MinecraftServer server, String factionName) {
      if (loaded() && server != null && factionName != null) {
         ClaimManager cm = ClaimManager.get(server);
         HashMap<ResourceLocation, List<Long>> release = releaseMap(cm.getFactionChunks(factionName));
         int total = 0;
         for (List<Long> chunks : release.values()) {
            total += chunks.size();
         }

         if (total > 0) {
            cm.unclaimChunks(release, server);
         }

         return total;
      } else {
         return 0;
      }
   }

   public static int releaseOutermost(MinecraftServer server, String factionName, FactionCores.Core core, int count) {
      if (loaded() && server != null && count > 0) {
         ClaimManager cm = ClaimManager.get(server);
         Map<ResourceKey<Level>, Set<Long>> held = cm.getFactionChunks(factionName);
         if (held != null && !held.isEmpty()) {
            ResourceKey<Level> coreDim = null;
            long coreChunk = 0L;
            if (core != null) {
               ResourceLocation rl = ResourceLocation.tryParse(core.dimension());
               if (rl != null) {
                  coreDim = ResourceKey.create(Registries.DIMENSION, rl);
                  coreChunk = new ChunkPos(BlockPos.of(core.pos())).toLong();
               }
            }

            List<Ranked> ranked = new ArrayList<>();

            for (Entry<ResourceKey<Level>, Set<Long>> e : held.entrySet()) {
               Set<Long> set = e.getValue();
               if (!set.isEmpty()) {
                  boolean withCore = e.getKey().equals(coreDim);
                  long anchor = withCore && set.contains(coreChunk) ? coreChunk : centreMost(set);
                  Map<Long, Integer> distance = distancesFrom(set, anchor);
                  int offset = withCore ? 0 : 1000000;

                  for (long chunk : set) {
                     ranked.add(new Ranked(e.getKey(), chunk, offset + distance.getOrDefault(chunk, 500000)));
                  }
               }
            }

            ranked.sort((a, b) -> Integer.compare(b.rank(), a.rank()));
            int take = Math.min(count, ranked.size());
            HashMap<ResourceLocation, List<Long>> release = new HashMap<>();

            for (int i = 0; i < take; i++) {
               Ranked r = ranked.get(i);
               release.computeIfAbsent(r.dim().location(), k -> new ArrayList<>()).add(r.chunk());
            }

            cm.unclaimChunks(release, server);
            return take;
         } else {
            return 0;
         }
      } else {
         return 0;
      }
   }

   private static long centreMost(Set<Long> set) {
      double sumX = 0.0;
      double sumZ = 0.0;

      for (long chunk : set) {
         sumX += (double)ChunkPos.getX(chunk);
         sumZ += (double)ChunkPos.getZ(chunk);
      }

      double cx = sumX / (double)set.size();
      double cz = sumZ / (double)set.size();
      long best = set.iterator().next();
      double bestDist = Double.MAX_VALUE;

      for (long chunk : set) {
         double dx = (double)ChunkPos.getX(chunk) - cx;
         double dz = (double)ChunkPos.getZ(chunk) - cz;
         double d = dx * dx + dz * dz;
         if (d < bestDist) {
            bestDist = d;
            best = chunk;
         }
      }

      return best;
   }

   private static Map<Long, Integer> distancesFrom(Set<Long> set, long anchor) {
      Map<Long, Integer> distance = new HashMap<>();
      ArrayDeque<Long> queue = new ArrayDeque<>();
      distance.put(anchor, 0);
      queue.add(anchor);

      while (!queue.isEmpty()) {
         long c = queue.poll();
         int next = distance.get(c) + 1;

         for (long n : neighbours(c)) {
            if (set.contains(n) && !distance.containsKey(n)) {
               distance.put(n, next);
               queue.add(n);
            }
         }
      }

      return distance;
   }

   private static int memberCount(Faction f) {
      Set<UUID> all = new HashSet<>();
      if (f.getOwner() != null) {
         all.add(f.getOwner());
      }

      if (f.getOfficers() != null) {
         all.addAll(f.getOfficers());
      }

      if (f.getMembers() != null) {
         all.addAll(f.getMembers());
      }

      return all.size();
   }

   public static int defaultCoreColor() {
      return ServerConfig.coreClaimColor & 16777215;
   }

   public static List<FactionsBridge.ClaimCell> regionClaims(ServerPlayer player, ResourceKey<Level> dim, ChunkPos center, int radius) {
      List<FactionsBridge.ClaimCell> out = new ArrayList<>();
      if (loaded() && player != null) {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return out;
         } else {
            ClaimManager cm = ClaimManager.get(server);
            FactionStateManager fsm = FactionStateManager.get(server);
            TerritoryNames names = TerritoryNames.get(server);
            AdminTerritories adminZones = AdminTerritories.get(server);
            Faction f = fsm.getFactionByPlayer(player.getUUID());
            String myFaction = f != null ? f.getName() : null;
            String myUuid = player.getUUID().toString();

            for (int x = center.x - radius; x <= center.x + radius; x++) {
               for (int z = center.z - radius; z <= center.z + radius; z++) {
                  ChunkPos pos = new ChunkPos(x, z);
                  if (cm.isClaimed(dim, pos)) {
                     ClaimData data = cm.getClaim(dim, pos);
                     if (data != null) {
                        int kind = 2;
                        if (data.type == ClaimType.ADMIN) {
                           kind = 3;
                        } else if (data.type == ClaimType.CORE && myUuid.equals(data.owner)) {
                           kind = 0;
                        } else if (data.type == ClaimType.FACTION && myFaction != null && myFaction.equals(data.owner)) {
                           kind = 1;
                        }

                        int color = ZoneColor.safe(data.color);
                        String label;
                        if (data.type == ClaimType.ADMIN) {
                           AdminTerritories.Territory zone = adminZones.governing(dim, pos.toLong());
                           label = zone != null && !zone.name().isEmpty() ? zone.name() : (zone != null && zone.warzone() ? "Warzone" : "Safezone");
                           color = zone != null && zone.warzone() ? ZoneColor.WARZONE : ZoneColor.SAFEZONE;
                        } else {
                           label = claimLabel(server, names, data);
                        }

                        out.add(new FactionsBridge.ClaimCell(x, z, kind, color, label));
                     }
                  }
               }
            }

            return out;
         }
      } else {
         return out;
      }
   }

   private static String claimLabel(MinecraftServer server, TerritoryNames names, ClaimData data) {
      if (data.type == ClaimType.FACTION) {
         return data.owner;
      } else if (data.type == ClaimType.CORE) {
         try {
            UUID id = UUID.fromString(data.owner);
            String tname = names.getName(id);
            return !tname.isEmpty() ? tname : nameOf(server, id);
         } catch (IllegalArgumentException var5) {
            return data.owner;
         }
      } else {
         return "Safezone";
      }
   }

   public static String commit(ServerPlayer player, int claimType, List<ChunkPos> add, List<ChunkPos> remove, int coreColor, String adminName) {
      if (!loaded()) {
         return "Holdfast Factions is not installed.";
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return "No server.";
         } else {
            ClaimManager cm = ClaimManager.get(server);
            FactionStateManager fsm = FactionStateManager.get(server);
            UUID uuid = player.getUUID();
            ResourceKey<Level> dimKey = player.level().dimension();
            ResourceLocation dim = dimKey.location();
            boolean warzone = claimType == TYPE_WARZONE;
            boolean admin = claimType == 2 || warzone;
            boolean faction = claimType == 1;
            String territoryName = adminName.isBlank() ? (warzone ? "Warzone" : "Safezone") : adminName;
            ClaimType type = admin ? ClaimType.ADMIN : (faction ? ClaimType.FACTION : ClaimType.CORE);
            Faction f = null;
            String myOwnerKey;
            if (admin) {
               if (!canAdminClaim(player)) {
                  return "Safezones and warzones require an operator in creative mode.";
               }

               myOwnerKey = "Admin";
               AdminTerritories.Territory named = AdminTerritories.get(server).get(dimKey, territoryName);
               if (!add.isEmpty() && named != null && named.warzone() != warzone) {
                  return "There is already a " + (named.warzone() ? "warzone" : "safezone") + " called " + named.name() + ".";
               }
            } else if (faction) {
               f = fsm.getFactionByPlayer(uuid);
               if (f == null) {
                  return "You are not in a faction.";
               }

               if (!fsm.playerIsOwnerOrOfficer(uuid)) {
                  return "Only the faction owner or an officer can claim for the faction.";
               }

               myOwnerKey = f.getName();
            } else {
               if (!canPersonalClaim(server, uuid)) {
                  return "Your faction's land is your land now - claim for the faction instead.";
               }

               myOwnerKey = uuid.toString();
            }

            Set<Long> removeOk = new HashSet<>();

            for (ChunkPos cp : remove) {
               ClaimData d = cm.getClaim(dimKey, cp);
               if (d != null && d.type == type && (admin || myOwnerKey.equals(d.owner))) {
                  removeOk.add(cp.toLong());
               }
            }

            List<ChunkPos> safeAdd = new ArrayList<>();
            Set<Long> addSet = new HashSet<>();

            for (ChunkPos cpx : add) {
               if (!cm.isClaimed(dimKey, cpx) && addSet.add(cpx.toLong())) {
                  safeAdd.add(cpx);
               }
            }

            if (!admin && TerritoryConfig.claimBufferChunks() > 0) {
               String tooClose = bufferRefusal(server, cm, fsm, dimKey, safeAdd, addSet, uuid, faction ? f.getName() : null);
               if (tooClose != null) {
                  return tooClose;
               }
            }

            if (!admin) {
               Set<Long> ownerNow = new HashSet<>(ownerChunks(cm, dimKey, faction, myOwnerKey, uuid));
               Set<Long> finalSet = new HashSet<>(ownerNow);
               finalSet.removeAll(removeOk);
               finalSet.addAll(addSet);
               if (componentCount(finalSet) > Math.max(1, componentCount(ownerNow))) {
                  return "Claims must be connected to each other.";
               }

               if (!safeAdd.isEmpty()) {
                  boolean dimensionAllowed = faction
                     ? ServerConfig.factionClaimDimensions.contains(dim.toString())
                     : ServerConfig.coreClaimDimensions.contains(dim.toString());
                  if (!dimensionAllowed) {
                     return "Land cannot be claimed in this dimension.";
                  }

                  if (ownerNow.isEmpty()) {
                     ChunkPos here = player.chunkPosition();

                     for (ChunkPos cpHome : safeAdd) {
                        if (Math.abs(cpHome.x - here.x) > 8 || Math.abs(cpHome.z - here.z) > 8) {
                           return "Your first claim has to be within 8 chunks of you.";
                        }
                     }
                  }

                  if (faction) {
                     if (!meetsMemberRequirement(f)) {
                        return "Your faction needs at least " + TerritoryConfig.minFactionMembers() + " members to claim land.";
                     }

                     int cap = factionCapFor(server, f);
                     if (cm.getFactionClaimCount(f.getName()) - removeOk.size() + safeAdd.size() > cap) {
                        return "Faction claim limit reached (" + cap + ").";
                     }
                  } else {
                     int cap = personalCapFor(server, uuid);
                     if (cm.getCoreChunkCount(uuid) - removeOk.size() + safeAdd.size() > cap) {
                        return "Personal claim limit reached (" + cap + ").";
                     }
                  }
               }
            }

            if (!removeOk.isEmpty()) {
               HashMap<ResourceLocation, List<Long>> rm = new HashMap<>();
               rm.put(dim, new ArrayList<>(removeOk));
               cm.unclaimChunks(rm, server);
               if (admin) {
                  AdminTerritories zones = AdminTerritories.get(server);

                  for (long chunk : removeOk) {
                     zones.clearChunk(dimKey, chunk);
                  }
               }
            }

            if (!safeAdd.isEmpty()) {
               HashMap<ResourceLocation, List<ChunkPos>> ad = new HashMap<>();
               ad.put(dim, safeAdd);
               if (admin) {
                  int rgb = warzone ? ZoneColor.WARZONE : ZoneColor.SAFEZONE;
                  cm.claimChunks(ad, ClaimType.ADMIN, "Admin", rgb, server);
                  AdminTerritories zones = AdminTerritories.get(server);

                  for (ChunkPos cpxxx : safeAdd) {
                     zones.assign(dimKey, cpxxx.toLong(), territoryName, rgb, warzone ? warzonePerms() : -1, warzone);
                  }
               } else if (faction) {
                  cm.claimChunks(ad, ClaimType.FACTION, f.getName(), f.getColor(), server);
               } else {
                  int color = coreColor < 0 ? ServerConfig.coreClaimColor : coreColor & 16777215;
                  cm.claimChunks(ad, ClaimType.CORE, uuid.toString(), color, server);
               }
            }

            return claimReceipt(safeAdd.size(), removeOk.size());
         }
      }
   }

   private static String claimReceipt(int added, int removed) {
      StringBuilder msg = new StringBuilder();
      if (added > 0) {
         msg.append("Claimed ").append(added).append(added == 1 ? " chunk" : " chunks");
      }

      if (removed > 0) {
         if (msg.length() > 0) {
            msg.append(", released ").append(removed);
         } else {
            msg.append("Released ").append(removed).append(removed == 1 ? " chunk" : " chunks");
         }
      }

      return msg.length() == 0 ? "" : msg.append(".").toString();
   }

   private static String bufferRefusal(
      MinecraftServer server,
      ClaimManager cm,
      FactionStateManager fsm,
      ResourceKey<Level> dimKey,
      List<ChunkPos> adds,
      Set<Long> addSet,
      UUID claimant,
      String factionName
   ) {
      int buffer = TerritoryConfig.claimBufferChunks();

      for (ChunkPos cp : adds) {
         for (int dx = -buffer; dx <= buffer; dx++) {
            for (int dz = -buffer; dz <= buffer; dz++) {
               if (dx != 0 || dz != 0) {
                  ChunkPos near = new ChunkPos(cp.x + dx, cp.z + dz);
                  if (!addSet.contains(near.toLong()) && cm.isClaimed(dimKey, near)) {
                     ClaimData d = cm.getClaim(dimKey, near);
                     if (d != null && d.type != ClaimType.ADMIN && !friendlyClaim(fsm, d, claimant, factionName)) {
                        return "Too close to "
                           + ownerDisplay(server, d)
                           + ". Claims must stay "
                           + buffer
                           + (buffer == 1 ? " chunk" : " chunks")
                           + " clear of another owner's land.";
                     }
                  }
               }
            }
         }
      }

      return null;
   }

   private static boolean friendlyClaim(FactionStateManager fsm, ClaimData claim, UUID claimant, String factionName) {
      if (claim.type == ClaimType.FACTION) {
         return factionName != null && factionName.equals(claim.owner);
      } else if (claim.type == ClaimType.CORE) {
         UUID owner = parseUuid(claim.owner);
         if (owner == null) {
            return false;
         } else if (owner.equals(claimant)) {
            return true;
         } else if (factionName == null) {
            return false;
         } else {
            Faction theirs = fsm.getFactionByPlayer(owner);
            return theirs != null && factionName.equals(theirs.getName());
         }
      } else {
         return true;
      }
   }

   public static final String RESERVED_COLOR_MESSAGE = "That colour is reserved for safezones and warzones. Pick another colour.";

   public static void recolorPersonal(ServerPlayer player, int rgb) {
      if (loaded() && !ZoneColor.isReserved(rgb)) {
         MinecraftServer server = player.getServer();
         if (server != null) {
            ClaimManager cm = ClaimManager.get(server);
            ResourceKey<Level> dimKey = player.level().dimension();
            ResourceLocation dim = dimKey.location();
            Set<Long> mine = cm.getPlayerCoreChunks(player.getUUID()).getOrDefault(dimKey, Set.of());
            if (!mine.isEmpty()) {
               List<ChunkPos> chunks = new ArrayList<>();

               for (long l : mine) {
                  chunks.add(new ChunkPos(l));
               }

               HashMap<ResourceLocation, List<ChunkPos>> map = new HashMap<>();
               map.put(dim, chunks);
               cm.claimChunks(map, ClaimType.CORE, player.getUUID().toString(), rgb & 16777215, server);
            }
         }
      }
   }

   public static String recolorFaction(ServerPlayer player, int rgb) {
      if (!loaded()) {
         return "Holdfast Factions is not installed.";
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return "No server.";
         } else {
            FactionStateManager fsm = FactionStateManager.get(server);
            ClaimManager cm = ClaimManager.get(server);
            UUID uuid = player.getUUID();
            Faction f = fsm.getFactionByPlayer(uuid);
            if (f == null) {
               return "You are not in a faction.";
            } else if (!fsm.playerIsOwnerOrOfficer(uuid)) {
               return "Only the faction owner or an officer can set the colour.";
            } else if (ZoneColor.isReserved(rgb)) {
               return RESERVED_COLOR_MESSAGE;
            } else {
               f.setColor(rgb & 16777215);
               fsm.setDirty();
               cm.changeFactionColor(f.getName(), rgb & 16777215, server);
               return "";
            }
         }
      }
   }

   private static Set<Long> ownerChunks(ClaimManager cm, ResourceKey<Level> dimKey, boolean faction, String factionName, UUID uuid) {
      Map<ResourceKey<Level>, Set<Long>> map = faction ? cm.getFactionChunks(factionName) : cm.getPlayerCoreChunks(uuid);
      Set<Long> set = map != null ? map.get(dimKey) : null;
      return set != null ? set : Set.of();
   }

   private static int componentCount(Set<Long> set) {
      if (set.isEmpty()) {
         return 0;
      } else {
         Set<Long> seen = new HashSet<>();
         int comps = 0;

         for (long start : set) {
            if (seen.add(start)) {
               comps++;
               ArrayDeque<Long> q = new ArrayDeque<>();
               q.add(start);

               while (!q.isEmpty()) {
                  long c = q.poll();

                  for (long n : neighbours(c)) {
                     if (set.contains(n) && seen.add(n)) {
                        q.add(n);
                     }
                  }
               }
            }
         }

         return comps;
      }
   }

   private static long[] neighbours(long packed) {
      int x = ChunkPos.getX(packed);
      int z = ChunkPos.getZ(packed);
      return new long[]{ChunkPos.asLong(x + 1, z), ChunkPos.asLong(x - 1, z), ChunkPos.asLong(x, z + 1), ChunkPos.asLong(x, z - 1)};
   }

   public static FactionsBridge.Decision decide(Player player, ResourceKey<Level> dim, BlockPos pos, Interaction interaction) {
      if (player == null || pos == null || handsOff(player.getServer(), dim, pos)) {
         return FactionsBridge.Decision.DEFER;
      } else {
         return decide(player, dim, new ChunkPos(pos), interaction);
      }
   }

   public static FactionsBridge.Decision decideAmbient(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos, Interaction interaction) {
      if (pos == null || handsOff(server, dim, pos)) {
         return FactionsBridge.Decision.DEFER;
      } else {
         return decideAmbient(server, dim, new ChunkPos(pos), interaction);
      }
   }

   public static FactionsBridge.Decision decide(Player player, ResourceKey<Level> dim, ChunkPos pos, Interaction interaction) {
      if (!loaded() || player == null || dim == null || pos == null || interaction == null) {
         return FactionsBridge.Decision.DEFER;
      } else if (!TerritoryConfig.protectionEnabled() && interaction != Interaction.PVP && interaction != Interaction.MOUNT) {
         return FactionsBridge.Decision.DEFER;
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return FactionsBridge.Decision.DEFER;
         } else {
            ClaimManager cm = ClaimManager.get(server);
            if (!cm.isClaimed(dim, pos)) {
               return FactionsBridge.Decision.DEFER;
            } else {
               ClaimData claim = cm.getClaim(dim, pos);
               if (claim == null) {
                  return FactionsBridge.Decision.DEFER;
               } else {
                  return player.hasPermissions(TerritoryConfig.bypassPermissionLevel())
                     ? FactionsBridge.Decision.DEFER
                     : claimDecision(server, player, claim, dim, pos, interaction);
               }
            }
         }
      }
   }

   private static FactionsBridge.Decision claimDecision(
      MinecraftServer server, Player player, ClaimData claim, ResourceKey<Level> dim, ChunkPos pos, Interaction interaction
   ) {
      if (interaction == Interaction.MOUNT) {
         return mountDecision(server, player, claim, dim, pos);
      }

      if (interaction == Interaction.PVP) {
         return pvpDecision(server, player, claim, dim, pos);
      }

      if (claim.type == ClaimType.CORE) {
         if (!TerritoryConfig.enforcePersonalClaims()) {
            return FactionsBridge.Decision.DEFER;
         } else if (!restricts(ClaimType.CORE, interaction)) {
            return relax(ClaimType.CORE, interaction);
         } else if (player.getUUID().toString().equals(claim.owner)) {
            return FactionsBridge.Decision.DEFER;
         } else {
            return warRaid(server, player, claim, interaction) ? FactionsBridge.Decision.ALLOW : FactionsBridge.Decision.DENY;
         }
      } else if (claim.type != ClaimType.FACTION) {
         return claim.type == ClaimType.ADMIN ? adminDecision(server, player.getUUID(), dim, pos.toLong(), interaction) : FactionsBridge.Decision.DEFER;
      } else if (!TerritoryConfig.enforceFactionClaims()) {
         return FactionsBridge.Decision.DEFER;
      } else if (!restricts(ClaimType.FACTION, interaction)) {
         return relax(ClaimType.FACTION, interaction);
      } else {
         FactionStateManager fsm = FactionStateManager.get(server);
         Faction mine = fsm.getFactionByPlayer(player.getUUID());
         if (mine != null && mine.getName().equals(claim.owner)) {
            return FactionsBridge.Decision.DEFER;
         } else if (outsiderMayUse(server, fsm, mine, claim.owner, interaction) || warRaid(server, player, claim, interaction)) {
            return FactionsBridge.Decision.ALLOW;
         } else {
            return FactionsBridge.Decision.DENY;
         }
      }
   }

   private static FactionsBridge.Decision pvpDecision(MinecraftServer server, Player player, ClaimData claim, ResourceKey<Level> dim, ChunkPos pos) {
      if (claim.type == ClaimType.ADMIN) {
         return adminDecision(server, player.getUUID(), dim, pos.toLong(), Interaction.PVP);
      } else if (claim.type == ClaimType.FACTION || claim.type == ClaimType.CORE) {
         return coreRestricts(coreListFor(claim.type), Interaction.PVP) ? FactionsBridge.Decision.ALLOW : FactionsBridge.Decision.DEFER;
      } else {
         return FactionsBridge.Decision.DEFER;
      }
   }

   private static FactionsBridge.Decision mountDecision(MinecraftServer server, Player player, ClaimData claim, ResourceKey<Level> dim, ChunkPos pos) {
      if (!TerritoryConfig.allowMounts()) {
         return claimDecision(server, player, claim, dim, pos, Interaction.INTERACT_ENTITY);
      } else if (claim.type == ClaimType.ADMIN || claim.type == ClaimType.FACTION || claim.type == ClaimType.CORE) {
         return coreRestricts(coreListFor(claim.type), Interaction.INTERACT_ENTITY) ? FactionsBridge.Decision.ALLOW : FactionsBridge.Decision.DEFER;
      } else {
         return FactionsBridge.Decision.DEFER;
      }
   }

   private static boolean outsiderMayUse(MinecraftServer server, FactionStateManager fsm, Faction visitor, String ownerFaction, Interaction interaction) {
      if (!interaction.isOutsiderToggle() || ownerFaction == null) {
         return false;
      } else {
         FactionSettings settings = FactionSettings.get(server);
         int level = interaction == Interaction.DOOR ? settings.doors(ownerFaction) : settings.utility(ownerFaction);
         if (level >= FactionSettings.EVERYONE) {
            return true;
         } else if (level == FactionSettings.ALLIES && visitor != null) {
            Faction owner = fsm.getFactionByName(ownerFaction);
            return owner != null && friendlyToward(owner, visitor.getName());
         } else {
            return false;
         }
      }
   }

   private static String claimOwnerFaction(MinecraftServer server, ClaimData claim) {
      if (claim.type == ClaimType.FACTION) {
         return claim.owner;
      } else {
         UUID owner = parseUuid(claim.owner);
         Faction f = owner == null ? null : FactionStateManager.get(server).getFactionByPlayer(owner);
         return f == null ? null : f.getName();
      }
   }

   private static boolean warRaid(MinecraftServer server, Player player, ClaimData claim, Interaction interaction) {
      if (!TerritoryConfig.warEnabled() || !TerritoryConfig.warAllows(interaction)) {
         return false;
      } else {
         Faction mine = FactionStateManager.get(server).getFactionByPlayer(player.getUUID());
         return mine != null && WarState.atWar(server, mine.getName(), claimOwnerFaction(server, claim));
      }
   }

   private static boolean restricts(ClaimType type, Interaction interaction) {
      if (TerritoryConfig.useOwnRestrictions()) {
         if (interaction == Interaction.CONTAINER) {
            return TerritoryConfig.protectContainers();
         } else {
            Set<Interaction> listed = TerritoryConfig.restrictedInteractions();
            return listed.contains(interaction) || interaction.isOutsiderToggle() && listed.contains(Interaction.RIGHT_CLICK_BLOCK);
         }
      } else {
         return coreRestricts(coreListFor(type), interaction);
      }
   }

   private static FactionsBridge.Decision relax(ClaimType type, Interaction interaction) {
      if (TerritoryConfig.overrideHoldfastFactions() && TerritoryConfig.useOwnRestrictions()) {
         return coreRestricts(coreListFor(type), interaction) ? FactionsBridge.Decision.ALLOW : FactionsBridge.Decision.DEFER;
      } else {
         return FactionsBridge.Decision.DEFER;
      }
   }

   private static Set<ChunkInteractionType> coreListFor(ClaimType type) {
      return switch (type) {
         case FACTION -> ServerConfig.factionClaimRestrictions;
         case CORE -> ServerConfig.coreClaimRestrictions;
         case ADMIN -> ServerConfig.adminClaimRestrictions;
         default -> throw new MatchException(null, null);
      };
   }

   private static FactionsBridge.Decision adminDecision(MinecraftServer server, UUID player, ResourceKey<Level> dim, long chunk, Interaction interaction) {
      AdminTerritories zones = AdminTerritories.get(server);
      AdminTerritories.Territory governing = zones.governing(dim, chunk);
      if (governing == null) {
         return FactionsBridge.Decision.DEFER;
      } else if (governing.warzone()) {
         AdminPerm warzonePerm = AdminPerm.forInteraction(interaction);
         if (interaction == Interaction.PLAYER_ATTACK || interaction == Interaction.PVP || warzonePerm == null) {
            return FactionsBridge.Decision.DEFER;
         } else {
            return warzonePerm.allowedIn(warzonePerms()) ? FactionsBridge.Decision.ALLOW : FactionsBridge.Decision.DENY;
         }
      } else if (player != null && zones.isTrusted(dim, chunk, player)) {
         return FactionsBridge.Decision.ALLOW;
      } else if (governing.perms() == -1) {
         return interaction == Interaction.EXPLOSION_DAMAGE ? FactionsBridge.Decision.DENY : FactionsBridge.Decision.DEFER;
      } else {
         AdminPerm perm = AdminPerm.forInteraction(interaction);
         if (perm == null) {
            return FactionsBridge.Decision.DEFER;
         } else {
            return perm.allowedIn(governing.perms()) ? FactionsBridge.Decision.ALLOW : FactionsBridge.Decision.DENY;
         }
      }
   }

   public static FactionsBridge.Decision decideAmbient(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos, Interaction interaction) {
      if (loaded() && server != null && dim != null && pos != null) {
         ClaimManager cm = ClaimManager.get(server);
         if (!cm.isClaimed(dim, pos)) {
            return FactionsBridge.Decision.DEFER;
         } else {
            ClaimData claim = cm.getClaim(dim, pos);
            if (claim == null) {
               return FactionsBridge.Decision.DEFER;
            } else if (claim.type == ClaimType.ADMIN) {
               return adminDecision(server, null, dim, pos.toLong(), interaction);
            } else if (interaction == Interaction.EXPLOSION_DAMAGE) {
               return FactionsBridge.Decision.ALLOW;
            } else if (!TerritoryConfig.protectionEnabled()) {
               return FactionsBridge.Decision.DEFER;
            } else if (claim.type == ClaimType.FACTION && !TerritoryConfig.enforceFactionClaims()) {
               return FactionsBridge.Decision.DEFER;
            } else if (claim.type == ClaimType.CORE && !TerritoryConfig.enforcePersonalClaims()) {
               return FactionsBridge.Decision.DEFER;
            } else {
               return restricts(claim.type, interaction) ? FactionsBridge.Decision.DENY : relax(claim.type, interaction);
            }
         }
      } else {
         return FactionsBridge.Decision.DEFER;
      }
   }

   private static boolean coreRestricts(Set<ChunkInteractionType> restrictions, Interaction interaction) {
      if (restrictions == null) {
         return false;
      } else {
         try {
            return restrictions.contains(ChunkInteractionType.valueOf(interaction.holdfastFactionsEquivalent().name()));
         } catch (IllegalArgumentException var3) {
            return false;
         }
      }
   }

   private static String ownerDisplay(MinecraftServer server, ClaimData claim) {
      if (claim.type != ClaimType.CORE) {
         return claim.owner;
      } else {
         UUID owner = parseUuid(claim.owner);
         return owner == null ? claim.owner : nameOf(server, owner);
      }
   }

   private static UUID parseUuid(String raw) {
      try {
         return raw == null ? null : UUID.fromString(raw);
      } catch (IllegalArgumentException var2) {
         return null;
      }
   }

   public static List<String> diagnose(ServerPlayer player) {
      List<String> out = new ArrayList<>();
      if (!loaded()) {
         out.add("Holdfast Factions is NOT loaded. Claims cannot work at all.");
         return out;
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return List.of("No server. Run this in game.");
         } else {
            ResourceKey<Level> dim = player.level().dimension();
            ChunkPos pos = player.chunkPosition();
            ClaimManager cm = ClaimManager.get(server);
            Faction mine = FactionStateManager.get(server).getFactionByPlayer(player.getUUID());
            out.add("Chunk " + pos.x + ", " + pos.z + " in " + dim.location());
            out.add("You: perm level " + permissionLevel(player) + ", faction " + (mine == null ? "(none)" : mine.getName()));
            boolean claimed = cm.isClaimed(dim, pos);
            ClaimData claim = claimed ? cm.getClaim(dim, pos) : null;
            if (claim == null) {
               out.add("Claim: UNCLAIMED. Nothing here is protected by anyone.");
            } else {
               out.add("Claim: " + claim.type + " owned by " + claim.owner + " (" + chunkOwnerDisplay(server, dim, pos) + ")");
               if (claim.type == ClaimType.ADMIN) {
                  AdminTerritories.Territory t = AdminTerritories.get(server).governing(dim, pos.toLong());
                  out.add(
                     "  safezone: "
                        + (t == null ? "(unnamed, the core global rules apply)" : t.name() + (t.perms() == -1 ? " (perms not customised)" : " (custom perms)"))
                  );
               }
            }

            out.add(
               "Config (territory-server.toml [protection]): enabled="
                  + TerritoryConfig.protectionEnabled()
                  + " faction="
                  + TerritoryConfig.enforceFactionClaims()
                  + " personal="
                  + TerritoryConfig.enforcePersonalClaims()
                  + " ownList="
                  + TerritoryConfig.useOwnRestrictions()
                  + " bypassLevel="
                  + TerritoryConfig.bypassPermissionLevel()
            );
            out.add(
               "  our restricted list: "
                  + TerritoryConfig.restrictedInteractions()
                  + " overrideEF="
                  + TerritoryConfig.overrideHoldfastFactions()
                  + " containers="
                  + (TerritoryConfig.protectContainers() ? "protected" : "open to all")
            );
            out.add(
               "Holdfast Factions live config: faction=" + nameSet(ServerConfig.factionClaimRestrictions) + " core=" + nameSet(ServerConfig.coreClaimRestrictions)
            );
            out.add("  Core claim dimensions: faction=" + ServerConfig.factionClaimDimensions + " core=" + ServerConfig.coreClaimDimensions);
            List<String> overrides = TerritoryConfig.perWorldConfigOverrides(server.getWorldPath(LevelResource.ROOT));
            if (!overrides.isEmpty()) {
               out.add("  WARNING: this world overrides " + overrides + " from <world>/serverconfig/. Edits made in config/ are being ignored.");
            }

            if (player.hasPermissions(TerritoryConfig.bypassPermissionLevel())) {
               out.add(
                  "VERDICT: you BYPASS protection at permission level "
                     + TerritoryConfig.bypassPermissionLevel()
                     + ". Test as a normal player, or raise bypassPermissionLevel to 4."
               );
            }

            for (Interaction i : new Interaction[]{
               Interaction.BREAK_BLOCK, Interaction.PLACE_BLOCK, Interaction.RIGHT_CLICK_BLOCK, Interaction.CONTAINER, Interaction.INTERACT_ENTITY
            }) {
               FactionsBridge.Decision d = decide(player, dim, pos, i);

               String note = switch (d) {
                  case ALLOW -> " (allowed - Holdfast Factions' refusal is being undone here)";
                  case DENY -> " (blocked)";
                  case DEFER -> " (no opinion; Holdfast Factions' answer stands)";
               };
               out.add("VERDICT " + i + ": " + d + note);
            }

            return out;
         }
      }
   }

   public static List<String> protectionWarnings() {
      List<String> out = new ArrayList<>();
      if (!loaded()) {
         return out;
      } else if (ServerConfig.factionClaimRestrictions != null && ServerConfig.coreClaimRestrictions != null) {
         if (!TerritoryConfig.useOwnRestrictions()) {
            for (ChunkInteractionType t : new ChunkInteractionType[]{ChunkInteractionType.BREAK_BLOCK, ChunkInteractionType.PLACE_BLOCK}) {
               if (!ServerConfig.factionClaimRestrictions.contains(t)) {
                  out.add("Holdfast Factions' factionClaimRestrictions does not contain " + t + ", so faction claims will NOT stop it.");
               }

               if (!ServerConfig.coreClaimRestrictions.contains(t)) {
                  out.add("Holdfast Factions' coreClaimRestrictions does not contain " + t + ", so personal claims will NOT stop it.");
               }
            }

            return out;
         } else {
            if (!TerritoryConfig.overrideHoldfastFactions()) {
               List<Interaction> stillBlocked = new ArrayList<>();

               for (Interaction i : Interaction.values()) {
                  if (i != Interaction.CONTAINER
                     && !TerritoryConfig.restrictedInteractions().contains(i)
                     && (coreRestricts(ServerConfig.factionClaimRestrictions, i) || coreRestricts(ServerConfig.coreClaimRestrictions, i))) {
                     stillBlocked.add(i);
                  }
               }

               if (!stillBlocked.isEmpty()) {
                  out.add(
                     "overrideHoldfastFactions is off, so Holdfast Factions still blocks "
                        + stillBlocked
                        + " inside claims even though restrictedInteractions permits them. Turn it on, or remove those entries from holdfast_factions-server.toml as well."
                  );
               }
            }

            return out;
         }
      } else {
         out.add("Holdfast Factions' restriction lists are null: its server config never loaded for this world.");
         return out;
      }
   }

   private static int permissionLevel(ServerPlayer player) {
      for (int level = 4; level >= 1; level--) {
         if (player.hasPermissions(level)) {
            return level;
         }
      }

      return 0;
   }

   private static String nameSet(Set<ChunkInteractionType> set) {
      if (set == null) {
         return "(not loaded)";
      } else {
         return set.isEmpty() ? "[] EMPTY - protects nothing" : set.toString();
      }
   }

   public static int defaultAdminPerms() {
      int mask = 0;

      for (AdminPerm perm : AdminPerm.values()) {
         boolean anyRestricted = false;

         for (Interaction i : perm.covers()) {
            if (coreRestricts(ServerConfig.adminClaimRestrictions, i)) {
               anyRestricted = true;
               break;
            }
         }

         if (!anyRestricted) {
            mask |= perm.mask();
         }
      }

      return mask;
   }

   public static List<FactionsBridge.AdminZoneInfo> adminZones(ServerPlayer player) {
      List<FactionsBridge.AdminZoneInfo> out = new ArrayList<>();
      if (loaded() && player != null && canAdminClaim(player)) {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return out;
         } else {
            ResourceKey<Level> dim = player.level().dimension();
            AdminTerritories zones = AdminTerritories.get(server);
            int inherited = defaultAdminPerms();

            for (AdminTerritories.Territory t : zones.list(dim)) {
               if (t.warzone()) {
                  continue;
               }

               List<String> members = new ArrayList<>();

               for (UUID id : t.members()) {
                  members.add(nameOf(server, id));
               }

               int size = zones.chunksOf(dim, t.name()).size();
               boolean custom = t.perms() != -1;
               out.add(
                  new FactionsBridge.AdminZoneInfo(
                     t.name(), ZoneColor.SAFEZONE, custom ? t.perms() : inherited, custom, size, members, worldGuardLoaded()
                  )
               );
            }

            return out;
         }
      } else {
         return out;
      }
   }

   public static String setAdminPerm(ServerPlayer player, String territory, int permOrdinal, boolean allowed) {
      if (!loaded()) {
         return "Holdfast Factions is not installed.";
      } else if (!canAdminClaim(player)) {
         return "Operators only.";
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return "No server.";
         } else if (permOrdinal >= 0 && permOrdinal < AdminPerm.values().length) {
            ResourceKey<Level> dim = player.level().dimension();
            AdminTerritories zones = AdminTerritories.get(server);
            AdminTerritories.Territory t = zones.get(dim, territory);
            if (t == null) {
               return "No safezone called " + territory + " here.";
            } else if (t.warzone()) {
               return "Warzones always follow fixed rules and cannot be edited.";
            } else {
               int base = t.perms() == -1 ? defaultAdminPerms() : t.perms();
               zones.setPerms(dim, t.name(), AdminPerm.with(base, AdminPerm.values()[permOrdinal], allowed));
               return "";
            }
         } else {
            return "Unknown permission.";
         }
      }
   }

   public static String setAdminMember(ServerPlayer player, String territory, String targetName, boolean add) {
      if (!loaded()) {
         return "Holdfast Factions is not installed.";
      } else if (!canAdminClaim(player)) {
         return "Operators only.";
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return "No server.";
         } else if (targetName != null && !targetName.isBlank()) {
            ResourceKey<Level> dim = player.level().dimension();
            AdminTerritories zones = AdminTerritories.get(server);
            AdminTerritories.Territory named = zones.get(dim, territory);
            if (named == null) {
               return "No safezone called " + territory + " here.";
            } else if (named.warzone()) {
               return "Warzones always follow fixed rules and cannot be edited.";
            } else {
               UUID id = resolvePlayer(server, targetName);
               if (id == null) {
                  return "Never seen a player called " + targetName + ".";
               } else {
                  if (add) {
                     zones.addMember(dim, territory, id);
                  } else {
                     zones.removeMember(dim, territory, id);
                  }

                  return "";
               }
            }
         } else {
            return "Type a player name first.";
         }
      }
   }

   private static UUID resolvePlayer(MinecraftServer server, String name) {
      ServerPlayer online = server.getPlayerList().getPlayerByName(name);
      if (online != null) {
         return online.getUUID();
      } else {
         if (server.getProfileCache() != null) {
            Optional<GameProfile> profile = server.getProfileCache().get(name);
            if (profile.isPresent()) {
               return profile.get().getId();
            }
         }

         return null;
      }
   }

   public static FactionsBridge.FactionInfo factionInfo(ServerPlayer player) {
      if (loaded() && player != null) {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return FactionsBridge.FactionInfo.empty();
         } else {
            UUID uuid = player.getUUID();
            FactionStateManager fsm = FactionStateManager.get(server);
            Faction f = fsm.getFactionByPlayer(uuid);
            if (f == null) {
               List<String> invites = fsm.getInvitesForPlayer(uuid);
               return new FactionsBridge.FactionInfo(
                  true, false, "", 16777215, "", false, false, false, "", List.of(), invites != null ? invites : List.of(), List.of(), 0, 0, Upkeep.Status.NONE, FactionsBridge.Extras.NONE
               );
            } else {
               Set<UUID> officers = f.getOfficers() != null ? f.getOfficers() : Set.of();
               boolean isOwner = uuid.equals(f.getOwner());
               boolean isOfficer = officers.contains(uuid);
               LinkedHashSet<UUID> all = new LinkedHashSet<>();
               if (f.getOwner() != null) {
                  all.add(f.getOwner());
               }

               all.addAll(officers);
               if (f.getMembers() != null) {
                  all.addAll(f.getMembers());
               }

               List<FactionsBridge.Member> members = new ArrayList<>();

               for (UUID id : all) {
                  int role = id.equals(f.getOwner()) ? 2 : (officers.contains(id) ? 1 : 0);
                  members.add(new FactionsBridge.Member(nameOf(server, id), role));
               }

               List<String> invited = new ArrayList<>();
               if (f.getInvited() != null) {
                  for (UUID id : f.getInvited()) {
                     invited.add(nameOf(server, id));
                  }
               }

               List<FactionsBridge.Relation> relations = new ArrayList<>();
               if (f.getOutgoingRelations() != null) {
                  for (Entry<String, RelationshipStatus> en : f.getOutgoingRelations().entrySet()) {
                     relations.add(new FactionsBridge.Relation(en.getKey(), en.getValue().name()));
                  }
               }

               int cap = factionCapFor(server, f);
               int used = ClaimManager.get(server).getFactionClaimCount(f.getName());
               return new FactionsBridge.FactionInfo(
                  true,
                  true,
                  f.getName(),
                  ZoneColor.safe(f.getColor()),
                  f.getAbbreviation() != null ? f.getAbbreviation() : "",
                  isOwner,
                  isOfficer,
                  f.getFriendlyFire(),
                  nameOf(server, f.getOwner()),
                  members,
                  invited,
                  relations,
                  cap,
                  used,
                  Upkeep.status(server, f.getName()),
                  extrasFor(server, f, uuid, isOwner || isOfficer)
               );
            }
         }
      } else {
         return FactionsBridge.FactionInfo.empty();
      }
   }

   public static String revokeInvite(ServerPlayer player, String targetName) {
      if (!loaded()) {
         return "Holdfast Factions is not installed.";
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return "No server.";
         } else {
            FactionStateManager fsm = FactionStateManager.get(server);
            UUID uuid = player.getUUID();
            Faction f = fsm.getFactionByPlayer(uuid);
            if (f == null) {
               return "You are not in a faction.";
            } else if (!fsm.playerIsOwnerOrOfficer(uuid)) {
               return "Only the owner or an officer can revoke invites.";
            } else {
               if (f.getInvited() != null) {
                  for (UUID id : f.getInvited()) {
                     if (nameOf(server, id).equalsIgnoreCase(targetName)) {
                        try {
                           fsm.revokeInvitation(player, id);
                           return "";
                        } catch (RuntimeException var9) {
                           return var9.getMessage() != null ? var9.getMessage() : "Could not revoke invite.";
                        }
                     }
                  }
               }

               return "No pending invite for " + targetName + ".";
            }
         }
      }
   }

   public static String disband(ServerPlayer player) {
      if (!loaded()) {
         return "Holdfast Factions is not installed.";
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return "No server.";
         } else {
            FactionStateManager fsm = FactionStateManager.get(server);
            if (!fsm.playerOwnsFaction(player.getUUID())) {
               return "Only the faction owner can disband.";
            } else {
               Faction f = fsm.getFactionByPlayer(player.getUUID());
               if (f == null) {
                  return "You are not in a faction.";
               } else {
                  fsm.disbandFaction(f.getName(), server);
                  return "";
               }
            }
         }
      }
   }

   public static String chunkOwnerDisplay(MinecraftServer server, ResourceKey<Level> dim, ChunkPos pos) {
      if (loaded() && server != null) {
         ClaimManager cm = ClaimManager.get(server);
         if (!cm.isClaimed(dim, pos)) {
            return "";
         } else {
            ClaimData d = cm.getClaim(dim, pos);
            if (d == null) {
               return "";
            } else if (d.type == ClaimType.FACTION) {
               return d.owner;
            } else if (d.type == ClaimType.CORE) {
               try {
                  return nameOf(server, UUID.fromString(d.owner));
               } catch (IllegalArgumentException var6) {
                  return d.owner;
               }
            } else {
               String named = AdminTerritories.get(server).nameAt(dim, pos.toLong());
               return named.isEmpty() ? "Safezone" : named;
            }
         }
      } else {
         return "";
      }
   }

   public static int[] claimColorGrid(MinecraftServer server, ResourceKey<Level> dim, ChunkPos center, int radius) {
      int span = 2 * radius + 1;
      int[] grid = new int[span * span];
      if (loaded() && server != null) {
         ClaimManager cm = ClaimManager.get(server);
         int idx = 0;

         for (int j = 0; j < span; j++) {
            for (int i = 0; i < span; i++) {
               ChunkPos cp = new ChunkPos(center.x - radius + i, center.z - radius + j);
               if (cm.isClaimed(dim, cp)) {
                  ClaimData d = cm.getClaim(dim, cp);
                  int shown = 0;
                  if (d != null) {
                     shown = ZoneColor.safe(d.color);
                     if (d.type == ClaimType.ADMIN) {
                        AdminTerritories.Territory zone = AdminTerritories.get(server).governing(dim, cp.toLong());
                        shown = zone != null && zone.warzone() ? ZoneColor.WARZONE : ZoneColor.SAFEZONE;
                     }
                  }

                  grid[idx] = d != null ? 0xFF000000 | shown : 0;
               }

               idx++;
            }
         }

         return grid;
      } else {
         return grid;
      }
   }

   public static List<FactionsBridge.MapLabel> claimLabels(MinecraftServer server, ResourceKey<Level> dim, ChunkPos center, int radius) {
      List<FactionsBridge.MapLabel> out = new ArrayList<>();
      if (loaded() && server != null) {
         ClaimManager cm = ClaimManager.get(server);
         TerritoryNames names = TerritoryNames.get(server);
         AdminTerritories adminZones = AdminTerritories.get(server);
         Map<Long, String> labelByChunk = new HashMap<>();

         for (int j = -radius; j <= radius; j++) {
            for (int i = -radius; i <= radius; i++) {
               ChunkPos cp = new ChunkPos(center.x + i, center.z + j);
               if (cm.isClaimed(dim, cp)) {
                  ClaimData d = cm.getClaim(dim, cp);
                  if (d != null) {
                     String label;
                     if (d.type == ClaimType.ADMIN) {
                        String named = adminZones.nameAt(dim, ChunkPos.asLong(cp.x, cp.z));
                        label = named.isEmpty() ? "Safezone" : named;
                     } else {
                        label = claimLabel(server, names, d);
                     }

                     labelByChunk.put(ChunkPos.asLong(cp.x, cp.z), label);
                  }
               }
            }
         }

         Set<Long> seen = new HashSet<>();

         for (Entry<Long, String> en : labelByChunk.entrySet()) {
            long sk = en.getKey();
            if (seen.add(sk)) {
               String label = en.getValue();
               ArrayDeque<Long> q = new ArrayDeque<>();
               q.add(sk);
               double sumX = 0.0;
               double sumZ = 0.0;
               int count = 0;

               while (!q.isEmpty()) {
                  long c = q.poll();
                  sumX += (double)ChunkPos.getX(c);
                  sumZ += (double)ChunkPos.getZ(c);
                  count++;

                  for (long n : neighbours(c)) {
                     if (label.equals(labelByChunk.get(n)) && seen.add(n)) {
                        q.add(n);
                     }
                  }
               }

               int dx = (int)Math.round(sumX / (double)count) - center.x;
               int dz = (int)Math.round(sumZ / (double)count) - center.z;
               out.add(new FactionsBridge.MapLabel(dx, dz, label));
               if (out.size() >= 16) {
                  break;
               }
            }
         }

         return out;
      } else {
         return out;
      }
   }

   private static String nameOf(MinecraftServer server, UUID id) {
      if (id == null) {
         return "?";
      } else {
         ServerPlayer online = server.getPlayerList().getPlayer(id);
         if (online != null) {
            return online.getGameProfile().getName();
         } else {
            if (server.getProfileCache() != null) {
               Optional<GameProfile> gp = server.getProfileCache().get(id);
               if (gp.isPresent()) {
                  return gp.get().getName();
               }
            }

            String s = id.toString();
            return s.length() > 8 ? s.substring(0, 8) : s;
         }
      }
   }

   public static record AdminZoneInfo(
      String name, int color, int perms, boolean custom, int chunks, List<String> members, boolean worldGuard
   ) {
   }

   public static record ClaimCell(int x, int z, int kind, int color, String label) {
   }

   public static record Ctx(
      boolean coreLoaded,
      boolean inFaction,
      boolean canFactionClaim,
      boolean canAdminClaim,
      boolean canPersonalClaim,
      String factionName,
      int factionColor,
      int coreCap,
      int coreUsed,
      int factionCap,
      int factionUsed
   ) {
      public static FactionsBridge.Ctx empty() {
         return new FactionsBridge.Ctx(false, false, false, false, true, "", 16777215, 0, 0, 0, 0);
      }
   }

   public static enum Decision {
      ALLOW,
      DENY,
      DEFER;
   }

   public static record DisbandResult(int kept, int released) {
      public static final FactionsBridge.DisbandResult NONE = new FactionsBridge.DisbandResult(0, 0);
   }

   public static record FactionInfo(
      boolean coreLoaded,
      boolean inFaction,
      String name,
      int color,
      String abbreviation,
      boolean isOwner,
      boolean isOfficer,
      boolean friendlyFire,
      String ownerName,
      List<FactionsBridge.Member> members,
      List<String> invitesForViewer,
      List<FactionsBridge.Relation> relations,
      int factionCap,
      int factionUsed,
      Upkeep.Status upkeep,
      FactionsBridge.Extras extras
   ) {
      public static FactionsBridge.FactionInfo empty() {
         return new FactionsBridge.FactionInfo(
            false, false, "", 16777215, "", false, false, false, "", List.of(), List.of(), List.of(), 0, 0, Upkeep.Status.NONE, FactionsBridge.Extras.NONE
         );
      }
   }

   public static record Contribution(String name, long value) {
   }

   public static record Extras(
      int doors, int utility, int deposit, boolean canEdit, List<FactionsBridge.Contribution> top, long mine, List<String> atWar, int warnMinutes
   ) {
      public static final FactionsBridge.Extras NONE = new FactionsBridge.Extras(0, 0, 0, false, List.of(), 0L, List.of(), 0);
   }

   private static FactionsBridge.Extras extrasFor(MinecraftServer server, Faction f, UUID viewer, boolean canEdit) {
      FactionSettings settings = FactionSettings.get(server);
      Map<UUID, Long> given = settings.contributions(f.getName());
      List<FactionsBridge.Contribution> top = new ArrayList<>();
      given.entrySet().stream()
         .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
         .limit(5)
         .forEach(e -> top.add(new FactionsBridge.Contribution(nameOf(server, e.getKey()), e.getValue())));
      List<String> atWar = new ArrayList<>(WarState.opponentsOf(server, f.getName()));
      atWar.sort(String::compareToIgnoreCase);
      return new FactionsBridge.Extras(
         settings.doors(f.getName()),
         settings.utility(f.getName()),
         settings.deposit(f.getName()),
         canEdit,
         top,
         given.getOrDefault(viewer, 0L),
         atWar,
         (int)Math.min(Integer.MAX_VALUE, TerritoryConfig.upkeepWarnMinutes())
      );
   }

   public static String setFactionSetting(ServerPlayer player, String key, int level) {
      if (!loaded()) {
         return "Holdfast Factions is not installed.";
      } else {
         MinecraftServer server = player.getServer();
         if (server == null) {
            return "No server.";
         } else {
            FactionStateManager fsm = FactionStateManager.get(server);
            Faction f = fsm.getFactionByPlayer(player.getUUID());
            if (f == null) {
               return "You are not in a faction.";
            } else if (!fsm.playerIsOwnerOrOfficer(player.getUUID())) {
               return "Only the faction owner or an officer can change this.";
            } else {
               FactionSettings settings = FactionSettings.get(server);
               switch (key) {
                  case "doors" -> settings.setDoors(f.getName(), level);
                  case "utility" -> settings.setUtility(f.getName(), level);
                  case "deposit" -> settings.setDeposit(f.getName(), level);
                  default -> {
                     return "";
                  }
               }

               return "";
            }
         }
      }
   }

   public static record MapLabel(int dx, int dz, String name) {
   }

   public static record Member(String name, int role) {
   }

   public static record Relation(String faction, String status) {
   }
}
