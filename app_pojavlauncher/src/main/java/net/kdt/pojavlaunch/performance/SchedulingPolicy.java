package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, reversible requests for identified game threads. Never changes affinity or clocks.
 * All ownership changes are serialized with shutdown, including priority restoration.
 * ADPF is target-only: no actual render-loop timing is available, so its benefit is unmeasured.
 */
public final class SchedulingPolicy {
    // Exact names (including Linux comm truncation); exclude broad worker/driver/main matches.
    private static final String[] RENDER_THREAD_NAMES = {
            "render thread", "client main thread", "client main thr", "minecraft main", "lwjgl"
    };
    private static final long TICK_MS = 1000L;
    private static volatile SchedulingPolicy sActive;
    private final Context appContext;
    private final PerformancePolicy policy;
    private final HandlerThread thread;
    private final Handler handler;
    private final Map<Integer, BoostedThread> boosted = new LinkedHashMap<>();
    private final Map<Integer, String> yielded = new LinkedHashMap<>();
    private final List<Runnable> tickListeners = new ArrayList<>();
    private volatile boolean shedByThermal;
    private boolean stopped;
    private android.os.PerformanceHintManager.Session hintSession;
    private int[] hintThreadIds = new int[0];

    private static final class BoostedThread {
        final String startTime;
        final int originalPriority;
        int appliedPriority;
        BoostedThread(String startTime, int original, int applied) {
            this.startTime = startTime;
            originalPriority = original;
            appliedPriority = applied;
        }
    }

