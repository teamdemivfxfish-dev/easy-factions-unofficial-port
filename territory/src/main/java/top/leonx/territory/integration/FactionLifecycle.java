package top.leonx.territory.integration;

import dev.xmat5.holdfast.server.api.events.FactionCreateEvent;
import dev.xmat5.holdfast.server.api.events.FactionDisbandEvent;
import dev.xmat5.holdfast.server.faction.Faction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.world.FactionCores;
import top.leonx.territory.world.FactionSettings;

import java.util.UUID;

/**
 * The leader's land IS the faction's land.
 *
 * Founding a faction converts the founder's personal claims into faction claims, and ending one hands the
 * ex-leader back as much as his personal cap allows. From the founding onwards he claims for the faction and
 * not for himself, so his members are extending and defending one shared territory instead of the leader
 * quietly keeping a private set of chunks outside the war.
 *
 * <h2>Why these listeners are registered by hand</h2>
 * The method signatures below name Holdfast Factions types, so this class must not be loaded on a server without
 * Holdfast Factions. {@code @EventBusSubscriber} would load it during mod construction unconditionally; calling
 * {@link #register()} from common setup behind a {@link FactionsBridge#loaded()} check means the class
 * is only ever touched when Holdfast Factions is really there.
 */
public final class FactionLifecycle {

    private FactionLifecycle() {}

    public static void register() {
        if (!FactionsBridge.loaded()) return;
        NeoForge.EVENT_BUS.addListener(FactionLifecycle::onFactionCreated);
        // HIGHEST so this runs BEFORE Holdfast Factions' own listener, which calls deleteFactionData and takes
        // the faction's claims out of the index we need to read
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, FactionLifecycle::onFactionDisbanded);
    }

    private static void onFactionCreated(FactionCreateEvent event) {
        ServerPlayer founder = event.getCreator();
        if (founder == null) return;
        MinecraftServer server = founder.getServer();
        if (server == null) return;

        int converted = FactionsBridge.convertPersonalToFaction(server, founder, event.getFactionName());
        if (converted <= 0) return;
        founder.sendSystemMessage(Component.literal(
                        converted + (converted == 1 ? " personal claim is" : " personal claims are")
                                + " now " + event.getFactionName() + "'s territory. Claim for the faction from here on.")
                .withStyle(ChatFormatting.GOLD));
    }

    private static void onFactionDisbanded(FactionDisbandEvent event) {
        Faction faction = event.getFaction();
        if (faction == null) return;
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        String name = faction.getName();
        UUID ownerId = faction.getOwner();

        FactionsBridge.DisbandResult result = FactionsBridge.revertFactionToPersonal(server, name, ownerId);

        FactionCores.get(server).clear(name);
        FactionSettings.get(server).clear(name);

        ServerPlayer owner = ownerId != null ? server.getPlayerList().getPlayer(ownerId) : null;
        if (owner == null || (result.kept() == 0 && result.released() == 0)) return;

        String msg = TerritoryConfig.leaderClaimsBecomeFaction()
                ? result.kept() + " chunks are yours personally again"
                + (result.released() > 0 ? ", and " + result.released() + " were released." : ".")
                : result.released() + " chunks were released.";
        owner.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
    }
}
