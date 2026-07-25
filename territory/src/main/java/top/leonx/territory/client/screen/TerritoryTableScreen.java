package top.leonx.territory.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import top.leonx.territory.client.MinimapSampler;
import top.leonx.territory.container.TerritoryTableMenu;
import top.leonx.territory.integration.EasyFactionsBridge;
import top.leonx.territory.network.AdminActionC2S;
import top.leonx.territory.network.FactionActionC2S;
import top.leonx.territory.network.FactionInfoRequestC2S;
import top.leonx.territory.network.FactionInfoS2C;
import top.leonx.territory.network.TerritoryColorC2S;
import top.leonx.territory.network.TerritoryCommitC2S;
import top.leonx.territory.network.TerritoryDataS2C;
import top.leonx.territory.network.TerritoryRequestC2S;
import top.leonx.territory.world.AdminPerm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Territory Table GUI: a big pannable/zoomable Easy-Factions claim map (tap a chunk to stage a
 * claim/unclaim, drag to pan, scroll to zoom) plus a sub-tabbed Faction manager. Claims render in their
 * real colour with an owner/territory-name label per contiguous group. Claim type is Personal / Faction /
 * Admin (Admin only shown to operators in creative). Personal claims must stay connected and can be
 * recoloured from a swatch palette; faction colours recolour the whole faction.
 */
public class TerritoryTableScreen extends AbstractContainerScreen<TerritoryTableMenu> {

    public static final int TAB_MAP = 0;
    public static final int TAB_FACTION = 1;
    /** Permissions for admin territories. Only ever shown to operators. */
    public static final int TAB_PERMS = 2;

    private static final int T_PERSONAL = EasyFactionsBridge.TYPE_PERSONAL;
    private static final int T_FACTION = EasyFactionsBridge.TYPE_FACTION;
    private static final int T_ADMIN = EasyFactionsBridge.TYPE_ADMIN;
    private static final int T_CHILD = EasyFactionsBridge.TYPE_CHILD;

    private static final int PANEL_BG = 0xF0140F0A;
    private static final int OUTLINE_DARK = 0xFF120D08;
    private static final int OUTLINE_GOLD = 0xFF8A6A3C;
    private static final int TITLE_GOLD = 0xFFE6C87A;
    private static final int TEXT_DIM = 0xFFB7A98C;

    private static final int[] SPANS = {8, 12, 16, 24, 32, 48};
    private static final int PAN_MARGIN = 8;

    /** Last zoom span the player viewed, mirrored onto the table's floating BlockEntity map. */
    public static int savedFloatSpan = 16;

    private static final int[] PRESET_COLORS = {
            0xE6C87A, 0xCC5555, 0x55CC55, 0x5577CC, 0xC056C0, 0x44C2C2, 0xD2812B, 0xECECEC
    };

    private static final int A_FILL = 0x88000000;
    private static final int A_ADD = 0xAA40C040;
    private static final int A_REMOVE = 0x99CC4040;
    /** Parent land while a plot is being drawn inside it: still visible, clearly not what you are editing. */
    private static final int A_GREYED = 0x55000000;
    /** A child plot drawn over its parent, in the plot's own colour. */
    private static final int A_CHILD = 0x77000000;

    /** Half-brightness version of a colour, for land that is greyed out rather than hidden. */
    private static int dim(int rgb) {
        int r = ((rgb >> 16) & 0xFF) / 2, gr = ((rgb >> 8) & 0xFF) / 2, b = (rgb & 0xFF) / 2;
        return (r << 16) | (gr << 8) | b;
    }

    private int tab = TAB_MAP;

    // layout (responsive, computed in init)
    private int mapXoff, mapYoff, mapPx, ctrlXoff, ctrlW;

    // claim state
    private int claimType = T_PERSONAL;
    private final Set<Long> mineSet = new HashSet<>();
    private final Set<Long> forbidden = new HashSet<>();
    private final Set<Long> stagedAdd = new HashSet<>();
    private final Set<Long> stagedRemove = new HashSet<>();
    private final List<Cluster> clusters = new ArrayList<>();
    private TerritoryDataS2C data;
    private boolean initialized = false;
    private String lastTypedName = null;
    /** Admin territory name typed but not yet committed, kept across the server's data refreshes. */
    private String lastTypedAdminName = null;
    /** Child plot name typed but not yet committed. Names the plot being drawn or edited. */
    private String lastTypedChildName = null;
    /**
     * Border colour for the NEXT admin territory painted. Unlike personal/faction colours (which recolour
     * everything that owner holds the moment a swatch is clicked) this is staged and applied only to the
     * chunks committed with it, so separate admin regions can each keep their own colour.
     */
    private int adminColor = EasyFactionsBridge.ADMIN_COLOR;

    // view + buffer
    private int zoom = 2;
    private int tableChunkX, tableChunkZ;
    private double viewCenterX, viewCenterZ;
    private int bufLeftX, bufLeftZ, bufSpan, bufRadius, texSize;
    private DynamicTexture mapTex;
    private ResourceLocation mapTexId;

    // drag + brush (hold to arm, then drag to paint claims / relinquish)
    private static final long BRUSH_ARM_MS = 750L;
    private boolean dragging, panned, painting, paintErase;
    private double dragStartMouseX, dragStartMouseY, dragStartViewX, dragStartViewZ;
    private long pressStartMillis;
    private int pressChunkX, pressChunkZ;

    private EditBox nameField;

    // permissions tab (operators only): a scrolling list of admin territories on the left, the selected
    // territory's switches and members on the right. Both panes are drawn by hand and clipped to their
    // pane, so a server with two hundred territories or a long member list can never spill out of the panel.
    private static final int ROW_H = 14;
    private int permListX, permListY, permListW, permListH, permDetX, permDetW;
    private int permListScroll, permDetScroll;
    private String selectedZone = "";
    private EditBox memberField;
    /** Row hitboxes rebuilt every frame, so a click always tests exactly what the player can see. */
    private final List<Hit> permHits = new ArrayList<>();

    /** One clickable region in a scrolling pane: {@code kind} says what a click on it does. */
    private record Hit(int x0, int y0, int x1, int y1, int kind, String arg, int index) {}

    private static final int HIT_ZONE = 0;      // select a territory
    private static final int HIT_PERM = 1;      // flip one permission switch
    private static final int HIT_MEMBER = 2;    // remove a trusted player

    // faction tab
    private static final String[] REL_STATUS = {"FRIENDLY", "NEUTRAL", "HOSTILE"};
    private FactionInfoS2C factionInfo;
    private int factionSubTab = 0;   // 0 Members, 1 Invites, 2 Relations, 3 Options
    private boolean pendingLeave, pendingDisband;   // two-click "are you sure?" guards
    private EditBox factionArg;
    private Button relCycleButton;
    private int relIndex = 1;

    private record Cluster(double cx, double cz, String label) {}

    public TerritoryTableScreen(TerritoryTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.tableChunkX = menu.pos.getX() >> 4;
        this.tableChunkZ = menu.pos.getZ() >> 4;
    }

    @Override
    protected void init() {
        int avail = Math.min(this.width - 40, this.height - 40);
        this.ctrlW = 154;
        this.mapPx = Math.max(176, Math.min(avail - ctrlW - 30, 432));
        this.mapXoff = 8;
        this.mapYoff = 48;
        this.ctrlXoff = mapXoff + mapPx + 10;
        // floor the panel width so the (full-width) Faction tab always has room for its rows + buttons
        this.imageWidth = Math.max(ctrlXoff + ctrlW + 8, 384);
        this.imageHeight = mapYoff + mapPx + 8;
        super.init();

        if (!initialized) {
            initialized = true;
            viewCenterX = tableChunkX + 0.5;
            viewCenterZ = tableChunkZ + 0.5;
        }
        rebuildView((int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ));
        relayout();
    }

