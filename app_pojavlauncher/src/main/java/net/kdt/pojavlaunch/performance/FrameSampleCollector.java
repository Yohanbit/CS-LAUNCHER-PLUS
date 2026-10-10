package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FpsCounter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Opt-in foreground submission-rate windows; not GPU/presentation timings,
 * render-loop percentiles, 1% lows, or controlled comparisons between modes.
 * Zero windows are retained. Native Vulkan coverage is unknown, not zero.
 * Pause destroys the looper and resets the window but retains collected data.
 * Foreground ticks reconcile async renderer/policy changes into separate segments. */
public final class FrameSampleCollector {
    private static final int WINDOW_SECONDS = 3600;
    public static final String HARNESS_TAG = "CSPerfFps";
    private static Session session;
    private static SamplingRun active;
    private FrameSampleCollector() { }

    private static boolean traceEnabled() { return Log.isLoggable(HARNESS_TAG, Log.DEBUG); }
    private static boolean enabled() {
        return traceEnabled() || (LauncherPreferences.DEFAULT_PREF != null
                && LauncherPreferences.DEFAULT_PREF.getBoolean("extremeFrameDiagnostics", false));
    }

    private static final class Session {
        final long startedAt = System.currentTimeMillis(); // timestamp only
        final String rendererId, modeKey, versionId;
        final FpsCounter.Window window = new FpsCounter.Window(1_000_000_000L);
        final int[] samples = new int[WINDOW_SECONDS];
        int size, next;
        int peakThermalLevel;
        long observedNanos;
        Session(String rendererId, String modeKey, String versionId) {
            this.rendererId = rendererId; this.modeKey = modeKey; this.versionId = versionId;
        }
        void add(int fps, long elapsedNanos) {
            samples[next] = fps;
            next = (next + 1) % samples.length;
            if (size < samples.length) size++;
            observedNanos += elapsedNanos;
        }
        int[] sortedSamples() {
            int[] copy = Arrays.copyOf(samples, size);
            Arrays.sort(copy);
            return copy;
        }
    }

    /** No thread, loop, file or sample allocations unless diagnostics or adb tracing is enabled. */
    public static synchronized void begin(Context ctx, String rendererId, String versionId) {
        if (ctx == null || !enabled()) { pause(); return; }
        PerformancePolicy policy = PerformancePolicy.current();
        String mode = policy != null ? policy.mode.key : PerformanceMode.NORMAL.key;
        if (session != null && (!Objects.equals(session.rendererId, rendererId)
                || !Objects.equals(session.modeKey, mode) || !Objects.equals(session.versionId, versionId))) {
            pause(); persist(ctx, session); session = null;
        }
        if (session == null) session = new Session(rendererId, mode, versionId);
        if (active != null) return;
        session.window.reset();
        SamplingRun run = new SamplingRun(ctx.getApplicationContext(), session);
        active = run;
        try {
            run.thread.start();
            run.handler = new Handler(run.thread.getLooper());
            if (!run.handler.post(run)) throw new IllegalStateException("Diagnostics looper rejected start");
        } catch (Exception unavailable) {
            // A partial start must not retain a thread or block the next resume.
            // Preserve any earlier observations, just as an ordinary pause does.
            pause();
            Log.w("CSPerf", "Submission sampling unavailable", unavailable);
        }
    }

    /** Cancel/revoke old callbacks and quit the thread; no heartbeat remains in the background. */
    public static synchronized void pause() {
        SamplingRun run = active;
        active = null;
        // Capture before PerformanceEngine stops the governor or the next resume resets its peak.
        if (run != null && session == run.owner) {
            session.peakThermalLevel = Math.max(session.peakThermalLevel,
                    ThermalGovernor.peakLevelOrdinal());
        }
        if (session != null) session.window.reset();
        if (run != null) {
            if (run.handler != null) run.handler.removeCallbacksAndMessages(null);
            run.thread.quitSafely();
        }
    }

    public static synchronized void finishAndClear(Context ctx) {
        pause();
        Session finished = session;
        session = null;
        if (finished != null) persist(ctx, finished);
    }

    /** Descriptive median of observed windows, including zeros; -1 if unavailable. */
    public static synchronized int liveMedianFps() {
        if (session == null || session.size == 0) return -1;
        int[] copy = session.sortedSamples();
        return copy[copy.length / 2];
    }

