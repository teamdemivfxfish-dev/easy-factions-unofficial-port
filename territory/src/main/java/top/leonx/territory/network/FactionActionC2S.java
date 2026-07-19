package top.leonx.territory.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

/**
 * Client -> server: a Faction tab button press. The server maps {@code action} to a fixed Easy Factions
 * command template (or the disband API), sanitizes {@code arg}/{@code arg2}, and runs it as the player so
 * EF does all validation, permission checks, and messaging. {@code arg2} is only used by SET_RELATION.
 */
public record FactionActionC2S(BlockPos pos, int action, String arg, String arg2) implements CustomPacketPayload {

    public static final int CREATE = 0;
    public static final int INVITE = 1;
    public static final int JOIN = 2;
    public static final int LEAVE = 3;
    public static final int KICK = 4;
    public static final int ADD_OFFICER = 5;
    public static final int REMOVE_OFFICER = 6;
    public static final int SET_ABBREV = 7;
    public static final int SET_COLOR = 8;
    public static final int SET_RELATION = 9;
    public static final int FRIENDLY_FIRE = 10;
    public static final int DISBAND = 11;
    public static final int REVOKE = 12;
    public static final int BUY_CLAIMS = 13;   // owner pays to raise the faction claim cap (handled in the bridge)

    public static final Type<FactionActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "faction_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionActionC2S> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeBlockPos(m.pos);
                buf.writeVarInt(m.action);
                buf.writeUtf(m.arg);
                buf.writeUtf(m.arg2);
            },
            buf -> new FactionActionC2S(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