    private boolean faction() { return claimType == T_FACTION; }
    private boolean admin() { return claimType == T_ADMIN; }
    private boolean child() { return claimType == T_CHILD; }
    /** Admin-side modes share the "no cap, no contiguity" rules and the staged colour. */
    private boolean adminSide() { return admin() || child(); }

    // ---- server data ----------------------------------------------------------------------------

    public void acceptData(TerritoryDataS2C msg) {
        // relayout() rebuilds the box, so stash whatever is half-typed or the refresh eats it
        if (tab == TAB_MAP && nameField != null) {
            if (claimType == T_PERSONAL) lastTypedName = nameField.getValue();
            else if (claimType == T_ADMIN) lastTypedAdminName = nameField.getValue();
            else if (claimType == T_CHILD) lastTypedChildName = nameField.getValue();
        }
        this.data = msg;
        // if a type is selected the player is no longer eligible for, fall back to something they can use
        if (adminSide() && !msg.canAdminClaim()) claimType = firstAllowedType(msg);
        if (claimType == T_PERSONAL && !msg.canPersonalClaim()) claimType = firstAllowedType(msg);
        if (tab == TAB_PERMS && !msg.canAdminClaim()) tab = TAB_MAP;
        if (selectedZone.isEmpty() && !msg.adminZones().isEmpty()) selectedZone = msg.adminZones().get(0).name();
        recomputeSets();
        relayout();
    }

    /** The first claim type this player is actually allowed to use, so the GUI never sits on a dead mode. */
    private int firstAllowedType(TerritoryDataS2C msg) {
        if (msg.canPersonalClaim()) return T_PERSONAL;
        return T_FACTION;
    }

