package top.leonx.territory.integration;

import com.jpreiss.easy_factions.server.api.events.FactionCreateEvent;
import com.jpreiss.easy_factions.server.api.events.FactionDisbandEvent;
import com.jpreiss.easy_factions.server.faction.Faction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.world.ClaimPenalties;
import top.leonx.territory.world.PurchasedClaims;

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
 * The method signatures below name Easy Factions types, so this class must not be loaded on a server without
 * Easy Factions. {@code @EventBusSubscriber} would load it during mod construction unconditionally; calling
 * {@link #register()} from common setup behind a {@link EasyFactionsBridge#loaded()} check means the class
 * is only ever touched when Easy Factions is really there.
 */
public final class FactionLifecycle {

    private FactionLifecycle() {}

    public static void register() {
        if (!EasyFactionsBridge.loaded()) return;
        NeoForge.EVENT_BUS.addListener(FactionLifecycle::onFactionCreated);
        // HIGHEST so this runs BEFORE Easy Factions' own listener, which calls deleteFactionData and takes
        // the faction's claims out of the index we need to read
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, FactionLifecycle::onFactionDisbanded);
    }

    private static void onFactionCreated(FactionCreateEvent event) {
        ServerPlayer founder = event.getCreator();
        if (founder == null) return;
        MinecraftServer server = founder.getServer();
        if (server == null) return;

        int converted = EasyFactionsBridge.convertPersonalToFaction(server, founder, event.getFactionName());
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

        EasyFactionsBridge.DisbandResult result = EasyFactionsBridge.revertFactionToPersonal(server, name, ownerId);

        // don't let bought slots or war debts haunt a future faction that happens to reuse the name
        PurchasedClaims.get(server).clear(name);
        ClaimPenalties.get(server).clear(name);

        ServerPlayer owner = ownerId != null ? server.getPlayerList().getPlayer(ownerId) : null;
        if (owner == null || (result.kept() == 0 && result.released() == 0)) return;

        String msg = TerritoryConfig.leaderClaimsBecomeFaction()
                ? result.kept() + " chunks are yours personally again"
                + (result.released() > 0 ? ", and " + result.released() + " were released." : ".")
                : result.released() + " chunks were released.";
        owner.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
    }
}
