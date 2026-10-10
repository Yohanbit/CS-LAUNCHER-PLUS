import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.Round3WatcherCaller;
import net.kdt.pojavlaunch.utils.FpsCounter;

/** Runs production MainActivity watcher adapter + production helper + JNI wrapper. */
public final class Round3WatcherLifecycleTest {
    private static native void configure(int coverage, long total);
    private static native void changeCoverageOnTotal(int coverage);
    private static native int coverageReads();
    private static native int totalReads();

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static void empty(String label) {
        check(Tools.MAIN_HANDLER.callbacks.isEmpty(), label);
    }
    private static Round3WatcherCaller create(int coverage, long total) {
        Tools.MAIN_HANDLER.clear(); configure(coverage, total);
        Round3WatcherCaller activity = new Round3WatcherCaller();
        activity.onCreate();
        empty("onCreate cannot post/read before foreground");
        check(coverageReads() == 0 && totalReads() == 0, "onCreate zero JNI reads");
        return activity;
    }
    private static void notObserved(Round3WatcherCaller activity, String label) {
        check(activity.mBootLog.visibleCalls == 0 && activity.mGameStateController.playingCalls == 0, label);
    }
    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--legacy")) {
            System.load(args[1]);
            configure(1, 5L);
            check(FpsCounter.getTotalPresents() == 5L, "legacy total JNI still links");
            check(FpsCounter.getCounterCoverage() == FpsCounter.COVERAGE_UNREADY,
                    "old JNI library without coverage cannot certify GL");
            Round3WatcherCaller legacy = create(1, 5L);
            legacy.onResume(); Tools.MAIN_HANDLER.runNext();
            check(totalReads() == 0, "legacy watcher skips uncertified total");
            notObserved(legacy, "legacy coverage never invents a first frame");
            Runnable stale = Tools.MAIN_HANDLER.take(); legacy.onPause(); stale.run();
            empty("legacy background run stops"); legacy.onDestroy();
            System.out.println("PASS round3 watcher legacy JNI: old total entry point preserved, missing coverage stays unready, pause cancels fallback retry");
            return;
        }
        // Missing optional JNI linkage must not certify coverage or throw.
        check(FpsCounter.getCounterCoverage() == FpsCounter.COVERAGE_UNREADY, "unlinked coverage fallback");
        check(FpsCounter.getTotalPresents() == -1L, "unlinked total contract");
        System.load(args[0]);
        configure(99, 100L);
        check(FpsCounter.getCounterCoverage() == FpsCounter.COVERAGE_UNREADY, "invalid native enum fallback");

        Round3WatcherCaller a = create(0, 0);
        a.onResume(); a.onResume();
        check(Tools.MAIN_HANDLER.callbacks.size() == 1, "duplicate resume one callback");
        Tools.MAIN_HANDLER.runNext();
        check(totalReads() == 0, "unready skips cumulative JNI reads");
        check(Tools.MAIN_HANDLER.delays.get(0) == 250L, "initial unready delay");
        for (int i = 0; i < 1000; i++) Tools.MAIN_HANDLER.runNext();
        check(Tools.MAIN_HANDLER.delays.get(0) == 2000L, "bounded unready backoff, no boot deadline");
        notObserved(a, "unready is never an invented first frame");
        configure(1, 0);
        Tools.MAIN_HANDLER.runNext();
        check(Tools.MAIN_HANDLER.delays.get(0) == 250L, "counted GL warmup cadence restored");
        configure(1, -1);
        Tools.MAIN_HANDLER.runNext(); notObserved(a, "transient total unavailable");
        configure(1, 0);
        Tools.MAIN_HANDLER.runNext(); notObserved(a, "reset/zero never counted as frame");
        configure(1, 1);
        Tools.MAIN_HANDLER.runNext(); empty("successful GL observation one-shot");
        check(a.mGameStateController.state.equals("playing") && a.mBootLog.visibleCalls == 1,
                "late GL first submission preserves game state and overlay callback");
        int reads = coverageReads();
        a.onPause(); a.onStop(); a.onResume();
        empty("observed latch needs no further watcher polling");
        check(a.mGameStateController.state.equals("playing") && coverageReads() == reads,
                "observed resume restores state without JNI");
        a.onDestroy();

        a = create(2, 500); a.onResume();
        Runnable unsupportedCallback = Tools.MAIN_HANDLER.take(); unsupportedCallback.run();
        empty("explicit native Vulkan terminates run");
        check(totalReads() == 0, "unsupported Vulkan never reads stale GL totals");
        notObserved(a, "Vulkan total must not invent first frame");
        reads = coverageReads();
        a.onResume(); unsupportedCallback.run();
        empty("duplicate resume/stale unsupported callback cannot restart run");
        check(coverageReads() == reads, "no lingering Vulkan JNI polling");
        // A new GL hint has no fake notification. Recover only on the next
        // real foreground boundary, and do not demand an additional swap.
        configure(1, 1); a.onResume(); empty("hint change alone does not restart terminal run");
        a.onPause(); a.onStop(); a.onResume(); Tools.MAIN_HANDLER.runNext();
        check(a.mBootLog.visibleCalls == 1, "Vulkan -> GL recovered next foreground");
        a.onDestroy();

        a = create(1, 42); changeCoverageOnTotal(2); a.onResume(); Tools.MAIN_HANDLER.runNext();
        empty("NO_API hint changed during read terminates run");
        notObserved(a, "old GL count not attributed to native Vulkan");
        a.onPause(); configure(1, 0); a.onResume(); Tools.MAIN_HANDLER.runNext();
        notObserved(a, "new counted window reset warms without invented frame");
        configure(1, 1); Tools.MAIN_HANDLER.runNext();
        check(a.mBootLog.visibleCalls == 1, "reset recovers with actual positive cumulative count");
        a.onDestroy();

        a = create(1, 3); changeCoverageOnTotal(0); a.onResume(); Tools.MAIN_HANDLER.runNext();
        notObserved(a, "coverage lost during read cannot certify old total");
        a.onDestroy(); empty("destroy cancels unready retry");

        a = create(1, 1); a.finishing = true; a.onResume();
        empty("finishing Activity does not start"); check(coverageReads() == 0, "finishing zero JNI");
        a.onDestroy();
        a = create(1, 1); a.onResume(); a.destroyed = true; Tools.MAIN_HANDLER.runNext();
        empty("destroyed host terminates callback"); check(coverageReads() == 0, "destroyed zero JNI");
        a.onDestroy();

        a = create(1, 0); Tools.MAIN_HANDLER.rejectNext = true; a.onResume();
        empty("rejected initial post has no false active run"); a.onResume();
        check(Tools.MAIN_HANDLER.callbacks.size() == 1, "initial-post failure retries cleanly");
        Tools.MAIN_HANDLER.rejectNext = true; Tools.MAIN_HANDLER.runNext();
        empty("rejected retry post tears down run"); a.onResume();
        check(Tools.MAIN_HANDLER.callbacks.size() == 1, "retry-post failure retries cleanly");
        a.onDestroy(); a.onResume(); empty("release is permanent");

        // Full actual caller sequence, with queued callbacks deliberately held
        // across pause/resume/destroy: generation guards forbid background work
        // or duplicate foreground loops even if removeCallbacks was too late.
        for (int i = 0; i < 100; i++) {
            a = create(1, 0); a.onResume();
            Runnable stale = Tools.MAIN_HANDLER.take();
            a.onPause(); a.onStop(); configure(1, 1); stale.run();
            empty("paused stale callback does not repost");
            check(coverageReads() == 0 && totalReads() == 0, "paused stale callback zero JNI");
            check(a.mGameStateController.state.equals("paused"), "paused state never overwritten");
            a.onResume(); stale.run();
            check(Tools.MAIN_HANDLER.callbacks.size() == 1, "old generation cannot duplicate resume loop");
            check(coverageReads() == 0, "old generation cannot read during new resume");
            Runnable current = Tools.MAIN_HANDLER.take();
            a.onDestroy(); current.run();
            empty("destroyed stale callback cannot repost");
            check(coverageReads() == 0 && totalReads() == 0, "destroyed stale callback zero JNI");
            notObserved(a, "full sequence has no false visibility callback");
        }
        System.out.println("PASS round3 watcher: production wiring/helper/JNI wrapper, GL warmup, reset, Vulkan unknown, hint transitions, post failures, 100 full lifecycle stale-callback sequences");
    }
}
