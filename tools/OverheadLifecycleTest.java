import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.performance.FrameSampleCollector;
import net.kdt.pojavlaunch.performance.PerformancePolicy;
import net.kdt.pojavlaunch.performance.SchedulingPolicy;
import net.kdt.pojavlaunch.performance.ThermalGovernor;
import net.kdt.pojavlaunch.utils.FrameMetrics;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/** Runs production lifecycle code; Android doubles and a JNI call spy, not a device benchmark. */
public class OverheadLifecycleTest {
    private static native int[] calls();
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class ? null : target);
    }
    private static void idle(String message) {
        check(HandlerThread.liveCount() == 0 && Handler.pendingCount() == 0, message);
    }
    private static void begin(Context ctx, PerformancePolicy policy) {
        SchedulingPolicy.begin(ctx, policy); ThermalGovernor.start(ctx, policy);
    }
    private static void end() { ThermalGovernor.stop(); SchedulingPolicy.end(); }
    private static void failStart(int phase) {
        if (phase == 0) HandlerThread.failNextStart = true;
        if (phase == 1) Handler.failNextConstruction = true;
        if (phase == 2) Handler.rejectNextPost = true;
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); Tools.DIR_DATA = args[1]; Tools.LOCAL_RENDERER = "opengles2";
        Context ctx = new Context();
        PerformancePolicy policy = new PerformancePolicy();
        begin(ctx, policy);
        idle("normal/default policy has no scheduler or thermal heartbeat");
        policy.thermalPolling = true;
        int created = HandlerThread.ALL.size();
        Build.VERSION.SDK_INT = 28;
        begin(ctx, policy);
        idle("API28 observe-only requests must not create UNKNOWN-only polling");
        check(HandlerThread.ALL.size() == created && field(ThermalGovernor.class, "sTick") == null,
                "unsupported observe-only path has no thread allocation or false sampling marker");
        Build.VERSION.SDK_INT = 30;
        android.os.PowerManager pm = ctx.power; ctx.power = null;
        begin(ctx, policy);
        idle("missing power service creates no observe-only polling");
        ctx.power = pm;
        begin(ctx, policy);
        check(HandlerThread.liveCount() == 1 && Handler.pendingCount() == 1,
                "supported thermal observation keeps exactly one foreground heartbeat");
        check(pm.headroomReads == 1 && pm.statusReads == 1, "initial thermal read remains synchronous");
        int[] listenerCalls = {0};
        Runnable listener = () -> listenerCalls[0]++;
        SchedulingPolicy.addTickListener(listener); SchedulingPolicy.addTickListener(listener);
        Object scheduler = field(SchedulingPolicy.class, "sActive");
        Object listeners = field(scheduler, "tickListeners");
        for (int i = 1; i <= 9; i++) {
            SystemClock.now = i * 1000L;
            Handler.take("CS-PerfEngine").run();
            check(field(scheduler, "tickListeners") == listeners, "steady heartbeat reuses immutable listener array");
        }
        check(listenerCalls[0] == 9, "duplicate listener registration calls only once per tick");
        check(pm.headroomReads == 1, "headroom still respects minimum 10s interval");
        SchedulingPolicy.removeTickListener(listener);
        check(field(scheduler, "tickListeners") != listeners, "listener removal publishes new snapshot");
        SystemClock.now = 10_000L; Handler.take("CS-PerfEngine").run();
        check(listenerCalls[0] == 9 && pm.headroomReads == 2, "removed listener stays absent; 10s headroom read retained");
        Runnable stale = Handler.take("CS-PerfEngine"); end();
        idle("background destroys scheduler heartbeat");
        begin(ctx, policy); int queued = Handler.pendingCount(); stale.run();
        check(Handler.pendingCount() == queued, "stale scheduler callback cannot join restarted run");
        end();
        policy.boostGameThreads = true; Build.VERSION.SDK_INT = 28;
        begin(ctx, policy);
        check(HandlerThread.liveCount() == 1 && SchedulingPolicy.isActive(),
                "unsupported thermal API does not suppress explicit priority experiments");
        end(); policy.boostGameThreads = false; Build.VERSION.SDK_INT = 30;

        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < 100; i++) {
            begin(ctx, policy); Runnable callback = Handler.take("CS-PerfEngine");
            Thread worker = new Thread(() -> {
                try { callback.run(); } catch (Throwable error) { failure.set(error); }
            });
            worker.start(); end(); worker.join();
            idle("scheduler callback/end race cannot resurrect polling");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        for (int phase = 0; phase < 3; phase++) {
            failStart(phase); begin(ctx, policy);
            idle("scheduler partial startup cleans up phase " + phase);
            check(field(SchedulingPolicy.class, "sActive") == null, "failed scheduler is not retained");
            check(field(ThermalGovernor.class, "sTick") == null
                    && ThermalGovernor.describe(ctx).contains("not sampling"),
                    "failed scheduler cannot retain a false thermal sampling marker");
            begin(ctx, policy);
            check(HandlerThread.liveCount() == 1 && Handler.pendingCount() == 1, "scheduler retries cleanly after failure");
            end(); idle("scheduler retry teardown");
        }
        System.out.println("PASS overhead-scheduler: default/unsupported/no-service idle, public thermal cadence, copy-on-registration listeners, duplicate/remove/stale callbacks, 100 stop races, explicit experiments preserved");

        FrameMetrics.start(ctx); FrameMetrics.stop(); FrameMetrics.stop();
        check(calls()[0] == 0 && calls()[1] == 0, "default-off/repeated stop makes zero JNI timing calls");
        LauncherPreferences.DEFAULT_PREF.diagnostics = true;
        FrameMetrics.start(ctx); FrameMetrics.stop();
        check(calls()[0] == 0 && calls()[1] == 0, "stop before first sample revokes no unowned native epoch");
        FrameMetrics.start(ctx); Handler.take("CSFrameDiagnostics").run();
        Handler.take("CSFrameDiagnostics").run();
        check(calls()[0] == 1 && calls()[2] == 2, "opt-in run enables once and retains snapshot polling");
        Runnable oldMetrics = Handler.take("CSFrameDiagnostics");
        FrameMetrics.stop(); FrameMetrics.stop();
        check(calls()[1] == 1, "enabled run is disabled exactly once");
        FrameMetrics.start(ctx); queued = Handler.pendingCount(); oldMetrics.run();
        check(calls()[0] == 1 && Handler.pendingCount() == queued, "old diagnostics callback cannot enable/repost");
        Handler.take("CSFrameDiagnostics").run();
        LauncherPreferences.DEFAULT_PREF.diagnostics = false;
        Handler.take("CSFrameDiagnostics").run();
        check(calls()[0] == 2 && calls()[1] == 2, "live opt-out releases native timing once");
        idle("diagnostic opt-out destroys looper");
        System.out.println("PASS overhead-native-gating: default-off/pre-first-tick/repeated stops perform no JNI timing writes; enabled run and opt-out revoke exactly once; stale callbacks inert (JNI spy)");

        LauncherPreferences.DEFAULT_PREF.diagnostics = true;
        int enableBefore = calls()[0], disableBefore = calls()[1];
        for (int phase = 0; phase < 3; phase++) {
            failStart(phase); FrameSampleCollector.begin(ctx, "opengles2", "test");
            idle("collector partial startup cleans up phase " + phase);
            check(field(FrameSampleCollector.class, "active") == null, "failed collector is not retained");
            FrameSampleCollector.begin(ctx, "opengles2", "test");
            check(HandlerThread.liveCount() == 1 && Handler.pendingCount() == 1, "collector retries cleanly after failure");
            FrameSampleCollector.finishAndClear(ctx); idle("collector retry teardown");
            failStart(phase); FrameMetrics.start(ctx);
            idle("metrics partial startup cleans up phase " + phase);
            check(field(FrameMetrics.class, "active") == null, "failed metrics run is not retained");
            FrameMetrics.start(ctx);
            check(HandlerThread.liveCount() == 1 && Handler.pendingCount() == 1, "metrics retries cleanly after failure");
            FrameMetrics.stop(); idle("metrics retry teardown");
        }
        check(calls()[0] == enableBefore && calls()[1] == disableBefore,
                "startup failures and unstarted retries cannot alter native timing");
        LauncherPreferences.DEFAULT_PREF.diagnostics = false;
        System.out.println("PASS overhead-startup: production scheduler and both samplers recover from thread-start/handler-construction/post rejection without live loops or unowned timing writes");
    }
}
