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
     * software/GL bridge (egl + osmesa). Used to latch the loading video onto
     * the FIRST real frame ("release exactly when the game renders"). -1 when
     * native is not linked yet or coverage is unknown (including native Vulkan).
     */
    public static long getTotalPresents() {
        try {
            return nativeGetTotalPresents();
        } catch (UnsatisfiedLinkError t) {
            return -1;
        }
    }
}
