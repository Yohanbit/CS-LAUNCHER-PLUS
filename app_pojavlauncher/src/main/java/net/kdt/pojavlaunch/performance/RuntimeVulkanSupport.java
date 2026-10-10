package net.kdt.pojavlaunch.performance;

/** Android Vulkan feature metadata is not a physical-device extension/feature probe. */
public final class RuntimeVulkanSupport {
    private RuntimeVulkanSupport() {}

    public static final int API_1_0 = 1 << 22;

    /** Reject unknown/malformed or alternate-variant metadata rather than guessing a backend API. */
    public static boolean validVersion(int version) {
        return version > 0 && (version >>> 29) == 0 && ((version >>> 22) & 0x7f) > 0;
    }

    public static int highestReportedVersion(int[] versions) {
        int result = 0;
        if (versions != null) for (int version : versions)
            if (validVersion(version) && version > result) result = version;
        return result;
    }

    /** Only permits an explicit launch attempt. It does NOT certify Zink's build-specific needs. */
    public static boolean canAttemptZink(boolean hardwareFeaturesPresent, int reportedVersion) {
        return hardwareFeaturesPresent && validVersion(reportedVersion) && reportedVersion >= API_1_0;
    }

    public static String versionText(int version) {
        if (!validVersion(version)) return "unknown";
        return ((version >>> 22) & 0x7f) + "." + ((version >>> 12) & 0x3ff)
                + "." + (version & 0xfff);
    }
}
