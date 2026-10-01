package dev.xmat5.holdfast.server.claims;

import dev.xmat5.holdfast.Utils;
import dev.xmat5.holdfast.server.claims.model.ClaimData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class ClaimCommands {
   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      dispatcher.register(
         Commands.literal("claim")
            .then(
               Commands.literal("whereAmI")
                  .executes(
                     ctx -> {
                        try {
                           MinecraftServer server = ((CommandSourceStack)ctx.getSource()).getServer();
                           ServerPlayer player = ((CommandSourceStack)ctx.getSource()).getPlayerOrException();
                           ClaimData result = ClaimManager.get(server).getClaim(player.level().dimension(), player.chunkPosition());
                           if (result != null) {
                              switch (result.type) {
                                 case FACTION:
                                    ((CommandSourceStack)ctx.getSource())
                                       .sendSuccess(() -> Component.literal("This chunk is owned by faction " + result.owner), false);
                                    break;
                                 case CORE:
                                    ((CommandSourceStack)ctx.getSource())
                                       .sendSuccess(
                                          () -> Component.literal(
                                                "This chunk is owned by player " + Utils.getPlayerNameOffline(UUID.fromString(result.owner), server)
                                             ),
                                          false
                                       );
                                    break;
                                 case ADMIN:
                                    ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("This chunk was claimed by an admin."), false);
                              }

                              return 1;
                           } else {
                              ((CommandSourceStack)ctx.getSource()).sendSuccess(() -> Component.literal("This chunk is not claimed."), false);
                              return 1;
                           }
                        } catch (RuntimeException var4) {
                           ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(var4.getMessage()));
                           return 0;
                        }
                     }
                  )
            )
            .then(
               Commands.literal("resetUnclaimedChunks")
                  .requires(source -> source.hasPermission(2))
                  .then(Commands.argument("resetChunks", BoolArgumentType.bool()).executes(ctx -> {
                     try {
                        boolean resetChunks = BoolArgumentType.getBool(ctx, "resetChunks");
                        if (resetChunks) {
                           ChunkCleaner.scheduleWipe(((CommandSourceStack)ctx.getSource()).getServer());
                           ((CommandSourceStack)ctx.getSource())
                              .sendSuccess(() -> Component.literal("Unclaimed chunks will be reset the next restart."), false);
                        } else {
                           ChunkCleaner.stopWipe();
                           ((CommandSourceStack)ctx.getSource())
                              .sendSuccess(() -> Component.literal("Unclaimed chunks will not be reset the next restart."), false);
                        }

                        return 1;
                     } catch (RuntimeException var2) {
                        ((CommandSourceStack)ctx.getSource()).sendFailure(Component.literal(var2.getMessage()));
                        return 0;
                     }
                  }))
            )
      );
   }
}
