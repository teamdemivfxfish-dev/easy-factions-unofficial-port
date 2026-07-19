package top.leonx.territory.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import top.leonx.territory.client.MinimapSampler;
import top.leonx.territory.client.screen.TerritoryTableScreen;
import top.leonx.territory.blocks.TerritoryTableBlockEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replicates the original MineTerritory tile-entity renderer for 1.21: a floating, slowly-spinning,
 * gently-bobbing world MAP hovers above the table when a player is near (the live terrain around the
 * table, sampled client-side), with the chunk's owner shown as floating "Owner" + name text above it.
 *
 * The map texture is sampled from loaded chunks and cached per table position, rebuilt a few times a
 * minute and evicted (and freed) once a table stops being rendered.
 */
public class TerritoryTableBlockEntityRenderer implements BlockEntityRenderer<TerritoryTableBlockEntity> {

    private static final double VIEW_DIST_SQR = 18.0 * 18.0;
    private static final long REBUILD_TICKS = 100L;        // re-sample the terrain ~every 5s
    private static final long EVICT_TICKS = 60L;
    private static final int OWNER_NAME = 0xFFFFFFFF;
    private static final int FULL_BRIGHT = 0xF000F0;       // LightTexture.FULL_BRIGHT — map always lit

    private final Font font;
    private final Map<Long, Entry> cache = new HashMap<>();

    private static final class Entry {
        DynamicTexture tex;
        ResourceLocation id;
        long built;
        long seen;
        int claimsHash;
        int builtSpan;
    }

