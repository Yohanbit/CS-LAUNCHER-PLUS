package net.kdt.pojavlaunch.performance;

import java.util.ArrayList;
import java.util.List;

/** Pure argument decisions. User keys win; an unknown runtime gets no automatic HotSpot flags. */
public final class RuntimeJvmArguments {
    private RuntimeJvmArguments() {}

    public static String optionKey(String arg) {
        if (arg == null) return "";
        if (arg.startsWith("-Xmx")) return "-Xmx";
        if (arg.startsWith("-Xms")) return "-Xms";
        if (arg.startsWith("-XX:+") || arg.startsWith("-XX:-")) return "-XX:" + arg.substring(5);
        int equals = arg.indexOf('=');
        return equals < 0 ? arg : arg.substring(0, equals);
    }

    public static boolean hasKey(List<String> args, String flag) {
        if (args == null) return false;
        String key = optionKey(flag);
        for (String arg : args) if (optionKey(arg).equals(key)) return true;
        return false;
    }

    /** Merge only exact keys; properties with a common prefix do not own one another. */
    public static boolean addDefault(List<String> args, String flag) {
        if (hasKey(args, flag)) return false;
        args.add(flag);
        return true;
    }

    public static void removeKey(List<String> args, String flag) {
        String key = optionKey(flag);
        for (int i = args.size() - 1; i >= 0; i--) {
            if (optionKey(args.get(i)).equals(key)) args.remove(i);
        }
    }

    private static boolean userOwnsCollector(List<String> args) {
        if (args == null) return false;
        for (String arg : args) {
            String key = optionKey(arg);
            if ((key.startsWith("-XX:Use") && key.endsWith("GC"))
                    || key.equals("-XX:ExplicitGCInvokesConcurrent")
                    || key.equals("-XX:DisableExplicitGC")) return true;
        }
        return false;
    }

    /** G1 is available in the bundled HotSpot 8+ runtimes; no diagnostic/experimental unlock needed. */
    public static List<String> g1Experiment(boolean enabled, int javaVersion, boolean throughput,
                                           int cores, List<String> userArgs) {
        return enabled ? g1Experiment(javaVersion, throughput, cores, userArgs) : new ArrayList<>();
    }

    public static List<String> g1Experiment(int javaVersion, boolean throughput, int cores,
                                           List<String> userArgs) {
        List<String> out = new ArrayList<>();
        if (javaVersion < 8 || userOwnsCollector(userArgs)) return out;
        addUnlessOwned(out, userArgs, "-XX:+UseG1GC");
        addUnlessOwned(out, userArgs, "-XX:MaxGCPauseMillis=" + (throughput ? 120 : 50));
        addUnlessOwned(out, userArgs, "-XX:ParallelGCThreads=" + Math.max(1, Math.min(4, cores / 2)));
        addUnlessOwned(out, userArgs, "-XX:ConcGCThreads=" + (cores >= 8 ? 2 : 1));
        addUnlessOwned(out, userArgs, "-XX:+ExplicitGCInvokesConcurrent");
        if (throughput) addUnlessOwned(out, userArgs, "-XX:+ParallelRefProcEnabled");
        return out;
    }

    private static void addUnlessOwned(List<String> out, List<String> user, String flag) {
        if (!hasKey(user, flag) && !hasKey(out, flag)) out.add(flag);
    }

    public static List<String> gcDiagnostics(int javaVersion, String file, List<String> userArgs) {
        List<String> out = new ArrayList<>();
        if (javaVersion < 8 || file == null || file.indexOf(':') >= 0) return out;
        if (userArgs != null) for (String arg : userArgs) {
            if (arg.startsWith("-Xlog") || arg.startsWith("-verbose:gc")
                    || optionKey(arg).startsWith("-XX:PrintGC")) return out;
        }
        if (javaVersion >= 9) {
            out.add("-Xlog:gc*,safepoint:file=" + file + ":time,uptime,level,tags:filecount=2,filesize=8m");
        } else {
            out.add("-Xloggc:" + file);
            out.add("-XX:+PrintGCDetails");
            addUnlessOwned(out, userArgs, "-XX:+UseGCLogFileRotation");
            addUnlessOwned(out, userArgs, "-XX:NumberOfGCLogFiles=2");
            addUnlessOwned(out, userArgs, "-XX:GCLogFileSize=8M");
        }
        return out;
    }
}
