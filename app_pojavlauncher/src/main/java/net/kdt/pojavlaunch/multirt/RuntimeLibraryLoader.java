package net.kdt.pojavlaunch.multirt;

import java.util.Arrays;

/** Fail-fast loading of required VM entry libraries; no assumptions about optional AWT modules. */
public final class RuntimeLibraryLoader {
    private RuntimeLibraryLoader() {}

    public interface Loader {
        boolean load(String path);
    }

    public static void require(String description, Loader loader, String... candidates) {
        for (String path : candidates) {
            if (path != null && loader.load(path)) return;
        }
        throw new IllegalStateException("Cannot load " + description + " from " + Arrays.toString(candidates)
                + "; check Java runtime installation, process ABI and dependent libraries");
    }
}
