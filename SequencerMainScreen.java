package seq.sequencermod.client.ui;

import net.minecraft.client.gui.widget.CyclingButtonWidget;
import seq.sequencermod.client.preview.CloudPreset;
import net.minecraft.client.render.entity.EntityRenderer; // можно удалить, если не используете напрямую
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.OverlayTexture;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexFormat;

import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.mob.EvokerFangsEntity;

// JOML — в 1.20.1 MatrixStack возвращает org.joml.Matrix4f
import org.joml.Matrix4f;
import com.mojang.authlib.GameProfile; // можно удалить, если не используется
import net.minecraft.client.network.OtherClientPlayerEntity; // можно удалить, если не используется
import net.minecraft.client.world.ClientWorld; // можно удалить, если не используется
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix3f;

import net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity;
import net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity;
import net.minecraft.entity.projectile.FishingBobberEntity; // можно удалить, если не используется
import net.minecraft.text.Text;
import org.joml.Quaternionf;
import org.joml.Vector3f; // можно удалить, если не используется
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11; // ДОБАВЛЕНО: для glFrontFace(...)
import seq.sequencermod.client.preview.PreviewDebug; // ДОБАВЛЕНО: если используете короткое имя PreviewDebug.inGui(...)

import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// AWT fallback (для диалога выбора файла, если нет NFD)
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.HeadlessException;

public class SequencerMainScreen extends Screen {

    private CloudPreset currentPreset = CloudPreset.POTION_CLOUD;
    @Nullable
    private CloudPreset.Custom custom = null;

    // UI-элемент для выбора пресета AEC
    private CyclingButtonWidget<CloudPreset> aecPresetButton;

    private int dbgEntityRenderErrors = 0;
    private static final int PAD = 8;
    private static final int TAB_H = 20;

    private static final int ROW_H = 20;
    private static final int V_GAP = 2;
    private static final int ROW = ROW_H + V_GAP;

    private static final int HISTORY_LIMIT = 50;
    private static final boolean SHOW_DEBUG_MARKS = true;


    private static final boolean VERBOSE_LOG = true;

    private static final long DBG_DEDUP_WINDOW_MS = 300;       // окно склейки повторов
    private static final int  DBG_REPEAT_FLUSH_EVERY = 200;    // каждые N повторов печатать прогресс
    private static String DBG_FILTER = null;                   // подстрока-фильтр (задаётся через -Dsequencer.debug.filter=...)
    private static long   DBG_LAST_MS = 0L;
    private static String DBG_LAST = null;
    private static int    DBG_REPEAT = 0;

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private enum Tab { MORPHS, PRESETS, SETTINGS }
    private Tab currentTab = Tab.PRESETS;

    // Data
    private Map<String, SequencePreset> presets;
    private String selectedPresetName;

    // Undo/Redo
    private final Deque<StepsSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<StepsSnapshot> redoStack = new ArrayDeque<>();

    // Morphs
    private TextFieldWidget searchBox;
    private MorphList morphList;
    private int morphCountShown = 0;
    private EntityType<?> selectedEntityType;
    private LivingEntity previewEntity;
    private ButtonWidget presetCycleBtn;
    private long searchLastChangeMs = 0L;

    // Preview geometry/interaction
    private int morphPrevLeft = 0, morphPrevTop = 0, morphPrevRight = 0, morphPrevBottom = 0;
    private float morphZoomUser = 1.0f;
    private boolean morphDragging = false;
    private double morphLastMouseX = 0, morphLastMouseY = 0;
    private float morphRotX = 0f, morphRotY = 0f;
    private long morphLastClickMs = 0L;
    private long previewLastClickMs = 0L;

    // Presets
    private PresetList presetList;
    private StepList stepList;
    private ControlPanelList panelList;

    // Right panel
    private TextFieldWidget entityIdBox;
    private TextFieldWidget durationBox;
    private TextFieldWidget presetNameBox;

    // Comments for steps
    private TextFieldWidget commentBox;

    // Player
    private TextFieldWidget startDelayBox;
    private TextFieldWidget loopCountBox;
    private boolean loopInfinite = false;

    // Mass edit
    private TextFieldWidget bulkTicksBox;

    // Extra mass ops
    private TextFieldWidget bulkPercentBox;
    private TextFieldWidget multipleBox;
    private TextFieldWidget replaceIdBox;

    // Inline tick editor
    private TextFieldWidget inlineEditField;
    private boolean inlineEditVisible = false;
    private int inlineEditIndex = -1;
    private int inlineEditModelIndex = -1; // модельный индекс редактируемого шага

    // Clipboard (steps + comments)
    private final List<SequencePreset.Step> stepClipboard = new ArrayList<>();
    private final List<String> commentsClipboard = new ArrayList<>();

    // Tab buttons
    private ButtonWidget tabMorphsBtn;
    private ButtonWidget tabPresetsBtn;
    private ButtonWidget tabSettingsBtn;

    // JSON/config
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Hotkeys (rebindable)
    private static KeyConfig keyConfig = new KeyConfig();
    private Path keyConfigPath;
    private enum WaitingKey { NONE, PLAY_PAUSE, STOP, RESTART, ADD_STEP }
    private WaitingKey waitingKey = WaitingKey.NONE;

    // Context menu
    private ContextMenu contextMenu;

    // Confirm dialog (delete)
    private boolean confirmDeleteVisible = false;
    private int confirmDeleteCount = 0;
    private int confirmDlgX, confirmDlgY, confirmDlgW = 260, confirmDlgH = 110;
    private int confirmYesX, confirmYesY, confirmNoX, confirmNoY, confirmBtnW = 100, confirmBtnH = 20;
    private Runnable onConfirmDelete;

    // Status line
    private String statusMsg = "";
    private long statusUntilMs = 0L;

    // Double-click in stepList
    private long lastClickMs = 0L;
    private int lastClickIndex = -1;

    // UI config
    private static UiConfig uiConfig = new UiConfig();
    private Path uiConfigPath;
    private TextFieldWidget uiStepHBox, uiLeftPctBox, uiRightPctBox, uiMenuMaxItemsBox, uiPreviewScaleBox;

    // Hotkeys overlay
    private boolean hotkeysOverlayVisible = false;
    private int hotkeysScroll = 0;
    private List<String> hotkeysLines = Collections.emptyList();

    // Timeline
    private int timelineLeft = 0, timelineTop = 0, timelineRight = 0, timelineBottom = 0;
    private static final int TIMELINE_H = 36;

    private static final boolean SAFE_NO_3D_RENDER_IN_MORPHS = false;   // большое превью остаётся выключенным
    private static final boolean SAFE_NO_ENTITY_CREATION_IN_MORPHS = false; // иконки снова разрешены

    // === Режим выбора морфа через вкладку MORPHS ===
    private boolean morphPickMode = false;
    private String morphPickReturnPreset = "";
    private int morphPickReturnModelIndex = -1;
    private boolean morphPickApplyToSelection = false; // на будущее
    private long entityIdLastClickMs = 0L;             // даблклик по контролу морфа
    private static final int FOOTER_H = 24;

    // ===== Panel resizers (подготовка к п.1) =====
    private static final int RESIZER_W = 5; // визуальная ширина “полоски” разделителя
    private boolean draggingLeftResizer = false;
    private boolean draggingRightResizer = false;
    private int resizerDragStartX = 0;
    private int ghostLeftResizerX = -1;   // “призрачная” позиция во время drag
    private int ghostRightResizerX = -1;

    // Текущие позиции разделителей (в пикселях) — вычисляются в buildPresetsTab()
    private int leftResizerX = -1;   // граница между левой и средней колонкой
    private int rightResizerX = -1;  // граница между средней и правой колонкой

    // ===== Step filter (подготовка к п.2) =====
    private TextFieldWidget stepFilterBox;
    private String stepFilterLower = "";   // только подсветка (скрытие добавлю в части 2)

    // ===== Inline ID autocomplete (подготовка к п.3) =====
    private boolean idAutocompleteVisible = false;
    private List<String> allEntityIds = null;     // кэш всех entityId
    private List<String> idSuggestions = new ArrayList<>();
    private int idSuggestIndex = 0;
    private int idSuggestScroll = 0;
    private static final int ID_SUGGEST_MAX_VISIBLE = 10;
    private int idPopupX = 0, idPopupY = 0, idPopupW = 0, idPopupH = 0;
    private int idPopupItemH = 12;

    // ===== Timeline scrubbing + edge adjust (подготовка к п.4) =====
    private boolean timelineScrubbing = false;      // drag по таймлайну
    private boolean timelineEdgeAdjust = false;     // Shift+drag “граница шага”
    private int timelineScrubMouseX = 0;            // текущая x позиция во время скраббинга

    // ===== Edge adjust (Shift+Drag) по таймлайну =====
    private boolean timelineEdgeActive = false;   // активное перетаскивание границы
    private int edgeBoundaryIndex = -1;          // индекс границы (между шагами i и i+1)
    private int edgeStartMouseX = 0;             // пиксели старта
    private boolean edgeUndoPushed = false;      // чтобы не пушить Undo на каждый пиксель
    private int edgeLastAppliedDelta = 0; // последний применённый deltaTicks

    // ===== Фильтр шагов: маппинг “вид → модель” (для скрытия несоответствий) =====
    private final List<Integer> stepViewToModel = new ArrayList<>();
    private int[] stepModelToView = new int[0]; // -1 если модельный индекс не отображается (отфильтрован)

    // Для конвертации пикселей в тики
    private int tlX0 = 0;        // x0 области таймлайна (внутренний, с учётом отступов)
    private int tlWidth = 0;     // реальная ширина бара таймлайна (px)

    // ===== Sections / локальные повторы (подготовка к п.5) =====
    private static class Section {
        String name = "";
        int fromIndex = 0;
        int toIndex = 0;
        int repeat = 1;
    }
    // Хранилище секций по имени пресета (заготовка)
    private final Map<String, List<Section>> sectionsByPreset = new HashMap<>();

    // ===== Кэш превью поз/зума (подготовка к п.9) =====
    private static class PreviewState { float rotX, rotY, zoom; }
    private final Map<String, PreviewState> previewCache = new HashMap<>();
    private final Map<String, Item> entityPreviewItems = new HashMap<>();



    // Кэш мини-превью сущностей для списка морфов (LRU до 64)
    private final LinkedHashMap<String, LivingEntity> morphIconCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LivingEntity> eldest) {
            if (size() > 64) {
                try { if (eldest.getValue() != null) eldest.getValue().discard(); } catch (Throwable ignored) {}
                return true;
            }
            return false;
        }
    };

    private final Set<String> badIconIds = new HashSet<>();
    private final Set<String> badPreviewIds = new HashSet<>();

    private final Map<String, Entity> morphIconCacheAny = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Entity> eldest) {
            if (size() > 64) {
                try { if (eldest.getValue() != null) eldest.getValue().discard(); } catch (Throwable ignored) {}
                return true;
            }
            return false;
        }
    };

    // Предпросмотр справа для любой сущности
    private Entity previewEntityAny = null;

    // Замените весь метод на эту версию

    private static Identifier pickAecTexture() {
        MinecraftClient mc = MinecraftClient.getInstance();
        // Список кандидатов для 1.20.1 (первые два — возможные варианты AEC),
        // далее — безопасные fallback'и (particle и white)
        String[] candidates = new String[] {
                "textures/entity/area_effect_cloud/area_effect_cloud.png",
                "textures/entity/area_effect_cloud.png",
                "textures/particle/dragon_breath.png",
                "textures/particle/poof.png",
                "textures/misc/white.png"
        };
        for (String p : candidates) {
            Identifier id = new Identifier("minecraft", p);
            if (mc.getResourceManager().getResource(id).isPresent()) {
                System.out.println("[SequencerPreview] AEC texture pick: " + id);
                return id;
            }
        }
        Identifier fallback = new Identifier("minecraft", "textures/misc/white.png");
        System.out.println("[SequencerPreview] AEC texture pick: fallback " + fallback);
        return fallback;
    }

    private void drawEntityAny(net.minecraft.client.gui.DrawContext ctx, int x, int y, int scale, float rotDX, float rotDY, net.minecraft.entity.Entity e) {
        if (e == null) {
            dbg("drawEntityAny: entity is null");
            return;
        }
        dbg("drawEntityAny: start, type=" + net.minecraft.registry.Registries.ENTITY_TYPE.getId(e.getType()));

        if (e instanceof net.minecraft.entity.projectile.thrown.ThrownItemEntity tie) {
            try {
                net.minecraft.item.ItemStack stack = tie.getStack();
                ctx.drawItem(stack, x - 8, y - 8);
                if (SHOW_DEBUG_MARKS) debugMark(ctx, x - scale + 6, y - scale + 6, 0xAACCFFAA, "ITEM*");
                dbg("drawEntityAny: rendered thrown item as 2D");
            } catch (Throwable t) {
                dbg("drawEntityAny: thrown-item draw failed: " + t);
            }
            return;
        }

        try {
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.clearDepth(1.0);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            // Глобальное отключение cull — пусть останется, но для AEC нам важнее нижеописанные пробы
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            net.minecraft.client.render.DiffuseLighting.enableGuiDepthLighting();
        } catch (Throwable ignored) {
        }

        var matrices = ctx.getMatrices();
        matrices.push();
        matrices.translate(x, y, 1050.0F);
        matrices.scale(1.0F, 1.0F, -1.0F); // стандартный флип по Z в GUI (делает детерминант отрицательным)

        matrices.push();
        matrices.translate(0.0F, 0.0F, 50.0F);

        float scaleFactor = (float) Math.max(8, scale);
        matrices.scale(scaleFactor, scaleFactor, scaleFactor);

        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(180.0F));
        matrices.multiply(net.minecraft.util.math.RotationAxis.NEGATIVE_X.rotationDegrees(rotDY));
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(rotDX));

        if (e instanceof net.minecraft.entity.mob.EvokerFangsEntity) {
            matrices.translate(0.0F, 1.6F, 0.0F);
        }

        net.minecraft.client.render.VertexConsumerProvider.Immediate immediate =
                mc.getBufferBuilders().getEntityVertexConsumers();
        net.minecraft.client.render.entity.EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        try {
            dispatcher.setRotation(new org.joml.Quaternionf());
        } catch (Throwable ignored) {
        }
        try {
            dispatcher.setRenderShadows(false);
        } catch (Throwable ignored) {
        }

        boolean flippedXForAEC = false;
        boolean switchedFrontFace = false;

        try {
            float det = new org.joml.Matrix4f(matrices.peek().getPositionMatrix()).determinant();
            dbg("drawEntityAny: det=" + det + (e instanceof net.minecraft.entity.AreaEffectCloudEntity ? " (AEC)" : ""));
        } catch (Throwable ignored) {}

// Сброс флага (переменная уже объявлена ранее в методе)
        // Сброс флага локального флипа
        flippedXForAEC = false;

