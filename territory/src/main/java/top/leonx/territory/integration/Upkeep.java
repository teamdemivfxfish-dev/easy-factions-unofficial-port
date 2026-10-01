package top.leonx.territory.integration;

import dev.xmat5.holdfast.server.claims.ClaimManager;
import dev.xmat5.holdfast.server.faction.Faction;
import dev.xmat5.holdfast.server.faction.FactionStateManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.blocks.TerritoryTableBlockEntity;
import top.leonx.territory.world.FactionCores;
import top.leonx.territory.world.FactionSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Upkeep {

    public static final int MAX_STORED = 4096;

    private Upkeep() {}

    public record Status(int held, int due, int minutesToNext, boolean hasCore, String unit,
                         int intervalMinutes, int graceMinutesLeft, long corePos) {
        public static final Status NONE = new Status(0, 0, 0, false, "", 0, -1, 0L);
    }

    private record Target(TerritoryTableBlockEntity table, String prefix, String refusal, String faction) {}

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static int valueOf(ItemStack stack) {
        return stack.isEmpty() ? 0 : TerritoryConfig.upkeepValues().getOrDefault(itemId(stack), 0);
    }

    private static String itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null ? id : new ItemStack(item).getHoverName().getString();
    }

    static long minutesFor(long value, int chunks) {
        long cost = TerritoryConfig.upkeepCostPerChunk();
        return cost <= 0L ? 0L : value * intervalMinutes() / (Math.max(1, chunks) * cost);
    }

    public static String formatMinutes(long minutes) {
        if (minutes <= 0L) return "under 1m";
        if (minutes >= 1440L) return (minutes / 1440L) + "d " + ((minutes % 1440L) / 60L) + "h";
        if (minutes >= 60L) return (minutes / 60L) + "h " + (minutes % 60L) + "m";
        return minutes + "m";
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        ResourceLocation rl = ResourceLocation.tryParse(dimension);
        return rl == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    private static TerritoryTableBlockEntity tableAt(MinecraftServer server, FactionCores.Core core) {
        ServerLevel level = levelOf(server, core.dimension());
        if (level == null) return null;
        return level.getBlockEntity(BlockPos.of(core.pos())) instanceof TerritoryTableBlockEntity table ? table : null;
    }

    public static FactionCores.Core validCore(MinecraftServer server, String faction) {
        FactionCores cores = FactionCores.get(server);
        FactionCores.Core core = cores.getCore(faction);
        if (core == null) return null;
        if (tableAt(server, core) != null) return core;
        cores.clearCore(faction);
        return null;
    }

    private static int heldValue(TerritoryTableBlockEntity table) {
        if (table == null) return 0;
        Map<String, Integer> values = TerritoryConfig.upkeepValues();
        long sum = table.getCredit();
        for (Map.Entry<String, Integer> e : table.getDeposits().entrySet()) {
            sum += (long) e.getValue() * values.getOrDefault(e.getKey(), 0);
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    private static int dueValue(int chunks) {
        return (int) Math.min(Integer.MAX_VALUE, (long) chunks * TerritoryConfig.upkeepCostPerChunk());
    }

    private static long intervalMinutes() {
        return TerritoryConfig.upkeepIntervalTicks() / 1200L;
    }

    public static Status status(MinecraftServer server, String faction) {
        int chunks = ClaimManager.get(server).getFactionClaimCount(faction);
        FactionCores.Core core = validCore(server, faction);
        int held = core == null ? 0 : heldValue(tableAt(server, core));
        FactionCores cores = FactionCores.get(server);
        long now = server.overworld().getGameTime();
        long nextDue = cores.getNextDue(faction);
        long ticks = nextDue == 0L ? TerritoryConfig.upkeepIntervalTicks() : Math.max(0L, nextDue - now);
        long graceUntil = cores.getGraceUntil(faction);
        int graceLeft = graceUntil == 0L ? -1 : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, graceUntil - now) / 1200L);
        return new Status(held, dueValue(chunks), (int) Math.min(Integer.MAX_VALUE, ticks / 1200L), core != null,
                TerritoryConfig.upkeepUnit(), (int) Math.min(Integer.MAX_VALUE, intervalMinutes()), graceLeft,
                core == null ? 0L : core.pos());
    }

    public static String placementRefusal(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !FactionsBridge.loaded()) return null;
        String faction = FactionsBridge.factionNameOf(server, player.getUUID());
        if (faction.isEmpty()) return null;
        FactionCores.Core core = validCore(server, faction);
        if (core == null) return null;
        BlockPos pos = BlockPos.of(core.pos());
        return faction + " already has its core at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + ". A faction only gets one Territory Table.";
    }

    public static void registerCore(ServerPlayer player, Level level, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null || !FactionsBridge.loaded()) return;
        String faction = FactionsBridge.factionNameOf(server, player.getUUID());
        if (faction.isEmpty() || validCore(server, faction) != null) return;
        FactionCores.get(server).setCore(faction, level.dimension().location().toString(), pos.asLong());
        player.sendSystemMessage(Component.literal("This table is now the core of " + faction
                        + ". Open it and use the Vault tab, or right-click it holding upkeep items, to pay land upkeep.")
                .withStyle(ChatFormatting.GOLD));
    }

    public static void coreBlownUp(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        if (server == null || !FactionsBridge.loaded()) return;
        FactionCores cores = FactionCores.get(server);
        String faction = cores.factionAt(dimension.location().toString(), pos.asLong());
        if (faction == null) return;
        int released = FactionsBridge.releaseAllFactionClaims(server, faction);
        cores.clearDue(faction);
        FactionsBridge.notifyFaction(server, faction, Component.literal(faction + "'s core was blown up. "
                + (released == 1 ? "Its 1 claimed chunk was released." : "All " + released + " claimed chunks were released.")
                + " Place a new Territory Table to claim land again.").withStyle(ChatFormatting.RED));
    }

    public static void forgetCore(Level level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        FactionCores.get(server).clearCoreAt(level.dimension().location().toString(), pos.asLong());
    }

    private static Target target(ServerPlayer player, Level level, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null || !FactionsBridge.loaded()) return null;
        String faction = FactionsBridge.factionNameOf(server, player.getUUID());
        if (faction.isEmpty()) return null;
        if (!(level.getBlockEntity(pos) instanceof TerritoryTableBlockEntity table)) return null;

        String dimension = level.dimension().location().toString();
        FactionCores cores = FactionCores.get(server);
        FactionCores.Core core = validCore(server, faction);
        String prefix = "";
        if (core == null) {
            cores.setCore(faction, dimension, pos.asLong());
            prefix = "This table is now the core of " + faction + ". ";
        } else if (!core.dimension().equals(dimension) || core.pos() != pos.asLong()) {
            BlockPos at = BlockPos.of(core.pos());
            return new Target(null, "", "Your faction's core is at " + at.getX() + ", " + at.getY() + ", " + at.getZ() + ".",
                    faction);
        }
        return new Target(table, prefix, null, faction);
    }

    private static int moveIn(TerritoryTableBlockEntity table, ItemStack stack, boolean consume) {
        if (valueOf(stack) <= 0) return 0;
        String id = itemId(stack);
        int room = MAX_STORED - table.getDepositCount(id);
        if (room <= 0) return 0;
        int moved = Math.min(room, stack.getCount());
        table.addDeposit(id, moved);
        if (consume) stack.shrink(moved);
        return moved;
    }

    private static String afterDeposit(MinecraftServer server, String faction, TerritoryTableBlockEntity table,
                                       long addedValue) {
        int chunks = ClaimManager.get(server).getFactionClaimCount(faction);
        int due = dueValue(chunks);
        int held = heldValue(table);
        String basis = chunks == 0 ? " for 1 chunk" : "";
        StringBuilder msg = new StringBuilder("Deposited " + formatMinutes(minutesFor(addedValue, chunks)) + " of upkeep" + basis + ".");
        FactionCores cores = FactionCores.get(server);
        if (due > 0 && cores.getGraceUntil(faction) != 0L && held >= due) {
            payInFull(server, faction, chunks, table, server.overworld().getGameTime());
            msg.append(" The overdue upkeep is paid.");
            held = heldValue(table);
        }
        msg.append(" The core now covers ").append(formatMinutes(minutesFor(held, chunks))).append(basis).append(".");
        return msg.toString();
    }

    public static String deposit(ServerPlayer player, Level level, BlockPos pos, ItemStack stack) {
        int value = valueOf(stack);
        if (value <= 0) return null;
        Target target = target(player, level, pos);
        if (target == null) return null;
        if (target.refusal() != null) return target.refusal();
        String denied = depositRefusal(player, target.faction());
        if (denied != null) return denied;
        int moved = moveIn(target.table(), stack, !player.isCreative());
        if (moved <= 0) return target.prefix() + "The core cannot hold any more " + itemName(itemId(stack)) + ".";
        record(player, target.faction(), (long) moved * value);
        return target.prefix() + afterDeposit(player.getServer(), target.faction(), target.table(), (long) moved * value);
    }

    static String depositRefusal(ServerPlayer player, String faction) {
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        int rule = FactionSettings.get(server).deposit(faction);
        if (rule == FactionSettings.DEPOSIT_MEMBERS) return null;
        FactionStateManager fsm = FactionStateManager.get(server);
        UUID id = player.getUUID();
        if (rule == FactionSettings.DEPOSIT_OFFICERS) {
            return fsm.playerIsOwnerOrOfficer(id) ? null : "Only officers and the owner can add to the faction bank.";
        }
        Faction f = fsm.getFactionByPlayer(id);
        return f != null && id.equals(f.getOwner()) ? null : "Only the owner can add to the faction bank.";
    }

    private static void record(ServerPlayer player, String faction, long value) {
        MinecraftServer server = player.getServer();
        if (server == null || player.isCreative() || value <= 0L) return;
        FactionSettings.get(server).addContribution(faction, player.getUUID(), value);
    }

    public static String depositAll(ServerPlayer player, Level level, BlockPos pos, Container input) {
        Target target = target(player, level, pos);
        if (target == null) return "Join a faction to fund its land.";
        if (target.refusal() != null) return target.refusal();
        String denied = depositRefusal(player, target.faction());
        if (denied != null) return denied;
        long added = 0L;
        boolean hadCoins = false;
        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack stack = input.getItem(i);
            int value = valueOf(stack);
            if (value <= 0) continue;
            hadCoins = true;
            int moved = moveIn(target.table(), stack, true);
            added += (long) moved * value;
            if (stack.isEmpty()) input.setItem(i, ItemStack.EMPTY);
        }
        input.setChanged();
        if (added <= 0L) {
            return target.prefix() + (hadCoins ? "The core cannot hold any more of those items." : "Put items in the slots first.");
        }
        record(player, target.faction(), added);
        return target.prefix() + afterDeposit(player.getServer(), target.faction(), target.table(), added);
    }

    static long runwayMinutes(Status s) {
        if (s.due() <= 0) return Long.MAX_VALUE;
        return s.minutesToNext() + (long) (s.held() / s.due()) * s.intervalMinutes();
    }

    static String lowMessage(MinecraftServer server, String faction) {
        long warn = TerritoryConfig.upkeepWarnMinutes();
        if (warn <= 0L || TerritoryConfig.upkeepCostPerChunk() <= 0) return null;
        Status s = status(server, faction);
        if (s.graceMinutesLeft() >= 0) {
            return "Upkeep is overdue. " + faction + "'s land is safe for " + formatMinutes(s.graceMinutesLeft())
                    + ". Deposit coins at the core table to pay it.";
        }
        if (s.due() <= 0) return null;
        if (!s.hasCore()) {
            return faction + " has no core table, so its upkeep cannot be paid. Place a Territory Table and deposit coins.";
        }
        long runway = runwayMinutes(s);
        if (runway >= warn) return null;
        if (s.held() < s.due()) {
            return faction + "'s core cannot cover the next upkeep payment, due in " + formatMinutes(s.minutesToNext())
                    + ". Deposit coins at the core table to keep the land.";
        }
        return faction + "'s core only covers " + formatMinutes(runway) + " more of upkeep. Deposit coins at the core table.";
    }

    record Settlement(Map<String, Integer> deposits, long credit) {}

    static Settlement plan(Map<String, Integer> deposits, long credit, long paid, Map<String, Integer> values) {
        long fromCredit = Math.min(credit, paid);
        long needed = paid - fromCredit;
        Map<String, Integer> next = new LinkedHashMap<>(deposits);
        long consumed = 0L;
        if (needed > 0L) {
            List<String> cheapestFirst = new ArrayList<>();
            for (String id : next.keySet()) {
                if (values.containsKey(id)) cheapestFirst.add(id);
            }
            cheapestFirst.sort(Comparator.comparingInt(values::get));
            for (String id : cheapestFirst) {
                if (consumed >= needed) break;
                long value = values.get(id);
                int have = next.get(id);
                int take = (int) Math.min((long) have, (needed - consumed + value - 1L) / value);
                if (take <= 0) continue;
                consumed += take * value;
                if (take >= have) next.remove(id);
                else next.put(id, have - take);
            }
        }
        long overshoot = Math.max(0L, consumed - needed);
        return new Settlement(next, credit - fromCredit + overshoot);
    }

    private static void settle(TerritoryTableBlockEntity table, int paid) {
        Settlement result = plan(table.getDeposits(), table.getCredit(), paid, TerritoryConfig.upkeepValues());
        table.setDeposits(result.deposits());
        table.setCredit(result.credit());
    }

    private static void payInFull(MinecraftServer server, String faction, int chunks, TerritoryTableBlockEntity table,
                                  long now) {
        int due = dueValue(chunks);
        settle(table, due);
        FactionCores cores = FactionCores.get(server);
        cores.setNextDue(faction, now + TerritoryConfig.upkeepIntervalTicks());
        cores.setGraceUntil(faction, 0L);

        int left = heldValue(table);
        Component text = Component.literal("Upkeep paid for " + chunks + (chunks == 1 ? " chunk." : " chunks.")
                + " The core now covers " + formatMinutes(minutesFor(left, chunks)) + ".");
        if (left < due) {
            text = text.copy().append(Component.literal(" That is less than one full payment, so deposit more.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        FactionsBridge.notifyFaction(server, faction, text.copy().withStyle(ChatFormatting.GOLD));
    }

    @EventBusSubscriber(modid = "holdfast_factions", bus = EventBusSubscriber.Bus.GAME)
    public static final class Clock {

        private static final Map<String, Long> LAST_WARN = new java.util.concurrent.ConcurrentHashMap<>();

        private Clock() {}

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            MinecraftServer server = event.getServer();
            if (server.getTickCount() % 200 != 0 || !FactionsBridge.loaded()) return;
            if (TerritoryConfig.upkeepCostPerChunk() <= 0) return;

            ClaimManager claims = ClaimManager.get(server);
            FactionCores cores = FactionCores.get(server);
            long now = server.overworld().getGameTime();
            for (String faction : FactionAdmin.factionNames(server)) {
                int chunks = claims.getFactionClaimCount(faction);
                if (chunks == 0) {
                    cores.clearDue(faction);
                    continue;
                }
                long due = cores.getNextDue(faction);
                if (due == 0L) {
                    cores.setNextDue(faction, now + TerritoryConfig.upkeepIntervalTicks());
                } else if (now >= due) {
                    long graceUntil = cores.getGraceUntil(faction);
                    if (graceUntil != 0L && now < graceUntil) continue;
                    collect(server, faction, chunks, now);
                } else {
                    warnIfLow(server, faction, now);
                }
            }
        }

        private static void warnIfLow(MinecraftServer server, String faction, long now) {
            Long last = LAST_WARN.get(faction);
            if (last != null && now >= last && now - last < TerritoryConfig.upkeepWarnRepeatTicks()) return;
            String text = lowMessage(server, faction);
            if (text == null) return;
            LAST_WARN.put(faction, now);
            FactionsBridge.notifyFaction(server, faction, Component.literal(text).withStyle(ChatFormatting.GOLD));
        }

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !FactionsBridge.loaded()) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            String faction = FactionsBridge.factionNameOf(server, player.getUUID());
            if (faction.isEmpty() || ClaimManager.get(server).getFactionClaimCount(faction) == 0) return;
            String text = lowMessage(server, faction);
            if (text != null) player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
        }

        private static void collect(MinecraftServer server, String faction, int chunks, long now) {
            int cost = TerritoryConfig.upkeepCostPerChunk();
            int due = dueValue(chunks);
            FactionCores.Core core = validCore(server, faction);
            TerritoryTableBlockEntity table = core == null ? null : tableAt(server, core);
            int held = heldValue(table);
            if (held >= due) {
                payInFull(server, faction, chunks, table, now);
                return;
            }

            FactionCores cores = FactionCores.get(server);
            long graceTicks = TerritoryConfig.upkeepGraceTicks();
            if (graceTicks > 0L && cores.getGraceUntil(faction) == 0L) {
                cores.setGraceUntil(faction, now + graceTicks);
                FactionsBridge.notifyFaction(server, faction, Component.literal(
                        "Upkeep is due and the core of " + faction + " cannot pay it. It needs items worth "
                                + formatMinutes(minutesFor(due - held, chunks)) + " more. The land is safe for "
                                + formatMinutes(graceTicks / 1200L) + ". Deposit items at the core to pay.")
                        .withStyle(ChatFormatting.RED));
                return;
            }

            int affordable = (int) Math.min(chunks, held / cost);
            int paid = affordable * cost;
            int lost = chunks - affordable;
            if (paid > 0) settle(table, paid);
            int released = lost > 0 ? FactionsBridge.releaseOutermost(server, faction, core, lost) : 0;
            cores.setNextDue(faction, now + TerritoryConfig.upkeepIntervalTicks());
            cores.setGraceUntil(faction, 0L);
            FactionsBridge.notifyFaction(server, faction, Component.literal(
                    "Upkeep short: " + faction + " could not pay for all its land and lost its outermost "
                            + released + (released == 1 ? " chunk." : " chunks."))
                    .withStyle(ChatFormatting.RED));
        }
    }
}
