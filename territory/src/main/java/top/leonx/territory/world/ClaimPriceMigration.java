package top.leonx.territory.world;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.leonx.territory.TerritoryMod;

import java.util.Map;
import java.util.UUID;

/**
 * Handing back what the old pay-on-place rule charged.
 *
 * <h2>Why anything is owed at all</h2>
 * Claims were charged for twice: once at the Buy Claims button for the ceiling, and again at the map for
 * each chunk put down under it. The second charge is gone, and the money it took is still in players'
 * ledgers rather than their balances. Nobody did anything wrong to be charged it, so it goes back.
 *
 * <h2>Why it is parked rather than paid</h2>
 * A server starts with nobody on it. The amount is put in {@link ClaimRefunds} under the party it belongs
 * to, a faction under its name and a player under their UUID, and reaches them the next time they log in
 * or run {@code /territory refunds}. Nothing is lost if a whole faction is offline for a week.
 *
 * <h2>Why it is safe to run every boot</h2>
 * {@link ClaimLedger#drainAll()} empties the ledger as it reads it and nothing writes to it any more, so
 * the second boot finds nothing and pays nothing. There is no flag to get out of step with the data.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClaimPriceMigration {

    private ClaimPriceMigration() {}

    private static final Logger LOG = LoggerFactory.getLogger("territory-refunds");

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;

        ClaimLedger ledger = ClaimLedger.get(server);
        if (ledger.isEmpty()) return;

        Map<String, Long> owed = ledger.drainAll();
        if (owed.isEmpty()) return;

        ClaimRefunds pool = ClaimRefunds.get(server);
        long total = 0L;
        for (Map.Entry<String, Long> e : owed.entrySet()) {
            long amount = e.getValue() == null ? 0L : e.getValue();
            if (amount <= 0L) continue;
            pool.add(keyFor(e.getKey()), amount);
            total += amount;
        }
        LOG.info("Claims are paid for when they are bought now, not when they are placed. Refunding {} "
                        + "to {} owner(s) for land they were charged for on the map; it is waiting in the "
                        + "refund pool and is paid on login or with /territory refunds.",
                total, owed.size());
    }

    /**
     * Which pool an old ledger owner belongs in.
     *
     * The ledger recorded a faction NAME for faction land and a player UUID for a personal claim, in the
     * same field, so the two are told apart by whether the string parses as a UUID. A faction cannot be
     * named as one, which is why this is decidable at all.
     */
    private static String keyFor(String owner) {
        try {
            return ClaimRefunds.playerKey(UUID.fromString(owner));
        } catch (IllegalArgumentException notAUuid) {
            return ClaimRefunds.factionKey(owner);
        }
    }
}
