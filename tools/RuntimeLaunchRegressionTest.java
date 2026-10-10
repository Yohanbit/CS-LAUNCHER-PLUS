import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.multirt.RuntimeLibraryLoader;
import net.kdt.pojavlaunch.performance.RuntimeJvmArguments;
import net.kdt.pojavlaunch.performance.RuntimeMemoryBudget;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Concrete launch regressions; real host HotSpot checks are not Android-runtime validation. */
public final class RuntimeLaunchRegressionTest {
    private static int checks;

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void metadata() {
        check(Runtime.majorVersion("1.8.0_402-b06") == 8, "legacy Java 8 update/build");
        check(Runtime.majorVersion("17.0.14+7") == 17, "LTS build suffix");
        check(Runtime.majorVersion("21") == 21, "bare modern major");
        check(Runtime.majorVersion("25-ea") == 25, "early-access version no dot");
        check(Runtime.majorVersion("25-ea+13") == 25, "early access build suffix");
        for (String invalid : Arrays.asList(null, "", "1", "1.", "0", "17x", "17..1",
                "-17", "2147483648", "unknown", "17\n-Dfoo=bar")) {
            check(Runtime.majorVersion(invalid) == 0, "invalid version fails closed: " + invalid);
        }
        Runtime parsed = Runtime.fromRelease("test", "JAVA_VERSION=\"25-ea\"\r\nOS_ARCH=\"aarch64\"\r\n");
        check(parsed.javaVersion == 25 && "aarch64".equals(parsed.arch), "release CRLF parsed");
        check(Runtime.fromRelease("test", "NOT_JAVA_VERSION=\"21\"\nOS_ARCH=\"aarch64\"").javaVersion == 0,
                "release keys matched exactly");
        check(Runtime.fromRelease("test", "JAVA_VERSION=\"21\"\nOS_ARCH=\"\"").versionString == null,
                "missing architecture invalidates release");
        check(Runtime.fromRelease("test", "JAVA_VERSION=\"21\"\n").arch == null, "missing architecture closed");
        check(Runtime.fromRelease("test", "JAVA_VERSION=\"oops\"\nOS_ARCH=\"arm\"").javaVersion == 0,
                "malformed installed runtime cannot crash scanning");
        check(Runtime.fromRelease("test", null).javaVersion == 0, "missing release closed");
        check(new Runtime(null).equals(new Runtime(null)), "missing selection value remains safely comparable");
    }

    private static void memory() {
        RuntimeMemoryBudget.HeapAdvice exhausted = RuntimeMemoryBudget.assess(4096, 700, -1);
        check(exhausted.known && exhausted.recommendedMb == 0, "known readings distinguish exhausted headroom");
        check(exhausted.shouldWarn(512), "zero recommendation must warn even if Xmx fits raw available RAM");
        check(exhausted.suggestedMb() == 0, "do not offer fabricated 256 MB suggestion");
        RuntimeMemoryBudget.HeapAdvice zeroAvailable = RuntimeMemoryBudget.assess(4096, 0, -1);
        check(zeroAvailable.known && zeroAvailable.shouldWarn(256), "measured zero memory is exhausted, not unknown");
        RuntimeMemoryBudget.HeapAdvice unknown = RuntimeMemoryBudget.assess(0, 3000, -1);
        check(!unknown.known && !unknown.shouldWarn(512), "unknown total is not treated as exhaustion");
        check(unknown.suggestedMb() == 0, "unknown cannot recommend a heap");
        check(!RuntimeMemoryBudget.assess(4096, -1, -1).known, "unavailable memory measurement sentinel");
        check(RuntimeMemoryBudget.assess(8192, 5000, 0).shouldWarn(256), "measured zero address space ceiling");
        RuntimeMemoryBudget.HeapAdvice address = RuntimeMemoryBudget.assess(8192, 6000, 1024);
        check(address.recommendedMb == 1024, "do not subtract physical-memory reserve again from address-space hole");
        check(address.suggestedMb() == 1024 && address.shouldWarn(2048), "known launchable advisory");
        check(!address.shouldWarn(1024), "no warning at the advised heap");
        check(RuntimeMemoryBudget.assess(1024, 900, -1).suggestedMb() == 0,
                "128 MB recommendation cannot become 256 MB suggestion");
    }

