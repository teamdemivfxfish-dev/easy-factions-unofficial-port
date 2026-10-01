package dev.xmat5.holdfast.server;

import dev.xmat5.holdfast.server.claims.ChunkInteractionType;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.MOD
)
public class ServerConfig {
   private static final Builder BUILDER = new Builder();
   private static final IntValue MAX_FACTION_SIZE = BUILDER.comment("The maximum amount of members a faction can have.")
      .defineInRange("maxFactionSize", 10, 1, Integer.MAX_VALUE);
   private static final IntValue MAX_ALLIANCE_SIZE = BUILDER.comment("The maximum amount of factions an alliances can contain.")
      .defineInRange("maxAllianceSize", 3, 1, Integer.MAX_VALUE);
   private static final BooleanValue FORCE_FRIENDLY_FIRE = BUILDER.comment("Enables friendly fire, overwriting faction settings.")
      .define("forceFriendlyFire", false);
   private static final IntValue FACTION_ABBREVIATION_MIN_LENGTH = BUILDER.comment("Minimum length for faction abbreviations.")
      .defineInRange("factionAbbreviationMinLength", 3, 1, Integer.MAX_VALUE);
   private static final IntValue FACTION_ABBREVIATION_MAX_LENGTH = BUILDER.comment("Maximum length for faction abbreviations.")
      .defineInRange("factionAbbreviationMaxLength", 3, 1, Integer.MAX_VALUE);
   private static final IntValue ALLIANCE_ABBREVIATION_MIN_LENGTH = BUILDER.comment("Minimum length for alliance abbreviations.")
      .defineInRange("allianceAbbreviationMinLength", 3, 1, Integer.MAX_VALUE);
   private static final IntValue ALLIANCE_ABBREVIATION_MAX_LENGTH = BUILDER.comment("Maximum length for alliance abbreviations.")
      .defineInRange("allianceAbbreviationMaxLength", 3, 1, Integer.MAX_VALUE);
   private static final BooleanValue ENABLE_ABBREVIATION = BUILDER.comment("Allow factions to set an abbreviation").define("enableAbbreviation", true);
   private static final BooleanValue ALLOW_ABBREVIATION_CHANGE = BUILDER.comment("Allow factions to change their abbreviation")
      .define("allowAbbreviationChange", false);
   private static final IntValue COST_PER_CHUNK = BUILDER.comment("Cost in points per chunk").defineInRange("chunkCost", 1, 0, Integer.MAX_VALUE);
   private static final IntValue CORE_CHUNK_AMOUNT = BUILDER.comment("How many core chunks players should be allowed to own.")
      .defineInRange("coreChunks", 9, 0, Integer.MAX_VALUE);
   private static final BooleanValue REFUND_COST_UNCLAIM = BUILDER.comment(
         "If set to true, points are refunded when a chunk is unclaimed or set as an admin chunk"
      )
      .define("refundCostUnclaim", false);
   private static final IntValue POINTS_PER_KILL = BUILDER.comment("Points gained against a faction per kill.")
      .comment("To unclaim one chunk, chunkCost points are used and pointsPerStolenChunk are giving to the killing faction.")
      .defineInRange("pointsPerKill", 1, 0, Integer.MAX_VALUE);
   private static final IntValue POINTS_PER_STOLEN_CHUNK = BUILDER.comment(
         "How many claim points are given to a faction for taking a chunk from another faction"
      )
      .defineInRange("pointsPerStolenChunk", 1, 0, Integer.MAX_VALUE);
   private static final IntValue POINT_GENERATION_INTERVAL = BUILDER.comment(
         "The interval in seconds in which factions are given points to be used for claiming chunks"
      )
      .comment("After every interval, a point is given for every online member of the faction.")
      .defineInRange("pointGenerationInterval", 600, 0, Integer.MAX_VALUE);
   private static final IntValue POINT_GENERATION_AMOUNT = BUILDER.comment("How many points are given per interval")
      .defineInRange("pointGenerationAmount", 1, 0, Integer.MAX_VALUE);
   private static final IntValue ADMIN_CLAIM_COLOR = BUILDER.comment("The color of admin claims on the map").defineInRange("adminColor", 16711935, 0, 16777215);
   private static final IntValue CORE_CLAIM_COLOR = BUILDER.comment("The color of core claims on the map").defineInRange("coreColor", 16777215, 0, 16777215);
   private static final ConfigValue<List<? extends String>> ADMIN_CLAIM_RESTRICTIONS = BUILDER.comment(
         "The restrictions set for non-members in admin-claimed chunks"
      )
      .comment(
         "Possible values: BREAK_BLOCK, PLACE_BLOCK, RIGHT_CLICK_BLOCK, LEFT_CLICK_BLOCK, RIGHT_CLICK_ITEM, INTERACT_ENTITY, MOB_GRIEFING_DAMAGE, EXPLOSION_DAMAGE, PISTON_MOVE, USE_BUCKET, PLAYER_ATTACK"
      )
      .comment("'PLAYER_ATTACK' prevents players from attacking entities.")
      .defineListAllowEmpty(
         "adminClaimRestrictions",
         List.of("BREAK_BLOCK", "PLACE_BLOCK", "RIGHT_CLICK_BLOCK", "LEFT_CLICK_BLOCK", "RIGHT_CLICK_ITEM", "INTERACT_ENTITY", "USE_BUCKET", "PLAYER_ATTACK"),
         ServerConfig::validateRestriction
      );
   private static final ConfigValue<List<? extends String>> CORE_CLAIM_RESTRICTIONS = BUILDER.comment(
         "The restrictions set for non-members in core-claimed chunks"
      )
      .comment(
         "Possible values: BREAK_BLOCK, PLACE_BLOCK, RIGHT_CLICK_BLOCK, LEFT_CLICK_BLOCK, RIGHT_CLICK_ITEM, INTERACT_ENTITY, MOB_GRIEFING_DAMAGE, EXPLOSION_DAMAGE, PISTON_MOVE, USE_BUCKET, PLAYER_ATTACK"
      )
      .defineListAllowEmpty(
         "coreClaimRestrictions",
         List.of("BREAK_BLOCK", "PLACE_BLOCK", "RIGHT_CLICK_BLOCK", "LEFT_CLICK_BLOCK", "RIGHT_CLICK_ITEM", "INTERACT_ENTITY", "USE_BUCKET"),
         ServerConfig::validateRestriction
      );
   private static final ConfigValue<List<? extends String>> FACTION_CLAIM_RESTRICTIONS = BUILDER.comment(
         "The restrictions set for non-members in faction-claimed chunks"
      )
      .comment(
         "Possible values: BREAK_BLOCK, PLACE_BLOCK, RIGHT_CLICK_BLOCK, LEFT_CLICK_BLOCK, RIGHT_CLICK_ITEM, INTERACT_ENTITY, MOB_GRIEFING_DAMAGE, EXPLOSION_DAMAGE, PISTON_MOVE, USE_BUCKET, PLAYER_ATTACK"
      )
      .defineListAllowEmpty(
         "factionClaimRestrictions",
         List.of("BREAK_BLOCK", "PLACE_BLOCK", "RIGHT_CLICK_BLOCK", "LEFT_CLICK_BLOCK", "RIGHT_CLICK_ITEM", "INTERACT_ENTITY", "USE_BUCKET"),
         ServerConfig::validateRestriction
      );
   private static final ConfigValue<List<? extends String>> CORE_CLAIM_DIMENSIONS = BUILDER.comment("The dimensions allowed for core (player) claims.")
      .defineListAllowEmpty("coreClaimDimensions", List.of("minecraft:overworld"), o -> o instanceof String);
   private static final ConfigValue<List<? extends String>> FACTION_CLAIM_DIMENSIONS = BUILDER.comment("The dimensions allowed for faction claims.")
      .defineListAllowEmpty("factionClaimDimensions", List.of("minecraft:overworld"), o -> o instanceof String);
   private static final IntValue FACTION_BASE_CLAIM_LIMIT = BUILDER.comment("The base amount of chunks a faction can claim")
      .defineInRange("factionBaseClaimLimit", 100, 0, Integer.MAX_VALUE);
   private static final IntValue FACTION_ADDITIONAL_CLAIM_LIMIT_PER_MEMBER = BUILDER.comment("The additional amount of chunks a faction can claim per member.")
      .comment("The final limit of claimable chunks per faction is (factionBaseClaimLimit + factionAdditionalClaimLimitPerMember * factionMembers)")
      .defineInRange("factionAdditionalClaimLimitPerMember", 100, 0, Integer.MAX_VALUE);
   private static final IntValue MAX_CHUNKS_PER_PACKET = BUILDER.comment("How many chunks should be sent per claim update packet.")
      .comment("Set to a higher amount if mods such as XLPackets are installed.")
      .defineInRange("maxChunksPerPacket", 1000, 10, Integer.MAX_VALUE);
   public static final ModConfigSpec SPEC = BUILDER.build();
   public static int maxAllianceSize;
   public static int maxFactionSize;
   public static boolean forceFriendlyFire;
   public static int factionAbbreviationMinLength;
   public static int factionAbbreviationMaxLength;
   public static int allianceAbbreviationMinLength;
   public static int allianceAbbreviationMaxLength;
   public static boolean enableAbbreviation;
   public static boolean allowAbbreviationChange;
   public static int chunkCost;
   public static int coreChunkAmount;
   public static boolean refundCostUnclaim;
   public static int pointsPerKill;
   public static int pointsPerStolenChunk;
   public static int pointGenerationInterval;
   public static int pointGenerationAmount;
   public static int adminClaimColor;
   public static int coreClaimColor;
   public static Set<ChunkInteractionType> adminClaimRestrictions;
   public static Set<ChunkInteractionType> coreClaimRestrictions;
   public static Set<ChunkInteractionType> factionClaimRestrictions;
   public static Set<String> coreClaimDimensions;
   public static Set<String> factionClaimDimensions;
   public static int factionBaseClaimLimit;
   public static int factionAdditionalClaimLimitPerMember;
   public static int maxChunksPerPacket;

