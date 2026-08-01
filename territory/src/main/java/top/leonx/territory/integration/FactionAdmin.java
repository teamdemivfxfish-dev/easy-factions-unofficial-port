package top.leonx.territory.integration;

import com.jpreiss.easy_factions.server.faction.Faction;
import com.jpreiss.easy_factions.server.faction.FactionStateManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Operator overrides for faction membership: putting a player into a faction, taking one out, and ending a
 * faction, without going through the invite an operator has no way to send on someone else's behalf.
 *
 * <h2>Why this needs to exist at all</h2>
 * Every membership route Easy Factions offers is written from the point of view of a member. Inviting
 * requires being the owner or an officer of the faction you are inviting to, joining requires an invitation
 * addressed to you, and kicking requires being an officer of the faction you are kicking from. An operator
 * standing outside all three is simply not modelled, so a leader who has quit, an invite that was never
 * accepted, or a member stuck in a faction nobody is left to kick them from has no in-game remedy.
 *
 * <h2>How the join is actually performed</h2>
 * The obvious approach — writing the player into the faction's member set directly — quietly breaks. Easy
 * Factions keeps a second index, {@code playerFactionMap}, that every one of its own lookups reads, and it
 * is private with no setter. A player added only to the member set therefore holds no faction as far as the
 * running server is concerned, and appears in it after the next restart, when that index is rebuilt from
 * the saved member sets. That is a worse bug than the one being fixed.
 *
 * So the invite is written and then Easy Factions' own {@code joinFaction} is called to consume it. Nothing
 * is bypassed except the part an operator cannot do: the faction full check, the already in a faction check,
 * the join event, the client sync and the player's command tree all run exactly as they do for a real join.
 *
 * <h2>Why the target has to be online</h2>
 * {@code joinFaction} and {@code leaveFaction} both take a {@code ServerPlayer}, because both of them sync
 * the result to that player's client and rebuild their command tree. There is no offline equivalent, and
 * reaching past them into the private index to fake one would reintroduce precisely the desync described
 * above. Ending a faction outright is the exception and needs nobody online: see
 * {@link #forceDisband(MinecraftServer, String)}.
 */
public final class FactionAdmin {

    private FactionAdmin() {}

    /** Every faction name on the server, sorted, for command suggestions. Empty if EF is absent. */
    public static List<String> factionNames(MinecraftServer server) {
        if (!EasyFactionsBridge.loaded() || server == null) return List.of();
        Set<String> names = FactionStateManager.get(server).getAllFactionNames();
        List<String> out = new ArrayList<>(names);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Put {@code target} into {@code factionName} whether or not they were invited.
     *
     * @return a message for the operator; {@code ok} is false when nothing was changed
     */
    public static Result forceAdd(MinecraftServer server, ServerPlayer target, String factionName) {
        if (!EasyFactionsBridge.loaded()) return Result.fail("Easy Factions is not installed.");
        if (server == null || target == null) return Result.fail("No server.");

        FactionStateManager fsm = FactionStateManager.get(server);
        Faction faction = fsm.getFactionByName(factionName);
        if (faction == null) {
            return Result.fail("There is no faction called \"" + factionName + "\".");
        }

        UUID uuid = target.getUUID();
        Faction current = fsm.getFactionByPlayer(uuid);
        if (current != null) {
            if (current.getName().equals(faction.getName())) {
                return Result.fail(target.getGameProfile().getName() + " is already in " + faction.getName() + ".");
            }
            return Result.fail(target.getGameProfile().getName() + " is already in " + current.getName()
                    + ". Take them out of it first: /territory faction remove "
                    + target.getGameProfile().getName());
        }

        // Write the invitation this player was never sent, then let Easy Factions consume it the same way it
        // consumes a real one. Restore the invite list on failure so a refused add leaves nothing behind.
        boolean alreadyInvited = faction.getInvited().contains(uuid);
        if (!alreadyInvited) faction.getInvited().add(uuid);
        try {
            fsm.joinFaction(target, faction.getName(), server);
        } catch (RuntimeException e) {
            if (!alreadyInvited) faction.getInvited().remove(uuid);
            // the faction being full is the one refusal an operator will actually hit, and EF words it well
            return Result.fail(e.getMessage() != null ? e.getMessage() : "Easy Factions refused the join.");
        }
        return Result.ok("Added " + target.getGameProfile().getName() + " to " + faction.getName() + ".");
    }

    /**
     * Take {@code target} out of whatever faction they are in.
     *
     * Refuses on the OWNER. Easy Factions treats an owner leaving as the faction ending — {@code leaveFaction}
     * calls straight through to {@code disbandFaction} — so removing one member would silently take the
     * faction, its claims and everyone else in it with them. That has to be asked for by name.
     */
    public static Result forceRemove(MinecraftServer server, ServerPlayer target) {
        if (!EasyFactionsBridge.loaded()) return Result.fail("Easy Factions is not installed.");
        if (server == null || target == null) return Result.fail("No server.");

        FactionStateManager fsm = FactionStateManager.get(server);
        UUID uuid = target.getUUID();
        Faction faction = fsm.getFactionByPlayer(uuid);
        String who = target.getGameProfile().getName();
        if (faction == null) return Result.fail(who + " is not in a faction.");

        if (uuid.equals(faction.getOwner())) {
            return Result.fail(who + " OWNS " + faction.getName() + ", and Easy Factions ends a faction when"
                    + " its owner leaves. Removing them would disband it and release its land. If that is what"
                    + " you want: /territory faction disband " + faction.getName());
        }

        String name = faction.getName();
        try {
            fsm.leaveFaction(target, server);
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage() != null ? e.getMessage() : "Easy Factions refused the removal.");
        }
        return Result.ok("Removed " + who + " from " + name + ".");
    }

    /**
     * End a faction outright. Works with nobody online, since Easy Factions' disband takes only a name.
     *
     * The land is not left stranded: our own {@code FactionDisbandEvent} listener runs first and hands the
     * ex-leader back what his personal cap allows before releasing the rest. Without it, Easy Factions'
     * {@code deleteFactionData} would drop the faction from its indexes and leave every chunk claimed by a
     * faction that no longer exists, which nothing can then unclaim.
     */
    public static Result forceDisband(MinecraftServer server, String factionName) {
        if (!EasyFactionsBridge.loaded()) return Result.fail("Easy Factions is not installed.");
        if (server == null) return Result.fail("No server.");

        FactionStateManager fsm = FactionStateManager.get(server);
        Faction faction = fsm.getFactionByName(factionName);
        if (faction == null) return Result.fail("There is no faction called \"" + factionName + "\".");

        String name = faction.getName();
        int members = faction.getMembers() != null ? faction.getMembers().size() : 0;
        fsm.disbandFaction(name, server);
        return Result.ok("Disbanded " + name + " (" + members + (members == 1 ? " member" : " members") + ").");
    }

    /** One line per faction for {@code /territory faction list}: name, member count, owner, claims held. */
    public static List<String> roster(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        if (!EasyFactionsBridge.loaded() || server == null) return out;
        FactionStateManager fsm = FactionStateManager.get(server);
        for (String name : factionNames(server)) {
            Faction f = fsm.getFactionByName(name);
            if (f == null) continue;
            int members = f.getMembers() != null ? f.getMembers().size() : 0;
            out.add(name + " - " + members + (members == 1 ? " member" : " members")
                    + ", owned by " + nameOf(server, f.getOwner()));
        }
        return out;
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        if (id == null) return "nobody";
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (server.getProfileCache() != null) {
            var profile = server.getProfileCache().get(id);
            if (profile.isPresent()) return profile.get().getName();
        }
        return id.toString().substring(0, 8);
    }

    /** Outcome of an operator override: whether anything changed, and what to tell the operator. */
    public record Result(boolean ok, String message) {
        static Result ok(String message) {
            return new Result(true, message);
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }
}
