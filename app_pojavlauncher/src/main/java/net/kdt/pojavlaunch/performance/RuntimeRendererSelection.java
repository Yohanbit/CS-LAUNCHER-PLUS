package net.kdt.pojavlaunch.performance;

import java.util.List;

/** Main-entry fallback follows the same manual-only Mesa rule as the native loader. */
public final class RuntimeRendererSelection {
    private RuntimeRendererSelection() {}
    public static String initialFallback(List<String> installed, boolean modern) {
        if (installed == null) return null;
        if (modern && installed.contains("opengles_mobileglues")) return "opengles_mobileglues";
        for (String id : new String[]{"opengles3_ltw", "opengles3_KW", "opengles2"})
            if (installed.contains(id)) return id;
        for (String id : installed) {
            if (!RuntimeRendererRules.isMesa(id) && RuntimeRendererRules.library(id) != null) return id;
        }
        return null;
    }
}
