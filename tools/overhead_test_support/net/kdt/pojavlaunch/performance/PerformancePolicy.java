package net.kdt.pojavlaunch.performance;
/** Policy inputs only; the test compiles/executes the real lifecycle implementations. */
public class PerformancePolicy {
    public PerformanceMode mode = PerformanceMode.NORMAL;
    public McContext game;
    public boolean boostGameThreads, thermalPolling, thermalShedAllowed;
    public int maxBoostedThreads = 2, threadPriority = -2;
    public static volatile PerformancePolicy CURRENT;
    public static PerformancePolicy current() { return CURRENT; }
}
