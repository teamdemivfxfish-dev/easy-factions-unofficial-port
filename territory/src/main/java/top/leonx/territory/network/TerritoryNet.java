package top.leonx.territory.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import top.leonx.territory.client.ClientHooks;
import top.leonx.territory.container.TerritoryTableMenu;
import top.leonx.territory.integration.FactionsBridge;
import top.leonx.territory.integration.Upkeep;
import top.leonx.territory.world.TerritoryNames;
import top.leonx.territory.world.ZoneColor;

@EventBusSubscriber(
   modid = "holdfast_factions",
   bus = Bus.MOD
)
public final class TerritoryNet {
   private TerritoryNet() {
   }

   @SubscribeEvent
   static void register(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("1");
      registrar.playToServer(TerritoryRequestC2S.TYPE, TerritoryRequestC2S.CODEC, TerritoryNet::onRequest);
      registrar.playToServer(TerritoryCommitC2S.TYPE, TerritoryCommitC2S.CODEC, TerritoryNet::onCommit);
      registrar.playToClient(TerritoryDataS2C.TYPE, TerritoryDataS2C.CODEC, TerritoryNet::onData);
      registrar.playToServer(FactionInfoRequestC2S.TYPE, FactionInfoRequestC2S.CODEC, TerritoryNet::onFactionRequest);
      registrar.playToServer(FactionActionC2S.TYPE, FactionActionC2S.CODEC, TerritoryNet::onFactionAction);
      registrar.playToClient(FactionInfoS2C.TYPE, FactionInfoS2C.CODEC, TerritoryNet::onFactionInfo);
      registrar.playToServer(TerritoryColorC2S.TYPE, TerritoryColorC2S.CODEC, TerritoryNet::onColor);
      registrar.playToServer(AdminActionC2S.TYPE, AdminActionC2S.CODEC, TerritoryNet::onAdminAction);
   }

   private static int clampRadius(int r) {
      return Math.max(1, Math.min(28, r));
   }