    public TerritoryTableBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @Override
    public void render(TerritoryTableBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Minecraft mc = Minecraft.getInstance();
        Level level = be.getLevel();
        if (mc.player == null || level == null) return;

        BlockPos pos = be.getBlockPos();
        if (mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > VIEW_DIST_SQR) return;

        long now = level.getGameTime();
        Entry e = mapFor(be, level, pos, now);
        evictStale(now);

        float time = (float) (now % 100000L) + partialTick;
        float bob = (float) Math.sin(time * 0.08f) * 0.03f;

        // turn the map to FACE the player (like the enchanting-table book), tilted back so it reads
        double px = Mth.lerp(partialTick, mc.player.xo, mc.player.getX());
        double pz = Mth.lerp(partialTick, mc.player.zo, mc.player.getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(px - (pos.getX() + 0.5), pz - (pos.getZ() + 0.5)));

        pose.pushPose();
        pose.translate(0.5, 1.05 + bob, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.XP.rotationDegrees(-50f));     // tilt back so the face reads toward the player
        PoseStack.Pose last = pose.last();
        // RenderType.text = no directional shading + respects the lightmap (we pass FULL_BRIGHT), exactly
        // how vanilla draws map items in the world. entityCutout would darken at night and flicker on spin.
        quad(buffers.getBuffer(RenderType.text(e.id)), last, 0.33f, -0.003f, 0xFF101010, FULL_BRIGHT);  // border, behind
        quad(buffers.getBuffer(RenderType.text(e.id)), last, 0.30f, 0f, 0xFFFFFFFF, FULL_BRIGHT);       // map, in front

        // stamp the owner / territory name labels onto the map, in its plane (mirrors the in-GUI labels)
        int floatSpan = Mth.clamp(TerritoryTableScreen.savedFloatSpan, 8, TerritoryTableBlockEntity.GRID_SPAN);
        float perChunk = 0.60f / floatSpan;   // map face is 0.60 wide and shows floatSpan chunks
        for (TerritoryTableBlockEntity.MapLabel l : be.getLabels()) {
            if (l.name().isEmpty() || Math.abs(l.dx()) > floatSpan / 2 || Math.abs(l.dz()) > floatSpan / 2) continue;
            pose.pushPose();
            pose.translate(l.dx() * perChunk, -l.dz() * perChunk, 0.02f);   // +y local = north
            pose.scale(0.004f, -0.004f, 0.004f);
            int w = font.width(l.name());
            // drop shadow for readability, NO background plate (the plate read as a black smudge on the map)
            font.drawInBatch(l.name(), -w / 2f, -4f, OWNER_NAME, true, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Flat quad in local XY, full texture, tinted {@code argb}, centred on the origin (text vertex
     *  format: position + colour + uv + lightmap only — no overlay/normal). */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose, float s, float z, int argb, int light) {
        put(vc, pose, -s, -s, z, 0f, 1f, argb, light);
        put(vc, pose, s, -s, z, 1f, 1f, argb, light);
        put(vc, pose, s, s, z, 1f, 0f, argb, light);
        put(vc, pose, -s, s, z, 0f, 0f, argb, light);
    }

    private static void put(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                            float u, float v, int argb, int light) {
        vc.addVertex(pose.pose(), x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setLight(light);
    }

    private Entry mapFor(TerritoryTableBlockEntity be, Level level, BlockPos pos, long now) {
        long key = pos.asLong();
        Entry e = cache.get(key);
        int hash = Arrays.hashCode(be.getClaims());
        // mirror the player's last GUI zoom (clamped to what the synced grid covers), centred on the table
        int gridSpan = TerritoryTableBlockEntity.GRID_SPAN;
        int floatSpan = Mth.clamp(TerritoryTableScreen.savedFloatSpan, 8, gridSpan);
        int offset = TerritoryTableBlockEntity.GRID_RADIUS - floatSpan / 2;
        if (e == null || now - e.built > REBUILD_TICKS || e.claimsHash != hash || e.builtSpan != floatSpan) {
            int leftX = (pos.getX() >> 4) - floatSpan / 2;
            int leftZ = (pos.getZ() >> 4) - floatSpan / 2;
            NativeImage img = MinimapSampler.sample(level, leftX, leftZ, floatSpan);
            paintClaims(img, be.getClaims(), gridSpan, floatSpan, offset);
            DynamicTexture tex = new DynamicTexture(img);
            ResourceLocation id = Minecraft.getInstance().getTextureManager().register("territory_table_map", tex);
            if (e != null) release(e);
            else e = new Entry();
            e.tex = tex;
            e.id = id;
            e.built = now;
            e.claimsHash = hash;
            e.builtSpan = floatSpan;
            cache.put(key, e);
        }
        e.seen = now;
        return e;
    }

    /** Tint each claimed chunk in its colour + a 2px border where it meets a different/no claim. Renders a
     *  {@code floatSpan}-chunk window into the {@code gridSpan}-wide synced claim grid (offset into it). */
    private static void paintClaims(NativeImage img, int[] claims, int gridSpan, int floatSpan, int offset) {
        if (claims.length != gridSpan * gridSpan) return;
        for (int fj = 0; fj < floatSpan; fj++) {
            for (int fi = 0; fi < floatSpan; fi++) {
                int gi = fi + offset, gj = fj + offset;
                if (gi < 0 || gj < 0 || gi >= gridSpan || gj >= gridSpan) continue;
                int c = claims[gj * gridSpan + gi];
                if ((c & 0xFF000000) == 0) continue;   // unclaimed
                int rgb = c & 0xFFFFFF;
                int x0 = fi * 16, y0 = fj * 16;
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++) blend(img, x0 + x, y0 + y, rgb, 0.33f);
                // borders compare against grid neighbours so the floating-window edge still gets a border
                boolean left = gi == 0 || claims[gj * gridSpan + gi - 1] != c;
                boolean right = gi == gridSpan - 1 || claims[gj * gridSpan + gi + 1] != c;
                boolean up = gj == 0 || claims[(gj - 1) * gridSpan + gi] != c;
                boolean down = gj == gridSpan - 1 || claims[(gj + 1) * gridSpan + gi] != c;
                for (int t = 0; t < 2; t++) {
                    if (left) for (int y = 0; y < 16; y++) solid(img, x0 + t, y0 + y, rgb);
                    if (right) for (int y = 0; y < 16; y++) solid(img, x0 + 15 - t, y0 + y, rgb);
                    if (up) for (int x = 0; x < 16; x++) solid(img, x0 + x, y0 + t, rgb);
                    if (down) for (int x = 0; x < 16; x++) solid(img, x0 + x, y0 + 15 - t, rgb);
                }
            }
        }
    }

    private static void blend(NativeImage img, int x, int y, int rgb, float a) {
        int p = img.getPixelRGBA(x, y);
        int pr = p & 0xFF, pg = (p >> 8) & 0xFF, pb = (p >> 16) & 0xFF, pa = (p >> 24) & 0xFF;
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        int nr = (int) (pr * (1 - a) + cr * a);
        int ng = (int) (pg * (1 - a) + cg * a);
        int nb = (int) (pb * (1 - a) + cb * a);
        img.setPixelRGBA(x, y, (pa << 24) | (nb << 16) | (ng << 8) | nr);
    }

    private static void solid(NativeImage img, int x, int y, int rgb) {
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        img.setPixelRGBA(x, y, (0xFF << 24) | (cb << 16) | (cg << 8) | cr);
    }

    private void evictStale(long now) {
        if (cache.size() <= 1) return;
        List<Long> dead = new ArrayList<>();
        for (Map.Entry<Long, Entry> en : cache.entrySet()) {
            if (now - en.getValue().seen > EVICT_TICKS) dead.add(en.getKey());
        }
        for (long k : dead) {
            Entry e = cache.remove(k);
            if (e != null) release(e);
        }
    }

    private void release(Entry e) {
        if (e.id != null) Minecraft.getInstance().getTextureManager().release(e.id);
        if (e.tex != null) e.tex.close();
    }

    @Override
    public boolean shouldRenderOffScreen(TerritoryTableBlockEntity be) {
        return true;
    }
}
