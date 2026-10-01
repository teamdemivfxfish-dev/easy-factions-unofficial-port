package top.leonx.territory.network;

import top.leonx.territory.TerritoryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record AdminActionC2S(int action, String territory, String arg, boolean flag, int centerX, int centerZ, int radius) implements CustomPacketPayload {
   public static final int SET_PERM = 0;
   public static final int ADD_MEMBER = 1;
   public static final int REMOVE_MEMBER = 2;
   public static final int MAX_TEXT = 48;
   public static final Type<AdminActionC2S> TYPE = new Type(ResourceLocation.fromNamespaceAndPath(TerritoryMod.MODID, "admin_action"));
   public static final StreamCodec<RegistryFriendlyByteBuf, AdminActionC2S> CODEC = StreamCodec.of((buf, m) -> {
      buf.writeVarInt(m.action);
      buf.writeUtf(m.territory, 48);
      buf.writeUtf(m.arg, 48);
      buf.writeBoolean(m.flag);
      buf.writeVarInt(m.centerX);
      buf.writeVarInt(m.centerZ);
      buf.writeVarInt(m.radius);
   }, buf -> new AdminActionC2S(buf.readVarInt(), buf.readUtf(48), buf.readUtf(48), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

   public AdminActionC2S(int action, String territory, String arg, boolean flag, int centerX, int centerZ, int radius) {
      if (territory == null) {
         territory = "";
      } else if (territory.length() > 48) {
         territory = territory.substring(0, 48);
      }

      if (arg == null) {
         arg = "";
      } else if (arg.length() > 48) {
         arg = arg.substring(0, 48);
      }

      this.action = action;
      this.territory = territory;
      this.arg = arg;
      this.flag = flag;
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.radius = radius;
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
