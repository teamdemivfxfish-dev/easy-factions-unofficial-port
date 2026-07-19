package top.leonx.territory.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: the claim picture the Territory Table map renders. Carries the player's claim standing
 * (caps/used, faction role, their personal claim colour) plus the claimed chunks in the requested region.
 * Each {@link ClaimEntry} has a KIND code (mine-core / mine-faction / other, from
 * {@link top.leonx.territory.integration.EasyFactionsBridge}), the claim's real EF colour (so borders show
 * in their owner's colour), and an index into {@code owners} — the label to draw at the claim's centre
 * (the territory's NAME if it has one, else the owner's name).
 */
public record TerritoryDataS2C(boolean efLoaded, boolean inFaction, boolean canFactionClaim, boolean canAdminClaim,
                               String factionName, int factionColor,
                               int coreCap, int coreUsed, int factionCap, int factionUsed,
                               String personalName, int personalColor,
                               List<String> owners, List<ClaimEntry> claims) implements CustomPacketPayload {

    /** One claimed chunk: coords + KIND + the claim's EF colour (RGB) + index into the owners label list. */
    public record ClaimEntry(int x, int z, int kind, int color, int ownerIdx) {}

    public static final Type<TerritoryDataS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryDataS2C> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeBoolean(m.efLoaded);
                buf.writeBoolean(m.inFaction);
                buf.writeBoolean(m.canFactionClaim);
                buf.writeBoolean(m.canAdminClaim);
                buf.writeUtf(m.factionName);
                buf.writeVarInt(m.factionColor);
                buf.writeVarInt(m.coreCap);
                buf.writeVarInt(m.coreUsed);
                buf.writeVarInt(m.factionCap);
                buf.writeVarInt(m.factionUsed);
                buf.writeUtf(m.personalName);
                buf.writeVarInt(m.personalColor);
                buf.writeVarInt(m.owners.size());
                for (String o : m.owners) buf.writeUtf(o);
                buf.writeVarInt(m.claims.size());
                for (ClaimEntry e : m.claims) {
                    buf.writeVarInt(e.x());
                    buf.writeVarInt(e.z());
                    buf.writeByte(e.kind());
                    buf.writeInt(e.color());
                    buf.writeVarInt(e.ownerIdx());
                }
            },
            buf -> {
                boolean efLoaded = buf.readBoolean();
                boolean inFaction = buf.readBoolean();
                boolean canFactionClaim = buf.readBoolean();
                boolean canAdminClaim = buf.readBoolean();
                String factionName = buf.readUtf();
                int factionColor = buf.readVarInt();
                int coreCap = buf.readVarInt();
                int coreUsed = buf.readVarInt();
                int factionCap = buf.readVarInt();
                int factionUsed = buf.readVarInt();
                String personalName = buf.readUtf();
                int personalColor = buf.readVarInt();
                int ownerCount = buf.readVarInt();
                List<String> owners = new ArrayList<>(ownerCount);
                for (int i = 0; i < ownerCount; i++) owners.add(buf.readUtf());
                int n = buf.readVarInt();
                List<ClaimEntry> claims = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    claims.add(new ClaimEntry(buf.readVarInt(), buf.readVarInt(), buf.readByte(),
                            buf.readInt(), buf.readVarInt()));
                }
                return new TerritoryDataS2C(efLoaded, inFaction, canFactionClaim, canAdminClaim, factionName, factionColor,
                        coreCap, coreUsed, factionCap, factionUsed, personalName, personalColor, owners, claims);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