    private SchedulingPolicy(Context ctx, PerformancePolicy policy) {
        appContext = ctx.getApplicationContext();
        this.policy = policy;
        thread = new HandlerThread("CS-PerfEngine", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public static synchronized void begin(Context ctx, PerformancePolicy policy) {
        end();
        if (ctx == null || policy == null || !policy.boostGameThreads) return;
        try {
            SchedulingPolicy s = new SchedulingPolicy(ctx, policy);
            sActive = s;
            s.handler.post(s.tick);
        } catch (Throwable ignored) { }
    }

    public static synchronized void end() {
        SchedulingPolicy s = sActive;
        sActive = null;
        if (s == null) return;
        s.shutdown();
        s.thread.quitSafely();
    }

    private synchronized void shutdown() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        closeHintSession();
        for (Map.Entry<Integer, BoostedThread> entry : boosted.entrySet()) {
            int tid = entry.getKey();
            BoostedThread record = entry.getValue();
            try {
                if (record.startTime.equals(readStartTime(tid))
                        && Process.getThreadPriority(tid) == record.appliedPriority) {
                    Process.setThreadPriority(tid, record.originalPriority);
                }
            } catch (Throwable ignored) { }
        }
        boosted.clear();
        yielded.clear();
    }

    public static boolean isActive() { return sActive != null; }

    public static void addTickListener(Runnable r) {
        SchedulingPolicy s = sActive;
        if (s == null || r == null) return;
        synchronized (s.tickListeners) {
            if (!s.tickListeners.contains(r)) s.tickListeners.add(r);
        }
    }

    public static void removeTickListener(Runnable r) {
        SchedulingPolicy s = sActive;
        if (s == null || r == null) return;
        synchronized (s.tickListeners) { s.tickListeners.remove(r); }
    }

    public static void setThermalShed(boolean shed) {
        SchedulingPolicy s = sActive;
        if (s == null || !s.policy.thermalShedAllowed) return;
        synchronized (s) {
            if (s.stopped || s.shedByThermal == shed) return;
            s.shedByThermal = shed;
            s.handler.post(() -> s.applyPriorities());
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            synchronized (SchedulingPolicy.this) { if (stopped) return; }
            scanThreads();
            List<Runnable> copy;
            synchronized (tickListeners) { copy = new ArrayList<>(tickListeners); }
            for (Runnable listener : copy) {
                synchronized (SchedulingPolicy.this) { if (stopped) return; }
                try { listener.run(); } catch (Throwable ignored) { }
            }
            synchronized (SchedulingPolicy.this) {
                if (!stopped) handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private synchronized void scanThreads() {
        if (stopped) return;
        pruneThreads();
        try {
            File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks != null && !shedByThermal) {
                for (File task : tasks) {
                    if (boosted.size() >= Math.max(0, policy.maxBoostedThreads)) break;
                    int tid;
                    try { tid = Integer.parseInt(task.getName()); }
                    catch (NumberFormatException ignored) { continue; }
                    if (boosted.containsKey(tid) || tid == Process.myPid()
                            || tid == thread.getThreadId()) continue;
                    String name = readProc(tid, "comm");
                    if (name == null || !isRenderThread(name)) continue;
                    String identity = readStartTime(tid);
                    if (identity == null) continue;
                    if (identity.equals(yielded.get(tid))) continue;
                    yielded.remove(tid);
                    try {
                        int original = Process.getThreadPriority(tid);
                        int target = Math.min(original, policy.threadPriority);
                        if (target == original || !identity.equals(readStartTime(tid))) continue;
                        Process.setThreadPriority(tid, target);
                        if (identity.equals(readStartTime(tid))) {
                            boosted.put(tid, new BoostedThread(identity, original, target));
                        }
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { /* OEMs can restrict proc access. Fail closed. */ }
        refreshHintSession();
    }

    private void pruneThreads() {
        Iterator<Map.Entry<Integer, String>> yieldedIterator = yielded.entrySet().iterator();
        while (yieldedIterator.hasNext()) {
            Map.Entry<Integer, String> entry = yieldedIterator.next();
            if (!entry.getValue().equals(readStartTime(entry.getKey()))) yieldedIterator.remove();
        }
        Iterator<Map.Entry<Integer, BoostedThread>> it = boosted.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, BoostedThread> entry = it.next();
            try {
                boolean sameThread = entry.getValue().startTime.equals(readStartTime(entry.getKey()));
                boolean externallyChanged = sameThread && Process.getThreadPriority(entry.getKey())
                        != entry.getValue().appliedPriority;
                if (!sameThread || externallyChanged) {
                    if (externallyChanged) yielded.put(entry.getKey(), entry.getValue().startTime);
                    it.remove(); // exited/recycled or priority changed by another owner
                    closeHintSession(); // also rebuild if a recycled TID yields the same numeric list
                }
            } catch (Throwable ignored) { it.remove(); closeHintSession(); }
        }
    }

    private synchronized void applyPriorities() {
        if (stopped) return;
        pruneThreads();
        for (Map.Entry<Integer, BoostedThread> entry : boosted.entrySet()) {
            BoostedThread record = entry.getValue();
            try {
                if (!record.startTime.equals(readStartTime(entry.getKey()))) continue;
                int target = shedByThermal ? record.originalPriority
                        : Math.min(record.originalPriority, policy.threadPriority);
                Process.setThreadPriority(entry.getKey(), target);
                record.appliedPriority = target;
            } catch (Throwable ignored) { }
        }
        refreshHintSession();
    }

    private static boolean isRenderThread(String comm) {
        String name = comm.toLowerCase(Locale.ROOT);
        for (String candidate : RENDER_THREAD_NAMES) if (name.equals(candidate)) return true;
        return false;
    }

    private static String readProc(int tid, String name) {
        try (BufferedReader reader = new BufferedReader(new FileReader(
                new File(new File("/proc/self/task", String.valueOf(tid)), name)))) {
            return reader.readLine();
        } catch (Throwable ignored) { return null; }
    }

    private static String readStartTime(int tid) {
        String stat = readProc(tid, "stat");
        if (stat == null) return null;
        // comm can contain spaces and parentheses; fields after its final ')' start at field 3.
        int end = stat.lastIndexOf(')');
        if (end < 0) return null;
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        return fields.length > 19 ? fields[19] : null; // field 22: Linux task start time
    }

    // Caller holds this object's monitor. Recreate on membership change (API 31 has no setThreads).
    private void refreshHintSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !policy.adpfSession) return;
        int[] ids = boostedThreadIds();
        if (shedByThermal || !Arrays.equals(ids, hintThreadIds)) closeHintSession();
        if (stopped || shedByThermal || ids.length == 0) return;
        try {
            long budget = measureFrameBudgetNanos();
            if (hintSession == null) {
                android.os.PerformanceHintManager manager = appContext
                        .getSystemService(android.os.PerformanceHintManager.class);
                if (manager == null) return;
                hintSession = manager.createHintSession(ids, budget);
                if (hintSession != null) hintThreadIds = ids;
            } else {
                hintSession.updateTargetWorkDuration(budget);
            }
        } catch (Throwable ignored) { closeHintSession(); }
    }

    private void closeHintSession() {
        if (hintSession != null) {
            try { hintSession.close(); } catch (Throwable ignored) { }
        }
        hintSession = null;
        hintThreadIds = new int[0];
    }

    /** Only successfully changed game threads, never launcher UI/engine threads. */
    private int[] boostedThreadIds() {
        int[] ids = new int[boosted.size()];
        int index = 0;
        for (Integer tid : boosted.keySet()) ids[index++] = tid;
        return ids;
    }

    private long measureFrameBudgetNanos() {
        float hz = 60f;
        try {
            android.view.WindowManager wm = (android.view.WindowManager) appContext
                    .getSystemService(Context.WINDOW_SERVICE);
            android.view.Display display = wm == null ? null : wm.getDefaultDisplay();
            if (display != null) {
                float current = display.getRefreshRate();
                if (current >= 24f && current <= 240f) hz = current;
            }
        } catch (Throwable ignored) { }
        // A target is not a display-mode request. Respect the currently active rate and player's cap.
        if (policy.game != null) {
            int wanted = policy.game.mcMaxFps();
            if (wanted > 0 && wanted < hz) hz = wanted;
        }
        return (long) (1_000_000_000L / hz);
    }

    public static String lastEngineNote() {
        return "ADPF: " + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? "target-only; benefit unmeasured" : "needs Android 12")
                + " · priorities: " + (isActive() ? "active this session" : "idle");
    }

    public static boolean isShedActive() {
        SchedulingPolicy s = sActive;
        return s != null && s.shedByThermal;
    }
}
