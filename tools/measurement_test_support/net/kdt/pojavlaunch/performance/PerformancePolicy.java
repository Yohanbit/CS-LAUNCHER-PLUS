package net.kdt.pojavlaunch.performance;
public class PerformancePolicy {
    public PerformanceMode mode = PerformanceMode.NORMAL;
    public McContext game;
    public static volatile PerformancePolicy CURRENT;
    public static PerformancePolicy current() { return CURRENT; }
}
