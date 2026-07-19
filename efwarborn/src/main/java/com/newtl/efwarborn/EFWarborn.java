package com.newtl.efwarborn;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warborn Realms add-ons bundled into the Easy Factions NeoForge port. Server-side only behaviour:
 * adds /factionbuy so a faction can buy extra chunks past its claim cap with in-game money.
 *
 * Everything else the server wants (300 cap, member-scaled limits, 9 personal chunks, place/break-only
 * protection) is handled by the port's own config; this mod adds only the one thing that needs code.
 */
@Mod(EFWarborn.MODID)
public class EFWarborn {

    public static final String MODID = "efwarborn";
    public static final Logger LOGGER = LoggerFactory.getLogger("Easy Factions Warborn");

    public EFWarborn(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, WarbornConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FactionBuyCommand.register(event.getDispatcher());
        // NOTE: do NOT read WarbornConfig values here. RegisterCommandsEvent fires during world load,
        // before the SERVER config is bound, so calling .get() throws "Cannot get config value before
        // config is loaded." Config reads belong in the command's executes() path, which runs later.
        LOGGER.info("Easy Factions Warborn: /factionbuy registered.");
    }
}
