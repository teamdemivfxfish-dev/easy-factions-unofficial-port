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
 *
 * Admin claims additionally carry the CHILD plot covering them, if any, and operators get the full list of
 * admin territories for the Permissions tab. Both are sent only to this GUI: children exist nowhere in Easy
 * Factions' own data, so no world map, atlas or minimap can render them even by accident.
 */
public record TerritoryDataS2C(boolean efLoaded, boolean inFaction, boolean canFactionClaim, boolean canAdminClaim,
                               boolean canPersonalClaim,
                               String factionName, int factionColor,
                               int coreCap, int coreUsed, int factionCap, int factionUsed,
                               String personalName, int personalColor,
                               List<String> owners, List<ClaimEntry> claims,
                               List<AdminZone> adminZones) implements CustomPacketPayload {

    /**
     * One claimed chunk: coords + KIND + the claim's EF colour (RGB) + index into the owners label list.
     * {@code childIdx} indexes the same owners list for the child plot covering this chunk, or -1 for none.
     */
    public record ClaimEntry(int x, int z, int kind, int color, int ownerIdx, int childIdx, int childColor) {}

    /**
     * One admin territory for the Permissions tab. {@code parent} is empty for a top-level territory,
     * {@code perms} is the effective switch mask and {@code custom} says whether the territory set it
     * itself or is still following Easy Factions' global admin config.
     */
    public record AdminZone(String name, String parent, int color, int perms, boolean custom, int chunks,
                            List<String> members) {}

    public static final Type<TerritoryDataS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryDataS2C> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeBoolean(m.efLoaded);
                buf.writeBoolean(m.inFaction);
                buf.writeBoolean(m.canFactionClaim);
                buf.writeBoolean(m.canAdminClaim);
                buf.writeBoolean(m.canPersonalClaim);
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
                    buf.writeVarInt(e.childIdx() + 1);          // -1 (no child) travels as 0
                    buf.writeInt(e.childColor());
                }
                buf.writeVarInt(m.adminZones.size());
                for (AdminZone z : m.adminZones) {
                    buf.writeUtf(z.name());
                    buf.writeUtf(z.parent());
                    buf.writeInt(z.color());
                    buf.writeInt(z.perms());
                    buf.writeBoolean(z.custom());
                    buf.writeVarInt(z.chunks());
                    buf.writeVarInt(z.members().size());
                    for (String member : z.members()) buf.writeUtf(member);
                }
            },
            buf -> {
                boolean efLoaded = buf.readBoolean();
                boolean inFaction = buf.readBoolean();
                boolean canFactionClaim = buf.readBoolean();
                boolean canAdminClaim = buf.readBoolean();
                boolean canPersonalClaim = buf.readBoolean();
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
                            buf.readInt(), buf.readVarInt(), buf.readVarInt() - 1, buf.readInt()));
                }
                int zoneCount = buf.readVarInt();
                List<AdminZone> zones = new ArrayList<>(zoneCount);
                for (int i = 0; i < zoneCount; i++) {
                    String name = buf.readUtf();
                    String parent = buf.readUtf();
                    int color = buf.readInt();
                    int perms = buf.readInt();
                    boolean custom = buf.readBoolean();
                    int chunks = buf.readVarInt();
                    int memberCount = buf.readVarInt();
                    List<String> members = new ArrayList<>(memberCount);
                    for (int j = 0; j < memberCount; j++) members.add(buf.readUtf());
                    zones.add(new AdminZone(name, parent, color, perms, custom, chunks, members));
                }
                return new TerritoryDataS2C(efLoaded, inFaction, canFactionClaim, canAdminClaim, canPersonalClaim,
                        factionName, factionColor, coreCap, coreUsed, factionCap, factionUsed,
                        personalName, personalColor, owners, claims, zones);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
