package dev.xmat5.holdfast.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.MOD,
   value = {Dist.CLIENT}
)
public class ClientConfig {
   private static final Builder BUILDER = new Builder();
   private static final BooleanValue SHOW_FACTION_ABBREVIATION = BUILDER.comment(
         "Show the faction abbreviation (if available) instead of the name in the tag above player heads."
      )
      .define("showFactionAbbreviation", false);
   private static final BooleanValue SHOW_ALLIANCE_ABBREVIATION = BUILDER.comment(
         "Show the alliance abbreviation (if available) instead of the name in the tag above player heads."
      )
      .define("showAllianceAbbreviation", true);
   private static final DoubleValue CHUNK_BORDER_WIDTH = BUILDER.comment("The thickness of the stroke around claimed chunks")
      .defineInRange("chunkBorderWidth", 1.5, 0.0, 2.0);
   private static final DoubleValue CHUNK_BORDER_OPACITY = BUILDER.comment("The opacity of the stroke around claimed chunks")
      .defineInRange("chunkBorderOpacity", 0.0, 0.0, 1.0);
   private static final DoubleValue CHUNK_OVERLAY_OPACITY = BUILDER.comment("The opacity of the color above claimed chunks")
      .defineInRange("chunkOverlayOpacity", 0.25, 0.0, 1.0);
   private static final IntValue CLAIM_MERGE_GRID_SIZE = BUILDER.comment(
         new String[]{
            "The grid size (in chunks) used to split massive claims on the map.",
            "Lower values (e.g. 16) prevent large claims from disappearing on the minimap.",
            "Higher values (e.g. 64) increase performance."
         }
      )
      .defineInRange("claimMergeGridSize", 32, 1, 256);
   public static final ModConfigSpec SPEC = BUILDER.build();
   public static boolean showFactionAbbreviation;
   public static boolean showAllianceAbbreviation;
   public static float chunkBorderWidth;
   public static float chunkBorderOpacity;
   public static float chunkOverlayOpacity;
   public static int claimMergeGridSize;

   @SubscribeEvent
   static void onLoad(ModConfigEvent event) {
      if (event instanceof ModConfigEvent.Unloading) {
         return;
      }

      if (event.getConfig().getSpec() == SPEC) {
         showFactionAbbreviation = (Boolean)SHOW_FACTION_ABBREVIATION.get();
         showAllianceAbbreviation = (Boolean)SHOW_ALLIANCE_ABBREVIATION.get();
         chunkBorderWidth = ((Double)CHUNK_BORDER_WIDTH.get()).floatValue();
         chunkBorderOpacity = ((Double)CHUNK_BORDER_OPACITY.get()).floatValue();
         chunkOverlayOpacity = ((Double)CHUNK_OVERLAY_OPACITY.get()).floatValue();
         claimMergeGridSize = (Integer)CLAIM_MERGE_GRID_SIZE.get();
      }
   }

   public static void setShowFactionAbbreviation(boolean value) {
      SHOW_FACTION_ABBREVIATION.set(value);
      showFactionAbbreviation = value;
   }

   public static void setShowAllianceAbbreviation(boolean value) {
      SHOW_ALLIANCE_ABBREVIATION.set(value);
      showAllianceAbbreviation = value;
   }

   public static boolean getShowFactionAbbreviation() {
      return (Boolean)SHOW_FACTION_ABBREVIATION.get();
   }

   public static boolean getShowAllianceAbbreviation() {
      return (Boolean)SHOW_ALLIANCE_ABBREVIATION.get();
   }
}
