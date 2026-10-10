import java.io.*;
import java.nio.file.*;
import net.kdt.pojavlaunch.performance.ExtremeLaunchOptions;
public final class ExtremeOptionsTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("cs-options-test");
        Path file = dir.resolve("options.txt");
        String original = "maxFps:60\nvsync:true\nrenderDistance:8\nsimulationDistance:6\n";
        Files.write(file, original.getBytes("UTF-8"));
        ExtremeLaunchOptions.apply(dir.toFile(), "unlimited", 120);
        String result = new String(Files.readAllBytes(file), "UTF-8");
        check(result.contains("maxFps:260\n"), "Vanilla unlimited sentinel");
        check(result.contains("vsync:false\n"), "unlimited disables game vsync");
        check(result.contains("renderDistance:8\n"), "quality preserved");
        ExtremeLaunchOptions.apply(dir.toFile(), "manual", 144);
        result = new String(Files.readAllBytes(file), "UTF-8");
        check(result.contains("maxFps:144\n"), "manual cap");
        ExtremeLaunchOptions.apply(dir.toFile(), "original", 120);
        result = new String(Files.readAllBytes(file), "UTF-8");
        check(result.contains("maxFps:60\n") && result.contains("vsync:true\n"), "original restored");
        check(!Files.exists(dir.resolve("cs-extreme-pacing.properties")), "backup consumed");
        Path empty = Files.createTempDirectory("cs-options-empty");
        ExtremeLaunchOptions.apply(empty.toFile(), "unlimited", 120);
        ExtremeLaunchOptions.apply(empty.toFile(), "original", 120);
        check(Files.size(empty.resolve("options.txt")) == 0, "absent fields restored");
        System.out.println("PASS 7 reversible pacing/quality assertions; not a game execution test");
    }
}
