package top.leonx.territory.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

/** Client -> server: the Faction tab asks for the player's faction roster + role. */
public record FactionInfoRequestC2S(BlockPos pos) implements CustomPacketPayload {

    public static final Type<FactionInfoRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "faction_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionInfoRequestC2S> CODEC = StreamCodec.of(
            (buf, m) -> buf.writeBlockPos(m.pos),
            buf -> new FactionInfoRequestC2S(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