    private void recomputeSets() {
        mineSet.clear();
        forbidden.clear();
        clusters.clear();
        if (data == null) return;
        if (child()) {
            recomputeChildSets();
            computeClusters();
            return;
        }
        int mineKind = admin() ? EasyFactionsBridge.KIND_ADMIN
                : (faction() ? EasyFactionsBridge.KIND_MINE_FACTION : EasyFactionsBridge.KIND_MINE_CORE);
        for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
            long key = ChunkPos.asLong(e.x(), e.z());
            if (e.kind() == mineKind) mineSet.add(key);
            else forbidden.add(key);
        }
        stagedAdd.removeIf(k -> mineSet.contains(k) || forbidden.contains(k));
        stagedRemove.removeIf(k -> !mineSet.contains(k));
        computeClusters();
    }

    /**
     * Child mode: what counts as "mine" is the plot currently being named, and everything that is not free
     * ground inside its parent is off limits.
     *
     * A plot lives inside exactly one parent. The parent is the one this plot already occupies, or — for a
     * plot being drawn for the first time — the one under the chunk painted first. Every chunk of any other
     * territory is forbidden, which is what makes "a child can only be inside its parent" something the
     * player can see rather than an error message after the fact.
     */
    private void recomputeChildSets() {
        String plot = currentChildName();
        String parent = childParentLabel(plot);

        for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
            long key = ChunkPos.asLong(e.x(), e.z());
            String childName = label(e.childIdx());
            if (!plot.isEmpty() && childName.equals(plot)) {
                mineSet.add(key);
                continue;
            }
            boolean freeGroundInParent = e.kind() == EasyFactionsBridge.KIND_ADMIN
                    && childName.isEmpty()
                    && (parent.isEmpty() || label(e.ownerIdx()).equals(parent));
            if (!freeGroundInParent) forbidden.add(key);
        }
        // chunks with no claim at all are not in the list, and painting one is meaningless in child mode
        stagedAdd.removeIf(k -> mineSet.contains(k) || forbidden.contains(k) || !claimed(k));
        stagedRemove.removeIf(k -> !mineSet.contains(k));
    }

    private boolean claimed(long key) {
        for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
            if (ChunkPos.asLong(e.x(), e.z()) == key) return true;
        }
        return false;
    }

    private String label(int idx) {
        return idx >= 0 && idx < data.owners().size() ? data.owners().get(idx) : "";
    }

    private String currentChildName() {
        if (nameField != null && child()) return nameField.getValue().strip();
        return lastTypedChildName != null ? lastTypedChildName.strip() : "";
    }

    /** The parent territory a plot belongs to: where it already sits, else where its first staged chunk is. */
    private String childParentLabel(String plot) {
        for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
            if (!plot.isEmpty() && label(e.childIdx()).equals(plot)) return label(e.ownerIdx());
        }
        for (long staged : stagedAdd) {
            for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
                if (ChunkPos.asLong(e.x(), e.z()) == staged) return label(e.ownerIdx());
            }
        }
        return "";
    }

    private void computeClusters() {
        if (data == null) return;
        Map<Long, TerritoryDataS2C.ClaimEntry> byPos = new HashMap<>();
        for (TerritoryDataS2C.ClaimEntry e : data.claims()) byPos.put(ChunkPos.asLong(e.x(), e.z()), e);
        Set<Long> seen = new HashSet<>();
        for (TerritoryDataS2C.ClaimEntry start : data.claims()) {
            long sk = ChunkPos.asLong(start.x(), start.z());
            if (seen.contains(sk)) continue;
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(sk);
            seen.add(sk);
            double sumX = 0, sumZ = 0;
            int count = 0;
            while (!q.isEmpty()) {
                long c = q.poll();
                int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                sumX += cx;
                sumZ += cz;
                count++;
                for (long n : new long[]{ChunkPos.asLong(cx + 1, cz), ChunkPos.asLong(cx - 1, cz),
                        ChunkPos.asLong(cx, cz + 1), ChunkPos.asLong(cx, cz - 1)}) {
                    TerritoryDataS2C.ClaimEntry ne = byPos.get(n);
                    if (ne != null && ne.ownerIdx() == start.ownerIdx() && seen.add(n)) q.add(n);
                }
            }
            String label = start.ownerIdx() >= 0 && start.ownerIdx() < data.owners().size()
                    ? data.owners().get(start.ownerIdx()) : "";
            clusters.add(new Cluster(sumX / count + 0.5, sumZ / count + 0.5, label));
        }
    }

    // ---- widgets --------------------------------------------------------------------------------

    private void relayout() {
        clearWidgets();
        int x = leftPos, y = topPos;
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.tab.map"), b -> selectTab(TAB_MAP))
                .bounds(x + 6, y + 28, 74, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.tab.faction"), b -> selectTab(TAB_FACTION))
                .bounds(x + 84, y + 28, 74, 16).build());
        // the permissions tab exists only for operators, and only once there is something to administer
        if (data != null && data.canAdminClaim()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.territory.tab.perms"), b -> selectTab(TAB_PERMS))
                    .bounds(x + 162, y + 28, 74, 16).build());
        }
        if (tab == TAB_MAP) buildMapWidgets(x, y);
        else if (tab == TAB_PERMS) buildPermWidgets(x, y);
        else buildFactionWidgets(x, y);
    }

    /**
     * The permissions page: a fixed two-pane frame with everything that can grow put inside a scrolling
     * pane. Only the "add a player" row is a real widget, and it sits in a reserved slot at the bottom of
     * the details pane where it cannot be scrolled away from or overlapped.
     */
    private void buildPermWidgets(int x, int y) {
        permListX = x + 8;
        permListY = y + 50;
        permListW = 132;
        permListH = imageHeight - 58;
        permDetX = permListX + permListW + 8;
        permDetW = imageWidth - (permDetX - x) - 8;

        int fieldY = y + imageHeight - 24;
        memberField = new EditBox(font, permDetX, fieldY, permDetW - 56, 16,
                Component.translatable("gui.territory.perm.member_hint"));
        memberField.setMaxLength(16);
        memberField.setHint(Component.translatable("gui.territory.perm.member_hint"));
        addRenderableWidget(memberField);
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.perm.add"),
                        b -> sendMember(memberField.getValue(), true))
                .bounds(permDetX + permDetW - 52, fieldY, 52, 16).build());
    }

    private void buildMapWidgets(int x, int y) {
        int cx = x + ctrlXoff, cy = y + mapYoff;
        String typeKey = child() ? "gui.territory.type.child"
                : admin() ? "gui.territory.type.admin"
                : (faction() ? "gui.territory.type.faction" : "gui.territory.type.personal");
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.territory.type", Component.translatable(typeKey)), b -> cycleType())
                .bounds(cx, cy, ctrlW, 16).build());

        nameField = new EditBox(font, cx, cy + 46, ctrlW, 16, Component.empty());
        nameField.setMaxLength(48);
        if (faction()) {
            nameField.setValue(data != null ? data.factionName() : "");
            nameField.setEditable(false);
        } else if (admin()) {
            // named BEFORE painting: whatever is in this box labels the chunks committed with it
            nameField.setValue(lastTypedAdminName != null ? lastTypedAdminName : "");
            nameField.setEditable(true);
        } else if (child()) {
            // the plot's name. Typing an existing plot's name edits that plot instead of starting a new one.
            nameField.setValue(lastTypedChildName != null ? lastTypedChildName : "");
            nameField.setEditable(true);
            nameField.setResponder(v -> {
                lastTypedChildName = v;
                recomputeSets();
            });
        } else {
            nameField.setValue(lastTypedName != null ? lastTypedName : (data != null ? data.personalName() : ""));
            nameField.setEditable(true);
        }
        addRenderableWidget(nameField);

        int zoomY = cy + 128;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> changeZoom(1))
                .bounds(cx, zoomY, 24, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> changeZoom(-1))
                .bounds(cx + ctrlW - 24, zoomY, 24, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.done"), b -> commit())
                .bounds(cx, y + mapYoff + mapPx - 20, ctrlW, 18).build());
    }

    private void selectTab(int which) {
        this.tab = which;
        pendingLeave = pendingDisband = false;
        permListScroll = permDetScroll = 0;
        relayout();
        if (which == TAB_FACTION) PacketDistributor.sendToServer(new FactionInfoRequestC2S(menu.pos));
        else requestData();   // the permissions tab reads the same payload as the map
    }

    /**
     * Cycle Personal -> Faction -> (Admin, Plot if operator) -> Personal.
     *
     * Personal is dropped from the cycle for a faction LEADER: his personal claims became the faction's when
     * he founded it, so offering him a mode that always refuses would just be a button that does nothing.
     */
    private void cycleType() {
        List<Integer> types = new ArrayList<>();
        if (data == null || data.canPersonalClaim()) types.add(T_PERSONAL);
        types.add(T_FACTION);
        if (data != null && data.canAdminClaim()) {
            types.add(T_ADMIN);
            types.add(T_CHILD);
        }
        int idx = types.indexOf(claimType);
        claimType = types.get((idx + 1) % types.size());
        stagedAdd.clear();
        stagedRemove.clear();
        lastTypedName = null;
        recomputeSets();
        relayout();
    }

    private void changeZoom(int delta) {
        int next = Math.max(0, Math.min(SPANS.length - 1, zoom + delta));
        if (next == zoom) return;
        zoom = next;
        ensureCoverage();
    }

    // ---- view / buffer / data -------------------------------------------------------------------

    private int neededRadius() {
        return Math.min(TerritoryRequestC2S.MAX_RADIUS, SPANS[zoom] / 2 + PAN_MARGIN);
    }

    private void requestData() {
        PacketDistributor.sendToServer(new TerritoryRequestC2S(
                (int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ), bufRadius));
    }

    private void rebuildView(int centerChunkX, int centerChunkZ) {
        Level level = minecraft != null ? minecraft.level : null;
        if (level == null) return;
        bufRadius = neededRadius();
        bufSpan = 2 * bufRadius + 1;
        bufLeftX = centerChunkX - bufRadius;
        bufLeftZ = centerChunkZ - bufRadius;
        texSize = bufSpan * 16;

        releaseMapTexture();
        NativeImage img = MinimapSampler.sample(level, bufLeftX, bufLeftZ, bufSpan);
        mapTex = new DynamicTexture(img);
        mapTexId = minecraft.getTextureManager().register("territory_minimap", mapTex);

        PacketDistributor.sendToServer(new TerritoryRequestC2S(centerChunkX, centerChunkZ, bufRadius));
    }

    private void ensureCoverage() {
        double half = SPANS[zoom] / 2.0;
        boolean inside = viewCenterX - half >= bufLeftX + 0.5
                && viewCenterX + half <= bufLeftX + bufSpan - 0.5
                && viewCenterZ - half >= bufLeftZ + 0.5
                && viewCenterZ + half <= bufLeftZ + bufSpan - 0.5
                && bufRadius >= neededRadius();
        if (!inside) rebuildView((int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ));
    }

    private void releaseMapTexture() {
        if (mapTexId != null && minecraft != null) {
            minecraft.getTextureManager().release(mapTexId);
            mapTexId = null;
        }
        if (mapTex != null) {
            mapTex.close();
            mapTex = null;
        }
    }

    @Override
    public void removed() {
        super.removed();
        releaseMapTexture();
        savedFloatSpan = SPANS[zoom];   // the table's floating map mirrors your last zoom
    }

    // ---- interaction ----------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (tab == TAB_PERMS && button == 0 && data != null) {
            // hit-test the rows drawn this frame, so a click can only ever land on something visible
            for (Hit hit : permHits) {
                if (mx < hit.x0() || mx >= hit.x1() || my < hit.y0() || my >= hit.y1()) continue;
                switch (hit.kind()) {
                    case HIT_ZONE -> {
                        selectedZone = hit.arg();
                        permDetScroll = 0;
                    }
                    case HIT_PERM -> {
                        TerritoryDataS2C.AdminZone zone = zoneByName(hit.arg());
                        if (zone != null) {
                            sendPerm(hit.arg(), hit.index(),
                                    !AdminPerm.values()[hit.index()].allowedIn(zone.perms()));
                        }
                    }
                    case HIT_MEMBER -> sendMember(hit.arg(), false);
                    default -> { }
                }
                return true;
            }
        }
        if (tab == TAB_MAP && button == 0) {
            if (data != null) {
                int sw = swatchHit(mx, my);
                if (sw >= 0) {
                    if (adminSide()) {
                        // staged, not sent: it applies to the chunks this admin commits next, so two admin
                        // regions can differ. Recolouring live would repaint every admin claim on the server.
                        adminColor = PRESET_COLORS[sw];
                    } else {
                        PacketDistributor.sendToServer(new TerritoryColorC2S(PRESET_COLORS[sw], faction(),
                                (int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ), bufRadius));
                    }
                    return true;
                }
            }
            if (overMap(mx, my)) {
                dragging = true;
                panned = false;
                painting = false;
                dragStartMouseX = mx;
                dragStartMouseY = my;
                dragStartViewX = viewCenterX;
                dragStartViewZ = viewCenterZ;
                pressStartMillis = Util.getMillis();
                pressChunkX = chunkXAt(mx);
                pressChunkZ = chunkZAt(my);
                // brush direction: started on your own land -> relinquish; otherwise -> claim
                paintErase = data != null && mineSet.contains(ChunkPos.asLong(pressChunkX, pressChunkZ));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            if (painting) {                 // brush armed: drag paints chunks
                paintAt(mx, my);
                return true;
            }
            double totalX = mx - dragStartMouseX, totalY = my - dragStartMouseY;
            if (Math.abs(totalX) > 3 || Math.abs(totalY) > 3) panned = true;   // moved before arming -> pan
            float cell = (float) mapPx / SPANS[zoom];
            viewCenterX = dragStartViewX - totalX / cell;
            viewCenterZ = dragStartViewZ - totalY / cell;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging && button == 0) {
            boolean wasPainting = painting;
            dragging = false;
            painting = false;
            if (!panned && !wasPainting && data != null && overMap(mx, my)) {
                toggleChunk(pressChunkX, pressChunkZ);   // quick tap = single chunk
            }
            ensureCoverage();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private int chunkXAt(double mx) {
        float cell = (float) mapPx / SPANS[zoom];
        double leftX = viewCenterX - SPANS[zoom] / 2.0;
        return (int) Math.floor(leftX + (mx - (leftPos + mapXoff)) / cell);
    }

    private int chunkZAt(double my) {
        float cell = (float) mapPx / SPANS[zoom];
        double topZ = viewCenterZ - SPANS[zoom] / 2.0;
        return (int) Math.floor(topZ + (my - (topPos + mapYoff)) / cell);
    }

    private void paintAt(double mx, double my) {
        if (!overMap(mx, my) || data == null) return;
        paintChunk(chunkXAt(mx), chunkZAt(my));
    }

    /** Brush a single chunk: claim it (claim brush) or stage its release (erase brush). No message spam. */
    private void paintChunk(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        if (paintErase) {
            if (stagedAdd.contains(key)) stagedAdd.remove(key);
            else if (mineSet.contains(key)) stagedRemove.add(key);
        } else {
            if (forbidden.contains(key)) return;
            if (stagedAdd.contains(key)) return;
            if (mineSet.contains(key)) {                 // re-claim a chunk staged for removal
                stagedRemove.remove(key);
                return;
            }
            tryStageAdd(cx, cz);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (tab == TAB_MAP && overMap(mx, my) && sy != 0) {
            changeZoom(sy > 0 ? -1 : 1);
            return true;
        }
        if (tab == TAB_PERMS && sy != 0) {
            int step = (int) (-sy * ROW_H);
            if (mx >= permListX - 2 && mx < permListX + permListW + 2) {
                permListScroll = Math.max(0, permListScroll + step);
                return true;
            }
            if (mx >= permDetX - 2 && mx < permDetX + permDetW + 2) {
                permDetScroll = Math.max(0, permDetScroll + step);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private TerritoryDataS2C.AdminZone zoneByName(String name) {
        if (data == null) return null;
        for (TerritoryDataS2C.AdminZone z : data.adminZones()) {
            if (z.name().equals(name)) return z;
        }
        return null;
    }

    private boolean isSelected(long key) {
        return (mineSet.contains(key) && !stagedRemove.contains(key)) || stagedAdd.contains(key);
    }

    private boolean selectionEmpty() {
        if (!stagedAdd.isEmpty()) return false;
        for (long k : mineSet) if (!stagedRemove.contains(k)) return false;
        return true;
    }

    private int selectionCount() {
        return (mineSet.size() - stagedRemove.size()) + stagedAdd.size();
    }

    private void toggleChunk(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        if (forbidden.contains(key)) return;
        if (stagedAdd.contains(key)) {
            stagedAdd.remove(key);
            return;
        }
        if (mineSet.contains(key)) {
            if (stagedRemove.contains(key)) stagedRemove.remove(key);
            else stagedRemove.add(key);
            return;
        }
        tryStageAdd(cx, cz, true);   // unclaimed chunk: stage a new claim (announce failures on a tap)
    }

    /** Stage a claim on an unclaimed chunk, honouring contiguity + cap (admin skips both). */
    private boolean tryStageAdd(int cx, int cz) {
        return tryStageAdd(cx, cz, false);
    }

    private boolean tryStageAdd(int cx, int cz, boolean announce) {
        if (!adminSide()) {
            if (!selectionEmpty() && !touchesSelected(cx, cz)) {
                if (announce) messageActionBar("gui.territory.must_connect");
                return false;
            }
            int cap = faction() ? data.factionCap() : data.coreCap();
            int worldUsed = faction() ? data.factionUsed() : data.coreUsed();
            int projected = worldUsed - stagedRemove.size() + stagedAdd.size() + 1;
            if (cap > 0 && projected > cap) {
                if (announce) messageActionBar("gui.territory.cap_reached");
                return false;
            }
        }
        stagedAdd.add(ChunkPos.asLong(cx, cz));
        return true;
    }

    private boolean touchesSelected(int cx, int cz) {
        return isSelected(ChunkPos.asLong(cx + 1, cz)) || isSelected(ChunkPos.asLong(cx - 1, cz))
                || isSelected(ChunkPos.asLong(cx, cz + 1)) || isSelected(ChunkPos.asLong(cx, cz - 1));
    }

    private void messageActionBar(String key) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    private void commit() {
        if (data == null) return;
        List<Long> add = new ArrayList<>(stagedAdd);
        List<Long> remove = new ArrayList<>(stagedRemove);
        // personal claims carry the player's territory name; admin claims and plots carry their own
        String name = (nameField == null || claimType == T_FACTION) ? "" : nameField.getValue();
        boolean nameChanged = claimType == T_PERSONAL && nameField != null && !name.equals(data.personalName());
        if (add.isEmpty() && remove.isEmpty() && !nameChanged) return;
        int color = adminSide() ? adminColor : -1;
        PacketDistributor.sendToServer(new TerritoryCommitC2S(claimType, add, remove, name, color,
                (int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ), bufRadius));
    }

    private boolean overMap(double mx, double my) {
        return mx >= leftPos + mapXoff && mx < leftPos + mapXoff + mapPx
                && my >= topPos + mapYoff && my < topPos + mapYoff + mapPx;
    }

    private int swatchHit(double mx, double my) {
        int cx = leftPos + ctrlXoff, cy = topPos + mapYoff + 82;
        int sw = 18, gap = 4;
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            int col = i % 4, row = i / 4;
            int sx = cx + col * (sw + gap), sy = cy + row * (sw + gap);
            if (mx >= sx && mx < sx + sw && my >= sy && my < sy + sw) return i;
        }
        return -1;
    }

    // ---- keyboard -------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (getFocused() instanceof EditBox box) {
            if (box.keyPressed(key, scan, mods)) return true;
            if (key != 256) return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (getFocused() instanceof EditBox box && box.charTyped(c, mods)) return true;
        return super.charTyped(c, mods);
    }

    // ---- rendering ------------------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL_BG);
        g.renderOutline(x, y, imageWidth, imageHeight, OUTLINE_DARK);
        g.renderOutline(x + 1, y + 1, imageWidth - 2, imageHeight - 2, OUTLINE_GOLD);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        String t = title.getString();
        int tw = font.width(t);
        float s = 1.4f;
        float cxp = imageWidth / 2f;
        g.pose().pushPose();
        g.pose().translate(cxp, 9f, 0f);
        g.pose().scale(s, s, 1f);
        g.drawString(font, t, -tw / 2, 0, TITLE_GOLD, true);
        g.pose().popPose();

        int cx = Math.round(cxp);
        int half = Math.round(tw * s / 2f) + 7;
        int uy = 23;
        g.fill(cx - half, uy, cx + half, uy + 1, OUTLINE_GOLD);
        g.fill(cx - half - 3, uy - 1, cx - half, uy + 2, TITLE_GOLD);
        g.fill(cx + half, uy - 1, cx + half + 3, uy + 2, TITLE_GOLD);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (tab == TAB_MAP) ensureCoverage();
        super.render(g, mouseX, mouseY, partialTick);
        if (tab == TAB_MAP) {
            renderMapPage(g);
            renderBrush(g, mouseX, mouseY);
        } else if (tab == TAB_PERMS) {
            renderPermsPage(g, mouseX, mouseY);
        } else {
            renderFactionPage(g);
        }
    }

    // ---- permissions page -------------------------------------------------------------------------

    /**
     * Two panes: the admin territories on the left, the selected one's switches and trusted players on the
     * right. Everything that can grow lives inside a scissored, scrolling pane with a scrollbar, so a
     * hundred plots or a long member list scroll rather than run off the panel.
     */
    private void renderPermsPage(GuiGraphics g, int mouseX, int mouseY) {
        permHits.clear();
        if (data == null) return;

        List<TerritoryDataS2C.AdminZone> zones = data.adminZones();
        if (zones.isEmpty()) {
            g.drawString(font, Component.translatable("gui.territory.perm.none"),
                    permListX, permListY + 4, TEXT_DIM, false);
            return;
        }
        if (zones.stream().noneMatch(z -> z.name().equals(selectedZone))) selectedZone = zones.get(0).name();

        g.drawString(font, Component.translatable("gui.territory.perm.territories"),
                permListX, permListY - 11, TEXT_DIM, false);
        renderZoneList(g, zones);
        renderZoneDetail(g, zones);
    }

    private void renderZoneList(GuiGraphics g, List<TerritoryDataS2C.AdminZone> zones) {
        int contentH = zones.size() * ROW_H;
        permListScroll = clampScroll(permListScroll, contentH, permListH);

        g.fill(permListX - 2, permListY - 2, permListX + permListW + 2, permListY + permListH + 2, 0x50000000);
        g.renderOutline(permListX - 2, permListY - 2, permListW + 4, permListH + 4, OUTLINE_DARK);

        g.enableScissor(permListX, permListY, permListX + permListW, permListY + permListH);
        int y = permListY - permListScroll;
        for (TerritoryDataS2C.AdminZone z : zones) {
            if (y + ROW_H >= permListY && y <= permListY + permListH) {
                boolean selected = z.name().equals(selectedZone);
                if (selected) g.fill(permListX, y, permListX + permListW, y + ROW_H - 1, 0x556A4A1C);
                int indent = z.parent().isEmpty() ? 0 : 8;
                g.fill(permListX + indent + 1, y + 3, permListX + indent + 6, y + ROW_H - 4,
                        0xFF000000 | z.color());
                String text = trim(z.name().isEmpty() ? I18nAdmin() : z.name(), permListW - indent - 34);
                g.drawString(font, text, permListX + indent + 10, y + 3, selected ? TITLE_GOLD : 0xFFDDDDDD, false);
                g.drawString(font, String.valueOf(z.chunks()), permListX + permListW - 20, y + 3, TEXT_DIM, false);
                permHits.add(new Hit(permListX, y, permListX + permListW, y + ROW_H, HIT_ZONE, z.name(), 0));
            }
            y += ROW_H;
        }
        g.disableScissor();
        drawScrollbar(g, permListX + permListW - 2, permListY, permListH, contentH, permListScroll);
    }

    private void renderZoneDetail(GuiGraphics g, List<TerritoryDataS2C.AdminZone> zones) {
        TerritoryDataS2C.AdminZone zone = null;
        for (TerritoryDataS2C.AdminZone z : zones) {
            if (z.name().equals(selectedZone)) zone = z;
        }
        if (zone == null) return;

        // fixed header: never scrolls, so you always know which territory you are editing
        String header = zone.name().isEmpty() ? I18nAdmin() : zone.name();
        g.drawString(font, Component.literal(header).withStyle(net.minecraft.ChatFormatting.BOLD),
                permDetX, permListY - 11, TITLE_GOLD, false);
        if (!zone.parent().isEmpty()) {
            int w = font.width(header) + 6;
            g.drawString(font, Component.translatable("gui.territory.perm.child_of", zone.parent()),
                    permDetX + w, permListY - 11, TEXT_DIM, false);
        }

        // the scrolling body stops short of the "add player" row reserved at the bottom of the panel
        int bodyH = permListH - 22;
        int bodyBottom = permListY + bodyH;
        int contentH = permContentHeight(zone);
        permDetScroll = clampScroll(permDetScroll, contentH, bodyH);

        g.fill(permDetX - 2, permListY - 2, permDetX + permDetW + 2, bodyBottom + 2, 0x50000000);
        g.renderOutline(permDetX - 2, permListY - 2, permDetW + 4, bodyH + 4, OUTLINE_DARK);
        g.enableScissor(permDetX, permListY, permDetX + permDetW, bodyBottom);

        int y = permListY - permDetScroll;
        g.drawString(font, Component.translatable(zone.custom()
                ? "gui.territory.perm.custom" : "gui.territory.perm.inherited"), permDetX + 2, y + 2, TEXT_DIM, false);
        y += ROW_H + 2;

        AdminPerm[] perms = AdminPerm.values();
        for (int i = 0; i < perms.length; i++) {
            if (y + ROW_H >= permListY && y <= bodyBottom) {
                boolean on = perms[i].allowedIn(zone.perms());
                g.drawString(font, Component.translatable(perms[i].langKey()), permDetX + 4, y + 3, 0xFFDDDDDD, false);
                drawToggle(g, permDetX + permDetW - 40, y + 1, on);
                permHits.add(new Hit(permDetX, y, permDetX + permDetW, y + ROW_H, HIT_PERM, zone.name(), i));
            }
            y += ROW_H;
        }

        y += 4;
        if (y + ROW_H >= permListY && y <= bodyBottom) {
            g.drawString(font, Component.translatable("gui.territory.perm.members", zone.members().size()),
                    permDetX + 2, y + 3, TEXT_DIM, false);
        }
        y += ROW_H;
        for (String member : zone.members()) {
            if (y + ROW_H >= permListY && y <= bodyBottom) {
                g.drawString(font, trim(member, permDetW - 40), permDetX + 8, y + 3, 0xFFDDDDDD, false);
                g.drawString(font, Component.translatable("gui.territory.perm.remove"),
                        permDetX + permDetW - 30, y + 3, 0xFFCC6666, false);
                permHits.add(new Hit(permDetX + permDetW - 34, y, permDetX + permDetW, y + ROW_H,
                        HIT_MEMBER, member, 0));
            }
            y += ROW_H;
        }
        if (zone.members().isEmpty() && y - ROW_H + ROW_H >= permListY) {
            g.drawString(font, Component.translatable("gui.territory.perm.no_members"),
                    permDetX + 8, y - ROW_H + 3, TEXT_DIM, false);
        }

        g.disableScissor();
        drawScrollbar(g, permDetX + permDetW - 2, permListY, bodyH, contentH, permDetScroll);
    }

    private int permContentHeight(TerritoryDataS2C.AdminZone zone) {
        int rows = 1 + AdminPerm.values().length + 1 + Math.max(1, zone.members().size());
        return rows * ROW_H + 8;
    }

    private void drawToggle(GuiGraphics g, int x, int y, boolean on) {
        int w = 34, h = ROW_H - 3;
        g.fill(x, y, x + w, y + h, on ? 0xFF2E6B2E : 0xFF5A2626);
        g.renderOutline(x, y, w, h, on ? 0xFF6ED06E : 0xFFD06E6E);
        Component label = Component.translatable(on ? "gui.territory.on" : "gui.territory.off");
        g.drawCenteredString(font, label, x + w / 2, y + 2, on ? 0xFFCFF0CF : 0xFFF0CFCF);
    }

    private void drawScrollbar(GuiGraphics g, int x, int y, int viewH, int contentH, int scroll) {
        if (contentH <= viewH) return;
        int barH = Math.max(12, viewH * viewH / contentH);
        int maxScroll = contentH - viewH;
        int barY = y + (int) ((viewH - barH) * (scroll / (float) maxScroll));
        g.fill(x, y, x + 2, y + viewH, 0x40FFFFFF);
        g.fill(x, barY, x + 2, barY + barH, 0xFF8A6A3C);
    }

    private static int clampScroll(int scroll, int contentH, int viewH) {
        return Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        // truncate in place rather than letting a long name run under the count or off the pane
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(b.toString() + c + "...") > maxWidth) break;
            b.append(c);
        }
        return b + "...";
    }

    private String I18nAdmin() {
        return Component.translatable("gui.territory.type.admin").getString();
    }

    private void sendPerm(String territory, int index, boolean allowed) {
        PacketDistributor.sendToServer(new AdminActionC2S(AdminActionC2S.SET_PERM, territory,
                Integer.toString(index), allowed,
                (int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ), bufRadius));
    }

    private void sendMember(String name, boolean add) {
        if (name == null || name.isBlank()) return;
        PacketDistributor.sendToServer(new AdminActionC2S(
                add ? AdminActionC2S.ADD_MEMBER : AdminActionC2S.REMOVE_MEMBER, selectedZone, name.strip(), add,
                (int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ), bufRadius));
        if (add && memberField != null) memberField.setValue("");
    }

    /** Hold-to-arm brush: counts down on the cursor, then a drag paints claims / relinquishes. */
    private void renderBrush(GuiGraphics g, int mouseX, int mouseY) {
        if (!dragging || panned || data == null) return;
        int claimColor = 0xFF66D066, eraseColor = 0xFFD06666;
        if (painting) {
            cursorLabel(g, mouseX, mouseY, Component.translatable(paintErase
                    ? "gui.territory.brush.releasing" : "gui.territory.brush.claiming"), paintErase ? eraseColor : claimColor);
            return;
        }
        if (!overMap(mouseX, mouseY)) return;
        long elapsed = Util.getMillis() - pressStartMillis;
        if (elapsed >= BRUSH_ARM_MS) {
            painting = true;
            paintAt(mouseX, mouseY);   // paint the chunk you armed on
            return;
        }
        int cd = Math.max(1, Math.min(3, (int) Math.ceil((BRUSH_ARM_MS - elapsed) / (BRUSH_ARM_MS / 3.0))));
        cursorLabel(g, mouseX, mouseY, Component.translatable(paintErase
                ? "gui.territory.brush.release" : "gui.territory.brush.claim", cd), paintErase ? eraseColor : claimColor);
    }

    private void cursorLabel(GuiGraphics g, int mouseX, int mouseY, Component text, int color) {
        int w = font.width(text);
        int tx = mouseX + 10, ty = mouseY - 4;
        g.fill(tx - 2, ty - 2, tx + w + 2, ty + 10, 0xCC000000);
        g.drawString(font, text, tx, ty, color, false);
    }

    private void renderMapPage(GuiGraphics g) {
        int mx0 = leftPos + mapXoff, my0 = topPos + mapYoff;
        g.fill(mx0 - 1, my0 - 1, mx0 + mapPx + 1, my0 + mapPx + 1, 0xFF000000);

        int span = SPANS[zoom];
        float cell = (float) mapPx / span;
        double leftX = viewCenterX - span / 2.0;
        double topZ = viewCenterZ - span / 2.0;

        if (mapTexId != null) {
            int uw = span * 16;
            float maxOff = Math.max(0, texSize - uw);
            float srcU = clamp((float) ((leftX - bufLeftX) * 16.0), 0, maxOff);
            float srcV = clamp((float) ((topZ - bufLeftZ) * 16.0), 0, maxOff);
            g.enableScissor(mx0, my0, mx0 + mapPx, my0 + mapPx);
            g.blit(mapTexId, mx0, my0, mapPx, mapPx, srcU, srcV, uw, uw, texSize, texSize);
            g.disableScissor();
        }
        if (data == null) {
            g.drawCenteredString(font, Component.translatable("gui.territory.syncing"),
                    mx0 + mapPx / 2, my0 + mapPx / 2 - 4, TEXT_DIM);
        }

        g.enableScissor(mx0, my0, mx0 + mapPx, my0 + mapPx);
        if (data != null) {
            boolean showPlots = data.canAdminClaim();
            for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
                long key = ChunkPos.asLong(e.x(), e.z());
                // in plot mode the parent territory recedes into the background so the plot being drawn
                // inside it is the thing you can actually read
                int fill = child() && !mineSet.contains(key)
                        ? A_GREYED | dim(e.color()) : A_FILL | (e.color() & 0xFFFFFF);
                drawCell(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, fill, false);
                // plots are drawn for operators only, and only here: they exist in no other map on the server
                if (showPlots && e.childIdx() >= 0 && !mineSet.contains(key)) {
                    drawCell(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0,
                            A_CHILD | (e.childColor() & 0xFFFFFF), true);
                }
                if (mineSet.contains(key)) {
                    if (stagedRemove.contains(key)) drawCell(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, A_REMOVE, true);
                    else outlineCell(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, TITLE_GOLD);
                }
            }
            for (long key : stagedAdd) {
                drawCell(g, ChunkPos.getX(key), ChunkPos.getZ(key), leftX, topZ, cell, mx0, my0, A_ADD, true);
            }
        }
        outlineCell(g, tableChunkX, tableChunkZ, leftX, topZ, cell, mx0, my0, 0xFFFFFFFF);

        for (Cluster c : clusters) {
            if (c.label.isEmpty()) continue;
            int lx = mx0 + (int) ((c.cx - leftX) * cell);
            int ly = my0 + (int) ((c.cz - topZ) * cell);
            if (lx < mx0 || lx > mx0 + mapPx || ly < my0 || ly > my0 + mapPx) continue;
            int w = font.width(c.label);
            g.fill(lx - w / 2 - 2, ly - 5, lx + w / 2 + 2, ly + 5, 0xAA000000);
            g.drawCenteredString(font, c.label, lx, ly - 4, 0xFFFFFFFF);
        }
        g.disableScissor();
        g.renderOutline(mx0 - 1, my0 - 1, mapPx + 2, mapPx + 2, OUTLINE_GOLD);

        renderMapControls(g);
    }

    private void renderMapControls(GuiGraphics g) {
        int cx = leftPos + ctrlXoff, y = topPos + mapYoff;
        if (data != null && !data.efLoaded()) {
            g.drawString(font, Component.translatable("gui.territory.no_ef"), cx, y + 22, 0xFFCC6666, false);
        } else if (child()) {
            String parent = data == null ? "" : childParentLabel(currentChildName());
            Component line = parent.isEmpty()
                    ? Component.translatable("gui.territory.child_pick_parent")
                    : Component.translatable("gui.territory.child_in", selectionCount(), parent);
            g.drawString(font, line, cx, y + 22, 0xFFC056C0, false);
        } else if (admin()) {
            g.drawString(font, Component.translatable("gui.territory.claims_admin", selectionCount()), cx, y + 22, 0xFFC056C0, false);
        } else if (data != null) {
            int cap = faction() ? data.factionCap() : data.coreCap();
            int worldUsed = faction() ? data.factionUsed() : data.coreUsed();
            int projected = worldUsed - stagedRemove.size() + stagedAdd.size();
            int color = (cap > 0 && projected > cap) ? 0xFFCC6666 : TEXT_DIM;
            g.drawString(font, Component.translatable("gui.territory.claims", projected, cap), cx, y + 22, color, false);
        }

        String nameKey = child() ? "gui.territory.name.child"
                : admin() ? "gui.territory.name.admin"
                : (faction() ? "gui.territory.name.faction" : "gui.territory.name.personal");
        g.drawString(font, Component.translatable(nameKey), cx, y + 36, TEXT_DIM, false);
        g.drawString(font, Component.translatable("gui.territory.border_color"), cx, y + 70, TEXT_DIM, false);
        int sy = y + 82, sw = 18, gap = 4;
        int current = data == null ? -1 : (adminSide() ? (adminColor & 0xFFFFFF)
                : ((faction() ? data.factionColor() : data.personalColor()) & 0xFFFFFF));
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            int col = i % 4, row = i / 4;
            int sx = cx + col * (sw + gap), yy = sy + row * (sw + gap);
            g.fill(sx, yy, sx + sw, yy + sw, 0xFF000000 | PRESET_COLORS[i]);
            g.renderOutline(sx, yy, sw, sw, PRESET_COLORS[i] == current ? 0xFFFFFFFF : OUTLINE_DARK);
        }
        g.drawCenteredString(font, Component.translatable("gui.territory.zoom", SPANS[zoom]),
                cx + ctrlW / 2, y + 132, TEXT_DIM);
    }

    private void drawCell(GuiGraphics g, int cx, int cz, double leftX, double topZ, float cell,
                          int mx0, int my0, int argb, boolean inset) {
        int px0 = mx0 + Math.round((float) ((cx - leftX) * cell));
        int pz0 = my0 + Math.round((float) ((cz - topZ) * cell));
        int px1 = mx0 + Math.round((float) ((cx + 1 - leftX) * cell));
        int pz1 = my0 + Math.round((float) ((cz + 1 - topZ) * cell));
        if (inset && px1 - px0 > 3) {
            px0++; pz0++; px1--; pz1--;
        }
        g.fill(px0, pz0, px1, pz1, argb);
    }

    private void outlineCell(GuiGraphics g, int cx, int cz, double leftX, double topZ, float cell,
                             int mx0, int my0, int color) {
        int px0 = mx0 + Math.round((float) ((cx - leftX) * cell));
        int pz0 = my0 + Math.round((float) ((cz - topZ) * cell));
        int px1 = mx0 + Math.round((float) ((cx + 1 - leftX) * cell));
        int pz1 = my0 + Math.round((float) ((cz + 1 - topZ) * cell));
        g.renderOutline(px0, pz0, px1 - px0, pz1 - pz0, color);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- faction tab ----------------------------------------------------------------------------

    public void acceptFactionInfo(FactionInfoS2C msg) {
        this.factionInfo = msg;
        if (tab == TAB_FACTION) relayout();
    }

    // faction-tab content spans the full panel width; all coords derive from these so nothing bleeds out
    private int innerL() { return leftPos + 10; }
    private int innerR() { return leftPos + imageWidth - 10; }

    private void buildFactionWidgets(int x, int y) {
        FactionInfoS2C fi = factionInfo;
        if (fi == null) return;
        int iL = innerL(), iR = innerR(), iW = iR - iL;

        if (!fi.inFaction()) {
            factionArg = new EditBox(font, iL, y + 70, iW, 18, Component.empty());
            factionArg.setMaxLength(48);
            factionArg.setHint(Component.translatable("gui.territory.faction.name_hint"));
            addRenderableWidget(factionArg);
            int halfW = (iW - 6) / 2;
            addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.create"),
                            b -> sendFactionAction(FactionActionC2S.CREATE, factionArg.getValue(), ""))
                    .bounds(iL, y + 92, halfW, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.join"),
                            b -> sendFactionAction(FactionActionC2S.JOIN, factionArg.getValue(), ""))
                    .bounds(iL + halfW + 6, y + 92, halfW, 18).build());
            return;
        }

        // sub-tab buttons span the full width
        String[] subKeys = {"gui.territory.faction.sub.members", "gui.territory.faction.sub.invites",
                "gui.territory.faction.sub.relations", "gui.territory.faction.sub.options"};
        int sbGap = 6, sbW = (iW - sbGap * (subKeys.length - 1)) / subKeys.length, sbY = y + 66;
        for (int i = 0; i < subKeys.length; i++) {
            int fi2 = i;
            Button b = Button.builder(Component.translatable(subKeys[i]),
                            btn -> { factionSubTab = fi2; pendingLeave = pendingDisband = false; relayout(); })
                    .bounds(iL + i * (sbW + sbGap), sbY, sbW, 16).build();
            b.active = factionSubTab != i;
            addRenderableWidget(b);
        }

        int contentY = y + 92;
        switch (factionSubTab) {
            case 1 -> buildInvitesTab(contentY, fi);
            case 2 -> buildRelationsTab(contentY, fi);
            case 3 -> buildOptionsTab(contentY, fi);
            default -> buildMembersTab(contentY, fi);
        }
    }

    private void buildMembersTab(int yStart, FactionInfoS2C fi) {
        int iR = innerR();
        int btnW = 78, gap = 4;
        int b2x = iR - btnW;             // promote/demote slot
        int b1x = b2x - gap - btnW;      // kick slot
        int rowH = 22, row = yStart + 4;
        int maxRows = Math.max(1, (topPos + imageHeight - 12 - row) / rowH);
        int shown = 0;
        for (FactionInfoS2C.Member m : fi.members()) {
            if (shown >= maxRows) break;
            boolean isOwnerRow = m.role() == 2;
            boolean canKick = !isOwnerRow && (fi.isOwner() || (fi.isOfficer() && m.role() == 0));
            if (canKick) {
                addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.kick"),
                                b -> sendFactionAction(FactionActionC2S.KICK, m.name(), ""))
                        .bounds(b1x, row, btnW, 18).build());
            }
            if (fi.isOwner() && !isOwnerRow) {
                if (m.role() == 0) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.promote"),
                                    b -> sendFactionAction(FactionActionC2S.ADD_OFFICER, m.name(), ""))
                            .bounds(b2x, row, btnW, 18).build());
                } else if (m.role() == 1) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.demote"),
                                    b -> sendFactionAction(FactionActionC2S.REMOVE_OFFICER, m.name(), ""))
                            .bounds(b2x, row, btnW, 18).build());
                }
            }
            row += rowH;
            shown++;
        }
    }

    private void buildInvitesTab(int yStart, FactionInfoS2C fi) {
        if (!(fi.isOwner() || fi.isOfficer())) return;
        int iL = innerL(), iR = innerR();
        int btnW = 84;
        factionArg = new EditBox(font, iL, yStart, iR - iL - btnW - 6, 18, Component.empty());
        factionArg.setMaxLength(48);
        factionArg.setHint(Component.translatable("gui.territory.faction.player_hint"));
        addRenderableWidget(factionArg);
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.invite"),
                        b -> sendFactionAction(FactionActionC2S.INVITE, factionArg.getValue(), ""))
                .bounds(iR - btnW, yStart, btnW, 18).build());

        int row = yStart + 28, rowH = 22;
        int maxRows = Math.max(1, (topPos + imageHeight - 12 - row) / rowH);
        int shown = 0;
        for (String inv : fi.invites()) {
            if (shown >= maxRows) break;
            addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.revoke"),
                            b -> sendFactionAction(FactionActionC2S.REVOKE, inv, ""))
                    .bounds(iR - btnW, row, btnW, 18).build());
            row += rowH;
            shown++;
        }
    }

    private void buildRelationsTab(int yStart, FactionInfoS2C fi) {
        if (!(fi.isOwner() || fi.isOfficer())) return;
        int iL = innerL(), iR = innerR();
        int setW = 56, cycleW = 74, gap = 6;
        int setX = iR - setW, cycleX = setX - gap - cycleW;
        factionArg = new EditBox(font, iL, yStart, cycleX - gap - iL, 18, Component.empty());
        factionArg.setMaxLength(48);
        factionArg.setHint(Component.translatable("gui.territory.faction.faction_hint"));
        addRenderableWidget(factionArg);
        relCycleButton = Button.builder(Component.literal(REL_STATUS[relIndex]), b -> {
            relIndex = (relIndex + 1) % REL_STATUS.length;
            relCycleButton.setMessage(Component.literal(REL_STATUS[relIndex]));
        }).bounds(cycleX, yStart, cycleW, 18).build();
        addRenderableWidget(relCycleButton);
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.set"),
                        b -> sendFactionAction(FactionActionC2S.SET_RELATION, factionArg.getValue(), REL_STATUS[relIndex]))
                .bounds(setX, yStart, setW, 18).build());
    }

    private void buildOptionsTab(int yStart, FactionInfoS2C fi) {
        int iL = innerL(), iR = innerR();
        int w = Math.min(200, iR - iL);
        // Leave: two-click confirm so you can't fat-finger your way out of a faction
        addRenderableWidget(Button.builder(Component.translatable(pendingLeave
                        ? "gui.territory.faction.leave_confirm" : "gui.territory.faction.leave"), b -> {
                    if (pendingLeave) { sendFactionAction(FactionActionC2S.LEAVE, "", ""); pendingLeave = false; }
                    else { pendingLeave = true; pendingDisband = false; relayout(); }
                })
                .bounds(iL, yStart, w, 18).build());
        if (!fi.isOwner()) return;
        int btnW = 84, fieldW = w - btnW - 6;
        factionArg = new EditBox(font, iL, yStart + 24, fieldW, 18, Component.empty());
        factionArg.setMaxLength(16);
        factionArg.setHint(Component.translatable("gui.territory.faction.abbrev_hint"));
        addRenderableWidget(factionArg);
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.abbrev"),
                        b -> sendFactionAction(FactionActionC2S.SET_ABBREV, factionArg.getValue(), ""))
                .bounds(iL + fieldW + 6, yStart + 24, btnW, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.faction.ff", fi.friendlyFire()
                        ? Component.translatable("gui.territory.on") : Component.translatable("gui.territory.off")),
                        b -> sendFactionAction(FactionActionC2S.FRIENDLY_FIRE, fi.friendlyFire() ? "false" : "true", ""))
                .bounds(iL, yStart + 48, w, 18).build());
        // Buy Claims: the /factionbuy idea as a button. Owner-only, server validates funds + raises the cap.
        int row = yStart + 72;
        if (fi.buyEnabled()) {
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.territory.faction.buyclaims", fi.claimsPerPurchase()),
                            b -> sendFactionAction(FactionActionC2S.BUY_CLAIMS, "", ""))
                    .bounds(iL, row, w, 18).build());
            row += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable(pendingDisband
                        ? "gui.territory.faction.disband_confirm" : "gui.territory.faction.disband"), b -> {
                    if (pendingDisband) { sendFactionAction(FactionActionC2S.DISBAND, "", ""); pendingDisband = false; }
                    else { pendingDisband = true; pendingLeave = false; relayout(); }
                })
                .bounds(iL, row, w, 18).build());
    }

    private void sendFactionAction(int action, String arg, String arg2) {
        PacketDistributor.sendToServer(new FactionActionC2S(menu.pos, action, arg == null ? "" : arg, arg2));
    }

    private void renderFactionPage(GuiGraphics g) {
        int x = leftPos, y = topPos;
        int iL = innerL(), iR = innerR();
        FactionInfoS2C fi = factionInfo;
        if (fi != null && !fi.efLoaded()) {
            g.drawString(font, Component.translatable("gui.territory.no_ef"), iL, y + 50, 0xFFCC6666, false);
            return;
        }
        if (fi == null) {
            g.drawString(font, Component.translatable("gui.territory.syncing"), iL, y + 50, TEXT_DIM, false);
            return;
        }
        if (!fi.inFaction()) {
            g.drawString(font, Component.translatable("gui.territory.faction.none"), iL, y + 50, TEXT_DIM, false);
            if (!fi.invites().isEmpty()) {
                g.drawString(font, Component.translatable("gui.territory.faction.invited"), iL, y + 120, TITLE_GOLD, false);
                int r = y + 132, shown = 0;
                for (String inv : fi.invites()) {
                    if (shown >= 6) break;
                    g.drawString(font, "- " + inv, iL + 6, r, TEXT_DIM, false);
                    r += 11; shown++;
                }
            }
            return;
        }

        // header: name on the left, role + member-count right-aligned (won't collide on narrow panels)
        int header = 0xFF000000 | (fi.color() & 0xFFFFFF);
        String name = fi.name() + (fi.abbreviation().isEmpty() ? "" : " [" + fi.abbreviation() + "]");
        g.drawString(font, name, iL, y + 50, header, false);
        String role = fi.isOwner() ? "Owner" : (fi.isOfficer() ? "Officer" : "Member");
        int n = fi.members().size();
        String info = role + " · " + n + (n == 1 ? " member" : " members");
        g.drawString(font, info, iR - font.width(info), y + 50, TEXT_DIM, false);

        int contentY = y + 92;
        switch (factionSubTab) {
            case 1 -> renderInvitesTab(g, iL, contentY, fi);
            case 2 -> renderRelationsTab(g, iL, iR, contentY, fi);
            case 3 -> renderOptionsInfo(g, iL, iR, fi);
            default -> renderMembersTab(g, iL, contentY, fi);
        }
    }

    /** One bottom line on the Options tab: the faction's claim capacity, and (for the owner) the buy cost.
     *  Clipped to the inner panel so long numbers can't bleed past the edge. */
    private void renderOptionsInfo(GuiGraphics g, int iL, int iR, FactionInfoS2C fi) {
        int y = topPos + imageHeight - 11;
        StringBuilder s = new StringBuilder("Claims ").append(fi.factionUsed()).append(" / ").append(fi.factionCap());
        if (fi.bonusClaims() > 0) s.append(" (+").append(fi.bonusClaims()).append(" bought)");
        if (fi.isOwner() && fi.buyEnabled()) {
            // Show the SDM price on SDM servers; emeralds are only the fallback where SDM is not installed.
            String price = net.neoforged.fml.ModList.get().isLoaded("sdmeconomy")
                    ? fi.costSdm() + " SDM"
                    : fi.costEmerald() + " emeralds";
            s.append("   ·   Buy ").append(fi.claimsPerPurchase()).append(": ").append(price);
        }
        g.enableScissor(iL, y - 1, iR, y + 9);
        g.drawString(font, s.toString(), iL, y, TEXT_DIM, false);
        g.disableScissor();
    }

    private void renderMembersTab(GuiGraphics g, int iL, int yStart, FactionInfoS2C fi) {
        int rowH = 22, row = yStart + 4;
        int roleX = iL + 140;
        int maxRows = Math.max(1, (topPos + imageHeight - 12 - row) / rowH);
        int shown = 0;
        for (FactionInfoS2C.Member m : fi.members()) {
            if (shown >= maxRows) {
                g.drawString(font, Component.translatable("gui.territory.more", fi.members().size() - shown), iL + 6, row, TEXT_DIM, false);
                break;
            }
            int roleColor = m.role() == 2 ? 0xFFD060D0 : (m.role() == 1 ? 0xFF60D060 : 0xFFCFCFCF);
            String roleText = m.role() == 2 ? "OWNER" : (m.role() == 1 ? "OFFICER" : "MEMBER");
            g.drawString(font, m.name(), iL + 6, row + 5, 0xFFFFFFFF, false);
            g.drawString(font, roleText, roleX, row + 5, roleColor, false);
            row += rowH;
            shown++;
        }
    }

    private void renderInvitesTab(GuiGraphics g, int iL, int yStart, FactionInfoS2C fi) {
        if (!(fi.isOwner() || fi.isOfficer())) {
            g.drawString(font, Component.translatable("gui.territory.faction.no_perm"), iL, yStart, TEXT_DIM, false);
            return;
        }
        int row = yStart + 28, rowH = 22, shown = 0;
        int maxRows = Math.max(1, (topPos + imageHeight - 12 - row) / rowH);
        for (String inv : fi.invites()) {
            if (shown >= maxRows) break;
            g.drawString(font, inv, iL + 6, row + 5, 0xFFFFFFFF, false);
            row += rowH;
            shown++;
        }
        if (fi.invites().isEmpty()) g.drawString(font, Component.translatable("gui.territory.faction.no_invites"), iL + 6, row + 5, TEXT_DIM, false);
    }

    private void renderRelationsTab(GuiGraphics g, int iL, int iR, int yStart, FactionInfoS2C fi) {
        int row = yStart + (fi.isOwner() || fi.isOfficer() ? 28 : 0), shown = 0, rowH = 12;
        if (fi.relations().isEmpty()) {
            g.drawString(font, Component.translatable("gui.territory.faction.no_relations"), iL, row, TEXT_DIM, false);
            return;
        }
        for (FactionInfoS2C.Relation rel : fi.relations()) {
            if (shown >= 16) break;
            int c = "FRIENDLY".equals(rel.status()) ? 0xFF60D060 : ("HOSTILE".equals(rel.status()) ? 0xFFD06060 : 0xFFCFCFCF);
            g.drawString(font, rel.faction(), iL, row, 0xFFFFFFFF, false);
            String st = rel.status();
            g.drawString(font, st, iR - font.width(st), row, c, false);
            row += rowH;
            shown++;
        }
    }
}