   private static void onRequest(TerritoryRequestC2S msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (ctx.player() instanceof ServerPlayer sp) {
            sendData(sp, new ChunkPos(msg.chunkX(), msg.chunkZ()), clampRadius(msg.radius()));
         }
      });
   }

   private static void onCommit(TerritoryCommitC2S msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (ctx.player() instanceof ServerPlayer sp) {
            MinecraftServer server = sp.getServer();
            if (server != null) {
               List<ChunkPos> add = new ArrayList<>();

               for (long l : msg.add()) {
                  add.add(new ChunkPos(l));
               }

               List<ChunkPos> remove = new ArrayList<>();

               for (long l : msg.remove()) {
                  remove.add(new ChunkPos(l));
               }

               boolean personal = msg.claimType() == 0;
               boolean adminClaim = msg.claimType() == FactionsBridge.TYPE_ADMIN || msg.claimType() == FactionsBridge.TYPE_WARZONE;
               int coreColor = personal ? TerritoryNames.get(server).getColor(sp.getUUID()) : -1;
               String adminName = adminClaim ? msg.name() : "";
               String status = FactionsBridge.commit(sp, msg.claimType(), add, remove, coreColor, adminName);

               if (personal) {
                  TerritoryNames.get(server).setName(sp.getUUID(), msg.name());
               }

               if (status != null && !status.isEmpty()) {
                  sp.displayClientMessage(Component.literal(status), true);
               }

               sendData(sp, new ChunkPos(msg.centerX(), msg.centerZ()), clampRadius(msg.radius()));
            }
         }
      });
   }

   private static void onColor(TerritoryColorC2S msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (ctx.player() instanceof ServerPlayer sp) {
            MinecraftServer server = sp.getServer();
            if (server != null) {
               int rgb = msg.color() & 16777215;
               if (msg.faction()) {
                  String status = FactionsBridge.recolorFaction(sp, rgb);
                  if (status != null && !status.isEmpty()) {
                     sp.displayClientMessage(Component.literal(status), true);
                  }
               } else {
                  if (ZoneColor.isReserved(rgb)) {
                     sp.displayClientMessage(Component.literal(FactionsBridge.RESERVED_COLOR_MESSAGE), true);
                  } else {
                     TerritoryNames.get(server).setColor(sp.getUUID(), rgb);
                     FactionsBridge.recolorPersonal(sp, rgb);
                  }
               }

               sendData(sp, new ChunkPos(msg.centerX(), msg.centerZ()), clampRadius(msg.radius()));
            }
         }
      });
   }

   private static void onAdminAction(AdminActionC2S msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (ctx.player() instanceof ServerPlayer sp) {
            String status = switch (msg.action()) {
               case 0 -> FactionsBridge.setAdminPerm(sp, msg.territory(), parseOrdinal(msg.arg()), msg.flag());
               case 1 -> FactionsBridge.setAdminMember(sp, msg.territory(), msg.arg(), true);
               case 2 -> FactionsBridge.setAdminMember(sp, msg.territory(), msg.arg(), false);
               default -> "";
            };
            if (status != null && !status.isEmpty()) {
               sp.displayClientMessage(Component.literal(status), true);
            }

            sendData(sp, new ChunkPos(msg.centerX(), msg.centerZ()), clampRadius(msg.radius()));
         }
      });
   }

   private static int parseOrdinal(String s) {
      try {
         return Integer.parseInt(s.trim());
      } catch (NumberFormatException var2) {
         return -1;
      }
   }

   private static void onData(TerritoryDataS2C msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> ClientHooks.acceptData(msg));
   }

   private static void onFactionRequest(FactionInfoRequestC2S msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (ctx.player() instanceof ServerPlayer sp) {
            sendFactionInfo(sp);
         }
      });
   }

   private static void onFactionAction(FactionActionC2S msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> {
         if (ctx.player() instanceof ServerPlayer sp) {
            MinecraftServer server = sp.getServer();
            if (server != null) {
               String arg = sanitize(msg.arg());
               String arg2 = sanitize(msg.arg2());
               String cmd = null;
               switch (msg.action()) {
                  case 0:
                     if (!arg.isEmpty()) {
                        cmd = "faction create " + arg;
                     }
                     break;
                  case 1:
                     if (!arg.isEmpty()) {
                        cmd = "faction invite " + arg;
                     }
                     break;
                  case 2:
                     if (!arg.isEmpty()) {
                        cmd = "faction join " + arg;
                     }
                     break;
                  case 3:
                     cmd = "faction leave";
                     break;
                  case 4:
                     if (!arg.isEmpty()) {
                        cmd = "faction kick " + arg;
                     }
                     break;
                  case 5:
                     if (!arg.isEmpty()) {
                        cmd = "faction addOfficer " + arg;
                     }
                     break;
                  case 6:
                     if (!arg.isEmpty()) {
                        cmd = "faction removeOfficer " + arg;
                     }
                     break;
                  case 7:
                     if (!arg.isEmpty()) {
                        cmd = "faction setAbbreviation " + arg;
                     }
                     break;
                  case 8:
                     if (!arg.isEmpty()) {
                        cmd = "faction setColor " + arg;
                     }
                     break;
                  case 9:
                     if (!arg.isEmpty() && !arg2.isEmpty()) {
                        cmd = "faction setRelation " + arg + " " + arg2;
                     }
                     break;
                  case 10:
                     cmd = "faction friendlyFire " + ("true".equalsIgnoreCase(arg) ? "true" : "false");
                     break;
                  case 11:
                     String statusxx = FactionsBridge.disband(sp);
                     if (statusxx != null && !statusxx.isEmpty()) {
                        sp.displayClientMessage(Component.literal(statusxx), false);
                     }
                     break;
                  case 12:
                     String statusx = FactionsBridge.revokeInvite(sp, arg);
                     if (statusx != null && !statusx.isEmpty()) {
                        sp.displayClientMessage(Component.literal(statusx), true);
                     }
                     break;
                  case 13:
                     if (sp.containerMenu instanceof TerritoryTableMenu menu && menu.pos.equals(msg.pos())) {
                        String deposited = Upkeep.depositAll(sp, sp.level(), menu.pos, menu.inputContainer());
                        if (deposited != null && !deposited.isEmpty()) {
                           sp.displayClientMessage(Component.literal(deposited), true);
                        }
                     }
                     break;
                  case 14:
                     String changed = FactionsBridge.setFactionSetting(sp, arg, parseOrdinal(arg2));
                     if (changed != null && !changed.isEmpty()) {
                        sp.displayClientMessage(Component.literal(changed), true);
                     }
               }

               if (cmd != null) {
                  server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), cmd);
               }

               sendFactionInfo(sp);
            }
         }
      });
   }

   private static void onFactionInfo(FactionInfoS2C msg, IPayloadContext ctx) {
      ctx.enqueueWork(() -> ClientHooks.acceptFactionInfo(msg));
   }

   private static void sendFactionInfo(ServerPlayer sp) {
      FactionsBridge.FactionInfo info = FactionsBridge.factionInfo(sp);
      List<FactionInfoS2C.Member> members = new ArrayList<>(info.members().size());

      for (FactionsBridge.Member m : info.members()) {
         members.add(new FactionInfoS2C.Member(m.name(), m.role()));
      }

      List<FactionInfoS2C.Relation> relations = new ArrayList<>(info.relations().size());

      for (FactionsBridge.Relation r : info.relations()) {
         relations.add(new FactionInfoS2C.Relation(r.faction(), r.status()));
      }

      List<FactionInfoS2C.Contribution> top = new ArrayList<>();
      for (FactionsBridge.Contribution c : info.extras().top()) {
         top.add(new FactionInfoS2C.Contribution(c.name(), c.value()));
      }

      FactionInfoS2C.Extras extras = new FactionInfoS2C.Extras(
         info.extras().doors(),
         info.extras().utility(),
         info.extras().deposit(),
         info.extras().canEdit(),
         top,
         info.extras().mine(),
         info.extras().atWar(),
         info.extras().warnMinutes()
      );
      FactionInfoS2C out = new FactionInfoS2C(
         info.coreLoaded(),
         info.inFaction(),
         info.name(),
         info.color(),
         info.abbreviation(),
         info.isOwner(),
         info.isOfficer(),
         info.friendlyFire(),
         info.ownerName(),
         members,
         info.invitesForViewer(),
         relations,
         info.factionCap(),
         info.factionUsed(),
         info.upkeep().held(),
         info.upkeep().due(),
         info.upkeep().minutesToNext(),
         info.upkeep().hasCore(),
         info.upkeep().unit(),
         info.upkeep().intervalMinutes(),
         info.upkeep().graceMinutesLeft(),
         info.upkeep().corePos(),
         extras
      );
      PacketDistributor.sendToPlayer(sp, out, new CustomPacketPayload[0]);
   }

   private static String sanitize(String s) {
      if (s == null) {
         return "";
      } else {
         StringBuilder b = new StringBuilder();

         for (int i = 0; i < s.length() && b.length() < 48; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '#' || c == ' ') {
               b.append(c);
            }
         }

         return b.toString().trim();
      }
   }

   private static void sendData(ServerPlayer sp, ChunkPos center, int radius) {
      MinecraftServer server = sp.getServer();
      if (server != null) {
         FactionsBridge.Ctx c = FactionsBridge.gatherContext(sp);
         List<FactionsBridge.ClaimCell> claims = FactionsBridge.regionClaims(sp, sp.level().dimension(), center, radius);
         List<String> owners = new ArrayList<>();
         Map<String, Integer> ownerIndex = new HashMap<>();
         List<TerritoryDataS2C.ClaimEntry> entries = new ArrayList<>(claims.size());

         for (FactionsBridge.ClaimCell cell : claims) {
            int idx = ownerIndex.computeIfAbsent(cell.label(), k -> {
               owners.add(k);
               return owners.size() - 1;
            });

            entries.add(new TerritoryDataS2C.ClaimEntry(cell.x(), cell.z(), cell.kind(), cell.color(), idx));
         }

         List<TerritoryDataS2C.AdminZone> zones = new ArrayList<>();

         for (FactionsBridge.AdminZoneInfo z : FactionsBridge.adminZones(sp)) {
            zones.add(
               new TerritoryDataS2C.AdminZone(z.name(), z.color(), z.perms(), z.custom(), z.chunks(), z.members(), z.worldGuard())
            );
         }

         TerritoryNames names = TerritoryNames.get(server);
         String personalName = names.getName(sp.getUUID());
         int personalColor = names.getColor(sp.getUUID());
         if (personalColor == Integer.MIN_VALUE) {
            personalColor = FactionsBridge.defaultCoreColor();
         }

         TerritoryDataS2C data = new TerritoryDataS2C(
            c.coreLoaded(),
            c.inFaction(),
            c.canFactionClaim(),
            c.canAdminClaim(),
            c.canPersonalClaim(),
            c.factionName(),
            c.factionColor(),
            c.coreCap(),
            c.coreUsed(),
            c.factionCap(),
            c.factionUsed(),
            personalName,
            personalColor,
            owners,
            entries,
            zones
         );
         PacketDistributor.sendToPlayer(sp, data, new CustomPacketPayload[0]);
      }
   }
}
