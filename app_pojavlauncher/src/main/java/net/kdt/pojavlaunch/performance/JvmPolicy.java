package net.kdt.pojavlaunch.performance;

import java.util.Collections;
import java.util.List;

/** Optional, reversible G1 experiment. No default GC, code-cache, pre-touch or processor override. */
public final class JvmPolicy {
    private JvmPolicy() {}

    /** Without a selected runtime version, fail closed. The launch path uses the overload below. */
    public static List<String> flagsFor(PerformancePolicy policy) {
        return Collections.emptyList();
    }

    public static List<String> flagsFor(PerformancePolicy policy, int javaVersion, List<String> userArgs) {
        if (policy == null) return Collections.emptyList();
        boolean enabled = policy.experimentalJvmFlags && policy.gcPlan != PerformancePolicy.GcPlan.ERGONOMIC;
        if (!enabled) return Collections.emptyList();
        return Collections.unmodifiableList(RuntimeJvmArguments.g1Experiment(enabled, javaVersion,
                policy.gcPlan == PerformancePolicy.GcPlan.G1_THROUGHPUT, policy.device.cores, userArgs));
    }
}
