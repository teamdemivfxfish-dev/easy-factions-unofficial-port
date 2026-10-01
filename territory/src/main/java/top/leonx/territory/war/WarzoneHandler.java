package top.leonx.territory.war;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import top.leonx.territory.integration.FactionsBridge;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.GAME
)
public final class WarzoneHandler {
   private WarzoneHandler() {
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST,
      receiveCanceled = true
   )
   public static void onPlayerDamage(LivingIncomingDamageEvent event) {
      if (event.getEntity() instanceof ServerPlayer victim && event.getSource().getEntity() instanceof ServerPlayer attacker && attacker != victim) {
         MinecraftServer server = victim.getServer();
         if (server != null && FactionsBridge.inWarzone(server, victim.level().dimension(), victim.chunkPosition())) {
            event.setCanceled(FactionsBridge.sameFaction(server, attacker.getUUID(), victim.getUUID()));
         }
      }
   }
}
