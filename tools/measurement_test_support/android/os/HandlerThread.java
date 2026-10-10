package android.os;
import java.util.ArrayList;
import java.util.List;
/** Deterministic lifecycle test double, not a physical Android looper. */
public class HandlerThread {
    public static final List<HandlerThread> ALL = new ArrayList<>();
    public final String name;
    public boolean running;
    private final Looper looper = new Looper(this);
    public HandlerThread(String name, int priority) { this.name = name; ALL.add(this); }
    public void start() { running = true; }
    public Looper getLooper() { return looper; }
    public boolean quitSafely() { running = false; return true; }
    public static int liveCount() {
        int count = 0;
        for (HandlerThread thread : ALL) if (thread.running) count++;
        return count;
    }
}
