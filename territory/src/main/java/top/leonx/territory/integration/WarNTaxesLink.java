package top.leonx.territory.integration;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WarNTaxesLink {

    public static final String MOD_ID = "minecolonytax";

    public record Side(Set<UUID> attackers, Set<UUID> defenders) {}

    private static final Logger LOG = LoggerFactory.getLogger("territory-war");
    private static final String WAR_SYSTEM = "net.machiavelli.minecolonytax.WarSystem";
    private static final String WAR_DATA = "net.machiavelli.minecolonytax.data.WarData";
    private static final String FIGHTING = "INWAR";

    private static volatile boolean resolved;
    private static volatile boolean usable;
    private static Field activeWars;
    private static Method getStatus;
    private static Method getAttacker;
    private static Method getDefender;
    private static Method getAttackerAllies;
    private static Method getDefenderAllies;

    private WarNTaxesLink() {
    }

    public static boolean loaded() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!loaded()) return;
        try {
            bind(Class.forName(WAR_SYSTEM), Class.forName(WAR_DATA));
            LOG.info("WarNTaxes found: wars it puts in their fighting phase now count as wars between the factions involved.");
        } catch (Throwable t) {
            LOG.warn("WarNTaxes is installed but its war API could not be read ({}). Faction wars are switched off.", t.toString());
        }
    }

    static synchronized void bind(Class<?> system, Class<?> war) throws ReflectiveOperationException {
        activeWars = system.getField("ACTIVE_WARS");
        getStatus = war.getMethod("getStatus");
        getAttacker = war.getMethod("getAttacker");
        getDefender = war.getMethod("getDefender");
        getAttackerAllies = war.getMethod("getAttackerAllies");
        getDefenderAllies = war.getMethod("getDefenderAllies");
        resolved = true;
        usable = true;
    }

    @SuppressWarnings("unchecked")
    public static List<Side> fightingWars() {
        if (!resolved) resolve();
        if (!usable) return List.of();
        try {
            Object map = activeWars.get(null);
            if (!(map instanceof Map<?, ?> wars) || wars.isEmpty()) return List.of();
            List<Side> out = new ArrayList<>();
            for (Object war : wars.values()) {
                Object status = getStatus.invoke(war);
                if (status == null || !FIGHTING.equals(((Enum<?>) status).name())) continue;
                Set<UUID> attackers = new HashSet<>();
                Set<UUID> defenders = new HashSet<>();
                add(attackers, getAttacker.invoke(war));
                add(defenders, getDefender.invoke(war));
                Object aa = getAttackerAllies.invoke(war);
                Object da = getDefenderAllies.invoke(war);
                if (aa instanceof Collection<?> c) for (Object o : c) add(attackers, o);
                if (da instanceof Collection<?> c) for (Object o : c) add(defenders, o);
                out.add(new Side(attackers, defenders));
            }
            return out;
        } catch (Throwable t) {
            LOG.warn("Reading WarNTaxes wars failed ({}). Faction wars are switched off.", t.toString());
            usable = false;
            return List.of();
        }
    }

    private static void add(Set<UUID> into, Object value) {
        if (value instanceof UUID id) into.add(id);
    }
}
