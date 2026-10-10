package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional nice -2 experiment for exact game comm names; no affinity, clocks or realtime priority.
 * All writes and restoration are serialized. ADPF remains disabled until genuine CPU work timing
 * and explicit render-work membership are available; swap/FPS intervals are not substitutes.
 */
public final class SchedulingPolicy {
    private static final long TICK_MS = 1000L;
    private static final Runnable[] NO_LISTENERS = new Runnable[0];
    private static volatile SchedulingPolicy sActive;
    private final PerformancePolicy policy;
    private final HandlerThread thread;
    private final Handler handler;
    private final Map<Integer, OwnedThread> owned = new LinkedHashMap<>();
    private final Map<Integer, String> yielded = new LinkedHashMap<>();
    // Copy only on registration changes, not on every foreground heartbeat.
    private volatile Runnable[] tickListeners = NO_LISTENERS;
    private volatile boolean shedByThermal;
    private boolean stopped;

    private static final class OwnedThread {
        final String startTime;
        final int original;
        int applied;
        OwnedThread(String identity, int priority) {
            startTime = identity; original = priority; applied = priority;
        }
    }

    private SchedulingPolicy(PerformancePolicy policy) {
        this.policy = policy;
        thread = new HandlerThread("CS-PerfEngine", Process.THREAD_PRIORITY_BACKGROUND);
        try {
            thread.start();
            handler = new Handler(thread.getLooper());
        } catch (Exception unavailable) {
            thread.quitSafely();
            throw unavailable;
        }
    }

    public static synchronized void begin(Context ctx, PerformancePolicy policy) {
        end();
        if (ctx == null || policy == null || (!policy.boostGameThreads
                && (!policy.thermalPolling || !ThermalGovernor.canPoll(ctx)))) return;
        try {
            SchedulingPolicy s = new SchedulingPolicy(policy);
            sActive = s;
            // Let the synchronous initial thermal read run before any experimental priority request.
            if (!s.handler.postDelayed(s.tick, TICK_MS)) {
                throw new IllegalStateException("Scheduler looper rejected start");
            }
        } catch (Throwable e) {
            end();
            Log.w("CSPerf", "Scheduler unavailable", e);
        }
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
        for (Map.Entry<Integer, OwnedThread> entry : owned.entrySet()) {
            if (!restore(entry.getKey(), entry.getValue())) {
                Log.w("CSPerf", "Unable to restore owned TID " + entry.getKey()
                        + "; exited/recycled/externally changed tasks are never overwritten");
            }
        }
        owned.clear();
        yielded.clear();
        tickListeners = NO_LISTENERS;
    }

    /** True only when priority experiments were requested, not for an observe-only heartbeat. */
    public static boolean isActive() { return sActive != null && sActive.policy.boostGameThreads; }

    public static void addTickListener(Runnable r) {
        tryAddTickListener(r);
    }

    /** Lifecycle handshake: consumers must not claim sampling without a live heartbeat. */
    static boolean tryAddTickListener(Runnable r) {
        SchedulingPolicy s = sActive;
        if (s == null || r == null) return false;
        synchronized (s) {
            if (s.stopped) return false;
            for (Runnable listener : s.tickListeners) if (listener == r) return true;
            Runnable[] next = Arrays.copyOf(s.tickListeners, s.tickListeners.length + 1);
            next[next.length - 1] = r;
            s.tickListeners = next;
            return true;
        }
    }

    public static void removeTickListener(Runnable r) {
        SchedulingPolicy s = sActive;
        if (s == null || r == null) return;
        synchronized (s) {
            Runnable[] previous = s.tickListeners;
            for (int i = 0; i < previous.length; i++) {
                if (previous[i] != r) continue;
                Runnable[] next = new Runnable[previous.length - 1];
                System.arraycopy(previous, 0, next, 0, i);
                System.arraycopy(previous, i + 1, next, i, previous.length - i - 1);
                s.tickListeners = next;
                return;
            }
        }
    }

