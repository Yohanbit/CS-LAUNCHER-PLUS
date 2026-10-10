package net.kdt.pojavlaunch.utils;

/**
 * FpsCounter reads successful submissions at the GL/native-window swap boundary.
 * This is not GPU timing or proof that SurfaceFlinger displayed every frame.
 * Driver/compositor queues and screen refresh can differ from this rate.
 *
 * Values:  >0 fps reading · 0 warming up · -1 window too small / native
 * not ready (caller keeps the last shown value).
 */
public final class FpsCounter {
    private FpsCounter() {}

    private static native int nativeGetFps();
    private static native long nativeGetTotalPresents();

    /** Current FPS. Never throws — native lib glues late on some launches. */
    public static int getFps() {
        try {
            return nativeGetFps();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Cumulative successful swap submissions since process start, counted inside every
     * software/GL bridge (egl + osmesa). Used to latch the loading video onto
     * the FIRST real frame ("release exactly when the game renders"). -1 when
     * native is not linked yet.
     */
    public static long getTotalPresents() {
        try {
            return nativeGetTotalPresents();
        } catch (Throwable t) {
            return -1;
        }
    }
}
