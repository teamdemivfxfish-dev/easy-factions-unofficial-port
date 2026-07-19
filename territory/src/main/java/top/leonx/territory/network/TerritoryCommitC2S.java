package top.leonx.territory.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> server: apply the GUI's staged claim diff. {@code claimType} picks Easy Factions CORE
 * (personal) / FACTION / ADMIN; {@code add}/{@code remove} are packed chunk longs. After applying, the
 * server replies with refreshed {@link TerritoryDataS2C} centred on ({@code centerX},{@code centerZ}) so
 * the panned map redraws.
 *
 * {@code name} is the territory's cosmetic name: the personal-territory name for CORE claims and the admin
 * region's name for ADMIN claims (ignored for faction claims, which are labelled with the faction's name).
 * {@code color} is the admin region's chosen border colour, or negative to accept the configured default;
 * it is ignored for personal and faction claims, whose colours come from the player and faction records.
 *
 * None of this is trusted: the server re-checks permissions, caps and ownership in
 * {@link top.leonx.territory.integration.EasyFactionsBridge#commit}.
 */
public record TerritoryCommitC2S(int claimType, List<Long> add, List<Long> remove, String name, int color,
                                 int centerX, int centerZ, int radius) implements CustomPacketPayload {

    /** Longest name accepted off the wire, so a crafted client cannot push an unbounded string. */
    public static final int MAX_NAME = 48;

    /** Normalise on construction so an over-long name is trimmed here rather than thrown at encode time. */
    public TerritoryCommitC2S {
        if (name == null) name = "";
        else if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
    }

    /** Hard cap so a crafted client can't make the server pre-allocate a huge list. */
    public static final int MAX_CHUNKS = 1024;

    public static final Type<TerritoryCommitC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "commit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryCommitC2S> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeVarInt(m.claimType);
                writeLongs(buf, m.add);
                writeLongs(buf, m.remove);
                buf.writeUtf(m.name, MAX_NAME);
                buf.writeInt(m.color);
                buf.writeVarInt(m.centerX);
                buf.writeVarInt(m.centerZ);
                buf.writeVarInt(m.radius);
            },
            buf -> {
                int claimType = buf.readVarInt();
                List<Long> add = readLongs(buf);
                List<Long> remove = readLongs(buf);
                String name = buf.readUtf(MAX_NAME);
                int color = buf.readInt();
                int centerX = buf.readVarInt();
                int centerZ = buf.readVarInt();
                int radius = buf.readVarInt();
                return new TerritoryCommitC2S(claimType, add, remove, name, color, centerX, centerZ, radius);
            });

    private static void writeLongs(RegistryFriendlyByteBuf buf, List<Long> longs) {
        buf.writeVarInt(longs.size());
        for (long l : longs) buf.writeLong(l);
    }

    private static List<Long> readLongs(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_CHUNKS) {
            throw new io.netty.handler.codec.DecoderException("territory: chunk list too large (" + n + ")");
        }
        List<Long> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readLong());
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
