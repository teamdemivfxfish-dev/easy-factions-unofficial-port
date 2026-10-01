package top.leonx.territory.network;

import top.leonx.territory.TerritoryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record TerritoryRequestC2S(int chunkX, int chunkZ, int radius) implements CustomPacketPayload {
   public static final int MAX_RADIUS = 28;
   public static final Type<TerritoryRequestC2S> TYPE = new Type(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "request"));
   public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryRequestC2S> CODEC = StreamCodec.of((buf, m) -> {
      buf.writeVarInt(m.chunkX);
      buf.writeVarInt(m.chunkZ);
      buf.writeVarInt(m.radius);
   }, buf -> new TerritoryRequestC2S(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
