import net.kdt.pojavlaunch.performance.RuntimeJvmArguments;
import net.kdt.pojavlaunch.performance.RuntimeMemoryBudget;
import net.kdt.pojavlaunch.performance.RuntimeRendererRules;
import net.kdt.pojavlaunch.performance.RuntimeThermalState;
import net.kdt.pojavlaunch.performance.RuntimeThreadRules;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Android-independent regressions; passing these is not a device performance benchmark. */
public final class RuntimeSafetyPolicyTest {
    private static int checks;
    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
    private static void equal(Object expected, Object actual, String what) {
        check(expected.equals(actual), what + ": expected=" + expected + ", actual=" + actual);
    }

    private static void renderer() {
        List<String> installed = Arrays.asList("opengles3_KW", "opengles2", "opengles_mobileglues");
        equal(Arrays.asList("opengles_mobileglues", "opengles3_KW", "opengles2"),
                RuntimeRendererRules.candidates("opengles_mobileglues", installed), "requested first");
        equal(installed, RuntimeRendererRules.candidates("gallium_panfrost", installed), "no incompatible Panfrost");
        check(RuntimeRendererRules.candidates("opengles3_KW", Collections.emptyList()).isEmpty(), "no assumed installed wrapper");
        check(RuntimeRendererRules.candidates(null, null).isEmpty(), "unknown compatible list fails closed");
        check(RuntimeRendererRules.candidates(null, installed).isEmpty(), "AWT installer never selects a game renderer");
        List<String> forbiddenProbe = new java.util.AbstractList<String>() {
            @Override public String get(int index) { throw new AssertionError("installer probed renderers"); }
            @Override public int size() { throw new AssertionError("installer probed renderer count"); }
        };
        check(RuntimeRendererRules.candidates(null, forbiddenProbe).isEmpty(), "null renderer performs no compatible-list probing");
        check(RuntimeRendererRules.routingEnvironment(null).isEmpty(), "installer has no graphics routing");
        equal(Collections.singletonList("opengles3_desktopgl_zink_kopper"),
                RuntimeRendererRules.candidates("vulkan_zink", Arrays.asList("vulkan_zink", "opengles3_desktopgl_zink_kopper")),
                "Zink aliases canonicalized and deduplicated");
        equal(Collections.singletonList("opengles2"),
                RuntimeRendererRules.candidates("bogus", Arrays.asList("bogus", null, "opengles2", "opengles2")), "unknown ids excluded");
        for (String renderer : installed) {
            check(RuntimeRendererRules.library(renderer) != null, "known library " + renderer);
            Map<String, String> env = RuntimeRendererRules.routingEnvironment(renderer);
            equal(renderer, env.get("AMETHYST_RENDERER"), "actual renderer routing");
            check(!RuntimeRendererRules.isMesa(renderer), "non-Mesa family");
            for (String key : env.keySet()) check(!key.startsWith("MESA_") && !key.startsWith("TU_")
                    && !key.startsWith("ZINK_"), "no foreign driver key for " + renderer);
        }
        Map<String, String> zink = RuntimeRendererRules.routingEnvironment("opengles3_desktopgl_zink_kopper");
        equal("zink", zink.get("GALLIUM_DRIVER"), "Zink driver");
        equal("libEGL_mesa.so", zink.get("POJAVEXEC_EGL"), "Kopper EGL");
        check(!zink.containsKey("MESA_VK_WSI_PRESENT_MODE"), "no forced mailbox");
        check(!zink.containsKey("TU_DEBUG"), "no conformance bypass");
        List<String> unwiredGallium = Arrays.asList("gallium_freedreno", "gallium_panfrost");
        for (String unsupported : unwiredGallium) {
            check(RuntimeRendererRules.library(unsupported) == null, "unwired Gallium has no loadable library: " + unsupported);
            check(RuntimeRendererRules.routingEnvironment(unsupported).isEmpty(), "unwired Gallium has no native routing: " + unsupported);
            check(!RuntimeRendererRules.isMesa(unsupported), "unsupported Gallium gets no Mesa tuning: " + unsupported);
            check(RuntimeRendererRules.candidates(unsupported, unwiredGallium).isEmpty(), "Gallium-only install fails closed: " + unsupported);
            equal(Collections.singletonList("opengles2"), RuntimeRendererRules.candidates(unsupported,
                    Arrays.asList("gallium_panfrost", "opengles2", "gallium_freedreno")), "unwired selected/fallback routes excluded");
        }
        check(RuntimeRendererRules.library("untrusted") == null, "unknown has no library");
        check(RuntimeRendererRules.routingEnvironment("untrusted").isEmpty(), "unknown has no routing");
    }

