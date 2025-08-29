package seq.sequencermod.client.preview;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Превью‑рендер AEC:
 *  - renderOffscreenAndBlit: рендер в FBO и блит в прямоугольник GUI (максимально изолированный вариант)
 *  - render2DInRect: 2D‑рендер в экранных координатах
 *  - renderInRect: 3D‑рендер в прямоугольнике GUI
 *  - render: ядро 3D‑рендера (локальный Immediate, безопасные стейты)
 */
public final class ManualAECPreviewRenderer {
    private ManualAECPreviewRenderer() {}

    private static final Identifier WHITE_TEX = new Identifier("minecraft", "textures/misc/white.png");

    // ========= OFFSCREEN → BLIT =========

    private static SimpleFramebuffer PREVIEW_FBO = null;
    private static int FBO_W = 0, FBO_H = 0;

    private static void ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (PREVIEW_FBO != null && FBO_W == w && FBO_H == h) return;
        if (PREVIEW_FBO != null) {
            PREVIEW_FBO.delete();
            PREVIEW_FBO = null;
        }
        PREVIEW_FBO = new SimpleFramebuffer(w, h, true, MinecraftClient.IS_SYSTEM_MAC);
        PREVIEW_FBO.setClearColor(0f, 0f, 0f, 0f);
        FBO_W = w; FBO_H = h;
    }

    public static void renderOffscreenAndBlit(DrawContext ctx,
                                              int x, int y, int w, int h,
                                              AreaEffectCloudEntity aec,
                                              float tickDelta,
                                              Style style) {
        if (aec == null || style == null || w <= 2 || h <= 2) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        var window = mc.getWindow();

        ensureFbo(w, h);

        // 1) Рисуем в FBO
        PREVIEW_FBO.beginWrite(true); // выставляет viewport = w×h
        try {
            RenderSystem.clearColor(0f, 0f, 0f, 0f);
            // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
            RenderSystem.clear(0x4100, MinecraftClient.IS_SYSTEM_MAC);

            MatrixStack ms = new MatrixStack();
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float pixelsPerUnit = Math.min(w, h) * 0.42f;

            ms.push();
            ms.translate(cx, cy, 0f);
            ms.scale(pixelsPerUnit, pixelsPerUnit, pixelsPerUnit);
            render(ms, null, aec, tickDelta, style);
            ms.pop();
        } finally {
            PREVIEW_FBO.endWrite();
            // вернуть viewport окна
            RenderSystem.viewport(0, 0, window.getFramebufferWidth(), window.getFramebufferHeight());
        }

        // 2) Блитим содержимое FBO в прямоугольник GUI
        blitFbo(ctx, PREVIEW_FBO, x, y, w, h);
    }

    // Ручной блит color‑attachment FBO в прямоугольник GUI
    private static void blitFbo(DrawContext ctx, SimpleFramebuffer fb, int x, int y, int w, int h) {
        if (fb == null || w <= 0 || h <= 0) return;

        // Привязываем текстуру color‑attachment
        int tex = fb.getColorAttachment();

        MatrixStack matrices = ctx.getMatrices();
        matrices.push();
        // Чуть "вперёд" по Z, чтоб было поверх любой подложки
        matrices.translate(0, 0, 400);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);

        // ВАЖНО: биндим не ресурс, а сырой id текстуры
        GlStateManager._bindTexture(tex);

        Matrix4f pos = matrices.peek().getPositionMatrix();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // Рисуем quad в пиксельных координатах GUI, UV — 0..1
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buf.vertex(pos, x,     y + h, 0).texture(0f, 1f).next();
        buf.vertex(pos, x,     y,     0).texture(0f, 0f).next();
        buf.vertex(pos, x + w, y,     0).texture(1f, 0f).next();
        buf.vertex(pos, x + w, y + h, 0).texture(1f, 1f).next();
        tess.draw();

        // Возврат GUI‑состояний
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        matrices.pop();
    }

    // ========= 2D ВАРИАНТ =========

    public static void render2DInRect(DrawContext ctx,
                                      int x, int y, int w, int h,
                                      AreaEffectCloudEntity aec,
                                      float tickDelta,
                                      Style style) {
        if (aec == null || style == null || w <= 2 || h <= 2) return;

        MatrixStack matrices = ctx.getMatrices();
        matrices.push();
        matrices.translate(0, 0, 400);

        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;

        float age = aec.age + tickDelta;
        float lifeFade = computeLifeFade(aec, age);
        float pulse = 1.0f + 0.04f * (float) Math.sin(age * 0.12f);

        float basePx = Math.min(w, h) * 0.40f;
        float outerR = basePx * pulse;

        int argb = aec.getColor();
        int override = style.tintOverride != null ? style.tintOverride : argb;
        int a = (override >>> 24) & 0xFF;
        int r = (override >>> 16) & 0xFF;
        int g = (override >>> 8)  & 0xFF;
        int b = (override)        & 0xFF;

        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float baseA = Math.max(0.25f, (a == 0 ? 255 : a) / 255f) * lifeFade;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1, 1, 1, 1);

        // Базовый диск
        if (style.renderBaseDisk) {
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();
            Matrix4f pos = matrices.peek().getPositionMatrix();

            float innerAlpha = baseA * 0.90f;
            float outerAlpha = baseA * 0.18f;

            int segs = 90;
            float wob = style.edgeWobble;
            float wobFreq = 7.0f;
            float wobSpeed = 0.35f;

            buf.begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_COLOR);
            buf.vertex(pos, cx, cy, 0).color(rf, gf, bf, innerAlpha).next();
            for (int i = 0; i <= segs; i++) {
                double ang = (Math.PI * 2.0) * i / segs;
                float wobMul = 1.0f + wob * (float) Math.sin((float) ang * wobFreq + age * wobSpeed);
                float rx = (float) Math.cos(ang);
                float ry = (float) Math.sin(ang);
                float px = cx + rx * outerR * wobMul;
                float py = cy + ry * outerR * wobMul;
                buf.vertex(pos, px, py, 0).color(rf, gf, bf, outerAlpha).next();
            }
            tess.draw();
        }

        // Спрайты
        if (style.renderSoftPuffs || style.renderPotionSprites) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            RenderSystem.setShaderTexture(0, SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();

            Sprite[] frames = getEffectFrames();

            int baseCount = (int) (120 + aec.getRadius() * 60);
            int count = Math.max(60, Math.min(360, Math.round(baseCount * style.spriteDensity)));

            java.util.Random seeded = new java.util.Random(aec.getUuid().getLeastSignificantBits() ^ 0x9E3779B97F4A7C15L);
            float alpha = baseA * (style.renderPotionSprites ? style.spriteAlpha : 0.40f);

            float sBase = Math.min(w, h) * 0.40f * 0.18f;
            Matrix4f pos = matrices.peek().getPositionMatrix();

            for (int i = 0; i < count; i++) {
                float a0 = (i / (float) count) * (float) (Math.PI * 2.0);
                float jitter = (seeded.nextFloat() - 0.5f) * 0.8f;
                float speed = 0.3f + seeded.nextFloat() * 0.8f;
                float ang = a0 + jitter + age * 0.15f * speed;

                float dist = Math.min(w, h) * 0.40f * (0.25f + seeded.nextFloat() * 0.60f);
                float sx = cx + (float) Math.cos(ang) * dist;
                float sy = cy + (float) Math.sin(ang) * dist;

                float s = sBase * (0.8f + seeded.nextFloat() * 0.6f);
                float hs = s * 0.5f;

                int fi = Math.floorMod((int) Math.floor(age * 8.0f + i * 0.37f), frames.length);
                Sprite sp = frames[fi];
                float u0 = sp.getMinU(), v0 = sp.getMinV(), u1 = sp.getMaxU(), v1 = sp.getMaxV();

                buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
                buf.vertex(pos, sx - hs, sy + hs, 0).texture(u0, v1).color(rf, gf, bf, alpha).next();
                buf.vertex(pos, sx - hs, sy - hs, 0).texture(u0, v0).color(rf, gf, bf, alpha).next();
                buf.vertex(pos, sx + hs, sy - hs, 0).texture(u1, v0).color(rf, gf, bf, alpha).next();
                buf.vertex(pos, sx + hs, sy + hs, 0).texture(u1, v1).color(rf, gf, bf, alpha).next();
                tess.draw();
            }
        }

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        matrices.pop();
    }

    // ========= 3D ВАРИАНТЫ =========

    public static void renderInRect(MatrixStack matrices,
                                    int x, int y, int w, int h,
                                    VertexConsumerProvider.Immediate ignoredSharedImmediate,
                                    AreaEffectCloudEntity aec,
                                    float tickDelta,
                                    Style style) {
        if (aec == null || style == null || w <= 2 || h <= 2) return;
        float cx = x + w * 0.5f;
        float cy = y + h * 0.5f;
        float pixelsPerUnit = Math.min(w, h) * 0.42f;

        matrices.push();
        matrices.translate(cx, cy, 0.0f);
        matrices.scale(pixelsPerUnit, pixelsPerUnit, pixelsPerUnit);
        render(matrices, null, aec, tickDelta, style);
        matrices.pop();
    }

    public static void render(MatrixStack matrices,
                              VertexConsumerProvider.Immediate ignoredSharedImmediate,
                              AreaEffectCloudEntity aec,
                              float tickDelta,
                              Style style) {

        Tessellator localTess = new Tessellator(1 << 16);
        VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(localTess.getBuffer());

        final float yOffset = 0.12F;
        final float tiltDeg = -18.0F;

        float radius = aec.getRadius();
        if (!(radius > 0.0F) || Float.isNaN(radius) || Float.isInfinite(radius)) radius = 1.5F;
        float sizeBlocks = Math.max(0.6F, Math.min(radius, 3.0F));

        float age = (aec.age + tickDelta);
        float lifeFade = computeLifeFade(aec, age);
        float pulse = 1.0f + 0.04f * (float) Math.sin(age * 0.12f);
        float outerR = Math.max(0.6F, Math.min(sizeBlocks, 3.0F)) * 0.24F * pulse;

        int argb = aec.getColor();
        int override = style.tintOverride != null ? style.tintOverride : argb;
        int a = (override >>> 24) & 0xFF;
        int r = (override >>> 16) & 0xFF;
        int g = (override >>> 8)  & 0xFF;
        int b = (override)        & 0xFF;

        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float baseA = Math.max(0.25f, (a == 0 ? 255 : a) / 255f) * lifeFade;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1, 1, 1, 1);

        matrices.push();
        matrices.translate(0.0F, yOffset, 0.0F);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(tiltDeg));

        final int light = 0x00F000F0;

        if (style.renderPotionSprites) {
            renderPotionSprites(
                    matrices, immediate,
                    outerR, rf, gf, bf, baseA * style.spriteAlpha,
                    age, radius, light, style,
                    tiltDeg,
                    aec.getUuid().getLeastSignificantBits()
            );
        } else {
            if (style.renderBaseDisk) {
                VertexConsumer vc = immediate.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEX));

                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(age * 8.0f));

                float innerAlpha = baseA * 0.90f;
                float outerAlpha = baseA * 0.18f;

                final int SEGMENTS = 80;
                final float EDGE_WOBBLE = style.edgeWobble;
                final float WOBBLE_FREQ = 7.0f;
                final float WOBBLE_SPEED = 0.35f;

                float innerR = Math.max(outerR * 0.006f, 0.001f);

                for (int i = 0; i < SEGMENTS; i++) {
                    double ang0 = (i / (double) SEGMENTS) * Math.PI * 2.0;
                    double ang1 = ((i + 1) / (double) SEGMENTS) * Math.PI * 2.0;

                    float wob0 = 1.0f + EDGE_WOBBLE * (float) Math.sin((float) ang0 * WOBBLE_FREQ + age * WOBBLE_SPEED);
                    float wob1 = 1.0f + EDGE_WOBBLE * (float) Math.sin((float) ang1 * WOBBLE_FREQ + age * WOBBLE_SPEED);

                    float or0 = outerR * wob0;
                    float or1 = outerR * wob1;

                    float ox0 = (float) Math.cos(ang0) * or0;
                    float oz0 = (float) Math.sin(ang0) * or0;
                    float ox1 = (float) Math.cos(ang1) * or1;
                    float oz1 = (float) Math.sin(ang1) * or1;

                    float ix0 = (float) Math.cos(ang0) * innerR;
                    float iz0 = (float) Math.sin(ang0) * innerR;
                    float ix1 = (float) Math.cos(ang1) * innerR;
                    float iz1 = (float) Math.sin(ang1) * innerR;

                    Matrix4f pos = matrices.peek().getPositionMatrix();
                    Matrix3f nrm = matrices.peek().getNormalMatrix();

                    vc.vertex(pos, ix0, 0.0f, iz0).color(rf, gf, bf, innerAlpha)
                            .texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV)
                            .light(light).normal(nrm, 0, 1, 0).next();

                    vc.vertex(pos, ox0, 0.0f, oz0).color(rf, gf, bf, outerAlpha)
                            .texture(0.5f, 0.0f).overlay(OverlayTexture.DEFAULT_UV)
                            .light(light).normal(nrm, 0, 1, 0).next();

                    vc.vertex(pos, ox1, 0.0f, oz1).color(rf, gf, bf, outerAlpha)
                            .texture(1.0f, 0.0f).overlay(OverlayTexture.DEFAULT_UV)
                            .light(light).normal(nrm, 0, 1, 0).next();

                    vc.vertex(pos, ix1, 0.0f, iz1).color(rf, gf, bf, innerAlpha)
                            .texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV)
                            .light(light).normal(nrm, 0, 1, 0).next();
                }
            }

            if (style.renderSoftPuffs) {
                int baseCount = 28 + (int)(radius * 6.0f);
                int puffCount = Math.min(64, Math.max(20, Math.round(baseCount * style.puffCountMul)));

                java.util.Random seeded = new java.util.Random(aec.getUuid().getLeastSignificantBits());
                VertexConsumer vc2 = immediate.getBuffer(RenderLayer.getEntityTranslucent(WHITE_TEX));

                for (int i = 0; i < puffCount; i++) {
                    float baseAng = (i / (float) puffCount) * (float)(Math.PI * 2.0);
                    float jitter = (seeded.nextFloat() - 0.5f) * 0.5f;
                    float speed = 0.3f + seeded.nextFloat() * 0.8f;
                    float ang = baseAng + jitter + age * 0.15f * speed;

                    float dist = outerR * (0.25f + seeded.nextFloat() * 0.60f);
                    float puffR = outerR * (0.07f + seeded.nextFloat() * 0.12f)
                            * (0.9f + 0.2f * (float) Math.sin(age * (0.6f + speed * 0.2f)));

                    float puffAlpha = baseA * (0.14f + seeded.nextFloat() * 0.20f);

                    float cx = (float) Math.cos(ang) * dist;
                    float cz = (float) Math.sin(ang) * dist;

                    matrices.push();
                    matrices.translate(cx, 0.0f, cz);

                    final int SEG_SMALL = 24;
                    float innerR = Math.max(puffR * 0.12f, 0.001f);
                    float innerAlpha = puffAlpha;
                    float outerAlpha = puffAlpha * 0.05f;

                    Matrix4f pos = matrices.peek().getPositionMatrix();
                    Matrix3f nrm = matrices.peek().getNormalMatrix();

                    for (int s = 0; s < SEG_SMALL; s++) {
                        double a0 = (s / (double) SEG_SMALL) * Math.PI * 2.0;
                        double a1 = ((s + 1) / (double) SEG_SMALL) * Math.PI * 2.0;

                        float ox0 = (float) Math.cos(a0) * puffR;
                        float oz0 = (float) Math.sin(a0) * puffR;
                        float ox1 = (float) Math.cos(a1) * puffR;
                        float oz1 = (float) Math.sin(a1) * puffR;

                        float ix0 = (float) Math.cos(a0) * innerR;
                        float iz0 = (float) Math.sin(a0) * innerR;
                        float ix1 = (float) Math.cos(a1) * innerR;
                        float iz1 = (float) Math.sin(a1) * innerR;

                        vc2.vertex(pos, ix0, 0.0f, iz0).color(rf, gf, bf, innerAlpha)
                                .texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV)
                                .light(light).normal(nrm, 0, 1, 0).next();

                        vc2.vertex(pos, ox0, 0.0f, oz0).color(rf, gf, bf, outerAlpha)
                                .texture(0.5f, 0.0f).overlay(OverlayTexture.DEFAULT_UV)
                                .light(light).normal(nrm, 0, 1, 0).next();

                        vc2.vertex(pos, ox1, 0.0f, oz1).color(rf, gf, bf, outerAlpha)
                                .texture(1.0f, 0.0f).overlay(OverlayTexture.DEFAULT_UV)
                                .light(light).normal(nrm, 0, 1, 0).next();

                        vc2.vertex(pos, ix1, 0.0f, iz1).color(rf, gf, bf, innerAlpha)
                                .texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV)
                                .light(light).normal(nrm, 0, 1, 0).next();
                    }

                    matrices.pop();
                }
            }
        }

        immediate.draw();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);

        matrices.pop();
    }

    private static float computeLifeFade(AreaEffectCloudEntity aec, float age) {
        int duration = aec.getDuration();
        float fadeIn  = Math.min(1.0f, age / 10.0f);
        float fadeOut = duration > 0 ? Math.min(1.0f, Math.max(0.0f, (duration - age) / 10.0f)) : 1.0f;
        return Math.max(0.15f, Math.min(fadeIn, fadeOut));
    }

    private static void renderPotionSprites(MatrixStack matrices,
                                            VertexConsumerProvider.Immediate immediate,
                                            float outerR,
                                            float rf, float gf, float bf,
                                            float alpha,
                                            float age, float radius,
                                            int light,
                                            Style style,
                                            float tiltDeg,
                                            long seedLSB) {

        Sprite[] frames = getEffectFrames();
        VertexConsumer vc = immediate.getBuffer(RenderLayer.getEntityTranslucent(SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE));

        int baseCount = (int) (120 + radius * 60);
        int count = Math.max(60, Math.min(360, Math.round(baseCount * style.spriteDensity)));

        java.util.Random seeded = new java.util.Random(seedLSB ^ 0xA1EEC10DL);

        final int framesCount = frames.length;
        final float fps = 8.0f;
        float globalFrame = (age * fps);

        for (int i = 0; i < count; i++) {
            float a0 = (i / (float) count) * (float) (Math.PI * 2.0);
            float jitter = (seeded.nextFloat() - 0.5f) * 0.8f;
            float ang = a0 + jitter;

            float rUnit = 0.10f + 0.90f * seeded.nextFloat();
            float dist = outerR * rUnit;

            float cx = (float) Math.cos(ang) * dist;
            float cz = (float) Math.sin(ang) * dist;

            float s = outerR * (0.07f + seeded.nextFloat() * 0.10f);
            float hs = s * 0.5f;

            matrices.push();
            matrices.translate(cx, 0.0f, cz);
            matrices.multiply(RotationAxis.NEGATIVE_X.rotationDegrees(tiltDeg));

            Matrix4f pos = matrices.peek().getPositionMatrix();
            Matrix3f nrm = matrices.peek().getNormalMatrix();

            Sprite sp = frames[Math.floorMod((int) Math.floor(globalFrame + i * 0.37f), framesCount)];
            float u0 = sp.getMinU(), v0 = sp.getMinV(), u1 = sp.getMaxU(), v1 = sp.getMaxV();

            vc.vertex(pos, -hs,  hs, 0.0f).color(rf, gf, bf, alpha)
                    .texture(u0, v1).overlay(OverlayTexture.DEFAULT_UV)
                    .light(light).normal(nrm, 0, 0, 1).next();

            vc.vertex(pos, -hs, -hs, 0.0f).color(rf, gf, bf, alpha)
                    .texture(u0, v0).overlay(OverlayTexture.DEFAULT_UV)
                    .light(light).normal(nrm, 0, 0, 1).next();

            vc.vertex(pos,  hs, -hs, 0.0f).color(rf, gf, bf, alpha)
                    .texture(u1, v0).overlay(OverlayTexture.DEFAULT_UV)
                    .light(light).normal(nrm, 0, 0, 1).next();

            vc.vertex(pos,  hs,  hs, 0.0f).color(rf, gf, bf, alpha)
                    .texture(u1, v1).overlay(OverlayTexture.DEFAULT_UV)
                    .light(light).normal(nrm, 0, 0, 1).next();

            matrices.pop();
        }
    }

    private static Sprite[] EFFECT_FRAMES_CACHE = null;
    private static Sprite[] getEffectFrames() {
        if (EFFECT_FRAMES_CACHE != null) return EFFECT_FRAMES_CACHE;

        final int expected = 8;
        Sprite[] frames = new Sprite[expected];

        var atlas = MinecraftClient.getInstance().getSpriteAtlas(SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE);
        int loaded = 0;
        for (int i = 0; i < expected; i++) {
            Identifier id = new Identifier("minecraft", "particle/effect_" + i);
            Sprite sp = atlas.apply(id);
            if (sp != null && sp.getContents() != null) {
                frames[i] = sp;
                loaded++;
            }
        }
        if (loaded == 0) {
            Sprite fallback = atlas.apply(new Identifier("minecraft", "particle/witch"));
            if (fallback == null) fallback = atlas.apply(new Identifier("minecraft", "particle/crit"));
            for (int i = 0; i < expected; i++) frames[i] = fallback;
        }
        EFFECT_FRAMES_CACHE = frames;
        return frames;
    }

    // ===== Style =====
    public static final class Style {
        public final String name;
        public final int swirlFreq;
        public final float edgeWobble;
        public final float puffCountMul;
        public final Integer tintOverride;

        public final boolean renderPotionSprites;
        public final boolean renderBaseDisk;
        public final boolean renderSoftPuffs;
        public final int spriteGrid;
        public final float spriteDensity;
        public final float spriteAlpha;

        private Style(Builder b) {
            this.name = b.name;
            this.swirlFreq = b.swirlFreq;
            this.edgeWobble = b.edgeWobble;
            this.puffCountMul = b.puffCountMul;
            this.tintOverride = b.tintOverride;

            this.renderPotionSprites = b.renderPotionSprites;
            this.renderBaseDisk = b.renderBaseDisk;
            this.renderSoftPuffs = b.renderSoftPuffs;
            this.spriteGrid = b.spriteGrid;
            this.spriteDensity = b.spriteDensity;
            this.spriteAlpha = b.spriteAlpha;
        }

        public Builder toBuilder() { return new Builder(this); }
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String name = "";
            private int swirlFreq = 0;
            private float edgeWobble = 0.05f;
            private float puffCountMul = 1.0f;
            private Integer tintOverride = null;

            private boolean renderPotionSprites = false;
            private boolean renderBaseDisk = true;
            private boolean renderSoftPuffs = true;
            private int spriteGrid = 8;
            private float spriteDensity = 1.0f;
            private float spriteAlpha = 0.90f;

            private Builder() {}
            private Builder(Style s) {
                this.name = s.name;
                this.swirlFreq = s.swirlFreq;
                this.edgeWobble = s.edgeWobble;
                this.puffCountMul = s.puffCountMul;
                this.tintOverride = s.tintOverride;

                this.renderPotionSprites = s.renderPotionSprites;
                this.renderBaseDisk = s.renderBaseDisk;
                this.renderSoftPuffs = s.renderSoftPuffs;
                this.spriteGrid = s.spriteGrid;
                this.spriteDensity = s.spriteDensity;
                this.spriteAlpha = s.spriteAlpha;
            }

            public Builder name(String v) { this.name = v; return this; }
            public Builder swirlFreq(int v) { this.swirlFreq = v; return this; }
            public Builder edgeWobble(float v) { this.edgeWobble = v; return this; }
            public Builder puffCountMul(float v) { this.puffCountMul = v; return this; }
            public Builder tintOverride(Integer v) { this.tintOverride = v; return this; }

            public Builder renderPotionSprites(boolean v) { this.renderPotionSprites = v; return this; }
            public Builder renderBaseDisk(boolean v) { this.renderBaseDisk = v; return this; }
            public Builder renderSoftPuffs(boolean v) { this.renderSoftPuffs = v; return this; }
            public Builder spriteGrid(int v) { this.spriteGrid = v; return this; }
            public Builder spriteDensity(float v) { this.spriteDensity = v; return this; }
            public Builder spriteAlpha(float v) { this.spriteAlpha = v; return this; }

            public Style build() { return new Style(this); }
        }
    }
}