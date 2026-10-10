import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.kdt.pojavlaunch.performance.*;
import net.kdt.pojavlaunch.multirt.Runtime;

public class ConsolidationRuntimeTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void eq(Object actual, Object expected) { check(Objects.equals(actual, expected), actual + " != " + expected); }
    static List<String> parse(String text) { return ActualLaunch.parseJavaArguments(text); }
    static void rejects(Runnable test) { try { test.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException expected) { checks++; } }
    public static void main(String[] args) throws Exception {
        eq(parse(null), Collections.emptyList()); eq(parse("  \t\n"), Collections.emptyList());
        eq(parse("-Dsample=\"hello world\" -Dother='a=b=c'"), Arrays.asList("-Dsample=hello world", "-Dother=a=b=c"));
        eq(parse("-Dsample=hello\\ world -Dpath=C:\\temp\\thing"), Arrays.asList("-Dsample=hello world", "-Dpath=C:\\temp\\thing"));
        eq(parse("-Dquote=\"a\\\"b\" -Dslash=\\\\"), Arrays.asList("-Dquote=a\"b", "-Dslash=\\"));
        eq(parse("-XX:+UseG1GC -XX:-UseG1GC"), Arrays.asList("-XX:+UseG1GC", "-XX:-UseG1GC"));
        eq(parse("-XX:-UseG1GC -XX:+UseG1GC"), Arrays.asList("-XX:-UseG1GC", "-XX:+UseG1GC"));
        String log = "-Xlog:gc*:file=/tmp/gc.log:time:filecount=2,filesize=8m";
        String agent = "-javaagent:/tmp/agent.jar=key=value";
        eq(parse(log + " " + agent), Arrays.asList(log, agent));
        eq(parse("--add-opens java.base/java.lang=ALL-UNNAMED -Dembedded=-XX:+UseZGC"),
                Arrays.asList("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Dembedded=-XX:+UseZGC"));
        eq(parse("-Dempty=\"\" -Dsingle='literal\\value'"), Arrays.asList("-Dempty=", "-Dsingle=literal\\value"));
        rejects(() -> parse("-Dfoo='unterminated")); rejects(() -> parse("-Dfoo=\"unterminated"));
        android.os.Build.VERSION.SDK_INT = 23;
        System.setProperty("os.arch", "arm"); android.os.Process.bit64 = false;
        ActualLaunch.Context ram = new ActualLaunch.Context(8192);
        eq(ActualLaunch.getMaximumRamAllocation(ram), 1024); eq(ActualLaunch.findBestRAMAllocation(ram), 696);
        check(ActualLaunch.isCompatible(Runtime.fromRelease("arm", "JAVA_VERSION=\"17.0.1\"\nOS_ARCH=\"arm\"")), "compatible ARM32");
        check(!ActualLaunch.isCompatible(Runtime.fromRelease("wide", "JAVA_VERSION=\"21\"\nOS_ARCH=\"aarch64\"")), "exclude wide JVM");
        android.os.Process.bit64 = true; System.setProperty("os.arch", "aarch64");
        eq(ActualLaunch.getMaximumRamAllocation(ram), 7168); eq(ActualLaunch.findBestRAMAllocation(ram), 3072);
        for (int mb : new int[]{512,1024,1536,2048,4096,8192}) {
            ram.ram = mb; check(ActualLaunch.findBestRAMAllocation(ram) <= ActualLaunch.getMaximumRamAllocation(ram), "default under selector ceiling");
        }
        ram.ram = 8192;
        List<String> finalArgs = ActualLaunch.selectedHeap("-Xmx256M -Xms256M " + log + " " + agent
                + " -XX:+UseG1GC -XX:-UseG1GC -Dorg.lwjgl.opengl.libname=unsafe -Dorg.lwjgl.opengl.libname.extra=keep", 4096,
                Arrays.asList("GameMain", "--game-argument"));
        check(finalArgs.contains("-Xmx4096M"), "explicit selected heap not silently clamped");
        check(!finalArgs.contains("-Xmx256M"), "reserved user heap purged");
        check(finalArgs.contains(log) && finalArgs.contains(agent), "valid multiple equals reaches final argv");
        check(finalArgs.indexOf("-XX:+UseG1GC") < finalArgs.indexOf("-XX:-UseG1GC"), "boolean last-owner order intact");
        check(!finalArgs.contains("-Dorg.lwjgl.opengl.libname=unsafe"), "reserved key removed");
        check(finalArgs.contains("-Dorg.lwjgl.opengl.libname.extra=keep"), "prefix-neighbor preserved");
        eq(finalArgs.subList(finalArgs.size()-2,finalArgs.size()), Arrays.asList("GameMain", "--game-argument"));
        List<String> launch = new ArrayList<>();
        RuntimeModWorkarounds.addJvmDefault(launch, parse("-Dsodium.checks.issue2561=true"), true);
        check(launch.isEmpty(), "explicit true wins");
        RuntimeModWorkarounds.addJvmDefault(launch, parse("-Dsodium.checks.issue2561=false"), true);
        check(launch.isEmpty(), "explicit false also wins");
        RuntimeModWorkarounds.addJvmDefault(launch, Collections.emptyList(), false);
        check(launch.isEmpty(), "default off");
        RuntimeModWorkarounds.addJvmDefault(launch, Collections.emptyList(), true);
        RuntimeModWorkarounds.addJvmDefault(launch, Collections.emptyList(), true);
        eq(launch, Arrays.asList("-Dsodium.checks.issue2561=false"));
        check(ActualLaunch.selectedHeap("-Dsodium.checks.issue2561=true", 1024, Arrays.asList("Main")).contains("-Dsodium.checks.issue2561=true"), "true in final argv");
        eq(RuntimeRendererSelection.initialFallback(Arrays.asList("opengles3_desktopgl_zink_kopper"), true), null);
        eq(RuntimeRendererSelection.initialFallback(Arrays.asList("vulkan_zink", "opengles_mobileglues"), true), "opengles_mobileglues");
        eq(RuntimeRendererSelection.initialFallback(Arrays.asList("gallium_freedreno", "opengles3_KW"), false), "opengles3_KW");
        check(RuntimeRendererRules.candidates("opengles3_desktopgl_zink_kopper", Arrays.asList("opengles3_desktopgl_zink_kopper")).size()==1, "explicit Zink attempt preserved");
        Path dir = Files.createTempDirectory("runtime-consolidation");
        File config = dir.resolve("sodium-mixins.properties").toFile();
        String key = "mixin.features.buffer_builder.intrinsics";
        String other = "mixin.features.chunk_rendering";
        String original = "# user\r\n" + key + "=true\r\nother=user";
        Files.write(config.toPath(), original.getBytes("UTF-8"));
        RuntimeModWorkarounds.applyMixinConfig(config, false); eq(new String(Files.readAllBytes(config.toPath()),"UTF-8"), original);
        RuntimeModWorkarounds.applyMixinConfig(config, true);
        String enabled = new String(Files.readAllBytes(config.toPath()),"UTF-8");
        check(enabled.contains(key+"=true") && !enabled.contains(key+"=false"), "user key preserved");
        check(enabled.contains(other+"=false"), "absent key added");
        RuntimeModWorkarounds.applyMixinConfig(config, true); eq(new String(Files.readAllBytes(config.toPath()),"UTF-8"), enabled);
        RuntimeModWorkarounds.applyMixinConfig(config, false); eq(new String(Files.readAllBytes(config.toPath()),"UTF-8"), original);
        RuntimeModWorkarounds.applyMixinConfig(config, true);
        Files.write(config.toPath(), (enabled.replace(other+"=false",other+"=true")+"\nnew=user\n").getBytes("UTF-8"));
        RuntimeModWorkarounds.applyMixinConfig(config, false);
        String edited = new String(Files.readAllBytes(config.toPath()),"UTF-8");
        check(edited.contains(other+"=true") && edited.contains("new=user"), "user edited owned key preserved");
        Files.write(config.toPath(), (key+"=false\n"+other+"=false\n").getBytes("UTF-8"));
        RuntimeModWorkarounds.applyMixinConfig(config, false);
        check(new String(Files.readAllBytes(config.toPath()),"UTF-8").contains(key+"=false"), "legacy unowned edits not deleted");
        File missing = dir.resolve("missing.properties").toFile(); RuntimeModWorkarounds.applyMixinConfig(missing,true); check(!missing.exists(),"no invented mod config");
        ActualLaunch context = new ActualLaunch(); context.gameDirectory = dir.toFile();
        String options = "maxFps:30\nvsync:true\nmaxFps:144\nvsync:false\n";
        Files.write(dir.resolve("options.txt"), options.getBytes("UTF-8"));
        eq(context.mcOption("maxFps"), "144"); eq(context.mcOption("vsync"), "false");
        eq(new String(Files.readAllBytes(dir.resolve("options.txt")),"UTF-8"), options);
        eq(RuntimeNativeProperties.freetypePath(null), null); eq(RuntimeNativeProperties.freetypePath(""),null);
        try { RuntimeNativeProperties.freetypePath(dir.toString()); throw new AssertionError("missing Freetype"); }
        catch (IllegalStateException expected) { checks++; }
        Files.write(dir.resolve("libfreetype.so"), new byte[]{1});
        eq(RuntimeNativeProperties.freetypePath(dir.toString()),dir.resolve("libfreetype.so").toString());
        System.out.println("Consolidation runtime: " + checks + " checks passed (actual production methods + API seams, not Android gameplay)");
    }
}
