package top.leonx.territory.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.client.render.TerritoryTableBlockEntityRenderer;
import top.leonx.territory.client.screen.TerritoryTableScreen;

/** Client-only mod-bus wiring: bind the Territory Table menu to its screen + its BlockEntity renderer. */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(TerritoryMod.TERRITORY_MENU.get(), TerritoryTableScreen::new);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TerritoryMod.TERRITORY_BE.get(), TerritoryTableBlockEntityRenderer::new);
    }
}
