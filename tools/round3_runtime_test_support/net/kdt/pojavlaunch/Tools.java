package net.kdt.pojavlaunch;
import android.content.Context;
import android.content.pm.PackageManager;
import java.io.File;
import java.util.List;
public final class Tools {
    public static String DIR_DATA = System.getProperty("java.io.tmpdir") + "/round3-device-test";
    public static final class RenderersList { public List<String> rendererIds; }
    public static RenderersList getCompatibleRenderers(Context context) { return null; }
    public static boolean checkVulkanSupport(PackageManager pm) { return false; }
    public static void write(String path, String text) {}
    public static String read(File file) { return ""; }
}
