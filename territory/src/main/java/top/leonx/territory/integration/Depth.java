package top.leonx.territory.integration;

import dev.xmat5.holdfast.server.claims.ClaimManager;
import dev.xmat5.holdfast.server.claims.model.ClaimData;
import dev.xmat5.holdfast.server.claims.model.ClaimType;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.world.Interaction;

public final class Depth {
    public static final int NONE = -1;

    private Depth() {
    }

    public static int of(MinecraftServer server, ResourceKey<Level> dim, ChunkPos chunk, int limit) {
        if (!FactionsBridge.loaded() || server == null || dim == null || chunk == null) {
            return NONE;
        }
        ClaimManager cm = ClaimManager.get(server);
        if (!cm.isClaimed(dim, chunk)) {
            return NONE;
        }
        ClaimData base = cm.getClaim(dim, chunk);
        if (base == null || base.type != ClaimType.FACTION) {
            return NONE;
        }
        return DepthMath.depth((x, z) -> sameFaction(cm, dim, base, x, z), chunk.x, chunk.z, limit);
    }

    public static boolean allows(Player player, ResourceKey<Level> dim, BlockPos pos, Interaction interaction, boolean tnt) {
        if (!TerritoryConfig.depthEnabled()) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ChunkPos chunk = new ChunkPos(pos);
        if (tnt && TerritoryConfig.allowTnt() && (interaction == Interaction.PLACE_BLOCK || interaction == Interaction.RIGHT_CLICK_BLOCK)) {
            return isFactionClaim(server, dim, chunk);
        }
        if (!TerritoryConfig.openOuterRing() || !opensOuterRing(interaction)) {
            return false;
        }
        if ((interaction == Interaction.BREAK_BLOCK || interaction == Interaction.LEFT_CLICK_BLOCK)
                && player.level().getBlockState(pos).is(TerritoryMod.TERRITORY_TABLE.get())) {
            return false;
        }
        return of(server, dim, chunk, 1) == 0;
    }

    private static boolean opensOuterRing(Interaction interaction) {
        return interaction == Interaction.BREAK_BLOCK
                || interaction == Interaction.PLACE_BLOCK
                || interaction == Interaction.LEFT_CLICK_BLOCK
                || interaction == Interaction.USE_BUCKET;
    }

    private static boolean isFactionClaim(MinecraftServer server, ResourceKey<Level> dim, ChunkPos chunk) {
        ClaimManager cm = ClaimManager.get(server);
        if (!cm.isClaimed(dim, chunk)) {
            return false;
        }
        ClaimData claim = cm.getClaim(dim, chunk);
        return claim != null && claim.type == ClaimType.FACTION;
    }

    private static boolean sameFaction(ClaimManager cm, ResourceKey<Level> dim, ClaimData base, int x, int z) {
        ChunkPos pos = new ChunkPos(x, z);
        if (!cm.isClaimed(dim, pos)) {
            return false;
        }
        ClaimData other = cm.getClaim(dim, pos);
        return other != null && other.type == ClaimType.FACTION && Objects.equals(base.owner, other.owner);
    }
}
