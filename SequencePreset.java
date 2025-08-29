package seq.sequencermod.client.ui;

import java.util.ArrayList;
import java.util.List;

public class SequencePreset {
    public String name;
    public final List<Step> steps = new ArrayList<>();

    public SequencePreset() {}

    public SequencePreset(String name) {
        this.name = name;
    }

    public static class Step {
        public String entityId;
        public int durationTicks;

        public Step() {}

        public Step(String entityId, int durationTicks) {
            this.entityId = entityId;
            this.durationTicks = durationTicks;
        }
    }
}