    public static void setThermalShed(boolean shed) {
        SchedulingPolicy s = sActive;
        if (s == null || !s.policy.thermalShedAllowed) return;
        synchronized (s) {
            if (s.stopped || s.shedByThermal == shed) return;
            s.shedByThermal = shed;
            s.applyPriorities(); // revoke immediately; no queue racing the next scan
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            synchronized (SchedulingPolicy.this) { if (stopped) return; }
            Runnable[] copy = tickListeners;
            // Sample heat before scanning new candidates.
            for (Runnable listener : copy) {
                synchronized (SchedulingPolicy.this) { if (stopped) return; }
                try { listener.run(); } catch (Throwable ignored) { }
            }
            scanThreads();
            synchronized (SchedulingPolicy.this) {
                if (!stopped) handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private synchronized void scanThreads() {
        if (stopped || !policy.boostGameThreads) return;
        pruneThreads();
        if (shedByThermal) return;
        try {
            File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks == null) return; // OEM restrictions: no guessing names or TIDs
            for (File task : tasks) {
                if (owned.size() >= Math.min(2, Math.max(0, policy.maxBoostedThreads))) break;
                int tid;
                try { tid = Integer.parseInt(task.getName()); }
                catch (NumberFormatException ignored) { continue; }
                if (owned.containsKey(tid) || tid == Process.myPid() || tid == thread.getThreadId()) continue;
                if (!RuntimeThreadRules.isGameRenderThread(readProc(tid, "comm"))) continue;
                String identity = readStartTime(tid);
                if (identity == null || identity.equals(yielded.get(tid))) continue;
                yielded.remove(tid);
                try {
                    int original = Process.getThreadPriority(tid);
                    // Never compete with audio/display priorities, even if a malformed policy asks.
                    int target = Math.min(original, Math.max(-2, policy.threadPriority));
                    if (target == original || !identity.equals(readStartTime(tid))) continue;
                    OwnedThread record = new OwnedThread(identity, original);
                    // Retain the record even if verification reads fail after a successful write.
                    owned.put(tid, record);
                    Process.setThreadPriority(tid, target);
                    record.applied = target;
                } catch (Throwable e) { Log.w("CSPerf", "Priority request unavailable for TID " + tid, e); }
            }
        } catch (Throwable ignored) { }
    }

    private void pruneThreads() {
        Iterator<Map.Entry<Integer, String>> yi = yielded.entrySet().iterator();
        while (yi.hasNext()) {
            Map.Entry<Integer, String> e = yi.next();
            String identity = readStartTime(e.getKey());
            if (identity != null && !e.getValue().equals(identity)) yi.remove();
            else if (!new File("/proc/self/task/" + e.getKey()).exists()) yi.remove();
        }
        Iterator<Map.Entry<Integer, OwnedThread>> it = owned.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, OwnedThread> e = it.next();
            String identity = readStartTime(e.getKey());
            if (identity == null) {
                if (!new File("/proc/self/task/" + e.getKey()).exists()) it.remove();
                continue; // transient proc read failure: keep restoration ownership
            }
            if (!e.getValue().startTime.equals(identity)) { it.remove(); continue; }
            try {
                if (Process.getThreadPriority(e.getKey()) != e.getValue().applied) {
                    yielded.put(e.getKey(), identity);
                    it.remove(); // a later external owner wins
                } else if (!RuntimeThreadRules.isGameRenderThread(readProc(e.getKey(), "comm"))
                        && restore(e.getKey(), e.getValue())) {
                    yielded.put(e.getKey(), identity);
                    it.remove();
                }
            } catch (Throwable ignored) { /* retain for later restoration */ }
        }
    }

    private synchronized void applyPriorities() {
        if (stopped) return;
        pruneThreads();
        for (Map.Entry<Integer, OwnedThread> e : owned.entrySet()) {
            OwnedThread record = e.getValue();
            try {
                if (!RuntimeThreadRules.mayRestore(record.startTime, readStartTime(e.getKey()),
                        record.applied, Process.getThreadPriority(e.getKey()))) continue;
                int target = shedByThermal ? record.original : Math.min(record.original, Math.max(-2, policy.threadPriority));
                Process.setThreadPriority(e.getKey(), target);
                record.applied = target;
            } catch (Throwable ignored) { }
        }
    }

    private static boolean restore(int tid, OwnedThread record) {
        String identity = readStartTime(tid);
        if (identity != null && !record.startTime.equals(identity)) return true; // recycled
        if (identity == null) return !new File("/proc/self/task/" + tid).exists(); // exited
        try {
            int priority = Process.getThreadPriority(tid);
            if (!RuntimeThreadRules.mayRestore(record.startTime, identity, record.applied, priority)) return true;
            Process.setThreadPriority(tid, record.original);
            record.applied = record.original;
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static String readProc(int tid, String name) {
        try (BufferedReader reader = new BufferedReader(new FileReader(
                new File(new File("/proc/self/task", String.valueOf(tid)), name)))) {
            return reader.readLine();
        } catch (Throwable ignored) { return null; }
    }

    private static String readStartTime(int tid) { return RuntimeThreadRules.startTime(readProc(tid, "stat")); }

    public static String lastEngineNote() {
        return "ADPF: disabled (genuine CPU work timing/membership unavailable) · priority experiment: "
                + (isActive() ? "nice -2 requested; benefit unmeasured" : "off");
    }

    public static boolean isShedActive() {
        SchedulingPolicy s = sActive;
        return s != null && s.shedByThermal;
    }
}
