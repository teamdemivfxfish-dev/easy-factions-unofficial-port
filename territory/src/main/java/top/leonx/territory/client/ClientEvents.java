package top.leonx.territory.client;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.client.render.TerritoryTableBlockEntityRenderer;
import top.leonx.territory.client.screen.TerritoryTableScreen;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.MOD,
   value = {Dist.CLIENT}
)
public final class ClientEvents {
   private ClientEvents() {
   }

   @SubscribeEvent
   static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
      event.register((MenuType)TerritoryMod.TERRITORY_MENU.get(), TerritoryTableScreen::new);
   }

   @SubscribeEvent
   static void onRegisterRenderers(RegisterRenderers event) {
      event.registerBlockEntityRenderer((BlockEntityType)TerritoryMod.TERRITORY_BE.get(), TerritoryTableBlockEntityRenderer::new);
   }
}
