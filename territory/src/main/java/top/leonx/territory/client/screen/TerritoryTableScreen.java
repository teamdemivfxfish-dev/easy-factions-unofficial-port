package top.leonx.territory.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import top.leonx.territory.client.MinimapSampler;
import top.leonx.territory.container.TerritoryTableMenu;
import top.leonx.territory.integration.FactionsBridge;
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
import java.util.LinkedHashMap;
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
    public static final int TAB_VAULT = 3;

    private static final int T_PERSONAL = FactionsBridge.TYPE_PERSONAL;
    private static final int T_FACTION = FactionsBridge.TYPE_FACTION;
    private static final int T_ADMIN = FactionsBridge.TYPE_ADMIN;
    private static final int T_WARZONE = FactionsBridge.TYPE_WARZONE;

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
            0xE6C87A, 0xF2E34A, 0x2FAE6B, 0x5577CC, 0xC056C0, 0x44C2C2, 0xD2812B,
            0xECECEC, 0xE87FB5, 0xFF3A3A, 0x5CC8FF, 0x8A5CE6, 0x8B5A2B, 0x8C8C8C
    };
    private static final int SWATCH_COLS = 7;

    private static final int A_FILL = 0x88000000;
    private static final int A_WARZONE = 0xC0000000;
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
    /** Safezone name typed but not yet committed, kept across the server's data refreshes. */
    private String lastTypedAdminName = null;
    private String lastTypedWarzoneName = null;

    // view + buffer
    private int zoom = 2;
    private int tableChunkX, tableChunkZ;
    private double viewCenterX, viewCenterZ;
    private int bufLeftX, bufLeftZ, bufSpan, bufRadius, texSize;
    private DynamicTexture mapTex;
    private ResourceLocation mapTexId;

    // drag + brush: plain drag always pans immediately; holding Shift when the drag starts paints
    // claims/relinquishes instead. Decided once, at press time - no arm delay, no ambiguity.
    private boolean dragging, panned, painting, paintErase;
    private double dragStartMouseX, dragStartMouseY, dragStartViewX, dragStartViewZ;
    private int pressChunkX, pressChunkZ;

    private EditBox nameField;

    // permissions tab (operators only): a scrolling list of admin territories on the left, the selected
    // territory's switches and members on the right. Both panes are drawn by hand and clipped to their
    // pane, so a server with two hundred territories or a long member list can never spill out of the panel.
    private static final int ROW_H = 14;
    private int permListX, permListY, permListW, permListH, permDetX, permDetW;
    private int permListScroll, permDetScroll;
    private String selectedZone = "";
    private int permsPage = 0;
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
    private Button depositButton;
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
        this.ctrlW = 154;
        this.mapXoff = 8;
        this.mapYoff = 48;
        // Each axis limits the map on its own: width has to fit map + controls side by side, height only
        // the map itself. (Both used to share one "smaller of the two" figure minus the controls' width,
        // which shrank the map to its minimum on any window where height was the tighter side.)
        int byWidth = this.width - 40 - ctrlW - 30;
        int byHeight = this.height - 20 - mapYoff - 8;
        this.mapPx = Math.max(176, Math.min(Math.min(byWidth, byHeight), 560));
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
    private boolean warzone() { return claimType == T_WARZONE; }
    /** Admin-side modes share the "no cap, no contiguity" rules and the staged colour. */
    private boolean adminSide() { return admin() || warzone(); }

    // ---- server data ----------------------------------------------------------------------------

    public void acceptData(TerritoryDataS2C msg) {
        // relayout() rebuilds the box, so stash whatever is half-typed or the refresh eats it
        if (tab == TAB_MAP && nameField != null) {
            if (claimType == T_PERSONAL) lastTypedName = nameField.getValue();
            else if (claimType == T_ADMIN) lastTypedAdminName = nameField.getValue();
            else if (claimType == T_WARZONE) lastTypedWarzoneName = nameField.getValue();
        }
        this.data = msg;
        // if a type is selected the player is no longer eligible for, fall back to something they can use
        if (adminSide() && !msg.canAdminClaim()) claimType = firstAllowedType(msg);
        if (claimType == T_PERSONAL && !msg.canPersonalClaim()) claimType = firstAllowedType(msg);
        if (tab == TAB_PERMS && !msg.canAdminClaim() && !msg.canFactionClaim()) tab = TAB_MAP;
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
        int mineKind = admin() || warzone() ? FactionsBridge.KIND_ADMIN
                : (faction() ? FactionsBridge.KIND_MINE_FACTION : FactionsBridge.KIND_MINE_CORE);
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
        addRenderableWidget(Button.builder(Component.translatable("gui.territory.tab.vault"), b -> selectTab(TAB_VAULT))
                .bounds(x + 162, y + 28, 74, 16).build());
        // the permissions tab is for operators (safezones) and faction owners and officers (faction access)
        if (canSafezones() || canFactionAccess()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.territory.tab.perms"), b -> selectTab(TAB_PERMS))
                    .bounds(x + 240, y + 28, 74, 16).build());
        }
        menu.setVaultOpen(tab == TAB_VAULT);
        depositButton = null;
        if (tab == TAB_MAP) buildMapWidgets(x, y);
        else if (tab == TAB_PERMS) buildPermWidgets(x, y);
        else if (tab == TAB_VAULT) buildVaultWidgets(x, y);
        else buildFactionWidgets(x, y);
    }

    private void buildVaultWidgets(int x, int y) {
        int w = Math.min(150, imageWidth - 200);
        depositButton = Button.builder(Component.translatable("gui.territory.vault.deposit"),
                        b -> sendFactionAction(FactionActionC2S.DEPOSIT, "", ""))
                .bounds(x + 190, y + TerritoryTableMenu.HOTBAR_Y - 2, w, 18).build();
        addRenderableWidget(depositButton);
    }

    private static String duration(long minutes) {
        if (minutes <= 0L) return "under 1m";
        if (minutes >= 1440L) return (minutes / 1440L) + "d " + ((minutes % 1440L) / 60L) + "h";
        if (minutes >= 60L) return (minutes / 60L) + "h " + (minutes % 60L) + "m";
        return minutes + "m";
    }

    private int drawWrapped(GuiGraphics g, String text, int x, int y, int width, int color) {
        for (net.minecraft.util.FormattedCharSequence line : font.split(Component.literal(text), width)) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y + 3;
    }

    private void renderVaultPage(GuiGraphics g) {
        int x = leftPos, y = topPos;
        g.drawString(font, Component.translatable("gui.territory.vault.slots"), x + 10, y + 64, TITLE_GOLD, false);
        g.drawString(font, Component.translatable("gui.territory.vault.inventory"), x + 10, y + 106, TEXT_DIM, false);
        int tx = x + 190, tw = imageWidth - 200, ty = y + 54;
        FactionInfoS2C fi = factionInfo;
        int slotValue = menu.inputValue();
        if (fi == null) {
            drawWrapped(g, "Syncing...", tx, ty, tw, TEXT_DIM);
            if (depositButton != null) depositButton.active = false;
            return;
        }
        if (!fi.inFaction()) {
            drawWrapped(g, "Join a faction to fund its land.", tx, ty, tw, TEXT_DIM);
            if (depositButton != null) depositButton.active = false;
            return;
        }
        FactionInfoS2C.Extras bank = fi.extras();
        boolean mayDeposit = switch (bank.deposit()) {
            case 1 -> fi.isOwner() || fi.isOfficer();
            case 2 -> fi.isOwner();
            default -> true;
        };
        if (depositButton != null) depositButton.active = slotValue > 0 && mayDeposit;

        int due = fi.dueValue(), held = fi.coreValue();
        int chunks = fi.factionUsed();
        String basis = chunks == 0 ? " for 1 chunk" : "";
        if (!fi.hasCore()) {
            ty = drawWrapped(g, "No core yet. Depositing here makes this table your faction's core.", tx, ty, tw, TEXT_DIM);
        } else if (fi.corePos() == menu.pos.asLong()) {
            ty = drawWrapped(g, "This table is your faction's core.", tx, ty, tw, 0xFF8FAE72);
        } else {
            net.minecraft.core.BlockPos core = net.minecraft.core.BlockPos.of(fi.corePos());
            ty = drawWrapped(g, "Your core is at " + core.getX() + ", " + core.getY() + ", " + core.getZ()
                    + ". Items only go into that table.", tx, ty, tw, 0xFFCC6666);
        }
        if (!mayDeposit) {
            ty = drawWrapped(g, bank.deposit() == 2 ? "Only the owner can add to the faction bank." : "Only officers and the owner can add to the faction bank.",
                    tx, ty, tw, 0xFFCC6666);
        }
        if (menu.costPerChunk <= 0) {
            drawWrapped(g, "Upkeep is switched off on this server.", tx, ty, tw, TEXT_DIM);
            return;
        }
        if (chunks == 0) {
            ty = drawWrapped(g, "Nothing is claimed yet, so no upkeep is due. Times are shown for 1 chunk.", tx, ty, tw, TEXT_DIM);
        }
        ty = drawWrapped(g, held > 0 ? "The core covers " + duration(minutesFor(held, chunks)) + basis + "." : "The core is empty.",
                tx, ty, tw, 0xFFFFFFFF);
        if (!bank.top().isEmpty()) {
            StringBuilder top = new StringBuilder("Top: ");
            for (int i = 0; i < Math.min(3, bank.top().size()); i++) {
                FactionInfoS2C.Contribution c = bank.top().get(i);
                top.append(i > 0 ? ", " : "").append(c.name()).append(' ').append(duration(minutesFor(c.value(), chunks)));
            }
            ty = drawWrapped(g, top.toString(), tx, ty, tw, TEXT_DIM);
        }
        if (bank.mine() > 0L) {
            ty = drawWrapped(g, "You added " + duration(minutesFor(bank.mine(), chunks)) + " in total.", tx, ty, tw, 0xFF8FAE72);
        }

        int reserve = fi.graceMinutesLeft() >= 0 ? 36 : (due > 0 ? 14 : 0);
        int extra = slotValue > 0 ? 26 : 0;
        int limit = y + TerritoryTableMenu.HOTBAR_Y - 4 - reserve - extra;
        ty = drawItemTimes(g, tx, ty + 2, tw, limit, chunks, slotValue > 0);
        if (slotValue > 0) {
            ty = drawWrapped(g, "Adds " + duration(minutesFor(slotValue, chunks)) + basis + ", covered for "
                    + duration(minutesFor((long) held + slotValue, chunks)) + " in total.", tx, ty + 2, tw, 0xFF8FAE72);
        }
        if (fi.graceMinutesLeft() >= 0) {
            long need = (long) due - held - slotValue;
            String fix = need > 0 ? "Deposit items worth " + duration(minutesFor(need, chunks)) + " more to pay in full."
                    : "These items pay it in full.";
            drawWrapped(g, "Payment overdue. The land is safe for " + duration(fi.graceMinutesLeft()) + ". " + fix,
                    tx, ty + 4, tw, 0xFFCC6666);
        } else if (due > 0) {
            drawWrapped(g, "Next payment in " + duration(fi.minutesToNext()) + ".", tx, ty + 4, tw, TEXT_DIM);
        }
    }

    private long minutesFor(long value, int chunks) {
        FactionInfoS2C fi = factionInfo;
        if (fi == null || menu.costPerChunk <= 0) return 0L;
        return value * fi.intervalMinutes() / ((long) Math.max(1, chunks) * menu.costPerChunk);
    }

    private static String itemLabel(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null ? id : new ItemStack(item).getHoverName().getString();
    }

    /** What each item is worth as upkeep time: the stacks in the deposit slots when there are any, otherwise every item the core accepts. */
    private int drawItemTimes(GuiGraphics g, int tx, int ty, int tw, int limit, int chunks, boolean slots) {
        Map<String, Long> worth = new LinkedHashMap<>();
        if (slots) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < TerritoryTableMenu.INPUT_SLOTS; i++) {
                ItemStack stack = menu.inputContainer().getItem(i);
                if (stack.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (menu.coinValues.containsKey(id)) counts.merge(id, stack.getCount(), Integer::sum);
            }
            counts.forEach((id, count) -> worth.put(id, (long) count * menu.coinValues.get(id)));
        } else {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(menu.coinValues.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Integer> e : sorted) worth.put(e.getKey(), (long) e.getValue());
        }
        if (worth.isEmpty()) return ty;

        g.drawString(font, slots ? "In the slots:" : "Each item adds:", tx, ty, TITLE_GOLD, false);
        ty += 11;
        int maxRows = Math.max(0, (limit - ty) / 10);
        int rows = worth.size();
        int shown = rows > maxRows ? Math.max(0, maxRows - 1) : rows;
        int drawn = 0;
        for (Map.Entry<String, Long> e : worth.entrySet()) {
            if (drawn >= shown) break;
            String count = slots ? menu.coinValues.get(e.getKey()) > 0 ? countIn(e.getKey()) + " x " : "" : "";
            String line = count + itemLabel(e.getKey()) + ": " + duration(minutesFor(e.getValue(), chunks));
            g.enableScissor(tx, ty, tx + tw, ty + 10);
            g.drawString(font, line, tx + 4, ty, 0xFFFFFFFF, false);
            g.disableScissor();
            ty += 10;
            drawn++;
        }
        if (drawn < rows && maxRows > 0) {
            g.drawString(font, "+" + (rows - drawn) + " more, hover an item to see its time", tx + 4, ty, TEXT_DIM, false);
            ty += 10;
        }
        return ty;
    }

    private int countIn(String id) {
        int n = 0;
        for (int i = 0; i < TerritoryTableMenu.INPUT_SLOTS; i++) {
            ItemStack stack = menu.inputContainer().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id)) n += stack.getCount();
        }
        return n;
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = super.getTooltipFromContainerItem(stack);
        FactionInfoS2C fi = factionInfo;
        if (tab != TAB_VAULT || stack.isEmpty() || fi == null || !fi.inFaction() || menu.costPerChunk <= 0) return lines;
        Integer value = menu.coinValues.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (value == null) return lines;
        int chunks = fi.factionUsed();
        String basis = chunks == 0 ? " for 1 chunk" : "";
        String text = "Upkeep: " + duration(minutesFor(value, chunks)) + " each" + basis;
        if (stack.getCount() > 1) text += ", " + duration(minutesFor((long) value * stack.getCount(), chunks)) + " for the stack";
        List<Component> out = new ArrayList<>(lines);
        out.add(Component.literal(text).withStyle(ChatFormatting.GOLD));
        return out;
    }

    /**
     * The permissions page: a fixed two-pane frame with everything that can grow put inside a scrolling
     * pane. Only the "add a player" row is a real widget, and it sits in a reserved slot at the bottom of
     * the details pane where it cannot be scrolled away from or overlapped.
     */
    private boolean canFactionAccess() { return data != null && data.canFactionClaim(); }

    private boolean canSafezones() { return data != null && data.canAdminClaim(); }

    private boolean onAccessPage() { return canFactionAccess() && (!canSafezones() || permsPage == 0); }

    private static final int ACCESS_ROW_H = 40;

    private void buildAccessWidgets(int x, int y) {
        FactionInfoS2C fi = factionInfo;
        if (canFactionAccess() && canSafezones()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.territory.access.to_zones"), b -> { permsPage = 1; relayout(); })
                    .bounds(x + imageWidth - 124, y + 45, 114, 12).build());
        }
        if (fi == null || !fi.inFaction()) return;
        String[] keys = {"doors", "utility", "deposit"};
        int[] levels = {fi.extras().doors(), fi.extras().utility(), fi.extras().deposit()};
        String[] prefix = {"gui.territory.access.level.", "gui.territory.access.level.", "gui.territory.access.dep."};
        String[][] names = {{"members", "allies", "everyone"}, {"members", "allies", "everyone"}, {"members", "officers", "owner"}};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            final int next = (levels[i] + 1) % 3;
            Component label = Component.translatable(prefix[i] + names[i][Math.max(0, Math.min(2, levels[i]))]);
            addRenderableWidget(Button.builder(label,
                            b -> PacketDistributor.sendToServer(new FactionActionC2S(menu.pos, FactionActionC2S.SET_SETTING, key, String.valueOf(next))))
                    .bounds(x + imageWidth - 148, y + 66 + i * ACCESS_ROW_H, 138, 18).build());
        }
    }

    private void renderAccessPage(GuiGraphics g) {
        FactionInfoS2C fi = factionInfo;
        int x = leftPos, y = topPos;
        if (fi == null) {
            g.drawString(font, Component.translatable("gui.territory.syncing"), x + 10, y + 66, TEXT_DIM, false);
            return;
        }
        if (!fi.inFaction()) {
            g.drawString(font, Component.translatable("gui.territory.access.nofaction"), x + 10, y + 66, TEXT_DIM, false);
            return;
        }
        g.drawString(font, Component.translatable("gui.territory.access.title", fi.name()), x + 10, y + 50, TITLE_GOLD, false);
        String[] titles = {"doors", "utility", "deposit"};
        int textW = imageWidth - 170;
        for (int i = 0; i < titles.length; i++) {
            int ry = y + 66 + i * ACCESS_ROW_H;
            g.drawString(font, Component.translatable("gui.territory.access." + titles[i]), x + 10, ry, 0xFFFFFFFF, false);
            int dy = ry + 11;
            for (net.minecraft.util.FormattedCharSequence line : font.split(Component.translatable("gui.territory.access." + titles[i] + ".desc"), textW)) {
                g.drawString(font, line, x + 10, dy, TEXT_DIM, false);
                dy += 10;
            }
        }
        int ny = y + 66 + titles.length * ACCESS_ROW_H + 2;
        for (net.minecraft.util.FormattedCharSequence line : font.split(Component.translatable("gui.territory.access.note"), imageWidth - 20)) {
            g.drawString(font, line, x + 10, ny, TEXT_DIM, false);
            ny += 10;
        }
        ny += 6;
        List<String> atWar = fi.extras().atWar();
        Component war = atWar.isEmpty()
                ? Component.translatable("gui.territory.access.war.none")
                : Component.translatable("gui.territory.access.war", String.join(", ", atWar));
        for (net.minecraft.util.FormattedCharSequence line : font.split(war, imageWidth - 20)) {
            g.drawString(font, line, x + 10, ny, atWar.isEmpty() ? TEXT_DIM : 0xFFCC6666, false);
            ny += 10;
        }
    }

    private void buildPermWidgets(int x, int y) {
        if (onAccessPage()) {
            buildAccessWidgets(x, y);
            return;
        }
        if (canFactionAccess() && canSafezones()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.territory.access.to_faction"), b -> { permsPage = 0; relayout(); })
                    .bounds(x + imageWidth - 124, y + 45, 114, 12).build());
        }
        permListX = x + 8;
        permListY = y + 58;
        permListW = 132;
        permListH = imageHeight - 66;
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
        String typeKey = warzone() ? "gui.territory.type.warzone"
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
        } else if (warzone()) {
            nameField.setValue(lastTypedWarzoneName != null ? lastTypedWarzoneName : "");
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
        permListScroll = permDetScroll = 0;
        relayout();
        if (which == TAB_FACTION || which == TAB_VAULT) PacketDistributor.sendToServer(new FactionInfoRequestC2S(menu.pos));
        else if (which == TAB_PERMS) {
            PacketDistributor.sendToServer(new FactionInfoRequestC2S(menu.pos));
            requestData();
        }
        else requestData();   // the permissions tab reads the same payload as the map
    }

    /**
     * Cycle Personal -> Faction -> (Safezone, Warzone if operator) -> Personal.
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
            types.add(T_WARZONE);
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
        if (tab == TAB_PERMS && button == 0 && data != null && !onAccessPage()) {
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
                int sw = adminSide() ? -1 : swatchHit(mx, my);
                if (sw >= 0) {
                    PacketDistributor.sendToServer(new TerritoryColorC2S(PRESET_COLORS[sw], faction(),
                            (int) Math.floor(viewCenterX), (int) Math.floor(viewCenterZ), bufRadius));
                    return true;
                }
            }
            if (overMap(mx, my)) {
                dragging = true;
                panned = false;
                // Shift at press time arms the brush; otherwise a drag pans, immediately and always
                painting = hasShiftDown();
                dragStartMouseX = mx;
                dragStartMouseY = my;
                dragStartViewX = viewCenterX;
                dragStartViewZ = viewCenterZ;
                pressChunkX = chunkXAt(mx);
                pressChunkZ = chunkZAt(my);
                // brush direction: started on your own land -> relinquish; otherwise -> claim
                paintErase = data != null && mineSet.contains(ChunkPos.asLong(pressChunkX, pressChunkZ));
                if (painting && data != null) paintChunk(pressChunkX, pressChunkZ);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            if (painting) {                 // Shift held at press: drag paints chunks
                panned = true;
                paintAt(mx, my);
                return true;
            }
            double totalX = mx - dragStartMouseX, totalY = my - dragStartMouseY;
            // a couple of pixels of jitter is still a tap; anything more is a pan, applied immediately
            if (Math.abs(totalX) > 2 || Math.abs(totalY) > 2) panned = true;
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
        if (tab == TAB_PERMS && sy != 0 && !onAccessPage()) {
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
        // personal claims carry the player's territory name; safezones and warzones carry their own
        String name = (nameField == null || claimType == T_FACTION) ? "" : nameField.getValue();
        boolean nameChanged = claimType == T_PERSONAL && nameField != null && !name.equals(data.personalName());
        if (add.isEmpty() && remove.isEmpty() && !nameChanged) return;
        PacketDistributor.sendToServer(new TerritoryCommitC2S(claimType, add, remove, name,
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
            int col = i % SWATCH_COLS, row = i / SWATCH_COLS;
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
        if (tab == TAB_VAULT) {
            for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                int sx = x + slot.x, sy = y + slot.y;
                g.fill(sx - 1, sy - 1, sx + 17, sy + 17, slot.index < TerritoryTableMenu.INPUT_SLOTS ? OUTLINE_GOLD : 0xFF3A2E20);
                g.fill(sx, sy, sx + 16, sy + 16, 0xFF1C1610);
            }
            renderVaultPage(g);
        }
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
        } else if (tab == TAB_VAULT) {
            renderTooltip(g, mouseX, mouseY);
        } else {
            renderFactionPage(g);
        }
    }

    // ---- permissions page -------------------------------------------------------------------------

    /**
     * Two panes: the admin territories on the left, the selected one's switches and trusted players on the
     * right. Everything that can grow lives inside a scissored, scrolling pane with a scrollbar, so a
     * hundred safezones or a long member list scroll rather than run off the panel.
     */
    private void renderPermsPage(GuiGraphics g, int mouseX, int mouseY) {
        permHits.clear();
        if (onAccessPage()) {
            renderAccessPage(g);
            return;
        }
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
                g.fill(permListX + 1, y + 3, permListX + 6, y + ROW_H - 4, 0xFF000000 | z.color());
                String text = trim(z.name().isEmpty() ? I18nAdmin() : z.name(), permListW - 34);
                g.drawString(font, text, permListX + 10, y + 3, selected ? TITLE_GOLD : 0xFFDDDDDD, false);
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
        if (zone.worldGuard()) {
            for (var line : font.split(Component.translatable("gui.territory.perm.wg"), permDetW - 8)) {
                if (y + 10 >= permListY && y <= bodyBottom) g.drawString(font, line, permDetX + 4, y, TEXT_DIM, false);
                y += 10;
            }
            y += 4;
        }
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
        if (zone.members().isEmpty()) {
            if (y + ROW_H >= permListY && y <= bodyBottom) {
                g.drawString(font, Component.translatable("gui.territory.perm.no_members"),
                        permDetX + 8, y + 3, TEXT_DIM, false);
            }
            y += ROW_H;
        }

        g.disableScissor();
        drawScrollbar(g, permDetX + permDetW - 2, permListY, bodyH, contentH, permDetScroll);
    }

    private int permContentHeight(TerritoryDataS2C.AdminZone zone) {
        int rows = 1 + AdminPerm.values().length + 1 + Math.max(1, zone.members().size());
        int note = zone.worldGuard() ? font.split(Component.translatable("gui.territory.perm.wg"), permDetW - 8).size() * 10 + 4 : 0;
        return rows * ROW_H + 8 + note;
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

    /** Shift-drag brush: a cursor label while a stroke is painting claims / relinquishing them. */
    private void renderBrush(GuiGraphics g, int mouseX, int mouseY) {
        if (!dragging || !painting || data == null) return;
        int claimColor = 0xFF66D066, eraseColor = 0xFFD06666;
        cursorLabel(g, mouseX, mouseY, Component.translatable(paintErase
                ? "gui.territory.brush.releasing" : "gui.territory.brush.claiming"), paintErase ? eraseColor : claimColor);
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
            Set<Long> warSet = new HashSet<>();
            for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
                if (e.color() == FactionsBridge.WARZONE_COLOR) warSet.add(ChunkPos.asLong(e.x(), e.z()));
            }
            for (TerritoryDataS2C.ClaimEntry e : data.claims()) {
                long key = ChunkPos.asLong(e.x(), e.z());
                boolean war = warSet.contains(key);
                drawCell(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, (war ? A_WARZONE : A_FILL) | (e.color() & 0xFFFFFF), false);
                if (war) outlineBorder(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, 0xFFFF4040, warSet::contains);
                if (mineSet.contains(key)) {
                    if (stagedRemove.contains(key)) drawCell(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, A_REMOVE, true);
                    else outlineBorder(g, e.x(), e.z(), leftX, topZ, cell, mx0, my0, TITLE_GOLD,
                            k -> mineSet.contains(k) && !stagedRemove.contains(k));
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

        Component hint = Component.translatable("gui.territory.brush.hint");
        g.drawString(font, hint, leftPos + imageWidth - 8 - font.width(hint), topPos + 32, TEXT_DIM, false);
    }

    private void renderMapControls(GuiGraphics g) {
        int cx = leftPos + ctrlXoff, y = topPos + mapYoff;
        if (data != null && !data.coreLoaded()) {
            g.drawString(font, Component.translatable("gui.territory.no_core"), cx, y + 22, 0xFFCC6666, false);
        } else if (warzone()) {
            g.drawString(font, Component.translatable("gui.territory.claims_warzone", selectionCount()), cx, y + 22, 0xFFCC5555, false);
        } else if (admin()) {
            g.drawString(font, Component.translatable("gui.territory.claims_admin", selectionCount()), cx, y + 22, 0xFF000000 | FactionsBridge.SAFEZONE_COLOR, false);
        } else if (data != null) {
            int cap = faction() ? data.factionCap() : data.coreCap();
            int worldUsed = faction() ? data.factionUsed() : data.coreUsed();
            int projected = worldUsed - stagedRemove.size() + stagedAdd.size();
            int color = (cap > 0 && projected > cap) ? 0xFFCC6666 : TEXT_DIM;
            g.drawString(font, Component.translatable("gui.territory.claims", projected, cap), cx, y + 22, color, false);
        }

        String nameKey = warzone() ? "gui.territory.name.warzone"
                : admin() ? "gui.territory.name.admin"
                : (faction() ? "gui.territory.name.faction" : "gui.territory.name.personal");
        g.drawString(font, Component.translatable(nameKey), cx, y + 36, TEXT_DIM, false);
        if (warzone()) {
            drawWrapped(g, Component.translatable("gui.territory.warzone_color").getString(), cx, y + 70, ctrlW, 0xFFCC5555);
        } else if (admin()) {
            drawWrapped(g, Component.translatable("gui.territory.safezone_color").getString(), cx, y + 70, ctrlW, 0xFF000000 | FactionsBridge.SAFEZONE_COLOR);
        } else {
            g.drawString(font, Component.translatable("gui.territory.border_color"), cx, y + 70, TEXT_DIM, false);
            int sy = y + 82, sw = 18, gap = 4;
            int current = data == null ? -1 : ((faction() ? data.factionColor() : data.personalColor()) & 0xFFFFFF);
            for (int i = 0; i < PRESET_COLORS.length; i++) {
                int col = i % SWATCH_COLS, row = i / SWATCH_COLS;
                int sx = cx + col * (sw + gap), yy = sy + row * (sw + gap);
                g.fill(sx, yy, sx + sw, yy + sw, 0xFF000000 | PRESET_COLORS[i]);
                g.renderOutline(sx, yy, sw, sw, PRESET_COLORS[i] == current ? 0xFFFFFFFF : OUTLINE_DARK);
            }
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

    private void outlineBorder(GuiGraphics g, int cx, int cz, double leftX, double topZ, float cell,
                               int mx0, int my0, int color, java.util.function.LongPredicate sameGroup) {
        int px0 = mx0 + Math.round((float) ((cx - leftX) * cell));
        int pz0 = my0 + Math.round((float) ((cz - topZ) * cell));
        int px1 = mx0 + Math.round((float) ((cx + 1 - leftX) * cell));
        int pz1 = my0 + Math.round((float) ((cz + 1 - topZ) * cell));
        if (!sameGroup.test(ChunkPos.asLong(cx, cz - 1))) g.fill(px0, pz0, px1, pz0 + 1, color);
        if (!sameGroup.test(ChunkPos.asLong(cx, cz + 1))) g.fill(px0, pz1 - 1, px1, pz1, color);
        if (!sameGroup.test(ChunkPos.asLong(cx - 1, cz))) g.fill(px0, pz0, px0 + 1, pz1, color);
        if (!sameGroup.test(ChunkPos.asLong(cx + 1, cz))) g.fill(px1 - 1, pz0, px1, pz1, color);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- faction tab ----------------------------------------------------------------------------

    public void acceptFactionInfo(FactionInfoS2C msg) {
        this.factionInfo = msg;
        if (tab == TAB_FACTION || tab == TAB_PERMS) relayout();
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
        int row = yStart + 72;
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
        if (fi != null && !fi.coreLoaded()) {
            g.drawString(font, Component.translatable("gui.territory.no_core"), iL, y + 50, 0xFFCC6666, false);
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

    /** One bottom line on the Options tab: claim capacity and the state of the faction core's upkeep.
     *  Clipped to the inner panel so long numbers can't bleed past the edge. */
    private void renderOptionsInfo(GuiGraphics g, int iL, int iR, FactionInfoS2C fi) {
        int y = topPos + imageHeight - 11;
        StringBuilder s = new StringBuilder("Claims ").append(fi.factionUsed()).append(" / ").append(fi.factionCap());
        if (!fi.hasCore()) {
            s.append("   ·   No core table");
        } else if (fi.dueValue() > 0) {
            s.append("   ·   Core covers ").append(duration(minutesFor(fi.coreValue(), fi.factionUsed())));
        } else {
            s.append("   ·   No upkeep due");
        }
        if (fi.dueValue() > 0) {
            s.append("   ·   Due in ").append(duration(fi.minutesToNext()));
        }
        long warn = fi.extras().warnMinutes();
        long runway = fi.dueValue() > 0 ? fi.minutesToNext() + (long) (fi.coreValue() / fi.dueValue()) * fi.intervalMinutes() : Long.MAX_VALUE;
        boolean low = warn > 0 && fi.hasCore() && runway < warn;
        if (low) s.append("   ·   LOW");
        g.enableScissor(iL, y - 1, iR, y + 9);
        g.drawString(font, s.toString(), iL, y, low ? 0xFFCC6666 : TEXT_DIM, false);
        g.disableScissor();
        List<String> atWar = fi.extras().atWar();
        if (!atWar.isEmpty()) {
            g.enableScissor(iL, y - 12, iR, y - 2);
            g.drawString(font, "At war with " + String.join(", ", atWar), iL, y - 11, 0xFFCC6666, false);
            g.disableScissor();
        }
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
