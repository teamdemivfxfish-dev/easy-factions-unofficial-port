package dev.xmat5.holdfast.objective;

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
   bus = Bus.MOD
)
public class ObjectiveConfig {
   private static final Builder BUILDER = new Builder();
   private static final IntValue CONTROL_POINT_LIFETIME = BUILDER.comment(
         "Max age (in seconds) a control point can have. Older points expire, so a faction's score decays unless it keeps holding the objective."
      )
      .defineInRange("controlPointLifetime", 600, 1, Integer.MAX_VALUE);
   private static final IntValue CONTROL_POINT_INTERVAL = BUILDER.comment("How often (in seconds) points are awarded to the factions holding the core area.")
      .defineInRange("controlPointInterval", 10, 1, Integer.MAX_VALUE);
   private static final IntValue REWARDS_CHECK_INTERVAL = BUILDER.comment("How often (in seconds) the system checks whether to generate rewards.")
      .defineInRange("rewardsCheckInterval", 30, 1, Integer.MAX_VALUE);
   private static final DoubleValue LOSS_PERCENTAGE = BUILDER.comment("Fraction of points a faction loses when a member dies in the Kill Zone (0.5 = 50%).")
      .defineInRange("lossPercentage", 0.5, 0.0, 1.0);
   private static final IntValue MAX_POINT_LOSS = BUILDER.comment("The maximum raw amount of points that can be lost in a single kill.")
      .defineInRange("maxPointLoss", 100, 0, Integer.MAX_VALUE);
   private static final DoubleValue CONVERSION_PERCENTAGE = BUILDER.comment(
         "How much of the lost points are transferred to the killer's faction (0.5 = 50% transfer)."
      )
      .defineInRange("conversionPercentage", 0.5, 0.0, 1.0);
   private static final BooleanValue DISABLE_REWARD_INFO = BUILDER.comment("Disables the /objective listRewards command for non-operators.")
      .define("disableRewardInfo", false);
   public static final ModConfigSpec SPEC = BUILDER.build();
   public static int controlPointLifetime;
   public static int controlPointInterval;
   public static int rewardsCheckInterval;
   public static double lossPercentage;
   public static int maxPointLoss;
   public static double conversionPercentage;
   public static boolean disableRewardInfo;

   @SubscribeEvent
   static void onLoad(ModConfigEvent event) {
      if (event instanceof ModConfigEvent.Unloading) {
         return;
      }

      if (event.getConfig().getSpec() == SPEC) {
         controlPointLifetime = (Integer)CONTROL_POINT_LIFETIME.get();
         controlPointInterval = (Integer)CONTROL_POINT_INTERVAL.get();
         rewardsCheckInterval = (Integer)REWARDS_CHECK_INTERVAL.get();
         lossPercentage = (Double)LOSS_PERCENTAGE.get();
         maxPointLoss = (Integer)MAX_POINT_LOSS.get();
         conversionPercentage = (Double)CONVERSION_PERCENTAGE.get();
         disableRewardInfo = (Boolean)DISABLE_REWARD_INFO.get();
      }
   }
}