// ЗАМЕНА ВЕСЬ БЛОК if (isAEC) { ... } else { ... } НА ЭТО:
        if (e instanceof net.minecraft.entity.AreaEffectCloudEntity aec) {
            // 1) Применяем выбранный пресет к превью‑сущности (частицы/радиус/время/цвет и т.п.)
            currentPreset.applyToEntity(aec, custom);

            // 2) Рисуем безопасным ручным превью‑рендером по стилю текущего пресета
            seq.sequencermod.client.preview.ManualAECPreviewRenderer.render(
                    matrices,
                    immediate,
                    aec,
                    mc.getTickDelta(),
                    currentPreset.previewStyle(custom)
            );

            // ВАЖНО: ManualAECPreviewRenderer сам вызывает immediate.draw(),
            // поэтому здесь НИЧЕГО дополнительно флашить/пушить/попать не нужно.

        } else {
            // Ванильный рендер для всех прочих сущностей
            try {
                dispatcher.render(e, 0.0, 0.0, 0.0, 0.0F, mc.getTickDelta(), matrices, immediate, 0x00F000F0);
                immediate.draw();
            } catch (Throwable t) {
                System.out.println("[SequencerPreview] drawEntityAny: dispatcher.render failed: " + t);
            }
        }


        matrices.pop();
        matrices.pop();

        try {
            net.minecraft.client.render.DiffuseLighting.disableGuiDepthLighting();
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            dispatcher.setRenderShadows(false);
        } catch (Throwable ignored) {}

        if (e instanceof net.minecraft.entity.AreaEffectCloudEntity cloud) {
            drawAreaEffectCloudOverlay(ctx, x, y, scale, cloud);
            if (SHOW_DEBUG_MARKS && PreviewDebug.isMainPreview()) {
                debugMark(ctx, x - scale + 6, y - scale + 20, 0xAA44FF44, "CLOUD");
            }
            dbg("drawEntityAny: AEC overlay drawn");

        } else if (e instanceof net.minecraft.entity.mob.EvokerFangsEntity) {
            if (SHOW_DEBUG_MARKS && PreviewDebug.isMainPreview()) {
                debugMark(ctx, x - scale + 6, y - scale + 6, 0xAAFF4444, "FANGS");
            }
            dbg("drawEntityAny: FANGS marker drawn");

        } else {
            // ВАЖНО: fallback-оверлей AEC теперь только в главном превью
            if (PreviewDebug.isMainPreview() && selectedEntityType == net.minecraft.entity.EntityType.AREA_EFFECT_CLOUD) {
                var tmp = new net.minecraft.entity.AreaEffectCloudEntity(mc.world, 0.0, 0.0, 0.0);
                tmp.setRadius(3.0f);
                tmp.setColor(0xFF55FFFF);
                drawAreaEffectCloudOverlay(ctx, x, y, scale, tmp);
                if (SHOW_DEBUG_MARKS) {
                    debugMark(ctx, x - scale + 6, y - scale + 20, 0xAA44FF44, "CLOUD*");
                }
                dbg("drawEntityAny: tmp AEC overlay drawn (fallback)");
            } else if (PreviewDebug.isMainPreview() && selectedEntityType == net.minecraft.entity.EntityType.EVOKER_FANGS) {
                if (SHOW_DEBUG_MARKS) {
                    debugMark(ctx, x - scale + 6, y - scale + 6, 0xAAFF4444, "FANGS*");
                }
                dbg("drawEntityAny: FANGS* marker drawn (fallback)");
            }
        }
    }

    // Добавь в класс SequencerMainScreen (любой блок private-хелперов)
    private void unbanCloudAndFangsFromBadCaches() {
        String cloud = "minecraft:area_effect_cloud";
        String fangs = "minecraft:evoker_fangs";
        if (badIconIds != null) { badIconIds.remove(cloud); badIconIds.remove(fangs); }
        if (badPreviewIds != null) { badPreviewIds.remove(cloud); badPreviewIds.remove(fangs); }
        if (morphIconCacheAny != null) { morphIconCacheAny.remove(cloud); morphIconCacheAny.remove(fangs); }
        if (previewEntityAny != null) {
            var id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(previewEntityAny.getType());
            if (id != null && (id.toString().equals(cloud) || id.toString().equals(fangs))) {
                try { previewEntityAny.discard(); } catch (Throwable ignored) {}
                previewEntityAny = null;
            }
        }
        dbg("Unban cloud/fangs caches done");
    }


    private static boolean isParamCompatible(Class<?> paramType, Object arg) {
        if (arg == null) return !paramType.isPrimitive();
        Class<?> argType = arg.getClass();
        if (paramType.isAssignableFrom(argType)) return true;
        if (paramType.isPrimitive()) {
            if (paramType == int.class) return argType == Integer.class;
            if (paramType == float.class) return argType == Float.class;
            if (paramType == double.class) return argType == Double.class;
            if (paramType == boolean.class) return argType == Boolean.class;
            if (paramType == long.class) return argType == Long.class;
            if (paramType == short.class) return argType == Short.class;
            if (paramType == byte.class) return argType == Byte.class;
            if (paramType == char.class) return argType == Character.class;
        }
        return false;
    }

    private static boolean tryInvoke1(Object target, String methodName, Object arg) {
        if (target == null || methodName == null) return false;
        Class<?> cls = target.getClass();

        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                Class<?> p = m.getParameterTypes()[0];
                if (!isParamCompatible(p, arg)) continue;
                try { m.setAccessible(true); m.invoke(target, arg); return true; } catch (Throwable ignored) {}
            }
        }
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                Class<?> p = m.getParameterTypes()[0];
                if (!isParamCompatible(p, arg)) continue;
                try { m.setAccessible(true); m.invoke(target, arg); return true; } catch (Throwable ignored) {}
            }
        }
        return false;
    }



    private static boolean trySetIntField(Object target, int value, String... fieldNames) {
        if (target == null || fieldNames == null) return false;
        Class<?> cls = target.getClass();
        for (String name : fieldNames) {
            Class<?> c = cls;
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class || f.getType() == Integer.class) {
                        f.setAccessible(true);
                        f.set(target, value);
                        return true;
                    }
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
        }
        return false;
    }

    private static void dbg(String msg) {
        if (!VERBOSE_LOG) return;

        // Фильтр по подстроке (если задан через -Dsequencer.debug.filter=...)
        if (DBG_FILTER != null && (msg == null || !msg.contains(DBG_FILTER))) return;

        long now = System.currentTimeMillis();

        // Склейка одинаковых подряд сообщений в коротком окне
        if (java.util.Objects.equals(msg, DBG_LAST) && (now - DBG_LAST_MS) <= DBG_DEDUP_WINDOW_MS) {
            DBG_REPEAT++;
            // периодически печатаем прогресс, чтобы видеть активность
            if (DBG_REPEAT % DBG_REPEAT_FLUSH_EVERY == 0) {
                System.out.println("[SequencerPreview] (repeated " + DBG_REPEAT + "x) " + msg);
            }
            return;
        }

        // Если были повторы — финальный флеш о количестве
        if (DBG_REPEAT > 0 && DBG_LAST != null) {
            System.out.println("[SequencerPreview] (last repeated " + DBG_REPEAT + "x) " + DBG_LAST);
            DBG_REPEAT = 0;
        }

        DBG_LAST = msg;
        DBG_LAST_MS = now;
        System.out.println("[SequencerPreview] " + msg);
    }

    private static void dbgFlushRepeats() {
        if (DBG_REPEAT > 0 && DBG_LAST != null) {
            System.out.println("[SequencerPreview] (last repeated " + DBG_REPEAT + "x) " + DBG_LAST);
            DBG_REPEAT = 0;
        }
    }
    // Безопасное создание облака (пробуем разные конструкторы)
    private AreaEffectCloudEntity createAreaEffectCloudSafe() {
        try {
            // В новых маппингах есть конструктор (World, x, y, z)
            var e = new AreaEffectCloudEntity(mc.world, 0.0, 0.0, 0.0);
            return e;
        } catch (Throwable t1) {
            try {
                // Бэкап: (EntityType, World)
                var e = new AreaEffectCloudEntity(EntityType.AREA_EFFECT_CLOUD, mc.world);
                e.setPos(0.0, 0.0, 0.0);
                return e;
            } catch (Throwable t2) {
                // Совсем крайний случай: через фабрику
                try {
                    return (AreaEffectCloudEntity) EntityType.AREA_EFFECT_CLOUD.create(mc.world);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
    }

    // Безопасное создание клыков (пробуем разные конструкторы, потом фабрику)
    private EvokerFangsEntity createEvokerFangsSafe() {
        try {
            var e = new EvokerFangsEntity(EntityType.EVOKER_FANGS, mc.world);
            e.setPos(0.0, 0.0, 0.0);
            return e;
        } catch (Throwable t1) {
            try {
                // В некоторых версиях есть (World, x, y, z, yaw, warmup, LivingEntity owner)
                var ctor = EvokerFangsEntity.class.getDeclaredConstructor(
                        net.minecraft.world.World.class, double.class, double.class, double.class, float.class, int.class, net.minecraft.entity.LivingEntity.class
                );
                ctor.setAccessible(true);
                var e = ctor.newInstance(mc.world, 0.0, 0.0, 0.0, 0.0f, 0, null);
                return e;
            } catch (Throwable t2) {
                try {
                    return (EvokerFangsEntity) EntityType.EVOKER_FANGS.create(mc.world);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
    }


    // Evoker Fangs — warmup через метод/поле
    private static void trySetEvokerFangsWarmup(Object fangs, int warmup) {
        if (tryInvoke1(fangs, "setWarmup", warmup)) return;
        if (tryInvoke1(fangs, "setWarmupTicks", warmup)) return;
        if (tryInvoke1(fangs, "warmup", warmup)) return;
        trySetIntField(fangs, warmup, "warmup", "warmupTicks", "startDelay", "field_15225");
    }

    private void advanceEvokerFangsForPreview(net.minecraft.entity.mob.EvokerFangsEntity f) {
        trySetEvokerFangsWarmup(f, 0);
        try { f.tick(); } catch (Throwable ignored) {}
        try {
            if (f.age >= 12) {
                trySetIntField(f, 20, "lifeTicks", "life", "field_15224");
                f.age = 0;
                trySetEvokerFangsWarmup(f, 0);
            }
        } catch (Throwable ignored) {}
    }

    // Поддерживаем облако "стабильным" и движущимся (в основном для консистентности)
    private void advanceCloudForPreview(net.minecraft.entity.AreaEffectCloudEntity c) {
        try {
            int col = c.getColor(); if ((col & 0xFF000000) == 0) c.setColor(col | 0xFF000000);
            if (c.getDuration() < 1_000_000) c.setDuration(2_000_000);
            if (c.getWaitTime() != 0)        c.setWaitTime(0);
            if (c.getRadius() < 1.2f)        c.setRadius(1.6f);
            c.tick();
        } catch (Throwable ignored) {}
    }
    // 2) ДЕБАГ-МАРКЕРЫ (временно, чтобы увидеть, что ветка реально выполняется)
    private void debugMark(DrawContext ctx, int x, int y, int color, String label) {
        if (!SHOW_DEBUG_MARKS) return; // не рисуем маркеры, если флаг выключен
        ctx.fill(x - 2, y - 2, x + 2, y + 2, color);
        try { ctx.drawText(mc.textRenderer, label, x + 4, y - 6, 0xFFFFFFFF, false); } catch (Throwable ignored) {}
    }

    // Рисуем полупрозрачный круг поверх зоны иконки, чтобы «видеть» облако
    private void drawAreaEffectCloudOverlay(DrawContext ctx, int x, int y, int scale, AreaEffectCloudEntity cloud) {
        if (SHOW_DEBUG_MARKS) debugMark(ctx, x, y, 0xFFFF00FF, "AEC");

        float worldR = Math.max(0.2f, cloud.getRadius());
        float R = Math.max(2f, Math.min(scale * 0.9f, worldR * 10.0f));

        int rgb = cloud.getColor();
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8)  & 0xFF) / 255f;
        float b = (rgb        & 0xFF) / 255f;

        var matrices = ctx.getMatrices();
        matrices.push();
        matrices.translate(0, 0, 400); // ПОДНИМАЕМ оверлей над всеми 2D‑элементами

        org.joml.Matrix4f mat = matrices.peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        buf.begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_COLOR);
        buf.vertex(mat, x, y, 0).color(r, g, b, 0.55f).next();
        int segs = 40;
        for (int i = 0; i <= segs; i++) {
            double ang = (Math.PI * 2.0) * i / segs;
            float px = x + (float) Math.cos(ang) * R;
            float py = y + (float) Math.sin(ang) * R;
            buf.vertex(mat, px, py, 0).color(r, g, b, 0.35f).next();
        }
        tess.draw();

        buf.begin(VertexFormat.DrawMode.LINE_STRIP, VertexFormats.POSITION_COLOR);
        for (int i = 0; i <= segs; i++) {
            double ang = (Math.PI * 2.0) * i / segs;
            float px = x + (float) Math.cos(ang) * R;
            float py = y + (float) Math.sin(ang) * R;
            buf.vertex(mat, px, py, 0).color(r, g, b, 0.9f).next();
        }
        tess.draw();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        matrices.pop();
    }

    private void initPreviewEntityDefaults(Entity e, boolean iconMode) {
        if (e == null) return;

        e.setPos(0.0, 0.0, 0.0);
        e.setVelocity(0, 0, 0);
        try { e.setNoGravity(true); } catch (Throwable ignored) {}

        if (e instanceof AreaEffectCloudEntity cloud) {
            cloud.setRadius(iconMode ? 1.6f : 3.2f);
            cloud.setDuration(2_000_000);
            cloud.setWaitTime(0);
            cloud.setColor(0xFF55FFFF);
        } else if (e instanceof EvokerFangsEntity fangs) {
            trySetEvokerFangsWarmup(fangs, 0);
            for (int i = 0; i < 6; i++) {
                try { fangs.tick(); } catch (Throwable ignored) {}
            }
            try { e.age = 6; } catch (Throwable ignored) {}
            trySetIntField(fangs, 6, "lifeTicks", "life", "field_15224");
        } else if (e instanceof net.minecraft.entity.ItemEntity itemE) {
            if (itemE.getStack().isEmpty()) {
                itemE.setStack(new ItemStack(Items.DIAMOND));
            }
        } else if (e instanceof DisplayEntity.ItemDisplayEntity idisp) {
            tryInvoke1(idisp, "setItemStack", new ItemStack(Items.DIAMOND_SWORD));
            tryInvoke1(idisp, "setBillboardMode", DisplayEntity.BillboardMode.CENTER);
        } else if (e instanceof DisplayEntity.TextDisplayEntity tdisp) {
            tryInvoke1(tdisp, "setText", Text.literal("Text"));
            tryInvoke1(tdisp, "setBillboardMode", DisplayEntity.BillboardMode.CENTER);
            tryInvoke1(tdisp, "setBackground", 0x66000000);
            tryInvoke1(tdisp, "setLineWidth", 120);
        }
    }

    private Entity getOrCreateIconEntityAny(EntityType<?> type) {
        if (type == null || mc.world == null) return null;
        var id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(type);
        if (id == null) return null;
        String key = id.toString();

        // Для этих двух типов — не используем badIconIds и не берём кэш: пересоздаём каждый раз
        if (type == EntityType.AREA_EFFECT_CLOUD) {
            dbg("Icon create: AREA_EFFECT_CLOUD");
            Entity e = createAreaEffectCloudSafe();
            if (e != null) {
                initPreviewEntityDefaults(e, true);
                return e;
            } else {
                dbg("Icon create failed: AREA_EFFECT_CLOUD");
                return null;
            }
        }
        if (type == EntityType.EVOKER_FANGS) {
            dbg("Icon create: EVOKER_FANGS");
            Entity e = createEvokerFangsSafe();
            if (e != null) {
                initPreviewEntityDefaults(e, true);
                return e;
            } else {
                dbg("Icon create failed: EVOKER_FANGS");
                return null;
            }
        }

        // Остальные как обычно (можешь оставить свой кэш)
        Entity cached = morphIconCacheAny.get(key);
        if (cached != null) return cached;

        try {
            Entity e;
            if (type == EntityType.ITEM) {
                e = new ItemEntity(mc.world, 0, 0, 0, new ItemStack(Items.DIAMOND));
            } else {
                e = type.create(mc.world);
            }
            if (e == null) {
                dbg("Icon create returned null for " + key);
                return null;
            }
            initPreviewEntityDefaults(e, true);
            morphIconCacheAny.put(key, e);
            return e;
        } catch (Throwable t) {
            dbg("Icon create exception for " + key + ": " + t);
            return null;
        }
    }

    private Entity getOrCreatePreviewAny() {
        if (selectedEntityType == null || mc.world == null) { dbg("getOrCreatePreviewAny: world or type is null"); return null; }
        var id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(selectedEntityType);
        String key = id == null ? "unknown" : id.toString();
        dbg("getOrCreatePreviewAny: try type=" + key);

        if (previewEntityAny != null && previewEntityAny.getType() == selectedEntityType) {
            dbg("getOrCreatePreviewAny: reuse existing instance for " + key);
            return previewEntityAny;
        }

        if (selectedEntityType == EntityType.AREA_EFFECT_CLOUD) {
            dbg("Preview create: AREA_EFFECT_CLOUD");
            Entity e = createAreaEffectCloudSafe();
            if (e != null) {
                initPreviewEntityDefaults(e, false);
                previewEntityAny = e;
                dbg("Preview AEC created OK");
                return e;
            } else {
                dbg("Preview AEC create failed");
                previewEntityAny = null;
                return null;
            }
        }
        if (selectedEntityType == EntityType.EVOKER_FANGS) {
            dbg("Preview create: EVOKER_FANGS");
            Entity e = createEvokerFangsSafe();
            if (e != null) {
                initPreviewEntityDefaults(e, false);
                previewEntityAny = e;
                dbg("Preview FANGS created OK");
                return e;
            } else {
                dbg("Preview FANGS create failed");
                previewEntityAny = null;
                return null;
            }
        }

        if (previewEntityAny != null) {
            try { previewEntityAny.discard(); } catch (Throwable ignored) {}
            previewEntityAny = null;
        }

        try {
            Entity e;
            if (selectedEntityType == EntityType.ITEM) {
                e = new ItemEntity(mc.world, 0, 0, 0, new ItemStack(Items.DIAMOND));
            } else {
                e = selectedEntityType.create(mc.world);
            }
            if (e == null) {
                dbg("Preview create returned null for " + key);
                return null;
            }
            initPreviewEntityDefaults(e, false);
            previewEntityAny = e;
            dbg("Preview created generic OK for " + key);
            return e;
        } catch (Throwable t) {
            dbg("Preview create exception for " + key + ": " + t);
            previewEntityAny = null;
            return null;
        }
    }




    // Флаги/геометрия пресет-вкладки
    private boolean compactPresetsLayout = false; // устанавливается в buildPresetsTab()
    private int presetsPanelTopY = 0;             // верх панели в компакт-режиме

    // Dirty
    private boolean uiDirty = false;
    private long dirtyUntilMs = 0L;

    // Comments storage per preset (index-aligned with steps)
    private final Map<String, List<String>> presetComments = new HashMap<>();

    public SequencerMainScreen() { super(Text.literal("Sequencer")); }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        super.init();
        int btnW = 170;
        int btnH = 20;
// Позиция справа сверху. Если у тебя есть правая панель — подправь y, чтобы вписать в интерфейс
        int x = this.width - btnW - 10;
        int y = 20;

        this.aecPresetButton = CyclingButtonWidget.builder((CloudPreset p) -> Text.of(p.previewStyle.name))
                .values(CloudPreset.values())
                .initially(currentPreset)
                .build(x, y, btnW, btnH, Text.of("AEC Preset"), (button, value) -> {
                    currentPreset = value;
                    // Для не-CUSTOM пресетов чистим кастомные настройки
                    if (currentPreset != CloudPreset.CUSTOM) {
                        custom = null;
                    }
                });

        this.addDrawableChild(this.aecPresetButton);
        ensurePresetLoaded();

        unbanCloudAndFangsFromBadCaches();

        keyConfigPath = Paths.get(mc.runDirectory.getAbsolutePath(), "config", "sequencer_keys.json");
        keyConfig = KeyConfig.loadFrom(keyConfigPath);

        uiConfigPath = Paths.get(mc.runDirectory.getAbsolutePath(), "config", "sequencer_ui.json");
        uiConfig = UiConfig.loadFrom(uiConfigPath);

        if (DBG_FILTER == null) {
            try {
                String f = System.getProperty("sequencer.debug.filter");
                if (f != null && !f.isBlank()) DBG_FILTER = f;
            } catch (Throwable ignored) {}
        }
        if (allEntityIds == null) {
            allEntityIds = Registries.ENTITY_TYPE.stream()
                    .map(et -> Registries.ENTITY_TYPE.getId(et))
                    .filter(Objects::nonNull)
                    .map(Identifier::toString)
                    .sorted()
                    .toList();
        }
        // Восстановление кэша превью в оперативный формат
        if (uiConfig.previewStates != null) {
            previewCache.clear();
            for (var e : uiConfig.previewStates.entrySet()) {
                PreviewState ps = new PreviewState();
                ps.rotX = e.getValue().rotX;
                ps.rotY = e.getValue().rotY;
                ps.zoom = e.getValue().zoom;
                previewCache.put(e.getKey(), ps);
            }
        }

        if (entityPreviewItems.isEmpty()) {
            entityPreviewItems.put("minecraft:arrow", Items.ARROW);
            entityPreviewItems.put("minecraft:minecart", Items.MINECART);
            entityPreviewItems.put("minecraft:chest_minecart", Items.CHEST_MINECART);
            entityPreviewItems.put("minecraft:boat", Items.OAK_BOAT);
            entityPreviewItems.put("minecraft:chest_boat", Items.OAK_CHEST_BOAT);
            entityPreviewItems.put("minecraft:area_effect_cloud", Items.LINGERING_POTION);
            // при желании добавляйте сюда свои соответствия
        }

        currentTab = parseTabOrDefault(uiConfig.lastTab, Tab.PRESETS);

        switch (currentTab) {
            case MORPHS -> buildMorphsTab();
            case PRESETS -> buildPresetsTab();
            case SETTINGS -> buildSettingsTab();
        }

        addTabButtonsOnTop();
        if (contextMenu == null) contextMenu = new ContextMenu();
    }

    @Override
    public void resize(net.minecraft.client.MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        if (this.aecPresetButton != null) {
            int btnW = this.aecPresetButton.getWidth();
            int x = this.width - btnW - 10;
            int y = 20;
            this.aecPresetButton.setPosition(x, y);
            // при необходимости можешь подправить ширину:
            // this.aecPresetButton.setWidth(btnW);
        }
    }

    @Override
    protected void clearAndInit() { super.clearAndInit(); }

    private void addTabButtonsOnTop() {
        int tabX = PAD;

        tabMorphsBtn = ButtonWidget.builder(Text.literal((currentTab == Tab.MORPHS ? "*" : "") + "Морфы"), b -> {
            currentTab = Tab.MORPHS; uiConfig.lastTab = "MORPHS"; uiConfig.saveTo(uiConfigPath); clearAndInit();
        }).position(tabX, PAD).size(90, TAB_H).build();
        addDrawableChild(tabMorphsBtn);

        tabX += 95;
        tabPresetsBtn = ButtonWidget.builder(Text.literal((currentTab == Tab.PRESETS && isDirty()) ? "*Пресеты" : "Пресеты"), b -> {
            currentTab = Tab.PRESETS; uiConfig.lastTab = "PRESETS"; uiConfig.saveTo(uiConfigPath); clearAndInit();
        }).position(tabX, PAD).size(95, TAB_H).build();
        addDrawableChild(tabPresetsBtn);

        tabX += 100;
        tabSettingsBtn = ButtonWidget.builder(Text.literal("Настройки"), b -> {
            currentTab = Tab.SETTINGS; uiConfig.lastTab = "SETTINGS"; uiConfig.saveTo(uiConfigPath); clearAndInit();
        }).position(tabX, PAD).size(110, TAB_H).build();
        addDrawableChild(tabSettingsBtn);
    }

    private boolean isDirty() { return uiDirty || System.currentTimeMillis() < dirtyUntilMs; }
    private void markDirty() { uiDirty = true; dirtyUntilMs = System.currentTimeMillis() + 1500; }
    private void markSaved() { uiDirty = false; dirtyUntilMs = System.currentTimeMillis() + 1000; }

    @Override
    public void tick() {
        super.tick();

        if (searchBox != null) searchBox.tick();
        if (entityIdBox != null) entityIdBox.tick();
        if (durationBox != null) durationBox.tick();
        if (presetNameBox != null) presetNameBox.tick();
        if (commentBox != null) commentBox.tick();
        if (startDelayBox != null) startDelayBox.tick();
        if (loopCountBox != null) loopCountBox.tick();
        if (bulkTicksBox != null) bulkTicksBox.tick();
        if (bulkPercentBox != null) bulkPercentBox.tick();
        if (multipleBox != null) multipleBox.tick();
        if (replaceIdBox != null) replaceIdBox.tick();
        if (inlineEditVisible && inlineEditField != null) inlineEditField.tick();

        if (uiStepHBox != null) uiStepHBox.tick();
        if (uiLeftPctBox != null) uiLeftPctBox.tick();
        if (uiRightPctBox != null) uiRightPctBox.tick();
        if (uiMenuMaxItemsBox != null) uiMenuMaxItemsBox.tick();
        if (uiPreviewScaleBox != null) uiPreviewScaleBox.tick();
        if (morphList != null) morphList.tickRebuild();

        // ВАЖНО: продвигаем состояния сущностей в превью
        if (currentTab == Tab.MORPHS && previewEntityAny != null) {
            try {
                previewEntityAny.setVelocity(0, 0, 0);
                if (previewEntityAny instanceof net.minecraft.entity.mob.EvokerFangsEntity f) {
                    advanceEvokerFangsForPreview(f);
                } else if (previewEntityAny instanceof net.minecraft.entity.AreaEffectCloudEntity c) {
                    advanceCloudForPreview(c);
                } else {
                    previewEntityAny.age++;
                    previewEntityAny.tick();
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        // Ввод символов в поле фильтра шагов
        if (currentTab == Tab.PRESETS && stepFilterBox != null && stepFilterBox.isFocused()) {
            if (stepFilterBox.charTyped(chr, modifiers)) return true;
        }
        if (confirmDeleteVisible) return super.charTyped(chr, modifiers);
        if (hotkeysOverlayVisible) return true;
        if (contextMenu != null && contextMenu.visible) return true;

        if (inlineEditVisible && inlineEditField != null && inlineEditField.isFocused()) {
            if (inlineEditField.charTyped(chr, modifiers)) return true;
        }
        if (currentTab == Tab.SETTINGS) {
            if (uiStepHBox != null && uiStepHBox.isFocused() && uiStepHBox.charTyped(chr, modifiers)) return true;
            if (uiLeftPctBox != null && uiLeftPctBox.isFocused() && uiLeftPctBox.charTyped(chr, modifiers)) return true;
            if (uiRightPctBox != null && uiRightPctBox.isFocused() && uiRightPctBox.charTyped(chr, modifiers)) return true;
            if (uiMenuMaxItemsBox != null && uiMenuMaxItemsBox.isFocused() && uiMenuMaxItemsBox.charTyped(chr, modifiers)) return true;
            if (uiPreviewScaleBox != null && uiPreviewScaleBox.isFocused() && uiPreviewScaleBox.charTyped(chr, modifiers)) return true;
        }
        if (currentTab == Tab.MORPHS && searchBox != null && searchBox.isFocused()) {
            if (searchBox.charTyped(chr, modifiers)) return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (confirmDeleteVisible) return super.keyPressed(keyCode, scanCode, modifiers);

        if (hotkeysOverlayVisible) {
            if (keyCode == 256) { hotkeysOverlayVisible = false; return true; }
            return true;
        }

        if (contextMenu != null && contextMenu.visible) {
            // Esc — закрыть
            if (keyCode == 256) { contextMenu.hide(); return true; }
            // Вверх/вниз — навигация
            if (keyCode == 265 || keyCode == 264) { // Up/Down
                contextMenu.moveActive(keyCode == 265 ? -1 : 1);
                return true;
            }
            // Enter — выполнить активный
            if (keyCode == 257 || keyCode == 335) {
                contextMenu.activateActive();
                return true;
            }
            return true;
        }

        if (currentTab == Tab.MORPHS && searchBox != null && searchBox.isFocused()) {
            if (searchBox.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (isEnterKey(keyCode)) { searchBox.setFocused(false); return true; }
        }

        // Если фокус в поле фильтра шагов — отдаём туда клавиатурные события
        if (currentTab == Tab.PRESETS && stepFilterBox != null && stepFilterBox.isFocused()) {
            // запрещаем глобальное удаление, пока фокус в поле
            if (keyCode == 261 || keyCode == 259) {
                // Backspace/Delete обрабатываются самим текстовым полем
                if (stepFilterBox.keyPressed(keyCode, scanCode, modifiers)) return true;
                return true;
            }
            if (stepFilterBox.keyPressed(keyCode, scanCode, modifiers)) return true;
            // Enter — снять фокус с поля
            if (isEnterKey(keyCode)) { stepFilterBox.setFocused(false); return true; }
        }

        if (currentTab == Tab.MORPHS && isEnterKey(keyCode)) {
            if (morphPickMode) { applyPickedMorphAndReturn(); }
            else { doMorphSelected(); }
            return true;
        }
        if (currentTab == Tab.MORPHS && keyCode == 256 && morphPickMode) { // Esc
            cancelMorphPickReturn();
            return true;
        }

        if (waitingKey != WaitingKey.NONE) {
            if (keyCode == 256) {
                switch (waitingKey) {
                    case PLAY_PAUSE -> keyConfig.keyPlayPause = 0;
                    case STOP -> keyConfig.keyStop = 0;
                    case RESTART -> keyConfig.keyRestart = 0;
                    case ADD_STEP -> keyConfig.keyAddStep = 0;
                }
                keyConfig.saveTo(keyConfigPath);
                setStatus("Привязка очищена");
            } else {
                switch (waitingKey) {
                    case PLAY_PAUSE -> keyConfig.keyPlayPause = keyCode;
                    case STOP -> keyConfig.keyStop = keyCode;
                    case RESTART -> keyConfig.keyRestart = keyCode;
                    case ADD_STEP -> keyConfig.keyAddStep = keyCode;
                }
                keyConfig.saveTo(keyConfigPath);
                setStatus("Назначено: " + waitingKeyLabel(waitingKey) + " → " + keyName(keyCode));
            }
            waitingKey = WaitingKey.NONE;
            return true;
        }

        // Навигация по попапу ID (подготовка к п.3)
        if (entityIdBox != null && entityIdBox.isFocused()) {
            if (idAutocompleteVisible) {
                if (keyCode == 265) { // Up
                    idSuggestIndex = Math.max(0, idSuggestIndex - 1);
                    if (idSuggestIndex < idSuggestScroll) idSuggestScroll = idSuggestIndex;
                    return true;
                }
                if (keyCode == 264) { // Down
                    idSuggestIndex = Math.min(Math.max(0, idSuggestions.size() - 1), idSuggestIndex + 1);
                    int maxRows = Math.min(ID_SUGGEST_MAX_VISIBLE, Math.max(2, this.height / 20));
                    if (idSuggestIndex >= idSuggestScroll + maxRows) idSuggestScroll = idSuggestIndex - maxRows + 1;
                    return true;
                }
                if (keyCode == 257 || keyCode == 335) { // Enter
                    if (!idSuggestions.isEmpty()) {
                        String pick = idSuggestions.get(Math.max(0, Math.min(idSuggestIndex, idSuggestions.size() - 1)));
                        entityIdBox.setText(pick);

                        // Ctrl+Enter — применить к выбранному/помеченным шагам
                        if (hasControlDown()) {
                            SequencePreset p = selectedPreset();
                            if (p != null) {
                                pushUndoSnapshot(); redoStack.clear();
                                java.util.List<Integer> idxs = getMarkedOrSelectedIndices();
                                if (idxs.isEmpty() && stepList != null && stepList.getSelectedOrNull() != null)
                                    idxs = java.util.List.of(stepList.getSelectedOrNull().index);
                                for (int i : idxs) if (i >= 0 && i < p.steps.size()) p.steps.get(i).entityId = pick;
                                PresetStorage.save(presets);
                                markSaved();
                                reloadStepsFromSelected();
                                setStatus("ID применён: " + pick + (idxs.size() > 1 ? " (" + idxs.size() + " шагов)" : ""));
                            }
                        } else {
                            setStatus("ID выбран: " + pick + " (Ctrl+Enter — применить к шагам)");
                        }
                    }
                    idAutocompleteVisible = false;
                    return true;
                }
                if (keyCode == 256) { // Esc
                    idAutocompleteVisible = false;
                    return true;
                }
            } else {
                // Включать попап на Down/Up, если поле непустое
                if ((keyCode == 264 || keyCode == 265) && entityIdBox.getText() != null && !entityIdBox.getText().isBlank()) {
                    idAutocompleteVisible = true;
                    idSuggestions = allEntityIds.stream()
                            .filter(s -> s.contains(entityIdBox.getText().trim().toLowerCase(Locale.ROOT)))
                            .limit(200).toList();
                    idSuggestIndex = 0; idSuggestScroll = 0;
                    return true;
                }
            }
        }

        if (currentTab == Tab.PRESETS && !anyTextFieldFocused() && matchesKey(keyConfig.keyPlayPause, keyCode)) { playPauseToggle(); return true; }
        if (currentTab == Tab.PRESETS && !anyTextFieldFocused() && matchesKey(keyConfig.keyStop, keyCode)) { safeStop(); return true; }
        if (currentTab == Tab.PRESETS && !anyTextFieldFocused() && matchesKey(keyConfig.keyRestart, keyCode)) { restartPlay(); return true; }

        if (inlineEditVisible && inlineEditField != null) {
            if (keyCode == 257 || keyCode == 335) {
                boolean applyToSelection = hasControlDown();
                commitInlineEdit(applyToSelection);
                return true;
            }
            if (keyCode == 256) { cancelInlineEdit(); return true; }
            if (keyCode == 265 || keyCode == 264) {
                int base = (keyCode == 265) ? 1 : -1;
                int step = base * (Screen.hasShiftDown() ? 10 : (hasControlDown() ? 5 : 1));
                int curr = parseIntSafe(inlineEditField.getText(), 1);
                int next = Math.max(1, curr + step);
                inlineEditField.setText(String.valueOf(next));
                return true;
            }
            return inlineEditField.keyPressed(keyCode, scanCode, modifiers);
        }

        if (anyTextFieldFocused()) {
            if (isEnterKey(keyCode)) { blurAllTextFields(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (currentTab == Tab.PRESETS && matchesKey(keyConfig.keyAddStep, keyCode)) {
            String id = entityIdBox != null ? entityIdBox.getText().trim() : "";
            int ticks = durationBox != null ? parseIntSafe(durationBox.getText(), 60) : 60;
            if (!id.isEmpty()) { addStepToCurrent(id, ticks); return true; }
        }

        if (currentTab == Tab.PRESETS && hasControlDown() && isEnterKey(keyCode)) {
            duplicateSelectedSteps();
            return true;
        }

        if (Screen.hasAltDown()) {
            if (keyCode == 265) { moveBlock(-1); return true; }
            if (keyCode == 264) { moveBlock(1); return true; }
        }

        if (currentTab == Tab.PRESETS) {
            if (keyCode == 265 || keyCode == 264) {
                boolean extend = Screen.hasShiftDown();
                pushUndoSnapshot(); redoStack.clear();
                moveSelectionBy(keyCode == 265 ? -1 : 1, extend);
                return true;
            }
            if (keyCode == 268 || keyCode == 269) {
                boolean extend = Screen.hasShiftDown();
                pushUndoSnapshot(); redoStack.clear();
                moveSelectionToEdge(keyCode == 269, extend);
                return true;
            }
            if (keyCode == 266 || keyCode == 267) { pageScroll(keyCode == 267 ? 1 : -1); return true; }
            if (keyCode == 32 || (hasControlDown() && keyCode == 32)) {
                if (stepList != null && stepList.getSelectedOrNull() != null) {
                    pushUndoSnapshot(); redoStack.clear();
                    stepList.toggleMarked(stepList.getSelectedOrNull().index);
                    rememberSelection();
                    return true;
                }
            }
        }

        if (currentTab == Tab.PRESETS) {
            if (hasControlDown() && !Screen.hasShiftDown()) {
                if (keyCode == 65) { pushUndoSnapshot(); redoStack.clear(); markAllSteps(); return true; }
                if (keyCode == 68) { pushUndoSnapshot(); redoStack.clear(); clearStepMarks(); return true; }
                if (keyCode == 67) { copySelectedSteps(); return true; }
                if (keyCode == 86) { pasteSteps(); return true; }
                if (keyCode == 73) { pushUndoSnapshot(); redoStack.clear(); invertSelection(); return true; }
                if (keyCode == 90) { undo(); return true; }
                if (keyCode == 89) { redo(); return true; }
            }
            if (hasControlDown() && Screen.hasShiftDown()) {
                if (keyCode == 68) { duplicateSelectedSteps(); return true; }
                if (keyCode == 90) { redo(); return true; }
            }
            if ((keyCode == 261 || keyCode == 259) && !anyTextFieldFocused()) {
                handleDeleteWithConfirm();
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // Совместимость прокрутки: 4-арг. делегирует в 3-арг.
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return mouseScrolled(mouseX, mouseY, verticalAmount);
    }

    // Основная реализация (3 аргумента)
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (hotkeysOverlayVisible) {
            hotkeysScroll += (int) (-amount * 3);
            if (hotkeysScroll < 0) hotkeysScroll = 0;
            return true;
        }
        if (contextMenu != null && contextMenu.visible) {
            if (contextMenu.contains(mouseX, mouseY)) contextMenu.scrollBy(-amount * 3);
            return true;
        }
        if (currentTab == Tab.MORPHS && pointInPreview(mouseX, mouseY)) {
            float factor = (float) Math.pow(1.1, amount);
            morphZoomUser = clampf(morphZoomUser * factor, 0.5f, 3.0f);
            // Сохраняем зум в кэше (подготовка к п.9)
            if (selectedEntityType != null) {
                Identifier id = Registries.ENTITY_TYPE.getId(selectedEntityType);
                if (id != null) {
                    PreviewState ps = previewCache.computeIfAbsent(id.toString(), k -> new PreviewState());
                    ps.rotX = morphRotX; ps.rotY = morphRotY; ps.zoom = morphZoomUser;

                    UiConfig.UIPreviewState dto = new UiConfig.UIPreviewState();
                    dto.rotX = ps.rotX; dto.rotY = ps.rotY; dto.zoom = ps.zoom;
                    uiConfig.previewStates.put(id.toString(), dto);
                    uiConfig.saveTo(uiConfigPath);
                }
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (hotkeysOverlayVisible) return true;
        if (contextMenu != null && contextMenu.visible) {
            contextMenu.mouseDragged(mouseX, mouseY, button, dx, dy);
            return true;
        }
        if (currentTab == Tab.MORPHS && morphDragging && button == 1) {
            morphRotY += dx;
            morphRotX = clampf(morphRotX + (float) dy, -60f, 60f);
            morphLastMouseX = mouseX; morphLastMouseY = mouseY;
            return true;
        }

        // Drag резайзеров (подготовка к п.1)
        if (currentTab == Tab.PRESETS && button == 0) {
            if (draggingLeftResizer) {
                ghostLeftResizerX = (int) mouseX;
                return true;
            }
            if (draggingRightResizer) {
                ghostRightResizerX = (int) mouseX;
                return true;
            }
        }

        // Скраббинг по таймлайну (подготовка к п.4)
        // Скраббинг / Edge‑adjust
        if (timelineScrubbing && !timelineEdgeAdjust) {
            timelineScrubMouseX = (int) mouseX;
            return true;
        }
        if (timelineEdgeActive) {
            applyEdgeAdjust((int) mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @SuppressWarnings("unused")
    public void filesDragged(java.util.List<java.nio.file.Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        boolean any = false;
        for (Path p : paths) {
            String name = p.getFileName() != null ? p.getFileName().toString().toLowerCase(Locale.ROOT) : "";
            if (name.endsWith(".json")) {
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    importJsonTextIntoCurrentPreset(text);
                    Path parent = p.getParent();
                    if (parent != null) { uiConfig.lastFileDir = parent.toString(); uiConfig.saveTo(uiConfigPath); }
                    any = true;
                } catch (Exception e) {
                    setStatus("Ошибка импорта из " + p.getFileName() + ": " + e.getMessage());
                }
            }
        }
        if (any) setStatus("Импорт из перетаскивания файлов выполнен");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hotkeysOverlayVisible) {
            if (button == 0) { hotkeysOverlayVisible = false; return true; }
            return true;
        }

        if (contextMenu != null && contextMenu.visible) {
            if (contextMenu.contains(mouseX, mouseY)) {
                contextMenu.mouseClick(mouseX, mouseY);
            } else {
                contextMenu.hide();
            }
            return true;
        }

        if (confirmDeleteVisible) {
            if (mouseIn(mouseX, mouseY, confirmYesX, confirmYesY, confirmBtnW, confirmBtnH)) {
                confirmDeleteVisible = false;
                if (onConfirmDelete != null) onConfirmDelete.run();
                onConfirmDelete = null;
                return true;
            }
            if (mouseIn(mouseX, mouseY, confirmNoX, confirmNoY, confirmBtnW, confirmBtnH)) {
                confirmDeleteVisible = false;
                onConfirmDelete = null;
                return true;
            }
            return true;
        }

        if (currentTab == Tab.MORPHS && searchBox != null) {
            if (mouseIn(mouseX, mouseY, searchBox.getX(), searchBox.getY(), searchBox.getWidth(), searchBox.getHeight())) {
                searchBox.setFocused(true);
                return searchBox.mouseClicked(mouseX, mouseY, button);
            }
        }

        if (currentTab == Tab.PRESETS && stepFilterBox != null) {
            if (mouseIn(mouseX, mouseY, stepFilterBox.getX(), stepFilterBox.getY(), stepFilterBox.getWidth(), stepFilterBox.getHeight())) {
                stepFilterBox.setFocused(true);
                return stepFilterBox.mouseClicked(mouseX, mouseY, button);
            }
        }

        // Резайзеры панелей (подготовка к п.1)
        if (currentTab == Tab.PRESETS && button == 0) {
            // Попадание в левый разделитель
            if (leftResizerX > 0 && Math.abs(mouseX - leftResizerX) <= RESIZER_W) {
                draggingLeftResizer = true;
                resizerDragStartX = (int) mouseX;
                ghostLeftResizerX = leftResizerX;
                return true;
            }
            // Попадание в правый разделитель
            if (rightResizerX > 0 && Math.abs(mouseX - rightResizerX) <= RESIZER_W) {
                draggingRightResizer = true;
                resizerDragStartX = (int) mouseX;
                ghostRightResizerX = rightResizerX;
                return true;
            }
        }

        // Скраббинг по таймлайну (подготовка к п.4)
        // Скраббинг / Edge‑adjust по таймлайну

        if (inlineEditVisible) {
            boolean inside = inlineEditField != null && inlineEditField.isMouseOver(mouseX, mouseY);
            if (!inside && button == 0) {
                commitInlineEdit(false);
                return true;
            }
        }

        if (currentTab == Tab.MORPHS && button == 1 && pointInPreview(mouseX, mouseY)) {
            morphDragging = true;
            morphLastMouseX = mouseX;
            morphLastMouseY = mouseY;
            return true;
        }

        if (currentTab == Tab.MORPHS && button == 0 && pointInPreview(mouseX, mouseY)) {
            long now = System.currentTimeMillis();
            if (now - previewLastClickMs < 300) {
                morphZoomUser = 1.0f; morphRotX = 0f; morphRotY = 0f;
                setStatus("Превью сброшено");
                previewLastClickMs = 0L;
                return true;
            }
            previewLastClickMs = now;
        }

        // Клик по попапу автодополнения ID
        if (idAutocompleteVisible && entityIdBox != null && entityIdBox.isFocused()) {
            if (mouseIn(mouseX, mouseY, idPopupX, idPopupY, idPopupW, idPopupH)) {
                int relY = (int)(mouseY - idPopupY) - 3; // отступ 3px сверху
                int idxInView = Math.max(0, relY / idPopupItemH);
                int maxRows = Math.min(ID_SUGGEST_MAX_VISIBLE, Math.max(2, this.height / 20));
                int start = Math.max(0, Math.min(idSuggestScroll, Math.max(0, idSuggestions.size() - maxRows)));
                int idx = start + idxInView;
                if (idx >= 0 && idx < idSuggestions.size()) {
                    String pick = idSuggestions.get(idx);
                    entityIdBox.setText(pick);
                    // ЛКМ по пункту — просто выбираем; Ctrl+ЛКМ — применяем сразу
                    if (hasControlDown()) {
                        SequencePreset p = selectedPreset();
                        if (p != null) {
                            pushUndoSnapshot(); redoStack.clear();
                            java.util.List<Integer> idxs = getMarkedOrSelectedIndices();
                            if (idxs.isEmpty() && stepList != null && stepList.getSelectedOrNull() != null)
                                idxs = java.util.List.of(stepList.getSelectedOrNull().index);
                            for (int i : idxs) if (i >= 0 && i < p.steps.size()) p.steps.get(i).entityId = pick;
                            PresetStorage.save(presets);
                            markSaved();
                            reloadStepsFromSelected();
                            setStatus("ID применён: " + pick + (idxs.size() > 1 ? " (" + idxs.size() + " шагов)" : ""));
                        }
                    } else {
                        setStatus("ID выбран: " + pick + " (Ctrl+клик — применить к шагам)");
                    }
                    idAutocompleteVisible = false;
                }
                return true;
            }
        }

        // Скраббинг / Edge‑adjust по таймлайну
        if (currentTab == Tab.PRESETS && button == 0
                && mouseIn(mouseX, mouseY, timelineLeft, timelineTop, timelineRight - timelineLeft, timelineBottom - timelineTop)) {

            boolean shift = Screen.hasShiftDown();
            if (shift) {
                if (beginEdgeAdjust(mouseX)) {
                    return true;
                }
                // если границу рядом не нашли — пойдём как обычный скраббинг
            }

            // Обычный скраббинг позиционирования
            timelineScrubbing = true;
            timelineEdgeAdjust = false;
            timelineScrubMouseX = (int) mouseX;
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (hotkeysOverlayVisible) return true;

        if (contextMenu != null && contextMenu.visible) {
            contextMenu.release();
            return true;
        }

        if (currentTab == Tab.MORPHS && button == 1 && morphDragging) {
            morphDragging = false;

            // Сохраняем поз/зум превью (подготовка к п.9)
            if (selectedEntityType != null) {
                Identifier id = Registries.ENTITY_TYPE.getId(selectedEntityType);
                if (id != null) {
                    PreviewState ps = new PreviewState();
                    ps.rotX = morphRotX; ps.rotY = morphRotY; ps.zoom = morphZoomUser;
                    previewCache.put(id.toString(), ps);

                    UiConfig.UIPreviewState dto = new UiConfig.UIPreviewState();
                    dto.rotX = ps.rotX; dto.rotY = ps.rotY; dto.zoom = ps.zoom;
                    uiConfig.previewStates.put(id.toString(), dto);
                    uiConfig.saveTo(uiConfigPath);
                }
            }
            return true;
        }

        // Завершение drag резайзеров (подготовка к п.1)
        if (currentTab == Tab.PRESETS && button == 0) {
            if (draggingLeftResizer && ghostLeftResizerX >= 0) {
                applyLeftResizer(ghostLeftResizerX);
            }
            if (draggingRightResizer && ghostRightResizerX >= 0) {
                applyRightResizer(ghostRightResizerX);
            }
            draggingLeftResizer = false;
            draggingRightResizer = false;
            ghostLeftResizerX = -1;
            ghostRightResizerX = -1;
        }

        // Завершение скраббинга по таймлайну (подготовка к п.4)
        // Завершение скраббинга / edge‑adjust по таймлайну
        if (timelineEdgeActive) {
            endEdgeAdjust();
            return true;
        }
        if (timelineScrubbing) {
            jumpToStepByTimeline(timelineScrubMouseX);
            timelineScrubbing = false;
            return true;
        }


        return super.mouseReleased(mouseX, mouseY, button);
    }

    // Применить новую позицию левого резайзера: пересчитать проценты и пересоздать UI
    private void applyLeftResizer(int x) {
        int totalW = this.width - 2 * PAD;

        // новый левый размер (левая колонка)
        int newLeftW = Math.max(140, Math.min(220, x - PAD)); // те же min/max, что в buildPresetsTab
        double leftPct = newLeftW / (double) totalW;

        // сохраняем, не даём сумме превысить 0.8
        double right = uiConfig.rightPanelPercent;
        if (leftPct + right > 0.8) {
            double ratio = 0.8 / (leftPct + right);
            leftPct *= ratio;
            right *= ratio;
        }

        uiConfig.leftPanelPercent = Math.max(0.10, Math.min(0.40, leftPct));
        uiConfig.rightPanelPercent = Math.max(0.15, Math.min(0.40, right));
        uiConfig.saveTo(uiConfigPath);
        clearAndInit();
    }

    private void applyRightResizer(int x) {
        int totalW = this.width - 2 * PAD;

        // Приблизим расчёт, как в buildPresetsTab
        int leftW = (int) (totalW * uiConfig.leftPanelPercent);
        int midW = totalW - leftW - (int) (totalW * uiConfig.rightPanelPercent) - 2 * PAD;
        if (midW < 220) return; // в компакт-режиме правой панели нет

        // “Используемая” ширина правой панели как расстояние от правого края
        int usedRightW = Math.max(200, Math.min(340, (PAD + totalW) - x));
        double rightPct = usedRightW / (double) totalW;

        double left = uiConfig.leftPanelPercent;
        if (left + rightPct > 0.8) {
            double ratio = 0.8 / (left + rightPct);
            left *= ratio;
            rightPct *= ratio;
        }

        uiConfig.leftPanelPercent = Math.max(0.10, Math.min(0.40, left));
        uiConfig.rightPanelPercent = Math.max(0.15, Math.min(0.40, rightPct));
        uiConfig.saveTo(uiConfigPath);
        clearAndInit();
    }

    // Запуск выбора морфа: запоминаем, куда вернуться, и переходим на вкладку Морфы
    private void beginMorphPick(boolean applyToSelection) {
        if (currentTab != Tab.PRESETS) return; // только из Пресетов

        morphPickMode = true;
        morphPickApplyToSelection = applyToSelection;
        morphPickReturnPreset = (selectedPresetName != null) ? selectedPresetName : "";
        morphPickReturnModelIndex = -1;

        if (stepList != null && stepList.getSelectedOrNull() != null) {
            morphPickReturnModelIndex = modelIndexOf(stepList.getSelectedOrNull()); // модельный индекс шага
        } else if (uiConfig != null) {
            morphPickReturnModelIndex = uiConfig.lastStepIndex;
        }

        currentTab = Tab.MORPHS;
        uiConfig.lastTab = "MORPHS";
        uiConfig.saveTo(uiConfigPath);
        clearAndInit();
        setStatus("Выберите морф (двойной клик или Enter). Esc — отмена и возврат.");
    }

    // Применение выбранного морфа и возврат во вкладку Пресеты
    private void applyPickedMorphAndReturn() {
        Identifier id = (selectedEntityType != null) ? Registries.ENTITY_TYPE.getId(selectedEntityType) : null;
        String pick = (id != null) ? id.toString() : null;

        // восстановим пресет, куда возвращаемся
        String targetPreset = (morphPickReturnPreset != null) ? morphPickReturnPreset : "";
        if (!targetPreset.isEmpty() && presets != null && presets.containsKey(targetPreset)) {
            selectedPresetName = targetPreset;
        }

        if (pick != null) {
            ensurePresetLoaded();
            SequencePreset p = selectedPreset();
            if (p != null) {
                pushUndoSnapshot(); redoStack.clear();

                // применяем к одному шагу — к тому, с которого начали выбор
                java.util.List<Integer> idxs;
                if (morphPickApplyToSelection) {
                    idxs = getMarkedOrSelectedIndices();
                    if (idxs.isEmpty() && morphPickReturnModelIndex >= 0) idxs = java.util.List.of(morphPickReturnModelIndex);
                } else {
                    int model = (morphPickReturnModelIndex >= 0) ? morphPickReturnModelIndex : uiConfig.lastStepIndex;
                    idxs = (model >= 0) ? java.util.List.of(model) : java.util.List.of();
                }

                for (int i : idxs) if (i >= 0 && i < p.steps.size()) p.steps.get(i).entityId = pick;

                PresetStorage.save(presets);
                markSaved();
            }
        }

        // Возврат в Пресеты
        currentTab = Tab.PRESETS;
        uiConfig.lastTab = "PRESETS";
        if (morphPickReturnModelIndex >= 0) uiConfig.lastStepIndex = morphPickReturnModelIndex;
        uiConfig.saveTo(uiConfigPath);
        clearAndInit();

        if (pick != null) {
            if (entityIdBox != null) entityIdBox.setText(pick);
            if (morphPickReturnModelIndex >= 0) selectViewByModelIndex(morphPickReturnModelIndex);
            setStatus("Морф установлен: " + pick);
        } else {
            setStatus("Морф не выбран");
        }

        // сброс флагов
        morphPickMode = false;
        morphPickReturnPreset = "";
        morphPickReturnModelIndex = -1;
        morphPickApplyToSelection = false;
    }

    // Отмена выбора морфа
    private void cancelMorphPickReturn() {
        String targetPreset = (morphPickReturnPreset != null) ? morphPickReturnPreset : "";

        morphPickMode = false;
        morphPickApplyToSelection = false;

        currentTab = Tab.PRESETS;
        if (!targetPreset.isEmpty()) selectedPresetName = targetPreset;
        uiConfig.lastTab = "PRESETS";
        uiConfig.saveTo(uiConfigPath);
        clearAndInit();
        setStatus("Выбор морфа отменён");
        morphPickReturnPreset = "";
        morphPickReturnModelIndex = -1;
    }



    // Начать перетаскивание границы шага (Shift+LMB по таймлайну).
// Возвращает true, если граница найдена и мы вошли в режим edge‑adjust.
    private boolean beginEdgeAdjust(double mouseX) {
        SequencePreset p = selectedPreset();
        if (p == null || p.steps.size() < 2) return false;
        if (tlWidth <= 0) return false;

        int total = ticksOfAll();
        if (total <= 0) return false;

        // Построим массив позиций границ в пикселях
        int[] boundariesPx = new int[p.steps.size() + 1]; // 0..N (включая конец)
        boundariesPx[0] = tlX0;
        int acc = 0;
        for (int i = 0; i < p.steps.size(); i++) {
            int dur = Math.max(1, p.steps.get(i).durationTicks);
            acc += dur;
            int x = tlX0 + (int)Math.round((acc / (double) total) * tlWidth);
            boundariesPx[i + 1] = x;
        }

        int mx = (int) mouseX;
        int bestIdx = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 1; i < boundariesPx.length - 0; i++) { // границы между шагами [i-1 | i], включая последний край
            int x = boundariesPx[i];
            int d = Math.abs(mx - x);
            if (d < bestDist) { bestDist = d; bestIdx = i; }
        }

        // Порог "близости" к границе — 8 пикселей
        if (bestIdx <= 0 || bestIdx >= boundariesPx.length || bestDist > 8) return false;

        // Инициализация
        timelineEdgeActive = true;
        timelineEdgeAdjust = true;
        timelineScrubbing = false;

        edgeBoundaryIndex = bestIdx; // граница между шагами (bestIdx-1) и bestIdx
        edgeStartMouseX = (int) mouseX;
        edgeUndoPushed = false;
        edgeLastAppliedDelta = 0;
        return true;
    }

    // Применить перемещение границы: пересчитать длительности соседних шагов
    private void applyEdgeAdjust(int mouseX) {
        SequencePreset p = selectedPreset();
        if (p == null) return;
        if (!timelineEdgeActive || edgeBoundaryIndex <= 0 || edgeBoundaryIndex >= p.steps.size()) return;
        if (tlWidth <= 0) return;

        int total = ticksOfAll();
        if (total <= 0) return;

        int dxPx = mouseX - edgeStartMouseX;
        int deltaTicks = (int)Math.round(dxPx * (total / (double) tlWidth));

        // Избежать лишних сохранений: если дельта не изменилась — выходим
        if (deltaTicks == edgeLastAppliedDelta) return;

        // Применяем дельту как приращение к левому и вычитание из правого шага
        int leftIdx = edgeBoundaryIndex - 1;
        int rightIdx = edgeBoundaryIndex;

        int leftOld = Math.max(1, p.steps.get(leftIdx).durationTicks);
        int rightOld = Math.max(1, p.steps.get(rightIdx).durationTicks);

        int leftNew = Math.max(1, leftOld + (deltaTicks - edgeLastAppliedDelta));
        int rightNew = Math.max(1, rightOld - (deltaTicks - edgeLastAppliedDelta));

        // Защита: если упёрлись в 1 тик — не даём уйти в 0 и не ломаем баланс
        int adjust = 0;
        if (leftNew <= 0) { adjust = 1 - leftNew; leftNew = 1; rightNew -= adjust; }
        if (rightNew <= 0) { adjust = 1 - rightNew; rightNew = 1; leftNew -= adjust; }
        if (leftNew <= 0 || rightNew <= 0) return;

        // Один снимок Undo — при первом движении
        if (!edgeUndoPushed) { pushUndoSnapshot(); redoStack.clear(); edgeUndoPushed = true; }

        p.steps.get(leftIdx).durationTicks = leftNew;
        p.steps.get(rightIdx).durationTicks = rightNew;

        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();

        edgeLastAppliedDelta = deltaTicks;
    }

    // Завершить edge‑adjust, сбросить флаги
    private void endEdgeAdjust() {
        timelineEdgeActive = false;
        timelineEdgeAdjust = false;
        timelineScrubbing = false;
        edgeBoundaryIndex = -1;
        edgeUndoPushed = false;
        edgeLastAppliedDelta = 0;
    }

    private boolean mouseIn(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private boolean pointInPreview(double mx, double my) {
        return mx >= morphPrevLeft && mx <= morphPrevRight && my >= morphPrevTop && my <= morphPrevBottom;
    }

    private boolean anyTextFieldFocused() {
        return (searchBox != null && searchBox.isFocused())
                || (entityIdBox != null && entityIdBox.isFocused())
                || (durationBox != null && durationBox.isFocused())
                || (presetNameBox != null && presetNameBox.isFocused())
                || (commentBox != null && commentBox.isFocused())
                || (startDelayBox != null && startDelayBox.isFocused())
                || (loopCountBox != null && loopCountBox.isFocused())
                || (bulkTicksBox != null && bulkTicksBox.isFocused())
                || (bulkPercentBox != null && bulkPercentBox.isFocused())
                || (multipleBox != null && multipleBox.isFocused())
                || (replaceIdBox != null && replaceIdBox.isFocused())
                || (inlineEditVisible && inlineEditField != null && inlineEditField.isFocused())
                || (uiStepHBox != null && uiStepHBox.isFocused())
                || (uiLeftPctBox != null && uiLeftPctBox.isFocused())
                || (uiRightPctBox != null && uiRightPctBox.isFocused())
                || (uiMenuMaxItemsBox != null && uiMenuMaxItemsBox.isFocused())
                || (uiPreviewScaleBox != null && uiPreviewScaleBox.isFocused());
    }

    private void blurAllTextFields() {
        if (searchBox != null) searchBox.setFocused(false);
        if (entityIdBox != null) entityIdBox.setFocused(false);
        if (durationBox != null) durationBox.setFocused(false);
        if (presetNameBox != null) presetNameBox.setFocused(false);
        if (commentBox != null) commentBox.setFocused(false);
        if (startDelayBox != null) startDelayBox.setFocused(false);
        if (loopCountBox != null) loopCountBox.setFocused(false);
        if (bulkTicksBox != null) bulkTicksBox.setFocused(false);
        if (bulkPercentBox != null) bulkPercentBox.setFocused(false);
        if (multipleBox != null) multipleBox.setFocused(false);
        if (replaceIdBox != null) replaceIdBox.setFocused(false);
        if (inlineEditField != null) inlineEditField.setFocused(false);
        if (uiStepHBox != null) uiStepHBox.setFocused(false);
        if (uiLeftPctBox != null) uiLeftPctBox.setFocused(false);
        if (uiRightPctBox != null) uiRightPctBox.setFocused(false);
        if (uiMenuMaxItemsBox != null) uiMenuMaxItemsBox.setFocused(false);
        if (uiPreviewScaleBox != null) uiPreviewScaleBox.setFocused(false);
    }

    private static boolean isEnterKey(int keyCode) { return keyCode == 257 || keyCode == 335; }

    public static boolean hasControlDown() { return Screen.hasControlDown(); }

    @Override
    public void close() {
        // Живое превью LivingEntity
        if (previewEntity != null) {
            try { previewEntity.discard(); } catch (Throwable ignored) {}
            previewEntity = null;
        }
        // NEW: универсальное превью (любой Entity)
        if (previewEntityAny != null) {
            try { previewEntityAny.discard(); } catch (Throwable ignored) {}
            previewEntityAny = null;
        }
        // OLD: кэш иконок Living
        if (!morphIconCache.isEmpty()) {
            for (var e : morphIconCache.values()) {
                try { if (e != null) e.discard(); } catch (Throwable ignored) {}
            }
            morphIconCache.clear();
        }
        // NEW: кэш иконок любых Entity (AEC/Fangs/ItemDisplay/и т.д.)
        if (!morphIconCacheAny.isEmpty()) {
            for (var e : morphIconCacheAny.values()) {
                try { if (e != null) e.discard(); } catch (Throwable ignored) {}
            }
            morphIconCacheAny.clear();
        }

        // Флеш «хвоста» повтора логов (если был спам одного сообщения)
        try { dbgFlushRepeats(); } catch (Throwable ignored) {}

        super.close();
    }

    private void withMainPreview(Runnable r) {
        PreviewDebug.inMainPreview(true);
        try {
            r.run();
        } finally {
            PreviewDebug.inMainPreview(false);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);

        Text titleText = Text.literal("Sequencer" + (isDirty() ? " *" : ""));
        ctx.drawText(textRenderer, titleText, this.width / 2 - textRenderer.getWidth(titleText) / 2, PAD + 2, 0xFFFFFF, true);

        super.render(ctx, mouseX, mouseY, delta);

        if (currentTab == Tab.MORPHS) {
            // “Найдено: N”
            ctx.drawText(textRenderer, Text.literal("Найдено: " + morphCountShown), PAD, this.height - 22, 0x888888, false);

            // Подложка под поле поиска поверх спис ка
            if (searchBox != null) {
                int fx = searchBox.getX();
                int fy = searchBox.getY();
                int fw = searchBox.getWidth();
                int fh = searchBox.getHeight();
                ctx.getMatrices().push();
                ctx.getMatrices().translate(0, 0, 300);
                ctx.fill(fx - 2, fy - 2, fx + fw + 2, fy + fh + 2, 0xAA202020);
                ctx.fill(fx - 2, fy - 2, fx + fw + 2, fy - 1, 0xFF000000);
                ctx.fill(fx - 2, fy + fh + 1, fx + fw + 2, fy + fh + 2, 0xFF000000);
                ctx.fill(fx - 2, fy - 2, fx - 1, fy + fh + 2, 0xFF000000);
                ctx.fill(fx + fw + 1, fy - 2, fx + fw + 2, fy + fh + 2, 0xFF000000);
                ctx.getMatrices().pop();
            }

            // Геометрия области превью (восстанавливаем локальные переменные)
            int contentTop = PAD + TAB_H + PAD;
            int rightX = PAD + 200 + PAD;

            int btnCount = 3;
            int btnBlockTop = contentTop + 8;
            int previewTop = btnBlockTop + btnCount * ROW + 10;

            int previewLeft = rightX;
            int previewRight = this.width - PAD;
            int previewBottom = this.height - PAD - 24;

            if (previewBottom < previewTop + 80) previewBottom = previewTop + 80;
            if (previewRight < previewLeft + 120) previewRight = previewLeft + 120;

            // Сохраняем для hit-test вращения/зума
            morphPrevLeft = previewLeft;
            morphPrevTop = previewTop;
            morphPrevRight = previewRight;
            morphPrevBottom = previewBottom;

            // Фон/рамка превью
            ctx.fill(previewLeft, previewTop, previewRight, previewBottom, 0x2A000000);
            ctx.fill(previewLeft, previewTop, previewRight, previewTop + 1, 0x55000000);
            ctx.fill(previewLeft, previewBottom - 1, previewRight, previewBottom, 0x55000000);
            ctx.fill(previewLeft, previewTop, previewLeft + 1, previewBottom, 0x55000000);
            ctx.fill(previewRight - 1, previewTop, previewRight, previewBottom, 0x55000000);

            // Подавление превью во время набора (и 200 мс после)
            boolean mFilterActive = (searchBox != null && searchBox.getText() != null && !searchBox.getText().isBlank());
            boolean mAllowPreview = !mFilterActive && (System.currentTimeMillis() - searchLastChangeMs > 200);

// Любой Entity для превью
            Entity toRenderAny = null;
            if (mAllowPreview) {
                try {
                    toRenderAny = getOrCreatePreviewAny();
                } catch (Throwable t) {
                    Identifier bad = (selectedEntityType != null) ? Registries.ENTITY_TYPE.getId(selectedEntityType) : null;
                    if (bad != null) badPreviewIds.add(bad.toString());
                    try { if (previewEntityAny != null) previewEntityAny.discard(); } catch (Throwable ignored) {}
                    previewEntityAny = null;
                    setStatus("Превью отключено для: " + (bad != null ? bad.toString() : "(unknown)"));
                }
            }

            if (toRenderAny != null) {
                int areaW = previewRight - previewLeft;
                int areaH = previewBottom - previewTop;

                int pX = previewLeft + areaW / 2;
                int pY = previewTop + areaH - 10;

                float h = (toRenderAny instanceof LivingEntity le) ? Math.max(1.0f, le.getHeight()) : 1.0f;
                float w = (toRenderAny instanceof LivingEntity le) ? Math.max(0.6f, le.getWidth()) : 0.6f;
                int base = Math.min(areaW, areaH);
                float autoScale = (float) Math.max(24, Math.min(96, (int) (base / (Math.max(h, w) + 0.5f))));
                int scale = (int) clampf((float) (autoScale * uiConfig.previewBaseScale * morphZoomUser), 12f, 200f);

                float rotDX = -morphRotY;
                float rotDY = -morphRotX;

                PreviewDebug.inMainPreview(true);
                try {
                    if (toRenderAny instanceof LivingEntity le) {
                        InventoryScreen.drawEntity(ctx, pX, pY, scale, rotDX, rotDY, le);
                    } else {
                        drawEntityAny(ctx, pX, pY, scale, rotDX, rotDY, toRenderAny);
                    }
                } catch (Throwable t) {
                    Identifier bad = (selectedEntityType != null) ? Registries.ENTITY_TYPE.getId(selectedEntityType) : null;
                    if (bad != null) badPreviewIds.add(bad.toString());
                    try { if (previewEntityAny != null) previewEntityAny.discard(); } catch (Throwable ignored) {}
                    previewEntityAny = null;
                    setStatus("Превью отключено для: " + (bad != null ? bad.toString() : "(unknown)"));
                } finally {
                    PreviewDebug.inMainPreview(false);
                }

                Identifier id = selectedEntityType != null ? Registries.ENTITY_TYPE.getId(selectedEntityType) : null;
                if (id != null) {
                    String s = id.toString();
                    int sw = textRenderer.getWidth(s);
                    ctx.drawText(textRenderer, Text.literal(s), pX - sw / 2, previewBottom - 16, 0xAAAAAA, false);
                }
            } else {
                // Фолбэк: рисуем предмет (spawn egg или маппинг) для не-Living сущностей
                Identifier sid = (selectedEntityType != null) ? Registries.ENTITY_TYPE.getId(selectedEntityType) : null;
                Item fallback = null;
                if (sid != null) {
                    // 1) пробуем spawn egg
                    Item egg = SpawnEggItem.forEntity(selectedEntityType);
                    if (egg != null) fallback = egg;
                    // 2) иначе берём из нашего маппинга
                    if (fallback == null) fallback = entityPreviewItems.get(sid.toString());
                }

                if (fallback != null) {
                    // Рисуем крупный предмет по центру области превью
                    int areaW = previewRight - previewLeft;
                    int areaH = previewBottom - previewTop;
                    int pX = previewLeft + areaW / 2;
                    int pY = previewTop + areaH / 2;

                    ItemStack stack = new ItemStack(fallback);
                    ctx.getMatrices().push();
                    ctx.getMatrices().translate(0, 0, 300); // поверх подложки
                    float scale = Math.max(2.5f, Math.min(5.0f, Math.min(areaW, areaH) / 32f)); // 16px * scale
                    ctx.getMatrices().scale(scale, scale, scale);
                    int drawX = (int) ((pX - 8) / scale);
                    int drawY = (int) ((pY - 8) / scale);
                    ctx.drawItem(stack, drawX, drawY);
                    ctx.drawItemInSlot(textRenderer, stack, drawX, drawY);
                    ctx.getMatrices().pop();

                    // подпись id
                    String s = sid != null ? sid.toString() : "";
                    int sw = textRenderer.getWidth(s);
                    ctx.drawText(textRenderer, Text.literal(s), pX - sw / 2, previewBottom - 16, 0xAAAAAA, false);
                } else {
                    // ничего подходящего не нашли — оставим текст
                    String msg = "Выберите сущность";
                    ctx.drawText(textRenderer, Text.literal(msg), rightX, previewTop + 8, 0xAAAAAA, false);
                }
            }
        } else {
            drawStatusLine(ctx);
        }

        if (currentTab == Tab.PRESETS) {
            renderStepsHeader(ctx);
            // Фон под поле фильтра — чтобы его было видно поверх “грязевого” фона
            if (stepFilterBox != null) {
                int fx = stepFilterBox.getX();
                int fy = stepFilterBox.getY();
                int fw = stepFilterBox.getWidth();
                int fh = stepFilterBox.getHeight();
                ctx.getMatrices().push();
                ctx.getMatrices().translate(0, 0, 300); // над остальным
                // полупрозрачная плашка + окантовка
                ctx.fill(fx - 2, fy - 2, fx + fw + 2, fy + fh + 2, 0xAA202020);
                ctx.fill(fx - 2, fy - 2, fx + fw + 2, fy - 1, 0xFF000000);
                ctx.fill(fx - 2, fy + fh + 1, fx + fw + 2, fy + fh + 2, 0xFF000000);
                ctx.fill(fx - 2, fy - 2, fx - 1, fy + fh + 2, 0xFF000000);
                ctx.fill(fx + fw + 1, fy - 2, fx + fw + 2, fy + fh + 2, 0xFF000000);
                ctx.getMatrices().pop();
            }
            renderTimeline(ctx, mouseX, mouseY);
            // Визуальные направляющие разделителей (подготовка к п.1)
            drawResizersOverlay(ctx);

            // Попап автодополнения ID (подготовка к п.3)
            drawIdAutocompletePopup(ctx, mouseX, mouseY);

            // Призрачный курсор скраббинга по таймлайну (подготовка к п.4)
            if (timelineScrubbing) {
                int x = Math.max(timelineLeft + 6, Math.min(timelineRight - 6, timelineScrubMouseX));
                ctx.fill(x - 1, timelineTop + 2, x + 1, timelineBottom - 2, 0xAAFFD54F);
            }
            if (stepList != null) stepList.renderDropIndicator(ctx);
        }

        if (contextMenu != null && contextMenu.visible) contextMenu.render(ctx);
        if (confirmDeleteVisible) renderConfirmDialog(ctx);
        if (hotkeysOverlayVisible) renderHotkeysOverlay(ctx);
    }

    // Overlay для разделителей панелей
    private void drawResizersOverlay(DrawContext ctx) {
        if (currentTab != Tab.PRESETS) return;
        // Левая полоска
        if (leftResizerX > 0) {
            int x = (ghostLeftResizerX >= 0) ? ghostLeftResizerX : leftResizerX;
            ctx.fill(x - RESIZER_W/2, PAD + TAB_H + PAD, x + RESIZER_W/2, this.height - PAD - 24, 0x22FFFFFF);
        }
        // Правая полоска (нет в компактной раскладке)
        if (rightResizerX > 0) {
            int x = (ghostRightResizerX >= 0) ? ghostRightResizerX : rightResizerX;
            ctx.fill(x - RESIZER_W/2, PAD + TAB_H + PAD, x + RESIZER_W/2, this.height - PAD - 24, 0x22FFFFFF);
        }
    }

    // Рисуем попап автодополнения для entityIdBox
    private void drawIdAutocompletePopup(DrawContext ctx, int mouseX, int mouseY) {
        if (entityIdBox == null || !entityIdBox.isFocused()) { idAutocompleteVisible = false; return; }
        String q = entityIdBox.getText() == null ? "" : entityIdBox.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) { idAutocompleteVisible = false; return; }



        // Обновляем список подсказок лениво
        if (!idAutocompleteVisible) {
            idSuggestions = allEntityIds.stream().filter(s -> s.contains(q)).limit(200).toList();
            idSuggestIndex = 0;
            idSuggestScroll = 0;
        }
        idAutocompleteVisible = !idSuggestions.isEmpty();
        if (!idAutocompleteVisible) return;

        int x = entityIdBox.getX();
        int y = entityIdBox.getY() + entityIdBox.getHeight() + 2;
        int w = Math.max(entityIdBox.getWidth(), 180);
        int maxRows = Math.min(ID_SUGGEST_MAX_VISIBLE, Math.max(2, this.height / 20));
        int rows = Math.min(maxRows, idSuggestions.size());
        int itemH = 12;
        int h = rows * itemH + 6;


        // Сохраняем габариты для hit‑test
        idPopupX = x; idPopupY = y; idPopupW = w; idPopupH = h; idPopupItemH = itemH;

        // коробка
        ctx.fill(x, y, x + w, y + h, 0xEE1E1E1E);
        ctx.fill(x, y, x + w, y + 1, 0xFF000000);
        ctx.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        ctx.fill(x, y, x + 1, y + h, 0xFF000000);
        ctx.fill(x + w - 1, y, x + w, y + h, 0xFF000000);

        // видимая часть
        int start = Math.max(0, Math.min(idSuggestScroll, Math.max(0, idSuggestions.size() - rows)));
        for (int i = 0; i < rows; i++) {
            int idx = start + i;
            if (idx >= idSuggestions.size()) break;
            String s = idSuggestions.get(idx);
            int iy = y + 3 + i * itemH;
            boolean sel = (idx == idSuggestIndex);
            if (sel) ctx.fill(x + 2, iy - 1, x + w - 2, iy + itemH - 1, 0x334A90E2);
            ctx.drawTextWithShadow(textRenderer, Text.literal(s), x + 4, iy, sel ? 0xFFFFFF : 0xDDDDDD);
        }
    }

    private void renderStepsHeader(DrawContext ctx) {
        if (stepList == null) return;
        int n = stepList.children().size();
        int ticksAll = ticksOfAll();
        double secsAll = ticksAll / 20.0;

        List<Integer> sel = getMarkedOrSelectedIndices();
        int ticksSel = ticksOfIndices(sel);
        double secsSel = ticksSel / 20.0;

        String sAll = String.format(Locale.ROOT, "Шагов: %d, суммарно %d тиков (~%.1f c)", n, ticksAll, secsAll);
        String sSel = sel.isEmpty() ? "" : String.format(Locale.ROOT, " | Выделено: %d тиков (~%.1f c)", ticksSel, secsSel);
        String s = sAll + sSel;

        int x = stepList.getRowLeftPublic() + 4;
        int y = stepList.getViewTop() - 14;
        if (y > (PAD + TAB_H + PAD)) ctx.drawTextWithShadow(textRenderer, Text.literal(s), x, y, 0xCCCCCC);
    }

    // ===== Таймлайн (с учётом компактной раскладки) =====
    private void renderTimeline(DrawContext ctx, int mouseX, int mouseY) {
        if (stepList == null || selectedPresetName == null) return;
        SequencePreset p = selectedPreset();
        if (p == null || p.steps.isEmpty()) return;

        int left = stepList.getRowLeftPublic() + 2;
        int right = stepList.getScrollbarPositionX() - 6;
        int top = stepList.getViewTop() + stepList.getViewHeight() + 6;
        int bottom = top + TIMELINE_H;

        // Базовые ограничения
        top = Math.min(Math.max(top, PAD + TAB_H + PAD + 24), this.height - PAD - 48);
        bottom = Math.min(bottom, this.height - PAD - 24);

        // Если панель справа находится под списком (компактная раскладка), не даём таймлайну наехать на неё
        if (compactPresetsLayout && presetsPanelTopY > 0) {
            bottom = Math.min(bottom, presetsPanelTopY - 6);
            if (bottom <= top + 6) return;
        }

        timelineLeft = left; timelineRight = right; timelineTop = top; timelineBottom = bottom;

        // Рамка
        ctx.fill(left, top, right, bottom, 0x22111111);
        ctx.fill(left, top, right, top + 1, 0x55333333);
        ctx.fill(left, bottom - 1, right, bottom, 0x55333333);
        ctx.fill(left, top, left + 1, bottom, 0x55333333);
        ctx.fill(right - 1, top, right, bottom, 0x55333333);

        int totalTicks = ticksOfAll();
        if (totalTicks <= 0) return;

        int x0 = left + 6;
        int x1 = right - 6;
        int width = Math.max(1, x1 - x0);

        this.tlX0 = x0;
        this.tlWidth = width;

        int cursorX = x0;
        int yMid = (top + bottom) / 2;

        // Блоки шагов
        for (int i = 0; i < p.steps.size(); i++) {
            int t = Math.max(1, p.steps.get(i).durationTicks);
            int w = Math.max(1, (int) Math.round((t / (double) totalTicks) * width));
            int bx0 = cursorX;
            int bx1 = Math.min(x0 + width, cursorX + w);

            ctx.fill(bx0, yMid - 6, bx1, yMid + 6, 0x334A90E2);
            ctx.fill(bx1 - 1, yMid - 7, bx1, yMid + 7, 0x664A90E2);

            cursorX = bx1;
        }

        // Подсветка выделенного шага
        var selEntry = stepList.getSelectedOrNull();
        if (selEntry != null) {
            int selIdx = selEntry.index;
            int startTick = 0;
            for (int i = 0; i < selIdx; i++) startTick += Math.max(1, p.steps.get(i).durationTicks);
            int endTick = startTick + Math.max(1, p.steps.get(selIdx).durationTicks);

            int selX0 = x0 + (int) Math.round((startTick / (double) totalTicks) * width);
            int selX1 = x0 + (int) Math.round((endTick / (double) totalTicks) * width);

            ctx.fill(selX0, top + 2, selX1, top + 4, 0xFF00C853);
            ctx.fill(selX0, bottom - 4, selX1, bottom - 2, 0xFF00C853);
        }

        // Плеер: текущий прогресс
        try {
            var info = SimpleSequencePlayerClient.getInfo();
            int playTick = 0;
            int total = totalTicks;
            boolean draw = false;
            try {
                var f = info.getClass().getDeclaredField("currentTick");
                f.setAccessible(true);
                Object v = f.get(info);
                if (v instanceof Integer iv) { playTick = Math.max(0, Math.min(total, iv)); draw = true; }
            } catch (Throwable ignore) { }
            if (draw && total > 0) {
                int px = x0 + (int) Math.round((playTick / (double) total) * width);
                ctx.fill(px - 1, top + 2, px + 1, bottom - 2, 0xFFFFD54F);
            }
        } catch (Throwable ignore) { }
    }

    private void jumpToStepByTimeline(double mouseX) {
        SequencePreset p = selectedPreset();
        if (p == null || p.steps.isEmpty()) return;
        int totalTicks = ticksOfAll();
        if (totalTicks <= 0) return;

        int x0 = timelineLeft + 6;
        int x1 = timelineRight - 6;
        int width = Math.max(1, x1 - x0);

        int mx = (int) mouseX;
        int clamped = Math.max(x0, Math.min(x1, mx));
        double frac = (clamped - x0) / (double) width;
        int targetTick = (int) Math.round(totalTicks * frac);

        int acc = 0;
        int idx = 0;
        for (int i = 0; i < p.steps.size(); i++) {
            int t = Math.max(1, p.steps.get(i).durationTicks);
            if (targetTick < acc + t) { idx = i; break; }
            acc += t;
            idx = Math.min(p.steps.size() - 1, i);
        }
        if (stepList != null) {
            selectViewByModelIndex(idx);
            rememberSelection();
        }
    }

    private int viewToModel(int viewIdx) {
        if (viewIdx < 0 || viewIdx >= stepViewToModel.size()) return -1;
        return stepViewToModel.get(viewIdx);
    }
    private int modelToView(int modelIdx) {
        if (modelIdx < 0 || modelIdx >= stepModelToView.length) return -1;
        return stepModelToView[modelIdx];
    }
    private void selectViewByModelIndex(int modelIdx) {
        if (stepList == null) return;
        int v = modelToView(modelIdx);
        if (v >= 0 && v < stepList.children().size()) {
            stepList.setSelectedIndex(v);
            stepList.centerRowIfHidden(v);
            stepList.setAnchor(v);
        }
    }
    private boolean stepMatchesFilterFor(int modelIndex, String entityId, String comment) {
        if (stepFilterLower == null || stepFilterLower.isBlank()) return true;
        String number = String.valueOf(modelIndex + 1); // номер шага 1-based
        String hay = (number + " " + entityId + " " + (comment == null ? "" : comment)).toLowerCase(java.util.Locale.ROOT);
        return hay.contains(stepFilterLower);
    }

    // ===== Статусная строка =====
    private void drawStatusLine(DrawContext ctx) {
        int nSel = currentTab == Tab.PRESETS ? getMarkedOrSelectedIndices().size() : 0;
        int tSel = currentTab == Tab.PRESETS ? ticksOfIndices(getMarkedOrSelectedIndices()) : 0;
        int tAll = ticksOfAll();
        double secsSel = tSel / 20.0;
        double secsAll = tAll / 20.0;

        String left = (currentTab == Tab.PRESETS)
                ? String.format(Locale.ROOT, "Выбрано: %d, суммарно %d тиков (~%.1fs)", nSel, tSel, secsSel)
                : "Откройте «Настройки», чтобы изменить горячие клавиши и размеры интерфейса";
        String right = String.format(Locale.ROOT, "Всего: %d тиков (~%.1fs)", tAll, secsAll);

        int y = this.height - 22;
        ctx.fill(PAD, y - 2, this.width - PAD, y + 16, 0x66000000);
        ctx.drawText(textRenderer, Text.literal(left), PAD + 6, y + 2, 0xCCCCCC, false);
        int rw = textRenderer.getWidth(right);
        ctx.drawText(textRenderer, Text.literal(right), this.width - PAD - 6 - rw, y + 2, 0xAAAAAA, false);

        if (System.currentTimeMillis() < statusUntilMs && statusMsg != null && !statusMsg.isEmpty()) {
            String msg = statusMsg;
            int mw = textRenderer.getWidth(msg);
            int mx = (this.width - mw) / 2;
            ctx.drawText(textRenderer, Text.literal(msg), mx, y + 2, 0xFFFFFF, false);
        }
    }
    private void setStatus(String msg) { statusMsg = msg; statusUntilMs = System.currentTimeMillis() + 3500; }

    // ===== Работа с комментариями шагов =====
    private List<String> ensureCommentsForPreset(String presetName, int size) {
        List<String> list = presetComments.computeIfAbsent(presetName, k -> new ArrayList<>());
        while (list.size() < size) list.add("");
        while (list.size() > size) list.remove(list.size() - 1);
        return list;
    }
    private String getComment(String presetName, int index) {
        List<String> list = presetComments.get(presetName);
        if (list == null || index < 0 || index >= list.size()) return "";
        String s = list.get(index);
        return s == null ? "" : s;
    }
    private void setComment(String presetName, int index, String value) {
        if (presetName == null) return;
        SequencePreset p = presets.get(presetName);
        if (p == null) return;
        List<String> list = ensureCommentsForPreset(presetName, p.steps.size());
        if (index >= 0 && index < list.size()) {
            list.set(index, value == null ? "" : value);
            markDirty();
        }
    }

    // ===== Выделение и буфер обмена =====
    private List<Integer> getMarkedOrSelectedIndices() {
        List<Integer> out = new ArrayList<>();
        if (stepList == null) return out;

        for (int v = 0; v < stepList.children().size(); v++) {
            if (stepList.isMarked(v)) {
                int m = viewToModel(v);
                if (m >= 0) out.add(m);
            }
        }
        if (out.isEmpty()) {
            var sel = stepList.getSelectedOrNull();
            if (sel != null) {
                int m = modelIndexOf(sel);
                if (m >= 0) out.add(m);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;

    }

    private int modelIndexOf(StepEntry e) {
        return (e == null) ? -1 : e.modelIndex;
    }

    private boolean hasSelection() {
        if (stepList == null) return false;
        return !getMarkedOrSelectedIndices().isEmpty();
    }

    private void markAllSteps() {
        if (stepList == null) return;
        pushUndoSnapshot(); redoStack.clear();
        stepList.markAll();
        rememberSelection();
    }

    private void clearStepMarks() {
        if (stepList == null) return;
        pushUndoSnapshot(); redoStack.clear();
        stepList.clearMarks();
        rememberSelection();
    }

    private void invertSelection() {
        if (stepList == null) return;
        pushUndoSnapshot(); redoStack.clear();
        stepList.invertMarks();
        rememberSelection();
    }

    private void copySelectedSteps() {
        SequencePreset p = selectedPreset();
        if (p == null) return;
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения для копирования"); return; }
        stepClipboard.clear();
        commentsClipboard.clear();
        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        for (int i : idxs) {
            if (i >= 0 && i < p.steps.size()) {
                SequencePreset.Step s = p.steps.get(i);
                stepClipboard.add(new SequencePreset.Step(s.entityId, s.durationTicks));
                commentsClipboard.add(i < com.size() ? com.get(i) : "");
            }
        }
        setStatus("Скопировано шагов: " + stepClipboard.size());
    }

    private boolean canPaste() { return !stepClipboard.isEmpty(); }

    private void pasteSteps() {
        if (!canPaste()) { setStatus("Буфер пуст"); return; }
        SequencePreset p = selectedPreset();
        if (p == null) return;

        pushUndoSnapshot(); redoStack.clear();

        int insertAt;
        var sel = (stepList != null) ? stepList.getSelectedOrNull() : null;
        if (sel != null) insertAt = Math.min(p.steps.size(), sel.index + 1);
        else insertAt = p.steps.size();

        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        for (int i = 0; i < stepClipboard.size(); i++) {
            SequencePreset.Step s = stepClipboard.get(i);
            p.steps.add(insertAt + i, new SequencePreset.Step(s.entityId, s.durationTicks));
        }
        com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        for (int i = 0; i < stepClipboard.size(); i++) {
            String c = (i < commentsClipboard.size()) ? commentsClipboard.get(i) : "";
            int at = Math.min(insertAt + i, com.size() - 1);
            com.set(at, c == null ? "" : c);
        }

        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();
        int newSel = Math.min(insertAt + stepClipboard.size() - 1, p.steps.size() - 1);
        if (stepList != null) selectViewByModelIndex(newSel);
        rememberSelection();
        setStatus("Вставлено шагов: " + stepClipboard.size());
    }

    private void duplicateSelectedSteps() {
        copySelectedSteps();
        pasteSteps();
        setStatus("Дублирование выполнено");
    }

    // ===== Удаление с подтверждением =====
    private void handleDeleteWithConfirm() {
        SequencePreset p = selectedPreset();
        if (p == null) return;
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения для удаления"); return; }

        confirmDeleteCount = idxs.size();
        onConfirmDelete = this::deleteSelectedSteps;
        buildConfirmDialogGeometry();
        confirmDeleteVisible = true;
    }

    private void deleteSelectedSteps() {
        SequencePreset p = selectedPreset();
        if (p == null) return;
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) return;

        pushUndoSnapshot(); redoStack.clear();

        idxs.sort(Comparator.reverseOrder());
        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        for (int i : idxs) {
            if (i >= 0 && i < p.steps.size()) {
                p.steps.remove(i);
                if (i >= 0 && i < com.size()) com.remove(i);
            }
        }

        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();

        if (stepList != null && p.steps.size() > 0) {
            int next = Math.min(idxs.get(idxs.size() - 1), p.steps.size() - 1);
            selectViewByModelIndex(next);
        }

        rememberSelection();
        setStatus("Удалено шагов: " + idxs.size());
    }

    // ===== Массовые операции тиков =====
    private void bulkSetTicks() {
        SequencePreset p = selectedPreset();
        if (p == null) return;
        int v = parseIntSafe(bulkTicksBox.getText(), -1);
        if (v <= 0) { setStatus("Некорректное значение тиков"); return; }
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения"); return; }

        pushUndoSnapshot(); redoStack.clear();
        for (int i : idxs) if (i >= 0 && i < p.steps.size()) p.steps.get(i).durationTicks = v;
        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();
        setStatus("Установлено " + v + " тиков для " + idxs.size() + " шагов");
    }

    private void bulkAddTicks() {
        SequencePreset p = selectedPreset();
        if (p == null) return;
        int dv = parseIntSafe(bulkTicksBox.getText(), 0);
        if (dv == 0) { setStatus("Смещение тиков = 0"); return; }
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения"); return; }

        pushUndoSnapshot(); redoStack.clear();
        for (int i : idxs) if (i >= 0 && i < p.steps.size()) {
            int old = Math.max(1, p.steps.get(i).durationTicks);
            p.steps.get(i).durationTicks = Math.max(1, old + dv);
        }
        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();
        setStatus((dv >= 0 ? "Прибавлено " : "Убавлено ") + Math.abs(dv) + " тиков у " + idxs.size() + " шагов");
    }

    // ===== Перемещение одиночного шага =====
    private boolean canMoveUp() {
        if (stepList == null) return false;
        var sel = stepList.getSelectedOrNull();
        return sel != null && sel.index > 0;
    }
    private boolean canMoveDown() {
        if (stepList == null) return false;
        var sel = stepList.getSelectedOrNull();
        SequencePreset p = selectedPreset();
        return sel != null && p != null && sel.index < p.steps.size() - 1;
    }

    private void moveStep(int dir) {
        SequencePreset p = selectedPreset();
        if (p == null || stepList == null) return;
        var sel = stepList.getSelectedOrNull();
        if (sel == null) return;

        int from = sel.index;
        int to = from + (dir < 0 ? -1 : 1);
        if (to < 0 || to >= p.steps.size()) return;

        pushUndoSnapshot(); redoStack.clear();

        Collections.swap(p.steps, from, to);

        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        if (from >= 0 && to >= 0 && from < com.size() && to < com.size()) {
            String tmp = com.get(from);
            com.set(from, com.get(to));
            com.set(to, tmp);
        }

        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();

        selectViewByModelIndex(to);
        rememberSelection();
    }

    // ===== Перемещение блока (помеченных/выбранных) =====
    private boolean canMoveBlockUp() {
        SequencePreset p = selectedPreset();
        if (p == null || stepList == null) return false;
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) return false;
        java.util.HashSet<Integer> set = new java.util.HashSet<>(idxs);
        for (int i : idxs) {
            if (i > 0 && !set.contains(i - 1)) return true;
        }
        return false;
    }
    private boolean canMoveBlockDown() {
        SequencePreset p = selectedPreset();
        if (p == null || stepList == null) return false;
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) return false;
        java.util.HashSet<Integer> set = new java.util.HashSet<>(idxs);
        for (int i : idxs) {
            if (i < p.steps.size() - 1 && !set.contains(i + 1)) return true;
        }
        return false;
    }

    private void moveBlock(int dir) {
        SequencePreset p = selectedPreset();
        if (p == null || stepList == null) return;
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) return;

        boolean contiguous = isContiguous(idxs);
        if (contiguous) {
            int target = dir < 0 ? (idxs.get(0) - 1) : (idxs.get(idxs.size() - 1) + 2);
            moveBlockToIndex(target);
            return;
        }

        // Разрознённый выбор — сдвигаем каждый шаг на 1 без слипания
        moveSparseBlockByOne(dir, idxs);
    }

    private void moveBlockToIndex(int rawTargetIndex) {
        SequencePreset p = selectedPreset();
        if (p == null || stepList == null) return;
        List<Integer> selIdx = getMarkedOrSelectedIndices();
        if (selIdx.isEmpty()) return;

        pushUndoSnapshot(); redoStack.clear();

        List<SequencePreset.Step> moving = new ArrayList<>();
        List<String> movingCom = new ArrayList<>();
        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        for (int i : selIdx) {
            moving.add(p.steps.get(i));
            movingCom.add(i < com.size() ? com.get(i) : "");
        }

        for (int i = selIdx.size() - 1; i >= 0; i--) {
            int idx = selIdx.get(i);
            p.steps.remove(idx);
            if (idx < com.size()) com.remove(idx);
        }

        int target = rawTargetIndex;
        target = Math.max(0, Math.min(target, p.steps.size()));

        int removedBefore = 0;
        for (int idx : selIdx) if (idx < target) removedBefore++;
        target -= removedBefore;

        for (int i = 0; i < moving.size(); i++) {
            p.steps.add(target + i, moving.get(i));
        }
        com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        for (int i = 0; i < movingCom.size(); i++) {
            int at = Math.min(target + i, com.size() - 1);
            com.set(at, movingCom.get(i));
        }

        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();

        if (stepList != null) {
            stepList.clearMarks();
            for (int i = 0; i < moving.size(); i++) {
                int modelIdx = target + i;
                int viewIdx = modelToView(modelIdx);
                if (viewIdx >= 0 && viewIdx < stepList.children().size()) stepList.toggleMarked(viewIdx);
            }
            selectViewByModelIndex(target);
        }
        rememberSelection();
        setStatus("Блок перемещён (" + moving.size() + " шагов)");
    }

    // Проверка: индексы идут подряд без разрывов
    private boolean isContiguous(List<Integer> idxs) {
        if (idxs == null || idxs.size() <= 1) return true;
        for (int k = 1; k < idxs.size(); k++) {
            if (idxs.get(k) != idxs.get(k - 1) + 1) return false;
        }
        return true;
    }

    // Сдвиг разрознённого выбора на 1 позицию вверх/вниз без "слипания" в один блок
    private void moveSparseBlockByOne(int dir, List<Integer> originalIdxs) {
        SequencePreset p = selectedPreset();
        if (p == null) return;
        if (originalIdxs == null || originalIdxs.isEmpty()) return;

        // Копии для вычислений
        java.util.ArrayList<Integer> idxs = new java.util.ArrayList<>(originalIdxs);
        java.util.HashSet<Integer> set = new java.util.HashSet<>(idxs);

        // Невозможность движения: если ни один не может сдвинуться — выходим
        boolean anyMovable = false;
        if (dir > 0) {
            for (int i : idxs) if (i < p.steps.size() - 1 && !set.contains(i + 1)) { anyMovable = true; break; }
        } else {
            for (int i : idxs) if (i > 0 && !set.contains(i - 1)) { anyMovable = true; break; }
        }
        if (!anyMovable) return;

        pushUndoSnapshot(); redoStack.clear();

        // Для сохранения комментариев — работаем синхронно
        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());

        // Двигаем по одному: вниз — с конца к началу; вверх — с начала к концу
        if (dir > 0) {
            // ВНИЗ: от большего индекса к меньшему
            idxs.sort(java.util.Comparator.reverseOrder());
            for (int i : idxs) {
                if (i >= 0 && i < p.steps.size() - 1 && !set.contains(i + 1)) {
                    // swap i <-> i+1
                    Collections.swap(p.steps, i, i + 1);
                    if (i >= 0 && i + 1 < com.size()) {
                        String tmp = com.get(i);
                        com.set(i, com.get(i + 1));
                        com.set(i + 1, tmp);
                    }
                }
            }
        } else {
            // ВВЕРХ: от меньшего к большему
            idxs.sort(java.util.Comparator.naturalOrder());
            for (int i : idxs) {
                if (i > 0 && i - 1 < p.steps.size() && !set.contains(i - 1)) {
                    // swap i-1 <-> i
                    Collections.swap(p.steps, i - 1, i);
                    if (i - 1 >= 0 && i < com.size()) {
                        String tmp = com.get(i - 1);
                        com.set(i - 1, com.get(i));
                        com.set(i, tmp);
                    }
                }
            }
        }

        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();

        // Пересобираем пометки и выбор на новых позициях
        if (stepList != null) {
            // Считаем новые модельные индексы после сдвига
            java.util.ArrayList<Integer> newModels = new java.util.ArrayList<>();
            for (int i : originalIdxs) {
                int candidate = dir > 0 ? i + 1 : i - 1;
                // Если сосед был тоже выделен (блокируется), индекс не меняется
                if ((dir > 0 && set.contains(i + 1)) || (dir < 0 && set.contains(i - 1))) candidate = i;
                candidate = Math.max(0, Math.min(candidate, p.steps.size() - 1));
                newModels.add(candidate);
            }

            stepList.clearMarks();
            // Отмечаем новые позиции в ВИДЕ
            for (int m : newModels) {
                int v = modelToView(m);
                if (v >= 0 && v < stepList.children().size()) stepList.toggleMarked(v);
            }
            // Выделим строку в зависимости от направления (нижнюю/верхнюю границу)
            int pickModel = (dir > 0)
                    ? newModels.stream().max(Integer::compareTo).orElse(newModels.get(0))
                    : newModels.stream().min(Integer::compareTo).orElse(newModels.get(0));
            selectViewByModelIndex(pickModel);
        }

        rememberSelection();
        setStatus(dir > 0 ? "Сдвинул вниз выделенные шаги" : "Сдвинул вверх выделенные шаги");
    }

    // ===== Вкладки =====
    private void buildMorphsTab() {
        int contentTop = PAD + TAB_H + PAD;

        int leftX = PAD;

        // Список морфов (добавляем первым, чтобы поиск был поверх)
        morphList = new MorphList(mc, 200, this.height - contentTop - PAD - 24, contentTop, this.height - PAD - 24, 22);
        morphList.setLeftPos(leftX);
        addDrawableChild(morphList);

        // Поле поиска — теперь поверх списка, прямо над ним
        searchBox = new TextFieldWidget(textRenderer, leftX, contentTop - 22, 200, 18, Text.literal("Поиск"));
        searchBox.setPlaceholder(Text.literal("Поиск морфа (имя/id)"));
        searchBox.setChangedListener(s -> {
            searchLastChangeMs = System.currentTimeMillis();
            if (morphList != null) morphList.setFilter(s);
        });
        addDrawableChild(searchBox);
        searchBox.setFocused(true);

        int rightX = leftX + 200 + PAD;
        int y = contentTop + 8;

        addDrawableChild(ButtonWidget.builder(Text.literal("Морфнуть"), b -> doMorphSelected())
                .position(rightX, y).size(160, ROW_H).build());
        y += ROW;

        presetCycleBtn = addDrawableChild(ButtonWidget.builder(Text.literal("Пресет: " + currentPresetLabel()), b -> {
            cyclePreset();
            b.setMessage(Text.literal("Пресет: " + currentPresetLabel()));
        }).position(rightX, y).size(160, ROW_H).build());
        y += ROW;

        addDrawableChild(ButtonWidget.builder(Text.literal("В пресет"), b -> addSelectedToPreset())
                .position(rightX, y).size(160, ROW_H).build());
    }

    private void buildPresetsTab() {
        int contentTop = PAD + TAB_H + PAD;

        if (presets.isEmpty()) {
            selectedPresetName = genUniquePresetName("preset");
            presets.put(selectedPresetName, new SequencePreset(selectedPresetName));
            PresetStorage.save(presets);
            markSaved();
        }

        if (presets.containsKey(uiConfig.lastPresetName)) {
            selectedPresetName = uiConfig.lastPresetName;
        } else if (selectedPresetName == null && !presets.isEmpty()) {
            selectedPresetName = presets.keySet().iterator().next();
        }

        int colH = Math.max(80, this.height - contentTop - PAD - FOOTER_H);
        int totalW = this.width - 2 * PAD;

        int minLeft = 140, maxLeft = 220;
        int minMid = 220, maxMid = 600;
        int minRight = 200, maxRight = 340;

        int leftW = clamp((int) (totalW * uiConfig.leftPanelPercent), minLeft, maxLeft);
        int rightW = clamp((int) (totalW * uiConfig.rightPanelPercent), minRight, maxRight);
        int midW = totalW - leftW - rightW - 2 * PAD;

        boolean compact = false;
        if (midW < minMid) {
            compact = true;
            leftW = clamp((int) (totalW * Math.max(0.22, uiConfig.leftPanelPercent)), minLeft, maxLeft);
            midW = totalW - leftW - PAD;
            rightW = midW;
        } else {
            midW = clamp(midW, minMid, Math.max(minMid, maxMid));
        }

        compactPresetsLayout = compact;

        int col1X = PAD;
        int col2X = col1X + leftW + PAD;

        leftResizerX = col2X - PAD / 2; // визуально — в зазоре между левой и средней
        rightResizerX = compact ? -1 : (col2X + midW + PAD / 2);
        ghostLeftResizerX = -1;
        ghostRightResizerX = -1;

        presetList = new PresetList(mc, leftW, colH, contentTop, contentTop + colH, 20);
        presetList.setLeftPos(col1X);
        addDrawableChild(presetList);
        presetList.rebuildFrom(presets, selectedPresetName);

        int timelineReserve = TIMELINE_H + 10;
        int stepH = compact ? (int) (colH * 0.52) : Math.max(80, colH - timelineReserve);

        stepList = new StepList(mc, midW, stepH, contentTop, contentTop + stepH, uiConfig.stepItemHeight);
        stepList.setLeftPos(col2X);
        addDrawableChild(stepList);
        // Строка фильтра шагов (подсветка совпадений)
        stepFilterBox = new TextFieldWidget(textRenderer, 0, 0, Math.max(120, midW), 18, Text.literal("Фильтр"));
        stepFilterBox.setX(stepList.getRowLeftPublic());
        stepFilterBox.setY(stepList.getViewTop() - 22); // прямо над списком
        stepFilterBox.setWidth(Math.max(160, midW));     // ширина = ширине списка
        stepFilterBox.setPlaceholder(Text.literal("Фильтр (номер / ID / комментарий)"));
        stepFilterBox.setChangedListener(s -> {
            stepFilterLower = (s == null ? "" : s.toLowerCase(Locale.ROOT).trim());
            reloadStepsFromSelected(); // мгновенно перестраиваем список
        });
        stepFilterBox.setEditable(true);
        addDrawableChild(stepFilterBox);
        reloadStepsFromSelected();
        if (selectedPresetName != null) {
            SequencePreset p = presets.get(selectedPresetName);
            if (p != null) ensureCommentsForPreset(selectedPresetName, p.steps.size());
        }

        if (stepList != null && uiConfig.lastStepIndex >= 0) {
            selectViewByModelIndex(uiConfig.lastStepIndex);
            var s = stepList.getSelectedOrNull();
            if (s != null) {
                if (entityIdBox != null) entityIdBox.setText(s.entityId);
                if (durationBox != null) durationBox.setText(String.valueOf(s.ticks));
                if (commentBox != null) commentBox.setText(getComment(selectedPresetName, s.modelIndex));
            }
        }

        int panelX = compact ? col2X : (col2X + midW + PAD);
        int panelY = compact ? (contentTop + stepH + PAD + TIMELINE_H + 10) : contentTop;
        int panelW = compact ? midW : rightW;
        int panelH = compact ? (colH - stepH - PAD - TIMELINE_H - 10) : colH;
        if (panelH < 160) panelH = 160;
        presetsPanelTopY = panelY;

        int maxPanelH = Math.max(80, this.height - panelY - PAD - FOOTER_H);
        panelH = Math.min(panelH, maxPanelH);

        panelList = new ControlPanelList(mc, panelW, panelH, panelY, panelY + panelH, ROW);
        panelList.setLeftPos(panelX);
        addDrawableChild(panelList);

        // Отображение текущего морфа шага + вход в режим выбора по двойному клику
        panelList.addRow(new MorphPickerEntry(
                panelW,
                () -> {
                    String label = "(нет шага)";
                    if (stepList != null && stepList.getSelectedOrNull() != null) {
                        var s = stepList.getSelectedOrNull();
                        label = (s.entityId == null || s.entityId.isBlank()) ? "(пусто)" : s.entityId;
                    }
                    return Text.literal("Морф: " + label + "  (двойной клик — выбрать)");
                },
                () -> beginMorphPick(false) // применим к текущему выбранному шагу
        ));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Отменить"), this::undo, this::hasUndo,
                Text.literal("Повторить"), this::redo, this::hasRedo));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Экспорт пресета (файл)"), () -> exportToFile(false), null,
                Text.literal("Экспорт выдел. (файл)"), () -> exportToFile(true), null));
        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Импорт из файла (JSON)"), this::importFromFileDialog));
        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Импорт из буфера (JSON)"), this::importFromClipboard));

        //entityIdBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("ID сущности"));
        //entityIdBox.setPlaceholder(Text.literal("minecraft:zombie"));
        //panelList.addRow(new LabeledFieldEntry(Text.literal("ID сущности"), entityIdBox, panelW, 110));

        durationBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Длительность (тики)"));
        durationBox.setTextPredicate(s -> s.matches("\\d*"));
        durationBox.setText("60");
        panelList.addRow(new LabeledFieldEntry(Text.literal("Тики"), durationBox, panelW, 110));

        commentBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Комментарий"));
        panelList.addRow(new LabeledFieldEntry(Text.literal("Комментарий"), commentBox, panelW, 110));

        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Добавить шаг"),
                this::addStepToCurrentSafe));

        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Удалить выделенные"),
                this::handleDeleteWithConfirm, this::hasSelection));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Вверх"), () -> moveStep(-1), this::canMoveUp,
                Text.literal("Вниз"), () -> moveStep(1), this::canMoveDown));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Блок вверх"), () -> moveBlock(-1), this::canMoveBlockUp,
                Text.literal("Блок вниз"), () -> moveBlock(1), this::canMoveBlockDown));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Играть с выбранного"), this::playFromSelectedOrStart, null,
                Text.literal("Играть выделение"), this::playSelection, this::hasSelection));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Выделить все"), this::markAllSteps, null,
                Text.literal("Сбросить выделение"), this::clearStepMarks, null));

        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Инвертировать выделение"), this::invertSelection));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Копировать"), this::copySelectedSteps, this::hasSelection,
                Text.literal("Вставить"), this::pasteSteps, this::canPaste));

        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Дублировать"), this::duplicateSelectedSteps, this::hasSelection));

        bulkTicksBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Массовые тики"));
        bulkTicksBox.setTextPredicate(s -> s.matches("-?\\d*"));
        bulkTicksBox.setText("60");
        panelList.addRow(new LabeledFieldEntry(Text.literal("Массовые тики"), bulkTicksBox, panelW, 140));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Установить"), this::bulkSetTicks, null,
                Text.literal("Прибавить"), this::bulkAddTicks, null));

        bulkPercentBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Процент, например 10 или -25"));
        bulkPercentBox.setTextPredicate(s -> s.matches("-?\\d{0,3}"));
        bulkPercentBox.setText("10");
        panelList.addRow(new LabeledFieldEntry(Text.literal("Сдвиг %"), bulkPercentBox, panelW, 140));
        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Применить сдвиг % к выделению"), this::bulkShiftPercent));

        multipleBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Кратность N"));
        multipleBox.setTextPredicate(s -> s.matches("\\d{0,3}"));
        multipleBox.setText("5");
        panelList.addRow(new LabeledFieldEntry(Text.literal("Нормализовать до кратности N"), multipleBox, panelW, 200));
        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Нормализовать (округление до ближайшей кратности)"), this::normalizeToMultiple));

        replaceIdBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Новый ID для выделенных"));
        replaceIdBox.setPlaceholder(Text.literal("minecraft:zombie"));
        panelList.addRow(new LabeledFieldEntry(Text.literal("Заменить ID"), replaceIdBox, panelW, 140));
        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Заменить ID у выделенных"), this::replaceEntityIdForSelection));

        presetNameBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Имя пресета"));
        presetNameBox.setText(selectedPresetName != null ? selectedPresetName : "");
        panelList.addRow(new LabeledFieldEntry(Text.literal("Имя пресета"), presetNameBox, panelW, 140));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Новый пресет"), () -> {
            String base = safeName(presetNameBox.getText().trim());
            if (base.isEmpty()) base = "preset";
            String name = genUniquePresetName(base);
            presets.put(name, new SequencePreset(name));
            presetComments.put(name, new ArrayList<>());
            selectedPresetName = name;
            uiConfig.lastPresetName = name;
            uiConfig.lastStepIndex = -1;
            uiConfig.saveTo(uiConfigPath);
            clearHistory();
            PresetStorage.save(presets);
            markSaved();
            clearAndInit();
        }, null,
                Text.literal("Переименовать"), this::renamePresetFromTextBox, null));

        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Удалить пресет"), () -> {
            if (selectedPresetName == null) return;
            presets.remove(selectedPresetName);
            presetComments.remove(selectedPresetName);
            clearHistory();
            PresetStorage.save(presets);
            markSaved();
            selectedPresetName = presets.isEmpty() ? null : presets.keySet().iterator().next();
            uiConfig.lastPresetName = selectedPresetName != null ? selectedPresetName : "";
            uiConfig.lastStepIndex = -1;
            uiConfig.saveTo(uiConfigPath);
            clearAndInit();
        }));

        startDelayBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Стартовая задержка (тики)"));
        startDelayBox.setTextPredicate(s -> s.matches("\\d*"));
        startDelayBox.setText("0");
        panelList.addRow(new LabeledFieldEntry(Text.literal("Старт. задержка"), startDelayBox, panelW, 140));

        loopCountBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Повторы (если не ∞)"));
        loopCountBox.setTextPredicate(s -> s.matches("\\d*"));
        loopCountBox.setText("1");
        panelList.addRow(new LabeledFieldEntry(Text.literal("Повторы"), loopCountBox, panelW, 140));

        panelList.addRow(new ButtonRowEntry(
                panelW,
                () -> Text.literal(loopInfinite ? "Повтор: ∞ (включен)" : "Повтор: выкл"),
                () -> {
                    loopInfinite = !loopInfinite;
                    loopCountBox.setEditable(!loopInfinite);
                    loopCountBox.setFocused(false);
                },
                null
        ));

        panelList.addRow(new DualButtonRowEntry(panelW,
                Text.literal("Пауза/Продолжить (" + keyName(keyConfig.keyPlayPause) + ")"), this::playPauseToggle, null,
                Text.literal("Стоп (" + keyName(keyConfig.keyStop) + ")"), this::safeStop, () -> SimpleSequencePlayerClient.getInfo().running));

        // Секции/локальные повторы — заготовка (п.5)
        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Секции и повторы — скоро (каркас)"), () -> {
            setStatus("Секции/повторы: каркас готов, реализация — в частях 2–3");
        }));
    }

    private void buildSettingsTab() {
        int contentTop = PAD + TAB_H + PAD;

        int totalW = this.width - 2 * PAD;
        int panelW = Math.min(520, totalW);
        int panelH = Math.max(120, this.height - contentTop - PAD);

        panelList = new ControlPanelList(mc, panelW, panelH, contentTop, contentTop + panelH, ROW);
        panelList.setLeftPos(PAD);
        addDrawableChild(panelList);

        panelList.addRow(new ButtonRowEntry(panelW,
                () -> Text.literal("Play/Pause: " + shownKey(keyConfig.keyPlayPause) + " — нажми для изменения"),
                () -> { waitingKey = WaitingKey.PLAY_PAUSE; setStatus("Назначение Play/Pause — нажми клавишу или Esc для очистки"); },
                null));
        panelList.addRow(new ButtonRowEntry(panelW,
                () -> Text.literal("Stop: " + shownKey(keyConfig.keyStop) + " — нажми для изменения"),
                () -> { waitingKey = WaitingKey.STOP; setStatus("Назначение Stop — нажми клавишу или Esc для очистки"); },
                null));
        panelList.addRow(new ButtonRowEntry(panelW,
                () -> Text.literal("Restart: " + shownKey(keyConfig.keyRestart) + " — нажми для изменения"),
                () -> { waitingKey = WaitingKey.RESTART; setStatus("Назначение Restart — нажми клавишу или Esc для очистки"); },
                null));
        panelList.addRow(new ButtonRowEntry(panelW,
                () -> Text.literal("Add Step: " + shownKey(keyConfig.keyAddStep) + " — нажми для изменения"),
                () -> { waitingKey = WaitingKey.ADD_STEP; setStatus("Назначение Add Step — нажми клавишу или Esc для очистки"); },
                null));
        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Сбросить горячие клавиши (P/S/R/N)"),
                () -> { keyConfig.resetDefaults(); keyConfig.saveTo(keyConfigPath); setStatus("Горячие клавиши сброшены"); }));

        uiStepHBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Высота строки"));
        uiStepHBox.setTextPredicate(s -> s.matches("\\d*"));
        uiStepHBox.setText(String.valueOf(uiConfig.stepItemHeight));
        panelList.addRow(new LabeledFieldEntry(Text.literal("Высота строки шагов"), uiStepHBox, panelW, 170));

        uiLeftPctBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Левая панель, %"));
        uiLeftPctBox.setTextPredicate(s -> s.matches("\\d{0,2}(\\.\\d+)?|\\d{1,3}"));
        uiLeftPctBox.setText(String.valueOf((int)Math.round(uiConfig.leftPanelPercent * 100)));
        panelList.addRow(new LabeledFieldEntry(Text.literal("Левая панель %"), uiLeftPctBox, panelW, 170));

        uiRightPctBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Правая панель, %"));
        uiRightPctBox.setTextPredicate(s -> s.matches("\\d{0,2}(\\.\\d+)?|\\d{1,3}"));
        uiRightPctBox.setText(String.valueOf((int)Math.round(uiConfig.rightPanelPercent * 100)));
        panelList.addRow(new LabeledFieldEntry(Text.literal("Правая панель %"), uiRightPctBox, panelW, 170));

        uiMenuMaxItemsBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Макс. пунктов меню"));
        uiMenuMaxItemsBox.setTextPredicate(s -> s.matches("\\d*"));
        uiMenuMaxItemsBox.setText(String.valueOf(uiConfig.menuMaxVisibleItems));
        panelList.addRow(new LabeledFieldEntry(Text.literal("Контекстное меню"), uiMenuMaxItemsBox, panelW, 170));

        uiPreviewScaleBox = new TextFieldWidget(textRenderer, 0, 0, panelW, ROW_H, Text.literal("Размер превью %"));
        uiPreviewScaleBox.setTextPredicate(s -> s.matches("\\d{0,3}"));
        uiPreviewScaleBox.setText(String.valueOf((int) Math.round(uiConfig.previewBaseScale * 100)));
        panelList.addRow(new LabeledFieldEntry(Text.literal("Размер превью (%)"), uiPreviewScaleBox, panelW, 170));

        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Применить настройки UI"),
                this::applyUiSettings));

        panelList.addRow(new ButtonRowEntry(panelW, Text.literal("Справка по горячим клавишам"),
                this::openHotkeysOverlay));
    }

    // Переименование пресета из текстового поля
    private void renamePresetFromTextBox() {
        if (selectedPresetName == null || !presets.containsKey(selectedPresetName)) return;
        if (presetNameBox == null) return;
        String newName = safeName(presetNameBox.getText() == null ? "" : presetNameBox.getText().trim());
        if (newName.isEmpty()) return;
        newName = genUniquePresetName(newName);
        if (!newName.equals(selectedPresetName)) {
            SequencePreset p = presets.remove(selectedPresetName);
            if (p == null) return;
            p.name = newName;
            presets.put(newName, p);
            selectedPresetName = newName;

            clearHistory();
            PresetStorage.save(presets);
            uiConfig.lastPresetName = newName;
            uiConfig.saveTo(uiConfigPath);

            clearAndInit();
            setStatus("Переименовано в: " + newName);
        }
    }

    private void openHotkeysOverlay() {
        hotkeysLines = hotkeysCheatLines(keyConfig);
        hotkeysScroll = 0;
        hotkeysOverlayVisible = true;
    }

    private List<String> hotkeysCheatLines(KeyConfig kc) {
        List<String> lines = new ArrayList<>();
        lines.add("Навигация по шагам:");
        lines.add("  ↑/↓ — переместить выделение; Shift+↑/↓ — расширить выделение");
        lines.add("  Home/End — к началу/концу; Shift+Home/End — с расширением");
        lines.add("  PageUp/PageDown — прокрутка на страницу");
        lines.add("  Alt+↑/↓ — сдвинуть блок выделенных шагов");
        lines.add("");
        lines.add("Выделение:");
        lines.add("  Пробел — пометить/снять пометку у текущего шага");
        lines.add("  Ctrl+A — пометить всё; Ctrl+D — снять пометки; Ctrl+I — инвертировать");
        lines.add("");
        lines.add("Буфер:");
        lines.add("  Ctrl+C — копировать; Ctrl+V — вставить; Ctrl+Shift+D — дублировать");
        lines.add("  Ctrl+Enter — дублировать выделенные (когда инлайн-редактор не активен)");
        lines.add("");
        lines.add("Инлайн-редактор тиков (двойной клик по шагу):");
        lines.add("  ↑/↓ — ±1; с Ctrl — ±5; с Shift — ±10");
        lines.add("  Enter — применить к шагу; Ctrl+Enter — ко всем выделенным");
        lines.add("  Esc — отмена; клик вне поля — подтвердить");
        lines.add("");
        lines.add("Плеер:");
        lines.add("  Play/Pause — " + shownKey(kc.keyPlayPause));
        lines.add("  Stop — " + shownKey(kc.keyStop));
        lines.add("  Restart — " + shownKey(kc.keyRestart));
        lines.add("");
        lines.add("Прочее:");
        lines.add("  Добавить шаг — " + shownKey(kc.keyAddStep));
        lines.add("  Вкладка «Морфы»: Enter — морфнуть; двойной клик по списку — в пресет");
        lines.add("  Превью морфа: колесо — зум; ПКМ — вращение; двойной клик — сброс");
        return lines;
    }

    private void applyUiSettings() {
        int stepH = parseIntSafe(uiStepHBox.getText(), uiConfig.stepItemHeight);
        int leftPct = parseIntSafe(uiLeftPctBox.getText(), (int)Math.round(uiConfig.leftPanelPercent * 100));
        int rightPct = parseIntSafe(uiRightPctBox.getText(), (int)Math.round(uiConfig.rightPanelPercent * 100));
        int maxItems = parseIntSafe(uiMenuMaxItemsBox.getText(), uiConfig.menuMaxVisibleItems);
        int previewPct = parseIntSafe(uiPreviewScaleBox.getText(), (int) Math.round(uiConfig.previewBaseScale * 100));

        stepH = clamp(stepH, 16, 30);
        leftPct = clamp(leftPct, 10, 40);
        rightPct = clamp(rightPct, 15, 40);
        maxItems = clamp(maxItems, 6, 30);
        previewPct = clamp(previewPct, 50, 200);

        double left = leftPct / 100.0;
        double right = rightPct / 100.0;
        if (left + right > 0.8) {
            double ratio = 0.8 / (left + right);
            left *= ratio; right *= ratio;
        }

        uiConfig.stepItemHeight = stepH;
        uiConfig.leftPanelPercent = left;
        uiConfig.rightPanelPercent = right;
        uiConfig.menuMaxVisibleItems = maxItems;
        uiConfig.previewBaseScale = previewPct / 100.0;
        uiConfig.saveTo(uiConfigPath);

        setStatus("Настройки UI применены");
        clearAndInit();
    }

    // ===== Навигация по списку шагов с клавиатуры =====
    private void moveSelectionBy(int dir, boolean extend) {
        if (stepList == null) return;
        int count = stepList.children().size();
        if (count == 0) return;

        int curr = stepList.getSelectedOrNull() != null ? stepList.getSelectedOrNull().index : 0;
        int next = clamp(curr + dir, 0, count - 1);

        if (extend) {
            int anchor = stepList.getAnchor();
            if (anchor < 0) anchor = curr;
            stepList.selectRange(anchor, next, false);
            stepList.setSelectedIndex(next);
            stepList.setAnchor(anchor);
        } else {
            stepList.setSelectedIndex(next);
            stepList.setAnchor(next);
        }
        stepList.centerRowIfHidden(next);
        rememberSelection();
    }

    private void moveSelectionToEdge(boolean toEnd, boolean extend) {
        if (stepList == null) return;
        int count = stepList.children().size();
        if (count == 0) return;

        int target = toEnd ? (count - 1) : 0;

        if (extend) {
            int curr = stepList.getSelectedOrNull() != null ? stepList.getSelectedOrNull().index : target;
            int anchor = stepList.getAnchor();
            if (anchor < 0) anchor = curr;
            stepList.selectRange(anchor, target, false);
            stepList.setSelectedIndex(target);
            stepList.setAnchor(anchor);
        } else {
            stepList.setSelectedIndex(target);
            stepList.setAnchor(target);
        }
        stepList.centerRowIfHidden(target);
        rememberSelection();
    }

    private void pageScroll(int dir) {
        if (stepList == null) return;
        int view = Math.max(24, stepList.getViewHeight());
        double next = stepList.getScrollAmount() + (dir > 0 ? view : -view);
        stepList.setScrollAmount(Math.max(0, Math.min(next, stepList.getMaxScroll())));
    }

    // ===== Перестройка списка шагов из пресета =====
    private void reloadStepsFromSelected() {
        if (stepList == null) return;
        stepList.clear();
        stepViewToModel.clear();

        if (selectedPresetName == null) return;
        SequencePreset p = presets.get(selectedPresetName);
        if (p == null) return;

        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());

        boolean filtering = (stepFilterLower != null && !stepFilterLower.isBlank());
        if (!filtering) {
            stepModelToView = new int[p.steps.size()];
            for (int i = 0; i < p.steps.size(); i++) {
                var s = p.steps.get(i);
                String comment = (i < com.size()) ? com.get(i) : "";
                stepList.add(i, s.entityId, s.durationTicks, comment); // модель = вид
                stepViewToModel.add(i);
                stepModelToView[i] = i;
            }
        } else {
            java.util.ArrayList<Integer> v2m = new java.util.ArrayList<>();
            for (int i = 0, v = 0; i < p.steps.size(); i++) {
                var s = p.steps.get(i);
                String comment = (i < com.size()) ? com.get(i) : "";
                if (stepMatchesFilterFor(i, s.entityId, comment)) {
                    stepList.addFiltered(v, i, s.entityId, s.durationTicks, comment);
                    v2m.add(i);
                    v++;
                }
            }
            stepViewToModel.addAll(v2m);
            stepModelToView = new int[p.steps.size()];
            java.util.Arrays.fill(stepModelToView, -1);
            for (int v = 0; v < stepViewToModel.size(); v++) {
                int m = stepViewToModel.get(v);
                if (m >= 0 && m < stepModelToView.length) stepModelToView[m] = v;
            }
        }
        stepList.sanitizeMarks();
    }

    // ===== Действия: морф и добавление шагов =====
    private void doMorphSelected() {
        if (selectedEntityType == null || mc.player == null || mc.player.networkHandler == null) return;
        Identifier id = Registries.ENTITY_TYPE.getId(selectedEntityType);
        if (id == null) return;
        mc.player.networkHandler.sendChatCommand("morphc " + id);
    }

    private void addSelectedToPreset() {
        if (selectedEntityType == null) return;
        ensurePresetLoaded();
        if (selectedPresetName == null) {
            selectedPresetName = genUniquePresetName("preset");
            presets.put(selectedPresetName, new SequencePreset(selectedPresetName));
            presetComments.put(selectedPresetName, new ArrayList<>());
        }
        Identifier id = Registries.ENTITY_TYPE.getId(selectedEntityType);
        if (id == null) return;

        pushUndoSnapshot();
        redoStack.clear();

        SequencePreset p = presets.get(selectedPresetName);
        if (p == null) return;
        p.steps.add(new SequencePreset.Step(id.toString(), 60));
        ensureCommentsForPreset(selectedPresetName, p.steps.size());

        PresetStorage.save(presets);
        markSaved();

        currentTab = Tab.PRESETS;
        uiConfig.lastTab = "PRESETS";
        uiConfig.lastPresetName = selectedPresetName;
        uiConfig.lastStepIndex = p.steps.size() - 1;
        uiConfig.saveTo(uiConfigPath);

        clearAndInit();
    }

    private void addStepToCurrent(String id, int ticks) {
        addStepToCurrentWithComment(id, ticks, commentBox != null ? commentBox.getText() : "");
    }

    // Безопасная обёртка: собирает параметры из UI, ловит любые ошибки и показывает их в статусе
    private void addStepToCurrentSafe() {
        try {
            String id = (entityIdBox != null && entityIdBox.getText() != null) ? entityIdBox.getText().trim() : "";
            int ticks = (durationBox != null) ? parseIntSafe(durationBox.getText(), 60) : 60;
            String comment = (commentBox != null) ? commentBox.getText() : "";
            addStepToCurrentWithComment(id, ticks, comment);
        } catch (Throwable t) {
            setStatus("Ошибка при добавлении шага: " + t.getClass().getSimpleName() + (t.getMessage() != null ? (" — " + t.getMessage()) : ""));
            t.printStackTrace();
        }
    }

    private void addStepToCurrentWithComment(String id, int ticks, String comment) {
        try {
            ensurePresetLoaded(); // гарантия, что presets не null

            // Автосоздание пресета при необходимости
            if (selectedPresetName == null || !presets.containsKey(selectedPresetName)) {
                selectedPresetName = genUniquePresetName("preset");
                presets.put(selectedPresetName, new SequencePreset(selectedPresetName));
                presetComments.put(selectedPresetName, new ArrayList<>());
                uiConfig.lastPresetName = selectedPresetName;
                uiConfig.saveTo(uiConfigPath);
            }

            if (id == null) id = "";
            id = id.trim();
            if (id.isEmpty()) { setStatus("ID сущности пуст — шаг не добавлен"); return; }

            SequencePreset p = presets.get(selectedPresetName);
            if (p == null) { setStatus("Пресет не найден: " + selectedPresetName); return; }

            pushUndoSnapshot();
            redoStack.clear();

            p.steps.add(new SequencePreset.Step(id, Math.max(1, ticks)));
            // гарантируем список комментариев нужной длины
            List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
            int at = Math.max(0, p.steps.size() - 1);
            if (at >= com.size()) {
                // на всякий — расширим (хотя ensureCommentsForPreset уже сделал это)
                while (com.size() <= at) com.add("");
            }
            com.set(at, comment == null ? "" : comment);

            PresetStorage.save(presets);
            markSaved();

            reloadStepsFromSelected();

            int newSel = Math.max(0, p.steps.size() - 1);
            if (stepList != null) selectViewByModelIndex(newSel);
            rememberSelection();

            setStatus("Шаг добавлен: " + id + " (" + Math.max(1, ticks) + " тиков)");
        } catch (Throwable t) {
            setStatus("Ошибка добавления шага: " + t.getClass().getSimpleName() + (t.getMessage() != null ? (" — " + t.getMessage()) : ""));
            t.printStackTrace();
        }
    }

    private void replaceEntityIdForSelection() {
        String newId = replaceIdBox.getText().trim();
        if (newId.isEmpty()) { setStatus("Новый ID пуст"); return; }
        if (selectedPresetName == null) return;
        SequencePreset p = presets.get(selectedPresetName);
        if (p == null) return;

        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения"); return; }

        pushUndoSnapshot(); redoStack.clear();

        for (int i : idxs) {
            if (i >= 0 && i < p.steps.size()) {
                p.steps.get(i).entityId = newId;
            }
        }
        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();
        setStatus("Заменён ID у " + idxs.size() + " шагов");
    }

    private void normalizeToMultiple() {
        int mul = parseIntSafe(multipleBox.getText(), 1);
        if (mul <= 1) { setStatus("Кратность N должна быть > 1"); return; }
        if (selectedPresetName == null) return;
        SequencePreset p = presets.get(selectedPresetName);
        if (p == null) return;

        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения"); return; }

        pushUndoSnapshot(); redoStack.clear();

        for (int i : idxs) {
            if (i >= 0 && i < p.steps.size()) {
                int v = Math.max(1, p.steps.get(i).durationTicks);
                int down = (v / mul) * mul;
                int up = ((v + mul / 2) / mul) * mul;
                int chosen = Math.max(1, up);
                if (Math.abs(chosen - v) > Math.abs(down - v)) chosen = Math.max(1, down);
                if (chosen == 0) chosen = mul;
                p.steps.get(i).durationTicks = chosen;
            }
        }
        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();
        setStatus("Нормализовано до кратности " + mul + " для " + idxs.size() + " шагов");
    }

    private void bulkShiftPercent() {
        int perc = parseIntSafe(bulkPercentBox.getText(), 0);
        if (perc == 0) { setStatus("Процент не задан"); return; }
        if (selectedPresetName == null) return;
        SequencePreset p = presets.get(selectedPresetName);
        if (p == null) return;

        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения"); return; }

        pushUndoSnapshot(); redoStack.clear();

        for (int i : idxs) {
            if (i >= 0 && i < p.steps.size()) {
                int old = Math.max(1, p.steps.get(i).durationTicks);
                int delta = (int) Math.round(old * (perc / 100.0));
                int next = Math.max(1, old + delta);
                p.steps.get(i).durationTicks = next;
            }
        }
        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();
        setStatus((perc >= 0 ? "Увеличено на " : "Уменьшено на ") + Math.abs(perc) + "% для " + idxs.size() + " шагов");
    }

    // ===== Экспорт/импорт JSON =====
    private JsonObject buildJsonFor(boolean onlySelection) {
        JsonObject root = new JsonObject();
        SequencePreset p = selectedPreset();
        if (p == null) return root;
        root.addProperty("type", "sequencer_preset");
        root.addProperty("name", p.name);

        JsonArray arr = new JsonArray();
        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        List<Integer> idxs = onlySelection ? getMarkedOrSelectedIndices() : null;

        if (onlySelection && (idxs == null || idxs.isEmpty())) {
            // пустой выбор — ничего не добавляем
        } else {
            if (onlySelection) {
                for (int i : idxs) {
                    if (i >= 0 && i < p.steps.size()) {
                        SequencePreset.Step s = p.steps.get(i);
                        JsonObject o = new JsonObject();
                        o.addProperty("entityId", s.entityId);
                        o.addProperty("ticks", s.durationTicks);
                        String c = (i >= 0 && i < com.size()) ? com.get(i) : "";
                        if (c != null && !c.isEmpty()) o.addProperty("comment", c);
                        arr.add(o);
                    }
                }
            } else {
                for (int i = 0; i < p.steps.size(); i++) {
                    SequencePreset.Step s = p.steps.get(i);
                    JsonObject o = new JsonObject();
                    o.addProperty("entityId", s.entityId);
                    o.addProperty("ticks", s.durationTicks);
                    String c = (i >= 0 && i < com.size()) ? com.get(i) : "";
                    if (c != null && !c.isEmpty()) o.addProperty("comment", c);
                    arr.add(o);
                }
            }
        }
        root.add("steps", arr);
        return root;
    }

    private Path preferredStartDir(String defaultSub) {
        try {
            if (uiConfig.lastFileDir != null && !uiConfig.lastFileDir.isBlank()) {
                Path p = Paths.get(uiConfig.lastFileDir);
                if (Files.isDirectory(p)) return p;
            }
        } catch (Exception ignored) {}
        Path dir = Paths.get(mc.runDirectory.getAbsolutePath(), defaultSub);
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir;
    }

    private void exportToFile(boolean onlySelection) {
        try {
            JsonObject json = buildJsonFor(onlySelection);
            String pretty = gson.toJson(json);

            Path dir = preferredStartDir("sequencer_exports");
            Files.createDirectories(dir);
            String base = safeName(selectedPresetName != null ? selectedPresetName : "preset");
            String suffix = onlySelection ? "_selection" : "_preset";
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
            Path file = dir.resolve(base + suffix + "_" + ts + ".json");
            Files.write(file, pretty.getBytes(StandardCharsets.UTF_8));

            uiConfig.lastFileDir = dir.toString();
            uiConfig.saveTo(uiConfigPath);

            setStatus("Экспортировано в файл: " + file.getFileName());
        } catch (Exception e) {
            setStatus("Ошибка экспорта: " + e.getMessage());
        }
    }

    private void importFromClipboard() {
        try {
            String text = mc.keyboard.getClipboard();
            if (text == null || text.isEmpty()) { setStatus("Буфер пуст"); return; }
            importJsonTextIntoCurrentPreset(text);
        } catch (Exception e) {
            setStatus("Ошибка импорта: " + e.getMessage());
        }
    }

    private void importFromFileDialog() {
        Path startDir = preferredStartDir("sequencer_exports");
        try {
            Files.createDirectories(startDir);

            Path chosen = null;
            try { chosen = tryOpenWithNFD(startDir); } catch (Throwable ignored) {}

            if (chosen == null) {
                try {
                    FileDialog fd = new FileDialog((Frame) null, "Выбор файла пресета JSON", FileDialog.LOAD);
                    fd.setDirectory(startDir.toFile().getAbsolutePath());
                    fd.setFile("*.json");
                    fd.setVisible(true);
                    String file = fd.getFile();
                    String dir = fd.getDirectory();
                    try { fd.dispose(); } catch (Throwable ignored) {}
                    if (file != null && dir != null) chosen = Paths.get(dir, file);
                } catch (HeadlessException he) {
                    showIngameFileMenu(startDir);
                    return;
                } catch (Throwable t) {
                    showIngameFileMenu(startDir);
                    return;
                }
            }

            if (chosen != null) {
                if (!Files.exists(chosen)) { setStatus("Файл не найден: " + chosen.getFileName()); return; }
                String text = Files.readString(chosen, StandardCharsets.UTF_8);
                importJsonTextIntoCurrentPreset(text);
                try {
                    Path parent = chosen.getParent();
                    if (parent != null) {
                        uiConfig.lastFileDir = parent.toString();
                        uiConfig.saveTo(uiConfigPath);
                    }
                } catch (Exception ignored) {}
                setStatus("Импортировано из файла: " + chosen.getFileName());
                return;
            }

            showIngameFileMenu(startDir);
        } catch (Throwable t) {
            setStatus("Ошибка импорта: " + t.getMessage());
        }
    }

    private void showIngameFileMenu(Path dir) {
        try {
            if (contextMenu == null) contextMenu = new ContextMenu();
            if (!Files.exists(dir)) Files.createDirectories(dir);

            List<FileInfo> files = Files.list(dir)
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .map(p -> {
                        try {
                            FileTime ft = Files.getLastModifiedTime(p);
                            long sz = Files.size(p);
                            return new FileInfo(p, ft, sz);
                        } catch (Exception e) {
                            return new FileInfo(p, FileTime.fromMillis(0), 0);
                        }
                    })
                    .sorted((a, b) -> b.modified.compareTo(a.modified))
                    .toList();

            if (files.isEmpty()) { setStatus("Нет .json в папке: " + dir.getFileName()); return; }

            List<MenuItem> items = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
            for (FileInfo f : files) {
                String label = String.format(Locale.ROOT, "%s — %s — %s",
                        f.path.getFileName().toString(), humanSize(f.size), fmt.format(f.modified.toInstant()));
                items.add(new MenuItem(label, true, () -> {
                    try {
                        String text = Files.readString(f.path, StandardCharsets.UTF_8);
                        importJsonTextIntoCurrentPreset(text);
                        Path parent = f.path.getParent();
                        if (parent != null) { uiConfig.lastFileDir = parent.toString(); uiConfig.saveTo(uiConfigPath); }
                        setStatus("Импортировано из файла: " + f.path.getFileName());
                    } catch (Exception e) {
                        setStatus("Ошибка импорта: " + e.getMessage());
                    }
                }));
            }

            int menuX = Math.max(PAD, this.width / 2 - 160);
            int menuY = Math.max(PAD, this.height / 2 - 160);
            contextMenu.show(menuX, menuY, items);
            setStatus("Выберите файл для импорта");
        } catch (Exception e) {
            setStatus("Ошибка показа меню файлов: " + e.getMessage());
        }
    }

    private static String humanSize(long size) {
        if (size < 1024) return size + " B";
        double kb = size / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.1f GB", gb);
    }

    private record FileInfo(Path path, FileTime modified, long size) {}

    private Path tryOpenWithNFD(Path startDir) throws Exception {
        Class<?> nfdCls = Class.forName("org.lwjgl.util.nfd.NativeFileDialog");
        Class<?> memStackCls = Class.forName("org.lwjgl.system.MemoryStack");
        Class<?> ptrBufCls = Class.forName("org.lwjgl.PointerBuffer");

        int NFD_OKAY = nfdCls.getField("NFD_OKAY").getInt(null);
        int NFD_CANCEL = nfdCls.getField("NFD_CANCEL").getInt(null);
        int NFD_ERROR = nfdCls.getField("NFD_ERROR").getInt(null);

        Object stack = memStackCls.getMethod("stackPush").invoke(null);
        try {
            Object outPath = memStackCls.getMethod("mallocPointer", int.class).invoke(stack, 1);

            String defaultPath = (uiConfig.lastFileDir != null && !uiConfig.lastFileDir.isBlank())
                    ? uiConfig.lastFileDir : (startDir != null ? startDir.toString() : null);
            int res = (int) nfdCls.getMethod("NFD_OpenDialog", String.class, String.class, ptrBufCls)
                    .invoke(null, "json", defaultPath, outPath);

            if (res == NFD_OKAY) {
                String selected = (String) ptrBufCls.getMethod("getStringUTF8", int.class).invoke(outPath, 0);
                long addr = (long) ptrBufCls.getMethod("get", int.class).invoke(outPath, 0);
                nfdCls.getMethod("NFD_Free", long.class).invoke(null, addr);
                return Paths.get(selected);
            } else if (res == NFD_CANCEL) {
                return null;
            } else if (res == NFD_ERROR) {
                String err = (String) nfdCls.getMethod("NFD_GetError").invoke(null);
                throw new RuntimeException(err != null ? err : "NFD error");
            }
            return null;
        } finally {
            try { memStackCls.getMethod("close").invoke(stack); } catch (Throwable ignored) {}
        }
    }

    private void importJsonTextIntoCurrentPreset(String text) {
        JsonElement el = JsonParser.parseString(text);
        JsonArray stepsArr = null;

        if (el.isJsonArray()) {
            stepsArr = el.getAsJsonArray();
        } else if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("steps") && obj.get("steps").isJsonArray()) {
                stepsArr = obj.getAsJsonArray("steps");
            } else if (obj.has("type") && "sequencer_preset".equals(obj.get("type").getAsString()) && obj.has("steps")) {
                stepsArr = obj.getAsJsonArray("steps");
            }
        }
        if (stepsArr == null) { setStatus("Неверный JSON: нет массива steps"); return; }

        List<SequencePreset.Step> parsed = new ArrayList<>();
        List<String> parsedComments = new ArrayList<>();
        for (JsonElement it : stepsArr) {
            if (!it.isJsonObject()) continue;
            JsonObject o = it.getAsJsonObject();
            if (!o.has("entityId") || !o.has("ticks")) continue;
            String id = o.get("entityId").getAsString();
            int ticks = Math.max(1, o.get("ticks").getAsInt());
            String comment = o.has("comment") ? o.get("comment").getAsString() : "";
            parsed.add(new SequencePreset.Step(id, ticks));
            parsedComments.add(comment);
        }
        if (parsed.isEmpty()) { setStatus("Импорт: валидных шагов не найдено"); return; }

        SequencePreset p = selectedPreset();
        if (p == null) return;
        pushUndoSnapshot(); redoStack.clear();

        int insertAt;
        var sel = stepList.getSelectedOrNull();
        if (sel != null) insertAt = Math.min(p.steps.size(), sel.index + 1);
        else insertAt = p.steps.size();

        for (int i = 0; i < parsed.size(); i++) {
            SequencePreset.Step s = parsed.get(i);
            p.steps.add(insertAt + i, new SequencePreset.Step(s.entityId, s.durationTicks));
        }
        List<String> com = ensureCommentsForPreset(selectedPresetName, p.steps.size());
        for (int i = 0; i < parsedComments.size(); i++) {
            int at = Math.min(insertAt + i, com.size() - 1);
            if (at >= 0) com.set(at, parsedComments.get(i));
        }

        PresetStorage.save(presets);
        markSaved();
        reloadStepsFromSelected();
        if (!p.steps.isEmpty()) {
            int newSel = Math.min(insertAt + parsed.size() - 1, p.steps.size() - 1);
            selectViewByModelIndex(newSel);
            rememberSelection();
        }
        setStatus("Импортировано шагов: " + parsed.size());
    }

    // Экспорт v2 (подготовка к п.7) — заготовка
    private JsonObject buildJsonV2(boolean onlySelection) {
        JsonObject root = buildJsonFor(onlySelection); // пока реиспользуем v1
        root.addProperty("format", "v2");
        // TODO: позже добавим метаданные и секции
        return root;
    }

    // Импорт v2 (подготовка к п.7) — заготовка
    private boolean tryImportV2(JsonObject obj) {
        // TODO: парсинг v2 (метаданные/секции) — часть 3
        return false;
    }

    // ===== Плеер =====
    private void playFromSelectedOrStart() {
        SequencePreset base = selectedPreset();
        if (base == null) { setStatus("Нет выбранного пресета"); return; }
        if (base.steps.isEmpty()) { setStatus("В пресете нет шагов"); return; }
        int startIdx = 0;
        var sel = stepList.getSelectedOrNull();
        if (sel != null) startIdx = Math.max(0, sel.index);
        SequencePreset sub = subsetFrom(base, startIdx, base.steps.size() - 1);
        int delay = (startDelayBox != null) ? parseIntSafe(startDelayBox.getText(), 0) : 0;
        int loops = (loopCountBox != null) ? parseIntSafe(loopCountBox.getText(), 1) : 1;
        try {
            SimpleSequencePlayerClient.play(sub, Math.max(0, delay), loopInfinite, Math.max(1, loops));
            setStatus("▶ Играть с шага " + (startIdx + 1));
        } catch (Throwable t) {
            setStatus("Ошибка плеера: " + t.getMessage());
        }
    }

    private void playSelection() {
        SequencePreset base = selectedPreset();
        if (base == null) { setStatus("Нет выбранного пресета"); return; }
        List<Integer> idxs = getMarkedOrSelectedIndices();
        if (idxs.isEmpty()) { setStatus("Нет выделения"); return; }
        SequencePreset sub = new SequencePreset(base.name + "_selection");
        for (int i : idxs) if (i >= 0 && i < base.steps.size()) {
            var s = base.steps.get(i);
            sub.steps.add(new SequencePreset.Step(s.entityId, s.durationTicks));
        }
        int delay = (startDelayBox != null) ? parseIntSafe(startDelayBox.getText(), 0) : 0;
        int loops = (loopCountBox != null) ? parseIntSafe(loopCountBox.getText(), 1) : 1;
        try {
            SimpleSequencePlayerClient.play(sub, Math.max(0, delay), loopInfinite, Math.max(1, loops));
            setStatus("▶ Играть выделение (" + sub.steps.size() + ")");
        } catch (Throwable t) {
            setStatus("Ошибка плеера: " + t.getMessage());
        }
    }

    private void playPauseToggle() {
        try {
            var info = SimpleSequencePlayerClient.getInfo();
            if (info.running) {
                if (info.paused) { SimpleSequencePlayerClient.resume(); setStatus("⏵ Продолжить"); }
                else { SimpleSequencePlayerClient.pause(); setStatus("⏸ Пауза"); }
            } else {
                playFromSelectedOrStart();
            }
        } catch (Throwable t) {
            setStatus("Ошибка плеера: " + t.getMessage());
        }
    }

    private void restartPlay() {
        SequencePreset base = selectedPreset();
        if (base == null) { setStatus("Нет выбранного пресета"); return; }
        if (base.steps.isEmpty()) { setStatus("В пресете нет шагов"); return; }
        int delay = (startDelayBox != null) ? parseIntSafe(startDelayBox.getText(), 0) : 0;
        int loops = (loopCountBox != null) ? parseIntSafe(loopCountBox.getText(), 1) : 1;
        try {
            SimpleSequencePlayerClient.play(base, Math.max(0, delay), loopInfinite, Math.max(1, loops));
            setStatus("⏮ Рестарт");
        } catch (Throwable t) {
            setStatus("Ошибка плеера: " + t.getMessage());
        }
    }

    private void safeStop() {
        try { SimpleSequencePlayerClient.stop(); setStatus("■ Стоп"); } catch (Throwable t) { setStatus("Ошибка плеера: " + t.getMessage()); }
    }

    private SequencePreset subsetFrom(SequencePreset src, int from, int to) {
        SequencePreset dst = new SequencePreset(src.name + "_from_" + (from + 1));
        int a = Math.max(0, Math.min(from, src.steps.size()));
        int b = Math.max(a, Math.min(to, src.steps.size() - 1));
        for (int i = a; i <= b; i++) {
            var s = src.steps.get(i);
            dst.steps.add(new SequencePreset.Step(s.entityId, s.durationTicks));
        }
        return dst;
    }

    private SequencePreset selectedPreset() {
        if (selectedPresetName == null) return null;
        return presets.get(selectedPresetName);
    }

    // ===== Undo/Redo =====
    private void pushUndoSnapshot() {
        StepsSnapshot snap = makeSnapshot();
        if (snap == null) return;
        undoStack.push(snap);
        while (undoStack.size() > HISTORY_LIMIT) undoStack.removeLast();
    }

    private void undo() {
        if (!hasUndo()) return;
        StepsSnapshot prev = undoStack.pop();
        StepsSnapshot curr = makeSnapshot();
        if (curr != null) redoStack.push(curr);
        applySnapshot(prev);
    }

    private void redo() {
        if (!hasRedo()) return;
        StepsSnapshot next = redoStack.pop();
        StepsSnapshot curr = makeSnapshot();
        if (curr != null) undoStack.push(curr);
        applySnapshot(next);
    }

    private boolean hasUndo() { return !undoStack.isEmpty(); }
    private boolean hasRedo() { return !redoStack.isEmpty(); }
    private void clearHistory() { undoStack.clear(); redoStack.clear(); }

    private StepsSnapshot makeSnapshot() {
        if (selectedPresetName == null) return null;
        SequencePreset p = presets.get(selectedPresetName);
        if (p == null) return null;
        StepsSnapshot s = new StepsSnapshot();
        s.presetName = selectedPresetName;
        s.steps = new ArrayList<>();
        for (SequencePreset.Step st : p.steps) s.steps.add(new SequencePreset.Step(st.entityId, st.durationTicks));
        s.comments = new ArrayList<>(ensureCommentsForPreset(selectedPresetName, p.steps.size()));
        s.selectedIndex = (stepList != null && stepList.getSelectedOrNull() != null) ? stepList.getSelectedOrNull().index : -1;
        s.marks = (stepList != null) ? stepList.copyMarks() : new BitSet();
        s.anchorIndex = (stepList != null) ? stepList.getAnchor() : -1;
        return s;
    }

    private void applySnapshot(StepsSnapshot s) {
        if (s == null) return;
        ensurePresetLoaded();
        selectedPresetName = s.presetName;

        SequencePreset p = presets.get(selectedPresetName);
        if (p == null) {
            p = new SequencePreset(selectedPresetName);
            presets.put(selectedPresetName, p);
        }
        p.steps.clear();
        for (SequencePreset.Step st : s.steps) p.steps.add(new SequencePreset.Step(st.entityId, st.durationTicks));
        PresetStorage.save(presets);

        presetComments.put(selectedPresetName, new ArrayList<>(s.comments));

        if (presetNameBox != null) presetNameBox.setText(selectedPresetName != null ? selectedPresetName : "");
        if (presetList != null) presetList.rebuildFrom(presets, selectedPresetName);

        reloadStepsFromSelected();

        if (stepList != null) {
            stepList.setMarks(s.marks);
            selectViewByModelIndex(s.selectedIndex);
            rememberSelection();
        }
        markSaved();
    }

    private static class StepsSnapshot {
        String presetName;
        List<SequencePreset.Step> steps;
        List<String> comments;
        int selectedIndex;
        BitSet marks;
        int anchorIndex;
    }

    // ===== Вспомогательные методы (ранее отсутствовали) =====
    private void ensurePresetLoaded() {
        if (presets == null) {
            presets = PresetStorage.load();
            if (selectedPresetName == null && presets != null && !presets.isEmpty()) {
                selectedPresetName = presets.keySet().iterator().next();
            }
            if (presets != null) {
                for (String name : presets.keySet()) presetComments.putIfAbsent(name, new ArrayList<>());
            } else {
                presets = new HashMap<>();
            }
        }
    }

    private static Tab parseTabOrDefault(String s, Tab def) {
        if (s == null) return def;
        try { return Tab.valueOf(s); } catch (Exception e) { return def; }
    }

    private static String waitingKeyLabel(WaitingKey wk) {
        return switch (wk) {
            case PLAY_PAUSE -> "Play/Pause";
            case STOP -> "Stop";
            case RESTART -> "Restart";
            case ADD_STEP -> "Add Step";
            default -> "";
        };
    }

    private static String keyName(int keyCode) {
        if (keyCode == 0) return "—";
        if (keyCode >= 65 && keyCode <= 90) return String.valueOf((char) keyCode);
        if (keyCode >= 48 && keyCode <= 57) return String.valueOf((char) keyCode);
        return switch (keyCode) {
            case 32 -> "Space";
            case 256 -> "Esc";
            case 262 -> "Right";
            case 263 -> "Left";
            case 264 -> "Down";
            case 265 -> "Up";
            case 266 -> "PageUp";
            case 267 -> "PageDown";
            case 268 -> "Home";
            case 269 -> "End";
            case 290 -> "F1";
            case 291 -> "F2";
            case 292 -> "F3";
            case 293 -> "F4";
            case 294 -> "F5";
            case 295 -> "F6";
            case 296 -> "F7";
            case 297 -> "F8";
            case 298 -> "F9";
            case 299 -> "F10";
            case 300 -> "F11";
            case 301 -> "F12";
            default -> "#" + keyCode;
        };
    }
    private static String shownKey(int key) { return key == 0 ? "—" : keyName(key); }
    private static boolean matchesKey(int configKey, int pressed) { return configKey != 0 && configKey == pressed; }

    private void rememberSelection() {
        if (selectedPresetName != null) uiConfig.lastPresetName = selectedPresetName;
        if (stepList != null && stepList.getSelectedOrNull() != null) {
            uiConfig.lastStepIndex = modelIndexOf(stepList.getSelectedOrNull());
        }
        uiConfig.saveTo(uiConfigPath);
    }

    private static int parseIntSafe(String s, int def) { try { return Integer.parseInt(s == null ? "" : s.trim()); } catch (Exception e) { return def; } }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static float clampf(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    private LivingEntity getOrCreatePreview() {
        if (selectedEntityType == null || mc.world == null) return null;

        Identifier id = Registries.ENTITY_TYPE.getId(selectedEntityType);
        if (id != null && badPreviewIds.contains(id.toString())) return null;

        if (previewEntity != null && previewEntity.getType() == selectedEntityType) return previewEntity;

        if (previewEntity != null) {
            try { previewEntity.discard(); } catch (Throwable ignored) {}
            previewEntity = null;
        }

        try {
            Entity e = selectedEntityType.create(mc.world);
            if (e instanceof LivingEntity le) {
                le.setNoGravity(true);
                previewEntity = le;
            } else {
                previewEntity = null; // не-LivingEntity — большое превью не рисуем
            }
        } catch (Throwable t) {
            if (id != null) badPreviewIds.add(id.toString());
            previewEntity = null;
        }
        return previewEntity;
    }

    private LivingEntity getOrCreateIconEntity(EntityType<?> type) {
        if (type == null || mc.world == null) return null;
        Identifier id = Registries.ENTITY_TYPE.getId(type);
        if (id == null) return null;
        String key = id.toString();
        if (badIconIds.contains(key)) return null;

        LivingEntity cached = morphIconCache.get(key);
        if (cached != null) return cached;

        try {
            Entity newE = type.create(mc.world);
            if (newE instanceof LivingEntity le) {
                le.setNoGravity(true);
                morphIconCache.put(key, le);
                return le;
            }
        } catch (Throwable t) {
            // больше не пытаемся создавать иконку для этого id в текущей сессии
            badIconIds.add(key);
        }
        return null;
    }

    private void buildConfirmDialogGeometry() {
        confirmDlgW = 320;
        confirmDlgH = 140;
        confirmDlgX = (this.width - confirmDlgW) / 2;
        confirmDlgY = (this.height - confirmDlgH) / 2;
        confirmYesX = confirmDlgX + 40;
        confirmNoX = confirmDlgX + confirmDlgW - 40 - confirmBtnW;
        confirmYesY = confirmDlgY + confirmDlgH - 40;
        confirmNoY = confirmYesY;
    }

    private void renderConfirmDialog(DrawContext ctx) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        ctx.fill(confirmDlgX, confirmDlgY, confirmDlgX + confirmDlgW, confirmDlgY + confirmDlgH, 0xFF202020);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Удалить " + confirmDeleteCount + " шаг(а/ов)?"),
                confirmDlgX + 20, confirmDlgY + 20, 0xFFFFFF);
        ctx.fill(confirmYesX, confirmYesY, confirmYesX + confirmBtnW, confirmYesY + confirmBtnH, 0xFF2E7D32);
        ctx.fill(confirmNoX, confirmNoY, confirmNoX + confirmBtnW, confirmNoY + confirmBtnH, 0xFFB71C1C);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Да"), confirmYesX + 40, confirmYesY + 6, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Нет"), confirmNoX + 38, confirmNoY + 6, 0xFFFFFFFF);
        ctx.getMatrices().pop();
    }

    private void renderHotkeysOverlay(DrawContext ctx) {
        if (hotkeysLines == null || hotkeysLines.isEmpty()) return;
        int boxW = Math.min(640, this.width - PAD * 2);
        int boxH = Math.min(360, this.height - PAD * 2);
        int x = (this.width - boxW) / 2;
        int y = (this.height - boxH) / 2;

        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 500);

        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        ctx.fill(x, y, x + boxW, y + boxH, 0xFF202020);
        ctx.fill(x, y, x + boxW, y + 1, 0xFF000000);
        ctx.fill(x, y + boxH - 1, x + boxW, y + boxH, 0xFF000000);
        ctx.fill(x, y, x + 1, y + boxH, 0xFF000000);
        ctx.fill(x + boxW - 1, y, x + boxW, y + boxH, 0xFF000000);

        String title = "Горячие клавиши";
        int tw = textRenderer.getWidth(title);
        ctx.drawTextWithShadow(textRenderer, Text.literal(title), x + (boxW - tw) / 2, y + 6, 0xFFFFFF);

        int pad = 8;
        int textX = x + pad;
        int textY = y + 24;
        int lineH = 12;
        int viewLines = (boxH - 24 - pad) / lineH;
        int maxScroll = Math.max(0, hotkeysLines.size() - viewLines);
        if (hotkeysScroll > maxScroll) hotkeysScroll = maxScroll;

        for (int i = 0; i < viewLines; i++) {
            int idx = i + hotkeysScroll;
            if (idx >= 0 && idx < hotkeysLines.size()) {
                ctx.drawText(textRenderer, Text.literal(hotkeysLines.get(idx)), textX, textY + i * lineH, 0xDDDDDD, false);
            }
        }

        ctx.getMatrices().pop();
    }

    private int ticksOfIndices(List<Integer> idxs) {
        SequencePreset p = selectedPreset();
        if (p == null) return 0;
        int sum = 0;
        for (int i : idxs) if (i >= 0 && i < p.steps.size()) sum += p.steps.get(i).durationTicks;
        return sum;
    }

    private int ticksOfAll() {
        SequencePreset p = selectedPreset();
        if (p == null) return 0;
        int sum = 0;
        for (SequencePreset.Step s : p.steps) sum += s.durationTicks;
        return sum;
    }

    private String genUniquePresetName(String base) {
        ensurePresetLoaded();
        String b = safeName(base == null || base.isEmpty() ? "preset" : base);
        String name = b;
        int i = 1;
        while (presets.containsKey(name)) name = b + "_" + i++;
        return name;
    }

    private static String safeName(String s) { return s.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_"); }

    private String currentPresetLabel() {
        return selectedPresetName != null ? (isDirty() ? selectedPresetName + " *" : selectedPresetName) : "(нет)";
    }

    private void cyclePreset() {
        ensurePresetLoaded();
        List<String> names = new ArrayList<>(presets.keySet());
        if (names.isEmpty()) {
            selectedPresetName = genUniquePresetName("preset");
            presets.put(selectedPresetName, new SequencePreset(selectedPresetName));
            presetComments.put(selectedPresetName, new ArrayList<>());
            PresetStorage.save(presets);
            markSaved();
            return;
        }
        names.sort(String::compareToIgnoreCase);
        if (selectedPresetName == null) {
            selectedPresetName = names.get(0);
            return;
        }
        int idx = names.indexOf(selectedPresetName);
        idx = (idx + 1) % names.size();
        selectedPresetName = names.get(idx);

        uiConfig.lastPresetName = selectedPresetName;
        uiConfig.saveTo(uiConfigPath);
    }

    // ===== Контекстное меню =====
    private class ContextMenu {
        private boolean visible = false;
        private int x, y, w = 220;

        private final int itemH = 18;
        private final int padding = 3;

        private final List<MenuItem> items = new ArrayList<>();

        private int scrollIndex = 0;
        private int maxVisibleItems = 16;
        private int activeIndex = 0;

        void show(int x, int y, List<MenuItem> items) {
            this.items.clear();
            this.items.addAll(items);

            int maxW = 0;
            for (MenuItem it : items) {
                if ("—".equals(it.label)) continue;
                maxW = Math.max(maxW, textRenderer.getWidth(it.label));
            }
            this.w = Math.max(180, maxW + 12);

            int maxH = Math.min(260, height - PAD * 2);
            this.maxVisibleItems = clamp(uiConfig.menuMaxVisibleItems, 6, Math.max(6, (maxH - padding * 2) / itemH));
            this.scrollIndex = 0;

            int viewH = maxVisibleItems * itemH + padding * 2;
            int mx = x, my = y;
            if (mx + w > width - PAD) mx = width - PAD - w;
            if (my + viewH > height - PAD) my = Math.max(PAD, height - PAD - viewH);

            this.x = Math.max(PAD, mx);
            this.y = Math.max(PAD, my);
            this.visible = true;
            this.activeIndex = 0;
        }

        void hide() { visible = false; items.clear(); }
        void release() { /* no-op */ }

        boolean contains(double mx, double my) {
            if (!visible) return false;
            int h = maxVisibleItems * itemH + padding * 2;
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        void render(DrawContext ctx) {
            if (!visible) return;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 400);

            int viewH = maxVisibleItems * itemH + padding * 2;
            ctx.fill(x, y, x + w, y + viewH, 0xEE1E1E1E);
            ctx.fill(x, y, x + w, y + 1, 0xFF000000);
            ctx.fill(x, y + viewH - 1, x + w, y + viewH, 0xFF000000);
            ctx.fill(x, y, x + 1, y + viewH, 0xFF000000);
            ctx.fill(x + w - 1, y, x + w, y + viewH, 0xFF000000);

            // гарантируем допустимые значения
            if (activeIndex < 0) activeIndex = 0;
            if (activeIndex >= items.size()) activeIndex = Math.max(0, items.size() - 1);

            int start = Math.max(0, Math.min(scrollIndex, Math.max(0, items.size() - maxVisibleItems)));
            int end = Math.min(items.size(), start + maxVisibleItems);
            int iy = y + padding;
            for (int i = start; i < end; i++) {
                MenuItem it = items.get(i);
                if ("—".equals(it.label)) {
                    ctx.fill(x + 4, iy + itemH / 2, x + w - 10, iy + itemH / 2 + 1, 0xFF444444);
                } else {
                    int col = it.enabled ? 0xFFFFFF : 0x777777;
                    ctx.drawTextWithShadow(textRenderer, Text.literal(it.label), x + 6, iy + 4, col);
                    if (i == activeIndex) {
                        ctx.fill(x + 2, iy - 1, x + w - 2, iy + itemH - 1, 0x2233AAFF);
                    }
                }
                iy += itemH;
            }

            ctx.getMatrices().pop();
        }

        void moveActive(int delta) {
            if (items.isEmpty()) return;
            int max = items.size() - 1;
            activeIndex = Math.max(0, Math.min(max, activeIndex + delta));
            // автопрокрутка
            int maxVisible = maxVisibleItems;
            int start = Math.max(0, Math.min(scrollIndex, Math.max(0, items.size() - maxVisible)));
            int end = Math.min(items.size(), start + maxVisible);
            if (activeIndex < start) scrollIndex = activeIndex;
            else if (activeIndex >= end) scrollIndex = activeIndex - maxVisible + 1;
        }

        void activateActive() {
            if (activeIndex >= 0 && activeIndex < items.size()) {
                MenuItem it = items.get(activeIndex);
                if (!"—".equals(it.label) && it.enabled && it.action != null) it.action.run();
            }
            hide();
        }

        boolean mouseClick(double mx, double my) {
            if (!visible) return false;
            int viewH = maxVisibleItems * itemH + padding * 2;
            if (mx < x || mx > x + w || my < y || my > y + viewH) { hide(); return false; }

            int start = Math.max(0, Math.min(scrollIndex, Math.max(0, items.size() - maxVisibleItems)));
            int relY = (int) (my - (y + padding));
            int idxInView = relY / itemH;
            int idx = start + idxInView;
            if (idx >= 0 && idx < items.size()) {
                MenuItem it = items.get(idx);
                if (!"—".equals(it.label) && it.enabled && it.action != null) it.action.run();
                hide();
                return true;
            }
            hide();
            return false;
        }

        void scrollBy(double itemCountDelta) {
            int maxScroll = Math.max(0, items.size() - maxVisibleItems);
            int newScroll = (int) Math.round(scrollIndex + itemCountDelta);
            scrollIndex = Math.max(0, Math.min(maxScroll, newScroll));
        }

        boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            return false;
        }
    }

    private static class MenuItem {
        final String label;
        final boolean enabled;
        final Runnable action;

        MenuItem(String label, boolean enabled, Runnable action) {
            this.label = label; this.enabled = enabled; this.action = action;
        }
    }

    // ===== Списки (Morph, Preset, Step) =====

    private class MorphList extends AlwaysSelectedEntryListWidget<MorphEntry> {
        private String filter = "";
        private boolean needsRebuild = true;

        MorphList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
            // первая фактическая сборка — в tickRebuild()
        }

        void setFilter(String f) {
            String next = (f == null) ? "" : f.trim().toLowerCase(Locale.ROOT);
            if (!Objects.equals(next, this.filter)) {
                this.filter = next;
                this.needsRebuild = true;
            }
        }

        void tickRebuild() {
            if (needsRebuild) {
                rebuildEntries();
                needsRebuild = false;
            }
        }

        void rebuildEntries() {
            clearEntries();
            List<EntityType<?>> all = Registries.ENTITY_TYPE.stream().collect(Collectors.toList());
            // безопасная сортировка по id
            all.sort(Comparator.comparing(et -> {
                Identifier id = Registries.ENTITY_TYPE.getId(et);
                return (id == null) ? "~" : id.toString();
            }));
            int added = 0;
            for (EntityType<?> t : all) {
                Identifier id = Registries.ENTITY_TYPE.getId(t);
                if (id == null) continue;
                String s = id.toString();
                if (!filter.isEmpty() && !s.toLowerCase(Locale.ROOT).contains(filter)) continue;
                this.addEntry(new MorphEntry(this, t, s));
                added++;
            }
            morphCountShown = added;
        }

        @Override public int getRowWidth() { return width; }
        @Override protected int getScrollbarPositionX() { return getRowLeft() + width - 6; }
    }

    private class MorphEntry extends AlwaysSelectedEntryListWidget.Entry<MorphEntry> {
        final MorphList parent;
        final EntityType<?> type;
        final String display;

        MorphEntry(MorphList parent, EntityType<?> type, String display) {
            this.parent = parent; this.type = type; this.display = display;
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {

            boolean sel = parent.getSelectedOrNull() == this;
            int color = (hovered || sel) ? 0xFFFFFF : 0xDDDDDD;

            int iconBox = entryHeight - 4;
            int iconW = Math.min(20, iconBox);
            int iconCenterX = x + 2 + iconW / 2;
            int iconBottomY = y + entryHeight - 2;

            // 1) Пытаемся отрисовать ЛЮБУЮ сущность
            Entity e = null;
            try { e = getOrCreateIconEntityAny(type); } catch (Throwable ignored) {}
            if (e != null) {
                try {
                    if (e instanceof net.minecraft.entity.mob.EvokerFangsEntity f) {
                        advanceEvokerFangsForPreview(f);
                    } else if (e instanceof net.minecraft.entity.AreaEffectCloudEntity c) {
                        advanceCloudForPreview(c);
                    }
                } catch (Throwable ignored) {}
                try {
                    float h = Math.max(0.6f, (e instanceof LivingEntity le) ? le.getHeight() : 1.0f);
                    float w = Math.max(0.6f, (e instanceof LivingEntity le) ? le.getWidth() : 0.6f);
                    int base = Math.min(iconW, iconBox);
                    float autoScale = (float) Math.max(10, Math.min(24, (int) (base / (Math.max(h, w) + 0.5f))));
                    int scale = (int) (autoScale * 0.9f);
                    float rotDX = -15f, rotDY = 0f;

                    ctx.getMatrices().push();
                    ctx.getMatrices().translate(0, 0, 200);
                    if (e instanceof LivingEntity le) {
                        InventoryScreen.drawEntity(ctx, iconCenterX, iconBottomY, scale, rotDX, rotDY, le);
                    } else {
                        drawEntityAny(ctx, iconCenterX, iconBottomY, scale, rotDX, rotDY, e);
                    }
                    ctx.getMatrices().pop();
                } catch (Throwable ignored) { }
            } else {
                // 2) Фолбэк: предмет (spawn egg/маппинг), если entity создать не удалось
                Identifier eid = Registries.ENTITY_TYPE.getId(type);
                Item fb = null;
                if (eid != null) {
                    Item egg = SpawnEggItem.forEntity(type);
                    if (egg != null) fb = egg;
                    if (fb == null && entityPreviewItems != null) fb = entityPreviewItems.get(eid.toString());
                }
                if (fb != null) {
                    ctx.getMatrices().push();
                    ctx.getMatrices().translate(0, 0, 200);
                    ItemStack stack = new ItemStack(fb);
                    float scale = Math.max(0.8f, Math.min(1.6f, (iconBox) / 16f));
                    ctx.getMatrices().scale(scale, scale, scale);
                    int ix = (int) ((iconCenterX - 8) / scale);
                    int iy = (int) ((iconBottomY - 16) / scale);
                    ctx.drawItem(stack, ix, iy);
                    ctx.drawItemInSlot(textRenderer, stack, ix, iy);
                    ctx.getMatrices().pop();
                }
            }

            // Текст
            int textX = x + 4 + iconW + 6;
            ctx.drawTextWithShadow(textRenderer, display, textX, y + 6, color);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            parent.setSelected(this);
            selectedEntityType = type;
            if (previewEntity != null) { previewEntity.discard(); previewEntity = null; }

            // Восстановление поз/зума из кэша (подготовка к п.9)
            Identifier id = Registries.ENTITY_TYPE.getId(selectedEntityType);
            if (id != null) {
                PreviewState ps = previewCache.get(id.toString());
                if (ps != null) { morphRotX = ps.rotX; morphRotY = ps.rotY; morphZoomUser = ps.zoom; }
                else { morphRotX = 0f; morphRotY = 0f; morphZoomUser = 1f; }
            }

            if (button == 0) {
                long now = System.currentTimeMillis();
                if (now - morphLastClickMs < 300) {
                    if (SequencerMainScreen.this.morphPickMode) {
                        SequencerMainScreen.this.applyPickedMorphAndReturn();
                    } else {
                        addSelectedToPreset();
                    }
                    morphLastClickMs = 0;
                    return true;
                }
                morphLastClickMs = now;
            }
            return true;
        }

        public Text getNarration() { return Text.literal(display); }
    }

    private class PresetList extends AlwaysSelectedEntryListWidget<PresetEntry> {
        PresetList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
        }

        void rebuildFrom(Map<String, SequencePreset> map, String selected) {
            clearEntries();
            List<String> names = new ArrayList<>(map.keySet());
            names.sort(String::compareToIgnoreCase);
            int idxSelected = -1;
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i);
                PresetEntry e = new PresetEntry(this, n);
                this.addEntry(e);
                if (n.equals(selected)) idxSelected = i;
            }
            if (idxSelected >= 0 && idxSelected < children().size()) setSelected(children().get(idxSelected));
        }

        @Override public int getRowWidth() { return width; }
        @Override protected int getScrollbarPositionX() { return getRowLeft() + width - 6; }
    }

    private class PresetEntry extends AlwaysSelectedEntryListWidget.Entry<PresetEntry> {
        final PresetList parent;
        final String name;

        PresetEntry(PresetList parent, String name) {
            this.parent = parent; this.name = name;
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            boolean sel = parent.getSelectedOrNull() == this;
            int color = sel ? 0xFFFFFF : 0xDDDDDD;
            String text = sel ? ("[" + name + "]") : name;
            ctx.drawTextWithShadow(textRenderer, text, x + 4, y + 6, color);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            parent.setSelected(this);
            selectedPresetName = name;
            if (presetNameBox != null) presetNameBox.setText(name);
            if (stepList != null) {
                stepList.clearMarks();
                stepList.setAnchor(-1);
            }
            clearHistory();
            PresetStorage.save(presets);
            markSaved();
            reloadStepsFromSelected();

            uiConfig.lastPresetName = name;
            uiConfig.lastStepIndex = -1;
            uiConfig.saveTo(uiConfigPath);
            return true;
        }

        public Text getNarration() { return Text.literal(name); }
    }

    private class StepList extends AlwaysSelectedEntryListWidget<StepEntry> {
        private BitSet marks = new BitSet();
        private int anchorIndex = -1;

        private final int viewTop;
        private final int viewBottom;
        private final int itemH;

        // Drag-and-drop
        private boolean dragArmed = false;
        private boolean dragging = false;
        private int armedIndex = -1;
        private int lastDropIndex = -1;

        StepList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
            this.viewTop = top;
            this.viewBottom = bottom;
            this.itemH = itemHeight;
        }

        void add(int index, String entityId, int ticks, String comment) { this.addEntry(new StepEntry(this, index, entityId, ticks, comment)); }
        void add(int index, String entityId, int ticks) { this.add(index, entityId, ticks, ""); }

        void addFiltered(int viewIndex, int modelIndex, String entityId, int ticks, String comment) {
            this.addEntry(new StepEntry(this, viewIndex, modelIndex, entityId, ticks, comment));
        }

        void clear() {
            super.clearEntries();
            marks.clear();
            anchorIndex = -1;
            dragging = false; dragArmed = false; armedIndex = -1; lastDropIndex = -1;
        }
        void setSelectedIndex(int idx) {
            List<StepEntry> kids = children();
            if (idx >= 0 && idx < kids.size()) setSelected(kids.get(idx));
        }

        void setAnchor(int idx) { this.anchorIndex = idx; }
        int getAnchor() { return anchorIndex; }

        void selectRange(int a, int b, boolean additive) {
            if (a < 0 && b < 0) return;
            int n = children().size();
            if (n == 0) return;
            if (a < 0) a = b;
            if (b < 0) b = a;
            a = Math.max(0, Math.min(a, n - 1));
            b = Math.max(0, Math.min(b, n - 1));
            int from = Math.min(a, b);
            int to = Math.max(a, b);
            if (!additive) marks.clear();
            marks.set(from, to + 1, true);
        }

        boolean isMarked(int index) { return marks.get(index); }
        void toggleMarked(int index) { if (index >= 0) marks.flip(index); }
        void markAll() { marks.set(0, children().size(), true); }
        void clearMarks() { marks.clear(); }
        void invertMarks() { int n = children().size(); if (n > 0) marks.flip(0, n); }
        List<Integer> getMarkedIndices() {
            List<Integer> out = new ArrayList<>();
            for (int i = marks.nextSetBit(0); i >= 0; i = marks.nextSetBit(i + 1)) out.add(i);
            return out;
        }
        void sanitizeMarks() {
            int n = children().size();
            if (marks.length() > n) {
                BitSet bs = new BitSet();
                for (int i = marks.nextSetBit(0); i >= 0 && i < n; i = marks.nextSetBit(i + 1)) bs.set(i);
                marks = bs;
            }
            if (anchorIndex < 0 || anchorIndex >= n) anchorIndex = -1;
        }

        BitSet copyMarks() { return (BitSet) marks.clone(); }
        void setMarks(BitSet bs) { marks = (BitSet) bs.clone(); sanitizeMarks(); }

        void centerRowIfHidden(int index) {
            if (index < 0 || index >= children().size()) return;
            int rowTop = viewTop + index * itemH - (int) getScrollAmount();
            int rowBottom = rowTop + itemH;
            int visTop = viewTop;
            int visBottom = viewBottom;
            if (rowTop < visTop || rowBottom > visBottom) {
                int targetScroll = index * itemH - (visBottom - visTop - itemH) / 2;
                setScrollAmount(clamp(targetScroll, 0, getMaxScroll()));
            }
        }

        int getViewHeight() { return Math.max(0, viewBottom - viewTop); }
        int getViewTop() { return viewTop; }
        int getRowLeftPublic() { return getRowLeft(); }

        void armDrag(int atIndex) { dragArmed = true; armedIndex = atIndex; dragging = false; lastDropIndex = -1; }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
            if (button != 0) return super.mouseDragged(mouseX, mouseY, button, dx, dy);
            if (!dragArmed && !dragging) return super.mouseDragged(mouseX, mouseY, button, dx, dy);

            if (!dragging) {
                if (Math.abs(dx) + Math.abs(dy) < 1.0) return super.mouseDragged(mouseX, mouseY, button, dx, dy);
                dragging = true;
            }

            int margin = 14;
            if (mouseY > viewBottom - margin) setScrollAmount(Math.min(getMaxScroll(), getScrollAmount() + 10));
            if (mouseY < viewTop + margin) setScrollAmount(Math.max(0, getScrollAmount() - 10));

            StepEntry over = this.getEntryAtPosition(mouseX, mouseY);
            if (over != null) {
                lastDropIndex = over.index;
            } else {
                if (mouseY > viewBottom) lastDropIndex = children().size();
                else if (mouseY < viewTop) lastDropIndex = 0;
            }
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            boolean handled = false;
            if (button == 0) {
                if (dragging && lastDropIndex >= 0) {
                    int targetModel;
                    if (lastDropIndex >= children().size()) targetModel = selectedPreset() != null ? selectedPreset().steps.size() : -1;
                    else targetModel = viewToModel(lastDropIndex);
                    if (targetModel >= 0) SequencerMainScreen.this.moveBlockToIndex(targetModel);
                    handled = true;
                }
            }
            dragArmed = false; dragging = false; armedIndex = -1; lastDropIndex = -1;
            return handled | super.mouseReleased(mouseX, mouseY, button);
        }

        void renderDropIndicator(DrawContext ctx) {
            if (!dragging || lastDropIndex < 0) return;
            int x = getRowLeft();
            int w = getRowWidth();
            int y;
            if (lastDropIndex >= children().size()) {
                y = viewTop + children().size() * itemH - (int) getScrollAmount();
            } else {
                y = viewTop + lastDropIndex * itemH - (int) getScrollAmount();
            }
            int lineY = y - 1;
            if (lineY < viewTop) lineY = viewTop;
            if (lineY > viewBottom - 2) lineY = viewBottom - 2;
            ctx.fill(x + 2, lineY, x + w - 2, lineY + 2, 0xFFFFC107);
        }

        @Override public int getRowWidth() { return width; }
        @Override protected int getScrollbarPositionX() { return getRowLeft() + width - 6; }
    }

    private class StepEntry extends AlwaysSelectedEntryListWidget.Entry<StepEntry> {
        final StepList parent;
        int index;
        String entityId;
        int ticks;
        String comment;
        int modelIndex;

        StepEntry(StepList parent, int index, String entityId, int ticks, String comment) {
            this.parent = parent; this.index = index; this.entityId = entityId; this.ticks = ticks; this.comment = comment == null ? "" : comment;
            this.modelIndex = index; // для нефильтрованного списка модель = вид
        }

        StepEntry(StepList parent, int viewIndex, int modelIndex, String entityId, int ticks, String comment) {
            this.parent = parent; this.index = viewIndex; this.modelIndex = modelIndex;
            this.entityId = entityId; this.ticks = ticks; this.comment = comment == null ? "" : comment;
        }

        @Override
        public void render(DrawContext ctx, int listIndex, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            boolean sel = parent.getSelectedOrNull() == this;
            boolean marked = parent.isMarked(listIndex);

            if (marked) ctx.fill(x, y, x + entryWidth, y + entryHeight, 0x5532CC71);
            if (sel)    ctx.fill(x, y, x + entryWidth, y + entryHeight, 0x553C78FF);

            int boxX = x + 2;
            int boxY = y + 4;
            int boxS = 12;
            int border = 0xFF000000;
            int tickCol = 0xFFFFFFFF; // цвет крестика [x]

// Рисуем чекбокс и крестик поверх остальных слоёв
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 300);

// фон чекбокса
            ctx.fill(boxX, boxY, boxX + boxS, boxY + boxS, 0xFF2B2B2B);
// рамка
            ctx.fill(boxX, boxY, boxX + boxS, boxY + 1, border);
            ctx.fill(boxX, boxY + boxS - 1, boxX + boxS, boxY + boxS, border);
            ctx.fill(boxX, boxY, boxX + 1, boxY + boxS, border);
            ctx.fill(boxX + boxS - 1, boxY, boxX + boxS, boxY + boxS, border);

// Крестик [x] — 2 диагонали + fallback-текст 'x'
            if (marked) {
                int inset = 2;
                // диагонали толщиной 2px
                for (int t = 0; t < 2; t++) {
                    for (int i = 0; i < boxS - inset * 2; i++) {
                        // диагональ \
                        int px1 = boxX + inset + i;
                        int py1 = boxY + inset + i + t;
                        ctx.fill(px1, py1, px1 + 1, py1 + 1, tickCol);
                        // диагональ /
                        int px2 = boxX + boxS - inset - 1 - i;
                        int py2 = boxY + inset + i + t;
                        ctx.fill(px2, py2, px2 + 1, py2 + 1, tickCol);
                    }
                }
                // Fallback: символ 'x' по центру — на случай, если диагонали не видны из‑за масштабирования
                String xMark = "x";
                int tx = boxX + (boxS - textRenderer.getWidth(xMark)) / 2;
                int ty = boxY + 1; // чуть смещаем вниз
                ctx.drawTextWithShadow(textRenderer, Text.literal(xMark), tx, ty, 0xFFFFFFFF);
            }

            ctx.getMatrices().pop();

            // Подсветка по фильтру (подготовка к п.2)
            boolean matches = false;
            if (stepFilterLower != null && !stepFilterLower.isBlank()) {
                // Ищем и по видимому номеру (listIndex+1), и по модельному (на всякий случай)
                String numberView = String.valueOf(listIndex + 1);
                String numberModel = String.valueOf(modelIndex + 1);
                String hay = (numberView + " " + numberModel + " " + entityId + " " + (comment == null ? "" : comment))
                        .toLowerCase(Locale.ROOT);
                matches = hay.contains(stepFilterLower);
            }
            if (matches) {
                ctx.fill(x, y, x + entryWidth, y + entryHeight, 0x222196F3);
            }

            int color = sel ? 0xFFFFFF : 0xDDDDDD;
            String main = (listIndex + 1) + ". " + entityId + "  (" + ticks + " тиков)";
            String full = comment != null && !comment.isBlank() ? (main + " — " + comment) : main;
            ctx.drawTextWithShadow(textRenderer, full, x + 4 + boxS + 4, y + 6, color);

            if (inlineEditVisible && inlineEditIndex == listIndex) {
                if (inlineEditField == null) {
                    inlineEditField = new TextFieldWidget(textRenderer, 0, 0, 60, ROW_H, Text.literal("ticks"));
                    inlineEditField.setTextPredicate(s -> s.matches("\\d*"));
                }
                int fieldX = x + entryWidth - 70;
                inlineEditField.setX(fieldX);
                inlineEditField.setY(y + 2);
                inlineEditField.setWidth(60);
                inlineEditField.render(ctx, mouseX, mouseY, delta);
            }

            this.index = listIndex;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 1) {
                parent.setSelected(this);
                parent.setAnchor(index);
                showContextMenuForRow((int) mouseX, (int) mouseY, index);
                return true;
            }

            int idx = this.index;
            int model = this.modelIndex;
            int left = parent.getRowLeft();
            boolean inLeftZone = mouseX >= left + 2 && mouseX <= left + 22;

            if (button == 0) parent.armDrag(idx);

            long now = System.currentTimeMillis();
            if (button == 0 && lastClickIndex == idx && (now - lastClickMs) < 300) {
                startInlineEdit(idx, model, ticks);
                lastClickIndex = -1;
                return true;
            }
            lastClickIndex = idx; lastClickMs = now;

            if (Screen.hasShiftDown()) {
                pushUndoSnapshot(); redoStack.clear();
                int anchor = parent.getAnchor();
                if (anchor < 0) {
                    var sel = stepList.getSelectedOrNull();
                    anchor = (sel != null) ? sel.index : idx;
                }
                boolean additive = hasControlDown();
                parent.selectRange(anchor, idx, additive);
                parent.setSelected(this);
                parent.setAnchor(anchor);
                return true;
            }

            if (hasControlDown() || inLeftZone) {
                pushUndoSnapshot(); redoStack.clear();
                parent.toggleMarked(idx);
                parent.setSelected(this);
                parent.setAnchor(idx);
                return true;
            }

            parent.setSelected(this);
            parent.setAnchor(idx);
            if (entityIdBox != null) entityIdBox.setText(entityId);
            if (durationBox != null) durationBox.setText(String.valueOf(ticks));
            if (commentBox != null) commentBox.setText(getComment(selectedPresetName, model));
            return true;
        }

        private void startInlineEdit(int viewIdx, int modelIdx, int currentTicks) {
            inlineEditVisible = true;
            inlineEditIndex = viewIdx;
            inlineEditModelIndex = modelIdx;
            if (inlineEditField == null) inlineEditField = new TextFieldWidget(textRenderer, 0, 0, 60, ROW_H, Text.literal("ticks"));
            inlineEditField.setTextPredicate(s -> s.matches("\\d*"));
            inlineEditField.setText(String.valueOf(Math.max(1, currentTicks)));
            inlineEditField.setFocused(true);
        }

        public Text getNarration() { return Text.literal(entityId + " (" + ticks + ")"); }
    }

    private void showContextMenuForRow(int x, int y, int row) {
        List<MenuItem> items = new ArrayList<>();
        boolean canUp = row > 0;
        SequencePreset p = selectedPreset();
        boolean canDown = p != null && row < p.steps.size() - 1;
        boolean hasSel = hasSelection();

        items.add(new MenuItem("Копировать", hasSel, this::copySelectedSteps));
        items.add(new MenuItem("Вставить", canPaste(), this::pasteSteps));
        items.add(new MenuItem("Дублировать", hasSel, this::duplicateSelectedSteps));
        items.add(new MenuItem("Удалить…", hasSel, this::handleDeleteWithConfirm));
        items.add(new MenuItem("—", false, null));
        items.add(new MenuItem("Вверх", canUp, () -> { stepList.setSelectedIndex(row); moveStep(-1); }));
        items.add(new MenuItem("Вниз", canDown, () -> { stepList.setSelectedIndex(row); moveStep(1); }));
        items.add(new MenuItem("Блок вверх", canMoveBlockUp(), () -> moveBlock(-1)));
        items.add(new MenuItem("Блок вниз", canMoveBlockDown(), () -> moveBlock(1)));
        items.add(new MenuItem("—", false, null));
        items.add(new MenuItem("Играть с этого шага", true, () -> { stepList.setSelectedIndex(row); playFromSelectedOrStart(); }));
        items.add(new MenuItem("Играть выделение", hasSel, this::playSelection));

        contextMenu.show(x, y, items);
    }

    // ===== Инлайн-редактор тиков =====
    private void commitInlineEdit(boolean forSelection) {
        if (!inlineEditVisible || inlineEditField == null) { cancelInlineEdit(); return; }
        int newTicks = parseIntSafe(inlineEditField.getText(), -1);
        if (newTicks > 0 && selectedPresetName != null) {
            SequencePreset p = presets.get(selectedPresetName);
            if (p != null) {
                pushUndoSnapshot(); redoStack.clear();
                if (forSelection) {
                    for (int i : getMarkedOrSelectedIndices()) {
                        if (i >= 0 && i < p.steps.size()) p.steps.get(i).durationTicks = newTicks;
                    }
                } else if (inlineEditModelIndex >= 0 && inlineEditModelIndex < p.steps.size()) {
                    p.steps.get(inlineEditModelIndex).durationTicks = newTicks;
                }
                PresetStorage.save(presets);
                markSaved();
                reloadStepsFromSelected();
                setStatus((forSelection ? "Тики применены к выделению: " : "Тики обновлены: ") + newTicks);
            }
        }
        cancelInlineEdit();
        inlineEditModelIndex = -1;

    }
    private void commitInlineEdit() { commitInlineEdit(false); }
    private void cancelInlineEdit() {
        inlineEditVisible = false;
        inlineEditIndex = -1;
        if (inlineEditField != null) inlineEditField.setFocused(false);
    }

    private class ControlPanelList extends AlwaysSelectedEntryListWidget<ControlEntry> {
        ControlPanelList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
        }
        public void addRow(ControlEntry e) { e.bindParent(this); super.addEntry(e); }
        public void clearRows() { super.clearEntries(); }

        @Override public int getRowWidth() { return width; }
        @Override protected int getScrollbarPositionX() { return getRowLeft() + width - 6; }
    }

    private abstract static class ControlEntry extends AlwaysSelectedEntryListWidget.Entry<ControlEntry> {
        protected ControlPanelList parent;
        final void bindParent(ControlPanelList parent) { this.parent = parent; }

        public Text getNarration() { return Text.empty(); }
        public List<? extends Element> children() { return Collections.emptyList(); }
        public List<? extends Selectable> selectableChildren() { return Collections.emptyList(); }
    }

    private class LabeledFieldEntry extends ControlEntry {
        private final Text label;
        private final TextFieldWidget field;
        private final int rowWidth;
        private final int labelWidth;

        LabeledFieldEntry(Text label, TextFieldWidget field, int rowWidth, int labelWidth) {
            this.label = label;
            this.field = field;
            this.rowWidth = rowWidth;
            this.labelWidth = Math.max(60, labelWidth);
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            int labelX = x + 2;
            int labelY = y + 6;
            ctx.drawTextWithShadow(textRenderer, label, labelX, labelY, 0xFFFFFF);

            int gap = 6;
            int fieldX = x + labelWidth + gap;
            int fieldW = Math.max(60, rowWidth - (labelWidth + gap));
            field.setX(fieldX);
            field.setY(y);
            field.setWidth(fieldW);
            field.setEditable(true);
            field.render(ctx, mouseX, mouseY, delta);
        }

        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (parent != null) parent.setSelected(this);
            field.setFocused(true);
            return field.mouseClicked(mouseX, mouseY, button);
        }
        public boolean mouseReleased(double mouseX, double mouseY, int button) { return field.mouseReleased(mouseX, mouseY, button); }
        public boolean charTyped(char chr, int modifiers) { return field.charTyped(chr, modifiers); }
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return field.keyPressed(keyCode, scanCode, modifiers); }
        public boolean keyReleased(int keyCode, int scanCode, int modifiers) { return field.keyReleased(keyCode, scanCode, modifiers); }

        public List<? extends Element> children() { return Collections.singletonList(field); }
        public List<? extends Selectable> selectableChildren() { return Collections.singletonList(field); }
    }

    // Кнопка-строка "Морф: <id>" с двойным кликом для входа в выбор
    private class MorphPickerEntry extends ControlEntry {
        private final ButtonWidget button;
        private final int rowWidth;
        private final Supplier<Text> labelSupplier;
        private final Runnable onDoubleClick;
        private long lastClickMs = 0L;

        MorphPickerEntry(int rowWidth, Supplier<Text> labelSupplier, Runnable onDoubleClick) {
            this.rowWidth = rowWidth;
            this.labelSupplier = labelSupplier;
            this.onDoubleClick = onDoubleClick;
            this.button = ButtonWidget.builder(labelSupplier.get(), b -> {}) // действие по одинарному клику не делаем
                    .position(0, 0).size(rowWidth, ROW_H).build();
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            if (labelSupplier != null) button.setMessage(labelSupplier.get());
            button.setX(x);
            button.setY(y);
            button.setWidth(rowWidth);
            button.render(ctx, mouseX, mouseY, delta);
        }

        public boolean mouseClicked(double mouseX, double mouseY, int buttonCode) {
            if (parent != null) parent.setSelected(this);
            boolean handled = this.button.mouseClicked(mouseX, mouseY, buttonCode);
            if (buttonCode == 0) { // ЛКМ
                long now = System.currentTimeMillis();
                if (now - lastClickMs < 300) {
                    if (onDoubleClick != null) onDoubleClick.run();
                    lastClickMs = 0L;
                    return true;
                }
                lastClickMs = now;
            }
            return handled;
        }

        public boolean mouseReleased(double mouseX, double mouseY, int buttonCode) {
            return this.button.mouseReleased(mouseX, mouseY, buttonCode);
        }

        public List<? extends Element> children() { return Collections.singletonList(button); }
        public List<? extends Selectable> selectableChildren() { return Collections.singletonList(button); }
    }

    private class ButtonRowEntry extends ControlEntry {
        private final ButtonWidget button;
        private final int rowWidth;
        private final Supplier<Text> labelSupplier;
        private final BooleanSupplier activeSupplier;

        ButtonRowEntry(int rowWidth, Text label, Runnable onClick) {
            this(rowWidth, () -> label, onClick, null);
        }

        ButtonRowEntry(int rowWidth, Text label, Runnable onClick, BooleanSupplier activeSupplier) {
            this(rowWidth, () -> label, onClick, activeSupplier);
        }

        ButtonRowEntry(int rowWidth, Supplier<Text> labelSupplier, Runnable onClick, BooleanSupplier activeSupplier) {
            this.rowWidth = rowWidth;
            this.labelSupplier = labelSupplier;
            this.activeSupplier = activeSupplier;
            this.button = ButtonWidget.builder(labelSupplier.get(), b -> onClick.run())
                    .position(0, 0).size(rowWidth, ROW_H).build();
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            if (labelSupplier != null) button.setMessage(labelSupplier.get());
            if (activeSupplier != null) button.active = activeSupplier.getAsBoolean();
            button.setX(x);
            button.setY(y);
            button.setWidth(rowWidth);
            button.render(ctx, mouseX, mouseY, delta);
        }

        public boolean mouseClicked(double mouseX, double mouseY, int b) {
            if (parent != null) parent.setSelected(this);
            return this.button.mouseClicked(mouseX, mouseY, b);
        }
        public boolean mouseReleased(double mouseX, double mouseY, int b) { return this.button.mouseReleased(mouseX, mouseY, b); }

        public List<? extends Element> children() { return Collections.singletonList(button); }
        public List<? extends Selectable> selectableChildren() { return Collections.singletonList(button); }
    }

    private class DualButtonRowEntry extends ControlEntry {
        private final ButtonWidget left;
        private final ButtonWidget right;
        private final int rowWidth;
        private final BooleanSupplier leftActive;
        private final BooleanSupplier rightActive;

        DualButtonRowEntry(int rowWidth,
                           Text leftLabel, Runnable leftClick, BooleanSupplier leftActive,
                           Text rightLabel, Runnable rightClick, BooleanSupplier rightActive) {
            this.rowWidth = rowWidth;
            int halfW = (rowWidth - 6) / 2;
            this.left = ButtonWidget.builder(leftLabel, b -> leftClick.run()).position(0, 0).size(halfW, ROW_H).build();
            this.right = ButtonWidget.builder(rightLabel, b -> rightClick.run()).position(0, 0).size(halfW, ROW_H).build();
            this.leftActive = leftActive;
            this.rightActive = rightActive;
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            int halfW = (rowWidth - 6) / 2;
            if (leftActive != null) left.active = leftActive.getAsBoolean();
            if (rightActive != null) right.active = rightActive.getAsBoolean();
            left.setX(x); left.setY(y); left.setWidth(halfW);
            right.setX(x + halfW + 6); right.setY(y); right.setWidth(halfW);
            left.render(ctx, mouseX, mouseY, delta);
            right.render(ctx, mouseX, mouseY, delta);
        }

        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (parent != null) parent.setSelected(this);
            return left.mouseClicked(mouseX, mouseY, button) | right.mouseClicked(mouseX, mouseY, button);
        }
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return left.mouseReleased(mouseX, mouseY, button) | right.mouseReleased(mouseX, mouseY, button);
        }

        public List<? extends Element> children() { return Arrays.asList(left, right); }
        public List<? extends Selectable> selectableChildren() { return Arrays.asList(left, right); }
    }

    // ===== Конфиги =====
    private static class KeyConfig {
        int keyPlayPause = 80; // P
        int keyStop = 83;      // S
        int keyRestart = 82;   // R
        int keyAddStep = 78;   // N

        static KeyConfig loadFrom(Path path) {
            try {
                if (Files.exists(path)) {
                    String s = Files.readString(path);
                    Gson g = new Gson();
                    KeyConfig kc = g.fromJson(s, KeyConfig.class);
                    if (kc != null) return kc.ensureValid();
                }
            } catch (Exception ignored) {}
            KeyConfig def = new KeyConfig();
            def.saveTo(path);
            return def;
        }

        void saveTo(Path path) {
            try {
                Files.createDirectories(path.getParent());
                String s = new GsonBuilder().setPrettyPrinting().create().toJson(this);
                Files.writeString(path, s, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }

        void resetDefaults() {
            keyPlayPause = 80; keyStop = 83; keyRestart = 82; keyAddStep = 78;
        }

        KeyConfig ensureValid() {
            if (keyPlayPause < 0) keyPlayPause = 80;
            if (keyStop < 0) keyStop = 83;
            if (keyRestart < 0) keyRestart = 82;
            if (keyAddStep < 0) keyAddStep = 78;
            return this;
        }
    }


    private static class UiConfig {
        static class UIPreviewState {
            public float rotX = 0f;
            public float rotY = 0f;
            public float zoom = 1f;
        }
        int stepItemHeight = 22;
        double leftPanelPercent = 0.22;
        double rightPanelPercent = 0.30;
        int menuMaxVisibleItems = 16;
        String lastFileDir = "";
        double previewBaseScale = 1.0;

        String lastTab = "PRESETS";
        String lastPresetName = "";
        int lastStepIndex = -1;
        Map<String, UIPreviewState> previewStates = new HashMap<>();

        static UiConfig loadFrom(Path path) {
            try {
                if (Files.exists(path)) {
                    String s = Files.readString(path);
                    Gson g = new Gson();
                    UiConfig u = g.fromJson(s, UiConfig.class);
                    if (u != null) return u.ensureValid();
                }
            } catch (Exception ignored) {}
            UiConfig def = new UiConfig();
            def.saveTo(path);
            return def;
        }

        void saveTo(Path path) {
            try {
                Files.createDirectories(path.getParent());
                String s = new GsonBuilder().setPrettyPrinting().create().toJson(this);
                Files.writeString(path, s, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }

        UiConfig ensureValid() {
            stepItemHeight = clamp(stepItemHeight, 16, 30);
            leftPanelPercent = Math.max(0.10, Math.min(0.40, leftPanelPercent));
            rightPanelPercent = Math.max(0.15, Math.min(0.40, rightPanelPercent));
            menuMaxVisibleItems = clamp(menuMaxVisibleItems, 6, 30);
            if (lastFileDir == null) lastFileDir = "";
            previewBaseScale = Math.max(0.5, Math.min(2.0, previewBaseScale));
            if (lastTab == null || (!lastTab.equals("MORPHS") && !lastTab.equals("PRESETS") && !lastTab.equals("SETTINGS"))) lastTab = "PRESETS";
            if (lastStepIndex < -1) lastStepIndex = -1;
            if (previewStates == null) previewStates = new HashMap<>();
            return this;
        }
    }
}