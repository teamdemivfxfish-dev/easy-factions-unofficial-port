package top.leonx.territory.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

/**
 * Client -> server: an edit made in the Permissions tab of the Territory Table.
 *
 * Nothing here is trusted. The server re-checks operator status on every action, checks the territory really
 * exists in the player's own dimension, and resolves player names itself — a crafted client can do no more
 * than an operator sitting at the table could.
 *
 * {@code territory} names the admin territory being edited, {@code arg} is the permission ordinal (for
 * {@link #SET_PERM}) or a player name (for the member actions), and {@code flag} is the new on/off state.
 */
public record AdminActionC2S(int action, String territory, String arg, boolean flag,
                             int centerX, int centerZ, int radius) implements CustomPacketPayload {

    public static final int SET_PERM = 0;
    public static final int ADD_MEMBER = 1;
    public static final int REMOVE_MEMBER = 2;

    /** Longest string accepted off the wire, so a crafted client cannot push an unbounded name. */
    public static final int MAX_TEXT = 48;

    public AdminActionC2S {
        if (territory == null) territory = "";
        else if (territory.length() > MAX_TEXT) territory = territory.substring(0, MAX_TEXT);
        if (arg == null) arg = "";
        else if (arg.length() > MAX_TEXT) arg = arg.substring(0, MAX_TEXT);
    }

    public static final Type<AdminActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "admin_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminActionC2S> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeVarInt(m.action);
                buf.writeUtf(m.territory, MAX_TEXT);
                buf.writeUtf(m.arg, MAX_TEXT);
                buf.writeBoolean(m.flag);
                buf.writeVarInt(m.centerX);
                buf.writeVarInt(m.centerZ);
                buf.writeVarInt(m.radius);
            },
            buf -> new AdminActionC2S(buf.readVarInt(), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT),
                    buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
