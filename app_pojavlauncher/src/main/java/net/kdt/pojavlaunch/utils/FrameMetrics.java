package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import java.io.*;

/** Optional one-second sampler of native swap work, not GPU time or physical presentation. */
public final class FrameMetrics {
    private static HandlerThread thread;
    private static Handler handler;
    private static final long[] snapshot = new long[11];
    private static long previousCount, previousSwap, previousGap, previousGapSamples, previousTime;
    private static BufferedWriter output;
    private static PowerManager power;
    private static native boolean nativeReadSnapshot(long[] values);
    private static boolean readNativeSnapshot() {
        // Activity resume can precede loading the launcher JNI library. Retry on the
        // next sampling tick rather than permanently disabling the selected diagnostic.
        try { return nativeReadSnapshot(snapshot); }
        catch (UnsatisfiedLinkError notLoadedYet) { return false; }
    }
    private FrameMetrics() { }
    public static synchronized void start(Context context) {
        if (thread != null || !LauncherPreferences.DEFAULT_PREF.getBoolean("extremeFrameDiagnostics", false)) return;
        try {
            File directory = new File(Tools.DIR_DATA, "performance");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create diagnostics directory");
            output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(directory,
                    "frames-" + System.currentTimeMillis() + ".csv")), "UTF-8"));
            output.write("elapsed_ms,successful_swap_submissions,submission_fps,avg_swap_cpu_wall_ms,avg_between_swap_wall_ms,last_swap_ms,max_swap_ms,failed_swaps,native_heap_bytes,art_heap_bytes,thermal_status,scale_percent\n");
            output.flush();
            power = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            previousCount = previousSwap = previousGap = previousGapSamples = previousTime = 0;
            thread = new HandlerThread("CSFrameDiagnostics", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start(); handler = new Handler(thread.getLooper()); handler.post(sample);
        } catch (Throwable error) { Log.w("CSPerf", "Diagnostics unavailable", error); stop(); }
    }
    private static final Runnable sample = new Runnable() { @Override public void run() {
        synchronized (FrameMetrics.class) {
            if (handler == null || output == null) return;
            try {
                if (readNativeSnapshot()) {
                    long time = SystemClock.elapsedRealtime();
                    long count = snapshot[1], delta = Math.max(0, count - previousCount);
                    long gaps = Math.max(0, snapshot[7] - previousGapSamples);
                    if (previousTime != 0 && time > previousTime) {
                        double fps = delta * 1000.0 / (time - previousTime);
                        double swap = delta > 0 ? Math.max(0, snapshot[3]-previousSwap) / (delta * 1e6) : 0;
                        double gap = gaps > 0 ? Math.max(0, snapshot[6]-previousGap) / (gaps * 1e6) : 0;
                        int thermal = Build.VERSION.SDK_INT >= 29 && power != null ? power.getCurrentThermalStatus() : -1;
                        long art = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                        output.write(time + "," + count + "," + fps + "," + swap + "," + gap + ","
                                + snapshot[4]/1e6 + "," + snapshot[5]/1e6 + "," + snapshot[2] + ","
                                + Debug.getNativeHeapAllocatedSize() + "," + art + "," + thermal + ","
                                + LauncherPreferences.PREF_SCALE_FACTOR + "\n");
                        output.flush();
                    }
                    previousCount=count; previousSwap=snapshot[3]; previousGap=snapshot[6];
                    previousGapSamples=snapshot[7]; previousTime=time;
                }
            } catch (Throwable error) { Log.w("CSPerf", "Frame sampling stopped", error); stop(); return; }
            if (handler != null) handler.postDelayed(this, 1000);
        }
    }};
    public static synchronized void stop() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        handler = null;
        if (output != null) try { output.close(); } catch (IOException ignored) { }
        output = null;
        if (thread != null) thread.quitSafely();
        thread = null; power = null;
    }
}
