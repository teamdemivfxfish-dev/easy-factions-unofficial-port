package top.leonx.territory;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config for the "Buy Claims" button in the Faction tab. Lives in {@code territory-server.toml}.
 *
 * The button is the /factionbuy idea as an in-GUI button: a faction owner pays in-game money to permanently
 * raise their faction's claim cap. SDM Economy is the default currency; if SDM is absent (or the buyer can't
 * pay with it) the cost falls back to emeralds at {@link #SDM_PER_EMERALD} SDM = 1 emerald.
 *
 * IMPORTANT: never read these values during mod construction / command registration (config is not bound
 * yet). Read them at use-time only (button press, faction-info send) via the helper getters below.
 */
public final class TerritoryConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue BUY_ENABLED;
    /** Cost of ONE purchase in SDM units (the default currency). */
    public static final ModConfigSpec.LongValue COST_SDM;
    /** Exchange rate for the emerald fallback: this many SDM units = 1 emerald. */
    public static final ModConfigSpec.IntValue SDM_PER_EMERALD;
    /** How many claim slots ONE purchase grants the faction. */
    public static final ModConfigSpec.IntValue CLAIMS_PER_PURCHASE;
    /** SDM currency key to charge. Blank = the buyer's first unlocked currency. */
    public static final ModConfigSpec.ConfigValue<String> SDM_CURRENCY_KEY;

    /** Minimum faction members required before a faction may claim land at all. */
    public static final ModConfigSpec.IntValue MIN_FACTION_MEMBERS;

    /** Master switch for the kill-takes-land tug of war. */
    public static final ModConfigSpec.BooleanValue CONQUEST_ENABLED;
    /** Claim slots the victim's faction loses per enemy kill. */
    public static final ModConfigSpec.IntValue CLAIMS_LOST_PER_KILL;
    /** Percentage of the victim's loss paid into the killer's faction pool. */
    public static final ModConfigSpec.IntValue KILLER_SHARE_PERCENT;
    /** Seconds between regeneration steps that refund one lost slot to every penalised faction. */
    public static final ModConfigSpec.IntValue PENALTY_REGEN_SECONDS;

    /** Whether being killed also costs a player personal (non-faction) claim capacity. */
    public static final ModConfigSpec.BooleanValue PERSONAL_CONQUEST_ENABLED;
    /** Personal claim slots the victim loses per kill. */
    public static final ModConfigSpec.IntValue PERSONAL_CLAIMS_LOST_PER_KILL;
    /** Seconds it takes to win back ONE lost personal claim slot. */
    public static final ModConfigSpec.IntValue PERSONAL_REGEN_SECONDS;
    /** Whether founding a faction turns the founder's personal claims into faction claims. */
    public static final ModConfigSpec.BooleanValue LEADER_CLAIMS_BECOME_FACTION;

    /** Default border colour for a newly painted admin territory. */
    public static final ModConfigSpec.IntValue ADMIN_DEFAULT_COLOR;
    /** Require creative mode, on top of operator, before admin claiming is offered. */
    public static final ModConfigSpec.BooleanValue ADMIN_REQUIRES_CREATIVE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Buy Claims button: a faction owner pays to permanently raise the faction claim cap.").push("buyclaims");

        BUY_ENABLED = b
                .comment("Show the Buy Claims button in the Faction tab.")
                .define("enabled", true);

        COST_SDM = b
                .comment("Cost of one purchase in SDM Economy units (the default currency).",
                        "Default 5000 for a set of 10 claims = 500 per chunk.")
                .defineInRange("costSdm", 5_000L, 0L, 1_000_000_000_000L);

        SDM_PER_EMERALD = b
                .comment("Emerald fallback exchange rate: this many SDM = 1 emerald.",
                        "Only used on servers WITHOUT SDM Economy installed; SDM servers never see emeralds.")
                .defineInRange("sdmPerEmerald", 100, 1, 1_000_000);

        CLAIMS_PER_PURCHASE = b
                .comment("How many extra faction claim slots ONE purchase grants.",
                        "Kept at 10 so claims are bought in fixed sets, not one at a time.")
                .defineInRange("claimsPerPurchase", 10, 1, 100_000);

        SDM_CURRENCY_KEY = b
                .comment("SDM Economy currency key to charge. Leave blank to use the buyer's first unlocked currency.")
                .define("sdmCurrencyKey", "");

        b.pop();

        b.comment("Rules for who may claim land, and how land changes hands through combat.").push("territory");

        MIN_FACTION_MEMBERS = b
                .comment("Members a faction needs before it may claim ANY land.",
                        "Stops one player founding a throwaway faction purely to fence off chunks.",
                        "This is a hard gate: the member-scaled claim cap alone cannot express it, because a",
                        "linear cap can never evaluate to zero. Unclaiming is always allowed regardless, so a",
                        "faction that drops below this can still release land it already holds.",
                        "Set to 1 to disable the gate.")
                .defineInRange("minFactionMembers", 3, 1, 100);

        CONQUEST_ENABLED = b
                .comment("Killing a rival faction member costs them claim capacity and pays your faction a share.",
                        "",
                        "IMPORTANT: Easy Factions ships its OWN version of this and it will double up. Set",
                        "  pointsPerKill = 0",
                        "in easy_factions-server.toml to switch theirs off. EF's version removes the victim chunk",
                        "nearest the KILLER'S LEADER'S personal chunks, falling back to chunk (0,0) when that",
                        "leader has none, so it eats land near world spawn instead of near the fight.")
                .define("conquestEnabled", true);

        CLAIMS_LOST_PER_KILL = b
                .comment("Claim slots the victim's faction loses per kill.",
                        "While the faction is holding fewer chunks than its cap this only shrinks the ceiling.",
                        "Once the ceiling drops below what they actually hold, real chunks start coming off the",
                        "map, nearest the place the victim died.")
                .defineInRange("claimsLostPerKill", 10, 0, 10_000);

        KILLER_SHARE_PERCENT = b
                .comment("Percentage of the victim's loss added to the KILLER'S FACTION pool (not the player).",
                        "At the default 50%, a 10-slot loss pays the killing faction 5 slots, so land is",
                        "contested rather than simply destroyed.")
                .defineInRange("killerSharePercent", 50, 0, 100);

        PENALTY_REGEN_SECONDS = b
                .comment("Seconds between steps that refund ONE lost slot to every penalised faction.",
                        "Default 600 = one slot back every 10 minutes, so the default 10-slot hit is fully",
                        "worked off in 100 minutes. Set to 0 to make losses permanent.")
                .defineInRange("penaltyRegenSeconds", 600, 0, 1_000_000);

        PERSONAL_CONQUEST_ENABLED = b
                .comment("Being killed by another player also costs PERSONAL claim capacity.",
                        "This is the solo player's version of the faction tug of war: you do not need a",
                        "faction to have land worth defending, or to lose it.",
                        "While you are still under your personal cap you only lose a slot you were not using",
                        "yet; once the cap drops below what you hold, the chunk nearest where you died is",
                        "released.")
                .define("personalConquestEnabled", true);

        PERSONAL_CLAIMS_LOST_PER_KILL = b
                .comment("Personal claim slots the victim loses per kill.")
                .defineInRange("personalClaimsLostPerKill", 1, 0, 1_000);

        PERSONAL_REGEN_SECONDS = b
                .comment("Seconds to win back ONE lost personal claim slot.",
                        "Default 600 = one chunk back every 10 minutes, so a player ground down from the",
                        "usual nine is whole again in an hour and a half. Set to 0 to make the losses",
                        "permanent.")
                .defineInRange("personalRegenSeconds", 600, 0, 1_000_000);

        LEADER_CLAIMS_BECOME_FACTION = b
                .comment("Founding a faction turns the founder's personal claims into faction claims.",
                        "The leader then claims for the faction instead of for himself: his land IS the",
                        "faction's land, which is what his members are fighting to extend and defend.",
                        "Only the leader is affected - ordinary members keep their own personal claims.",
                        "If the faction is disbanded the ex-leader gets personal claims back, but never more",
                        "than the personal cap allows; the rest of the land is released.")
                .define("leaderClaimsBecomeFaction", true);

        ADMIN_DEFAULT_COLOR = b
                .comment("Default border colour for a newly painted admin territory, as a packed RGB integer.",
                        "Default 0xC056C0 (purple). Admins may override this per territory in the GUI.")
                .defineInRange("adminDefaultColor", 0xC056C0, 0, 0xFFFFFF);

        ADMIN_REQUIRES_CREATIVE = b
                .comment("Require creative mode, on top of being an operator, to claim admin territory.",
                        "Off by default: when this was always on, an op in survival just never saw the Admin",
                        "entry in the type cycle and nothing explained why.")
                .define("adminRequiresCreative", false);

        b.pop();
        SPEC = b.build();
    }

    private TerritoryConfig() {}

    // ---- use-time getters (config is loaded by the time any of these is called) ----

    public static boolean buyEnabled() { return BUY_ENABLED.get(); }
    public static long costSdm() { return COST_SDM.get(); }
    public static int sdmPerEmerald() { return Math.max(1, SDM_PER_EMERALD.get()); }
    public static int claimsPerPurchase() { return CLAIMS_PER_PURCHASE.get(); }
    public static String sdmCurrencyKey() { return SDM_CURRENCY_KEY.get(); }

    public static int minFactionMembers() { return MIN_FACTION_MEMBERS.get(); }
    public static boolean conquestEnabled() { return CONQUEST_ENABLED.get(); }
    public static int claimsLostPerKill() { return CLAIMS_LOST_PER_KILL.get(); }
    public static int killerSharePercent() { return KILLER_SHARE_PERCENT.get(); }
    public static int adminDefaultColor() { return ADMIN_DEFAULT_COLOR.get() & 0xFFFFFF; }
    public static boolean adminRequiresCreative() { return ADMIN_REQUIRES_CREATIVE.get(); }

    public static boolean personalConquestEnabled() { return PERSONAL_CONQUEST_ENABLED.get(); }
    public static int personalClaimsLostPerKill() { return PERSONAL_CLAIMS_LOST_PER_KILL.get(); }
    public static boolean leaderClaimsBecomeFaction() { return LEADER_CLAIMS_BECOME_FACTION.get(); }

    /** Personal regeneration interval in server ticks, or 0 when losses are configured to be permanent. */
    public static int personalRegenTicks() {
        long ticks = PERSONAL_REGEN_SECONDS.get() * 20L;
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    /** Regeneration interval in server ticks, or 0 when losses are configured to be permanent. */
    public static int penaltyRegenTicks() {
        long ticks = PENALTY_REGEN_SECONDS.get() * 20L;
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    /** Slots paid to the killing faction for a loss of {@code lost}, rounded down, never negative. */
    public static int killerShareOf(int lost) {
        if (lost <= 0) return 0;
        return (int) ((long) lost * killerSharePercent() / 100L);
    }

    /** Emerald price derived from the SDM cost and the exchange rate (rounded up, never negative). */
    public static int costEmeralds() {
        long em = (costSdm() + sdmPerEmerald() - 1) / sdmPerEmerald();   // ceil
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, em));
    }
}
