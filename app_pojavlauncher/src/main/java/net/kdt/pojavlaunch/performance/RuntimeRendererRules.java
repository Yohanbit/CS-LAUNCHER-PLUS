package net.kdt.pojavlaunch.performance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure selection/routing. Compatibility is supplied by the install/device probe, not guessed here. */
public final class RuntimeRendererRules {
    private RuntimeRendererRules() {}

    public static String canonical(String renderer) {
        if ("vulkan_zink".equals(renderer) || "opengles3_desktopgl_zink".equals(renderer))
            return "opengles3_desktopgl_zink_kopper";
        return renderer;
    }

    public static String library(String renderer) {
        if (renderer == null) return null;
        switch (renderer) {
            case "opengles2": case "opengles2_5": case "opengles3": return "libgl4es_115.so";
            case "vulkan_zink": return "libOSMesa.so";
            case "opengles_mobileglues": return "libmobileglues.so";
            case "opengles3_desktopgl_zink": case "opengles3_desktopgl_zink_kopper": return "libglxshim.so";
            case "opengles3_ltw": return "libltw.so";
            case "opengles3_KW": return "libng_gl4es.so";
            // Gallium IDs are intentionally unsupported until BOTH bridge-table selection and
            // OSMesa loader binding resolve their actual selected libraries. dlopen alone is unsafe.
            default: return null;
        }
    }

    public static boolean isMesa(String renderer) {
        return "opengles3_desktopgl_zink_kopper".equals(canonical(renderer));
    }

    /** Explicit selection first, then install order. Unverified Vulkan routes are manual-only:
     * Android's Vulkan feature/version metadata cannot certify physical-device extensions or
     * requirements of this bundled Mesa build. Never promote Zink via a GL wrapper's fallback.
     * This is not an FPS ranking or a claim that Vulkan 1.1 is incompatible with every Zink build.
     */
    public static List<String> candidates(String selected, List<String> compatible) {
        List<String> out = new ArrayList<>();
        if (selected == null || compatible == null) return out;
        String wanted = canonical(selected);
        for (String id : compatible) {
            String candidate = canonical(id);
            if (candidate != null && candidate.equals(wanted) && library(candidate) != null) {
                out.add(candidate);
                break;
            }
        }
        for (String id : compatible) {
            String candidate = canonical(id);
            if (isMesa(candidate) && !candidate.equals(wanted)) continue;
            if (library(candidate) != null && !out.contains(candidate)) out.add(candidate);
        }
        return out;
    }

    /** Export these before dlopen as Mesa/wrapper constructors can inspect the environment. */
    public static Map<String, String> routingEnvironment(String renderer) {
        Map<String, String> env = new LinkedHashMap<>();
        if (library(renderer) == null) return env;
        env.put("AMETHYST_RENDERER", renderer);
        if ("opengles3_desktopgl_zink_kopper".equals(canonical(renderer))) {
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            env.put("GALLIUM_DRIVER", "zink");
            // Existing compatibility overrides; not a claim of native OpenGL 4.6 support.
            env.put("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
            env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
            if (!"vulkan_zink".equals(renderer)) {
                env.put("POJAVEXEC_EGL", "libEGL_mesa.so");
                env.put("LIBGL_ES", "3");
            }
        } else if ("opengles3_ltw".equals(renderer)) {
            env.put("LIBGL_ES", "3");
            env.put("POJAVEXEC_EGL", "libltw.so");
        } else if ("opengles_mobileglues".equals(renderer)) {
            env.put("POJAVEXEC_EGL", "libmobileglues.so");
        } else if ("opengles2".equals(renderer) || "opengles3_KW".equals(renderer)) {
            env.put("LIBGL_ES", "2");
        }
        return env;
    }
}
