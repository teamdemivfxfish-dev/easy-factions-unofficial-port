package top.leonx.territory.network;

import top.leonx.territory.TerritoryMod;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record TerritoryCommitC2S(int claimType, List<Long> add, List<Long> remove, String name, int centerX, int centerZ, int radius)
   implements CustomPacketPayload {
   public static final int MAX_NAME = 48;
   public static final int MAX_CHUNKS = 1024;
   public static final Type<TerritoryCommitC2S> TYPE = new Type(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "commit"));
   public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryCommitC2S> CODEC = StreamCodec.of((buf, m) -> {
      buf.writeVarInt(m.claimType);
      writeLongs(buf, m.add);
      writeLongs(buf, m.remove);
      buf.writeUtf(m.name, 48);
      buf.writeVarInt(m.centerX);
      buf.writeVarInt(m.centerZ);
      buf.writeVarInt(m.radius);
   }, buf -> {
      int claimType = buf.readVarInt();
      List<Long> add = readLongs(buf);
      List<Long> remove = readLongs(buf);
      String name = buf.readUtf(48);
      int centerX = buf.readVarInt();
      int centerZ = buf.readVarInt();
      int radius = buf.readVarInt();
      return new TerritoryCommitC2S(claimType, add, remove, name, centerX, centerZ, radius);
   });

   public TerritoryCommitC2S(int claimType, List<Long> add, List<Long> remove, String name, int centerX, int centerZ, int radius) {
      if (name == null) {
         name = "";
      } else if (name.length() > 48) {
         name = name.substring(0, 48);
      }

      this.claimType = claimType;
      this.add = add;
      this.remove = remove;
      this.name = name;
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.radius = radius;
   }

   private static void writeLongs(RegistryFriendlyByteBuf buf, List<Long> longs) {
      buf.writeVarInt(longs.size());

      for (long l : longs) {
         buf.writeLong(l);
      }
   }

   private static List<Long> readLongs(RegistryFriendlyByteBuf buf) {
      int n = buf.readVarInt();
      if (n >= 0 && n <= 1024) {
         List<Long> out = new ArrayList<>(n);

         for (int i = 0; i < n; i++) {
            out.add(buf.readLong());
         }

         return out;
      } else {
         throw new DecoderException("territory: chunk list too large (" + n + ")");
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