    private static final class SamplingRun implements Runnable {
        final Context context;
        Session owner;
        final HandlerThread thread = new HandlerThread("CS-FrameStats", Process.THREAD_PRIORITY_BACKGROUND);
        Handler handler;
        SamplingRun(Context context, Session owner) { this.context = context; this.owner = owner; }
        private boolean reconcileLabels(PerformancePolicy policy, String renderer) {
            // The async native loader publishes LOCAL_RENDERER, then reconciles
            // the volatile policy. Do not sample in that intermediate state.
            if (policy != null && policy.game != null && !policy.game.rendererId.isEmpty()
                    && !Objects.equals(policy.game.rendererId, renderer)) {
                owner.window.reset();
                return false;
            }
            String mode = policy != null ? policy.mode.key : PerformanceMode.NORMAL.key;
            String version = policy != null && policy.game != null && !policy.game.versionId.isEmpty()
                    ? policy.game.versionId : owner.versionId;
            if (!Objects.equals(owner.rendererId, renderer) || !Objects.equals(owner.modeKey, mode)
                    || !Objects.equals(owner.versionId, version)) {
                // Keep old windows under their original labels; discard the
                // transition window and start a fresh foreground-only baseline.
                persist(context, owner);
                owner = new Session(renderer, mode, version);
                session = owner;
            }
            return true;
        }
        @Override public void run() {
            // One lifecycle lock prevents pause/finish from racing a queued tick
            // or a stale tick attaching itself to a newly created handler.
            synchronized (FrameSampleCollector.class) {
                if (active != this || session != owner) return;
                if (!enabled()) { pause(); return; }
                owner.peakThermalLevel = Math.max(owner.peakThermalLevel,
                        ThermalGovernor.peakLevelOrdinal());
                // Read the volatile policy first so its publication also makes
                // the preceding loader assignment to LOCAL_RENDERER visible.
                PerformancePolicy policy = PerformancePolicy.current();
                String renderer = Tools.LOCAL_RENDERER;
                if (!reconcileLabels(policy, renderer)) {
                    handler.postDelayed(this, 1000L);
                    return;
                }
                int fps = owner.window.getFps();
                // A launch/fallback can finish during the native read. Never
                // retain a window crossing that metadata boundary.
                if (policy != PerformancePolicy.current() || !Objects.equals(renderer, Tools.LOCAL_RENDERER)) {
                    owner.window.reset();
                    handler.postDelayed(this, 1000L);
                    return;
                }
                if (fps >= 0) {
                    owner.add(fps, owner.window.getLastElapsedNanos());
                    if (traceEnabled()) Log.d(HARNESS_TAG, "t=" + owner.observedNanos / 1_000_000_000L
                            + "s fps=" + fps + " presents=" + FpsCounter.getTotalPresents()
                            + " metric=successful_swap_submissions observed_ms=" + owner.observedNanos / 1_000_000L
                            + " mode=" + owner.modeKey + " renderer=" + owner.rendererId);
                } else if (traceEnabled()) {
                    Log.d(HARNESS_TAG, "submission_fps=unknown_or_warming mode=" + owner.modeKey
                            + " renderer=" + owner.rendererId);
                }
                if (active == this) handler.postDelayed(this, 1000L);
            }
        }
    }

    private static void persist(Context ctx, Session finished) {
        // Foreground observed time only; calendar changes / background gaps do
        // not manufacture a long session or a zero-FPS stall on resume.
        long durationMs = finished.observedNanos / 1_000_000L;
        if (ctx == null || finished.size < 10 || durationMs < 30_000L) return;
        int[] copy = finished.sortedSamples();
        int median = copy[copy.length / 2], worst = copy[0];
        // Inverse median rate is NOT measured per-frame CPU/GPU time.
        float inverseMedianRateMs = median > 0 ? 1000f / median : Float.NaN;
        SessionStats.append(ctx, new SessionStats.Entry(finished.startedAt, finished.rendererId,
                finished.modeKey, finished.versionId, median, worst, inverseMedianRateMs,
                durationMs, finished.peakThermalLevel));
        Log.i("CSPerf", "descriptive submission-rate session (" + finished.modeKey + ", "
                + finished.rendererId + ", " + finished.versionId + "): median " + median
                + " fps, lowest sampled window " + worst + " fps, "
                + String.format(Locale.ROOT, "inverse median rate %.1f ms, %d windows, %ds observed foreground",
                inverseMedianRateMs, copy.length, durationMs / 1000L));
    }
}
