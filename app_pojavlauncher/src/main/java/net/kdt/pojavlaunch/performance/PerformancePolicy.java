package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single decision point of the performance engine.
 *
 * <p>One {@code PerformanceMode} plus what the device can do in and what the session is about to run
 * goes in; one immutable, fully-resolved policy comes out. Call sites are only allowed to read the
 * result — no {@code if (mode == MAXIMUM)} scattered through the launcher, no half-applied mode.
 * Anything the platform or this device cannot provide is reported as unavailable in
 * {@link #describe()} instead of being silently skipped or faked.
 *
 * <p>The policy is <b>frozen at launch</b>. Every axis except heat is fixed before the game starts,
 * because a setting that changes underneath a running JVM produces behaviour nobody can attribute to
 * a cause. Thermal state is the one live axis, applied by {@link ThermalGovernor}, and it can only
 * ever take levers away, never add unrequested ones. A pre-JVM renderer fallback reconciles the
 * renderer context/environment without resolving settings a second time.
 */
public final class PerformancePolicy {

    /** Where GC and heap sizing come from. */
    public enum GcPlan {
        /** Leave the JVM alone: it picks its own collector for this heap (Normal). */
        ERGONOMIC,
        /** G1 with a bounded pause target — the stutter-sensitive, modded case. */
        G1_LOW_PAUSE,
        /** G1 tuned for throughput rather than pause time (Maximum, big heap). */
        G1_THROUGHPUT
    }

    /** How much work the launcher UI does while a game session is on screen. */
    public enum QuietLevel {
        /** Normal background behaviour. */
        OFF,
        /** Stop infinite decor animations and defer non-essential work while the game runs. */
        REDUCED,
        /** REDUCED plus pause sync/telemetry traffic until the session ends. */
        MINIMAL
    }

    public final PerformanceMode mode;
    public final DeviceCapability device;
    public final McContext game;

    // ── scheduler ──
    public final boolean boostGameThreads;
    public final int threadPriority;
    public final int maxBoostedThreads;
    public final boolean adpfSession;

    /** True when the player (launcher switch or options.txt) asked for vsync. Never set by the engine. */
    public final boolean playerVsync;
    /** Who asked for it, so the card and the log can say why a frame is ever waited on. */
    public final String vsyncOwner;

    // ── presentation ──

    // ── JVM ──
    public final GcPlan gcPlan;
    public final int heapBiasPercent;
    /** Advisory heap budget only; 0 means unknown/insufficient headroom. Never overrides user Xmx. */
    public final int maxHeapMb;
    public final boolean experimentalJvmFlags;

    // ── driver / GL ──
    public final Map<String, String> rendererEnv;

    // ── launcher UI ──
    public final QuietLevel quietLevel;

    // ── thermal ──
    public final boolean thermalShedAllowed;
    public final boolean thermalPolling;

    /** Levers this device could not be given, for the UI to state out loud. */
    public final List<String> unavailable;

    private static volatile PerformancePolicy sFrozen;

    private PerformancePolicy(Builder b) {
        mode = b.mode;
        device = b.device;
        game = b.game;
        boostGameThreads = b.boostGameThreads;
        threadPriority = b.threadPriority;
        maxBoostedThreads = b.maxBoostedThreads;
        adpfSession = b.adpfSession;
        playerVsync = b.playerVsync;
        vsyncOwner = b.vsyncOwner;
        gcPlan = b.gcPlan;
        heapBiasPercent = b.heapBiasPercent;
        maxHeapMb = b.maxHeapMb;
        experimentalJvmFlags = b.experimentalJvmFlags;
        rendererEnv = Collections.unmodifiableMap(new LinkedHashMap<>(b.rendererEnv));
        quietLevel = b.quietLevel;
        thermalShedAllowed = b.thermalShedAllowed;
        thermalPolling = b.thermalPolling;
        unavailable = Collections.unmodifiableList(new ArrayList<>(b.unavailable));
    }

    /** Current launch policy; renderer facts may be reconciled before JVM creation after fallback. */
    public static PerformancePolicy current() {
        return sFrozen;
    }

    /** Pre-JVM fallback only: preserve launch decisions, replace renderer-specific facts. */
    public static synchronized PerformancePolicy reconcileRenderer(String renderer) {
        PerformancePolicy old = sFrozen;
        if (old == null || old.game == null || old.game.rendererId.equals(renderer)) return old;
        Builder b = new Builder(old.mode, old.device, old.game.withRenderer(renderer));
        b.boostGameThreads = old.boostGameThreads;
        b.threadPriority = old.threadPriority;
        b.maxBoostedThreads = old.maxBoostedThreads;
        b.adpfSession = old.adpfSession;
        b.playerVsync = old.playerVsync;
        b.vsyncOwner = old.vsyncOwner;
        b.gcPlan = old.gcPlan;
        b.heapBiasPercent = old.heapBiasPercent;
        b.maxHeapMb = old.maxHeapMb;
        b.experimentalJvmFlags = old.experimentalJvmFlags;
        b.quietLevel = old.quietLevel;
        b.thermalShedAllowed = old.thermalShedAllowed;
        b.thermalPolling = old.thermalPolling;
        for (String note : old.unavailable) {
            if (!note.startsWith("no driver tuning for the selected renderer")) b.unavailable.add(note);
        }
        b.rendererEnv.putAll(RendererPolicy.envFor(b.mode, b.device, b.game,
                b.experimentalJvmFlags, b.unavailable));
        sFrozen = b.build();
        return sFrozen;
    }

    /** Resolves and freezes. Called once from the launch path, before the JVM is created. */
    public static PerformancePolicy resolveAndFreeze(Context ctx, SharedPreferences prefs, McContext game) {
        PerformancePolicy p = resolve(ctx, prefs, game);
        sFrozen = p;
        return p;
    }

    public static PerformancePolicy resolve(Context ctx, SharedPreferences prefs, McContext game) {
        PerformanceMode mode = PerformanceMode.fromPrefs(prefs);
        DeviceCapability device = DeviceCapability.current();
        return newBuilder(mode, device, game == null ? new McContext(null, null, null, 0, false, false, false) : game,
                prefs, ctx).build();
    }

    public static Builder newBuilder(PerformanceMode mode, DeviceCapability device, McContext game,
                                     SharedPreferences prefs, Context ctx) {
        Builder b = new Builder(mode, device, game);
        b.experimentalJvmFlags = mode.isExperimentalEnabled(prefs);

        // ── presentation: the launcher never asks the display to hold back.
        // Sustained performance mode is vendor-dependent power/thermal policy, not a refresh-rate
        // setter and not proof of a 60 FPS limit. No unmeasured sustained-mode request is made here.
        b.playerVsync = RendererPolicy.playerAskedForVsync(game);
        b.vsyncOwner = RendererPolicy.vsyncReason(game);

        // ── scheduler
        b.boostGameThreads = mode.requestsBoost() && b.experimentalJvmFlags;
        b.maxBoostedThreads = b.boostGameThreads ? 2 : 0;
        // Favorable nice value only. Display/urgent display competes with composition/audio.
        b.threadPriority = android.os.Process.THREAD_PRIORITY_DEFAULT - 2;
        // FPS, swap duration and wall frame intervals are not genuine CPU work durations.
        b.adpfSession = false;
        if (mode == PerformanceMode.MAXIMUM) {
            b.unavailable.add(Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    ? "ADPF needs Android 12 and genuine CPU work timing (disabled)"
                    : "ADPF disabled: genuine CPU work timing/membership unavailable");
        }
        if (mode.requestsBoost() && !b.experimentalJvmFlags) {
            b.unavailable.add("scheduler/GC experiments off; no device benefit established");
        }

        // ── JVM: only what has a reason and a citation; sizing is clamped to what Android allows.
        int ceiling = ctx != null ? Tools.getMaximumRamAllocation(ctx) : Integer.MAX_VALUE;
        int base = LauncherPreferences.PREF_RAM_ALLOCATION > 0 ? LauncherPreferences.PREF_RAM_ALLOCATION : 1024;
        b.heapBiasPercent = 0; // Heap inflation is not GPU headroom or a proven optimization.
        b.maxHeapMb = RuntimeMemoryBudget.recommendedHeapMb(device.totalRamMb, device.availRamMb, ceiling);
        if (b.maxHeapMb == 0) b.unavailable.add("Android/native/GPU headroom cannot be estimated safely");
        else if (base > b.maxHeapMb) b.unavailable.add("selected heap exceeds advisory Android/native/GPU budget ("
                + b.maxHeapMb + " MB); user heap preserved");
        if (device.partial) b.unavailable.add("Device profile still building — user heap unchanged");
        b.gcPlan = pickGcPlan(mode, device, game, b.experimentalJvmFlags);
        if (mode == PerformanceMode.EXTREME) b.gcPlan = GcPlan.ERGONOMIC;

        // ── launcher UI
        b.quietLevel = mode == PerformanceMode.NORMAL ? QuietLevel.OFF
                : ((mode == PerformanceMode.MAXIMUM || mode == PerformanceMode.EXTREME) ? QuietLevel.MINIMAL : QuietLevel.REDUCED);

        // ── thermal
        b.thermalPolling = mode.requestsBoost();
        // Both boost profiles back off when the device reports heat; Normal asks for nothing, so it
        // has nothing to shed and does not even sample.
        b.thermalShedAllowed = mode.requestsBoost();

        // ── renderer env, capability-gated
        b.rendererEnv.putAll(RendererPolicy.envFor(mode, device, game, b.experimentalJvmFlags, b.unavailable));
        return b;
    }

    private static GcPlan pickGcPlan(PerformanceMode mode, DeviceCapability device, McContext game,
                                     boolean experimental) {
        if (mode == PerformanceMode.NORMAL || !experimental) return GcPlan.ERGONOMIC;
        boolean heavySession = game.allocationPressureClass() >= 2;
        boolean bigHeap = device.totalRamMb >= 6144 || game.isModern() && heavySession;
        // Throughput tuning is a Maximum-Performance decision only: it trades pause time for total
        // work, which is the wrong shape for a profile whose promise is smoothness.
        if (mode == PerformanceMode.MAXIMUM && bigHeap) return GcPlan.G1_THROUGHPUT;
        return GcPlan.G1_LOW_PAUSE;
    }

    public boolean isNormal() {
        return mode == PerformanceMode.NORMAL;
    }

    /** Short, human-readable account of exactly what this launch will do. Used by the settings card. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        b.append(mode.headline).append('\n');
        List<String> on = new ArrayList<>();
        if (boostGameThreads) on.add("game threads at " + priorityName(threadPriority));
        if (adpfSession) on.add("ADPF hint session");
        on.add(playerVsync
                ? "vsync held by the player (" + vsyncOwner + ")"
                : "no VSync request detected; FPS policy, game and driver limits are separate");
        if (gcPlan != GcPlan.ERGONOMIC) on.add(gcPlan == GcPlan.G1_LOW_PAUSE ? "low-pause G1" : "throughput G1");
        if (heapBiasPercent > 0) on.add("heap +" + heapBiasPercent + "% (" + maxHeapMb + " MB cap)");
        if (!rendererEnv.isEmpty()) on.add("renderer env: " + rendererEnv.keySet());
        if (quietLevel != QuietLevel.OFF) on.add("launcher UI " + quietLevel.name().toLowerCase(java.util.Locale.ROOT));
        if (thermalShedAllowed) on.add("thermal shed on severe throttling");
        b.append(on.isEmpty() ? "Nothing requested — the platform decides" : join(on, " · "));
        if (!unavailable.isEmpty()) b.append("\nNot available here: ").append(join(unavailable, ", "));
        return b.toString();
    }

    private static String priorityName(int priority) {
        if (priority == android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) return "urgent display priority";
        if (priority == android.os.Process.THREAD_PRIORITY_DISPLAY) return "display priority";
        return "priority " + priority;
    }

    /** minSdk 21 has no String.join and the app does not enable core-library desugaring. */
    private static String join(List<String> items, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(items.get(i));
        }
        return b.toString();
    }

    /** JVM arguments derived from this policy. Order is fixed so A/B runs are comparable. */
    public List<String> jvmFlags() {
        return JvmPolicy.flagsFor(this);
    }

    public List<String> jvmFlags(int javaVersion, List<String> userArgs) {
        return JvmPolicy.flagsFor(this, javaVersion, userArgs);
    }

    public static void clear() {
        sFrozen = null;
    }

    // ── builder ──

    public static final class Builder {
        private final PerformanceMode mode;
        private final DeviceCapability device;
        private final McContext game;
        private final List<String> unavailable = new ArrayList<>();
        private final Map<String, String> rendererEnv = new LinkedHashMap<>();

        private boolean boostGameThreads;
        private int threadPriority = android.os.Process.THREAD_PRIORITY_DEFAULT;
        private int maxBoostedThreads;
        private boolean adpfSession;
        private boolean playerVsync;
        private String vsyncOwner = "none";
        private GcPlan gcPlan = GcPlan.ERGONOMIC;
        private int heapBiasPercent;
        private int maxHeapMb;
        private boolean experimentalJvmFlags;
        private QuietLevel quietLevel = QuietLevel.OFF;
        private boolean thermalShedAllowed;
        private boolean thermalPolling;

        Builder(PerformanceMode mode, DeviceCapability device, McContext game) {
            this.mode = mode;
            this.device = device;
            this.game = game;
        }

        public PerformancePolicy build() {
            return new PerformancePolicy(this);
        }
    }
}
