package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import java.io.*;

/** Opt-in CPU swap-call wall times, not GPU execution or physical presentation.
 * Each foreground run owns its callbacks/output and a fresh native timing epoch. */
public final class FrameMetrics {
    private static Run active;
    private static native boolean nativeReadSnapshot(long[] values);
    private static native void nativeSetTimingEnabled(boolean enabled);
    private FrameMetrics() { }

    private static boolean enabled() {
        return LauncherPreferences.DEFAULT_PREF != null
                && LauncherPreferences.DEFAULT_PREF.getBoolean("extremeFrameDiagnostics", false);
    }

    public static synchronized void start(Context context) {
        if (context == null || !enabled()) { stop(); return; }
        if (active != null) return;
        try {
            Run run = new Run(context);
            active = run;
            run.thread.start();
            run.handler = new Handler(run.thread.getLooper());
            if (!run.handler.post(run)) throw new IllegalStateException("Diagnostics looper rejected start");
        } catch (Exception error) {
            Log.w("CSPerf", "Diagnostics unavailable", error);
            stop();
        }
    }

    private static final class Run implements Runnable {
        final long[] snapshot = new long[13];
        final long startedNanos = System.nanoTime();
        final HandlerThread thread = new HandlerThread("CSFrameDiagnostics",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        final BufferedWriter output;
        final PowerManager power;
        Handler handler;
        boolean timingEnabled;
        boolean baseline;
        long previousAttempts, previousCount, previousSwap, previousGap, previousGapSamples;
        long previousTime, previousEpoch, previousCoverage;

        Run(Context context) throws IOException {
            power = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            File directory = new File(Tools.DIR_DATA, "performance");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create diagnostics directory");
            }
            output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(directory,
                    "frames-" + System.currentTimeMillis() + "-" + startedNanos + ".csv")), "UTF-8"));
            try {
                output.write("elapsed_ms,coverage,cumulative_successful_submissions,submission_fps,"
                        + "timed_swap_attempts,avg_swap_call_cpu_wall_ms,avg_gap_between_swap_calls_wall_ms,"
                        + "last_swap_call_ms,max_swap_call_ms_in_timing_epoch,unsuccessful_swap_attempts_in_timing_epoch,"
                        + "native_heap_bytes,art_heap_bytes,thermal_status,scale_percent,timing_epoch\n");
                output.flush();
            } catch (IOException error) { output.close(); throw error; }
        }

        @Override public void run() {
            synchronized (FrameMetrics.class) {
                if (active != this) return;
                if (!enabled()) { stop(); return; }
                try {
                    // Activity resume can precede JNI loading. Only enable after
                    // native linkage succeeds; retry next tick without allocating.
                    if (!timingEnabled) {
                        nativeSetTimingEnabled(true);
                        timingEnabled = true;
                    }
                    if (nativeReadSnapshot(snapshot)) sample();
                } catch (UnsatisfiedLinkError notLoadedYet) {
                    // Loading late is expected, not a reason to leave timing on.
                } catch (Exception error) {
                    Log.w("CSPerf", "Frame sampling stopped", error);
                    stop(); return;
                }
                if (active == this) handler.postDelayed(this, 1000L);
            }
        }

        private void sample() throws IOException {
            long time = snapshot[10]; // native CLOCK_MONOTONIC, not calendar time
            long attempts = snapshot[0], count = snapshot[1], epoch = snapshot[11], coverage = snapshot[12];
            boolean valid = baseline && time > previousTime && epoch == previousEpoch
                    && coverage == previousCoverage && count >= previousCount && attempts >= previousAttempts
                    && snapshot[3] >= previousSwap && snapshot[6] >= previousGap
                    && snapshot[7] >= previousGapSamples;
            if (valid) {
                long timedAttempts = attempts - previousAttempts;
                long gaps = snapshot[7] - previousGapSamples;
                double fps = coverage == 0 ? Double.NaN
                        : (count - previousCount) * (1e9 / (time - previousTime));
                // Totals include failed attempts: denominator is attempts, NOT
                // successful submissions. Missing timing is NaN, not zero cost.
                double swap = timedAttempts > 0 ? (snapshot[3] - previousSwap) / (timedAttempts * 1e6) : Double.NaN;
                double gap = gaps > 0 ? (snapshot[6] - previousGap) / (gaps * 1e6) : Double.NaN;
                int thermal = Build.VERSION.SDK_INT >= 29 && power != null ? power.getCurrentThermalStatus() : -1;
                long art = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                String coverageName = coverage == 1 ? "egl_window_submission"
                        : coverage == 2 ? "osmesa_native_window_post" : "unknown_uninstrumented";
                output.write(((System.nanoTime() - startedNanos) / 1_000_000L) + "," + coverageName
                        + "," + (coverage == 0 ? -1 : count) + "," + fps + "," + timedAttempts + "," + swap
                        + "," + gap + "," + (attempts > 0 ? snapshot[4] / 1e6 : Double.NaN)
                        + "," + (attempts > 0 ? snapshot[5] / 1e6 : Double.NaN) + "," + snapshot[2] + ","
                        + Debug.getNativeHeapAllocatedSize() + "," + art + "," + thermal + ","
                        + LauncherPreferences.PREF_SCALE_FACTOR + "," + epoch + "\n");
                output.flush();
            }
            previousAttempts = attempts; previousCount = count; previousSwap = snapshot[3];
            previousGap = snapshot[6]; previousGapSamples = snapshot[7]; previousTime = time;
            previousEpoch = epoch; previousCoverage = coverage; baseline = true;
        }
    }

    public static synchronized void stop() {
        Run run = active;
        active = null; // invalidates even a callback already dequeued by an old looper
        if (run == null) return;
        // Default-off and duplicate lifecycle stops do not touch JNI at all.
        // A run which has not linked/enabled timing owns no native epoch to revoke.
        if (run.timingEnabled) {
            try { nativeSetTimingEnabled(false); } catch (UnsatisfiedLinkError notLoadedYet) { }
        }
        if (run.handler != null) run.handler.removeCallbacksAndMessages(null);
        run.thread.quitSafely();
        try { run.output.close(); } catch (IOException ignored) { }
    }
}
