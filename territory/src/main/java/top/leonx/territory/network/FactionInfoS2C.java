package top.leonx.territory.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import top.leonx.territory.TerritoryMod;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: the Faction tab's roster + the viewer's role, so the tab can render the member list
 * and gate its action buttons. When the viewer is not in a faction, {@code invites} lists factions that
 * have invited them (for Join buttons).
 */
public record FactionInfoS2C(boolean efLoaded, boolean inFaction, String name, int color, String abbreviation,
                             boolean isOwner, boolean isOfficer, boolean friendlyFire, String ownerName,
                             List<Member> members, List<String> invites,
                             List<Relation> relations,
                             int factionCap, int factionUsed, int bonusClaims,
                             long costSdm, int costEmerald, int claimsPerPurchase,
                             boolean buyEnabled) implements CustomPacketPayload {

    /** Resolved member name + role (0 member, 1 officer, 2 owner). */
    public record Member(String name, int role) {}

    /** A relationship toward another faction (status = FRIENDLY/NEUTRAL/HOSTILE). */
    public record Relation(String faction, String status) {}

    public static final Type<FactionInfoS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "faction_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionInfoS2C> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeBoolean(m.efLoaded);
                buf.writeBoolean(m.inFaction);
                buf.writeUtf(m.name);
                buf.writeVarInt(m.color);
                buf.writeUtf(m.abbreviation);
                buf.writeBoolean(m.isOwner);
                buf.writeBoolean(m.isOfficer);
                buf.writeBoolean(m.friendlyFire);
                buf.writeUtf(m.ownerName);
                buf.writeVarInt(m.members.size());
                for (Member mem : m.members) {
                    buf.writeUtf(mem.name());
                    buf.writeVarInt(mem.role());
                }
                buf.writeVarInt(m.invites.size());
                for (String inv : m.invites) buf.writeUtf(inv);
                buf.writeVarInt(m.relations.size());
                for (Relation r : m.relations) {
                    buf.writeUtf(r.faction());
                    buf.writeUtf(r.status());
                }
                buf.writeVarInt(m.factionCap);
                buf.writeVarInt(m.factionUsed);
                buf.writeVarInt(m.bonusClaims);
                buf.writeVarLong(m.costSdm);
                buf.writeVarInt(m.costEmerald);
                buf.writeVarInt(m.claimsPerPurchase);
                buf.writeBoolean(m.buyEnabled);
            },
            buf -> {
                boolean efLoaded = buf.readBoolean();
                boolean inFaction = buf.readBoolean();
                String name = buf.readUtf();
                int color = buf.readVarInt();
                String abbreviation = buf.readUtf();
                boolean isOwner = buf.readBoolean();
                boolean isOfficer = buf.readBoolean();
                boolean friendlyFire = buf.readBoolean();
                String ownerName = buf.readUtf();
                int memberCount = buf.readVarInt();
                List<Member> members = new ArrayList<>(memberCount);
                for (int i = 0; i < memberCount; i++) {
                    members.add(new Member(buf.readUtf(), buf.readVarInt()));
                }
                int inviteCount = buf.readVarInt();
                List<String> invites = new ArrayList<>(inviteCount);
                for (int i = 0; i < inviteCount; i++) invites.add(buf.readUtf());
                int relCount = buf.readVarInt();
                List<Relation> relations = new ArrayList<>(relCount);
                for (int i = 0; i < relCount; i++) relations.add(new Relation(buf.readUtf(), buf.readUtf()));
                int factionCap = buf.readVarInt();
                int factionUsed = buf.readVarInt();
                int bonusClaims = buf.readVarInt();
                long costSdm = buf.readVarLong();
                int costEmerald = buf.readVarInt();
                int claimsPerPurchase = buf.readVarInt();
                boolean buyEnabled = buf.readBoolean();
                return new FactionInfoS2C(efLoaded, inFaction, name, color, abbreviation,
                        isOwner, isOfficer, friendlyFire, ownerName, members, invites, relations,
                        factionCap, factionUsed, bonusClaims, costSdm, costEmerald, claimsPerPurchase, buyEnabled);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