    private static void jvm() throws Exception {
        for (int version : new int[]{0, 6, 7}) check(RuntimeJvmArguments.g1Experiment(version, false, 8, null).isEmpty(), "unknown/old JVM closed");
        for (int version : new int[]{8, 11, 17, 21}) {
            List<String> flags = RuntimeJvmArguments.g1Experiment(version, false, 8, null);
            check(flags.contains("-XX:+UseG1GC"), "explicit G1 experiment Java " + version);
            check(flags.contains("-XX:ParallelGCThreads=4"), "bounded parallel workers");
            check(flags.contains("-XX:ConcGCThreads=2"), "bounded concurrent workers");
            Set<String> keys = new HashSet<>();
            for (String flag : flags) {
                check(keys.add(RuntimeJvmArguments.optionKey(flag)), "unique flag key");
                check(!flag.contains("Unlock") && !flag.contains("PreTouch") && !flag.contains("ActiveProcessorCount")
                        && !flag.contains("CodeCache"), "no speculative automatic option");
            }
        }
        for (String user : Arrays.asList("-XX:+UseSerialGC", "-XX:+UseParallelGC", "-XX:+UseZGC",
                "-XX:+UseShenandoahGC", "-XX:+UseConcMarkSweepGC", "-XX:-UseG1GC", "-XX:+UseG1GC",
                "-XX:-ExplicitGCInvokesConcurrent", "-XX:+DisableExplicitGC")) {
            check(RuntimeJvmArguments.g1Experiment(21, true, 8, Arrays.asList(user)).isEmpty(), "user collector wins: " + user);
        }
        List<String> custom = Arrays.asList("-XX:MaxGCPauseMillis=75", "-XX:ParallelGCThreads=1",
                "-XX:ConcGCThreads=1", "-XX:-ParallelRefProcEnabled");
        List<String> flags = RuntimeJvmArguments.g1Experiment(17, true, 8, custom);
        for (String flag : flags) check(!RuntimeJvmArguments.hasKey(custom, flag), "no user-key duplicate");
        equal("-XX:UseG1GC", RuntimeJvmArguments.optionKey("-XX:-UseG1GC"), "negative boolean key");
        equal("-XX:UseG1GC", RuntimeJvmArguments.optionKey("-XX:+UseG1GC"), "positive boolean key");
        check(RuntimeJvmArguments.g1Experiment(8, false, 1, null).contains("-XX:ParallelGCThreads=1"), "single core workers");
        check(RuntimeJvmArguments.g1Experiment(8, false, 128, null).contains("-XX:ParallelGCThreads=4"), "large core count capped");
        List<String> gc8 = RuntimeJvmArguments.gcDiagnostics(8, "/tmp/gc.log", null);
        check(gc8.contains("-Xloggc:/tmp/gc.log"), "Java8 legacy logging");
        check(gc8.contains("-XX:GCLogFileSize=8M"), "Java8 bounded log");
        check(!gc8.toString().contains("-Xlog:"), "no unified logging on Java8");
        List<String> gc17 = RuntimeJvmArguments.gcDiagnostics(17, "/tmp/gc.log", null);
        check(gc17.get(0).startsWith("-Xlog:gc*,safepoint:"), "modern unified logging");
        check(!gc17.toString().contains("PrintGCDetails"), "no obsolete Java8 print flags on modern JVM");
        check(RuntimeJvmArguments.gcDiagnostics(0, "/tmp/gc.log", null).isEmpty(), "unknown logging runtime closed");
        check(RuntimeJvmArguments.gcDiagnostics(17, "/tmp/a:b.log", null).isEmpty(), "unsafe log delimiter rejected");
        for (String arg : Arrays.asList("-Xlog:gc", "-Xloggc:/tmp/user.log", "-XX:+PrintGCDetails", "-verbose:gc")) {
            check(RuntimeJvmArguments.gcDiagnostics(17, "/tmp/gc.log", Arrays.asList(arg)).isEmpty(), "user logging wins");
        }
        List<String> gcLogUser = Arrays.asList("-XX:-UseGCLogFileRotation", "-XX:NumberOfGCLogFiles=4", "-XX:GCLogFileSize=1M");
        for (String flag : RuntimeJvmArguments.gcDiagnostics(8, "/tmp/gc.log", gcLogUser))
            check(!RuntimeJvmArguments.hasKey(gcLogUser, flag), "no duplicate Java8 logging key");
        // Validate generated opt-in G1 flags on the actual host HotSpot, not an Android runtime.
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        int hostVersion = Integer.parseInt(System.getProperty("java.specification.version").replace("1.", ""));
        command.addAll(RuntimeJvmArguments.g1Experiment(hostVersion, true, 8, null));
        command.add("-version");
        check(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(new File("/dev/null"))
                .start().waitFor() == 0, "host accepts G1 experiment flags");
    }

