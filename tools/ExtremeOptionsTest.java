import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import net.kdt.pojavlaunch.performance.ExtremeLaunchOptions;

/** Temporary-profile IO tests and pure refresh-selection tests; not Minecraft/Android execution. */
public final class ExtremeOptionsTest {
    private static int assertions;
    static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
    static String read(Path file) throws IOException { return new String(Files.readAllBytes(file), "UTF-8"); }
    static void write(Path file, String text) throws IOException { Files.write(file, text.getBytes("UTF-8")); }
    static void rate(float actual, float expected, String name) {
        check(Math.abs(actual - expected) < .001f, name + ": " + actual);
    }
    static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) { Files.deleteIfExists(dir); return; }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) deleteTree(entry);
        }
        Files.deleteIfExists(dir);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cs-pacing-test");
        try {
            Path dir = Files.createDirectory(root.resolve("profile"));
            Path file = dir.resolve("options.txt");
            Path backup = dir.resolve("cs-extreme-pacing.properties");
            String quality = "renderDistance:8\nsimulationDistance:6\nshaderPack:custom.zip\nguiScale:3\nparticles:2\nmodSetting:á:β\n";
            write(file, "maxFps:60\nvsync:true\n" + quality);
            ExtremeLaunchOptions.apply(dir.toFile(), "unlimited", 120);
            check(read(file).contains("maxFps:260\n"), "legacy Vanilla unlimited sentinel retained, not a native cap");
            check(read(file).contains("vsync:false\n"), "unlimited disables game VSync only");
            check(read(file).contains(quality), "all unrelated quality/mod/UTF-8 values preserved");
            byte[] saved = Files.readAllBytes(backup);
            byte[] unlimited = Files.readAllBytes(file);
            ExtremeLaunchOptions.apply(dir.toFile(), "unlimited", 40);
            check(Arrays.equals(unlimited, Files.readAllBytes(file)), "repeat Unlimited is idempotent");
            ExtremeLaunchOptions.apply(dir.toFile(), "manual", 144);
            check(read(file).contains("maxFps:144\n"), "manual cap");
            check(read(file).contains("vsync:false\n"), "manual preserves existing mode's game-VSync-off behavior");
            check(Arrays.equals(saved, Files.readAllBytes(backup)), "switching modes never replaces original backup");
            ExtremeLaunchOptions.apply(dir.toFile(), "manual", -1);
            check(read(file).contains("maxFps:10\n"), "manual minimum");
            ExtremeLaunchOptions.apply(dir.toFile(), "manual", 1000);
            check(read(file).contains("maxFps:250\n"), "manual maximum below unlimited sentinel");
            write(file, read(file).replace("renderDistance:8", "renderDistance:12"));
            ExtremeLaunchOptions.apply(dir.toFile(), "original", 120);
            check(read(file).contains("maxFps:60\n") && read(file).contains("vsync:true\n"), "Original restores both pacing fields");
            check(read(file).contains("renderDistance:12\n"), "restoring pacing keeps later unrelated edits");
            check(!Files.exists(backup), "successful restoration consumes backup");
            byte[] restored = Files.readAllBytes(file);
            ExtremeLaunchOptions.apply(dir.toFile(), "original", 120);
            check(Arrays.equals(restored, Files.readAllBytes(file)), "Original without backup is a no-op");
            ExtremeLaunchOptions.apply(dir.toFile(), "invalid", 120);
            check(Arrays.equals(restored, Files.readAllBytes(file)), "unknown mode is a no-op");

            Path empty = Files.createDirectory(root.resolve("empty"));
            ExtremeLaunchOptions.apply(empty.toFile(), "unlimited", 120);
            ExtremeLaunchOptions.apply(empty.toFile(), "original", 120);
            check(Files.size(empty.resolve("options.txt")) == 0, "absent original fields restored as absent");
            Path missing = root.resolve("missing");
            ExtremeLaunchOptions.apply(missing.toFile(), "original", 120);
            ExtremeLaunchOptions.apply(missing.toFile(), null, 120);
            check(!Files.exists(missing), "no-op modes do not create a profile");

            write(file, "maxFps:30\nmaxFps:90\nvsync:false\nvsync:true\n" + quality);
            ExtremeLaunchOptions.apply(dir.toFile(), "manual", 120);
            ExtremeLaunchOptions.apply(dir.toFile(), "original", 120);
            check(read(file).contains("maxFps:90\n") && !read(file).contains("maxFps:30\n"), "last effective duplicate cap is restored");
            check(read(file).contains("vsync:true\n") && !read(file).contains("vsync:false\n"), "last effective duplicate VSync restored");

            Path blocked = Files.createDirectory(root.resolve("blocked"));
            Path blockedFile = blocked.resolve("options.txt");
            write(blockedFile, "maxFps:60\nvsync:true\n");
            Files.createDirectory(blocked.resolve("cs-extreme-pacing.properties"));
            boolean failed = false;
            try { ExtremeLaunchOptions.apply(blocked.toFile(), "unlimited", 120); }
            catch (IOException expected) { failed = true; }
            check(failed, "unreadable backup fails closed");
            check(read(blockedFile).equals("maxFps:60\nvsync:true\n"), "failure leaves options unchanged");

            check(ExtremeLaunchOptions.knownFpsLimit("30") == 30, "ordinary stored cap recognized");
            check(ExtremeLaunchOptions.knownFpsLimit("250") == 250, "maximum manual cap recognized");
            check(ExtremeLaunchOptions.knownFpsLimit("260") == 0, "legacy Unlimited sentinel not treated as fixed cap");
            check(ExtremeLaunchOptions.knownFpsLimit("1000") == 0, "nonstandard large limit semantics unknown");
            check(ExtremeLaunchOptions.knownFpsLimit(null) == 0, "absent cap unknown");
            check(ExtremeLaunchOptions.knownFpsLimit("bad") == 0, "invalid cap unknown");
            check(ExtremeLaunchOptions.knownFpsLimit("0") == 0, "zero cap unknown");
            check(ExtremeLaunchOptions.knownFpsLimit("-1") == 0, "negative cap unknown");
            float[] rates = {120, 60, 90};
            rate(ExtremeLaunchOptions.chooseRefreshRate(rates, 0), 120, "Unlimited requests highest supplied rate");
            rate(ExtremeLaunchOptions.chooseRefreshRate(rates, 30), 60, "manual 30 chooses smallest sufficient panel mode");
            rate(ExtremeLaunchOptions.chooseRefreshRate(rates, 90), 90, "manual exact panel mode");
            rate(ExtremeLaunchOptions.chooseRefreshRate(rates, 100), 120, "manual selects next supported mode");
            rate(ExtremeLaunchOptions.chooseRefreshRate(rates, 250), 120, "manual above panel never invents mode");
            rate(ExtremeLaunchOptions.chooseRefreshRate(new float[]{59.94f}, 60), 59.94f, "fractional physical refresh retained");
            rate(ExtremeLaunchOptions.chooseRefreshRate(new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1, 0, 90}, 0), 90, "invalid panel rates ignored");
            rate(ExtremeLaunchOptions.chooseRefreshRate(new float[0], 0), 0, "no supported rates means no vote");
            rate(ExtremeLaunchOptions.chooseRefreshRate(null, 0), 0, "missing mode list means no vote");
            System.out.println("PASS " + assertions + " reversible pacing/refresh-selection assertions; not a game/device execution test");
        } finally { deleteTree(root); }
    }
}
