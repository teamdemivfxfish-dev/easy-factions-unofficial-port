package top.leonx.territory.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import top.leonx.territory.TerritoryMod;
import top.leonx.territory.container.TerritoryTableMenu;

/**
 * The Territory Table. Place it, right-click to open the claim GUI (map + faction tabs). The block is
 * just the body; the claim "brain" is Easy Factions, driven server-side from the menu's buttons. It also
 * carries a BlockEntity that drives the cosmetic floating owner-name display.
 *
 * Ported from MineTerritory (GPLv3, by Leon). The original banner-power claim model is removed.
 */
public class TerritoryTableBlock extends Block implements EntityBlock {

    // The model is the enchanting-table shape (12px tall, inset). Match collision/outline to it AND mark the
    // block noOcclusion(), so the game does NOT treat it as a full solid cube. Without noOcclusion() a full
    // occluder culls the faces of neighbouring blocks while the smaller model leaves gaps -> you can see
    // straight through walls/floors next to the table (the reported "x-ray" bug). Vanilla's enchanting table
    // does the same thing for the same reason.
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 12.0, 16.0);

    public static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(3.5F)
                .requiresCorrectToolForDrops()
                .noOcclusion();
    }

    public TerritoryTableBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            MenuProvider provider = new SimpleMenuProvider(
                    (id, inv, p) -> new TerritoryTableMenu(id, inv, pos),
                    Component.translatable("block.territory.territory_table"));
            sp.openMenu(provider, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TerritoryTableBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == TerritoryMod.TERRITORY_BE.get()
                ? (lvl, pos, st, be) -> TerritoryTableBlockEntity.serverTick(lvl, pos, st, (TerritoryTableBlockEntity) be)
                : null;
    }
}