    private static void memory() {
        equal(0, RuntimeMemoryBudget.recommendedHeapMb(0, 3000, 4000), "unknown total");
        equal(0, RuntimeMemoryBudget.recommendedHeapMb(6000, 0, 4000), "unknown available");
        equal(0, RuntimeMemoryBudget.recommendedHeapMb(1024, 128, 1024), "insufficient reserve");
        equal(3072, RuntimeMemoryBudget.recommendedHeapMb(8192, 5120, 7168), "GPU/native/OS reserve");
        equal(1024, RuntimeMemoryBudget.recommendedHeapMb(8192, 5120, 1024), "32bit address ceiling");
        int large = RuntimeMemoryBudget.recommendedHeapMb(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        check(large > 0 && large % 128 == 0, "no arithmetic overflow");
        for (int selected : new int[]{256, 384, 512, 1024, 2048, 8192}) {
            int initial = RuntimeMemoryBudget.initialHeapMb(selected);
            check(initial <= selected, "Xms does not exceed user Xmx");
            check(initial >= 256 && initial <= 1024 && initial % 128 == 0, "modest aligned Xms");
        }
        boolean invalid = false;
        try { RuntimeMemoryBudget.initialHeapMb(128); } catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "invalid heap rejected, not silently changed");
    }

    private static void thermal() {
        RuntimeThermalState state = new RuntimeThermalState();
        check(!state.update(false, true, 0), "initial cool no boost added");
        check(state.update(true, false, 100), "hot sheds immediately");
        check(state.update(false, true, 1000), "cool starts recovery");
        check(state.update(false, true, 60999), "full minute required");
        check(!state.update(false, true, 61000), "minute cool recovers");
        check(state.update(true, false, 62000), "hot again sheds");
        check(state.update(false, true, 63000), "recovery begins");
        check(state.update(false, false, 100000), "unknown/warm resets recovery");
        check(state.update(false, true, 124000), "unknown did not count as cool");
        check(state.update(false, true, 183999), "reset duration enforced");
        check(!state.update(false, true, 184000), "second full cool minute");
        check(state.update(true, true, 185000), "hot wins over conflicting cool");
        check(state.update(false, true, 186000), "recovery starts again");
        check(state.update(false, true, 10), "backwards clock never releases shed");
        check(!state.update(false, true, 60010), "recovery uses monotonic elapsed argument");
        state.reset();
        check(!state.update(false, false, 999999), "new session no stale shed");
    }

    private static void threads() {
        for (String name : Arrays.asList("Render thread", "Client thread", "Client main thr"))
            check(RuntimeThreadRules.isGameRenderThread(name), "exact known comm: " + name);
        for (String name : Arrays.asList("main", "LWJGL", "lwjgl", "Render", "render thread", "RenderThread",
                "Server thread", "Chunk Render", "Render thread 1", "GLThread", "Audio thread", "Worker-Main-1", "CS-PerfEngine"))
            check(!RuntimeThreadRules.isGameRenderThread(name), "no broad/case-folded match: " + name);
        check(!RuntimeThreadRules.isGameRenderThread(null), "unknown comm closed");
        StringBuilder stat = new StringBuilder("123 (Client (main) thread) S");
        for (int field = 4; field <= 21; field++) stat.append(" 0");
        stat.append(" 987654 0 0");
        equal("987654", RuntimeThreadRules.startTime(stat.toString()), "field22 nested parentheses");
        check(RuntimeThreadRules.startTime("123 (broken) S 1") == null, "short stat closed");
        check(RuntimeThreadRules.startTime(null) == null, "missing stat closed");
        check(RuntimeThreadRules.startTime(stat.toString().replace("987654", "oops")) == null, "invalid identity closed");
        check(RuntimeThreadRules.mayRestore("987654", "987654", -2, -2), "owned TID restores");
        check(!RuntimeThreadRules.mayRestore("987654", "987655", -2, -2), "recycled TID never touched");
        check(!RuntimeThreadRules.mayRestore("987654", "987654", -2, 0), "external priority owner preserved");
        check(!RuntimeThreadRules.mayRestore("987654", null, -2, -2), "unreadable identity no write");
    }

    public static void main(String[] args) throws Exception {
        renderer(); jvm(); memory(); thermal(); threads();
        System.out.println("Runtime safety policy: " + checks + " checks passed (pure host tests; no FPS claim)");
    }
}
