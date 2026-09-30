package top.leonx.territory.integration;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.world.ClaimRefunds;

/**
 * Paying people back for land that stopped being theirs.
 *
 * The money is parked in {@link ClaimRefunds} the moment the land goes, because the people it belongs to are
 * usually not online when a colony grows or a server starts. This is the other end: handing it over when
 * somebody who can receive it turns up. Nothing is lost in between and nothing is paid twice, because the
 * pool is the single record of what is still owed.
 */
public final class Refunds {

    private Refunds() {}

    /** Add to what somebody is owed. */
    public static void owe(MinecraftServer server, String key, long amount) {
        if (server == null || key == null || amount <= 0L) return;
        ClaimRefunds.get(server).add(key, amount);
    }

    /** What a pool still holds. */
    public static long owed(MinecraftServer server, String key) {
        if (server == null || key == null) return 0L;
        return ClaimRefunds.get(server).amount(key);
    }

    /**
     * Hand a pool over to {@code player}, and answer with how much actually reached them.
     *
     * On a server paying in emeralds this deliberately pays only whole ones and leaves the remainder owed,
     * so a pool worth two and a half emeralds pays two and keeps the half. Anything that fails to land goes
     * straight back into the pool: an economy call that returns false must not quietly delete somebody's
     * money.
     */
    public static long payOut(ServerPlayer player, String key) {
        if (player == null || key == null) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;

        ClaimRefunds pool = ClaimRefunds.get(server);
        long owed = pool.amount(key);
        if (owed <= 0L) return 0L;

        long payable = owed;
        if (!ClaimEconomy.usingSdm()) {
            int rate = TerritoryConfig.sdmPerEmerald();
            payable = (owed / rate) * rate;
        }
        if (payable <= 0L) return 0L;

        long reserved = pool.takeUpTo(key, payable);
        long paid = ClaimEconomy.pay(player, reserved);
        if (paid < reserved) pool.add(key, reserved - paid);
        return paid;
    }
}
