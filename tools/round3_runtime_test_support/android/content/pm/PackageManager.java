package android.content.pm;
public class PackageManager {
    public static final String FEATURE_VULKAN_HARDWARE_VERSION = "android.hardware.vulkan.version";
    public FeatureInfo[] features;
    public FeatureInfo[] getSystemAvailableFeatures() { return features; }
}
