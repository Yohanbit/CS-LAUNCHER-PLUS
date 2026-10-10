package net.kdt.pojavlaunch.utils;

/**
 * FpsCounter reads successful submissions at the GL/native-window swap boundary.
 * This is not GPU timing or proof that SurfaceFlinger displayed every frame.
 * Driver/compositor queues and screen refresh can differ from this rate.
 *
 * Values: >=0 measured submission FPS (including zero stalls); -1 warming,
 * window too small, native not ready, or renderer coverage unknown.
 */
public final class FpsCounter {
    private FpsCounter() {}

    private static native int nativeGetFps();
    private static native long nativeGetTotalPresents();
    private static native int nativeGetCounterCoverage();

    public static final int COVERAGE_UNREADY = 0;
    public static final int COVERAGE_COUNTED = 1;
    public static final int COVERAGE_NATIVE_VULKAN_UNSUPPORTED = 2;
    private static final ThreadLocal<Window> LEGACY_WINDOW = new ThreadLocal<Window>() {
        @Override protected Window initialValue() { return new Window(); }
    };

    /** Give each HUD/controller/collector its own window, and reset on pause/resume.
     * Reads never reset native counters or interfere with other windows. */
    public static final class Window {
        private final FrameCounterWindow counter;
        public Window() { this(200_000_000L); }
        public Window(long minimumNanos) { counter = new FrameCounterWindow(minimumNanos); }
        public synchronized int getFps() {
            double fps = counter.sample(getTotalPresents(), System.nanoTime());
            return Double.isNaN(fps) ? -1 : (int) Math.min(Integer.MAX_VALUE, fps);
        }
        public synchronized void reset() { counter.reset(); }
        public synchronized long getLastElapsedNanos() { return counter.getLastElapsedNanos(); }
    }

    /** Compatibility-only per-thread window; new callers must own a Window. */
    @Deprecated
    public static int getFps() {
        return LEGACY_WINDOW.get().getFps();
    }

    /**
     * Cumulative successful swap submissions since process start, counted inside every
     * software/GL bridge (egl + osmesa). This is a submission signal, not proof
     * of GPU completion or display presentation. -1 when
     * native is not linked yet or coverage is unknown (including native Vulkan).
     */
    public static long getTotalPresents() {
        try {
            return nativeGetTotalPresents();
        } catch (UnsatisfiedLinkError t) {
            return -1;
        }
    }

    /**
     * Distinguishes GL startup/failure from an explicitly uncounted native Vulkan
     * window. Zink over GL/OSMesa is COUNTED, not native Vulkan. An older native
     * library without this optional entry point remains UNREADY: never certify
     * either coverage or a first submission from renderer names alone.
     */
    public static int getCounterCoverage() {
        try {
            int coverage = nativeGetCounterCoverage();
            return coverage == COVERAGE_COUNTED || coverage == COVERAGE_NATIVE_VULKAN_UNSUPPORTED
                    ? coverage : COVERAGE_UNREADY;
        } catch (UnsatisfiedLinkError t) {
            return COVERAGE_UNREADY;
        }
    }
}
