package top.leonx.territory.integration;

import dev.xmat5.holdfast.server.api.events.FactionChangeColorEvent;
import dev.xmat5.holdfast.server.claims.ClaimManager;
import dev.xmat5.holdfast.server.faction.Faction;
import dev.xmat5.holdfast.server.faction.FactionStateManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import top.leonx.territory.world.TerritoryNames;
import top.leonx.territory.world.ZoneColor;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.GAME
)
public final class FactionColorGuard {

   private FactionColorGuard() {
   }

   static boolean sanitize(Faction faction) {
      if (faction != null && ZoneColor.isReserved(faction.getColor())) {
         faction.setColor(ZoneColor.safe(faction.getColor()));
         return true;
      }

      return false;
   }

   @SubscribeEvent(
      priority = EventPriority.HIGHEST
   )
   public static void onFactionColor(FactionChangeColorEvent event) {
      if (sanitize(event.getFaction())) {
         ServerPlayer player = event.getPlayer();
         if (player != null) {
            player.sendSystemMessage(Component.literal(FactionsBridge.RESERVED_COLOR_MESSAGE + " Your faction colour was adjusted.").withStyle(ChatFormatting.RED));
         }
      }
   }

   @SubscribeEvent
   public static void onServerStarted(ServerStartedEvent event) {
      MinecraftServer server = event.getServer();
      if (FactionsBridge.loaded()) {
         FactionStateManager fsm = FactionStateManager.get(server);
         ClaimManager claims = ClaimManager.get(server);
         boolean changed = false;

         for (String name : fsm.getAllFactionNames()) {
            Faction faction = fsm.getFactionByName(name);
            if (sanitize(faction)) {
               claims.changeFactionColor(name, faction.getColor(), server);
               changed = true;
            }
         }

         if (changed) {
            fsm.setDirty();
         }

         TerritoryNames.get(server).sanitizeColors();
      }
   }
}
