package top.leonx.territory.network;

import top.leonx.territory.TerritoryMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record TerritoryDataS2C(
   boolean coreLoaded,
   boolean inFaction,
   boolean canFactionClaim,
   boolean canAdminClaim,
   boolean canPersonalClaim,
   String factionName,
   int factionColor,
   int coreCap,
   int coreUsed,
   int factionCap,
   int factionUsed,
   String personalName,
   int personalColor,
   List<String> owners,
   List<TerritoryDataS2C.ClaimEntry> claims,
   List<TerritoryDataS2C.AdminZone> adminZones
) implements CustomPacketPayload {
   public static final Type<TerritoryDataS2C> TYPE = new Type(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "data"));
   public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryDataS2C> CODEC = StreamCodec.of(
      (buf, m) -> {
         buf.writeBoolean(m.coreLoaded);
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

         for (String o : m.owners) {
            buf.writeUtf(o);
         }

         buf.writeVarInt(m.claims.size());

         for (TerritoryDataS2C.ClaimEntry e : m.claims) {
            buf.writeVarInt(e.x());
            buf.writeVarInt(e.z());
            buf.writeByte(e.kind());
            buf.writeInt(e.color());
            buf.writeVarInt(e.ownerIdx());
         }

         buf.writeVarInt(m.adminZones.size());

         for (TerritoryDataS2C.AdminZone z : m.adminZones) {
            buf.writeUtf(z.name());
            buf.writeInt(z.color());
            buf.writeInt(z.perms());
            buf.writeBoolean(z.custom());
            buf.writeBoolean(z.worldGuard());
            buf.writeVarInt(z.chunks());
            buf.writeVarInt(z.members().size());

            for (String member : z.members()) {
               buf.writeUtf(member);
            }
         }
      },
      buf -> {
         boolean coreLoaded = buf.readBoolean();
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

         for (int i = 0; i < ownerCount; i++) {
            owners.add(buf.readUtf());
         }

         int n = buf.readVarInt();
         List<TerritoryDataS2C.ClaimEntry> claims = new ArrayList<>(n);

         for (int i = 0; i < n; i++) {
            claims.add(
               new TerritoryDataS2C.ClaimEntry(
                  buf.readVarInt(), buf.readVarInt(), buf.readByte(), buf.readInt(), buf.readVarInt()
               )
            );
         }

         int zoneCount = buf.readVarInt();
         List<TerritoryDataS2C.AdminZone> zones = new ArrayList<>(zoneCount);

         for (int i = 0; i < zoneCount; i++) {
            String name = buf.readUtf();
            int color = buf.readInt();
            int perms = buf.readInt();
            boolean custom = buf.readBoolean();
            boolean worldGuard = buf.readBoolean();
            int chunks = buf.readVarInt();
            int memberCount = buf.readVarInt();
            List<String> members = new ArrayList<>(memberCount);

            for (int j = 0; j < memberCount; j++) {
               members.add(buf.readUtf());
            }

            zones.add(new TerritoryDataS2C.AdminZone(name, color, perms, custom, chunks, members, worldGuard));
         }
         return new TerritoryDataS2C(
            coreLoaded,
            inFaction,
            canFactionClaim,
            canAdminClaim,
            canPersonalClaim,
            factionName,
            factionColor,
            coreCap,
            coreUsed,
            factionCap,
            factionUsed,
            personalName,
            personalColor,
            owners,
            claims,
            zones
         );
      }
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static record AdminZone(
      String name, int color, int perms, boolean custom, int chunks, List<String> members, boolean worldGuard
   ) {
   }

   public static record ClaimEntry(int x, int z, int kind, int color, int ownerIdx) {
   }
}
