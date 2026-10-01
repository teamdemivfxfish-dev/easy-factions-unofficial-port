package top.leonx.territory.integration;

import dev.xmat5.holdfast.server.faction.Faction;
import dev.xmat5.holdfast.server.faction.FactionStateManager;
import net.minecraft.server.MinecraftServer;
import top.leonx.territory.TerritoryConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class WarState {

    private static final Map<String, Set<String>> EMPTY = Map.of();
    private static long cachedAt = Long.MIN_VALUE;
    private static Map<String, Set<String>> cached = EMPTY;

    private WarState() {
    }

    public static Set<String> opponentsOf(MinecraftServer server, String faction) {
        if (faction == null || faction.isEmpty()) return Set.of();
        Set<String> out = table(server).get(faction);
        return out == null ? Set.of() : out;
    }

    public static boolean atWar(MinecraftServer server, String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) return false;
        return opponentsOf(server, a).contains(b);
    }

    public static boolean inAnyWar(MinecraftServer server, String faction) {
        return !opponentsOf(server, faction).isEmpty();
    }

    private static synchronized Map<String, Set<String>> table(MinecraftServer server) {
        if (server == null || !TerritoryConfig.warEnabled() || !WarNTaxesLink.loaded()) return EMPTY;
        long now = server.getTickCount();
        if (now - cachedAt < 20L && now >= cachedAt) return cached;
        cachedAt = now;
        cached = compute(server);
        return cached;
    }

    private static Map<String, Set<String>> compute(MinecraftServer server) {
        FactionStateManager fsm = FactionStateManager.get(server);
        return opponents(WarNTaxesLink.fightingWars(), id -> {
            Faction f = fsm.getFactionByPlayer(id);
            return f == null ? null : f.getName();
        });
    }

    static Map<String, Set<String>> opponents(List<WarNTaxesLink.Side> wars, Function<UUID, String> factionOf) {
        Map<String, Set<String>> out = new HashMap<>();
        for (WarNTaxesLink.Side war : wars) {
            Set<String> attackers = factions(factionOf, war.attackers());
            Set<String> defenders = factions(factionOf, war.defenders());
            for (String a : attackers) {
                for (String d : defenders) {
                    if (a.equals(d)) continue;
                    out.computeIfAbsent(a, k -> new HashSet<>()).add(d);
                    out.computeIfAbsent(d, k -> new HashSet<>()).add(a);
                }
            }
        }
        return out;
    }

    private static Set<String> factions(Function<UUID, String> factionOf, Set<UUID> players) {
        Set<String> names = new HashSet<>();
        for (UUID id : players) {
            String name = factionOf.apply(id);
            if (name != null) names.add(name);
        }
        return names;
    }
}
