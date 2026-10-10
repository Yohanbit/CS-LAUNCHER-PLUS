package net.kdt.pojavlaunch.performance;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
public class SessionStats {
    public static final List<Entry> ENTRIES = new ArrayList<>();
    public static void append(Context context, Entry entry) { ENTRIES.add(entry); }
    public static class Entry {
        public final String renderer, mode, version;
        public final int median, worst;
        public final long duration;
        Entry(long timestamp, String renderer, String mode, String version, int median,
              int worst, float inverseRate, long duration, int thermal) {
            this.renderer = renderer; this.mode = mode; this.version = version;
            this.median = median; this.worst = worst; this.duration = duration;
        }
    }
}
