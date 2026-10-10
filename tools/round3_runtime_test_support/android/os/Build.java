package android.os;
public final class Build {
    public static final class VERSION { public static int SDK_INT = 34; }
    public static String[] SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a"};
    public static String[] SUPPORTED_64_BIT_ABIS = {"arm64-v8a"};
    public static String[] SUPPORTED_32_BIT_ABIS = {"armeabi-v7a"};
    public static String CPU_ABI = "arm64-v8a";
    public static String SOC_MODEL = "test", SOC_MANUFACTURER = "test", HARDWARE = "test";
    public static String FINGERPRINT = "test", BOARD = "test";
}
