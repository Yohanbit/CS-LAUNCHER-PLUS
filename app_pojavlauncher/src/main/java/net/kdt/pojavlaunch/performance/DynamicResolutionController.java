package net.kdt.pojavlaunch.performance;

/**
 * Opt-in framebuffer policy, not GPU-bottleneck detection or an FPS limiter.
 * A slow submission rate permits one small, reversible probe. Further quality loss requires
 * a measurable response to that probe; CPU/driver/pacing stalls must not walk down to the floor.
 * The caller supplies independent, fresh cumulative-swap windows and known pacing limits.
 */
public final class DynamicResolutionController {
    private static final long COOLDOWN_MS = 5000L;
    private static final long UNRESPONSIVE_RETRY_MS = 60000L;
    private float scale = 1f;
    private float ceiling = 1f;
    private float floor = 0.6f;
    private double target = 60;
    private int lowSamples, highSamples;
    private double lowFpsTotal;
    private final double[] lowWindow = new double[5];
    private int lowWindowIndex;
    private long lastResizeAt, lastSampleAt = -1L, retryProbeAt;
    private boolean probing;
    private float probeOriginalScale;
    private double probeBaselineFps, probeFpsTotal;
    private int probeSamples, settlingSamples;

    public void configure(float originalScale, int minimumPercent, int targetFps, long now) {
        configure(originalScale, minimumPercent, (double) targetFps, now);
    }

    public void configure(float originalScale, int minimumPercent, double targetFps, long now) {
        ceiling = validScale(originalScale);
        // Never raise an already manually reduced resolution to the configured minimum.
        floor = Math.min(ceiling, Math.max(25, Math.min(100, minimumPercent)) / 100f);
        // A known manual limit can be below the normal 15 FPS control-target minimum.
        target = finitePositive(targetFps) ? Math.max(1, Math.min(1000, targetFps)) : 60;
        scale = ceiling;
        clearHistory();
        probing = false;
        retryProbeAt = 0L;
        lastSampleAt = -1L;
        lastResizeAt = now;
    }

    /** Unknown limits are ignored. Screen refresh constrains the target only with known VSync. */
    public static double effectiveTarget(int requested, int knownManualLimit, boolean vsync,
                                         double actualRefreshHz) {
        double result = Math.max(15, Math.min(1000, requested));
        if (knownManualLimit > 0) result = Math.min(result, knownManualLimit);
        if (vsync && finitePositive(actualRefreshHz)) result = Math.min(result, actualRefreshHz);
        return Math.max(1, result);
    }

    private static boolean finitePositive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0;
    }

    public static float validScale(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 1f : Math.max(0.25f, Math.min(1f, value));
    }

    public float getScale() { return scale; }
    public double getTargetFps() { return target; }
    public boolean isProbing() { return probing; }

    private void clearHistory() {
        lowSamples = highSamples = 0;
        lowFpsTotal = 0;
        lowWindowIndex = 0;
    }

    private void cancelProbe(long now) {
        if (!probing) return;
        scale = probeOriginalScale;
        probing = false;
        lastResizeAt = now;
        retryProbeAt = now + UNRESPONSIVE_RETRY_MS;
    }

    /** Discard transitions, including any unvalidated probe, preserving accepted world quality. */
    public void pause(long now) {
        cancelProbe(now);
        clearHistory();
        lastSampleAt = -1L;
        lastResizeAt = now;
    }

    /** Five slow windows to probe, ten healthy windows to recover; a 5s resize cooldown. */
    public float sample(double fps, boolean thermallyLimited, long now) {
        if (!finitePositive(fps) || fps > 1000 || (lastSampleAt >= 0
                && (now - lastSampleAt < 500L || now - lastSampleAt > 2500L))) {
            cancelProbe(now);
            clearHistory();
            lastSampleAt = now;
            return scale;
        }
        lastSampleAt = now;
        if (probing) {
            // Heat, menus and invalid windows cannot validate a resolution-response experiment.
            if (thermallyLimited) {
                cancelProbe(now);
                clearHistory();
                return scale;
            }
            if (settlingSamples++ < 2) return scale;
            probeFpsTotal += fps;
            if (++probeSamples < 5) return scale;
            double response = probeFpsTotal / probeSamples;
            if (response < probeBaselineFps + Math.max(1.0, probeBaselineFps * 0.03)) {
                cancelProbe(now); // No useful response: restore, then wait a minute before retrying.
            } else {
                probing = false; // Resolution-sensitive throughput, not proof of GPU timing.
            }
            clearHistory();
            return scale;
        }
        if (fps < target * 0.90) {
            // Keep the most recent five windows during cooldown/backoff, not a stale old baseline.
            if (lowSamples >= 5) lowFpsTotal -= lowWindow[lowWindowIndex];
            else lowSamples++;
            lowWindow[lowWindowIndex] = fps;
            lowWindowIndex = (lowWindowIndex + 1) % lowWindow.length;
            lowFpsTotal += fps;
            highSamples = 0;
        } else if (fps >= target * 0.98 && !thermallyLimited) {
            highSamples = Math.min(10, highSamples + 1);
            lowSamples = 0;
            lowFpsTotal = 0;
            lowWindowIndex = 0;
        } else {
            clearHistory();
        }
        if (now - lastResizeAt < COOLDOWN_MS) return scale;
        float next = scale;
        if (lowSamples >= 5 && !thermallyLimited && now >= retryProbeAt) {
            next = Math.max(floor, scale - 0.025f);
            if (scale - next > 0.0001f) {
                probing = true;
                probeOriginalScale = scale;
                probeBaselineFps = lowFpsTotal / lowSamples;
                probeFpsTotal = 0;
                probeSamples = settlingSamples = 0;
            }
        }
        else if (highSamples >= 10) next = Math.min(ceiling, scale + 0.025f);
        if (Math.abs(next - scale) > 0.0001f) {
            scale = next;
            lastResizeAt = now;
            clearHistory();
        }
        return scale;
    }
}
