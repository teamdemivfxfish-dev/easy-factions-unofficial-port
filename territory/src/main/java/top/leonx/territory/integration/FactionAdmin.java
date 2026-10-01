package top.leonx.territory.integration;

import dev.xmat5.holdfast.server.faction.Faction;
import dev.xmat5.holdfast.server.faction.FactionStateManager;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class FactionAdmin {
   private FactionAdmin() {
   }

   public static List<String> factionNames(MinecraftServer server) {
      if (FactionsBridge.loaded() && server != null) {
         Set<String> names = FactionStateManager.get(server).getAllFactionNames();
         List<String> out = new ArrayList<>(names);
         out.sort(String.CASE_INSENSITIVE_ORDER);
         return out;
      } else {
         return List.of();
      }
   }

   public static FactionAdmin.Result forceAdd(MinecraftServer server, ServerPlayer target, String factionName) {
      if (!FactionsBridge.loaded()) {
         return FactionAdmin.Result.fail("Holdfast Factions is not installed.");
      } else if (server != null && target != null) {
         FactionStateManager fsm = FactionStateManager.get(server);
         Faction faction = fsm.getFactionByName(factionName);
         if (faction == null) {
            return FactionAdmin.Result.fail("There is no faction called \"" + factionName + "\".");
         } else {
            UUID uuid = target.getUUID();
            Faction current = fsm.getFactionByPlayer(uuid);
            if (current != null) {
               return current.getName().equals(faction.getName())
                  ? FactionAdmin.Result.fail(target.getGameProfile().getName() + " is already in " + faction.getName() + ".")
                  : FactionAdmin.Result.fail(
                     target.getGameProfile().getName()
                        + " is already in "
                        + current.getName()
                        + ". Take them out of it first: /territory faction remove "
                        + target.getGameProfile().getName()
                  );
            } else {
               boolean alreadyInvited = faction.getInvited().contains(uuid);
               if (!alreadyInvited) {
                  faction.getInvited().add(uuid);
               }

               try {
                  fsm.joinFaction(target, faction.getName(), server);
               } catch (RuntimeException var9) {
                  if (!alreadyInvited) {
                     faction.getInvited().remove(uuid);
                  }

                  return FactionAdmin.Result.fail(var9.getMessage() != null ? var9.getMessage() : "Holdfast Factions refused the join.");
               }

               return FactionAdmin.Result.ok("Added " + target.getGameProfile().getName() + " to " + faction.getName() + ".");
            }
         }
      } else {
         return FactionAdmin.Result.fail("No server.");
      }
   }

   public static FactionAdmin.Result forceRemove(MinecraftServer server, ServerPlayer target) {
      if (!FactionsBridge.loaded()) {
         return FactionAdmin.Result.fail("Holdfast Factions is not installed.");
      } else if (server != null && target != null) {
         FactionStateManager fsm = FactionStateManager.get(server);
         UUID uuid = target.getUUID();
         Faction faction = fsm.getFactionByPlayer(uuid);
         String who = target.getGameProfile().getName();
         if (faction == null) {
            return FactionAdmin.Result.fail(who + " is not in a faction.");
         } else if (uuid.equals(faction.getOwner())) {
            return FactionAdmin.Result.fail(
               who
                  + " OWNS "
                  + faction.getName()
                  + ", and Holdfast Factions ends a faction when its owner leaves. Removing them would disband it and release its land. If that is what you want: /territory faction disband "
                  + faction.getName()
            );
         } else {
            String name = faction.getName();

            try {
               fsm.leaveFaction(target, server);
            } catch (RuntimeException var8) {
               return FactionAdmin.Result.fail(var8.getMessage() != null ? var8.getMessage() : "Holdfast Factions refused the removal.");
            }

            return FactionAdmin.Result.ok("Removed " + who + " from " + name + ".");
         }
      } else {
         return FactionAdmin.Result.fail("No server.");
      }
   }

   public static FactionAdmin.Result forceDisband(MinecraftServer server, String factionName) {
      if (!FactionsBridge.loaded()) {
         return FactionAdmin.Result.fail("Holdfast Factions is not installed.");
      } else if (server == null) {
         return FactionAdmin.Result.fail("No server.");
      } else {
         FactionStateManager fsm = FactionStateManager.get(server);
         Faction faction = fsm.getFactionByName(factionName);
         if (faction == null) {
            return FactionAdmin.Result.fail("There is no faction called \"" + factionName + "\".");
         } else {
            String name = faction.getName();
            int members = faction.getMembers() != null ? faction.getMembers().size() : 0;
            fsm.disbandFaction(name, server);
            return FactionAdmin.Result.ok("Disbanded " + name + " (" + members + (members == 1 ? " member" : " members") + ").");
         }
      }
   }

   public static List<String> roster(MinecraftServer server) {
      List<String> out = new ArrayList<>();
      if (FactionsBridge.loaded() && server != null) {
         FactionStateManager fsm = FactionStateManager.get(server);

         for (String name : factionNames(server)) {
            Faction f = fsm.getFactionByName(name);
            if (f != null) {
               int members = f.getMembers() != null ? f.getMembers().size() : 0;
               out.add(name + " - " + members + (members == 1 ? " member" : " members") + ", owned by " + nameOf(server, f.getOwner()));
            }
         }

         return out;
      } else {
         return out;
      }
   }

   private static String nameOf(MinecraftServer server, UUID id) {
      if (id == null) {
         return "nobody";
      } else {
         ServerPlayer online = server.getPlayerList().getPlayer(id);
         if (online != null) {
            return online.getGameProfile().getName();
         } else {
            if (server.getProfileCache() != null) {
               Optional<GameProfile> profile = server.getProfileCache().get(id);
               if (profile.isPresent()) {
                  return profile.get().getName();
               }
            }

            return id.toString().substring(0, 8);
         }
      }
   }

   public static record Result(boolean ok, String message) {
      static FactionAdmin.Result ok(String message) {
         return new FactionAdmin.Result(true, message);
      }

      static FactionAdmin.Result fail(String message) {
         return new FactionAdmin.Result(false, message);
      }
   }
}
