package top.leonx.territory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.LongValue;
import top.leonx.territory.world.Interaction;

public final class TerritoryConfig {
   public static final ModConfigSpec SPEC;
   public static final ConfigValue<List<? extends String>> UPKEEP_ITEMS;
   public static final IntValue UPKEEP_COST_PER_CHUNK;
   public static final IntValue UPKEEP_INTERVAL_MINUTES;
   public static final ConfigValue<String> UPKEEP_UNIT;
   public static final ConfigValue<List<? extends String>> UPKEEP_FALLBACK_ITEMS;
   public static final ConfigValue<String> UPKEEP_FALLBACK_UNIT;
   public static final IntValue UPKEEP_GRACE_DAYS;
   public static final IntValue UPKEEP_WARN_HOURS;
   public static final IntValue UPKEEP_WARN_REPEAT_MINUTES;
   public static final BooleanValue SAFEZONE_BLOCK_MONSTER_SPAWNS;
   public static final BooleanValue SAFEZONE_BLOCK_ALL_SPAWNS;
   public static final BooleanValue WARZONE_BLOCK_NATURAL_SPAWNS;
   public static final BooleanValue WAR_ENABLED;
   public static final BooleanValue WAR_ALLOW_BREAK;
   public static final BooleanValue WAR_ALLOW_PLACE;
   public static final BooleanValue WAR_ALLOW_USE;
   public static final BooleanValue WAR_ALLOW_CONTAINERS;
   public static final IntValue MIN_FACTION_MEMBERS;
   public static final IntValue CLAIM_BUFFER_CHUNKS;
   public static final BooleanValue LEADER_CLAIMS_BECOME_FACTION;
   public static final BooleanValue ADMIN_REQUIRES_CREATIVE;
   public static final BooleanValue PROTECTION_ENABLED;
   public static final BooleanValue ENFORCE_FACTION;
   public static final BooleanValue ENFORCE_PERSONAL;
   public static final BooleanValue OWN_RESTRICTIONS;
   public static final ConfigValue<List<? extends String>> RESTRICTED_INTERACTIONS;
   public static final BooleanValue OVERRIDE_HOLDFAST_FACTIONS;
   public static final BooleanValue PROTECT_CONTAINERS;
   public static final IntValue BYPASS_PERMISSION_LEVEL;
   public static final BooleanValue ANNOUNCE_ENTRY;
   public static final BooleanValue ENTRY_ACTION_BAR;
   public static final BooleanValue PROTECT_FACTION_MEMBERS;
   public static final BooleanValue PROTECT_FACTION_PETS;
   public static final BooleanValue STOP_FRIENDLY_TARGETING;
   public static final BooleanValue PROTECT_ALLIES;
   public static final BooleanValue ALLOW_MOUNTS;
   private static volatile TerritoryConfig.Cache parsed;

   private TerritoryConfig() {
   }

