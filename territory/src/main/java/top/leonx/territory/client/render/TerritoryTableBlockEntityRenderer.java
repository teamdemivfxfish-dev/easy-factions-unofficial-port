package top.leonx.territory.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import top.leonx.territory.blocks.TerritoryTableBlockEntity;
import top.leonx.territory.client.MinimapSampler;
import top.leonx.territory.client.screen.TerritoryTableScreen;

public class TerritoryTableBlockEntityRenderer implements BlockEntityRenderer<TerritoryTableBlockEntity> {
   private static final double VIEW_DIST_SQR = 324.0;
   private static final long REBUILD_TICKS = 100L;
   private static final long EVICT_TICKS = 60L;
   private static final int OWNER_NAME = -1;
   private static final int FULL_BRIGHT = 15728880;
   private final Font font;
   private final Map<Long, TerritoryTableBlockEntityRenderer.Entry> cache = new HashMap<>();

   public TerritoryTableBlockEntityRenderer(Context ctx) {
      this.font = ctx.getFont();
   }

   public void render(TerritoryTableBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
      Minecraft mc = Minecraft.getInstance();
      Level level = be.getLevel();
      if (mc.player != null && level != null) {
         BlockPos pos = be.getBlockPos();
         if (!(mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > 324.0)) {
            long now = level.getGameTime();
            TerritoryTableBlockEntityRenderer.Entry e = this.mapFor(be, level, pos, now);
            this.evictStale(now);
            float time = (float)(now % 100000L) + partialTick;
            float bob = (float)Math.sin((double)(time * 0.08F)) * 0.03F;
            double px = Mth.lerp((double)partialTick, mc.player.xo, mc.player.getX());
            double pz = Mth.lerp((double)partialTick, mc.player.zo, mc.player.getZ());
            float yaw = (float)Math.toDegrees(Math.atan2(px - ((double)pos.getX() + 0.5), pz - ((double)pos.getZ() + 0.5)));
            pose.pushPose();
            pose.translate(0.5, 1.05 + (double)bob, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.mulPose(Axis.XP.rotationDegrees(-50.0F));
            Pose last = pose.last();
            quad(buffers.getBuffer(RenderType.text(e.id)), last, 0.33F, -0.003F, -15724528, 15728880);
            quad(buffers.getBuffer(RenderType.text(e.id)), last, 0.3F, 0.0F, -1, 15728880);
            int floatSpan = Mth.clamp(TerritoryTableScreen.savedFloatSpan, 8, 21);
            float perChunk = 0.6F / (float)floatSpan;

            for (TerritoryTableBlockEntity.MapLabel l : be.getLabels()) {
               if (!l.name().isEmpty() && Math.abs(l.dx()) <= floatSpan / 2 && Math.abs(l.dz()) <= floatSpan / 2) {
                  pose.pushPose();
                  pose.translate((float)l.dx() * perChunk, (float)(-l.dz()) * perChunk, 0.02F);
                  pose.scale(0.004F, -0.004F, 0.004F);
                  int w = this.font.width(l.name());
                  this.font.drawInBatch(l.name(), (float)(-w) / 2.0F, -4.0F, -1, true, pose.last().pose(), buffers, DisplayMode.NORMAL, 0, 15728880);
                  pose.popPose();
               }
            }

            pose.popPose();
         }
      }
   }

   private static void quad(VertexConsumer vc, Pose pose, float s, float z, int argb, int light) {
      put(vc, pose, -s, -s, z, 0.0F, 1.0F, argb, light);
      put(vc, pose, s, -s, z, 1.0F, 1.0F, argb, light);
      put(vc, pose, s, s, z, 1.0F, 0.0F, argb, light);
      put(vc, pose, -s, s, z, 0.0F, 0.0F, argb, light);
   }

   private static void put(VertexConsumer vc, Pose pose, float x, float y, float z, float u, float v, int argb, int light) {
      vc.addVertex(pose.pose(), x, y, z).setColor(argb).setUv(u, v).setLight(light);
   }

   private TerritoryTableBlockEntityRenderer.Entry mapFor(TerritoryTableBlockEntity be, Level level, BlockPos pos, long now) {
      long key = pos.asLong();
      TerritoryTableBlockEntityRenderer.Entry e = this.cache.get(key);
      int hash = Arrays.hashCode(be.getClaims());
      int gridSpan = 21;
      int floatSpan = Mth.clamp(TerritoryTableScreen.savedFloatSpan, 8, gridSpan);
      int offset = 10 - floatSpan / 2;
      if (e == null || now - e.built > 100L || e.claimsHash != hash || e.builtSpan != floatSpan) {
         int leftX = (pos.getX() >> 4) - floatSpan / 2;
         int leftZ = (pos.getZ() >> 4) - floatSpan / 2;
         NativeImage img = MinimapSampler.sample(level, leftX, leftZ, floatSpan);
         paintClaims(img, be.getClaims(), gridSpan, floatSpan, offset);
         DynamicTexture tex = new DynamicTexture(img);
         ResourceLocation id = Minecraft.getInstance().getTextureManager().register("territory_table_map", tex);
         if (e != null) {
            this.release(e);
         } else {
            e = new TerritoryTableBlockEntityRenderer.Entry();
         }

         e.tex = tex;
         e.id = id;
         e.built = now;
         e.claimsHash = hash;
         e.builtSpan = floatSpan;
         this.cache.put(key, e);
      }

      e.seen = now;
      return e;
   }

   private static void paintClaims(NativeImage img, int[] claims, int gridSpan, int floatSpan, int offset) {
      if (claims.length == gridSpan * gridSpan) {
         for (int fj = 0; fj < floatSpan; fj++) {
            for (int fi = 0; fi < floatSpan; fi++) {
               int gi = fi + offset;
               int gj = fj + offset;
               if (gi >= 0 && gj >= 0 && gi < gridSpan && gj < gridSpan) {
                  int c = claims[gj * gridSpan + gi];
                  if ((c & 0xFF000000) != 0) {
                     int rgb = c & 16777215;
                     int x0 = fi * 16;
                     int y0 = fj * 16;

                     for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                           blend(img, x0 + x, y0 + y, rgb, 0.33F);
                        }
                     }

                     boolean left = gi == 0 || claims[gj * gridSpan + gi - 1] != c;
                     boolean right = gi == gridSpan - 1 || claims[gj * gridSpan + gi + 1] != c;
                     boolean up = gj == 0 || claims[(gj - 1) * gridSpan + gi] != c;
                     boolean down = gj == gridSpan - 1 || claims[(gj + 1) * gridSpan + gi] != c;

                     for (int t = 0; t < 2; t++) {
                        if (left) {
                           for (int y = 0; y < 16; y++) {
                              solid(img, x0 + t, y0 + y, rgb);
                           }
                        }

                        if (right) {
                           for (int y = 0; y < 16; y++) {
                              solid(img, x0 + 15 - t, y0 + y, rgb);
                           }
                        }

                        if (up) {
                           for (int x = 0; x < 16; x++) {
                              solid(img, x0 + x, y0 + t, rgb);
                           }
                        }

                        if (down) {
                           for (int x = 0; x < 16; x++) {
                              solid(img, x0 + x, y0 + 15 - t, rgb);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void blend(NativeImage img, int x, int y, int rgb, float a) {
      int p = img.getPixelRGBA(x, y);
      int pr = p & 0xFF;
      int pg = p >> 8 & 0xFF;
      int pb = p >> 16 & 0xFF;
      int pa = p >> 24 & 0xFF;
      int cr = rgb >> 16 & 0xFF;
      int cg = rgb >> 8 & 0xFF;
      int cb = rgb & 0xFF;
      int nr = (int)((float)pr * (1.0F - a) + (float)cr * a);
      int ng = (int)((float)pg * (1.0F - a) + (float)cg * a);
      int nb = (int)((float)pb * (1.0F - a) + (float)cb * a);
      img.setPixelRGBA(x, y, pa << 24 | nb << 16 | ng << 8 | nr);
   }

   private static void solid(NativeImage img, int x, int y, int rgb) {
      int cr = rgb >> 16 & 0xFF;
      int cg = rgb >> 8 & 0xFF;
      int cb = rgb & 0xFF;
      img.setPixelRGBA(x, y, 0xFF000000 | cb << 16 | cg << 8 | cr);
   }

   private void evictStale(long now) {
      if (this.cache.size() > 1) {
         List<Long> dead = new ArrayList<>();

         for (Map.Entry<Long, TerritoryTableBlockEntityRenderer.Entry> en : this.cache.entrySet()) {
            if (now - en.getValue().seen > 60L) {
               dead.add(en.getKey());
            }
         }

         for (long k : dead) {
            TerritoryTableBlockEntityRenderer.Entry e = this.cache.remove(k);
            if (e != null) {
               this.release(e);
            }
         }
      }
   }

   private void release(TerritoryTableBlockEntityRenderer.Entry e) {
      if (e.id != null) {
         Minecraft.getInstance().getTextureManager().release(e.id);
      }

      if (e.tex != null) {
         e.tex.close();
      }
   }

   public boolean shouldRenderOffScreen(TerritoryTableBlockEntity be) {
      return true;
   }

   private static final class Entry {
      DynamicTexture tex;
      ResourceLocation id;
      long built;
      long seen;
      int claimsHash;
      int builtSpan;
   }
}
