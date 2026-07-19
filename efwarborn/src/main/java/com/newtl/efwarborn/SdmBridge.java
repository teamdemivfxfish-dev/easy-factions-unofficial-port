package com.newtl.efwarborn;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.sixik.sdmeconomy.api.EconomyAPI;
import net.sixik.sdmeconomy.economyData.CurrencyPlayerData;
import net.sixik.sdmeconomy.economyData.CurrencyPlayerData.PlayerCurrency;
import net.sixik.sdmeconomy.utils.ErrorCodeStruct;

import java.util.LinkedList;

/**
 * Soft-dependency bridge to SDM Economy ("sdmeconomy"). {@link #isLoaded()} touches no SDM type, so the
 * class loads fine when SDM is absent; the SDM-typed methods below are only ever called after isLoaded()
 * returns true, so their classes resolve lazily and never crash a server without SDM installed.
 */
public final class SdmBridge {

    private SdmBridge() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded("sdmeconomy");
    }

    /** Currency key to charge: the configured one, or the buyer's first unlocked currency if blank. */
    public static String resolveKey(ServerPlayer player, String configured) {
        if (configured != null && !configured.isBlank()) return configured.trim();
        try {
            LinkedList<PlayerCurrency> unlocked = CurrencyPlayerData.SERVER.getPlayerUnlockedCurrency(player);
            if (unlocked != null && !unlocked.isEmpty() && unlocked.get(0).currency != null) {
                return unlocked.get(0).currency.getName();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static double balance(ServerPlayer player, String key) {
        try {
            ErrorCodeStruct<Double> bal = CurrencyPlayerData.SERVER.getBalance(player, key);
            return (bal != null && bal.value != null) ? bal.value : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** Take {@code amount} from the player. Returns false if it could not be applied. */
    public static boolean withdraw(ServerPlayer player, String key, double amount) {
        try {
            CurrencyPlayerData.SERVER.addCurrencyValue(player, key, -amount);
            EconomyAPI.syncCurrencyData(player);
            return true;
        } catch (Throwable t) {
            EFWarborn.LOGGER.warn("Easy Factions Warborn: SDM withdraw failed.", t);
            return false;
        }
    }

    /** Give {@code amount} back (used to refund if the claim fails after charging). */
    public static void refund(ServerPlayer player, String key, double amount) {
        try {
            CurrencyPlayerData.SERVER.addCurrencyValue(player, key, amount);
            EconomyAPI.syncCurrencyData(player);
        } catch (Throwable t) {
            EFWarborn.LOGGER.error("Easy Factions Warborn: refund of {} {} failed after a bad claim.", amount, key, t);
        }
    }
}
