import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.performance.FrameSampleCollector;
import net.kdt.pojavlaunch.performance.McContext;
import net.kdt.pojavlaunch.performance.PerformanceMode;
import net.kdt.pojavlaunch.performance.PerformancePolicy;
import net.kdt.pojavlaunch.performance.SessionStats;
import net.kdt.pojavlaunch.utils.FrameMetrics;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Executes production sampler code against deterministic Android test doubles.
 * Native linkage deliberately absent: tests late-load retry and lifecycle, not
 * device rendering, scheduler behavior, disk speed, or actual JNI timing data. */
public class MeasurementLifecycleTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object session() throws Exception {
        return field(FrameSampleCollector.class, "session").get(null);
    }
    private static Object value(Object object, String name) throws Exception {
        return field(object.getClass(), name).get(object);
    }
    private static void seedObservedWindows(Object session, int fps) throws Exception {
        // Native linkage is deliberately absent. Seed historical observations
        // only to verify production split/persist bookkeeping and labels.
        Arrays.fill((int[]) value(session, "samples"), fps);
        field(session.getClass(), "size").setInt(session, 10);
        field(session.getClass(), "observedNanos").setLong(session, 31_000_000_000L);
    }
    private static void publishPolicy(String renderer, String version, String mode) {
        PerformancePolicy policy = new PerformancePolicy();
        policy.game = new McContext(renderer, version);
        policy.mode = new PerformanceMode(mode);
        PerformancePolicy.CURRENT = policy;
    }
    public static void main(String[] args) throws Exception {
        Tools.DIR_DATA = args[0];
        Tools.LOCAL_RENDERER = "opengles2";
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

        // Async launch-time fallback while foreground: no lifecycle resume call.
        Tools.LOCAL_RENDERER = "vulkan_zink";
        publishPolicy("vulkan_zink", "test", "normal");
        FrameSampleCollector.begin(ctx, "vulkan_zink", "test");
        Handler.take("CS-FrameStats").run();
        Object requested = session();
        seedObservedWindows(requested, 12);
        Tools.LOCAL_RENDERER = "opengles2"; // loader publication before policy reconciliation
        Handler.take("CS-FrameStats").run();
        check(session() == requested, "inconsistent policy/runtime defers sampling rather than relabeling");
        check(SessionStats.ENTRIES.isEmpty(), "transient fallback state is not persisted prematurely");
        publishPolicy("opengles2", "test", "normal");
        Handler.take("CS-FrameStats").run();
        Object fallback = session();
        check(fallback != requested && "opengles2".equals(value(fallback, "rendererId")), "fallback creates correctly labelled segment");
        check(((Integer) value(fallback, "size")) == 0 && ((Long) value(fallback, "observedNanos")) == 0,
                "fallback window and observed duration rebase, not carry across renderers");
        check(value(fallback, "window") != value(requested, "window"), "fallback owns a fresh counter baseline");
        check(SessionStats.ENTRIES.size() == 1 && "vulkan_zink".equals(SessionStats.ENTRIES.get(0).renderer)
                && SessionStats.ENTRIES.get(0).median == 12, "old observations retain original renderer label");
        check(HandlerThread.liveCount() == 1 && Handler.pendingCount() == 1, "split reuses only active foreground looper");
        seedObservedWindows(fallback, 60);
        publishPolicy("opengles2", "test-v2", "maximum");
        Handler.take("CS-FrameStats").run();
        check("maximum".equals(value(session(), "modeKey")) && "test-v2".equals(value(session(), "versionId")),
                "policy/version publication also splits without resume");
        check(SessionStats.ENTRIES.size() == 2 && "opengles2".equals(SessionStats.ENTRIES.get(1).renderer)
                && "normal".equals(SessionStats.ENTRIES.get(1).mode), "fallback observations persist with actual old mode");
        FrameSampleCollector.pause();
        Object paused = session();
        Tools.LOCAL_RENDERER = "vulkan_zink";
        publishPolicy("vulkan_zink", "test-v2", "maximum");
        check(session() == paused && HandlerThread.liveCount() == 0 && Handler.pendingCount() == 0,
                "background metadata change starts no reconciliation polling");
        FrameSampleCollector.finishAndClear(ctx);
        PerformancePolicy.CURRENT = null;
        Tools.LOCAL_RENDERER = null;
        FrameSampleCollector.begin(ctx, null, null);
        Handler.take("CS-FrameStats").run();
        Object unresolved = session();
        Tools.LOCAL_RENDERER = "opengles2";
        publishPolicy("opengles2", "loaded-version", "normal");
        Handler.take("CS-FrameStats").run();
        check(session() != unresolved && "opengles2".equals(value(session(), "rendererId"))
                && "loaded-version".equals(value(session(), "versionId")), "null launch metadata becomes actual renderer/version without resume");
        check(SessionStats.ENTRIES.size() == 2, "unobserved null-metadata segment is not persisted");
        FrameSampleCollector.finishAndClear(ctx);
        PerformancePolicy.CURRENT = null;
        LauncherPreferences.DEFAULT_PREF.diagnostics = false;
        FrameSampleCollector.begin(ctx, "vulkan_zink", "test-v2");
        check(session() == null && HandlerThread.liveCount() == 0, "default-off remains inert after fallback");
        System.out.println("PASS measurement-lifecycle: default-off, adb/preference opt-in, late JNI retry, unknown coverage, pause thread shutdown, opt-out, stale callbacks, 100 concurrent pause/tick races, foreground fallback label/split/rebase/persistence regression (Android test doubles)");
    }
}
