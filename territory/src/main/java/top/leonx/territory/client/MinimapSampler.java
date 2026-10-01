package top.leonx.territory.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.NativeImage.Format;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.MapColor.Brightness;

public final class MinimapSampler {
   private MinimapSampler() {
   }

   public static NativeImage sample(Level level, int leftChunkX, int leftChunkZ, int spanChunks) {
      int size = spanChunks * 16;
      NativeImage img = new NativeImage(Format.RGBA, size, size, false);
      int originX = leftChunkX << 4;
      int originZ = leftChunkZ << 4;
      int minY = level.getMinBuildHeight();
      MutableBlockPos m = new MutableBlockPos();

      for (int px = 0; px < size; px++) {
         int wx = originX + px;
         double lastHeight = 0.0;

         for (int pz = 0; pz < size; pz++) {
            int wz = originZ + pz;
            LevelChunk chunk = level.getChunk(wx >> 4, wz >> 4);
            int y = chunk.getHeight(Types.WORLD_SURFACE, wx & 15, wz & 15) + 1;
            int waterDepth = 0;
            BlockState state;
            if (y <= minY + 1) {
               state = Blocks.BEDROCK.defaultBlockState();
            } else {
               do {
                  m.set(wx, --y, wz);
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
            Brightness brightness;
            if (color == MapColor.WATER) {
               double d = (double)waterDepth * 0.1 + (double)(px + pz & 1) * 0.2;
               brightness = d < 0.5 ? Brightness.HIGH : (d > 0.9 ? Brightness.LOW : Brightness.NORMAL);
            } else {
               double delta = ((double)y - lastHeight) * 4.0 / 5.0 + ((double)(px + pz & 1) - 0.5) * 0.4;
               brightness = delta > 0.6 ? Brightness.HIGH : (delta < -0.6 ? Brightness.LOW : Brightness.NORMAL);
            }

            lastHeight = (double)y;
            int abgr = color == MapColor.NONE ? 0 : MapColor.getColorFromPackedId((byte)(color.id * 4 + brightness.id));
            img.setPixelRGBA(px, pz, abgr);
         }
      }

      return img;
   }

   private static BlockState adjustFluid(Level level, BlockState state, BlockPos pos) {
      FluidState fluid = state.getFluidState();
      return !fluid.isEmpty() && !state.isFaceSturdy(level, pos, Direction.UP) ? fluid.createLegacyBlock() : state;
   }
}
