package top.leonx.territory.war;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.EasyFactionsBridge;

/**
 * "This land is full of enemy claims and I can't build here, so I will go and take it off them."
 *
 * Killing a member of a rival faction costs that faction claim capacity and pays a share of it into the
 * killing faction's collective pool. While the victim is under their cap this only lowers the ceiling; once
 * the ceiling falls below what they actually hold, real chunks come off the map nearest the death site, so
 * winning a piece of ground means fighting on that ground.
 *
 * All Easy Factions contact happens inside {@link EasyFactionsBridge}, so this class never loads an EF type
 * and stays safe on a server without EF installed.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ConquestHandler {

    private static final Logger LOG = LoggerFactory.getLogger("territory-conquest");

    private ConquestHandler() {}

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        if (!TerritoryConfig.conquestEnabled() || !EasyFactionsBridge.loaded()) return;

        MinecraftServer server = victim.getServer();
        if (server == null) return;

        EasyFactionsBridge.ConquestResult result = EasyFactionsBridge.applyKill(victim, killer);
        if (!result.applied() || (result.slotsLost() == 0 && result.chunksTaken() == 0)) return;

        announce(server, result);
    }

    /** Tell the whole server, so land changing hands is visible and worth contesting. */
    private static void announce(MinecraftServer server, EasyFactionsBridge.ConquestResult result) {
        StringBuilder msg = new StringBuilder()
                .append(result.killerFaction())
                .append(" took ")
                .append(result.slotsLost())
                .append(result.slotsLost() == 1 ? " claim slot from " : " claim slots from ")
                .append(result.victimFaction());
        if (result.chunksTaken() > 0) {
            msg.append(", forcing ")
               .append(result.chunksTaken())
               .append(result.chunksTaken() == 1 ? " chunk" : " chunks")
               .append(" off the map");
        }
        if (result.slotsGained() > 0) {
            msg.append(" (+").append(result.slotsGained()).append(" to their pool)");
        }
        msg.append('!');
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(msg.toString()).withStyle(ChatFormatting.GOLD), false);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!TerritoryConfig.conquestEnabled() || !EasyFactionsBridge.loaded()) return;
        int interval = TerritoryConfig.penaltyRegenTicks();
        if (interval <= 0) return;          // losses configured to be permanent
        EasyFactionsBridge.regenPenalties(event.getServer(), interval);
    }

    /**
     * Easy Factions ships its own kill-steals-land handler and it will double up with ours. Warn loudly
     * rather than silently fighting it, because EF's version removes the victim chunk nearest the KILLER'S
     * LEADER'S personal chunks and falls back to chunk (0,0) when that leader has none, which eats land near
     * world spawn instead of near the fight.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!TerritoryConfig.conquestEnabled() || !EasyFactionsBridge.loaded()) return;
        int efPoints = EasyFactionsBridge.efPointsPerKill();
        if (efPoints > 0) {
            LOG.warn("Easy Factions pointsPerKill={} is still enabled alongside Territory's conquest system, "
                    + "so a single kill will take land TWICE (and EF takes it near world spawn, not near the "
                    + "fight). Set pointsPerKill = 0 in easy_factions-server.toml.", efPoints);
        }
    }
}
