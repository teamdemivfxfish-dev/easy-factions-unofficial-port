package top.leonx.territory.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Builds a top-down minimap image of the chunks around the Territory Table, client-side, using vanilla's
 * own map-colour algorithm (top map-coloured block per column, N-S height shading, water-depth shading).
 * Ported from MineTerritory's drawMapData (GPLv3) + vanilla MapItemSavedData. Client has the surrounding
 * chunks loaded (the player is standing at the table), so resampling for ZOOM needs no server round-trip.
 *
 * The image is {@code spanChunks*16} px square, north-up, pixel (px,pz) -> world (originX+px, originZ+pz).
 */
public final class MinimapSampler {

    private MinimapSampler() {}

    /** @param leftChunkX,leftChunkZ top-left chunk of the square; @param spanChunks chunks per side (zoom). */
    public static NativeImage sample(Level level, int leftChunkX, int leftChunkZ, int spanChunks) {
        int size = spanChunks * 16;
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        int originX = leftChunkX << 4;
        int originZ = leftChunkZ << 4;
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int px = 0; px < size; px++) {
            int wx = originX + px;
            double lastHeight = 0.0;
            for (int pz = 0; pz < size; pz++) {
                int wz = originZ + pz;
                LevelChunk chunk = level.getChunk(wx >> 4, wz >> 4);

                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15) + 1;
                BlockState state;
                int waterDepth = 0;
                if (y <= minY + 1) {
                    state = Blocks.BEDROCK.defaultBlockState();
                } else {
                    do {
                        y--;
                        m.set(wx, y, wz);
                        state = chunk.getBlockState(m);
                    } while (state.getMapColor(level, m) == MapColor.NONE && y > minY);

                    if (y > minY && !state.getFluidState().isEmpty()) {
                        int yy = y - 1;
                        BlockState below;
                        do {
                            m.set(wx, yy--, wz);
                            below = chunk.getBlockState(m);
                            waterDepth++;
                        } while (yy > minY && !below.getFluidState().isEmpty());
                        m.set(wx, y, wz);
                        state = adjustFluid(level, state, m);
                    }
                }

                MapColor color = state.getMapColor(level, m);
                MapColor.Brightness brightness;
                if (color == MapColor.WATER) {
                    double d = waterDepth * 0.1 + (px + pz & 1) * 0.2;
                    brightness = d < 0.5 ? MapColor.Brightness.HIGH : (d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL);
                } else {
                    double delta = (y - lastHeight) * 4.0 / 5.0 + ((px + pz & 1) - 0.5) * 0.4;
                    brightness = delta > 0.6 ? MapColor.Brightness.HIGH : (delta < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL);
                }
                lastHeight = y;

                int abgr = color == MapColor.NONE ? 0 : MapColor.getColorFromPackedId((byte) (color.id * 4 + brightness.id));
                img.setPixelRGBA(px, pz, abgr);
            }
        }
        return img;
    }

    private static BlockState adjustFluid(Level level, BlockState state, BlockPos pos) {
        var fluid = state.getFluidState();
        return (!fluid.isEmpty() && !state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP))
                ? fluid.createLegacyBlock() : state;
    }
}
