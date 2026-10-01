package top.leonx.territory.network;

import top.leonx.territory.TerritoryMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record FactionInfoRequestC2S(BlockPos pos) implements CustomPacketPayload {
   public static final Type<FactionInfoRequestC2S> TYPE = new Type(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "faction_request"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FactionInfoRequestC2S> CODEC = StreamCodec.of(
      (buf, m) -> buf.writeBlockPos(m.pos), buf -> new FactionInfoRequestC2S(buf.readBlockPos())
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
