import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.performance.FrameSampleCollector;
import net.kdt.pojavlaunch.utils.FrameMetrics;
import java.util.concurrent.atomic.AtomicReference;

/** Executes production sampler code against deterministic Android test doubles.
 * Native linkage deliberately absent: tests late-load retry and lifecycle, not
 * device rendering, scheduler behavior, disk speed, or actual JNI timing data. */
public class MeasurementLifecycleTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Tools.DIR_DATA = args[0];
        Context ctx = new Context();
        FrameSampleCollector.begin(ctx, "opengles2", "test");
        FrameMetrics.start(ctx);
        check(HandlerThread.ALL.isEmpty(), "default-off allocates no diagnostics thread");
        check(Handler.pendingCount() == 0, "default-off queues no polling");

        Log.trace = true;
        FrameSampleCollector.begin(ctx, "opengles2", "test");
        check(HandlerThread.liveCount() == 1, "adb trace opts in only collector");
        Handler.take("CS-FrameStats").run();
        check(FrameSampleCollector.liveMedianFps() == -1, "native absent is unknown, not zero");
        Runnable stale = Handler.take("CS-FrameStats");
        FrameSampleCollector.pause();
        check(HandlerThread.liveCount() == 0 && Handler.pendingCount() == 0, "pause quits thread");
        FrameSampleCollector.begin(ctx, "opengles2", "test");
        int pending = Handler.pendingCount();
        stale.run();
        check(Handler.pendingCount() == pending, "old callback cannot repost on new looper");
        Log.trace = false;
        Handler.take("CS-FrameStats").run();
        check(HandlerThread.liveCount() == 0 && Handler.pendingCount() == 0, "opt-out revokes heartbeat");

        LauncherPreferences.DEFAULT_PREF.diagnostics = true;
        FrameSampleCollector.begin(ctx, "opengles2", "test");
        FrameMetrics.start(ctx);
        check(HandlerThread.liveCount() == 2, "preference opts in both samplers");
        Handler.take("CSFrameDiagnostics").run();
        check(Handler.pendingCount() == 2, "late JNI load retries without killing loop");
        Runnable oldDiagnostic = Handler.take("CSFrameDiagnostics");
        FrameMetrics.stop(); FrameMetrics.start(ctx);
        pending = Handler.pendingCount();
        oldDiagnostic.run();
        check(Handler.pendingCount() == pending, "old diagnostic cannot write/repost into new run");
        FrameMetrics.stop(); FrameSampleCollector.finishAndClear(ctx);
        check(HandlerThread.liveCount() == 0 && Handler.pendingCount() == 0, "finish shuts down every loop");

        // A dequeued callback racing pause must not resurrect the heartbeat.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int n = 0; n < 100; n++) {
            FrameSampleCollector.begin(ctx, "opengles2", "test");
            Runnable callback = Handler.take("CS-FrameStats");
            Thread worker = new Thread(() -> {
                try { callback.run(); } catch (Throwable t) { failure.set(t); }
            });
            worker.start(); FrameSampleCollector.pause(); worker.join();
            check(HandlerThread.liveCount() == 0 && Handler.pendingCount() == 0, "pause/callback race leak");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        FrameSampleCollector.finishAndClear(ctx);
        System.out.println("PASS measurement-lifecycle: default-off, adb/preference opt-in, late JNI retry, unknown coverage, pause thread shutdown, opt-out, stale callbacks, 100 concurrent pause/tick races (Android test doubles)");
    }
}
