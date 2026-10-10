package net.kdt.pojavlaunch.utils;

/** Independent, monotonic-time rate window over a non-destructive cumulative counter.
 * No Android/native dependency, allocations, wall-clock assumptions, or arbitrary FPS cap.
 * NaN means unavailable/warming/too soon, whereas a measured stall is exactly 0. */
public final class FrameCounterWindow {
    private final long minimumNanos;
    private long previousCount = -1;
    private long previousNanos;
    private long lastElapsedNanos;

    public FrameCounterWindow(long minimumNanos) {
        if (minimumNanos <= 0) throw new IllegalArgumentException("Positive window required");
        this.minimumNanos = minimumNanos;
    }

    public synchronized void reset() {
        previousCount = -1;
        previousNanos = lastElapsedNanos = 0;
    }

    public synchronized double sample(long count, long monotonicNanos) {
        lastElapsedNanos = 0;
        if (count < 0) {
            reset();
            return Double.NaN;
        }
        if (previousCount < 0 || count < previousCount || monotonicNanos < previousNanos) {
            previousCount = count;
            previousNanos = monotonicNanos;
            return Double.NaN;
        }
        long elapsed = monotonicNanos - previousNanos;
        if (elapsed < minimumNanos) return Double.NaN;
        double rate = (count - previousCount) * (1_000_000_000.0 / elapsed);
        previousCount = count;
        previousNanos = monotonicNanos;
        lastElapsedNanos = elapsed;
        return rate;
    }

    /** Valid window duration, or zero if the latest call produced no sample. */
    public synchronized long getLastElapsedNanos() { return lastElapsedNanos; }
}
