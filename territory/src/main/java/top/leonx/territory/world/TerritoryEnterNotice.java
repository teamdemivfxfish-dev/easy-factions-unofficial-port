package top.leonx.territory.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.integration.FactionsBridge;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.GAME
)
public final class TerritoryEnterNotice {
   private static final int CHECK_INTERVAL_TICKS = 10;
   private static final Map<UUID, Long> LAST_CHUNK = new HashMap<>();
   private static final Map<UUID, String> LAST_TERRITORY = new HashMap<>();
   private static int countdown = 10;

   private TerritoryEnterNotice() {
   }

   @SubscribeEvent
   public static void onServerTick(Post event) {
      if (TerritoryConfig.announceTerritoryEntry() && FactionsBridge.loaded()) {
         if (--countdown <= 0) {
            countdown = 10;
            MinecraftServer server = event.getServer();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               check(server, player);
            }
         }
      }
   }

   @SubscribeEvent
   public static void onLogout(PlayerLoggedOutEvent event) {
      LAST_CHUNK.remove(event.getEntity().getUUID());
      LAST_TERRITORY.remove(event.getEntity().getUUID());
   }

   private static void check(MinecraftServer server, ServerPlayer player) {
      long chunk = player.chunkPosition().toLong() ^ (long)player.level().dimension().location().hashCode();
      Long previous = LAST_CHUNK.get(player.getUUID());
      if (previous == null || previous != chunk) {
         LAST_CHUNK.put(player.getUUID(), chunk);
         String territory = describe(server, player);
         String last = LAST_TERRITORY.put(player.getUUID(), territory);
         if (last != null && !last.equals(territory)) {
            Component msg = territory.isEmpty()
               ? Component.translatable("message.territory.entered_wilderness").withStyle(ChatFormatting.GRAY)
               : Component.translatable("message.territory.entered", new Object[]{territory}).withStyle(ChatFormatting.GOLD);
            player.displayClientMessage(msg, TerritoryConfig.entryOnActionBar());
         }
      }
   }

   private static String describe(MinecraftServer server, ServerPlayer player) {
      return FactionsBridge.chunkOwnerDisplay(server, player.level().dimension(), player.chunkPosition());
   }
}
