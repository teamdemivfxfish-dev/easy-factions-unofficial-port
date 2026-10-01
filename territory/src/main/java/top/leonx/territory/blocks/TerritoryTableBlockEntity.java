package top.leonx.territory.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.FactionsBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the Territory Table's cosmetic floating display. The server recomputes, a few times per second,
 * who owns the chunk the table sits in (via Holdfast Factions) and syncs the display string to nearby clients;
 * the {@link top.leonx.territory.client.render.TerritoryTableBlockEntityRenderer} draws it above the block.
 */
public class TerritoryTableBlockEntity extends BlockEntity {

    /** The floating-map preview covers a (2*GRID_RADIUS+1)^2 grid of chunks centred on the table. Sized so
     *  the floating map can mirror a zoomed-out GUI view (up to span ~21) and still show claim borders. */
    public static final int GRID_RADIUS = 10;
    public static final int GRID_SPAN = 2 * GRID_RADIUS + 1;

    /** A name label for the floating map: chunk offset from the table + the owner/territory name. */
    public record MapLabel(int dx, int dz, String name) {}

    private String ownerDisplay = "";
    private int[] claims = new int[GRID_SPAN * GRID_SPAN];   // synced claim colours for the preview
    private List<MapLabel> labels = new ArrayList<>();        // synced owner/faction name labels
    private final Map<String, Integer> deposits = new LinkedHashMap<>();
    private long credit;

    public TerritoryTableBlockEntity(BlockPos pos, BlockState state) {
        super(TerritoryMod.TERRITORY_BE.get(), pos, state);
    }

    public long getCredit() {
        return credit;
    }

    public void setCredit(long value) {
        credit = Math.max(0L, value);
        setChanged();
    }

    public Map<String, Integer> getDeposits() {
        return Collections.unmodifiableMap(deposits);
    }

    public int getDepositCount(String item) {
        return deposits.getOrDefault(item, 0);
    }

    public void addDeposit(String item, int amount) {
        if (amount <= 0) return;
        deposits.merge(item, amount, Integer::sum);
        setChanged();
    }

    public void setDeposits(Map<String, Integer> next) {
        deposits.clear();
        next.forEach((id, count) -> {
            if (count > 0) deposits.put(id, count);
        });
        setChanged();
    }

    public void dropDeposit(Level level, BlockPos pos) {
        Map<String, Integer> dropped = new LinkedHashMap<>(deposits);
        deposits.clear();
        setChanged();
        dropped.forEach((id, count) -> {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
            if (item == null) return;
            int stackSize = Math.max(1, new ItemStack(item).getMaxStackSize());
            int remaining = count;
            while (remaining > 0) {
                int size = Math.min(stackSize, remaining);
                Block.popResource(level, pos, new ItemStack(item, size));
                remaining -= size;
            }
        });
    }

    public String getOwnerDisplay() {
        return ownerDisplay;
    }

    public int[] getClaims() {
        return claims;
    }

    public List<MapLabel> getLabels() {
        return labels;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TerritoryTableBlockEntity be) {
        if (level.getGameTime() % 40L != 0L) return;   // ~2x/sec is plenty
        if (!(level instanceof ServerLevel sl)) return;
        ChunkPos center = new ChunkPos(pos);
        String owner = FactionsBridge.chunkOwnerDisplay(sl.getServer(), sl.dimension(), center);
        int[] grid = FactionsBridge.claimColorGrid(sl.getServer(), sl.dimension(), center, GRID_RADIUS);
        List<MapLabel> lbls = new ArrayList<>();
        for (FactionsBridge.MapLabel l : FactionsBridge.claimLabels(sl.getServer(), sl.dimension(), center, GRID_RADIUS)) {
            lbls.add(new MapLabel(l.dx(), l.dz(), l.name()));
        }
        if (!owner.equals(be.ownerDisplay) || !Arrays.equals(grid, be.claims) || !lbls.equals(be.labels)) {
            be.ownerDisplay = owner;
            be.claims = grid;
            be.labels = lbls;
            be.setChanged();
            sl.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        writeDisplay(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeDisplay(tag);
        ListTag stored = new ListTag();
        deposits.forEach((id, count) -> {
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            t.putInt("count", count);
            stored.add(t);
        });
        tag.put("deposits", stored);
        tag.putLong("credit", credit);
    }

    private void writeDisplay(CompoundTag tag) {
        tag.putString("owner", ownerDisplay);
        tag.putIntArray("claims", claims);
        ListTag list = new ListTag();
        for (MapLabel l : labels) {
            CompoundTag t = new CompoundTag();
            t.putInt("x", l.dx());
            t.putInt("z", l.dz());
            t.putString("n", l.name());
            list.add(t);
        }
        tag.put("labels", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ownerDisplay = tag.getString("owner");
        int[] c = tag.getIntArray("claims");
        if (c.length == GRID_SPAN * GRID_SPAN) claims = c;
        List<MapLabel> loaded = new ArrayList<>();
        ListTag list = tag.getList("labels", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            loaded.add(new MapLabel(t.getInt("x"), t.getInt("z"), t.getString("n")));
        }
        labels = loaded;
        deposits.clear();
        ListTag stored = tag.getList("deposits", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < stored.size(); i++) {
            CompoundTag t = stored.getCompound(i);
            int count = t.getInt("count");
            if (count > 0) deposits.put(t.getString("id"), count);
        }
        credit = Math.max(0L, tag.getLong("credit"));
    }
}
