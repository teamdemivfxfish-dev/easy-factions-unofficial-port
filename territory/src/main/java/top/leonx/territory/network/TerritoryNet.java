package top.leonx.territory.network;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import top.leonx.territory.TerritoryConfig;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.EasyFactionsBridge;
import top.leonx.territory.world.TerritoryNames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the Territory Table payloads and holds the server-side handlers. The client handler is
 * dispatched via {@code ClientHooks} only inside an enqueueWork lambda, so that client-only class is
 * never touched on a dedicated server.
 */
@EventBusSubscriber(modid = TerritoryMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TerritoryNet {

    private TerritoryNet() {}

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
        return Math.max(1, Math.min(TerritoryRequestC2S.MAX_RADIUS, r));
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
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            MinecraftServer server = sp.getServer();
            if (server == null) return;
            List<ChunkPos> add = new ArrayList<>();
            for (long l : msg.add()) add.add(new ChunkPos(l));
            List<ChunkPos> remove = new ArrayList<>();
            for (long l : msg.remove()) remove.add(new ChunkPos(l));

            boolean personal = msg.claimType() == EasyFactionsBridge.TYPE_PERSONAL;
            boolean adminClaim = msg.claimType() == EasyFactionsBridge.TYPE_ADMIN;
            boolean childPlot = msg.claimType() == EasyFactionsBridge.TYPE_CHILD;

            String status;
            if (childPlot) {
                // a child plot moves no Easy Factions claim: it only records which of the parent's chunks
                // belong to the plot, so it takes a different path from the three real claim types
                status = EasyFactionsBridge.commitChild(sp, msg.name(), add, remove, msg.color());
            } else {
                int coreColor = personal ? TerritoryNames.get(server).getColor(sp.getUUID()) : -1;
                // name/colour only mean anything for the claim type they belong to; commit() ignores the rest
                String adminName = adminClaim ? msg.name() : "";
                int adminColor = adminClaim ? msg.color() : -1;
                status = EasyFactionsBridge.commit(sp, msg.claimType(), add, remove, coreColor,
                        adminName, adminColor);
            }
            if (personal) {
                TerritoryNames.get(server).setName(sp.getUUID(), msg.name());
            }
            if (status != null && !status.isEmpty()) {
                sp.displayClientMessage(Component.literal(status), true);
            }
            sendData(sp, new ChunkPos(msg.centerX(), msg.centerZ()), clampRadius(msg.radius()));
        });
    }

    private static void onColor(TerritoryColorC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            MinecraftServer server = sp.getServer();
            if (server == null) return;
            int rgb = msg.color() & 0xFFFFFF;
            if (msg.faction()) {
                String status = EasyFactionsBridge.recolorFaction(sp, rgb);
                if (status != null && !status.isEmpty()) sp.displayClientMessage(Component.literal(status), true);
            } else {
                TerritoryNames.get(server).setColor(sp.getUUID(), rgb);
                EasyFactionsBridge.recolorPersonal(sp, rgb);   // keep existing borders consistent
            }
            sendData(sp, new ChunkPos(msg.centerX(), msg.centerZ()), clampRadius(msg.radius()));
        });
    }

    private static void onAdminAction(AdminActionC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            String status = switch (msg.action()) {
                case AdminActionC2S.SET_PERM -> EasyFactionsBridge.setAdminPerm(sp, msg.territory(),
                        parseOrdinal(msg.arg()), msg.flag());
                case AdminActionC2S.ADD_MEMBER -> EasyFactionsBridge.setAdminMember(sp, msg.territory(), msg.arg(), true);
                case AdminActionC2S.REMOVE_MEMBER -> EasyFactionsBridge.setAdminMember(sp, msg.territory(), msg.arg(), false);
                default -> "";
            };
            if (status != null && !status.isEmpty()) {
                sp.displayClientMessage(Component.literal(status), true);
            }
            sendData(sp, new ChunkPos(msg.centerX(), msg.centerZ()), clampRadius(msg.radius()));
        });
    }

    private static int parseOrdinal(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;      // rejected by the bridge as an unknown permission
        }
    }

    private static void onData(TerritoryDataS2C msg, IPayloadContext ctx) {
        // resolved + run only on the client; the class load is deferred to here
        ctx.enqueueWork(() -> top.leonx.territory.client.ClientHooks.acceptData(msg));
    }

    private static void onFactionRequest(FactionInfoRequestC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) sendFactionInfo(sp);
        });
    }

    private static void onFactionAction(FactionActionC2S msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            MinecraftServer server = sp.getServer();
            if (server == null) return;

            String arg = sanitize(msg.arg());
            String arg2 = sanitize(msg.arg2());
            String cmd = null;
            switch (msg.action()) {
                case FactionActionC2S.CREATE -> { if (!arg.isEmpty()) cmd = "faction create " + arg; }
                case FactionActionC2S.INVITE -> { if (!arg.isEmpty()) cmd = "faction invite " + arg; }
                case FactionActionC2S.JOIN -> { if (!arg.isEmpty()) cmd = "faction join " + arg; }
                case FactionActionC2S.LEAVE -> cmd = "faction leave";
                case FactionActionC2S.KICK -> { if (!arg.isEmpty()) cmd = "faction kick " + arg; }
                case FactionActionC2S.ADD_OFFICER -> { if (!arg.isEmpty()) cmd = "faction addOfficer " + arg; }
                case FactionActionC2S.REMOVE_OFFICER -> { if (!arg.isEmpty()) cmd = "faction removeOfficer " + arg; }
                case FactionActionC2S.SET_ABBREV -> { if (!arg.isEmpty()) cmd = "faction setAbbreviation " + arg; }
                case FactionActionC2S.SET_COLOR -> { if (!arg.isEmpty()) cmd = "faction setColor " + arg; }
                case FactionActionC2S.SET_RELATION -> { if (!arg.isEmpty() && !arg2.isEmpty()) cmd = "faction setRelation " + arg + " " + arg2; }
                case FactionActionC2S.FRIENDLY_FIRE -> cmd = "faction friendlyFire " + ("true".equalsIgnoreCase(arg) ? "true" : "false");
                case FactionActionC2S.DISBAND -> {
                    String status = EasyFactionsBridge.disband(sp);
                    if (status != null && !status.isEmpty()) sp.displayClientMessage(Component.literal(status), false);
                }
                case FactionActionC2S.REVOKE -> {
                    String status = EasyFactionsBridge.revokeInvite(sp, arg);
                    if (status != null && !status.isEmpty()) sp.displayClientMessage(Component.literal(status), true);
                }
                case FactionActionC2S.BUY_CLAIMS -> {
                    // /factionbuy as a button: owner pays to raise the faction claim cap (SDM, emerald fallback)
                    String status = EasyFactionsBridge.buyClaims(sp);
                    if (status != null && !status.isEmpty()) sp.displayClientMessage(Component.literal(status), false);
                }
                default -> { /* unknown action: ignore */ }
            }
            if (cmd != null) {
                server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), cmd);
            }
            // refresh the faction panel; the map re-requests itself when the player returns to it
            sendFactionInfo(sp);
        });
    }

    private static void onFactionInfo(FactionInfoS2C msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> top.leonx.territory.client.ClientHooks.acceptFactionInfo(msg));
    }

    private static void sendFactionInfo(ServerPlayer sp) {
        EasyFactionsBridge.FactionInfo info = EasyFactionsBridge.factionInfo(sp);
        List<FactionInfoS2C.Member> members = new ArrayList<>(info.members().size());
        for (EasyFactionsBridge.Member m : info.members()) {
            members.add(new FactionInfoS2C.Member(m.name(), m.role()));
        }
        List<FactionInfoS2C.Relation> relations = new ArrayList<>(info.relations().size());
        for (EasyFactionsBridge.Relation r : info.relations()) {
            relations.add(new FactionInfoS2C.Relation(r.faction(), r.status()));
        }
        FactionInfoS2C out = new FactionInfoS2C(info.efLoaded(), info.inFaction(), info.name(), info.color(),
                info.abbreviation(), info.isOwner(), info.isOfficer(), info.friendlyFire(), info.ownerName(),
                members, info.invitesForViewer(), relations,
                info.factionCap(), info.factionUsed(), info.bonusClaims(),
                TerritoryConfig.costSdm(), TerritoryConfig.costEmeralds(), TerritoryConfig.claimsPerPurchase(),
                TerritoryConfig.buyEnabled());
        PacketDistributor.sendToPlayer(sp, out);
    }

    /** Keep only command-safe characters so a crafted action string can't inject a different command. */
    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 48; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '#' || c == ' ') b.append(c);
        }
        return b.toString().trim();
    }

    private static void sendData(ServerPlayer sp, ChunkPos center, int radius) {
        MinecraftServer server = sp.getServer();
        if (server == null) return;
        EasyFactionsBridge.Ctx c = EasyFactionsBridge.gatherContext(sp);
        List<EasyFactionsBridge.ClaimCell> claims = EasyFactionsBridge.regionClaims(sp, sp.level().dimension(), center, radius);

        // dedupe labels into an owners list; each claim references it by index
        List<String> owners = new ArrayList<>();
        Map<String, Integer> ownerIndex = new HashMap<>();
        List<TerritoryDataS2C.ClaimEntry> entries = new ArrayList<>(claims.size());
        for (EasyFactionsBridge.ClaimCell cell : claims) {
            int idx = ownerIndex.computeIfAbsent(cell.label(), k -> {
                owners.add(k);
                return owners.size() - 1;
            });
            int childIdx = -1;
            if (!cell.childName().isEmpty()) {
                childIdx = ownerIndex.computeIfAbsent(cell.childName(), k -> {
                    owners.add(k);
                    return owners.size() - 1;
                });
            }
            entries.add(new TerritoryDataS2C.ClaimEntry(cell.x(), cell.z(), cell.kind(), cell.color(), idx,
                    childIdx, cell.childColor()));
        }

        // operators only: everything the Permissions tab needs. Everyone else gets an empty list, so a
        // normal player's client is never even told which admin territories exist.
        List<TerritoryDataS2C.AdminZone> zones = new ArrayList<>();
        for (EasyFactionsBridge.AdminZoneInfo z : EasyFactionsBridge.adminZones(sp)) {
            zones.add(new TerritoryDataS2C.AdminZone(z.name(), z.parent(), z.color(), z.perms(), z.custom(),
                    z.chunks(), z.members()));
        }

        TerritoryNames names = TerritoryNames.get(server);
        String personalName = names.getName(sp.getUUID());
        int personalColor = names.getColor(sp.getUUID());
        if (personalColor == TerritoryNames.NO_COLOR) personalColor = EasyFactionsBridge.defaultCoreColor();

        TerritoryDataS2C data = new TerritoryDataS2C(
                c.efLoaded(), c.inFaction(), c.canFactionClaim(), c.canAdminClaim(), c.canPersonalClaim(),
                c.factionName(), c.factionColor(),
                c.coreCap(), c.coreUsed(), c.factionCap(), c.factionUsed(),
                personalName, personalColor, owners, entries, zones);
        PacketDistributor.sendToPlayer(sp, data);
    }
}
