package dev.xmat5.holdfast.server.event_handlers;

import dev.xmat5.holdfast.network.NetworkManager;
import dev.xmat5.holdfast.server.ServerConfig;
import dev.xmat5.holdfast.server.alliance.Alliance;
import dev.xmat5.holdfast.server.alliance.AllianceStateManager;
import dev.xmat5.holdfast.server.api.events.FactionChangeColorEvent;
import dev.xmat5.holdfast.server.api.events.FactionDisbandEvent;
import dev.xmat5.holdfast.server.claims.ChunkInteractionType;
import dev.xmat5.holdfast.server.claims.ClaimManager;
import dev.xmat5.holdfast.server.claims.model.ClaimData;
import dev.xmat5.holdfast.server.faction.Faction;
import dev.xmat5.holdfast.server.faction.FactionStateManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent.Detonate;
import net.neoforged.neoforge.event.level.PistonEvent.Pre;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import top.leonx.territory.integration.FactionsBridge;

@EventBusSubscriber(
   modid = "holdfast_factions"
)
public class ClaimEventHandler {
   @SubscribeEvent
   public static void onPlayerLogin(PlayerLoggedInEvent event) {
      if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
         HashMap<ResourceLocation, HashMap<Long, Integer>> claims = new HashMap<>();
         ClaimManager claimManager = ClaimManager.get(player.getServer());

         for (Entry<ResourceKey<Level>, Map<Long, ClaimData>> entry : claimManager.getClaimMap().entrySet()) {
            HashMap<Long, Integer> currentDimClaims = new HashMap<>();

            for (Entry<Long, ClaimData> entry2 : entry.getValue().entrySet()) {
               currentDimClaims.put(entry2.getKey(), entry2.getValue().color);
            }

            claims.put(entry.getKey().location(), currentDimClaims);
         }

         NetworkManager.sendClaimsToPlayer(claims, player);
      }
   }


   @SubscribeEvent
   public static void onBlockBreak(BreakEvent event) {
      if (!playerHasPermission(
         event.getPlayer(), event.getPos(), event.getPlayer().level().dimension(), ChunkInteractionType.BREAK_BLOCK, event.getLevel().getServer()
      )) {
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public static void onBlockPlace(EntityPlaceEvent event) {
      if (event.getEntity() instanceof Player player) {
         if (!playerHasPermission(player, event.getPos(), player.level().dimension(), ChunkInteractionType.PLACE_BLOCK, event.getLevel().getServer())) {
            event.setCanceled(true);
         } else if (event.getEntity() instanceof TamableAnimal animal && !animal.isOwnedBy(player)) {
            event.setCanceled(true);
         }
      }
   }

   @SubscribeEvent
   public static void onBlockInteract(RightClickBlock event) {
      Player player = event.getEntity();
      MinecraftServer server = event.getEntity().getServer();
      if (server != null) {
         if (!playerHasPermission(player, event.getPos(), player.level().dimension(), ChunkInteractionType.RIGHT_CLICK_BLOCK, server)) {
            event.setCanceled(true);
         }
      }
   }

   @SubscribeEvent
   public static void onBlockLeftClick(LeftClickBlock event) {
      Player player = event.getEntity();
      MinecraftServer server = event.getEntity().getServer();
      if (server != null) {
         if (!playerHasPermission(player, event.getPos(), player.level().dimension(), ChunkInteractionType.LEFT_CLICK_BLOCK, server)) {
            event.setCanceled(true);
         }
      }
   }

   @SubscribeEvent
   public static void onItemRightClick(RightClickItem event) {
      Player player = event.getEntity();
      MinecraftServer server = event.getEntity().getServer();
      if (server != null) {
         if (!playerHasPermission(player, event.getPos(), player.level().dimension(), ChunkInteractionType.RIGHT_CLICK_ITEM, server)) {
            event.setCanceled(true);
         }
      }
   }

   @SubscribeEvent
   public static void onEntityInteract(EntityInteract event) {
      Player player = event.getEntity();
      MinecraftServer server = event.getEntity().getServer();
      if (server != null) {
         if (!playerHasPermission(player, event.getPos(), player.level().dimension(), ChunkInteractionType.INTERACT_ENTITY, server)) {
            event.setCanceled(true);
         }
      }
   }

   @SubscribeEvent
   public static void onMobGriefing(EntityMobGriefingEvent event) {
      if (event.getEntity() != null) {
         MinecraftServer server = event.getEntity().getServer();
         if (server != null) {
            ClaimManager claimManager = ClaimManager.get(server);
            ChunkPos chunkPos = event.getEntity().chunkPosition();
            ResourceKey<Level> dimension = event.getEntity().level().dimension();
            if (claimManager.isClaimed(dimension, chunkPos) && !FactionsBridge.handsOff(server, dimension, event.getEntity().blockPosition())) {
               ClaimData claim = claimManager.getClaim(dimension, chunkPos);
               switch (claim.type) {
                  case FACTION:
                     if (ServerConfig.factionClaimRestrictions.contains(ChunkInteractionType.MOB_GRIEFING_DAMAGE)) {
                        event.setCanGrief(false);
                     }
                     break;
                  case CORE:
                     if (ServerConfig.coreClaimRestrictions.contains(ChunkInteractionType.MOB_GRIEFING_DAMAGE)) {
                        event.setCanGrief(false);
                     }
                     break;
                  case ADMIN:
                     if (ServerConfig.adminClaimRestrictions.contains(ChunkInteractionType.MOB_GRIEFING_DAMAGE)) {
                        event.setCanGrief(false);
                     }
               }
            }
         }
      }
   }

   @SubscribeEvent
   public static void onExplosionDetonate(Detonate event) {
      if (!event.getLevel().isClientSide()) {
         MinecraftServer server = event.getLevel().getServer();
         if (server != null) {
            ClaimManager claimManager = ClaimManager.get(server);
            event.getAffectedBlocks().removeIf(blockPos -> {
               ChunkPos chunkPos = new ChunkPos(blockPos);
               ResourceKey<Level> dimension = event.getLevel().dimension();
               if (!claimManager.isClaimed(dimension, chunkPos) || FactionsBridge.handsOff(server, dimension, blockPos)) {
                  return false;
               } else {
                  ClaimData claim = claimManager.getClaim(dimension, chunkPos);

                  return switch (claim.type) {
                     case FACTION -> ServerConfig.factionClaimRestrictions.contains(ChunkInteractionType.EXPLOSION_DAMAGE);
                     case CORE -> ServerConfig.coreClaimRestrictions.contains(ChunkInteractionType.EXPLOSION_DAMAGE);
                     case ADMIN -> ServerConfig.adminClaimRestrictions.contains(ChunkInteractionType.EXPLOSION_DAMAGE);
                  };
               }
            });
         }
      }
   }

   @SubscribeEvent
   public static void onPistonMove(Pre event) {
      if (event.getLevel() instanceof Level level) {
         if (!event.getLevel().isClientSide()) {
            MinecraftServer server = event.getLevel().getServer();
            if (server != null) {
               ClaimManager claimManager = ClaimManager.get(server);
               ChunkPos pistonChunk = new ChunkPos(event.getPos());
               if (claimManager.isClaimed(level.dimension(), pistonChunk) && !FactionsBridge.handsOff(server, level.dimension(), event.getPos())) {
                  ClaimData claim = claimManager.getClaim(level.dimension(), pistonChunk);

                  boolean restricted = switch (claim.type) {
                     case FACTION -> ServerConfig.factionClaimRestrictions.contains(ChunkInteractionType.PISTON_MOVE);
                     case CORE -> ServerConfig.coreClaimRestrictions.contains(ChunkInteractionType.PISTON_MOVE);
                     case ADMIN -> ServerConfig.adminClaimRestrictions.contains(ChunkInteractionType.PISTON_MOVE);
                  };
                  if (restricted) {
                     event.setCanceled(true);
                  }
               }
            }
         }
      }
   }

   @SubscribeEvent
   public static void onBucketUse(RightClickBlock event) {
      if (!event.getLevel().isClientSide()) {
         MinecraftServer server = event.getEntity().getServer();
         if (server != null) {
            Item item = event.getItemStack().getItem();
            if (item instanceof BucketItem || item instanceof MobBucketItem) {
               BlockPos targetPos = event.getPos();
               if (!playerHasPermission(event.getEntity(), targetPos, event.getLevel().dimension(), ChunkInteractionType.USE_BUCKET, server)) {
                  event.setCanceled(true);
               }
            }
         }
      }
   }

   @SubscribeEvent
   public static void onEntityAttacked(LivingIncomingDamageEvent event) {
      MinecraftServer server = event.getEntity().getServer();
      if (server != null) {
         ClaimManager claimManager = ClaimManager.get(server);
         if (event.getSource().getEntity() instanceof Player player) {
            if (!claimManager.isClaimed(event.getEntity().level().dimension(), event.getEntity().chunkPosition())
               || FactionsBridge.handsOff(server, event.getEntity().level().dimension(), event.getEntity().blockPosition())) {
               return;
            }

            ClaimData claim = claimManager.getClaim(event.getEntity().level().dimension(), event.getEntity().chunkPosition());

            boolean restricted = switch (claim.type) {
               case FACTION -> ServerConfig.factionClaimRestrictions.contains(ChunkInteractionType.PLAYER_ATTACK);
               case CORE -> ServerConfig.coreClaimRestrictions.contains(ChunkInteractionType.PLAYER_ATTACK);
               case ADMIN -> ServerConfig.adminClaimRestrictions.contains(ChunkInteractionType.PLAYER_ATTACK);
            };
            if (restricted) {
               event.setCanceled(true);
            }
         }
      }
   }

   @SubscribeEvent
   public static void onFactionColorChange(FactionChangeColorEvent event) {
      MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
      ClaimManager.get(server).changeFactionColor(event.getFaction().getName(), event.getFaction().getColor(), server);
   }

   @SubscribeEvent
   public static void onFactionDisband(FactionDisbandEvent event) {
      MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
      ClaimManager.get(server).deleteFactionData(event.getFaction().getName());
   }

   public static boolean playerHasPermission(Player player, BlockPos pos, ResourceKey<Level> dimension, ChunkInteractionType type, MinecraftServer server) {
      return FactionsBridge.handsOff(server, dimension, pos) || playerHasPermission(player, new ChunkPos(pos), dimension, type, server);
   }

   public static boolean playerHasPermission(Player player, ChunkPos pos, ResourceKey<Level> dimension, ChunkInteractionType type, MinecraftServer server) {
      ClaimManager claimManager = ClaimManager.get(server);
      if (!claimManager.isClaimed(dimension, pos)) {
         return true;
      } else {
         ClaimData claim = claimManager.getClaim(dimension, pos);
         if (player.hasPermissions(2)) {
            return true;
         } else {
            switch (claim.type) {
               case FACTION:
                  if (!ServerConfig.factionClaimRestrictions.contains(type)) {
                     return true;
                  }

                  FactionStateManager factionManager = FactionStateManager.get(server);
                  Faction faction = factionManager.getFactionByPlayer(player.getUUID());
                  if (faction != null) {
                     return faction.getName().equals(claim.owner);
                  }
                  break;
               case CORE:
                  if (!ServerConfig.coreClaimRestrictions.contains(type)) {
                     return true;
                  }

                  Set<Long> coreChunks = claimManager.getPlayerCoreChunks(UUID.fromString(claim.owner)).get(dimension);
                  if (coreChunks != null) {
                     return coreChunks.contains(pos.toLong());
                  }
                  break;
               case ADMIN:
                  return !ServerConfig.adminClaimRestrictions.contains(type);
            }

            return false;
         }
      }
   }
}
