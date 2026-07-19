package com.newtl.efwarborn;

import com.jpreiss.easy_factions.server.ServerConfig;
import com.jpreiss.easy_factions.server.claims.ClaimManager;
import com.jpreiss.easy_factions.server.claims.model.ClaimType;
import com.jpreiss.easy_factions.server.faction.Faction;
import com.jpreiss.easy_factions.server.faction.FactionStateManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /factionbuy} - buy the chunk you are standing in for in-game money, once your faction has
 * reached its claim cap. Owner/officer only. The free, point-based claim path is the port's own
 * {@code /faction claim}; this is the paid overflow on top of it.
 */
public final class FactionBuyCommand {

    private FactionBuyCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("factionbuy")
                        .requires(src -> src.getEntity() instanceof ServerPlayer)
                        .executes(FactionBuyCommand::buy)
        );
    }

    private static void fail(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    private static int memberCount(Faction f) {
        Set<UUID> all = new HashSet<>();
        if (f.getMembers() != null) all.addAll(f.getMembers());
        if (f.getOwner() != null) all.add(f.getOwner());
        return all.size();
    }

    private static int buy(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p;
        try {
            p = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();

        FactionStateManager fsm = FactionStateManager.get(server);
        Faction f = fsm.getFactionByPlayer(p.getUUID());
        if (f == null) {
            fail(p, "You are not in a faction.");
            return 0;
        }
        if (!fsm.playerIsOwnerOrOfficer(p.getUUID())) {
            fail(p, "Only the faction owner or an officer can buy chunks.");
            return 0;
        }

        ClaimManager cm = ClaimManager.get(server);
        int members = memberCount(f);
        int current = cm.getFactionClaimCount(f.getName());
        int cap = ServerConfig.factionBaseClaimLimit + ServerConfig.factionAdditionalClaimLimitPerMember * members;

        if (WarbornConfig.REQUIRE_AT_CAP.get() && current < cap) {
            fail(p, "Your faction still has free claims left (" + current + "/" + cap + "). Use /faction claim first.");
            return 0;
        }

        ChunkPos pos = new ChunkPos(p.blockPosition());
        ResourceKey<Level> dim = p.serverLevel().dimension();
        if (cm.isClaimed(dim, pos)) {
            fail(p, "This chunk is already claimed.");
            return 0;
        }

        if (!SdmBridge.isLoaded()) {
            fail(p, "Paid claims are unavailable (no economy mod installed).");
            return 0;
        }
        String key = SdmBridge.resolveKey(p, WarbornConfig.CURRENCY_KEY.get());
        if (key == null) {
            fail(p, "No economy currency is configured on this server.");
            return 0;
        }

        int price = WarbornConfig.CHUNK_PRICE.get();
        double bal = SdmBridge.balance(p, key);
        if (bal < price) {
            fail(p, "You need " + price + " " + key + " to buy a chunk, but you only have " + (long) bal + ".");
            return 0;
        }
        if (!SdmBridge.withdraw(p, key, price)) {
            fail(p, "Payment failed - your balance was not changed.");
            return 0;
        }

        try {
            HashMap<ResourceLocation, List<ChunkPos>> claim = new HashMap<>();
            claim.put(dim.location(), List.of(pos));
            cm.claimChunks(claim, ClaimType.FACTION, f.getName(), f.getColor(), server);
        } catch (Throwable t) {
            SdmBridge.refund(p, key, price);
            EFWarborn.LOGGER.error("Easy Factions Warborn: claim failed after charging; refunded.", t);
            fail(p, "Could not claim the chunk - you were refunded.");
            return 0;
        }

        p.sendSystemMessage(Component.literal(
                "Bought this chunk for " + price + " " + key + ". Faction chunks: " + (current + 1) + ".")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
