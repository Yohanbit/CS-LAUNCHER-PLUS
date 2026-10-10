package android.os;
import java.util.ArrayList;
import java.util.List;
public class Handler {
    private static final List<Pending> QUEUE = new ArrayList<>();
    private final Looper looper;
    private static class Pending {
        final Handler handler; final Runnable callback;
        Pending(Handler handler, Runnable callback) { this.handler = handler; this.callback = callback; }
    }
    public Handler(Looper looper) { this.looper = looper; }
    public boolean post(Runnable callback) { return postDelayed(callback, 0); }
    public boolean postDelayed(Runnable callback, long delay) {
        synchronized (QUEUE) {
            if (!looper.owner.running) return false;
            QUEUE.add(new Pending(this, callback)); return true;
        }
    }
    public void removeCallbacksAndMessages(Object token) {
        synchronized (QUEUE) { QUEUE.removeIf(p -> p.handler == this); }
    }
    public static Runnable take(String threadName) {
        synchronized (QUEUE) {
            for (int i = 0; i < QUEUE.size(); i++) {
                Pending p = QUEUE.get(i);
                if (p.handler.looper.owner.name.equals(threadName)) {
                    QUEUE.remove(i); return p.callback;
                }
            }
        }
        throw new AssertionError("Missing callback " + threadName);
    }
    public static int pendingCount() { synchronized (QUEUE) { return QUEUE.size(); } }
}
