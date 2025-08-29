package seq.sequencermod.client.preview;

import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.Potion;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Преднастройки визуала и поведения AEC.
 */
public enum CloudPreset {
    POTION_CLOUD(
            // «Ванильный» вид из завитков. Чёрный цвет по просьбе автора.
            ManualAECPreviewRenderer.Style.builder()
                    .name("Potion cloud")
                    .tintOverride(0xFF000000)         // чёрный
                    .renderPotionSprites(true)         // включаем режим спрайлов
                    .renderBaseDisk(false)             // фон не нужен
                    .renderSoftPuffs(false)            // «пухи» не нужны
                    .spriteGrid(8)                     // effect.png как сетка 8×8
                    .spriteDensity(1.15f)              // чуть гуще
                    .spriteAlpha(0.90f)                // заметная, но не глухая
                    .build()
    ) {
        @Override
        public void applyToEntity(AreaEffectCloudEntity aec, @Nullable Custom custom) {
            aec.setParticleType(ParticleTypes.ENTITY_EFFECT);
            // Цвет можно оставить как в превью (чёрный) или взять из зелья:
            aec.setColor(0xFF000000);
            // Если задано зелье — применим (перекрасит частицы в мире, но превью остаётся чёрным)
            if (custom != null && custom.potionId != null && !custom.potionId.isEmpty()) {
                Potion p = Registries.POTION.get(new Identifier(custom.potionId));
                if (p != null) aec.setPotion(p);
            }
            tuneCommon(aec, custom);
        }
    },

    DRAGON_BREATH(
            ManualAECPreviewRenderer.Style.builder()
                    .name("Dragon's Breath")
                    .swirlFreq(6)
                    .edgeWobble(0.06f)
                    .puffCountMul(1.15f)
                    .renderPotionSprites(false)
                    .renderBaseDisk(true)
                    .renderSoftPuffs(true)
                    .tintOverride(0xFFB03CFF)
                    .build()
    ) {
        @Override
        public void applyToEntity(AreaEffectCloudEntity aec, @Nullable Custom custom) {
            aec.setParticleType(ParticleTypes.DRAGON_BREATH);
            tuneCommon(aec, custom);
        }
    },

    CUSTOM(
            ManualAECPreviewRenderer.Style.builder()
                    .name("Custom")
                    .swirlFreq(8)
                    .edgeWobble(0.05f)
                    .puffCountMul(1.0f)
                    .renderPotionSprites(false)
                    .renderBaseDisk(true)
                    .renderSoftPuffs(true)
                    .build()
    ) {
        @Override
        public void applyToEntity(AreaEffectCloudEntity aec, @Nullable Custom custom) {
            if (custom != null) {
                ParticleEffect particle = custom.particleEffect != null
                        ? custom.particleEffect
                        : ParticleTypes.ENTITY_EFFECT;
                aec.setParticleType(particle);
                if (custom.potionId != null && !custom.potionId.isEmpty()) {
                    Potion p = Registries.POTION.get(new Identifier(custom.potionId));
                    if (p != null) aec.setPotion(p);
                }
                if (custom.overrideColor != null) aec.setColor(custom.overrideColor);
            }
            tuneCommon(aec, custom);
        }
    };

    public final ManualAECPreviewRenderer.Style previewStyle;

    CloudPreset(ManualAECPreviewRenderer.Style style) {
        this.previewStyle = style;
    }

    public ManualAECPreviewRenderer.Style previewStyle(@Nullable Custom customOverride) {
        if (this != CUSTOM || customOverride == null) return previewStyle;
        return previewStyle.toBuilder()
                .tintOverride(customOverride.previewTintOverride != null
                        ? customOverride.previewTintOverride
                        : previewStyle.tintOverride)
                .puffCountMul(customOverride.previewPuffMul > 0f
                        ? customOverride.previewPuffMul
                        : previewStyle.puffCountMul)
                .edgeWobble(customOverride.previewEdgeWobble >= 0f
                        ? customOverride.previewEdgeWobble
                        : previewStyle.edgeWobble)
                .renderPotionSprites(customOverride.previewUsePotionSprites != null
                        ? customOverride.previewUsePotionSprites
                        : previewStyle.renderPotionSprites)
                .renderBaseDisk(customOverride.previewRenderBaseDisk != null
                        ? customOverride.previewRenderBaseDisk
                        : previewStyle.renderBaseDisk)
                .renderSoftPuffs(customOverride.previewRenderSoftPuffs != null
                        ? customOverride.previewRenderSoftPuffs
                        : previewStyle.renderSoftPuffs)
                .spriteGrid(customOverride.previewSpriteGrid > 0
                        ? customOverride.previewSpriteGrid
                        : previewStyle.spriteGrid)
                .spriteDensity(customOverride.previewSpriteDensity > 0f
                        ? customOverride.previewSpriteDensity
                        : previewStyle.spriteDensity)
                .spriteAlpha(customOverride.previewSpriteAlpha > 0f
                        ? customOverride.previewSpriteAlpha
                        : previewStyle.spriteAlpha)
                .build();
    }

    public abstract void applyToEntity(AreaEffectCloudEntity aec, @Nullable Custom custom);

    protected static void tuneCommon(AreaEffectCloudEntity aec, @Nullable Custom custom) {
        if (custom == null) return;
        if (custom.radius != null) aec.setRadius(custom.radius);
        if (custom.duration != null) aec.setDuration(custom.duration);
        if (custom.waitTime != null) aec.setWaitTime(custom.waitTime);
        if (custom.reapplicationDelay != null) {
            setReapplicationDelayCompat(aec, custom.reapplicationDelay.intValue());
        }
        if (custom.radiusOnUse != null) aec.setRadiusOnUse(custom.radiusOnUse);
        if (custom.radiusPerTick != null) aec.setRadiusGrowth(custom.radiusPerTick);
    }

    private static void setReapplicationDelayCompat(AreaEffectCloudEntity aec, int delay) {
        try {
            AreaEffectCloudEntity.class.getMethod("setReapplicationDelay", int.class).invoke(aec, delay);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException e) {
            System.out.println("[SequencerPreview] AEC: setReapplicationDelay reflection failed: " + e);
            return;
        }
        try {
            AreaEffectCloudEntity.class.getMethod("setReapplicationDelayTicks", int.class).invoke(aec, delay);
        } catch (ReflectiveOperationException e) {
            System.out.println("[SequencerPreview] AEC: reapplication delay setter not found in this mapping");
        }
    }

    public static class Custom {
        public Float radius;
        public Integer duration;
        public Integer waitTime;
        public Integer reapplicationDelay;
        public Float radiusOnUse;
        public Float radiusPerTick;

        public String potionId;
        public Integer overrideColor;
        public ParticleEffect particleEffect;

        // Настройки превью
        public Integer previewTintOverride;
        public float previewPuffMul = -1f;
        public float previewEdgeWobble = -1f;
        public Boolean previewUsePotionSprites;
        public Boolean previewRenderBaseDisk;
        public Boolean previewRenderSoftPuffs;
        public int previewSpriteGrid = -1;
        public float previewSpriteDensity = -1f;
        public float previewSpriteAlpha = -1f;
    }
}