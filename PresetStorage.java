package seq.sequencermod.client.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PresetStorage {
    private PresetStorage() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, SequencePreset>>() {}.getType();

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("sequencermod");
    }

    public static Path presetsPath() {
        return configDir().resolve("presets.json");
    }

    public static Map<String, SequencePreset> load() {
        try {
            Path dir = configDir();
            if (Files.notExists(dir)) Files.createDirectories(dir);
            Path file = presetsPath();
            if (Files.notExists(file)) {
                return new LinkedHashMap<>();
            }
            try (Reader r = Files.newBufferedReader(file)) {
                Map<String, SequencePreset> map = GSON.fromJson(r, MAP_TYPE);
                return map != null ? map : new LinkedHashMap<>();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return new LinkedHashMap<>();
        }
    }

    public static void save(Map<String, SequencePreset> map) {
        try {
            Path dir = configDir();
            if (Files.notExists(dir)) Files.createDirectories(dir);
            Path file = presetsPath();
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(map, MAP_TYPE, w);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}