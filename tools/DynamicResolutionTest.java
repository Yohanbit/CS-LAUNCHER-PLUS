import net.kdt.pojavlaunch.performance.DynamicResolutionController;

/** Synthetic policy windows only: does not measure GPU load, Android resizes or device FPS. */
public final class DynamicResolutionTest {
    private static int assertions;
    private static void equal(double actual, double expected, String name) {
        assertions++;
        if (Double.isNaN(actual) || Math.abs(actual - expected) > 0.0002)
            throw new AssertionError(name + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String name) {
        assertions++;
        if (!condition) throw new AssertionError(name);
    }
    private static final class Windows {
        final DynamicResolutionController d = new DynamicResolutionController();
        long now;
        Windows(float scale, int floor, double target) { d.configure(scale, floor, target, now); }
        float sample(double fps) { return sample(fps, false); }
        float sample(double fps, boolean hot) { now += 1000L; return d.sample(fps, hot, now); }
    }
    public static void main(String[] args) {
        equal(DynamicResolutionController.effectiveTarget(144, 30, false, 60), 30, "manual limits target");
        equal(DynamicResolutionController.effectiveTarget(60, 10, false, 120), 10, "manual below control range");
        equal(DynamicResolutionController.effectiveTarget(144, 120, true, 59.94), 59.94, "actual vsync refresh");
        equal(DynamicResolutionController.effectiveTarget(144, 30, true, 60), 30, "minimum of known limits");
        equal(DynamicResolutionController.effectiveTarget(144, 0, false, 60), 144, "refresh alone is not a submission cap");
        equal(DynamicResolutionController.effectiveTarget(144, 0, true, Double.NaN), 144, "unknown refresh ignored");
        equal(DynamicResolutionController.effectiveTarget(144, 0, true, 0), 144, "missing display ignored");
        equal(DynamicResolutionController.effectiveTarget(99999, -1, true, Double.POSITIVE_INFINITY), 1000, "bounds and invalid limits");
        equal(DynamicResolutionController.effectiveTarget(-1, 0, false, 60), 15, "requested target sanitized");

        Windows cpu = new Windows(.60f, 30, 60);
        for (int i = 0; i < 4; i++) equal(cpu.sample(20), .60f, "transient load ignored");
        equal(cpu.sample(20), .575f, "one small reversible probe");
        check(cpu.d.isProbing(), "decrease is provisional");
        for (int i = 0; i < 6; i++) equal(cpu.sample(20), .575f, "settling and response windows");
        equal(cpu.sample(20), .60f, "unchanged throughput restores quality");
        check(!cpu.d.isProbing(), "unresponsive probe ended");
        for (int i = 0; i < 59; i++) equal(cpu.sample(20), .60f, "unresponsive backoff avoids quality spiral");
        equal(cpu.sample(20), .575f, "bounded retry after a minute");
        cpu.d.pause(cpu.now);
        equal(cpu.d.getScale(), .60f, "menu cancels unvalidated probe");
        for (int i = 0; i < 10; i++) equal(cpu.sample(20), .60f, "menu cooldown/backoff retained");

        Windows responsive = new Windows(.60f, 30, 60);
        for (int i = 0; i < 5; i++) responsive.sample(20);
        for (int i = 0; i < 2; i++) equal(responsive.sample(100), .575f, "resize transients excluded from response");
        for (int i = 0; i < 5; i++) equal(responsive.sample(22), .575f, "sustained response retains reduction");
        check(!responsive.d.isProbing(), "response accepted without claiming GPU bottleneck");
        responsive.d.pause(responsive.now);
        equal(responsive.d.getScale(), .575f, "menu preserves accepted world scale");
        for (int i = 0; i < 4; i++) equal(responsive.sample(22), .575f, "fresh world samples required");
        equal(responsive.sample(22), .55f, "second separately evaluated probe");
        equal(responsive.sample(24, true), .575f, "heat invalidates causal response probe");
        for (int i = 0; i < 15; i++) equal(responsive.sample(100, true), .575f, "heat vetoes recovery");
        for (int i = 0; i < 9; i++) equal(responsive.sample(60), .575f, "recovery hysteresis");
        equal(responsive.sample(60), .60f, "quality recovery at ceiling");

        Windows capped = new Windows(.75f, 30,
                DynamicResolutionController.effectiveTarget(144, 30, false, 60));
        for (int i = 0; i < 30; i++) equal(capped.sample(30), .75f, "manual pacing is not GPU pressure");
        Windows vsync = new Windows(.75f, 30,
                DynamicResolutionController.effectiveTarget(144, 0, true, 59.94));
        for (int i = 0; i < 30; i++) equal(vsync.sample(59.94), .75f, "known VSync pacing retains quality");

        Windows floor = new Windows(.60f, 30, 144);
        double fps = 20;
        for (int probe = 0; probe < 12; probe++) {
            for (int i = 0; i < 5; i++) floor.sample(fps);
            for (int i = 0; i < 2; i++) floor.sample(fps);
            fps += 2;
            for (int i = 0; i < 5; i++) floor.sample(fps);
        }
        equal(floor.d.getScale(), .30f, "absolute floor, not 30 percent of manual 60");
        for (int i = 0; i < 30; i++) equal(floor.sample(fps), .30f, "floor is stable");
        Windows lowManual = new Windows(.45f, 60, 60);
        for (int i = 0; i < 30; i++) equal(lowManual.sample(10), .45f, "minimum never raises manually reduced resolution");

        Windows invalid = new Windows(.60f, 30, 60);
        for (int i = 0; i < 5; i++) invalid.sample(20);
        equal(invalid.sample(Double.NaN), .60f, "NaN cancels experiment");
        equal(invalid.sample(Double.POSITIVE_INFINITY), .60f, "infinity ignored");
        equal(invalid.sample(-1), .60f, "unavailable native counter ignored");
        equal(invalid.sample(0), .60f, "stalled counter ignored");
        equal(invalid.sample(1001), .60f, "implausible window ignored");
        invalid.d.configure(.75f, 30, 60, invalid.now);
        equal(invalid.d.getScale(), .75f, "manual reconfiguration clears old session");
        for (int i = 0; i < 4; i++) invalid.sample(20);
        equal(invalid.d.sample(20, false, invalid.now), .75f, "duplicate timestamp cannot trigger resize");
        invalid.now += 4000;
        equal(invalid.d.sample(20, false, invalid.now), .75f, "stale window cannot complete slow history");
        equal(invalid.d.sample(20, false, invalid.now - 1000), .75f, "clock reversal resets history");

        Windows deadband = new Windows(.75f, 30, 60);
        for (int i = 0; i < 20; i++) equal(deadband.sample(i % 2 == 0 ? 53 : 57), .75f, "deadband breaks transient low sequence");
        for (int i = 0; i < 10; i++) equal(deadband.sample(10, true), .75f, "heat never fabricates resolution benefit");
        equal(DynamicResolutionController.validScale(Float.NaN), 1, "invalid original sanitized");
        equal(DynamicResolutionController.validScale(Float.POSITIVE_INFINITY), 1, "infinite original sanitized");
        equal(DynamicResolutionController.validScale(.1f), .25f, "scale lower bound");
        equal(DynamicResolutionController.validScale(2f), 1, "scale upper bound");
        System.out.println("PASS " + assertions + " dynamic-resolution policy assertions; synthetic windows, not device/FPS measurement");
    }
}
