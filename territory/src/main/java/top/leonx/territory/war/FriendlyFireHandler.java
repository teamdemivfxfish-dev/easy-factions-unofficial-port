package top.leonx.territory.war;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.integration.FactionsBridge;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.GAME
)
public final class FriendlyFireHandler {
   private static final Method NONE;
   private static final Map<Class<?>, Method> OWNER_METHODS;
   private static final String[] OWNER_ACCESSORS;
   private static final int MAX_OWNER_DEPTH = 4;

   private FriendlyFireHandler() {
   }

   @SubscribeEvent(
      priority = EventPriority.HIGH
   )
   public static void onIncomingDamage(LivingIncomingDamageEvent event) {
      if (TerritoryConfig.protectFactionMembers() && FactionsBridge.loaded()) {
         LivingEntity victim = event.getEntity();
         if (victim != null && !victim.level().isClientSide()) {
            MinecraftServer server = victim.getServer();
            if (server != null) {
               UUID victimSide = victimSide(victim);
               if (victimSide != null) {
                  UUID attackerSide = attackerSide(event.getSource().getEntity());
                  if (attackerSide == null) {
                     attackerSide = attackerSide(event.getSource().getDirectEntity());
                  }

                  if (attackerSide != null) {
                     if (FactionsBridge.sameSideNoFriendlyFire(server, attackerSide, victimSide)) {
                        event.setCanceled(true);
                     }
                  }
               }
            }
         }
      }
   }

   @SubscribeEvent(
      priority = EventPriority.HIGH
   )
   public static void onChangeTarget(LivingChangeTargetEvent event) {
      if (TerritoryConfig.stopFriendlyTargeting() && TerritoryConfig.protectFactionMembers()) {
         if (FactionsBridge.loaded()) {
            LivingEntity mob = event.getEntity();
            LivingEntity target = event.getNewAboutToBeSetTarget();
            if (mob != null && target != null && !mob.level().isClientSide()) {
               MinecraftServer server = mob.getServer();
               if (server != null) {
                  UUID owner = attackerSide(mob);
                  if (owner != null) {
                     UUID targetSide = victimSide(target);
                     if (targetSide != null) {
                        if (FactionsBridge.sameSideNoFriendlyFire(server, owner, targetSide)) {
                           event.setCanceled(true);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static UUID attackerSide(Entity entity) {
      return resolveSide(entity, 0);
   }

   private static UUID victimSide(Entity entity) {
      if (entity == null) {
         return null;
      } else if (entity instanceof Player player) {
         return player.getUUID();
      } else {
         return !TerritoryConfig.protectFactionPets() ? null : resolveSide(entity, 0);
      }
   }

   private static UUID resolveSide(Entity entity, int depth) {
      if (entity == null || depth > 4) {
         return null;
      } else if (entity instanceof Player player) {
         return player.getUUID();
      } else {
         if (entity instanceof OwnableEntity ownable) {
            UUID owner = ownable.getOwnerUUID();
            if (owner != null) {
               return owner;
            }
         }

         if (entity instanceof Projectile projectile) {
            Entity shooter = projectile.getOwner();
            if (shooter != null && shooter != entity) {
               return resolveSide(shooter, depth + 1);
            }
         }

         return reflectiveOwner(entity, depth);
      }
   }

   private static UUID reflectiveOwner(Entity entity, int depth) {
      Method method = OWNER_METHODS.computeIfAbsent(entity.getClass(), FriendlyFireHandler::findOwnerMethod);
      if (method != null && method != NONE) {
         try {
            Object result = method.invoke(entity);
            if (result instanceof UUID) {
               return (UUID)result;
            }

            if (result instanceof Entity other && other != entity) {
               return resolveSide(other, depth + 1);
            }
         } catch (Throwable var5) {
         }

         return null;
      } else {
         return null;
      }
   }

   private static Method findOwnerMethod(Class<?> type) {
      for (String name : OWNER_ACCESSORS) {
         Method m = accessor(type, name);
         if (m != null) {
            return m;
         }
      }

      Method uuidAccessor = accessor(type, "getOwnerUUID");
      return uuidAccessor != null ? uuidAccessor : NONE;
   }

   private static Method accessor(Class<?> type, String name) {
      try {
         Method m = type.getMethod(name);
         if (m.getParameterCount() != 0) {
            return null;
         }

         Class<?> returns = m.getReturnType();
         if (Entity.class.isAssignableFrom(returns) || UUID.class.equals(returns)) {
            m.setAccessible(true);
            return m;
         }
      } catch (SecurityException | NoSuchMethodException var4) {
      }

      return null;
   }

   static {
      Method none;
      try {
         none = Object.class.getMethod("hashCode");
      } catch (NoSuchMethodException var2) {
         none = null;
      }

      NONE = none;
      OWNER_METHODS = new ConcurrentHashMap<>();
      OWNER_ACCESSORS = new String[]{"getSummoner", "getOwner", "getTrueOwner", "getCaster"};
   }
}
