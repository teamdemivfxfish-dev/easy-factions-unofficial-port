package top.leonx.territory.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import top.leonx.territory.world.Interaction;

public final class BlockKinds {

    private BlockKinds() {
    }

    public static Interaction rightClickKind(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return Interaction.RIGHT_CLICK_BLOCK;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof Container) return Interaction.CONTAINER;
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)) return Interaction.DOOR;
        if (state.getMenuProvider(level, pos) != null || entity instanceof MenuProvider) return Interaction.UTILITY;
        return Interaction.RIGHT_CLICK_BLOCK;
    }
}
