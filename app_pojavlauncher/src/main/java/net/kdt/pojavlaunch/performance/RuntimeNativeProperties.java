package net.kdt.pojavlaunch.performance;

import java.io.File;

/** Route-scoped native property validation; optional JVM modules stay optional. */
public final class RuntimeNativeProperties {
    private RuntimeNativeProperties() {}
    public static String freetypePath(String lwjglDirectory) {
        if (lwjglDirectory == null || lwjglDirectory.trim().isEmpty()) return null;
        File library = new File(lwjglDirectory, "libfreetype.so");
        if (!library.isFile() || library.length() == 0)
            throw new IllegalStateException("Missing Android LWJGL Freetype: " + library
                    + "; restart launcher to re-extract natives for the running process ABI");
        return library.getAbsolutePath();
    }
}
