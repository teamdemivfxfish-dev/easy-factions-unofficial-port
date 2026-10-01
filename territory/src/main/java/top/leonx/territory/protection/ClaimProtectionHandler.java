package top.leonx.territory.protection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent.Detonate;
import net.neoforged.neoforge.event.level.PistonEvent.Pre;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.FactionsBridge;
import top.leonx.territory.integration.Upkeep;
import top.leonx.territory.world.Interaction;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.GAME
)
public final class ClaimProtectionHandler {
   private static final Logger LOG = LoggerFactory.getLogger("territory-protection");
   private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();
   private static final long WARNING_COOLDOWN_TICKS = 40L;
   private static List<BlockPos> explosionSnapshot;

   private ClaimProtectionHandler() {
   }

   @SubscribeEvent
   public static void onServerStarted(ServerStartedEvent event) {
      if (!FactionsBridge.loaded()) {
         LOG.warn("Holdfast Factions is not loaded; Territory claim protection is inactive.");
      } else if (!TerritoryConfig.protectionEnabled()) {
         LOG.warn("protectionEnabled=false in territory-server.toml: claims are enforced by Holdfast Factions alone, which cannot enforce personal claims at all.");
      } else {
         LOG.info(
            "Claim protection active. faction={} personal={} ownRestrictionList={} bypassPermissionLevel={}",
            new Object[]{
               TerritoryConfig.enforceFactionClaims(),
               TerritoryConfig.enforcePersonalClaims(),
               TerritoryConfig.useOwnRestrictions(),
               TerritoryConfig.bypassPermissionLevel()
            }
         );
         LOG.info(
            "Protected interactions: {}. Containers are {}. Holdfast Factions' wider refusals are {}.",
            new Object[]{
               TerritoryConfig.restrictedInteractions(),
               TerritoryConfig.protectContainers() ? "owner-only" : "open to everyone",
               TerritoryConfig.overrideHoldfastFactions() ? "undone to match that list" : "left in place"
            }
         );
         List<String> unknown = TerritoryConfig.unknownInteractions();
         if (!unknown.isEmpty()) {
            LOG.warn("Ignoring unrecognised entries in restrictedInteractions: {}", unknown);
         }

         if (TerritoryConfig.restrictedInteractions().isEmpty() && TerritoryConfig.useOwnRestrictions()) {
            LOG.warn("restrictedInteractions is EMPTY: claims will protect nothing.");
         }

         for (String warning : FactionsBridge.protectionWarnings()) {
            LOG.warn(warning);
         }

         List<String> overrides = TerritoryConfig.perWorldConfigOverrides(event.getServer().getWorldPath(LevelResource.ROOT));
         if (!overrides.isEmpty()) {
            LOG.warn(
               "This world overrides {} from its own serverconfig folder. Those copies WIN over config/, so edits made in config/ are being ignored. Edit the copies under <world>/serverconfig/ instead, or delete them.",
               overrides
            );
         }

         LOG.info("Run /territory diagnose in game to see what a claim actually decides for the chunk you are standing in.");
      }
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onBlockBreak(BreakEvent event) {
      apply(event.getPlayer(), event.getPos(), Interaction.BREAK_BLOCK, event::setCanceled, event.isCanceled());
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onBlockPlace(EntityPlaceEvent event) {
      if (event.getEntity() instanceof Player player) {
         apply(player, event.getPos(), Interaction.PLACE_BLOCK, event::setCanceled, event.isCanceled());
      }
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onRightClickBlock(RightClickBlock event) {
      Interaction type = isBucket(event.getItemStack())
         ? Interaction.USE_BUCKET
         : BlockKinds.rightClickKind(event.getLevel(), event.getPos());
      apply(event.getEntity(), event.getPos(), type, event::setCanceled, event.isCanceled());
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onLeftClickBlock(LeftClickBlock event) {
      apply(event.getEntity(), event.getPos(), Interaction.LEFT_CLICK_BLOCK, event::setCanceled, event.isCanceled());
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onRightClickItem(RightClickItem event) {
      apply(event.getEntity(), event.getPos(), Interaction.RIGHT_CLICK_ITEM, event::setCanceled, event.isCanceled());
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onEntityInteract(EntityInteract event) {
      Interaction kind = MountRules.isRideAttempt(event.getEntity(), event.getTarget(), event.getItemStack()) ? Interaction.MOUNT : Interaction.INTERACT_ENTITY;
      apply(event.getEntity(), event.getPos(), kind, event::setCanceled, event.isCanceled());
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onEntityAttacked(LivingIncomingDamageEvent event) {
      if (event.getSource().getEntity() instanceof Player player) {
         Interaction kind = event.getEntity() instanceof Player ? Interaction.PVP : Interaction.PLAYER_ATTACK;
         apply(player, event.getEntity().blockPosition(), kind, event::setCanceled, event.isCanceled());
      }
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onPistonMove(Pre event) {
      if (event.getLevel() instanceof Level level && !level.isClientSide()) {
         FactionsBridge.Decision decision = FactionsBridge.decideAmbient(
            level.getServer(), level.dimension(), event.getPos(), Interaction.PISTON_MOVE
         );
         if (decision == FactionsBridge.Decision.DENY) {
            event.setCanceled(true);
         } else if (decision == FactionsBridge.Decision.ALLOW) {
            event.setCanceled(false);
         }

         return;
      }
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST
   )
   public static void onMobGriefing(EntityMobGriefingEvent event) {
      if (event.getEntity() != null) {
         MinecraftServer server = event.getEntity().getServer();
         if (server != null) {
            FactionsBridge.Decision decision = FactionsBridge.decideAmbient(
               server, event.getEntity().level().dimension(), event.getEntity().blockPosition(), Interaction.MOB_GRIEFING_DAMAGE
            );
            if (decision == FactionsBridge.Decision.DENY) {
               event.setCanGrief(false);
            } else if (decision == FactionsBridge.Decision.ALLOW) {
               event.setCanGrief(true);
            }
         }
      }
   }

   @SubscribeEvent(
      priority = EventPriority.HIGHEST
   )
   public static void onExplosionBefore(Detonate event) {
      explosionSnapshot = event.getLevel().isClientSide() ? null : new ArrayList<>(event.getAffectedBlocks());
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST
   )
   public static void onExplosionAfter(Detonate event) {
      List<BlockPos> before = explosionSnapshot;
      explosionSnapshot = null;
      if (before != null && !event.getLevel().isClientSide()) {
         MinecraftServer server = event.getLevel().getServer();
         if (server != null) {
            ResourceKey<Level> dim = event.getLevel().dimension();
            List<BlockPos> affected = event.getAffectedBlocks();
            Map<Long, FactionsBridge.Decision> byChunk = new HashMap<>();
            affected.removeIf(posx -> ruling(server, dim, posx, byChunk) == FactionsBridge.Decision.DENY);

            for (BlockPos pos : before) {
               if (ruling(server, dim, pos, byChunk) == FactionsBridge.Decision.ALLOW && !affected.contains(pos)) {
                  affected.add(pos);
               }
            }

            for (BlockPos pos : new ArrayList<>(affected)) {
               if (event.getLevel().getBlockState(pos).is(TerritoryMod.TERRITORY_TABLE.get())) {
                  Upkeep.coreBlownUp(server, dim, pos);
               }
            }
         }
      }
   }

   private static FactionsBridge.Decision ruling(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos, Map<Long, FactionsBridge.Decision> cache) {
      if (FactionsBridge.handsOff(server, dim, pos)) {
         return FactionsBridge.Decision.DEFER;
      }

      ChunkPos chunk = new ChunkPos(pos);
      return cache.computeIfAbsent(chunk.toLong(), k -> FactionsBridge.decideAmbient(server, dim, chunk, Interaction.EXPLOSION_DAMAGE));
   }

   private static void apply(Player player, BlockPos pos, Interaction interaction, ClaimProtectionHandler.Canceller canceller, boolean currentlyCanceled) {
      if (player != null && pos != null && !player.level().isClientSide()) {
         FactionsBridge.Decision decision = FactionsBridge.decide(player, player.level().dimension(), pos, interaction);
         if (decision == FactionsBridge.Decision.DENY) {
            if (!currentlyCanceled) {
               warn(player, pos);
            }

            canceller.set(true);
         } else if (decision == FactionsBridge.Decision.ALLOW && currentlyCanceled) {
            canceller.set(false);
         }
      }
   }

   private static void warn(Player player, BlockPos pos) {
      MinecraftServer server = player.getServer();
      if (server != null && player instanceof ServerPlayer sp) {
         long now = player.level().getGameTime();
         Long last = LAST_WARNING.get(player.getUUID());
         if (last == null || now - last >= 40L) {
            LAST_WARNING.put(player.getUUID(), now);
            String owner = FactionsBridge.chunkOwnerDisplay(server, player.level().dimension(), new ChunkPos(pos));
            Component msg = owner.isEmpty()
               ? Component.translatable("message.territory.protected")
               : Component.translatable("message.territory.protected_by", new Object[]{owner});
            sp.displayClientMessage(msg.copy().withStyle(ChatFormatting.RED), true);
         }
      }
   }

   private static boolean isBucket(ItemStack stack) {
      return stack.getItem() instanceof BucketItem || stack.getItem() instanceof MobBucketItem;
   }

   private interface Canceller {
      void set(boolean var1);
   }
}
