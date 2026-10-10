package net.kdt.pojavlaunch.performance;
public class PerformanceMode {
    public static final PerformanceMode NORMAL = new PerformanceMode("normal");
    public final String key;
    public PerformanceMode(String key) { this.key = key; }
}
