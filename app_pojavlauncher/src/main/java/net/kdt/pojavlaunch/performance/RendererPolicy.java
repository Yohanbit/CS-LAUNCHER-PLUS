package net.kdt.pojavlaunch.performance;

import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Driver-environment decisions, and the only place that decides them.
 *
 * <p>Driver-specific variables are gated on the selected renderer family, cache size is bounded,
 * and presentation requests respect the player's vsync choice. The launch integrator must rebuild
 * this environment if graphics-library fallback changes the selected renderer.
 *
 * <p>Mailbox is a driver-dependent presentation request, not proof of uncapped presentation or lower
 * latency. Normal keeps the existing presentation choice and skips
 * the rest of the driver retuning.
 */
public final class RendererPolicy {

    private RendererPolicy() {}

    /** Honor launcher Force VSync or Minecraft's stored choice; preserve existing unknown handling. */
    public static boolean playerAskedForVsync(McContext game) {
        try {
            if (net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_FORCE_VSYNC) return true;
        } catch (Throwable ignored) {
        }
        return game != null && game.mcVsyncEnabled();
    }

    /** Which of the two possible owners is asking for vsync, for the diagnostics line. */
    public static String vsyncReason(McContext game) {
        boolean launcherSwitch = false;
        try {
            launcherSwitch = net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_FORCE_VSYNC;
        } catch (Throwable ignored) {
        }
        if (launcherSwitch) return "launcher Force VSync switch";
        if (game != null && game.mcVsyncEnabled()) return "Minecraft options.txt vsync:true";
        return "none";
    }

    /**
     * @param mode the requested profile; only affects the driver-specific tuning, never the pacing
     */
    public static Map<String, String> envFor(PerformanceMode mode, DeviceCapability device,
                                             McContext game, boolean experimental,
                                             List<String> unavailableOut) {
        Map<String, String> env = new LinkedHashMap<>();
        String renderer = game == null || game.rendererId == null ? "" : game.rendererId.toLowerCase(Locale.ROOT);
        boolean zink = renderer.contains("zink");
        boolean mesaBacked = zink || renderer.contains("gallium");

        // Presentation requests: confined to Zink's Mesa/Vulkan path. Actual timing depends on the
        // shipped driver, native bridge and compositor; mailbox does not guarantee uncapped display FPS.
        if (!playerAskedForVsync(game)) {
            // Retain the existing mailbox preference, without claiming a universal latency/FPS gain.
            if (zink) env.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
            // The osmesa bridge (vulkan_zink) reads POJAV_VSYNC_IN_ZINK as a gate on whether the game's
            // own eglSwapInterval(0) is allowed to reach the native window. With the gate closed the
            // driver keeps its default lock. Defaulting the switch is on, so this only fires for installs
            // where somebody — usually the old "Maximum FPS" preset, not a crash report — turned it off;
            // overriding it is therefore limited to the two profiles whose whole job is to not hold back,
            // and it is written into the card as a note instead of happening invisibly.
            if (zink && mode != null && mode.requestsBoost() && !LauncherPreferences.PREF_VSYNC_IN_ZINK) {
                env.put("POJAV_VSYNC_IN_ZINK", "1");
                // Not a card lever (nothing was withheld), but it must not be invisible either: it is a
                // reversal of a stored setting, so it is logged and it shows up in the launch chain.
                android.util.Log.i("CSPerf", "Zink swap-interval gate reopened for the " + mode.key
                        + " profile (the launcher's VSync-in-Zink switch was off; the switch's own"
                        + " warning still applies — turn it back on if a driver update makes Zink crash)");
            }
        }

        if (mode == null || mode == PerformanceMode.NORMAL) {
            // Balanced profile: no driver retuning beyond the pacing above.
            return env;
        }

        if (!mesaBacked) {
            // GL4ES, Angle, MobileGlues and the Vulkan-native paths are not Mesa: writing MESA_* or
            // TU_* variables there is a no-op at best. Say so instead of pretending a lever was pulled.
            if (unavailableOut != null) {
                unavailableOut.add("no driver tuning for the selected renderer ("
                        + (renderer.isEmpty() ? "not chosen yet" : renderer) + ")");
            }
            return env;
        }

        // Allow shader-cache reuse, with a bounded disk budget. Effect depends on driver and workload.
        int totalRamMb = device == null ? 0 : device.totalRamMb;
        String cacheSize = totalRamMb >= 6144 ? "256M" : (totalRamMb >= 3584 ? "128M" : "64M");
        env.put("MESA_SHADER_CACHE_DISABLE", "false");
        env.put("MESA_SHADER_CACHE_MAX_SIZE", cacheSize);

        if (zink) {
            // Existing Zink descriptor strategy; validate against the installed Mesa build.
            env.put("ZINK_DESCRIPTORS", "lazy");
            // Turnip compatibility trade-off, never a default and never appropriate for Mali.
            if (experimental && device != null && device.isAdreno()) env.put("TU_DEBUG", "noconform");
        }

        if (experimental) {
            // Documented but driver-sensitive: threaded command submission has been implicated in
            // crashes on some builds, so it stays opt-in until there is a number for it.
            env.put("mesa_glthread", "true");
            if (zink) env.put("ZINK_DEBUG", "compact");
        }

        return env;
    }

    /**
     * Compatible options plus descriptive history. The stored sessions lack enough context for a
     * controlled comparison, so this method deliberately does not rank renderers.
     */
    public static String recommendationText(android.content.Context ctx, DeviceCapability device) {
        List<String> options = new ArrayList<>();
        try {
            Tools.RenderersList compatible = Tools.getCompatibleRenderers(ctx);
            if (compatible != null && compatible.rendererIds != null) {
                options.addAll(compatible.rendererIds);
            }
        } catch (Throwable ignored) {
        }
        if (options.isEmpty()) return "Renderer: nothing compatible is installed on this device.";

        Map<String, Float> measured = SessionStats.bestMedianFpsByRenderer(ctx);

        StringBuilder b = new StringBuilder("Renderer options on this phone: ");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) b.append(", ");
            Float fps = measured.get(options.get(i));
            b.append(options.get(i));
            if (fps != null) b.append(" (").append((int) (float) fps).append(" fps, mixed-session history)");
        }
        b.append("\nHistory is not a controlled renderer comparison: resolution, world, mods, ")
                .append("FPS cap and thermal conditions can differ. No fastest renderer is inferred. ")
                .append("Compare the same scene and settings on this device before choosing.");
        return b.toString();
    }
}
