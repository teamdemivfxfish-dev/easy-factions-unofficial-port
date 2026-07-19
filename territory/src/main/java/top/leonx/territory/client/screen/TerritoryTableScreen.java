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
import top.leonx.territory.network.FactionActionC2S;
import top.leonx.territory.network.FactionInfoRequestC2S;
import top.leonx.territory.network.FactionInfoS2C;
import top.leonx.territory.network.TerritoryColorC2S;
import top.leonx.territory.network.TerritoryCommitC2S;
import top.leonx.territory.network.TerritoryDataS2C;
import top.leonx.territory.network.TerritoryRequestC2S;

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

    private static final int T_PERSONAL = EasyFactionsBridge.TYPE_PERSONAL;
    private static final int T_FACTION = EasyFactionsBridge.TYPE_FACTION;
    private static final int T_ADMIN = EasyFactionsBridge.TYPE_ADMIN;

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

    // ---- server data ----------------------------------------------------------------------------

    public void acceptData(TerritoryDataS2C msg) {
        // relayout() rebuilds the box, so stash whatever is half-typed or the refresh eats it
        if (tab == TAB_MAP && nameField != null) {
            if (claimType == T_PERSONAL) lastTypedName = nameField.getValue();
            else if (claimType == T_ADMIN) lastTypedAdminName = nameField.getValue();
        }
        this.data = msg;
        // if admin type is selected but the player lost eligibility, fall back
        if (admin() && !msg.canAdminClaim()) claimType = T_PERSONAL;
        recomputeSets();
        relayout();
    }

    private void recomputeSets() {
        mineSet.clear();
        forbidden.clear();
        clusters.clear();
        if (data == null) return;
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
        if (tab == TAB_MAP) buildMapWidgets(x, y);
        else buildFactionWidgets(x, y);
    }

    private void buildMapWidgets(int x, int y) {
        int cx = x + ctrlXoff, cy = y + mapYoff;
        String typeKey = admin() ? "gui.territory.type.admin"
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
        relayout();
        if (which == TAB_FACTION) PacketDistributor.sendToServer(new FactionInfoRequestC2S(menu.pos));
        else requestData();
    }

    /** Cycle Personal -> Faction -> (Admin if eligible) -> Personal. */
    private void cycleType() {
        List<Integer> types = new ArrayList<>();
        types.add(T_PERSONAL);
        types.add(T_FACTION);
        if (data != null && data.canAdminClaim()) types.add(T_ADMIN);
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
        if (tab == TAB_MAP && button == 0) {
            if (data != null) {
                int sw = swatchHit(mx, my);
                if (sw >= 0) {
                    if (admin()) {
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
        return super.mouseScrolled(mx, my, sx, sy);
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
        if (!admin()) {
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
        // personal claims carry the player's territory name; admin claims carry this region's name
        String name = (nameField == null || claimType == T_FACTION) ? "" : nameField.getValue();
        boolean nameChanged = claimType == T_PERSONAL && nameField != null && !name.equals(data.personalName());
        if (add.isEmpty() && remove.isEmpty() && !nameChanged) return;
        int color = admin() ? adminColor : -1;
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
        } else {
            renderFactionPage(g);
        }
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
            for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
                drawCell(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, A_FILL | (e.color() & 0xFFFFFF), false);
                long key = ChunkPos.asLong(e.x(), e.z());
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
        } else if (admin()) {
            g.drawString(font, Component.translatable("gui.territory.claims_admin", selectionCount()), cx, y + 22, 0xFFC056C0, false);
        } else if (data != null) {
            int cap = faction() ? data.factionCap() : data.coreCap();
            int worldUsed = faction() ? data.factionUsed() : data.coreUsed();
            int projected = worldUsed - stagedRemove.size() + stagedAdd.size();
            int color = (cap > 0 && projected > cap) ? 0xFFCC6666 : TEXT_DIM;
            g.drawString(font, Component.translatable("gui.territory.claims", projected, cap), cx, y + 22, color, false);
        }

        String nameKey = admin() ? "gui.territory.name.admin"
                : (faction() ? "gui.territory.name.faction" : "gui.territory.name.personal");
        g.drawString(font, Component.translatable(nameKey), cx, y + 36, TEXT_DIM, false);
        g.drawString(font, Component.translatable("gui.territory.border_color"), cx, y + 70, TEXT_DIM, false);
        int sy = y + 82, sw = 18, gap = 4;
        int current = data == null ? -1 : (admin() ? (adminColor & 0xFFFFFF)
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
