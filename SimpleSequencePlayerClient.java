package seq.sequencermod.client.ui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Клиентский проигрыватель пресета:
 * - Стартовая задержка
 * - Последовательная смена морфов по тикам
 * - Пауза/Продолжить/Стоп
 * - Луп: бесконечный или N повторов
 * - Информация о прогрессе для UI
 */
public final class SimpleSequencePlayerClient {
    private static boolean inited = false;

    private static volatile boolean running = false;
    private static volatile boolean paused = false;

    private static SequencePreset preset = null;

    // Индексация шагов
    private static int indexNext = 0;            // следующий к проигрыванию шаг (0..n)
    private static int currentStepIndex = -1;    // текущий исполняемый шаг (−1 когда ещё ни одного)
    private static String currentEntityId = "";
    private static int currentStepDuration = 0;  // полная длительность текущего шага
    private static int currentStepLeft = 0;      // сколько тиков осталось в текущем шаге

    // Стартовая задержка
    private static int startDelayTotal = 0;
    private static int startDelayLeft = 0;

    // Луп
    private static boolean loopInfinite = false;
    private static int loopsTarget = 1;   // при loopInfinite игнорируется
    private static int loopsDone = 0;     // завершённых циклов (0 — первый цикл в процессе)

    public static void init() {
        if (inited) return;
        inited = true;
        ClientTickEvents.END_CLIENT_TICK.register(SimpleSequencePlayerClient::onClientTick);
    }

    public static void play(SequencePreset p, int startDelayTicks, boolean loopInfiniteFlag, int loopCount) {
        if (p == null || p.steps.isEmpty()) return;
        init();

        preset = p;
        startDelayTotal = Math.max(0, startDelayTicks);
        startDelayLeft = startDelayTotal;

        loopInfinite = loopInfiniteFlag;
        loopsTarget = Math.max(1, loopCount);
        loopsDone = 0;

        indexNext = 0;
        currentStepIndex = -1;
        currentEntityId = "";
        currentStepDuration = 0;
        currentStepLeft = 0;

        paused = false;
        running = true;

        // Если задержка нулевая — немедленно выполняем первый шаг
        if (startDelayLeft == 0) {
            doNextStep(MinecraftClient.getInstance());
        }
    }

    public static void stop() {
        running = false;
        paused = false;

        preset = null;
        indexNext = 0;
        currentStepIndex = -1;
        currentEntityId = "";
        currentStepDuration = 0;
        currentStepLeft = 0;
        startDelayLeft = 0;
        startDelayTotal = 0;
        loopsDone = 0;
    }

    public static void pause() {
        if (running) paused = true;
    }

    public static void resume() {
        if (running) paused = false;
    }

    private static void onClientTick(MinecraftClient client) {
        if (!running || paused) return;
        if (client == null || client.player == null || client.player.networkHandler == null) { stop(); return; }
        if (preset == null || preset.steps.isEmpty()) { stop(); return; }

        // Стартовая задержка между циклами
        if (startDelayLeft > 0) {
            startDelayLeft--;
            if (startDelayLeft == 0 && currentStepIndex < 0 && indexNext == 0) {
                // кончилась задержка перед самым первым шагом цикла
                doNextStep(client);
            }
            return;
        }

        if (currentStepLeft > 0) {
            currentStepLeft--;
            if (currentStepLeft > 0) return; // ещё не закончили шаг
            // текущий шаг закончился — на следующей итерации перейдём к следующему
        }

        // Если активного шага нет или он закончился — переходим к следующему
        doNextStep(client);
    }

    private static void doNextStep(MinecraftClient client) {
        if (!running) return;
        if (preset == null || preset.steps.isEmpty()) { stop(); return; }

        // Закончились шаги текущего цикла?
        if (indexNext >= preset.steps.size()) {
            // Завершили цикл
            loopsDone++;

            // Нужно ли продолжать?
            boolean continueLoop = loopInfinite || (loopsDone < loopsTarget);
            if (!continueLoop) {
                stop();
                return;
            }

            // Новый цикл
            indexNext = 0;
            currentStepIndex = -1;
            currentEntityId = "";
            currentStepDuration = 0;
            currentStepLeft = 0;

            startDelayLeft = startDelayTotal;
            if (startDelayLeft == 0) {
                // без задержки — сразу стартуем
                doNextStep(client);
            }
            return;
        }

        // Следующий шаг текущего цикла
        SequencePreset.Step step = preset.steps.get(indexNext);
        currentStepIndex = indexNext;
        indexNext++;

        currentEntityId = step.entityId == null ? "" : step.entityId.trim();
        currentStepDuration = Math.max(1, step.durationTicks);
        currentStepLeft = currentStepDuration;

        if (!currentEntityId.isEmpty()) {
            try {
                client.player.networkHandler.sendChatCommand("morphc " + currentEntityId);
            } catch (Throwable ignored) {}
        }
    }

    // ===== Информация о состоянии/прогрессе для UI =====

    public static PlaybackInfo getInfo() {
        PlaybackInfo info = new PlaybackInfo();
        info.running = running;
        info.paused = paused;

        info.stepsCount = (preset == null) ? 0 : preset.steps.size();
        info.currentStepIndex = currentStepIndex;
        info.currentEntityId = currentEntityId;
        info.currentStepDuration = currentStepDuration;
        info.currentStepLeft = currentStepLeft;

        info.startDelayTotal = startDelayTotal;
        info.startDelayLeft = startDelayLeft;

        info.loopInfinite = loopInfinite;
        info.loopsTarget = loopsTarget;
        info.loopsDone = loopsDone;

        // Считаем суммарную длительность цикла (задержка + все шаги, минимум 1 тик на шаг)
        int loopTotal = startDelayTotal;
        if (preset != null) {
            for (SequencePreset.Step s : preset.steps) loopTotal += Math.max(1, s.durationTicks);
        }
        info.loopTotalTicks = loopTotal;

        // Считаем, сколько тиков прошло внутри текущего цикла
        int elapsed = 0;
        // прошедшая часть стартовой задержки
        elapsed += Math.max(0, startDelayTotal - startDelayLeft);

        // завершённые шаги (до текущего)
        if (preset != null && currentStepIndex > 0) {
            for (int i = 0; i < currentStepIndex; i++) {
                elapsed += Math.max(1, preset.steps.get(i).durationTicks);
            }
        }

        // текущий шаг — уже оттикало
        if (currentStepIndex >= 0 && currentStepDuration > 0) {
            elapsed += Math.max(0, currentStepDuration - currentStepLeft);
        }

        info.loopElapsedTicks = Math.min(Math.max(0, elapsed), loopTotal);

        return info;
    }

    public static final class PlaybackInfo {
        public boolean running;
        public boolean paused;

        public int stepsCount;
        public int currentStepIndex;     // −1 если ещё ни одного шага
        public String currentEntityId = "";
        public int currentStepDuration;
        public int currentStepLeft;

        public int startDelayTotal;
        public int startDelayLeft;

        public boolean loopInfinite;
        public int loopsTarget;
        public int loopsDone;

        public int loopTotalTicks;
        public int loopElapsedTicks;
    }

    private SimpleSequencePlayerClient() {}
}