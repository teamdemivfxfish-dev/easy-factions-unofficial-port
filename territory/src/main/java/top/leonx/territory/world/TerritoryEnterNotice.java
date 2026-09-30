package top.leonx.territory.world;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.EasyFactionsBridge;
import top.leonx.territory.integration.MineColoniesBridge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Telling a player whose land they have just walked into.
 *
 * A claim is invisible from the ground. The first thing a border does is refuse something, so without this
 * the way you discover you are standing in a rival faction's territory is by trying to place a block and
 * being told off for it, and the way you discover you have left your own is by being killed in what you
 * thought was your back garden. One line as the border is crossed turns both of those into information.
 *
 * <h2>What it costs</h2>
 * Checked every half second per player, and only the chunk the player is standing in is looked up, from a
 * map already in memory. The last thing each player was told is remembered, so a player pacing along a
 * border is told once and not once per step, and walking between two chunks of the same territory says
 * nothing at all.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TerritoryEnterNotice {

    private TerritoryEnterNotice() {}

    private static final int CHECK_INTERVAL_TICKS = 10;

    /** The last chunk each player was checked in, so an unmoved player costs one comparison. */
    private static final Map<UUID, Long> LAST_CHUNK = new HashMap<>();
    /** The last territory each player was told about, so only real crossings are announced. */
    private static final Map<UUID, String> LAST_TERRITORY = new HashMap<>();

    private static int countdown = CHECK_INTERVAL_TICKS;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!TerritoryConfig.announceTerritoryEntry() || !EasyFactionsBridge.loaded()) return;
        if (--countdown > 0) return;
        countdown = CHECK_INTERVAL_TICKS;

        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            check(server, player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_CHUNK.remove(event.getEntity().getUUID());
        LAST_TERRITORY.remove(event.getEntity().getUUID());
    }

    private static void check(MinecraftServer server, ServerPlayer player) {
        // the dimension is part of the position: the same chunk coordinates in the Nether are a different
        // place, and a portal that lands you on the same coordinates must not be treated as not having moved
        long chunk = player.chunkPosition().toLong() ^ player.level().dimension().location().hashCode();
        Long previous = LAST_CHUNK.get(player.getUUID());
        if (previous != null && previous == chunk) return;
        LAST_CHUNK.put(player.getUUID(), chunk);

        String territory = describe(server, player);
        String last = LAST_TERRITORY.put(player.getUUID(), territory);
        if (last == null || last.equals(territory)) return;   // first sighting, or the same land as before

        Component msg = territory.isEmpty()
                ? Component.translatable("message.territory.entered_wilderness").withStyle(ChatFormatting.GRAY)
                : Component.translatable("message.territory.entered", territory).withStyle(ChatFormatting.GOLD);
        player.displayClientMessage(msg, TerritoryConfig.entryOnActionBar());
    }

    /**
     * What to call the ground a player is standing on: the claim owner, the colony, or both.
     *
     * A colony inside a claim is named as well as the claim, and named second, because the colony is the
     * thing that decides what happens there. Empty means nobody owns it, which is its own announcement.
     */
    private static String describe(MinecraftServer server, ServerPlayer player) {
        ChunkPos pos = player.chunkPosition();
        String claim = EasyFactionsBridge.chunkOwnerDisplay(server, player.level().dimension(), pos);
        MineColoniesBridge.ColonyRef colony = MineColoniesBridge.colonyAt(server, player.level().dimension(), pos);
        if (colony == null) return claim;
        if (claim.isEmpty()) return colony.display();
        return claim + " (" + colony.display() + ")";
    }
}
