package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Locale;

/** Public Android thermal signals only. Never changes platform protections, clocks or quality. */
public final class ThermalGovernor {
    public enum Level {
        UNKNOWN, COOL, MILD, MODERATE, SEVERE;
        public boolean atLeast(Level other) { return ordinal() >= other.ordinal(); }
        public String label() {
            switch (this) {
                case COOL: return "cool";
                case MILD: return "warm";
                case MODERATE: return "hot — platform severe throttling";
                case SEVERE: return "critical or worse";
                default: return "not reported by this device";
            }
        }
    }

    private static final long HEADROOM_INTERVAL_MS = 10_000L;
    private static volatile Level sLevel = Level.UNKNOWN;
    private static volatile float sHeadroom = Float.NaN;
    private static volatile long sHeadroomAt = -1L;
    private static volatile boolean sShed;
    private static volatile int sPeak;
    private static final RuntimeThermalState HYSTERESIS = new RuntimeThermalState();
    private static Runnable sTick;
    private static PowerManager sPowerManager;
    private static PerformancePolicy sPolicy;

    private ThermalGovernor() {}
    public static Level current() { return sLevel; }

    public static String describe(Context ctx) {
        String headroom = Float.isNaN(sHeadroom) ? "n/a"
                : String.format(Locale.ROOT, "%.2f (%ds ago)", sHeadroom,
                Math.max(0L, (SystemClock.elapsedRealtime() - sHeadroomAt) / 1000L));
        return "Thermal: " + sLevel.label() + " · headroom " + headroom + " · shed "
                + (sShed ? "active" : "none") + (sTick == null ? " (not sampling)" : "");
    }

    public static synchronized void start(final Context ctx, PerformancePolicy policy) {
        stop();
        sLevel = Level.UNKNOWN;
        sHeadroom = Float.NaN;
        sHeadroomAt = -1L;
        sPeak = 0;
        HYSTERESIS.reset();
        if (ctx == null || policy == null || !policy.thermalPolling) return;
        sPolicy = policy;
        try { sPowerManager = (PowerManager) ctx.getApplicationContext().getSystemService(Context.POWER_SERVICE); }
        catch (Throwable ignored) { sPowerManager = null; }
        final Runnable listener = new Runnable() {
            @Override public void run() {
                synchronized (ThermalGovernor.class) {
                    if (sTick == this) sample(); // discard a stale callback after stop/restart
                }
            }
        };
        sTick = listener;
        SchedulingPolicy.addTickListener(listener);
        sample();
    }

    public static synchronized void stop() {
        Runnable r = sTick;
        sTick = null;
        if (r != null) SchedulingPolicy.removeTickListener(r);
        sPowerManager = null;
        sPolicy = null;
        HYSTERESIS.reset();
        sShed = false;
    }

    private static void sample() {
        Level level = Level.UNKNOWN;
        long now = SystemClock.elapsedRealtime();
        PowerManager pm = sPowerManager;
        if (pm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { level = fromStatus(pm.getCurrentThermalStatus()); } catch (Throwable ignored) { }
            }
            // Java API added in Android 11 (30). The documented sampling limit is once per 10s.
            // Zero is valid cool headroom, not unsupported; NaN indicates unavailable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && (sHeadroomAt < 0L || now - sHeadroomAt >= HEADROOM_INTERVAL_MS)) {
                sHeadroomAt = now; // rate-limit even an unsupported/NaN response
                sHeadroom = Float.NaN;
                try {
                    float value = pm.getThermalHeadroom(0);
                    if (!Float.isNaN(value) && !Float.isInfinite(value) && value >= 0f) sHeadroom = value;
                } catch (Throwable ignored) { }
            }
        }
        boolean validHeadroom = !Float.isNaN(sHeadroom) && sHeadroomAt >= 0L
                && now - sHeadroomAt <= HEADROOM_INTERVAL_MS;
        // >=1 denotes severe throttling. Do not invent device-independent light/moderate mappings.
        if (validHeadroom && sHeadroom >= 1f && !level.atLeast(Level.MODERATE)) level = Level.MODERATE;
        sLevel = level;
        sPeak = Math.max(sPeak, level.ordinal());
        boolean allowed = sPolicy != null && sPolicy.thermalShedAllowed;
        boolean hot = allowed && level.atLeast(Level.MODERATE);
        // Recovery requires a genuinely COOL platform status (not UNKNOWN/MILD) for a minute.
        // 0.75 is a conservative recovery margin, not a documented status conversion.
        boolean cool = level == Level.COOL && (!validHeadroom || sHeadroom <= 0.75f);
        boolean shed = allowed && HYSTERESIS.update(hot, cool, now);
        if (shed != sShed) {
            sShed = shed;
            SchedulingPolicy.setThermalShed(shed);
        }
    }

    private static Level fromStatus(int status) {
        switch (status) {
            case 0: return Level.COOL;
            case 1: case 2: return Level.MILD; // light / moderate do not revoke the bounded request
            case 3: return Level.MODERATE;     // Android THERMAL_STATUS_SEVERE
            case 4: case 5: case 6: return Level.SEVERE; // critical / emergency / shutdown
            default: return Level.UNKNOWN;
        }
    }

    public static int peakLevelOrdinal() { return sPeak; }
}
