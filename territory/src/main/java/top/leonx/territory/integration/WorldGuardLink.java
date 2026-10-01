package top.leonx.territory.integration;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldGuardLink {
   private static final Logger LOG = LoggerFactory.getLogger("territory-worldguard");
   private static boolean resolved;
   private static Method zonesAt;
   private static Method globalZone;
   private static Method zoneId;
   private static boolean warned;

   private WorldGuardLink() {
   }

   private static synchronized void resolve() {
      if (!resolved) {
         resolved = true;
         try {
            Class<?> manager = Class.forName("com.jakeweinrich.jakesworldguard.ZoneManager");
            Class<?> zone = Class.forName("com.jakeweinrich.jakesworldguard.Zone");
            zonesAt = manager.getMethod("getZonesAt", String.class, BlockPos.class);
            globalZone = manager.getMethod("getGlobalZone", String.class);
            zoneId = zone.getMethod("getId");
         } catch (ReflectiveOperationException | LinkageError e) {
            zonesAt = null;
            LOG.warn("Jake's World Guard is installed but its zone API was not found ({}), so safezones stay under Holdfast Factions rules.", e.toString());
         }
      }
   }

   public static boolean zoneAt(String dimension, BlockPos pos) {
      resolve();
      if (zonesAt == null) {
         return false;
      } else {
         try {
            List<?> zones = (List<?>)zonesAt.invoke(null, dimension, pos);
            if (zones == null || zones.isEmpty()) {
               return false;
            } else {
               Object global = globalZone.invoke(null, dimension);
               Object globalId = global == null ? null : zoneId.invoke(global);

               for (Object zone : zones) {
                  Object id = zoneId.invoke(zone);
                  if (id != null && !id.equals(globalId)) {
                     return true;
                  }
               }

               return false;
            }
         } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
               warned = true;
               LOG.warn("Asking Jake's World Guard for the zones at a position failed: {}", e.toString());
            }

            return false;
         }
      }
   }
}
