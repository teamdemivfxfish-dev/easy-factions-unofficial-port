package top.leonx.territory.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

/**
 * Client -> server: the Territory Table GUI asks for the claim picture in a square of {@code radius}
 * chunks around the chunk ({@code chunkX},{@code chunkZ}) the map is currently centred on. The map pans,
 * so this follows the view, not the table. The server answers with a {@link TerritoryDataS2C}.
 */
public record TerritoryRequestC2S(int chunkX, int chunkZ, int radius) implements CustomPacketPayload {

    /** Hard ceiling on the gather radius the server will honour (bounds the scan + anti-abuse). */
    public static final int MAX_RADIUS = 28;

    public static final Type<TerritoryRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryRequestC2S> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeVarInt(m.chunkX);
                buf.writeVarInt(m.chunkZ);
                buf.writeVarInt(m.radius);
            },
            buf -> new TerritoryRequestC2S(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
