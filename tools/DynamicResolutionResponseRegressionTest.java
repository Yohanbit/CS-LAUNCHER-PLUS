import net.kdt.pojavlaunch.performance.DynamicResolutionController;

/** Regression tests for reversible probes; all throughput changes below are synthetic. */
public final class DynamicResolutionResponseRegressionTest {
    private static int assertions;
    private static long now;
    private static void equal(float actual, float expected, String name) {
        assertions++;
        if (Float.isNaN(actual) || Math.abs(actual - expected) > .0002f)
            throw new AssertionError(name + ": " + actual + " != " + expected);
    }
    private static float sample(DynamicResolutionController d, double fps) {
        now += 1000L;
        return d.sample(fps, false, now);
    }
    public static void main(String[] args) {
        DynamicResolutionController d = new DynamicResolutionController();
        d.configure(.75f, 30, 60, now);
        for (int i = 0; i < 5; i++) sample(d, 20);
        for (int i = 0; i < 7; i++) sample(d, 20);
        equal(d.getScale(), .75f, "non-responsive first probe restored");
        for (int i = 0; i < 55; i++) equal(sample(d, 20), .75f, "retry backoff");
        // Scene throughput changes BEFORE a retry, without any resolution change.
        for (int i = 0; i < 4; i++) equal(sample(d, 40), .75f, "new scene during backoff");
        equal(sample(d, 40), .725f, "retry still only a provisional small probe");
        for (int i = 0; i < 6; i++) equal(sample(d, 40), .725f, "response uses recent pre-probe baseline");
        equal(sample(d, 40), .75f, "stale 20 FPS baseline cannot falsely validate unchanged 40 FPS");

        d.configure(.75f, 30, 60, now);
        for (int i = 0; i < 5; i++) sample(d, 50);
        for (int i = 0; i < 2; i++) sample(d, 50);
        for (int i = 0; i < 4; i++) equal(sample(d, 51.4), .725f, "sub-threshold response remains provisional");
        equal(sample(d, 51.4), .75f, "sub-three-percent response restored");

        d.configure(.75f, 30, 60, now);
        for (int i = 0; i < 5; i++) sample(d, 20);
        for (int i = 0; i < 2; i++) sample(d, 20);
        for (int i = 0; i < 5; i++) sample(d, 21);
        equal(d.getScale(), .725f, "at least one FPS and three-percent response accepted");
        d.pause(now);
        equal(d.getScale(), .725f, "accepted quality survives transition");
        for (int i = 0; i < 4; i++) equal(sample(d, 21), .725f, "resume requires fresh sustained evidence");
        equal(sample(d, 21), .70f, "next small probe");
        now += 3000L;
        equal(d.sample(100, false, now), .725f, "stale response restores last accepted scale, not arbitrary ceiling");

        d.configure(.75f, 30, 60, now);
        for (int i = 0; i < 5; i++) sample(d, 20);
        equal(d.getScale(), .725f, "active probe before target change");
        d.configure(.75f, 30, DynamicResolutionController.effectiveTarget(60, 20, false, 60), now);
        for (int i = 0; i < 20; i++) equal(sample(d, 20), .75f, "new known pacing cap invalidates old pressure");
        System.out.println("PASS " + assertions + " response/backoff/transition regression assertions; no GPU/device measurement");
    }
}