    private static void arguments() {
        List<String> args = new ArrayList<>(Arrays.asList("-Djava.home.extra=user", "-Duser.language.region=user"));
        check(RuntimeJvmArguments.addDefault(args, "-Djava.home=/selected"), "prefix property cannot suppress required java.home");
        check(RuntimeJvmArguments.addDefault(args, "-Duser.language=es"), "prefix property cannot suppress locale");
        check(!RuntimeJvmArguments.addDefault(args, "-Djava.home=/other"), "exact default added only once");
        check(args.contains("-Djava.home=/selected") && args.contains("-Djava.home.extra=user"), "user extended property preserved");
        List<String> user = new ArrayList<>(Arrays.asList("-Dorg.lwjgl.system.allocator=jemalloc",
                "-Dorg.lwjgl.spvc.libname=user", "-XX:-UseG1GC"));
        check(!RuntimeJvmArguments.addDefault(user, "-Dorg.lwjgl.system.allocator=system"), "explicit allocator owns exact property");
        check(!RuntimeJvmArguments.addDefault(user, "-Dorg.lwjgl.spvc.libname=default"), "explicit SPVC library owns exact property");
        check(!RuntimeJvmArguments.addDefault(user, "-XX:+UseG1GC"), "negative boolean owns positive default key");
        check(user.size() == 3, "no user-key duplicates generated");
        List<String> agents = new ArrayList<>(Collections.singletonList("-javaagent:/one.jar=a"));
        check(RuntimeJvmArguments.addDefault(agents, "-javaagent:/two.jar=b"), "independent Java agents not conflated");
        check(!RuntimeJvmArguments.addDefault(agents, "-javaagent:/one.jar=c"), "same default agent path not duplicated");
        List<String> heaps = new ArrayList<>(Arrays.asList("-Xmx1G", "-Xmx2048M", "-Xms256M",
                "-Dorg.lwjgl.opengl.libname.extra=keep", "-Dorg.lwjgl.opengl.libname=remove"));
        RuntimeJvmArguments.removeKey(heaps, "-Xmx");
        RuntimeJvmArguments.removeKey(heaps, "-Dorg.lwjgl.opengl.libname");
        check(heaps.equals(Arrays.asList("-Xms256M", "-Dorg.lwjgl.opengl.libname.extra=keep")),
                "remove all real heap keys but retain extended renderer property");
        check(RuntimeJvmArguments.g1Experiment(0, true, 8, null).isEmpty(), "unknown runtime generates no experimental VM flags");
        check(RuntimeJvmArguments.gcDiagnostics(0, "/tmp/gc.log", null).isEmpty(), "unknown runtime generates no diagnostics flags");
        for (int version : new int[]{0, 8, 11, 17, 21, 25}) {
            for (boolean throughput : new boolean[]{false, true}) {
                check(RuntimeJvmArguments.g1Experiment(false, version, throughput, 8, null).isEmpty(),
                        "disabled experiment adds zero flags for Java " + version + " throughput=" + throughput);
            }
        }
        check(RuntimeJvmArguments.g1Experiment(true, 0, true, 8, null).isEmpty(), "opt-in cannot enable unknown-runtime flags");
    }

    private static void libraries() {
        List<String> attempted = new ArrayList<>();
        RuntimeLibraryLoader.require("libjvm", path -> { attempted.add(path); return path.equals("/selected/libjvm.so"); },
                "libjvm.so", "/selected/libjvm.so", "/never/libjvm.so");
        check(attempted.equals(Arrays.asList("libjvm.so", "/selected/libjvm.so")), "exact fallback stops after successful load");
        attempted.clear();
        boolean rejected = false;
        try {
            RuntimeLibraryLoader.require("libjli", path -> { attempted.add(path); return false; }, "/broken/libjli.so");
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("libjli") && expected.getMessage().contains("process ABI");
        }
        check(rejected && attempted.equals(Collections.singletonList("/broken/libjli.so")), "failed required launcher library fails fast with diagnostic");
        rejected = false;
        try { RuntimeLibraryLoader.require("libjvm", path -> false, "libjvm.so", "/broken/libjvm.so"); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "failure of both JVM candidates is fatal, not silently ignored");
    }

    private static int hostRun(File directory, String name, List<String> flags) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        command.addAll(flags);
        command.add("-version");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(new File(directory, name)).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("host JVM timed out");
        }
        return process.exitValue();
    }

    private static void hostFlags() throws Exception {
        File directory = Files.createTempDirectory("runtime-launch-regression-").toFile();
        try {
            int version = Runtime.majorVersion(System.getProperty("java.version"));
            check(hostRun(directory, "default.txt", Collections.emptyList()) == 0, "real host JVM starts without experimental flags");
            for (boolean throughput : new boolean[]{false, true}) {
                List<String> flags = RuntimeJvmArguments.g1Experiment(version, throughput, 8, null);
                check(!flags.isEmpty() && hostRun(directory, "g1-" + throughput + ".txt", flags) == 0,
                        "real host accepts generated G1 variant: " + throughput);
            }
            String gcFile = new File(directory, "gc.log").getAbsolutePath();
            check(hostRun(directory, "gc-output.txt", RuntimeJvmArguments.gcDiagnostics(version, gcFile, null)) == 0,
                    "real host accepts generated GC diagnostic syntax");
            check(new File(gcFile).isFile(), "generated diagnostics create the bounded log");
            check(hostRun(directory, "invalid.txt", Collections.singletonList("-XX:DefinitelyUnknownRound2Flag=1")) != 0,
                    "unknown VM flags are not silently accepted");
            String error = new String(Files.readAllBytes(new File(directory, "invalid.txt").toPath()), StandardCharsets.UTF_8);
            check(error.contains("Unrecognized VM option"), "real startup error identifies unknown VM flag");
            System.out.println("Runtime launch real-host flag validation: Java " + version + " (not Android)");
        } finally {
            File[] children = directory.listFiles();
            if (children != null) for (File child : children) child.delete();
            directory.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        metadata(); memory(); arguments(); libraries(); hostFlags();
        System.out.println("Runtime launch regressions: " + checks + " checks passed; no device/FPS claim");
    }
}
