package top.leonx.territory.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

/**
 * Client -> server: set a territory colour. If {@code faction}, recolour the player's FACTION (Easy
 * Factions changeFactionColor, owner/officer only); otherwise set + store the player's PERSONAL (CORE)
 * colour and recolour their core claims. {@code centerX}/{@code centerZ}/{@code radius} let the server
 * reply with refreshed map data for the current view.
 */
public record TerritoryColorC2S(int color, boolean faction, int centerX, int centerZ, int radius) implements CustomPacketPayload {

    public static final Type<TerritoryColorC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "color"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryColorC2S> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeInt(m.color);
                buf.writeBoolean(m.faction);
                buf.writeVarInt(m.centerX);
                buf.writeVarInt(m.centerZ);
                buf.writeVarInt(m.radius);
            },
            buf -> new TerritoryColorC2S(buf.readInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
