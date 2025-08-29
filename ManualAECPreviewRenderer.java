package seq.sequencermod.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
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
 *  - «диск + пухи» (Dragon's Breath и др.)
 *  - ванильный Potion cloud из кадров атласа частиц particle/effect_0..7.
 *
 * Важно: рендерер изолирован — использует собственный Tessellator/Immediate
 * и сам восстанавливает GL‑состояние, чтобы не портить UI.
 */
public final class ManualAECPreviewRenderer {
    private ManualAECPreviewRenderer() {}

    public static void render(MatrixStack matrices,
                              VertexConsumerProvider.Immediate ignoredSharedImmediate,
                              AreaEffectCloudEntity aec,
                              float tickDelta,
                              Style style) {

        // 1) Локальный буфер (НЕ общий Tessellator.getInstance())
        Tessellator localTess = new Tessellator(1 << 16); // 64k — с запасом
        VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(localTess.getBuffer());

        // 2) Подготовка матриц
        final float yOffset = 0.12F;
        final float tiltDeg = -18.0F;

        float radius = aec.getRadius();
        if (!(radius > 0.0F) || Float.isNaN(radius) || Float.isInfinite(radius)) radius = 1.5F;
        float sizeBlocks = Math.max(0.6F, Math.min(radius, 3.0F));
        float visualScale = 0.24F;

        float age = (aec.age + tickDelta);
        float lifeFade = computeLifeFade(aec, age);
        float pulse = 1.0f + 0.04f * (float) Math.sin(age * 0.12f);
        float outerR = sizeBlocks * visualScale * pulse;

        int argb = aec.getColor();
        int override = style.tintOverride != null ? style.tintOverride : argb;
        int a = (override >>> 24) & 0xFF;
        int r = (override >>> 16) & 0xFF;
        int g = (override >>> 8)  & 0xFF;
        int b = (override)        & 0xFF;
        float baseA = Math.max(0.25f, (a == 0 ? 255 : a) / 255.0f) * lifeFade;
        float rf = r / 255.0f, gf = g / 255.0f, bf = b / 255.0f;

        // 3) Сохраним/зададим безопасные для GUI стейты
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // 4) Рисуем
        matrices.push();
        matrices.translate(0.0F, yOffset, 0.0F);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(tiltDeg));

        final int light = 0x00F000F0;

        if (style.renderPotionSprites) {
            renderPotionSprites(
                    matrices, immediate,
                    outerR,
                    rf, gf, bf, baseA * style.spriteAlpha,
                    age, radius, light, style,
                    tiltDeg,
                    aec.getUuid().getLeastSignificantBits()
            );
        } else {
            // Белая текстура для «диска/пухов» — объявляем ОДИН раз и переиспользуем
            Identifier white = new Identifier("minecraft", "textures/misc/white.png");

            // 4.1) Базовый диск
            if (style.renderBaseDisk) {
                VertexConsumer vc = immediate.getBuffer(RenderLayer.getEntityTranslucent(white));

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

            // 4.2) Пухи
            if (style.renderSoftPuffs) {
                int baseCount = 28 + (int)(radius * 6.0f);
                int puffCount = Math.min(64, Math.max(20, Math.round(baseCount * style.puffCountMul)));

                java.util.Random seeded = new java.util.Random(aec.getUuid().getLeastSignificantBits());

                // ИСПОЛЬЗУЕМ уже объявленный выше 'white'
                VertexConsumer vc2 = immediate.getBuffer(RenderLayer.getEntityTranslucent(white));

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

        // 5) Флашим ТОЛЬКО локальный буфер
        immediate.draw();

        // 6) Восстанавливаем дефолты GUI
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        matrices.pop();
    }

    private static float computeLifeFade(AreaEffectCloudEntity aec, float age) {
        int duration = aec.getDuration();
        float fadeIn  = Math.min(1.0f, age / 10.0f);
        float fadeOut = duration > 0 ? Math.min(1.0f, Math.max(0.0f, (duration - age) / 10.0f)) : 1.0f;
        return Math.max(0.15f, Math.min(fadeIn, fadeOut));
    }

    // Ванильные завитки из атласа частиц: particle/effect_0..7
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

        final int framesCount = frames.length; // обычно 8
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

            float phase = seeded.nextFloat() * framesCount;
            int frameIndex = Math.floorMod((int) Math.floor(globalFrame + phase), framesCount);
            Sprite sprite = frames[frameIndex];

            float u0 = sprite.getMinU();
            float v0 = sprite.getMinV();
            float u1 = sprite.getMaxU();
            float v1 = sprite.getMaxV();

            // Биллбординг: отменяем наклон плоскости, чтобы спрайт смотрел в экран
            matrices.push();
            matrices.translate(cx, 0.0f, cz);
            matrices.multiply(RotationAxis.NEGATIVE_X.rotationDegrees(tiltDeg));

            Matrix4f pos = matrices.peek().getPositionMatrix();
            Matrix3f nrm = matrices.peek().getNormalMatrix();

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

    // ——— КЭШ кадров effect_0..7 ———
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

    // Настройки визуала превью
    public static final class Style {
        public final String name;
        public final int swirlFreq;
        public final float edgeWobble;
        public final float puffCountMul;
        public final Integer tintOverride;

        public final boolean renderPotionSprites;
        public final boolean renderBaseDisk;
        public final boolean renderSoftPuffs;
        public final int spriteGrid;       // для совместимости
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