package top.leonx.territory.integration;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import top.leonx.territory.TerritoryConfig;

/**
 * One till for everything land costs and everything it pays back.
 *
 * <h2>One currency at a time, never two</h2>
 * SDM Economy is the money where it is installed, and emeralds are the money where it is not. They are
 * never both live: falling back to emeralds because a player cannot afford the SDM price is what handed
 * players a second and far cheaper currency on exactly the servers that had configured a real economy, and
 * that is the bug this rule exists to keep fixed. Prices are quoted in SDM units throughout, including on a
 * server with no SDM, where they are divided by {@code sdmPerEmerald} at the moment of payment.
 *
 * <h2>Paying out in a currency made of items</h2>
 * A refund is an exact number and an emerald is not divisible, so paying one out can leave a remainder.
 * What is paid is always rounded DOWN and the remainder is reported back to the caller, which keeps it in
 * the pool it came from rather than rounding it away from the player it belongs to.
 */
public final class ClaimEconomy {

    private ClaimEconomy() {}

    /** Whether a real economy mod is running, as opposed to the emerald stand-in. */
    public static boolean usingSdm() {
        return SdmBridge.isLoaded();
    }

    /** The currency key to charge this player, or null when SDM is installed but has nothing set up. */
    private static String keyFor(ServerPlayer player) {
        return SdmBridge.resolveKey(player, TerritoryConfig.sdmCurrencyKey());
    }

    /** A price as a player should read it: their currency's name, or a count of emeralds. */
    public static String describe(ServerPlayer player, long sdmAmount) {
        if (sdmAmount <= 0L) return "nothing";
        if (usingSdm()) {
            String key = player == null ? null : keyFor(player);
            return sdmAmount + " " + (key == null || key.isBlank() ? "coins" : key);
        }
        long emeralds = emeraldsFor(sdmAmount);
        return emeralds + (emeralds == 1 ? " emerald" : " emeralds");
    }

    /** Emeralds needed to cover an SDM price, rounded UP so a charge is never short. */
    public static long emeraldsFor(long sdmAmount) {
        int rate = TerritoryConfig.sdmPerEmerald();
        return (sdmAmount + rate - 1) / rate;
    }

    /** What a player can pay right now, in SDM units, counting emeralds on a server without SDM. */
    public static long balance(ServerPlayer player) {
        if (player == null) return 0L;
        if (usingSdm()) {
            String key = keyFor(player);
            if (key == null) return 0L;
            return (long) SdmBridge.balance(player, key);
        }
        return (long) countEmeralds(player) * TerritoryConfig.sdmPerEmerald();
    }

    /**
     * Take a price off a player, or leave them untouched and answer false.
     *
     * Checked and taken in the same call on purpose: a caller that asks whether the player can afford it and
     * then charges separately has a window in between, and the two questions have to be about the same
     * moment for a price to mean anything.
     */
    public static boolean charge(ServerPlayer player, long sdmAmount) {
        if (player == null) return false;
        if (sdmAmount <= 0L) return true;
        if (usingSdm()) {
            String key = keyFor(player);
            if (key == null) return false;
            if (SdmBridge.balance(player, key) < sdmAmount) return false;
            return SdmBridge.withdraw(player, key, sdmAmount);
        }
        long needed = emeraldsFor(sdmAmount);
        if (countEmeralds(player) < needed) return false;
        removeEmeralds(player, (int) Math.min(Integer.MAX_VALUE, needed));
        return true;
    }

    /**
     * Pay a player, returning how much of {@code sdmAmount} actually reached them.
     *
     * Under SDM that is all of it. In emeralds it is the whole-emerald part, and the caller keeps the rest
     * owed. A player whose inventory is full still gets paid: the emeralds land at their feet rather than
     * disappearing on a technicality.
     */
    public static long pay(ServerPlayer player, long sdmAmount) {
        if (player == null || sdmAmount <= 0L) return 0L;
        if (usingSdm()) {
            String key = keyFor(player);
            if (key == null) return 0L;
            return SdmBridge.deposit(player, key, sdmAmount) ? sdmAmount : 0L;
        }
        int rate = TerritoryConfig.sdmPerEmerald();
        long emeralds = sdmAmount / rate;
        if (emeralds <= 0L) return 0L;
        long remaining = emeralds;
        while (remaining > 0L) {
            int stack = (int) Math.min(64L, remaining);
            ItemStack items = new ItemStack(Items.EMERALD, stack);
            if (!player.getInventory().add(items)) player.drop(items, false);
            remaining -= stack;
        }
        return emeralds * rate;
    }

    private static int countEmeralds(ServerPlayer player) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(Items.EMERALD)) n += st.getCount();
        }
        return n;
    }

    private static void removeEmeralds(ServerPlayer player, int count) {
        int remaining = count;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.is(Items.EMERALD)) continue;
            int take = Math.min(remaining, st.getCount());
            st.shrink(take);
            remaining -= take;
        }
        inv.setChanged();
    }
}