   private static boolean validateRestriction(Object object) {
      if (object instanceof String) {
         try {
            ChunkInteractionType.valueOf((String)object);
            return true;
         } catch (IllegalArgumentException var2) {
            return false;
         }
      } else {
         return false;
      }
   }

   @SubscribeEvent
   static void onLoad(ModConfigEvent event) {
      if (event instanceof ModConfigEvent.Unloading) {
         return;
      }

      if (event.getConfig().getSpec() == SPEC) {
         maxAllianceSize = (Integer)MAX_ALLIANCE_SIZE.get();
         maxFactionSize = (Integer)MAX_FACTION_SIZE.get();
         forceFriendlyFire = (Boolean)FORCE_FRIENDLY_FIRE.get();
         factionAbbreviationMinLength = (Integer)FACTION_ABBREVIATION_MIN_LENGTH.get();
         factionAbbreviationMaxLength = (Integer)FACTION_ABBREVIATION_MAX_LENGTH.get();
         allianceAbbreviationMinLength = (Integer)ALLIANCE_ABBREVIATION_MIN_LENGTH.get();
         allianceAbbreviationMaxLength = (Integer)ALLIANCE_ABBREVIATION_MAX_LENGTH.get();
         enableAbbreviation = (Boolean)ENABLE_ABBREVIATION.get();
         allowAbbreviationChange = (Boolean)ALLOW_ABBREVIATION_CHANGE.get();
         chunkCost = (Integer)COST_PER_CHUNK.get();
         coreChunkAmount = (Integer)CORE_CHUNK_AMOUNT.get();
         refundCostUnclaim = (Boolean)REFUND_COST_UNCLAIM.get();
         pointsPerKill = (Integer)POINTS_PER_KILL.get();
         pointsPerStolenChunk = (Integer)POINTS_PER_STOLEN_CHUNK.get();
         pointGenerationInterval = (Integer)POINT_GENERATION_INTERVAL.get();
         pointGenerationAmount = (Integer)POINT_GENERATION_AMOUNT.get();
         adminClaimColor = (Integer)ADMIN_CLAIM_COLOR.get();
         coreClaimColor = (Integer)CORE_CLAIM_COLOR.get();
         adminClaimRestrictions = ADMIN_CLAIM_RESTRICTIONS.get()
            .stream()
            .map(ChunkInteractionType::valueOf)
            .collect(Collectors.toSet());
         coreClaimRestrictions = CORE_CLAIM_RESTRICTIONS.get()
            .stream()
            .map(ChunkInteractionType::valueOf)
            .collect(Collectors.toSet());
         factionClaimRestrictions = FACTION_CLAIM_RESTRICTIONS.get()
            .stream()
            .map(ChunkInteractionType::valueOf)
            .collect(Collectors.toSet());
         coreClaimDimensions = new HashSet<>((Collection<? extends String>)CORE_CLAIM_DIMENSIONS.get());
         factionClaimDimensions = new HashSet<>((Collection<? extends String>)FACTION_CLAIM_DIMENSIONS.get());
         factionBaseClaimLimit = (Integer)FACTION_BASE_CLAIM_LIMIT.get();
         factionAdditionalClaimLimitPerMember = (Integer)FACTION_ADDITIONAL_CLAIM_LIMIT_PER_MEMBER.get();
         maxChunksPerPacket = (Integer)MAX_CHUNKS_PER_PACKET.get();
      }
   }
}
