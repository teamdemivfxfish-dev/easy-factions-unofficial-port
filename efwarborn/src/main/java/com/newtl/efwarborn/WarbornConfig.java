package com.newtl.efwarborn;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config for the paid-claim add-on. Lives in {@code efwarborn-server.toml}. */
public final class WarbornConfig {

    public static final ModConfigSpec SPEC;

    /** Cost in the server currency for one chunk bought past the faction claim cap. */
    public static final ModConfigSpec.IntValue CHUNK_PRICE;
    /** SDM Economy currency key to charge. Blank = the player's first unlocked currency. */
    public static final ModConfigSpec.ConfigValue<String> CURRENCY_KEY;
    /** If true, /factionbuy only works once the faction has reached its claim cap (the intended flow). */
    public static final ModConfigSpec.BooleanValue REQUIRE_AT_CAP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Paid faction chunks past the claim cap (uses SDM Economy).").push("factionbuy");

        CHUNK_PRICE = b
                .comment("Cost per chunk bought with /factionbuy.")
                .defineInRange("chunkPrice", 500, 0, 1_000_000_000);

        CURRENCY_KEY = b
                .comment("SDM Economy currency key to charge. Leave blank to use the buyer's first unlocked currency.")
                .define("currencyKey", "");

        REQUIRE_AT_CAP = b
                .comment("If true, /factionbuy is only allowed once the faction is at its claim cap",
                        "(factionBaseClaimLimit + factionAdditionalClaimLimitPerMember * members).",
                        "If false, a faction can buy chunks at any time.")
                .define("requireAtCap", true);

        b.pop();
        SPEC = b.build();
    }

    private WarbornConfig() {}
}
