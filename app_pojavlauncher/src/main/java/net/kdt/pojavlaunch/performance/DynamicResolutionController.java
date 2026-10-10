package net.kdt.pojavlaunch.performance;

/** Pure opt-in resolution policy. The caller supplies real, fresh swap-counter samples only. */
public final class DynamicResolutionController {
    private float scale = 1f;
    private float ceiling = 1f;
    private float floor = 0.6f;
    private int target = 60;
    private int lowSamples, highSamples;
    private long lastResizeAt;

    public void configure(float originalScale, int minimumPercent, int targetFps, long now) {
        ceiling = validScale(originalScale);
        // Never raise an already manually reduced resolution to the configured minimum.
        floor = Math.min(ceiling, Math.max(25, Math.min(100, minimumPercent)) / 100f);
        target = Math.max(15, Math.min(1000, targetFps));
        scale = ceiling;
        lowSamples = highSamples = 0;
        lastResizeAt = now;
    }

    public static float validScale(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 1f : Math.max(0.25f, Math.min(1f, value));
    }

    public float getScale() { return scale; }

    /** Discard menu/transition samples while preserving the last usable world resolution. */
    public void pause(long now) {
        lowSamples = highSamples = 0;
        lastResizeAt = now;
    }

    /** Five slow samples to reduce, ten healthy samples to recover; 5s resize cooldown. */
    public float sample(double fps, boolean thermallyLimited, long now) {
        if (Double.isNaN(fps) || Double.isInfinite(fps) || fps <= 0 || fps > 1000) {
            lowSamples = highSamples = 0;
            return scale;
        }
        if (fps < target * 0.90) {
            lowSamples++;
            highSamples = 0;
        } else if (fps >= target * 0.98 && !thermallyLimited) {
            highSamples++;
            lowSamples = 0;
        } else {
            lowSamples = highSamples = 0;
        }
        if (now - lastResizeAt < 5000L) return scale;
        float next = scale;
        if (lowSamples >= 5) next = Math.max(floor, scale - 0.025f);
        else if (highSamples >= 10) next = Math.min(ceiling, scale + 0.025f);
        if (Math.abs(next - scale) > 0.0001f) {
            scale = next;
            lastResizeAt = now;
            lowSamples = highSamples = 0;
        }
        return scale;
    }
}
