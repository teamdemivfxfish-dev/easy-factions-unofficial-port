package top.leonx.territory.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.integration.EasyFactionsBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Backs the Territory Table's cosmetic floating display. The server recomputes, a few times per second,
 * who owns the chunk the table sits in (via Easy Factions) and syncs the display string to nearby clients;
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

    public TerritoryTableBlockEntity(BlockPos pos, BlockState state) {
        super(TerritoryMod.TERRITORY_BE.get(), pos, state);
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
        String owner = EasyFactionsBridge.chunkOwnerDisplay(sl.getServer(), sl.dimension(), center);
        int[] grid = EasyFactionsBridge.claimColorGrid(sl.getServer(), sl.dimension(), center, GRID_RADIUS);
        List<MapLabel> lbls = new ArrayList<>();
        for (EasyFactionsBridge.MapLabel l : EasyFactionsBridge.claimLabels(sl.getServer(), sl.dimension(), center, GRID_RADIUS)) {
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
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
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
    }
}
