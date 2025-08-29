package seq.sequencermod.client.preview;

public final class PreviewDebug {
    private static final ThreadLocal<Boolean> GUI_PREVIEW = ThreadLocal.withInitial(() -> Boolean.FALSE);
    public static void inGui(boolean v) { GUI_PREVIEW.set(v); }
    public static boolean inGui() { return Boolean.TRUE.equals(GUI_PREVIEW.get()); }

    // ДОБАВЛЕНО: флаг "сейчас рисуем главное (правое) превью"
    private static final ThreadLocal<Boolean> MAIN_PREVIEW = ThreadLocal.withInitial(() -> Boolean.FALSE);
    public static void inMainPreview(boolean v) { MAIN_PREVIEW.set(v); }
    public static boolean isMainPreview() { return Boolean.TRUE.equals(MAIN_PREVIEW.get()); }

    private PreviewDebug() {}
}