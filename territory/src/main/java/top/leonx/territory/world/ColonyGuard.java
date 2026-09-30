package top.leonx.territory.world;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.ClaimEconomy;
import top.leonx.territory.integration.EasyFactionsBridge;
import top.leonx.territory.integration.EasyFactionsBridge.OwnerLoss;
import top.leonx.territory.integration.MineColoniesBridge;
import top.leonx.territory.integration.Refunds;

import java.util.List;

/**
 * A colony that grows takes back the ground it grows into.
 *
 * <h2>The half of the rule that was missing</h2>
 * Refusing to claim over a town only protects towns that already exist at the moment somebody drags a
 * selection. A colony is not a fixed shape: it spreads as it builds, and land that was genuinely free when
 * a faction claimed it can be somebody's town square a week later. Left alone, that produces exactly the
 * report this was supposed to answer, a player standing in their own colony unable to touch anything,
 * because the claim got there first and nothing ever revisits it.
 *
 * <h2>Why it also runs at startup</h2>
 * The rule is new and the server is not. There are already claims sitting on colonies, taken while nothing
 * stopped them, and the people they were taken from cannot release them: releasing a claim belongs to the
 * claim owner, and the claim owner is whoever took the land. The sweep at server start is what clears those
 * without an operator having to find them one at a time, and it is the first thing this version does on the
 * live world.
 *
 * <h2>Why anyone affected is told</h2>
 * Land disappearing off the map with no explanation is indistinguishable from a bug. Everyone online in an
 * affected faction gets one line naming the town, and the claim slots the land stood on are theirs again to
 * put down somewhere else.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ColonyGuard {

    private ColonyGuard() {}

    private static final Logger LOG = LoggerFactory.getLogger("territory-colonies");

    private static int ticksUntilSweep = -1;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ticksUntilSweep = interval();
        if (!ready()) return;
        // one tick late would be tidier, but the claim map and the colony index are both fully loaded by the
        // time this fires and the whole point is that it happens before anybody logs in and claims more
        List<OwnerLoss> losses = EasyFactionsBridge.sweepColonyOverlaps(event.getServer());
        report(event.getServer(), losses, true);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (interval() <= 0 || !ready()) return;
        if (--ticksUntilSweep > 0) return;
        ticksUntilSweep = interval();
        List<OwnerLoss> losses = EasyFactionsBridge.sweepColonyOverlaps(event.getServer());
        report(event.getServer(), losses, false);
    }

    /**
     * Pay out what somebody is owed the moment they can receive it.
     *
     * Nothing new is ever owed now that land is paid for at the Buy Claims button, so what this hands over
     * is what the old pay-on-place rule took: {@link ClaimPriceMigration} puts it in the pool at startup
     * and this is where it reaches the player, without anybody having to be told to log in at a set time.
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        long paid = 0L;
        for (String key : EasyFactionsBridge.collectableKeys(player)) paid += Refunds.payOut(player, key);
        if (paid <= 0L) return;
        player.sendSystemMessage(Component.literal("Refunded " + ClaimEconomy.describe(player, paid)
                + ": claims are paid for when you buy them, not when you place them.")
                .withStyle(ChatFormatting.GOLD));
    }

    private static boolean ready() {
        return EasyFactionsBridge.loaded() && MineColoniesBridge.usable() && TerritoryConfig.respectColonyClaims();
    }

    private static int interval() {
        long ticks = TerritoryConfig.colonySweepSeconds() * 20L;
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    private static void report(MinecraftServer server, List<OwnerLoss> losses, boolean startup) {
        if (losses.isEmpty()) {
            if (startup) LOG.info("No claims are standing on colony land.");
            return;
        }
        int chunks = 0;
        for (OwnerLoss loss : losses) {
            chunks += loss.chunks();
            String town = loss.colonyName() == null || loss.colonyName().isBlank()
                    ? "a colony" : "the colony " + loss.colonyName();
            LOG.info("Released {} chunk(s) of {} back to {}.", loss.chunks(), loss.ownerDisplay(), town);

            Component msg = Component.literal(loss.chunks()
                    + (loss.chunks() == 1 ? " chunk of your land has" : " chunks of your land have")
                    + " gone back to " + town + ". A colony outranks a claim."
                    + (loss.chunks() == 1 ? " That claim is" : " Those claims are")
                    + " yours again to place somewhere else.")
                    .withStyle(ChatFormatting.GOLD);
            for (ServerPlayer sp : EasyFactionsBridge.onlineOwners(server, loss)) {
                sp.sendSystemMessage(msg);
                // anything still owed from the old pay-on-place rule goes out while they are here to take it
                if (EasyFactionsBridge.mayCollect(sp, loss.refundKey())) Refunds.payOut(sp, loss.refundKey());
            }
        }
        if (startup) {
            LOG.info("Colony sweep at startup: {} chunk(s) released across {} owner(s).",
                    chunks, losses.size());
        }
    }
}
