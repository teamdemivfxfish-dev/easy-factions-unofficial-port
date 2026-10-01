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
public record FactionInfoS2C(boolean coreLoaded, boolean inFaction, String name, int color, String abbreviation,
                             boolean isOwner, boolean isOfficer, boolean friendlyFire, String ownerName,
                             List<Member> members, List<String> invites,
                             List<Relation> relations,
                             int factionCap, int factionUsed,
                             int coreValue, int dueValue, int minutesToNext,
                             boolean hasCore, String unit,
                             int intervalMinutes, int graceMinutesLeft, long corePos, Extras extras) implements CustomPacketPayload {

    /** Resolved member name + role (0 member, 1 officer, 2 owner). */
    public record Member(String name, int role) {}

    /** A relationship toward another faction (status = FRIENDLY/NEUTRAL/HOSTILE). */
    public record Relation(String faction, String status) {}

    public record Contribution(String name, long value) {}

    public record Extras(int doors, int utility, int deposit, boolean canEdit, List<Contribution> top, long mine,
                         List<String> atWar, int warnMinutes) {
        public static final Extras NONE = new Extras(0, 0, 0, false, List.of(), 0L, List.of(), 0);
    }

    public static final Type<FactionInfoS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "faction_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionInfoS2C> CODEC = StreamCodec.of(
            (buf, m) -> {
                buf.writeBoolean(m.coreLoaded);
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
                buf.writeVarInt(m.coreValue);
                buf.writeVarInt(m.dueValue);
                buf.writeVarInt(m.minutesToNext);
                buf.writeBoolean(m.hasCore);
                buf.writeUtf(m.unit);
                buf.writeVarInt(m.intervalMinutes);
                buf.writeInt(m.graceMinutesLeft);
                buf.writeLong(m.corePos);
                buf.writeVarInt(m.extras.doors());
                buf.writeVarInt(m.extras.utility());
                buf.writeVarInt(m.extras.deposit());
                buf.writeBoolean(m.extras.canEdit());
                buf.writeVarInt(m.extras.top().size());
                for (Contribution c : m.extras.top()) {
                    buf.writeUtf(c.name());
                    buf.writeLong(c.value());
                }
                buf.writeLong(m.extras.mine());
                buf.writeVarInt(m.extras.atWar().size());
                for (String w : m.extras.atWar()) buf.writeUtf(w);
                buf.writeVarInt(m.extras.warnMinutes());
            },
            buf -> {
                boolean coreLoaded = buf.readBoolean();
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
                int coreValue = buf.readVarInt();
                int dueValue = buf.readVarInt();
                int minutesToNext = buf.readVarInt();
                boolean hasCore = buf.readBoolean();
                String unit = buf.readUtf();
                int intervalMinutes = buf.readVarInt();
                int graceMinutesLeft = buf.readInt();
                long corePos = buf.readLong();
                int doors = buf.readVarInt();
                int utility = buf.readVarInt();
                int deposit = buf.readVarInt();
                boolean canEdit = buf.readBoolean();
                int topCount = buf.readVarInt();
                List<Contribution> top = new ArrayList<>(topCount);
                for (int i = 0; i < topCount; i++) top.add(new Contribution(buf.readUtf(), buf.readLong()));
                long mine = buf.readLong();
                int warCount = buf.readVarInt();
                List<String> atWar = new ArrayList<>(warCount);
                for (int i = 0; i < warCount; i++) atWar.add(buf.readUtf());
                int warnMinutes = buf.readVarInt();
                Extras extras = new Extras(doors, utility, deposit, canEdit, top, mine, atWar, warnMinutes);
                return new FactionInfoS2C(coreLoaded, inFaction, name, color, abbreviation,
                        isOwner, isOfficer, friendlyFire, ownerName, members, invites, relations,
                        factionCap, factionUsed, coreValue, dueValue, minutesToNext, hasCore, unit,
                        intervalMinutes, graceMinutesLeft, corePos, extras);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
