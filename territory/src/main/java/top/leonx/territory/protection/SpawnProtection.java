package top.leonx.territory.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.integration.FactionsBridge;
import top.leonx.territory.world.AdminTerritories;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.GAME
)
public final class SpawnProtection {

   private SpawnProtection() {
   }

   @SubscribeEvent(
      priority = EventPriority.LOWEST
   )
   public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
      if (event.isSpawnCancelled() || !FactionsBridge.loaded()) {
         return;
      }

      boolean blockMonsters = TerritoryConfig.SAFEZONE_BLOCK_MONSTER_SPAWNS.get();
      boolean blockAll = TerritoryConfig.SAFEZONE_BLOCK_ALL_SPAWNS.get();
      boolean blockWarzone = TerritoryConfig.WARZONE_BLOCK_NATURAL_SPAWNS.get();
      if (!blockMonsters && !blockAll && !blockWarzone) {
         return;
      }

      Mob mob = event.getEntity();
      MobSpawnType type = event.getSpawnType();
      boolean safezoneRule = shouldBlock(type, isHostile(mob), blockMonsters, blockAll);
      boolean warzoneRule = blockWarzone && shouldBlockInWarzone(type);
      if (!safezoneRule && !warzoneRule || !(event.getLevel().getLevel() instanceof ServerLevel level)) {
         return;
      }

      MinecraftServer server = level.getServer();
      if (!server.isSameThread()) {
         return;
      }

      BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
      if (FactionsBridge.handsOff(server, level.dimension(), pos)) {
         return;
      }

      AdminTerritories.Territory zone = AdminTerritories.get(server).governing(level.dimension(), new ChunkPos(pos).toLong());
      if (zone != null && (zone.warzone() ? warzoneRule : safezoneRule)) {
         event.setSpawnCancelled(true);
      }
   }

   public static boolean isHostile(Mob mob) {
      return mob instanceof Enemy || mob.getType().getCategory() == MobCategory.MONSTER;
   }

   private static boolean natural(MobSpawnType type) {
      return switch (type) {
         case NATURAL, CHUNK_GENERATION, STRUCTURE, JOCKEY, EVENT, REINFORCEMENT, PATROL -> true;
         default -> false;
      };
   }

   private static boolean automatic(MobSpawnType type) {
      return natural(type) || type == MobSpawnType.SPAWNER || type == MobSpawnType.TRIAL_SPAWNER;
   }

   public static boolean shouldBlock(MobSpawnType type, boolean hostile, boolean blockMonsters, boolean blockAll) {
      return automatic(type) && (blockAll || blockMonsters && hostile);
   }

   public static boolean shouldBlockInWarzone(MobSpawnType type) {
      return natural(type);
   }
}
