import net.kdt.pojavlaunch.performance.DynamicResolutionController;

public final class DynamicResolutionTest {
    private static int assertions;
    private static void equal(float actual, float expected, String name) {
        assertions++;
        if (Math.abs(actual - expected) > 0.0002f)
            throw new AssertionError(name + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) {
        DynamicResolutionController d = new DynamicResolutionController();
        d.configure(.60f, 30, 60, 0);
        for (int i = 1; i <= 4; i++) equal(d.sample(20, false, i * 1000L), .60f, "transient load ignored");
        equal(d.sample(20, false, 5000), .575f, "small reduction after sustained load");
        for (int i = 6; i <= 9; i++) equal(d.sample(20, false, i * 1000L), .575f, "resize cooldown");
        equal(d.sample(20, false, 10000), .55f, "second reduction");
        float savedWorld = d.getScale();
        d.pause(11000); // Menus use the manual scale; policy retains this world scale.
        equal(d.getScale(), savedWorld, "menu keeps world scale");
        d.pause(20000); // Resume resets both load history and resize cooldown.
        for (int i = 21; i <= 24; i++) equal(d.sample(1, false, i * 1000L), savedWorld, "no inherited menu load");
        equal(d.sample(1, false, 25000), .525f, "fresh sustained sample needed");
        for (int i = 26; i <= 200; i++) d.sample(10, false, i * 1000L);
        equal(d.getScale(), .30f, "absolute floor, not 30 percent of 60");
        for (int i = 201; i <= 215; i++) equal(d.sample(100, true, i * 1000L), .30f, "heat vetoes recovery");
        for (int i = 216; i <= 224; i++) equal(d.sample(100, false, i * 1000L), .30f, "recovery hysteresis");
        equal(d.sample(100, false, 225000), .325f, "healthy recovery");
        float before = d.getScale();
        equal(d.sample(Double.NaN, false, 230000), before, "NaN ignored");
        equal(d.sample(Double.POSITIVE_INFINITY, false, 231000), before, "infinity ignored");
        equal(d.sample(-1, false, 232000), before, "invalid counter ignored");
        d.configure(.45f, 60, 60, 0);
        for (int i = 1; i <= 60; i++) equal(d.sample(10, false, i*1000L), .45f, "floor never raises manual resolution");
        d.configure(.60f, 30, 60, 0);
        for (int i = 1; i <= 5; i++) d.sample(20, false, i*1000L);
        d.configure(.75f, 30, 60, 6000);
        equal(d.getScale(), .75f, "new manual choice clears old session");
        System.out.println("PASS " + assertions + " dynamic-resolution policy assertions; no device/FPS measurement");
    }
}
