package top.leonx.territory.war;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.EasyFactionsBridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A faction's friendly fire setting applied to everything that fights on its behalf.
 *
 * <h2>The report</h2>
 * Summons from Iron's Spells attack faction members, will not fight the actual enemy, and stand in the way
 * while doing it. Easy Factions has a friendly fire switch and it covers one player swinging at another; a
 * summoned skeleton, a spell construct or a tamed wolf is not a player, so it walks straight past that check
 * and treats an ally like anything else. The summoner and the person being mauled are on the same side, and
 * nothing in either mod knows it.
 *
 * <h2>Both halves are needed</h2>
 * Cancelling the damage alone leaves the summon locked onto a friend it can never hurt, swinging at them
 * instead of at the enemy it was summoned for, which is the "will not fight the actual enemies" half of the
 * same report. So a friendly target is refused outright and the mob goes looking for a real one, and the
 * damage cancel stays as the backstop for everything that never asks for a target: arrows, splash potions,
 * area effects, and any mob that picks its victim in its own code.
 *
 * <h2>Finding out who owns a mob</h2>
 * Vanilla answers through {@link OwnableEntity}, and pets and most summons implement it. Iron's Spells and
 * several other summon mods instead expose a summoner through their own interface, which cannot be named
 * here without making that mod a hard dependency, so those are read reflectively and the lookup is cached
 * per entity class. A mob whose owner cannot be established is simply not covered: this never guesses, and
 * a wrong guess would cancel damage between strangers.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FriendlyFireHandler {

    private FriendlyFireHandler() {}

    /** Nothing, cached, for a class that has no owner accessor at all, so it is looked up only once. */
    private static final Method NONE;

    static {
        Method none;
        try {
            none = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            none = null;
        }
        NONE = none;
    }

    private static final Map<Class<?>, Method> OWNER_METHODS = new ConcurrentHashMap<>();

    /** Names other mods use for "the player this belongs to", in the order they are worth trying. */
    private static final String[] OWNER_ACCESSORS = {"getSummoner", "getOwner", "getTrueOwner", "getCaster"};

    /** How far a chain of "who owns this" is followed before it is treated as a loop. */
    private static final int MAX_OWNER_DEPTH = 4;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!TerritoryConfig.protectFactionMembers() || !EasyFactionsBridge.loaded()) return;
        LivingEntity victim = event.getEntity();
        if (victim == null || victim.level().isClientSide()) return;
        MinecraftServer server = victim.getServer();
        if (server == null) return;

        UUID victimSide = victimSide(victim);
        if (victimSide == null) return;
        UUID attackerSide = attackerSide(event.getSource().getEntity());
        if (attackerSide == null) attackerSide = attackerSide(event.getSource().getDirectEntity());
        if (attackerSide == null) return;

        if (EasyFactionsBridge.sameSideNoFriendlyFire(server, attackerSide, victimSide)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!TerritoryConfig.stopFriendlyTargeting() || !TerritoryConfig.protectFactionMembers()) return;
        if (!EasyFactionsBridge.loaded()) return;
        LivingEntity mob = event.getEntity();
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (mob == null || target == null || mob.level().isClientSide()) return;
        MinecraftServer server = mob.getServer();
        if (server == null) return;

        // only ever asked of something that belongs to somebody: a wild mob choosing a target is not this
        // mod's business, and the lookup is skipped entirely for the overwhelming majority of these events
        UUID owner = attackerSide(mob);
        if (owner == null) return;
        UUID targetSide = victimSide(target);
        if (targetSide == null) return;

        if (EasyFactionsBridge.sameSideNoFriendlyFire(server, owner, targetSide)) {
            event.setCanceled(true);
        }
    }

    /**
     * The player on whose behalf {@code entity} is attacking.
     *
     * Always follows ownership, whatever {@code protectFactionPets} says: that switch is about a pet as a
     * VICTIM, and refusing to trace an attacker would switch off the entire thing this class exists for,
     * which is a summon swinging at the summoner's own faction.
     */
    private static UUID attackerSide(Entity entity) {
        return resolveSide(entity, 0);
    }

    /**
     * The player whose side {@code entity} counts as being on when something hits it.
     *
     * A player is always themselves. Everything else is only their owner's when the server has said pets
     * and summons are covered, so a server that wants wolves to be fair game keeps them fair game while
     * their owners still cannot hit each other.
     */
    private static UUID victimSide(Entity entity) {
        if (entity == null) return null;
        if (entity instanceof Player player) return player.getUUID();
        if (!TerritoryConfig.protectFactionPets()) return null;
        return resolveSide(entity, 0);
    }

    /**
     * The UUID of whoever owns, tamed, summoned or fired {@code entity}, following the chain.
     *
     * A summon can be fired by a projectile fired by a summon, and a mod is free to answer "who owns this"
     * with another mob rather than with a player, so the chain is followed rather than read once. Bounded
     * so a mod that answers with the entity itself cannot spin.
     */
    private static UUID resolveSide(Entity entity, int depth) {
        if (entity == null || depth > MAX_OWNER_DEPTH) return null;
        if (entity instanceof Player player) return player.getUUID();
        if (entity instanceof OwnableEntity ownable) {
            UUID owner = ownable.getOwnerUUID();
            if (owner != null) return owner;
        }
        if (entity instanceof Projectile projectile) {
            Entity shooter = projectile.getOwner();
            if (shooter != null && shooter != entity) return resolveSide(shooter, depth + 1);
        }
        return reflectiveOwner(entity, depth);
    }

    /**
     * The owner of a summon belonging to a mod this one cannot name.
     *
     * The accessor is resolved once per entity CLASS and remembered, including the answer "this class has
     * none", so the cost on the damage path after the first hit of each mob type is one map lookup. Anything
     * that throws is treated as no owner: a summon mod whose API has moved must not be able to take the
     * server down through a damage event.
     */
    private static UUID reflectiveOwner(Entity entity, int depth) {
        Method method = OWNER_METHODS.computeIfAbsent(entity.getClass(), FriendlyFireHandler::findOwnerMethod);
        if (method == null || method == NONE) return null;
        try {
            Object result = method.invoke(entity);
            if (result instanceof UUID uuid) return uuid;
            // followed rather than read: the answer is an Entity, and for a summon of a summon that Entity
            // is another mob rather than the player everything in the chain belongs to
            if (result instanceof Entity other && other != entity) return resolveSide(other, depth + 1);
        } catch (Throwable ignored) {
            // a moved or throwing accessor means "no owner", never a crash on the damage path
        }
        return null;
    }

    /**
     * The accessor on this entity class that answers "who does this belong to", or {@link #NONE}.
     *
     * <b>An {@code Entity} return counts.</b> Iron's Spells declares {@code IMagicSummon.getSummoner()} as
     * returning {@code Entity}, not {@code LivingEntity}, and every summon in that mod inherits it: a check
     * that insisted on {@code LivingEntity} would reject the summoner accessor of exactly the mod this was
     * written for, and would do it silently. Verified against irons_spellbooks-1.21.1-3.16.2.
     */
    private static Method findOwnerMethod(Class<?> type) {
        for (String name : OWNER_ACCESSORS) {
            Method m = accessor(type, name);
            if (m != null) return m;
        }
        Method uuidAccessor = accessor(type, "getOwnerUUID");
        return uuidAccessor != null ? uuidAccessor : NONE;
    }

    private static Method accessor(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            if (m.getParameterCount() != 0) return null;
            Class<?> returns = m.getReturnType();
            if (Entity.class.isAssignableFrom(returns) || UUID.class.equals(returns)) {
                m.setAccessible(true);
                return m;
            }
        } catch (NoSuchMethodException | SecurityException ignored) {
            // no such accessor on this class
        }
        return null;
    }
}
