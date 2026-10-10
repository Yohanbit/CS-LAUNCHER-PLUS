package net.kdt.pojavlaunch.performance;
import android.content.Context;
public class SessionStats {
    public static void append(Context context, Entry entry) { }
    public static class Entry {
        Entry(long timestamp, String renderer, String mode, String version, int median,
              int worst, float inverseRate, long duration, int thermal) { }
    }
}
