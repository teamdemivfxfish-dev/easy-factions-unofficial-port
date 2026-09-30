package top.leonx.territory.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.ClaimEconomy;
import top.leonx.territory.integration.EasyFactionsBridge;
import top.leonx.territory.integration.FactionAdmin;
import top.leonx.territory.integration.MineColoniesBridge;
import top.leonx.territory.integration.Refunds;
import top.leonx.territory.world.ClaimRefunds;

import java.util.List;

/**
 * The operator side of claims and factions.
 *
 * <h2>{@code /territory diagnose}</h2>
 * Why can, or can't, this player build where they are standing. Claim protection fails silently in several
 * ways that all look the same from in game and none of which writes anything to the log: a permission rank
 * quietly granting level 2, Easy Factions' restriction list being empty in the save's own copy of its config,
 * a claim sitting in a dimension the config never allowed. Rather than have a server owner guess, this prints
 * the inputs and the verdict side by side.
 *
 * <h2>{@code /territory colony-overlaps [release]}</h2>
 * Which claims are standing on a colony their owner has no part in, and the sweep that hands that land back.
 * Prevention only protects the towns nobody has taken yet; a server that ran without the rule already has
 * claims sitting on colonies, and the people they were taken from cannot release them, because releasing a
 * claim belongs to the claim owner and the claim owner is whoever took the land.
 *
 * <h2>{@code /territory faction ...}</h2>
 * Membership overrides an operator otherwise has no way to perform, because every route Easy Factions offers
 * is written from inside the faction. See {@link FactionAdmin} for what each one really does.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TerritoryCommand {

    private TerritoryCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("territory")
                .then(Commands.literal("diagnose")
                        .requires(src -> src.hasPermission(2))
                        .executes(TerritoryCommand::diagnose))
                .then(Commands.literal("colony-overlaps")
                        .requires(src -> src.hasPermission(2))
                        .executes(TerritoryCommand::listColonyOverlaps)
                        .then(Commands.literal("release")
                                .executes(TerritoryCommand::releaseColonyOverlaps)))
                .then(Commands.literal("refunds")
                        .executes(TerritoryCommand::showRefunds)
                        .then(Commands.literal("claim")
                                .executes(TerritoryCommand::claimRefunds)))
                .then(Commands.literal("faction")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("list")
                                .executes(TerritoryCommand::list))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("faction", StringArgumentType.string())
                                                .suggests(TerritoryCommand::suggestFactions)
                                                .executes(TerritoryCommand::add))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(TerritoryCommand::remove)))
                        .then(Commands.literal("disband")
                                .then(Commands.argument("faction", StringArgumentType.string())
                                        .suggests(TerritoryCommand::suggestFactions)
                                        .executes(TerritoryCommand::disband))));
        event.getDispatcher().register(root);
    }

    // ---- claim diagnosis ------------------------------------------------------------------------------

    private static int diagnose(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<String> lines = EasyFactionsBridge.diagnose(player);
        ctx.getSource().sendSuccess(() -> Component
                .literal("Territory claim diagnosis")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (String line : lines) {
            ChatFormatting colour = line.startsWith("VERDICT") ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }
        return lines.size();
    }

    // ---- colony overlaps ------------------------------------------------------------------------------

    /**
     * {@code /territory colony-overlaps} lists every claim standing on a colony its owner has no part in;
     * adding {@code release} takes them off the map.
     *
     * Listing is the bare command on purpose. This releases other people's land, the list is the only
     * evidence an operator has that it is releasing the right land, and a sweep that acted the moment it was
     * typed would be one keystroke away from unclaiming half a server. Read first, then release.
     */
    private static int listColonyOverlaps(CommandContext<CommandSourceStack> ctx) {
        if (!reportPreconditions(ctx)) return 0;
        List<EasyFactionsBridge.ColonyOverlap> found =
                EasyFactionsBridge.colonyOverlaps(ctx.getSource().getServer());
        if (found.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component
                    .literal("No claims are sitting on a colony they do not belong to.")
                    .withStyle(ChatFormatting.GREEN), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component
                .literal(found.size() + " claimed chunks stand on a colony their owner has no part in")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (String line : summarise(found)) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.GRAY), false);
        }
        ctx.getSource().sendSuccess(() -> Component
                .literal("Run /territory colony-overlaps release to give this land back.")
                .withStyle(ChatFormatting.YELLOW), false);
        return found.size();
    }

    private static int releaseColonyOverlaps(CommandContext<CommandSourceStack> ctx) {
        if (!reportPreconditions(ctx)) return 0;
        // the same sweep the server runs on its own, so a hand-run release and an automatic one cannot
        // behave differently: released, the claim slots handed back, and announced to whoever lost the land
        List<EasyFactionsBridge.OwnerLoss> losses =
                EasyFactionsBridge.sweepColonyOverlaps(ctx.getSource().getServer());
        if (losses.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component
                    .literal("Nothing to release.").withStyle(ChatFormatting.GREEN), false);
            return 0;
        }
        int released = 0;
        for (EasyFactionsBridge.OwnerLoss loss : losses) released += loss.chunks();
        final int chunks = released;
        // broadcast: this changes land other players hold, and it should not happen quietly
        ctx.getSource().sendSuccess(() -> Component
                .literal("Released " + chunks + " claimed chunks back to the colonies standing on them."
                        + " Their owners have the claim slots back.")
                .withStyle(ChatFormatting.GREEN), true);
        return released;
    }

    // ---- refunds --------------------------------------------------------------------------------------

    /**
     * {@code /territory refunds} says what is waiting, {@code /territory refunds claim} hands it over.
     *
     * Open to every player rather than to operators: this is their own money, held because the land was
     * taken while they were offline. A faction's pool is collectable by its owner alone, since the land it
     * paid for belonged to the faction and nobody else could have bought it.
     */
    private static int showRefunds(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long total = 0L;
        for (String key : EasyFactionsBridge.collectableKeys(player)) {
            long owed = Refunds.owed(player.getServer(), key);
            if (owed <= 0L) continue;
            // Quote what can actually be handed over. Emeralds do not divide, so a pool worth less than one
            // is real money that cannot be paid yet, and printing it as "1 emerald" would promise a payout
            // that /territory refunds claim then refuses to make.
            long payable = ClaimEconomy.usingSdm() ? owed
                    : (owed / TerritoryConfig.sdmPerEmerald()) * TerritoryConfig.sdmPerEmerald();
            total += payable;
            String label = ClaimRefunds.isFactionKey(key) ? ClaimRefunds.nameOf(key) + " (faction)" : "you";
            Component line = payable > 0L
                    ? Component.literal("Owed to " + label + ": " + ClaimEconomy.describe(player, payable))
                    : Component.literal("Owed to " + label + ": less than one emerald, held until it adds up");
            ctx.getSource().sendSuccess(() -> line.copy().withStyle(ChatFormatting.GOLD), false);
        }
        if (total <= 0L) {
            ctx.getSource().sendSuccess(() -> Component
                    .literal("Nothing is waiting for you.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component
                .literal("Run /territory refunds claim to collect.").withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int claimRefunds(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long paid = 0L;
        for (String key : EasyFactionsBridge.collectableKeys(player)) paid += Refunds.payOut(player, key);
        final long total = paid;
        if (total <= 0L) {
            ctx.getSource().sendSuccess(() -> Component
                    .literal("Nothing to collect.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component
                .literal("Collected " + ClaimEconomy.describe(player, total) + ".")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Say why the sweep can see nothing, rather than reporting a clean server that was never scanned. */
    private static boolean reportPreconditions(CommandContext<CommandSourceStack> ctx) {
        if (!EasyFactionsBridge.loaded()) {
            ctx.getSource().sendFailure(Component.literal("Easy Factions is not installed.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!MineColoniesBridge.loaded()) {
            ctx.getSource().sendFailure(Component
                    .literal("MineColonies is not installed, so there are no colonies to compare claims against.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (MineColoniesBridge.brokenApi()) {
            ctx.getSource().sendFailure(Component
                    .literal("MineColonies' claim API failed earlier this run, so colony land cannot be read. "
                            + "Check the server log and restart before trusting this command.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    /**
     * One line per claim owner and colony rather than one per chunk.
     *
     * A faction that claimed over a town holds dozens of its chunks, and printing every one of them pushes
     * the total off the top of the chat window, which is the number the operator is deciding on.
     */
    private static List<String> summarise(List<EasyFactionsBridge.ColonyOverlap> found) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> examples = new java.util.HashMap<>();
        for (EasyFactionsBridge.ColonyOverlap o : found) {
            String town = o.colonyName() == null || o.colonyName().isBlank()
                    ? "colony #" + o.colonyId() : o.colonyName();
            String key = o.ownerDisplay() + " (" + o.type() + ") on " + town;
            counts.merge(key, 1, Integer::sum);
            examples.putIfAbsent(key, o.chunkX() + ", " + o.chunkZ() + " in " + o.dim().location());
        }
        List<String> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            out.add(e.getKey() + " - " + e.getValue() + " chunks, e.g. " + examples.get(e.getKey()));
        }
        return out;
    }

    // ---- faction membership overrides -----------------------------------------------------------------

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestFactions(CommandContext<CommandSourceStack> ctx,
                            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(FactionAdmin.factionNames(ctx.getSource().getServer()), builder);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = FactionAdmin.roster(ctx.getSource().getServer());
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("There are no factions on this server.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(lines.size() + " factions")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (String line : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return lines.size();
    }

    private static int add(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String faction = StringArgumentType.getString(ctx, "faction");
        return report(ctx, FactionAdmin.forceAdd(ctx.getSource().getServer(), target, faction));
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        return report(ctx, FactionAdmin.forceRemove(ctx.getSource().getServer(), target));
    }

    private static int disband(CommandContext<CommandSourceStack> ctx) {
        String faction = StringArgumentType.getString(ctx, "faction");
        return report(ctx, FactionAdmin.forceDisband(ctx.getSource().getServer(), faction));
    }

    /**
     * Send the outcome and return a success count Brigadier can use.
     *
     * A refusal goes out through {@code sendFailure}, not as green success text: an operator running this
     * from a console or a command block needs "nothing happened" to be visibly different from "done", and a
     * failed override that reads like a completed one is how someone ends up thinking a player was moved.
     */
    private static int report(CommandContext<CommandSourceStack> ctx, FactionAdmin.Result result) {
        if (result.ok()) {
            ctx.getSource().sendSuccess(() -> Component.literal(result.message())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(result.message()).withStyle(ChatFormatting.RED));
        return 0;
    }
}
