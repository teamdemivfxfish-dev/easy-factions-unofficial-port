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
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.EasyFactionsBridge;
import top.leonx.territory.integration.FactionAdmin;

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
