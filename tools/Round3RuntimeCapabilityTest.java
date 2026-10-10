import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.performance.DeviceCapability;
import net.kdt.pojavlaunch.performance.RuntimeRendererRules;
import net.kdt.pojavlaunch.performance.RuntimeVulkanSupport;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/** Production helpers/classes run against controllable Android API seams, not a driver benchmark. */
public final class Round3RuntimeCapabilityTest {
    private static int checks;
    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }
    private static void equal(Object expected, Object actual, String reason) {
        check(expected.equals(actual), reason + ": " + expected + " != " + actual);
    }
    private static int api(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }
    private static FeatureInfo feature(String name, int version) {
        FeatureInfo info = new FeatureInfo(); info.name = name; info.version = version; return info;
    }
    private static void expectRuntimeRejected(String arch) {
        boolean rejected = false;
        try { Architecture.requireCompatibleRuntimeArchitecture(arch); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "runtime must reject " + arch + " in process " + System.getProperty("os.arch"));
    }

    private static void architecture() {
        String original = System.getProperty("os.arch");
        try {
            // 64-bit hardware deliberately remains constant through 32-bit process cases.
            check(Architecture.is64BitsDevice(), "fixture is 64-bit capable hardware");
            for (int sdk : new int[]{21, 22, 23, 34}) {
                Build.VERSION.SDK_INT = sdk;
                for (String arch : new String[]{"armv7l", "aarch64", "i686", "x86_64"}) {
                    System.setProperty("os.arch", arch);
                    boolean wide = arch.equals("aarch64") || arch.equals("x86_64");
                    Process.bit64 = wide;
                    Process.calls = 0;
                    equal(wide, Architecture.is64BitProcess(), "actual width API" + sdk + " " + arch);
                    equal(!wide, Architecture.is32BitProcess(), "actual narrow width");
                    check(sdk >= 23 ? Process.calls == 2 : Process.calls == 0, "API23 guard");
                    int expected = arch.startsWith("arm") ? Architecture.ARCH_ARM
                            : arch.equals("aarch64") ? Architecture.ARCH_ARM64
                            : arch.equals("i686") ? Architecture.ARCH_X86 : Architecture.ARCH_X86_64;
                    equal(expected, Architecture.getProcessArchitecture(), "VM instruction family");
                    equal(expected, Architecture.getDeviceArchitecture(), "legacy runtime selector is process ABI");
                    equal(wide ? Architecture.ADDRESS_SPACE_LIMIT_64_BIT : Architecture.ADDRESS_SPACE_LIMIT_32_BIT,
                            Architecture.getAddressSpaceLimit(), "address space belongs to process");
                    Architecture.requireCompatibleRuntimeArchitecture(Architecture.archAsString(expected));
                    check(true, "matching embedded JVM accepted");
                    expectRuntimeRejected(wide ? (arch.equals("aarch64") ? "arm" : "x86")
                            : (arch.startsWith("arm") ? "aarch64" : "amd64"));
                    expectRuntimeRejected(arch.startsWith("arm") || arch.equals("aarch64") ? "x86_64" : "arm64");
                    expectRuntimeRejected(null);
                }
            }
            Build.VERSION.SDK_INT = 23; Process.bit64 = false;
            System.setProperty("os.arch", "aarch64");
            equal(Architecture.ARCH_ARM, Architecture.getProcessArchitecture(), "public API beats VM width text");
            Process.bit64 = true; System.setProperty("os.arch", "armv7l");
            equal(Architecture.ARCH_ARM64, Architecture.getProcessArchitecture(), "public API controls actual width");
            for (int sdk : new int[]{21, 22, 23, 34}) {
                Build.VERSION.SDK_INT = sdk;
                for (String unknown : new String[]{"unknown", "mips64", "", "riscv64"}) {
                    System.setProperty("os.arch", unknown);
                    equal(Architecture.UNSUPPORTED_ARCH, Architecture.getProcessArchitecture(), "unknown process not device-guessed");
                    expectRuntimeRejected("arm64");
                    if (sdk < 23) check(Architecture.is32BitProcess(), "unknown API21-22 width narrows safely");
                }
                System.clearProperty("os.arch");
                equal(Architecture.UNSUPPORTED_ARCH, Architecture.getProcessArchitecture(), "missing process ABI fails closed");
                expectRuntimeRejected("arm");
            }
            Locale previous = Locale.getDefault();
            try { Locale.setDefault(new Locale("tr", "TR"));
                equal(Architecture.ARCH_X86, Architecture.archAsInt("I686"), "ABI matching is locale independent");
            } finally { Locale.setDefault(previous); }
        } finally { if (original != null) System.setProperty("os.arch", original); }
    }

    private static void vulkan() throws Exception {
        for (int invalid : new int[]{0, -1, 4096, 0x20000000 | api(1, 1, 0)}) {
            check(!RuntimeVulkanSupport.validVersion(invalid), "invalid metadata not evidence");
            check(!RuntimeVulkanSupport.canAttemptZink(true, invalid), "unknown cannot advertise manual attempt");
            equal("unknown", RuntimeVulkanSupport.versionText(invalid), "invalid label honest");
        }
        for (int minor : new int[]{0, 1, 2, 3}) {
            int version = api(1, minor, 7);
            check(RuntimeVulkanSupport.canAttemptZink(true, version), "Vulkan1." + minor + " manual attempt not universally rejected");
            check(!RuntimeVulkanSupport.canAttemptZink(false, version), "version alone not feature evidence");
            equal("1." + minor + ".7", RuntimeVulkanSupport.versionText(version), "declared version label");
        }
        equal(api(1, 3, 0), RuntimeVulkanSupport.highestReportedVersion(new int[]{0, api(1, 1, 0), -1, api(1, 3, 0)}), "highest valid declaration");
        equal(0, RuntimeVulkanSupport.highestReportedVersion(null), "missing declaration");
        PackageManager pm = new PackageManager();
        pm.features = new FeatureInfo[]{null, feature("android.hardware.vulkan.level", 1),
                feature("wrong.name", api(1, 3, 0)),
                feature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, api(1, 1, 0))};
        for (int sdk : new int[]{21, 22, 23}) {
            Build.VERSION.SDK_INT = sdk;
            equal(0, DeviceCapability.reportedVulkanVersion(pm), "Vulkan feature API24 guard");
        }
        Build.VERSION.SDK_INT = 34;
        equal(api(1, 1, 0), DeviceCapability.reportedVulkanVersion(pm), "reads exact FeatureInfo.name/version");
        equal(0, DeviceCapability.reportedVulkanVersion(null), "null PackageManager unknown");
        pm.features = null;
        equal(0, DeviceCapability.reportedVulkanVersion(pm), "null feature list unknown");
        PackageManager broken = new PackageManager() {
            @Override public FeatureInfo[] getSystemAvailableFeatures() { throw new IllegalStateException("test unavailable"); }
        };
        equal(0, DeviceCapability.reportedVulkanVersion(broken), "feature probe errors fail unknown");

        System.setProperty("os.arch", "armv7l"); Process.bit64 = false;
        Constructor<DeviceCapability> ctor = DeviceCapability.class.getDeclaredConstructor(String.class, String.class,
                String.class, String.class, int.class, int.class, int.class, boolean.class, boolean.class,
                int.class, int.class, java.util.List.class);
        ctor.setAccessible(true);
        for (String gpu : new String[]{"Mali", "Mali-G76", "Adreno", "unknown"}) {
            for (int version : new int[]{api(1, 1, 0), api(1, 3, 0)}) {
                DeviceCapability cap = ctor.newInstance("test", "test", gpu, "arm64-v8a", 8, 8192, 4096,
                        false, true, version, 3, Arrays.asList("opengles3_desktopgl_zink_kopper", "opengles_mobileglues"));
                check(!cap.hasMatureVulkan(), "GPU+GLES3 cannot certify Zink " + gpu);
                check(!cap.is64Bit, "device snapshot width is process, not ABI text");
                check(cap.describe().contains("reported (backend requirements unverified)"), "UI version uncertainty");
                check(cap.describe().contains(RuntimeVulkanSupport.versionText(version)), "UI includes version");
            }
        }
        Method key = DeviceCapability.class.getDeclaredMethod("deviceKey"); key.setAccessible(true);
        String narrow = (String) key.invoke(null);
        Process.bit64 = true; System.setProperty("os.arch", "aarch64");
        check(!narrow.equals(key.invoke(null)), "cache cannot cross process ABI");
        String originalKey = (String) key.invoke(null); BuildConfig.VERSION_CODE++;
        check(!originalKey.equals(key.invoke(null)), "cache invalidated by APK upgrade/library inventory");
    }

    private static void candidates() {
        String zink = "opengles3_desktopgl_zink_kopper", wrapper = "opengles_mobileglues";
        java.util.List<String> advertised = Arrays.asList(zink, wrapper, "opengles2", "vulkan_zink");
        equal(Arrays.asList(wrapper, "opengles2"), RuntimeRendererRules.candidates(wrapper, advertised), "wrapper cannot autopromote unknown Vulkan requirements");
        equal(Arrays.asList("opengles2", wrapper), RuntimeRendererRules.candidates("opengles2", advertised), "GL fallback excludes unverified Zink");
        equal(Arrays.asList(zink, wrapper, "opengles2"), RuntimeRendererRules.candidates("vulkan_zink", advertised), "explicit Zink alias attempt preserved; wrappers are fallback");
        equal(Arrays.asList(wrapper, "opengles2"), RuntimeRendererRules.candidates("bogus", advertised), "invalid preference cannot autopromote Zink");
        equal(Collections.emptyList(), RuntimeRendererRules.candidates(wrapper, Collections.singletonList(zink)), "no wrapper and only unknown Zink fails closed");
        equal(Collections.emptyList(), RuntimeRendererRules.candidates(null, advertised), "AWT no rendering");
        check(!RuntimeRendererRules.routingEnvironment(wrapper).containsKey("GALLIUM_DRIVER"), "no Vulkan driver routing on wrapper");
    }

    public static void main(String[] args) throws Exception {
        architecture(); vulkan(); candidates();
        System.out.println("Round3 runtime capability: " + checks + " checks passed (API seams/pure helpers; no device certification)");
    }
}