   private static boolean validInteraction(Object o) {
      if (o instanceof String s) {
         try {
            Interaction.valueOf(s.trim().toUpperCase(Locale.ROOT));
            return true;
         } catch (IllegalArgumentException var3) {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean validUpkeepEntry(Object o) {
      if (o instanceof String s) {
         int eq = s.lastIndexOf(61);
         if (eq > 0) {
            try {
               return Integer.parseInt(s.substring(eq + 1).trim()) > 0;
            } catch (NumberFormatException var4) {
               return false;
            }
         }
      }

      return false;
   }

   private static Map<String, Integer> parseValues(List<? extends String> entries) {
      Map<String, Integer> out = new LinkedHashMap<>();

      for (String raw : entries) {
         if (validUpkeepEntry(raw)) {
            int eq = raw.lastIndexOf(61);
            out.put(raw.substring(0, eq).trim(), Integer.parseInt(raw.substring(eq + 1).trim()));
         }
      }

      return out;
   }

   private static boolean itemExists(String id) {
      ResourceLocation rl = ResourceLocation.tryParse(id);
      return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
   }

   public static boolean usingFallback() {
      for (String id : parseValues(UPKEEP_ITEMS.get()).keySet()) {
         if (itemExists(id)) {
            return false;
         }
      }

      return true;
   }

   public static Map<String, Integer> upkeepValues() {
      return usingFallback() ? parseValues(UPKEEP_FALLBACK_ITEMS.get()) : parseValues(UPKEEP_ITEMS.get());
   }

   public static int upkeepCostPerChunk() {
      return (Integer)UPKEEP_COST_PER_CHUNK.get();
   }

   public static long upkeepGraceTicks() {
      return (long)((Integer)UPKEEP_GRACE_DAYS.get()).intValue() * 1440L * 1200L;
   }

   public static long upkeepWarnMinutes() {
      return (long)((Integer)UPKEEP_WARN_HOURS.get()).intValue() * 60L;
   }

   public static long upkeepWarnRepeatTicks() {
      return (long)((Integer)UPKEEP_WARN_REPEAT_MINUTES.get()).intValue() * 1200L;
   }

   public static boolean warEnabled() {
      return (Boolean)WAR_ENABLED.get();
   }

   public static boolean warAllows(Interaction interaction) {
      return warRule(interaction, (Boolean)WAR_ALLOW_BREAK.get(), (Boolean)WAR_ALLOW_PLACE.get(), (Boolean)WAR_ALLOW_USE.get(), (Boolean)WAR_ALLOW_CONTAINERS.get());
   }

   public static boolean warRule(Interaction interaction, boolean breakBlocks, boolean place, boolean use, boolean containers) {
      return switch (interaction) {
         case BREAK_BLOCK, LEFT_CLICK_BLOCK -> breakBlocks;
         case PLACE_BLOCK, USE_BUCKET -> place;
         case RIGHT_CLICK_BLOCK, RIGHT_CLICK_ITEM, INTERACT_ENTITY, DOOR, UTILITY -> use;
         case CONTAINER -> containers;
         case PLAYER_ATTACK, PVP -> true;
         default -> false;
      };
   }

   public static String upkeepUnit() {
      return (usingFallback() ? (String)UPKEEP_FALLBACK_UNIT.get() : (String)UPKEEP_UNIT.get()).trim();
   }

   public static long upkeepIntervalTicks() {
      return (long)((Integer)UPKEEP_INTERVAL_MINUTES.get()).intValue() * 1200L;
   }

   public static boolean allowMounts() {
      return (Boolean)ALLOW_MOUNTS.get();
   }

   public static int claimBufferChunks() {
      return (Integer)CLAIM_BUFFER_CHUNKS.get();
   }

   public static boolean announceTerritoryEntry() {
      return (Boolean)ANNOUNCE_ENTRY.get();
   }

   public static boolean entryOnActionBar() {
      return (Boolean)ENTRY_ACTION_BAR.get();
   }

   public static boolean protectFactionMembers() {
      return (Boolean)PROTECT_FACTION_MEMBERS.get();
   }

   public static boolean protectFactionPets() {
      return (Boolean)PROTECT_FACTION_PETS.get();
   }

   public static boolean stopFriendlyTargeting() {
      return (Boolean)STOP_FRIENDLY_TARGETING.get();
   }

   public static boolean protectAllies() {
      return (Boolean)PROTECT_ALLIES.get();
   }

   public static int minFactionMembers() {
      return (Integer)MIN_FACTION_MEMBERS.get();
   }

   public static boolean adminRequiresCreative() {
      return (Boolean)ADMIN_REQUIRES_CREATIVE.get();
   }

   public static boolean leaderClaimsBecomeFaction() {
      return (Boolean)LEADER_CLAIMS_BECOME_FACTION.get();
   }

   public static boolean protectionEnabled() {
      return (Boolean)PROTECTION_ENABLED.get();
   }

   public static boolean enforceFactionClaims() {
      return (Boolean)ENFORCE_FACTION.get();
   }

   public static boolean enforcePersonalClaims() {
      return (Boolean)ENFORCE_PERSONAL.get();
   }

   public static boolean useOwnRestrictions() {
      return (Boolean)OWN_RESTRICTIONS.get();
   }

   public static boolean overrideHoldfastFactions() {
      return (Boolean)OVERRIDE_HOLDFAST_FACTIONS.get();
   }

   public static int bypassPermissionLevel() {
      return (Integer)BYPASS_PERMISSION_LEVEL.get();
   }

   public static boolean protectContainers() {
      if (!useOwnRestrictions()) {
         return false;
      } else {
         Set<Interaction> list = restrictedInteractions();
         return (Boolean)PROTECT_CONTAINERS.get() || list.contains(Interaction.CONTAINER) || list.contains(Interaction.RIGHT_CLICK_BLOCK);
      }
   }

   public static Set<Interaction> restrictedInteractions() {
      List<? extends String> source = (List<? extends String>)RESTRICTED_INTERACTIONS.get();
      TerritoryConfig.Cache cache = parsed;
      if (cache != null && cache.source == source) {
         return cache.set;
      } else {
         EnumSet<Interaction> set = EnumSet.noneOf(Interaction.class);
         List<String> bad = new ArrayList<>();

         for (String raw : source) {
            if (raw != null) {
               try {
                  set.add(Interaction.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
               } catch (IllegalArgumentException var7) {
                  bad.add(raw);
               }
            }
         }

         parsed = new TerritoryConfig.Cache(source, Collections.unmodifiableSet(set), List.copyOf(bad));
         return parsed.set;
      }
   }

   public static List<String> unknownInteractions() {
      restrictedInteractions();
      TerritoryConfig.Cache cache = parsed;
      return cache == null ? List.of() : cache.unknown;
   }

   public static List<String> perWorldConfigOverrides(Path worldRoot) {
      Path dir = worldRoot.resolve("serverconfig");
      if (!Files.isDirectory(dir)) {
         return List.of();
      } else {
         List<String> found = new ArrayList<>();

         for (String name : new String[]{"holdfast_factions-server.toml", "territory-server.toml"}) {
            if (Files.isRegularFile(dir.resolve(name))) {
               found.add(name);
            }
         }

         return List.copyOf(found);
      }
   }

   static {
      Builder b = new Builder();
      b.comment(
            new String[]{
               "Faction land upkeep. Each faction keeps its upkeep items inside its one Territory Table (the",
               "faction core). Once per interval the faction pays from that table, and whatever it cannot",
               "pay for is released, outermost chunks first."
            }
         )
         .push("upkeep");
      UPKEEP_ITEMS = b.comment(
            new String[]{
               "Items that pay upkeep, each written as id=value. The value is what ONE item is worth in the",
               "base unit. The defaults are the Create: Numismatics coins, counted in spurs.",
               "Items are used whole, cheapest first. No change is given back: whatever a payment is worth",
               "beyond what is due stays in the core as credit and counts toward the next payments.",
               "If none of these items exist in the game (Numismatics is not installed), the",
               "fallbackItems list below is used instead."
            }
         )
         .defineListAllowEmpty(
            "items",
            List.of(
               "numismatics:spur=1",
               "numismatics:bevel=8",
               "numismatics:sprocket=16",
               "numismatics:cog=64",
               "numismatics:crown=512",
               "numismatics:sun=4096"
            ),
            () -> "numismatics:spur=1",
            TerritoryConfig::validUpkeepEntry
         );
      UPKEEP_COST_PER_CHUNK = b.comment(
            new String[]{
               "Upkeep per claimed chunk, per interval, in the base unit (spurs, or credits with the fallback list).",
               "Default 4: a 50-chunk faction pays 200 spurs, which is 3 cogs and a bevel, or 200 credits, which is",
               "about 13 iron ingots with the fallback list. Set to 0 to turn upkeep off."
            }
         )
         .defineInRange("costPerChunk", 4, 0, 1000000);
      UPKEEP_INTERVAL_MINUTES = b.comment(
            new String[]{"Real-time minutes between upkeep payments. Default 1440 = once a day.", "The clock only runs while the server is running."}
         )
         .defineInRange("intervalMinutes", 1440, 1, 525600);
      UPKEEP_GRACE_DAYS = b.comment(
            new String[]{
               "Real-time days a faction has to pay after a payment it cannot cover comes due.",
               "During the grace period nothing is released, and depositing items at the core pays the debt.",
               "Default 5. Set to 0 to release land immediately at the payment."
            }
         )
         .defineInRange("graceDays", 5, 0, 365);
      UPKEEP_UNIT = b.comment(new String[]{"Name shown in the Territory Table for the base unit."}).define("unitName", "spurs");
      UPKEEP_FALLBACK_ITEMS = b.comment(
            new String[]{
               "Items that pay upkeep INSTEAD of the list above when none of those items exist in the game,",
               "which is the case when Create: Numismatics is not installed. Same id=value format.",
               "Defaults are ores and gems, counted in credits.",
               "Same rules as the main list: used whole, cheapest first, and no change is given back.",
               "Any value beyond what is due stays in the core as credit toward the next payments."
            }
         )
         .defineListAllowEmpty(
            "fallbackItems",
            List.of(
               "minecraft:raw_copper=4",
               "minecraft:copper_ingot=8",
               "minecraft:raw_iron=8",
               "minecraft:iron_ingot=16",
               "minecraft:raw_gold=24",
               "minecraft:gold_ingot=48",
               "minecraft:emerald=64",
               "minecraft:diamond=256",
               "minecraft:netherite_ingot=1024"
            ),
            () -> "minecraft:iron_ingot=16",
            TerritoryConfig::validUpkeepEntry
         );
      UPKEEP_FALLBACK_UNIT = b.comment(new String[]{"Name shown in the Territory Table for the fallback unit."}).define("fallbackUnitName", "credits");
      UPKEEP_WARN_HOURS = b.comment(
            new String[]{
               "Warn a faction when its core can only cover this many more hours of upkeep, counting up to the",
               "first payment it will fail. Members get the warning when they log in and again on the repeat",
               "timer below. The Faction tab shows the same state in red. Default 24. Set to 0 to turn it off."
            }
         )
         .defineInRange("warnHours", 24, 0, 720);
      UPKEEP_WARN_REPEAT_MINUTES = b.comment(
            new String[]{"Real-time minutes between repeated low-upkeep warnings to the faction while it stays low. Default 180."}
         )
         .defineInRange("warnRepeatMinutes", 180, 5, 10080);
      b.pop();
      b.comment("Rules for who may claim land.").push("territory");
      MIN_FACTION_MEMBERS = b.comment(
            new String[]{
               "Members a faction needs before it may claim ANY land.",
               "Stops one player founding a throwaway faction purely to fence off chunks.",
               "This is a hard gate: the member-scaled claim cap alone cannot express it, because a",
               "linear cap can never evaluate to zero. Unclaiming is always allowed regardless, so a",
               "faction that drops below this can still release land it already holds.",
               "Set to 1 to disable the gate."
            }
         )
         .defineInRange("minFactionMembers", 3, 1, 100);
      CLAIM_BUFFER_CHUNKS = b.comment(
            new String[]{
               "Chunks of open ground that must sit between your claim and somebody else's.",
               "",
               "Default 1. Without it a faction can claim the ring of chunks around a rival's border",
               "and sit on their doorstep, which is the grief this is aimed at: the land is not being",
               "used, it is being used up. With 1 there is always a one chunk gap nobody owns.",
               "",
               "Only ever checked when land is ADDED, so borders that are already touching stay put",
               "and can still be released. Your own faction's land, your own personal claims, and the",
               "personal claims of your own members do not push you away. Safezones and warzones ignore this",
               "entirely, in both directions. Set to 0 to let borders touch again."
            }
         )
         .defineInRange("claimBufferChunks", 1, 0, 16);
      LEADER_CLAIMS_BECOME_FACTION = b.comment(
            new String[]{
               "When true, founding a faction turns the founder's personal claims into faction claims,",
               "and the leader can no longer place personal claims: his land IS the faction's land.",
               "If the faction is disbanded the ex-leader gets personal claims back, but never more",
               "than the personal cap allows; the rest of the land is released.",
               "When false (the default), everyone keeps their own personal claims whether or not they",
               "are in a faction or lead one, and founding or disbanding a faction leaves them alone."
            }
         )
         .define("leaderClaimsBecomeFaction", false);
      ADMIN_REQUIRES_CREATIVE = b.comment(
            new String[]{
               "Require creative mode, on top of being an operator, to claim a safezone or warzone.",
               "Off by default: when this was always on, an op in survival just never saw the Safezone",
               "and Warzone entries in the type cycle and nothing explained why."
            }
         )
         .define("adminRequiresCreative", false);
      b.pop();
      b.comment(
            new String[]{
               "Enforcement of claims. Read this block first if players report that claims do nothing.",
               "",
               "Holdfast Factions decides claim protection from its own holdfast_factions-server.toml. That is a",
               "NeoForge SERVER config, and a world may carry an OVERRIDE copy at",
               "  <world>/serverconfig/holdfast_factions-server.toml",
               "which silently wins over the one in config/. A world copied between servers, or set up",
               "by a host panel or a pack template, can therefore ignore every edit made in config/ with",
               "nothing whatsoever in the log to say so. Protection then works in a fresh test world and",
               "does nothing on the live server, which is exactly what it looks like when a mod is broken.",
               "",
               "This block exists so protection no longer depends on that file being the right one."
            }
         )
         .push("protection");
      PROTECTION_ENABLED = b.comment(
            new String[]{
               "Enforce claim protection from this mod. Turning this off leaves Holdfast Factions'",
               "own enforcement as the only thing standing between a player and someone else's land."
            }
         )
         .define("protectionEnabled", true);
      ENFORCE_FACTION = b.comment(
            new String[]{
               "Enforce a faction's claims against non-members, for whatever",
               "'restrictedInteractions' below covers.",
               "Holdfast Factions checks this correctly in its own code, so this is deliberate belt and",
               "braces: it also holds when the core's restriction list has been emptied, when the core's config",
               "never loaded from the save, and when another mod un-cancels the core's refusal."
            }
         )
         .define("enforceFactionClaims", true);
      ENFORCE_PERSONAL = b.comment(
            new String[]{
               "Enforce a player's personal claims against strangers, for whatever",
               "'restrictedInteractions' below covers.",
               "Holdfast Factions CANNOT do this: its check asks whether the chunk belongs to the claim's",
               "owner, which is true for any claimed chunk, and never looks at the player standing",
               "there. Every personal claim permits everybody until this is on."
            }
         )
         .define("enforcePersonalClaims", true);
      OWN_RESTRICTIONS = b.comment(
            new String[]{
               "Take the list of protected interactions from 'restrictedInteractions' below rather",
               "than from Holdfast Factions' factionClaimRestrictions / coreClaimRestrictions.",
               "On by default, because the core's lists come from a per-save config file that is easy to",
               "leave stale and impossible to notice: an empty list there silently disables all",
               "protection with no warning in the log. Set to false to hand the decision back to the core."
            }
         )
         .define("useOwnRestrictions", true);
      RESTRICTED_INTERACTIONS = b.comment(
            new String[]{
               "Interactions a claim protects against, used when useOwnRestrictions is true.",
               "Valid values: BREAK_BLOCK, PLACE_BLOCK, RIGHT_CLICK_BLOCK, LEFT_CLICK_BLOCK,",
               "RIGHT_CLICK_ITEM, INTERACT_ENTITY, USE_BUCKET, PLAYER_ATTACK, EXPLOSION_DAMAGE,",
               "MOB_GRIEFING_DAMAGE, PISTON_MOVE, CONTAINER.",
               "",
               "THE DEFAULT IS BREAK_BLOCK AND PLACE_BLOCK ONLY: a claim marks out land nobody else",
               "may reshape, and stops there. Doors, buttons, levers, beds, chests, animals and items",
               "all keep working for anyone who walks in, so a claim is a border rather than a dome.",
               "That is deliberately looser than Holdfast Factions' own default, which protects every kind",
               "of interaction and leaves visitors unable to so much as open a gate.",
               "",
               "Add RIGHT_CLICK_BLOCK to lock doors, buttons and chests to the owner, or just CONTAINER",
               "to lock only the things that hold items and leave doors and buttons open to everyone.",
               "Anything unrecognised is ignored and reported once at server start."
            }
         )
         .defineListAllowEmpty("restrictedInteractions", List.of("BREAK_BLOCK", "PLACE_BLOCK"), () -> "BREAK_BLOCK", TerritoryConfig::validInteraction);
      OVERRIDE_HOLDFAST_FACTIONS = b.comment(
            new String[]{
               "Allow interactions that Holdfast Factions refuses but 'restrictedInteractions' above does",
               "not protect against.",
               "",
               "This is what makes that list mean anything in the loosening direction. Holdfast Factions",
               "runs its own handlers first and cancels from its own config, and a list here can only",
               "ever ADD refusals on top; taking RIGHT_CLICK_BLOCK out of it would otherwise change",
               "nothing at all, because the core has already cancelled the click by the time we are asked.",
               "Only a refusal Holdfast Factions actually made is undone, and only inside a claimed chunk,",
               "so another protection mod's refusal is never touched.",
               "",
               "Turn this off if you would rather edit holdfast_factions-server.toml directly - but note",
               "that file is per-save and easy to edit the wrong copy of. See the block comment above."
            }
         )
         .define("overrideHoldfastFactions", true);
      PROTECT_CONTAINERS = b.comment(
            new String[]{
               "Keep chests, barrels, shulkers, hoppers, furnaces and brewing stands owner-only even",
               "when right-clicking blocks generally is allowed.",
               "",
               "OFF by default, matching the default restriction list: a claim stops the land being",
               "reshaped, and what you leave lying about inside it is your own risk. Turn it on for a",
               "server that wants players to be able to walk in and use doors but not empty the chests.",
               "Ignored when RIGHT_CLICK_BLOCK is in the list above, which already covers containers."
            }
         )
         .define("protectContainers", false);
      ALLOW_MOUNTS = b.comment(
            new String[]{
               "Let anyone mount and ride a saddled-type animal inside claims, whoever owns the land.",
               "",
               "Riding is a right-click on an entity, which INTERACT_ENTITY would otherwise lock to the owner.",
               "Only a plain mount attempt is let through: sneaking (opens the animal's inventory), and holding a",
               "saddle, horse armor, food, a lead or a name tag (equipping, feeding, leashing) still follow the claim rules.",
               "Applies to safezones too. On by default."
            }
         )
         .define("allowMounts", true);
      BYPASS_PERMISSION_LEVEL = b.comment(
            new String[]{
               "Permission level that ignores claim protection completely.",
               "Default 2, matching Holdfast Factions. RAISE THIS TO 4 if your server hands out level 2",
               "widely (LuckPerms groups, FTB Ranks, a blanket op list): at level 2 those players",
               "walk through every claim on the server and it looks exactly like protection being",
               "broken. Level 4 restricts the bypass to genuine server owners."
            }
         )
         .defineInRange("bypassPermissionLevel", 2, 1, 4);
      b.pop();
      b.comment("What the mod says to players, and when.").push("notices");
      ANNOUNCE_ENTRY = b.comment(
            new String[]{
               "Tell a player whose territory they have just walked into, and when they leave it.",
               "Answers 'why can I not build here' before it is asked, and it is the only warning a",
               "player gets that they have wandered into a rival faction's land."
            }
         )
         .define("announceTerritoryEntry", true);
      ENTRY_ACTION_BAR = b.comment(
            new String[]{
               "Put that notice on the action bar above the hotbar instead of in chat.",
               "On by default: crossing borders is frequent, and chat is where conversations live."
            }
         )
         .define("entryOnActionBar", true);
      b.pop();
      b.comment(
            new String[]{
               "Friendly fire, and the things fighting on your behalf.",
               "",
               "Holdfast Factions already has a friendly fire switch per faction, and it covers one player",
               "hitting another. It knows nothing about what a player brought with them, so a summoned",
               "wolf, skeleton or spell construct treats a faction member exactly like an enemy: this",
               "is the reported 'summons attack faction members and will not fight the actual enemy'.",
               "What follows applies a faction's own friendly fire setting to everything it owns."
            }
         )
         .push("friendlyfire");
      PROTECT_FACTION_MEMBERS = b.comment(
            new String[]{
               "Stop damage between members of one faction when that faction has friendly fire off.",
               "Covers the whole chain, not just a sword: arrows, thrown potions, pets and summons."
            }
         )
         .define("protectFactionMembers", true);
      PROTECT_FACTION_PETS = b.comment(
            new String[]{"Protect the pets and summons OF faction members too, so two members' wolves do not", "fight each other while their owners cannot."}
         )
         .define("protectFactionPets", true);
      STOP_FRIENDLY_TARGETING = b.comment(
            new String[]{
               "Stop a pet or summon choosing a faction member as its target at all.",
               "",
               "Cancelling the damage alone leaves the summon locked onto a friend it can never hurt,",
               "which is the other half of the report: it stands there swinging at an ally instead of",
               "fighting what it was summoned for. Refusing the target sends it looking for a real one."
            }
         )
         .define("stopFriendlyTargeting", true);
      PROTECT_ALLIES = b.comment(
            new String[]{
               "Treat a faction you have set FRIENDLY as your own for all of the above.",
               "Off by default: an alliance is a diplomatic state, not a shared health bar, and both",
               "sides must have set FRIENDLY for it to count."
            }
         )
         .define("protectAllies", false);
      b.pop();
      b.comment(
            new String[]{
               "Mob spawning inside safezones (not warzones).",
               "",
               "Natural spawns, chunk generation, spawners, patrols and the like are cancelled. Spawn eggs, commands,",
               "dispensers, buckets, breeding and summoned mobs (spells, pets) are never touched.",
               "Inside a Jake's World Guard zone these settings do nothing: World Guard's own flags decide there."
            }
         )
         .push("safezone");
      SAFEZONE_BLOCK_MONSTER_SPAWNS = b.comment(
            new String[]{"Stop hostile mobs from spawning inside safezones. Applies to modded hostile mobs too."}
         )
         .define("blockMonsterSpawns", true);
      SAFEZONE_BLOCK_ALL_SPAWNS = b.comment(
            new String[]{"Stop EVERY mob from spawning inside safezones, animals and ambient mobs included.", "Off by default so a safezone with a farm or a pasture still gets its animals."}
         )
         .define("blockAllMobSpawns", false);
      b.pop();
      b.comment(
            new String[]{
               "Mob spawning inside warzones.",
               "",
               "Natural spawns, chunk generation, patrols and the like are cancelled for every mob, hostile or not.",
               "Spawners still work, so a dungeon in a warzone keeps its monsters. Spawn eggs, commands, dispensers,",
               "breeding and summoned mobs (spells, pets) are never touched."
            }
         )
         .push("warzone");
      WARZONE_BLOCK_NATURAL_SPAWNS = b.comment(new String[]{"Stop natural mob spawns inside warzones."}).define("blockNaturalSpawns", true);
      b.pop();
      b.comment(
            new String[]{
               "Wars declared in WarNTaxes (Minecolonies: War 'N Taxes).",
               "",
               "Holdfast Factions has no war command of its own. When WarNTaxes has a war in its fighting phase,",
               "the factions of the people on each side count as at war with each other, and the rules below let",
               "them raid each other's claims until that war ends: opening chests and using doors and",
               "other blocks by default. Nobody loses land: a war only lifts",
               "protection between the two sides, it never transfers a claim. Safezones and warzones ignore it.",
               "Nothing here does anything when WarNTaxes is not installed."
            }
         )
         .push("war");
      WAR_ENABLED = b.comment(new String[]{"Turn the whole war integration on or off."}).define("enabled", true);
      WAR_ALLOW_BREAK = b.comment(new String[]{"Enemies may break blocks inside each other's claims during a war. Off by default: walls fall to explosions, which faction and personal claims never block."}).define("allowBreak", false);
      WAR_ALLOW_PLACE = b.comment(new String[]{"Enemies may place blocks and use buckets inside each other's claims during a war. Off by default."}).define("allowPlace", false);
      WAR_ALLOW_USE = b.comment(
            new String[]{"Enemies may use doors, buttons, crafting tables and other blocks, items and entities inside each other's claims."}
         )
         .define("allowUse", true);
      WAR_ALLOW_CONTAINERS = b.comment(
            new String[]{"Enemies may open chests and other containers inside each other's claims during a war. On by default."}
         )
         .define("allowContainers", true);
      b.pop();
      SPEC = b.build();
   }

   private static record Cache(List<? extends String> source, Set<Interaction> set, List<String> unknown) {
   }
}
