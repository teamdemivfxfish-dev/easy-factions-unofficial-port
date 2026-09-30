package top.leonx.territory;

import net.neoforged.neoforge.common.ModConfigSpec;
import top.leonx.territory.world.Interaction;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
    /** Flat cost of ONE purchase in SDM units, used only when the rising price is switched off. */
    public static final ModConfigSpec.LongValue COST_SDM;
    /** Exchange rate for the emerald fallback: this many SDM units = 1 emerald. */
    public static final ModConfigSpec.IntValue SDM_PER_EMERALD;
    /** How many claim slots ONE purchase grants the faction. */
    public static final ModConfigSpec.IntValue CLAIMS_PER_PURCHASE;
    /** SDM currency key to charge. Blank = the buyer's first unlocked currency. */
    public static final ModConfigSpec.ConfigValue<String> SDM_CURRENCY_KEY;

    /** Whether the price of a purchase climbs with how many claims the faction has already bought. */
    public static final ModConfigSpec.BooleanValue CHUNK_PRICE_ENABLED;
    /** Price of the first claim a faction buys, in SDM units. */
    public static final ModConfigSpec.LongValue CHUNK_PRICE_BASE;
    /** Percent of the base price added for every claim already bought. */
    public static final ModConfigSpec.DoubleValue CHUNK_PRICE_SCALE_PERCENT;
    /** Ceiling on the price of one bought claim, or 0 for no ceiling. */
    public static final ModConfigSpec.LongValue CHUNK_PRICE_MAX;

    /** Minimum faction members required before a faction may claim land at all. */
    public static final ModConfigSpec.IntValue MIN_FACTION_MEMBERS;

    /** Chunks of empty ground that must separate two different owners' claims. */
    public static final ModConfigSpec.IntValue CLAIM_BUFFER_CHUNKS;

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

    /** Master switch for enforcing claims from this mod at all. */
    public static final ModConfigSpec.BooleanValue PROTECTION_ENABLED;
    /** Enforce faction claims here instead of relying on Easy Factions to do it. */
    public static final ModConfigSpec.BooleanValue ENFORCE_FACTION;
    /** Enforce personal claims here (Easy Factions cannot: its check ignores the acting player). */
    public static final ModConfigSpec.BooleanValue ENFORCE_PERSONAL;
    /** Decide WHICH interactions are protected from our own list rather than Easy Factions' config. */
    public static final ModConfigSpec.BooleanValue OWN_RESTRICTIONS;
    /** Our restriction list, used when {@link #OWN_RESTRICTIONS} is on. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> RESTRICTED_INTERACTIONS;
    /** Un-cancel interactions Easy Factions refused that our own list does not protect against. */
    public static final ModConfigSpec.BooleanValue OVERRIDE_EASY_FACTIONS;
    /** Keep chests and other item stores owner-only even when right-clicking generally is allowed. */
    public static final ModConfigSpec.BooleanValue PROTECT_CONTAINERS;
    /** Permission level that bypasses claim protection entirely. */
    public static final ModConfigSpec.IntValue BYPASS_PERMISSION_LEVEL;
    /** Refuse to claim a chunk that belongs to somebody else's MineColonies colony. */
    public static final ModConfigSpec.BooleanValue RESPECT_COLONY_CLAIMS;
    /** Leave a colony's own people free inside it, even under a rival faction's claim. */
    public static final ModConfigSpec.BooleanValue COLONY_MEMBERS_KEEP_THEIR_LAND;
    /** Seconds between sweeps that hand back claims a colony has grown underneath. */
    public static final ModConfigSpec.IntValue COLONY_SWEEP_SECONDS;

    /** Tell a player whose land they just walked into. */
    public static final ModConfigSpec.BooleanValue ANNOUNCE_ENTRY;
    /** Put that on the action bar rather than in chat. */
    public static final ModConfigSpec.BooleanValue ENTRY_ACTION_BAR;

    /** Stop faction members hurting each other, including through what they summoned. */
    public static final ModConfigSpec.BooleanValue PROTECT_FACTION_MEMBERS;
    /** Extend that to the pets and summons of faction members, as victims. */
    public static final ModConfigSpec.BooleanValue PROTECT_FACTION_PETS;
    /** Stop a summon from picking a faction member as its target in the first place. */
    public static final ModConfigSpec.BooleanValue STOP_FRIENDLY_TARGETING;
    /** Treat factions you have set FRIENDLY as your own for all of the above. */
    public static final ModConfigSpec.BooleanValue PROTECT_ALLIES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Buying claims: the ONE place land costs money.",
                        "",
                        "A faction buys claims from the Faction tab of the Territory Table and then places them",
                        "on the map for nothing. Claiming used to charge as well, which billed a player twice",
                        "for the same chunk: once for the right to hold it and once for holding it.",
                        "",
                        "What a purchase buys is permanent capacity, so land released, lost to a colony or",
                        "given up comes back as a free slot to spend somewhere else rather than as money.")
                .push("buyclaims");

        BUY_ENABLED = b
                .comment("Show the Buy Claims button in the Faction tab.")
                .define("enabled", true);

        COST_SDM = b
                .comment("FLAT cost of one purchase in SDM Economy units, used only when the rising price in",
                        "the chunkprice block below is turned off. Default 1000 for a set of 10 = 100 each.")
                .defineInRange("costSdm", 1_000L, 0L, 1_000_000_000_000L);

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

        b.comment("What a claim costs to BUY, and how that price grows.",
                        "",
                        "This block prices the Buy Claims button above. The price climbs with how many claims",
                        "the faction has already bought, so a large faction pays more for its next stretch of",
                        "border than a new one does, and the land itself is then placed for free.",
                        "",
                        "SDM Economy is the currency where it is installed; emeralds stand in where it is not,",
                        "at the sdmPerEmerald rate above.")
                .push("chunkprice");

        CHUNK_PRICE_ENABLED = b
                .comment("Let the price of a claim climb with how many the faction has already bought.",
                        "Off falls back to the flat costSdm above for a whole set.")
                .define("enabled", true);

        CHUNK_PRICE_BASE = b
                .comment("Price of the FIRST claim a faction buys, in SDM units.",
                        "Default 100, so a first set of 10 costs 1005. Land is bought by the set and a set",
                        "is what players price it by, so read this as a tenth of what a set should cost.")
                .defineInRange("basePrice", 100L, 0L, 1_000_000_000_000L);

        CHUNK_PRICE_SCALE_PERCENT = b
                .comment("Percent of the base price added for every claim the faction ALREADY bought.",
                        "",
                        "  price = basePrice + basePrice * (scalePercentPerChunk / 100) * claims bought",
                        "",
                        "The climb is FLAT, not compounding: the same number of units is added per claim",
                        "already bought, however many that is.",
                        "",
                        "At the defaults (100, 0.1) one claim already bought adds a tenth of a unit, so every",
                        "claim a faction holds adds 1 to the price of its next SET of ten: a first set costs",
                        "1005, a set at a thousand bought costs 2005 and a set at three thousand costs 4005.",
                        "Sets are how players price land, so read this as 1 per claim per set. Raise it to",
                        "make a wide border expensive quickly; set it to 0 for a flat price per claim.",
                        "Claims won by killing a rival count as bought, since they raise the same ceiling.")
                .defineInRange("scalePercentPerChunk", 0.1D, 0.0D, 1_000.0D);

        CHUNK_PRICE_MAX = b
                .comment("Ceiling on the price of a single bought claim. 0 means no ceiling.")
                .defineInRange("maxPrice", 0L, 0L, 1_000_000_000_000L);

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

        CLAIM_BUFFER_CHUNKS = b
                .comment("Chunks of open ground that must sit between your claim and somebody else's.",
                        "",
                        "Default 2. Without it a faction can claim the ring of chunks around a rival's border",
                        "and sit on their doorstep, which is the grief this is aimed at: the land is not being",
                        "used, it is being used up. With 2 there is always a two chunk gap nobody owns.",
                        "",
                        "Only ever checked when land is ADDED, so borders that are already touching stay put",
                        "and can still be released. Your own faction's land, your own personal claims, and the",
                        "personal claims of your own members do not push you away. Admin claims ignore this",
                        "entirely, in both directions. Set to 0 to let borders touch again.")
                .defineInRange("claimBufferChunks", 2, 0, 16);

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

        b.comment("Enforcement of claims. Read this block first if players report that claims do nothing.",
                        "",
                        "Easy Factions decides claim protection from its own easy_factions-server.toml. That is a",
                        "NeoForge SERVER config, and a world may carry an OVERRIDE copy at",
                        "  <world>/serverconfig/easy_factions-server.toml",
                        "which silently wins over the one in config/. A world copied between servers, or set up",
                        "by a host panel or a pack template, can therefore ignore every edit made in config/ with",
                        "nothing whatsoever in the log to say so. Protection then works in a fresh test world and",
                        "does nothing on the live server, which is exactly what it looks like when a mod is broken.",
                        "",
                        "This block exists so protection no longer depends on that file being the right one.")
                .push("protection");

        PROTECTION_ENABLED = b
                .comment("Enforce claim protection from this mod. Turning this off leaves Easy Factions'",
                        "own enforcement as the only thing standing between a player and someone else's land.")
                .define("protectionEnabled", true);

        ENFORCE_FACTION = b
                .comment("Enforce a faction's claims against non-members, for whatever",
                        "'restrictedInteractions' below covers.",
                        "Easy Factions checks this correctly in its own code, so this is deliberate belt and",
                        "braces: it also holds when EF's restriction list has been emptied, when EF's config",
                        "never loaded from the save, and when another mod un-cancels EF's refusal.")
                .define("enforceFactionClaims", true);

        ENFORCE_PERSONAL = b
                .comment("Enforce a player's personal claims against strangers, for whatever",
                        "'restrictedInteractions' below covers.",
                        "Easy Factions CANNOT do this: its check asks whether the chunk belongs to the claim's",
                        "owner, which is true for any claimed chunk, and never looks at the player standing",
                        "there. Every personal claim permits everybody until this is on.")
                .define("enforcePersonalClaims", true);

        OWN_RESTRICTIONS = b
                .comment("Take the list of protected interactions from 'restrictedInteractions' below rather",
                        "than from Easy Factions' factionClaimRestrictions / coreClaimRestrictions.",
                        "On by default, because EF's lists come from a per-save config file that is easy to",
                        "leave stale and impossible to notice: an empty list there silently disables all",
                        "protection with no warning in the log. Set to false to hand the decision back to EF.")
                .define("useOwnRestrictions", true);

        RESTRICTED_INTERACTIONS = b
                .comment("Interactions a claim protects against, used when useOwnRestrictions is true.",
                        "Valid values: BREAK_BLOCK, PLACE_BLOCK, RIGHT_CLICK_BLOCK, LEFT_CLICK_BLOCK,",
                        "RIGHT_CLICK_ITEM, INTERACT_ENTITY, USE_BUCKET, PLAYER_ATTACK, EXPLOSION_DAMAGE,",
                        "MOB_GRIEFING_DAMAGE, PISTON_MOVE, CONTAINER.",
                        "",
                        "THE DEFAULT IS BREAK_BLOCK AND PLACE_BLOCK ONLY: a claim marks out land nobody else",
                        "may reshape, and stops there. Doors, buttons, levers, beds, chests, animals and items",
                        "all keep working for anyone who walks in, so a claim is a border rather than a dome.",
                        "That is deliberately looser than Easy Factions' own default, which protects every kind",
                        "of interaction and leaves visitors unable to so much as open a gate.",
                        "",
                        "Add RIGHT_CLICK_BLOCK to lock doors, buttons and chests to the owner, or just CONTAINER",
                        "to lock only the things that hold items and leave doors and buttons open to everyone.",
                        "Anything unrecognised is ignored and reported once at server start.")
                .defineListAllowEmpty("restrictedInteractions",
                        List.of("BREAK_BLOCK", "PLACE_BLOCK"),
                        () -> "BREAK_BLOCK",
                        TerritoryConfig::validInteraction);

        OVERRIDE_EASY_FACTIONS = b
                .comment("Allow interactions that Easy Factions refuses but 'restrictedInteractions' above does",
                        "not protect against.",
                        "",
                        "This is what makes that list mean anything in the loosening direction. Easy Factions",
                        "runs its own handlers first and cancels from its own config, and a list here can only",
                        "ever ADD refusals on top; taking RIGHT_CLICK_BLOCK out of it would otherwise change",
                        "nothing at all, because EF has already cancelled the click by the time we are asked.",
                        "Only a refusal Easy Factions actually made is undone, and only inside a claimed chunk,",
                        "so another protection mod's refusal is never touched.",
                        "",
                        "Turn this off if you would rather edit easy_factions-server.toml directly - but note",
                        "that file is per-save and easy to edit the wrong copy of. See the block comment above.")
                .define("overrideEasyFactions", true);

        PROTECT_CONTAINERS = b
                .comment("Keep chests, barrels, shulkers, hoppers, furnaces and brewing stands owner-only even",
                        "when right-clicking blocks generally is allowed.",
                        "",
                        "OFF by default, matching the default restriction list: a claim stops the land being",
                        "reshaped, and what you leave lying about inside it is your own risk. Turn it on for a",
                        "server that wants players to be able to walk in and use doors but not empty the chests.",
                        "Ignored when RIGHT_CLICK_BLOCK is in the list above, which already covers containers.")
                .define("protectContainers", false);

        BYPASS_PERMISSION_LEVEL = b
                .comment("Permission level that ignores claim protection completely.",
                        "Default 2, matching Easy Factions. RAISE THIS TO 4 if your server hands out level 2",
                        "widely (LuckPerms groups, FTB Ranks, a blanket op list): at level 2 those players",
                        "walk through every claim on the server and it looks exactly like protection being",
                        "broken. Level 4 restricts the bypass to genuine server owners.")
                .defineInRange("bypassPermissionLevel", 2, 1, 4);

        RESPECT_COLONY_CLAIMS = b
                .comment("A MineColonies colony outranks a faction. Nobody can claim a chunk belonging to a",
                        "colony they are not part of.",
                        "",
                        "Without this the two mods claim the same ground while knowing nothing about each",
                        "other, and a faction can fence off a town somebody spent weeks building. That is not",
                        "a raid the colony can answer: the claim lands from a map screen, at any distance,",
                        "with nobody present.",
                        "",
                        "Your OWN colony stays claimable. This only ever protects somebody else's.",
                        "Ignored when MineColonies is not installed.")
                .define("respectColonyClaims", true);

        COLONY_MEMBERS_KEEP_THEIR_LAND = b
                .comment("Inside a colony, that colony's own people are free regardless of who has claimed",
                        "the chunk.",
                        "",
                        "The point of the rule: a claim drawn over your town must never make you the",
                        "trespasser in it. With this on, a colony member keeps building, opening and using",
                        "everything inside their own colony even while a rival faction's border sits on top",
                        "of it, and the claim is left to do its work on the ground outside.",
                        "",
                        "'Their own people' means the colony owner and anyone the colony ranks above neutral.",
                        "A player the colony has ranked HOSTILE is not covered, and neither is a stranger.",
                        "Applies to personal and faction claims. Admin territories are deliberately exempt,",
                        "since an operator painting spawn or an arena means it.")
                .define("colonyMembersKeepTheirLand", true);

        COLONY_SWEEP_SECONDS = b
                .comment("How often, in seconds, to look for claims a colony has grown underneath.",
                        "",
                        "A colony is not a fixed shape. It spreads as it builds, and ground that was free when",
                        "a faction claimed it can be part of a town a week later. When that happens the claim",
                        "comes off and the colony keeps the chunk, because a colony outranks a claim in both",
                        "directions: not only can you not claim over a town, a town growing under you takes the",
                        "chunk back. The land released is paid for, so nobody is quietly out of pocket.",
                        "",
                        "The sweep also runs once at server start, which is what clears the claims already",
                        "sitting on colonies from before this rule existed. It reads an in-memory chunk index",
                        "and loads nothing, but it does walk every claim on the server, so 60 seconds is a",
                        "sensible floor. Set to 0 to sweep only at startup.")
                .defineInRange("colonySweepSeconds", 120, 0, 86_400);

        b.pop();

        b.comment("What the mod says to players, and when.").push("notices");

        ANNOUNCE_ENTRY = b
                .comment("Tell a player whose territory they have just walked into, and when they leave it.",
                        "Answers 'why can I not build here' before it is asked, and it is the only warning a",
                        "player gets that they have wandered into a rival faction's land.")
                .define("announceTerritoryEntry", true);

        ENTRY_ACTION_BAR = b
                .comment("Put that notice on the action bar above the hotbar instead of in chat.",
                        "On by default: crossing borders is frequent, and chat is where conversations live.")
                .define("entryOnActionBar", true);

        b.pop();

        b.comment("Friendly fire, and the things fighting on your behalf.",
                        "",
                        "Easy Factions already has a friendly fire switch per faction, and it covers one player",
                        "hitting another. It knows nothing about what a player brought with them, so a summoned",
                        "wolf, skeleton or spell construct treats a faction member exactly like an enemy: this",
                        "is the reported 'summons attack faction members and will not fight the actual enemy'.",
                        "What follows applies a faction's own friendly fire setting to everything it owns.")
                .push("friendlyfire");

        PROTECT_FACTION_MEMBERS = b
                .comment("Stop damage between members of one faction when that faction has friendly fire off.",
                        "Covers the whole chain, not just a sword: arrows, thrown potions, pets and summons.")
                .define("protectFactionMembers", true);

        PROTECT_FACTION_PETS = b
                .comment("Protect the pets and summons OF faction members too, so two members' wolves do not",
                        "fight each other while their owners cannot.")
                .define("protectFactionPets", true);

        STOP_FRIENDLY_TARGETING = b
                .comment("Stop a pet or summon choosing a faction member as its target at all.",
                        "",
                        "Cancelling the damage alone leaves the summon locked onto a friend it can never hurt,",
                        "which is the other half of the report: it stands there swinging at an ally instead of",
                        "fighting what it was summoned for. Refusing the target sends it looking for a real one.")
                .define("stopFriendlyTargeting", true);

        PROTECT_ALLIES = b
                .comment("Treat a faction you have set FRIENDLY as your own for all of the above.",
                        "Off by default: an alliance is a diplomatic state, not a shared health bar, and both",
                        "sides must have set FRIENDLY for it to count.")
                .define("protectAllies", false);

        b.pop();
        SPEC = b.build();
    }

    private TerritoryConfig() {}

    private static boolean validInteraction(Object o) {
        if (!(o instanceof String s)) return false;
        try {
            Interaction.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- use-time getters (config is loaded by the time any of these is called) ----

    public static boolean buyEnabled() { return BUY_ENABLED.get(); }
    public static long costSdm() { return COST_SDM.get(); }
    public static int sdmPerEmerald() { return Math.max(1, SDM_PER_EMERALD.get()); }
    public static int claimsPerPurchase() { return CLAIMS_PER_PURCHASE.get(); }
    public static String sdmCurrencyKey() { return SDM_CURRENCY_KEY.get(); }

    public static boolean chunkPriceEnabled() { return CHUNK_PRICE_ENABLED.get(); }
    public static long chunkPriceBase() { return CHUNK_PRICE_BASE.get(); }
    public static double chunkPriceScalePercent() { return CHUNK_PRICE_SCALE_PERCENT.get(); }
    public static long chunkPriceMax() { return CHUNK_PRICE_MAX.get(); }
    public static int claimBufferChunks() { return CLAIM_BUFFER_CHUNKS.get(); }
    public static int colonySweepSeconds() { return COLONY_SWEEP_SECONDS.get(); }
    public static boolean announceTerritoryEntry() { return ANNOUNCE_ENTRY.get(); }
    public static boolean entryOnActionBar() { return ENTRY_ACTION_BAR.get(); }
    public static boolean protectFactionMembers() { return PROTECT_FACTION_MEMBERS.get(); }
    public static boolean protectFactionPets() { return PROTECT_FACTION_PETS.get(); }
    public static boolean stopFriendlyTargeting() { return STOP_FRIENDLY_TARGETING.get(); }
    public static boolean protectAllies() { return PROTECT_ALLIES.get(); }

    /**
     * What the NEXT claim costs a faction that has already bought {@code bought} of them.
     *
     * The price is a function of the ceiling somebody has already paid for, not of the land they are
     * standing on: claims are bought once and placed whenever, so pricing them by what is currently on the
     * map would quote a different number for the same purchase depending on where the buyer had built.
     * Clamped to {@code maxPrice} where one is set.
     *
     * The scale is applied to the whole run already bought and rounded ONCE, here, instead of rounding
     * what a single claim adds. Rounding the per claim step first threw the scale away completely on a
     * cheap base: at a base of 100 and half a percent, one claim added 0.5 units, which floored to 0, so
     * the price never moved however much a faction bought. The quote and the charge still cannot drift
     * apart, because the Faction tab and the Buy Claims button both read their number from this method.
     */
    public static long purchasePrice(int bought) {
        if (!chunkPriceEnabled()) return purchaseRun(bought, 1);   // flat mode: one claim's share of a set
        long price = chunkPriceBase() + Math.round(chunkPriceStep() * Math.max(0, bought));
        long max = chunkPriceMax();
        return max > 0 ? Math.min(price, max) : Math.max(0L, price);
    }

    /** What one more claim bought adds to the price, in currency units, left unrounded on purpose. */
    public static double chunkPriceStep() {
        if (!chunkPriceEnabled()) return 0.0D;
        return chunkPriceBase() * (chunkPriceScalePercent() / 100.0D);
    }

    /** The price of buying {@code count} more claims on top of the {@code bought} already held. */
    public static long purchaseRun(int bought, int count) {
        if (count <= 0) return 0L;
        if (!chunkPriceEnabled()) {
            // flat mode: costSdm buys one whole set, so a part of a set costs its share of that
            int per = Math.max(1, claimsPerPurchase());
            return Math.max(0L, costSdm() * count / per);
        }
        long total = 0L;
        for (int i = 0; i < count; i++) total += purchasePrice(bought + i);
        return total;
    }

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

    public static boolean protectionEnabled() { return PROTECTION_ENABLED.get(); }
    public static boolean enforceFactionClaims() { return ENFORCE_FACTION.get(); }
    public static boolean enforcePersonalClaims() { return ENFORCE_PERSONAL.get(); }
    public static boolean useOwnRestrictions() { return OWN_RESTRICTIONS.get(); }
    public static boolean overrideEasyFactions() { return OVERRIDE_EASY_FACTIONS.get(); }
    public static int bypassPermissionLevel() { return BYPASS_PERMISSION_LEVEL.get(); }
    public static boolean respectColonyClaims() { return RESPECT_COLONY_CLAIMS.get(); }
    public static boolean colonyMembersKeepTheirLand() { return COLONY_MEMBERS_KEEP_THEIR_LAND.get(); }

    /**
     * Whether opening something that holds items is protected.
     *
     * True when containers are called out on their own, and also whenever right-clicking blocks is protected
     * outright, since a chest is a block you right-click and a list saying otherwise would contradict itself.
     */
    public static boolean protectContainers() {
        if (!useOwnRestrictions()) return false;      // Easy Factions' RIGHT_CLICK_BLOCK is the whole answer
        Set<Interaction> list = restrictedInteractions();
        return PROTECT_CONTAINERS.get()
                || list.contains(Interaction.CONTAINER)
                || list.contains(Interaction.RIGHT_CLICK_BLOCK);
    }

    /**
     * The configured restriction list, parsed once per config load.
     *
     * Block breaking asks this question several times a second, so the strings are turned into an EnumSet and
     * kept until the underlying list object is replaced, which is what a config reload does. Entries that are
     * not interaction names are dropped here rather than throwing; {@link #unknownInteractions()} reports them
     * so a typo surfaces in the log instead of silently narrowing what a claim protects.
     */
    public static Set<Interaction> restrictedInteractions() {
        List<? extends String> source = RESTRICTED_INTERACTIONS.get();
        Cache cache = parsed;
        if (cache != null && cache.source == source) return cache.set;
        EnumSet<Interaction> set = EnumSet.noneOf(Interaction.class);
        List<String> bad = new java.util.ArrayList<>();
        for (String raw : source) {
            if (raw == null) continue;
            try {
                set.add(Interaction.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                bad.add(raw);
            }
        }
        parsed = new Cache(source, java.util.Collections.unmodifiableSet(set), List.copyOf(bad));
        return parsed.set;
    }

    /** Entries of {@code restrictedInteractions} that are not interaction names, for the startup report. */
    public static List<String> unknownInteractions() {
        restrictedInteractions();
        Cache cache = parsed;
        return cache == null ? List.of() : cache.unknown;
    }

    private record Cache(List<? extends String> source, Set<Interaction> set, List<String> unknown) {}

    private static volatile Cache parsed;

    /**
     * Server configs this world overrides, given its save folder.
     *
     * NeoForge reads {@code config/<mod>-server.toml}, but a file of the same name under the save's
     * {@code serverconfig/} folder replaces it outright, and that substitution is never logged. It is a real
     * trap for a world that moved between servers or came out of a pack template: the file an admin edits is
     * simply not the file being read, and every symptom points at the mod instead.
     */
    public static List<String> perWorldConfigOverrides(java.nio.file.Path worldRoot) {
        java.nio.file.Path dir = worldRoot.resolve("serverconfig");
        if (!java.nio.file.Files.isDirectory(dir)) return List.of();
        List<String> found = new java.util.ArrayList<>();
        for (String name : new String[]{"easy_factions-server.toml", "territory-server.toml"}) {
            if (java.nio.file.Files.isRegularFile(dir.resolve(name))) found.add(name);
        }
        return List.copyOf(found);
    }

}
