package top.leonx.territory.protection;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.EasyFactionsBridge;
import top.leonx.territory.integration.EasyFactionsBridge.Decision;
import top.leonx.territory.world.Interaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforcement for the two things Easy Factions cannot decide correctly on its own.
 *
 * <h2>1. Personal claims actually belonging to someone</h2>
 * Easy Factions' permission check for a CORE claim asks whether the chunk belongs to the claim's owner —
 * which is trivially true for any claimed chunk — and never compares it against the player standing there.
 * The result is that personal claims permit everyone to do everything: they draw a coloured square on the
 * map and protect nothing. This handler asks the question the other way round, so a personal claim keeps
 * strangers out the way a faction claim does.
 *
 * <h2>2. Admin territories having their own rules</h2>
 * Easy Factions applies one global restriction list to every admin claim on the server, so spawn and an
 * arena can never differ. A territory with its own switches answers for itself here.
 *
 * <h2>Why LOWEST priority with receiveCanceled</h2>
 * Easy Factions' own handlers run first and have already had their say. Running last, with cancelled events
 * still delivered, is what lets this both CANCEL what Easy Factions wrongly permitted (personal claims) and
 * UN-CANCEL what it wrongly forbade (an admin territory that allows something the global config does not).
 * Anything this mod has no opinion on returns {@link Decision#DEFER} and Easy Factions' verdict stands
 * untouched, so a server that never opens the permissions tab behaves exactly as it did before.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClaimProtectionHandler {

    private ClaimProtectionHandler() {}

    /** Last game tick each player was told off, so holding a mouse button cannot spam their action bar. */
    private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();
    private static final long WARNING_COOLDOWN_TICKS = 40L;

    // ---- block / item / entity interactions ----------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        apply(event.getPlayer(), event.getPos(), Interaction.BREAK_BLOCK, event::setCanceled, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        apply(player, event.getPos(), Interaction.PLACE_BLOCK, event::setCanceled, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // buckets are their own switch in Easy Factions; everything else is a plain block use
        Interaction type = isBucket(event.getItemStack()) ? Interaction.USE_BUCKET : Interaction.RIGHT_CLICK_BLOCK;
        apply(event.getEntity(), event.getPos(), type, event::setCanceled, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        apply(event.getEntity(), event.getPos(), Interaction.LEFT_CLICK_BLOCK, event::setCanceled, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        apply(event.getEntity(), event.getPos(), Interaction.RIGHT_CLICK_ITEM, event::setCanceled, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        apply(event.getEntity(), event.getPos(), Interaction.INTERACT_ENTITY, event::setCanceled, event.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityAttacked(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        // the chunk that matters is where the VICTIM is standing, matching Easy Factions
        apply(player, event.getEntity().blockPosition(), Interaction.PLAYER_ATTACK,
                event::setCanceled, event.isCanceled());
    }

    // ---- world mechanics with no player behind them --------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPistonMove(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()) return;
        Decision decision = EasyFactionsBridge.decideAmbient(level.getServer(), level.dimension(),
                new ChunkPos(event.getPos()), Interaction.PISTON_MOVE);
        if (decision == Decision.DENY) event.setCanceled(true);
        else if (decision == Decision.ALLOW) event.setCanceled(false);
    }

    // no receiveCanceled here: EntityMobGriefingEvent is not a cancellable event, and asking for cancelled
    // deliveries on one is a hard registration error in NeoForge
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity() == null) return;
        MinecraftServer server = event.getEntity().getServer();
        if (server == null) return;
        Decision decision = EasyFactionsBridge.decideAmbient(server, event.getEntity().level().dimension(),
                event.getEntity().chunkPosition(), Interaction.MOB_GRIEFING_DAMAGE);
        if (decision == Decision.DENY) event.setCanGrief(false);
        else if (decision == Decision.ALLOW) event.setCanGrief(true);
    }

    /**
     * Explosions are the one case that cannot be decided after the fact: Easy Factions REMOVES the blocks it
     * wants to protect from the affected list, and a removed block cannot be put back from information the
     * event still carries. So the list is photographed before Easy Factions touches it, and any block inside
     * an admin territory that permits explosions is restored afterwards.
     */
    private static List<BlockPos> explosionSnapshot;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionBefore(ExplosionEvent.Detonate event) {
        explosionSnapshot = event.getLevel().isClientSide() ? null : new ArrayList<>(event.getAffectedBlocks());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosionAfter(ExplosionEvent.Detonate event) {
        List<BlockPos> before = explosionSnapshot;
        explosionSnapshot = null;
        if (before == null || event.getLevel().isClientSide()) return;
        MinecraftServer server = event.getLevel().getServer();
        if (server == null) return;
        ResourceKey<Level> dim = event.getLevel().dimension();

        List<BlockPos> affected = event.getAffectedBlocks();
        // a decision per CHUNK, not per block: an explosion touches hundreds of blocks in a handful of chunks
        Map<Long, Decision> byChunk = new HashMap<>();
        affected.removeIf(pos -> ruling(server, dim, pos, byChunk) == Decision.DENY);
        for (BlockPos pos : before) {
            if (ruling(server, dim, pos, byChunk) == Decision.ALLOW && !affected.contains(pos)) affected.add(pos);
        }
    }

    private static Decision ruling(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos,
                                   Map<Long, Decision> cache) {
        ChunkPos chunk = new ChunkPos(pos);
        return cache.computeIfAbsent(chunk.toLong(), k ->
                EasyFactionsBridge.decideAmbient(server, dim, chunk, Interaction.EXPLOSION_DAMAGE));
    }

    // ---- shared plumbing ------------------------------------------------------------------------------

    private interface Canceller {
        void set(boolean canceled);
    }

    private static void apply(Player player, BlockPos pos, Interaction interaction, Canceller canceller,
                              boolean currentlyCanceled) {
        if (player == null || pos == null || player.level().isClientSide()) return;
        Decision decision = EasyFactionsBridge.decide(player, player.level().dimension(), new ChunkPos(pos), interaction);
        if (decision == Decision.DENY) {
            if (!currentlyCanceled) warn(player, pos);
            canceller.set(true);
        } else if (decision == Decision.ALLOW && currentlyCanceled) {
            canceller.set(false);
        }
    }

    /** Tell the player WHY nothing happened, on the action bar, at most once every two seconds. */
    private static void warn(Player player, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        long now = player.level().getGameTime();
        Long last = LAST_WARNING.get(player.getUUID());
        if (last != null && now - last < WARNING_COOLDOWN_TICKS) return;
        LAST_WARNING.put(player.getUUID(), now);

        String owner = EasyFactionsBridge.chunkOwnerDisplay(server, player.level().dimension(), new ChunkPos(pos));
        Component msg = owner.isEmpty()
                ? Component.translatable("message.territory.protected")
                : Component.translatable("message.territory.protected_by", owner);
        sp.displayClientMessage(msg.copy().withStyle(ChatFormatting.RED), true);
    }

    private static boolean isBucket(ItemStack stack) {
        return stack.getItem() instanceof BucketItem || stack.getItem() instanceof MobBucketItem;
    }
}
