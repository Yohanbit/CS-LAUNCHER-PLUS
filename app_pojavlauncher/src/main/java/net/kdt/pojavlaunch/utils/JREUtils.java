package net.kdt.pojavlaunch.utils;

import static net.kdt.pojavlaunch.Architecture.ARCH_X86;
import static net.kdt.pojavlaunch.Architecture.archAsStringAndroid;
import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.Architecture.is64BitsDevice;
import static net.kdt.pojavlaunch.Tools.LOCAL_RENDERER;
import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_DUMP_SHADERS;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_VSYNC_IN_ZINK;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_ZINK_PREFER_SYSTEM_DRIVER;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.system.*;
import android.util.*;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;

import com.oracle.dalvik.*;
import java.io.*;
import java.util.*;
import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.lifecycle.LifecycleAwareAlertDialog;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.multirt.RuntimeLibraryLoader;
import net.kdt.pojavlaunch.plugins.FFmpegPlugin;
import net.kdt.pojavlaunch.prefs.*;
import net.kdt.pojavlaunch.performance.RuntimeJvmArguments;
import net.kdt.pojavlaunch.performance.RuntimeMemoryBudget;
import net.kdt.pojavlaunch.performance.RuntimeRendererRules;
import net.kdt.pojavlaunch.performance.JvmPolicy;

import org.lwjgl.glfw.*;

public class JREUtils {

    /** Only engine-owned renderer keys; custom_env.txt is reapplied after each reset. */
    private static final String[] RENDERER_ENV_KEYS = {
            "AMETHYST_RENDERER", "POJAVEXEC_EGL", "LIBGL_ES", "POJAV_VSYNC_IN_ZINK",
            "MESA_LOADER_DRIVER_OVERRIDE", "GALLIUM_DRIVER", "MESA_GL_VERSION_OVERRIDE",
            "MESA_GLSL_VERSION_OVERRIDE", "MESA_GLSL_CACHE_DIR", "MESA_SHADER_CACHE_DIR",
            "MESA_SHADER_CACHE_DISABLE", "MESA_SHADER_CACHE_MAX_SIZE", "MESA_VK_WSI_PRESENT_MODE",
            "mesa_glthread", "ZINK_DESCRIPTORS", "ZINK_DEBUG", "TU_DEBUG", "FD_DEV_FEATURES",
            "POJAV_LOAD_TURNIP", "force_glsl_extensions_warn", "allow_higher_compat_version",
            "allow_glsl_extension_directive_midshader"
    };

    private JREUtils() {}

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;

    public static String findInLdLibPath(String libName) {
        if(Os.getenv("LD_LIBRARY_PATH")==null) {
            try {
                if (LD_LIBRARY_PATH != null) {
                    Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
                }
            }catch (ErrnoException e) {
                e.printStackTrace();
            }
            return libName;
        }
        for (String libPath : Os.getenv("LD_LIBRARY_PATH").split(":")) {
            File f = new File(libPath, libName);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return libName;
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> returnValue = new ArrayList<>();
        File[] list = path.listFiles();
        if(list != null) {
            for(File f : list) {
                if(f.isFile() && f.getName().endsWith(".so")) {
                    returnValue.add(f);
                }else if(f.isDirectory()) {
                    returnValue.addAll(locateLibs(f));
                }
            }
        }
        return returnValue;
    }

    public static void initJavaRuntime(String jreHome) {
        RuntimeLibraryLoader.require("Java launcher libjli.so for " + jreHome,
                JREUtils::dlopen, findInLdLibPath("libjli.so"));
        RuntimeLibraryLoader.require("Java VM libjvm.so for " + jreHome,
                JREUtils::dlopen, "libjvm.so", jvmLibraryPath + "/libjvm.so");
        dlopen(findInLdLibPath("libverify.so"));
        dlopen(findInLdLibPath("libjava.so"));
        // dlopen(findInLdLibPath("libjsig.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        dlopen(findInLdLibPath("libfreetype.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
        for(File f : locateLibs(new File(jreHome, Tools.DIRNAME_HOME_JRE))) {
            dlopen(f.getAbsolutePath());
        }
        dlopen(NATIVE_LIB_DIR + "/libopenal.so");
    }

    public static void redirectAndPrintJRELog() {

        Log.v("jrelog","Log starts here");
        new Thread(new Runnable(){
            int failTime = 0;
            ProcessBuilder logcatPb;
            @Override
            public void run() {
                try {
                    if (logcatPb == null) {
                        // No filtering by tag anymore as that relied on incorrect log levels set in log.h
                        logcatPb = new ProcessBuilder().command("logcat", /* "-G", "1mb", */ "-v", "brief", "-s", "jrelog", "LIBGL", "NativeInput").redirectErrorStream(true);
                    }

                    Log.i("jrelog-logcat","Clearing logcat");
                    new ProcessBuilder().command("logcat", "-c").redirectErrorStream(true).start();
                    Log.i("jrelog-logcat","Starting logcat");
                    java.lang.Process p = logcatPb.start();

                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = p.getInputStream().read(buf)) != -1) {
                        String currStr = new String(buf, 0, len);
                        Logger.appendToLog(currStr);
                    }

                    if (p.waitFor() != 0) {
                        Log.e("jrelog-logcat", "Logcat exited with code " + p.exitValue());
                        failTime++;
                        Log.i("jrelog-logcat", (failTime <= 10 ? "Restarting logcat" : "Too many restart fails") + " (attempt " + failTime + "/10");
                        if (failTime <= 10) {
                            run();
                        } else {
                            Logger.appendToLog("ERROR: Unable to get more log.");
                        }
                    }
                } catch (Throwable e) {
                    Log.e("jrelog-logcat", "Exception on logging thread", e);
                    Logger.appendToLog("Exception on logging thread:\n" + Log.getStackTraceString(e));
                }
            }
        }).start();
        Log.i("jrelog-logcat","Logcat thread started");

    }

    public static void relocateLibPath(Runtime runtime, String jreHome) {
        if (runtime == null || runtime.arch == null || runtime.javaVersion == 0) {
            throw new IllegalArgumentException("Selected Java runtime has invalid release metadata");
        }
        // A previous launch may have used Java 8's lib/<arch>. Modern runtimes use lib/.
        Tools.DIRNAME_HOME_JRE = "lib";
        String JRE_ARCHITECTURE = runtime.arch;
        if (Architecture.archAsInt(JRE_ARCHITECTURE) == ARCH_X86){
            JRE_ARCHITECTURE = "i386/i486/i586";
        }

        for (String arch : JRE_ARCHITECTURE.split("/")) {
            File f = new File(jreHome, "lib/" + arch);
            if (f.exists() && f.isDirectory()) {
                Tools.DIRNAME_HOME_JRE = "lib/" + arch;
            }
        }

        String libName = is64BitsDevice() ? "lib64" : "lib";
        StringBuilder ldLibraryPath = new StringBuilder();
        if(FFmpegPlugin.isAvailable) {
            ldLibraryPath.append(FFmpegPlugin.libraryPath).append(":");
        }
        ldLibraryPath.append(jreHome)
                .append("/").append(Tools.DIRNAME_HOME_JRE)
                .append("/jli:").append(jreHome).append("/").append(Tools.DIRNAME_HOME_JRE)
                .append(":");
        ldLibraryPath.append("/system/").append(libName).append(":")
                .append("/vendor/").append(libName).append(":")
                .append("/vendor/").append(libName).append("/hw:")
                .append(NATIVE_LIB_DIR);
        // FIXME: Freetype is shipped inside lwjgl. We should ship it outside and use lwjgl native jars instead.
        // Use the dynamically determined LWJGL natives directory (set by generateLibClasspath)
        // to match the version required by the active modpack (3.3.3 or 3.4.1).
        if (Tools.lwjglNativesDir != null) {
            ldLibraryPath.append(":").append(Tools.lwjglNativesDir);
        } else {
            ldLibraryPath.append(String.format(":%s/lwjgl-3.3.3-natives/%s", Tools.DIR_DATA, archAsStringAndroid(getDeviceArchitecture())));
        }
        LD_LIBRARY_PATH = ldLibraryPath.toString();
    }

    public static void setJavaEnvironment(Activity activity, String jreHome) throws Throwable {
        clearRendererEnvironment();
        Map<String, String> envMap = new ArrayMap<>();
        envMap.put("POJAV_NATIVEDIR", NATIVE_LIB_DIR);
        envMap.put("JAVA_HOME", jreHome);
        envMap.put("HOME", Tools.DIR_GAME_HOME);
        envMap.put("TMPDIR", Tools.DIR_CACHE.getAbsolutePath());
        envMap.put("LIBGL_MIPMAP", "3");

        // Prevent OptiFine (and other error-reporting stuff in Minecraft) from balooning the log
        envMap.put("LIBGL_NOERROR", "1");

        // On certain GLES drivers, overloading default functions shader hack fails, so disable it
        envMap.put("LIBGL_NOINTOVLHACK", "1");

        // Fix white color on banner and sheep, since GL4ES 1.1.5
        envMap.put("LIBGL_NORMALIZE", "1");

        if(PREF_DUMP_SHADERS)
            envMap.put("LIBGL_VGPU_DUMP", "1");
        if(PREF_VSYNC_IN_ZINK && LOCAL_RENDERER != null && LOCAL_RENDERER.contains("zink"))
            envMap.put("POJAV_VSYNC_IN_ZINK", "1");
        if(Tools.deviceHasHangingLinker())
            envMap.put("POJAV_EMUI_ITERATOR_MITIGATE", "1");


        // The OPEN GL version is changed according
        String requestedGlVersion = (String) ExtraCore.getValue(ExtraConstants.OPEN_GL_VERSION);
        if (requestedGlVersion != null) envMap.put("LIBGL_ES", requestedGlVersion);

        envMap.put("FORCE_VSYNC", String.valueOf(LauncherPreferences.PREF_FORCE_VSYNC));

        envMap.putAll(RuntimeRendererRules.routingEnvironment(LOCAL_RENDERER));
        if (RuntimeRendererRules.isMesa(LOCAL_RENDERER)) {
            envMap.put("MESA_GLSL_CACHE_DIR", Tools.DIR_CACHE.getAbsolutePath());
            envMap.put("MESA_SHADER_CACHE_DIR", Tools.DIR_CACHE.getAbsolutePath());
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
        }
        // ── CS PERFORMANCE ENGINE: driver tuning, gated on the renderer actually selected ──
        // Nothing is written for a non-Mesa renderer, and the balanced profile writes nothing at all:
        // the previous code applied MESA_*/TU_* variables whatever the renderer was, including a
        // 512 MB shader-cache budget and a mailbox present mode that overrode the vsync preference.
        net.kdt.pojavlaunch.performance.PerformancePolicy envPolicy =
                net.kdt.pojavlaunch.performance.PerformancePolicy.reconcileRenderer(LOCAL_RENDERER);
        if (envPolicy != null) {
            envMap.putAll(envPolicy.rendererEnv);
        }
        // The presentation chain is where a lost frame hides, so every value that can pace a frame is
        // logged next to the process about to be started. One 'adb logcat -s CSPerf' answers
        // "why is this 60?" without a debugger, and the same text lands in performance/ in the data dir.

        envMap.put("VTEST_SOCKET_NAME", new File(Tools.DIR_CACHE, ".virgl_test").getAbsolutePath());

        envMap.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        envMap.put("PATH", jreHome + "/bin:" + Os.getenv("PATH"));
        if(FFmpegPlugin.isAvailable) {
            envMap.put("POJAV_FFMPEG_PATH", FFmpegPlugin.executablePath);
        }

        if(LOCAL_RENDERER != null) {
            envMap.put("AMETHYST_RENDERER", LOCAL_RENDERER);
            if(LOCAL_RENDERER.equals("opengles3_ltw")) {
                envMap.put("LIBGL_ES", "3");
                envMap.put("POJAVEXEC_EGL","libltw.so"); // Use ANGLE EGL
            }
            if(LOCAL_RENDERER.equals("opengles_mobileglues")){
                envMap.put("MG_DIR_PATH", Tools.DIR_DATA + "/MobileGlues");
                envMap.put("POJAVEXEC_EGL","libmobileglues.so");
            }
            if(LOCAL_RENDERER.equals("opengles2") || LOCAL_RENDERER.equals("opengles3_KW")){
                envMap.put("LIBGL_ES", "2"); // Krypton Wrapper crashes with anything other than 2
            }
            if (LOCAL_RENDERER.equals("opengles3_desktopgl_zink_kopper")){
                envMap.put("LIBGL_ES", "3");
                envMap.put("POJAVEXEC_EGL","libEGL_mesa.so"); // Use Mesa EGL
                if (Tools.shouldUseUBWC()) envMap.put("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1"); // Turnip fix for OneUI rendering issues
            }
            if (LOCAL_RENDERER.toLowerCase().contains("zink")){
                // This is sketch but it fixes a lot of things, if it causes problems we can just undo it.
                envMap.put("MESA_GL_VERSION_OVERRIDE","4.6COMPAT");
                envMap.put("MESA_GLSL_VERSION_OVERRIDE","460");
            }
        }
        if ("unlimited".equals(LauncherPreferences.DEFAULT_PREF.getString("extremeFpsMode", "unlimited"))) {
            envMap.put("CS_UNLIMITED_FPS", "1");
            // An explicit launcher VSync request remains authoritative; the default
            // is off. Unlimited disables the game's own option, not a user override.
        }
        try { envMap.put("POJAV_DISPLAY_REFRESH_HZ", String.valueOf(activity.getWindowManager().getDefaultDisplay().getRefreshRate())); }
        catch (Throwable ignored) { }
        envMap.put("AWTSTUB_WIDTH", Integer.toString(CallbackBridge.windowWidth > 0 ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth));
        envMap.put("AWTSTUB_HEIGHT", Integer.toString(CallbackBridge.windowHeight > 0 ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight));

        GLInfoUtils.GLInfo info = GLInfoUtils.getGlInfo();
        if(!envMap.containsKey("LIBGL_ES") && LOCAL_RENDERER != null) {
            int glesMajor = info.glesMajorVersion;
            Log.i("glesDetect","GLES version detected: "+glesMajor);

            if (glesMajor < 3) {
                //fallback to 2 since it's the minimum for the entire app
                envMap.put("LIBGL_ES","2");
            } else if (LOCAL_RENDERER.startsWith("opengles")) {
                envMap.put("LIBGL_ES", LOCAL_RENDERER.replace("opengles", "").replace("_5", ""));
            } else {
                // TODO if can: other backends such as Vulkan.
                // Sure, they should provide GLES 3 support.
                envMap.put("LIBGL_ES", "3");
            }
        }

        if(info.isAdreno() && LOCAL_RENDERER != null && LOCAL_RENDERER.contains("zink")
                && !PREF_ZINK_PREFER_SYSTEM_DRIVER) {
            envMap.put("POJAV_LOAD_TURNIP", "1");
        }

        readCustomEnv(envMap); // Must be last so it overrides anything the user sets for obvious reasons.
        // Record the complete environment after renderer routing and explicit user overrides.
        net.kdt.pojavlaunch.performance.LaunchDiagnostics.write(activity, envMap, envPolicy);

        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue());
            try {
                Os.setenv(env.getKey(), env.getValue(), true);
            }catch (NullPointerException exception){
                Log.e("JREUtils", exception.toString());
            }
        }

        File serverFile = new File(jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/server/libjvm.so");
        jvmLibraryPath = jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/" + (serverFile.exists() ? "server" : "client");
        Log.d("DynamicLoader","Base LD_LIBRARY_PATH: "+LD_LIBRARY_PATH);
        Log.d("DynamicLoader","Internal LD_LIBRARY_PATH: "+jvmLibraryPath+":"+LD_LIBRARY_PATH);
        setLdLibraryPath(jvmLibraryPath+":"+LD_LIBRARY_PATH);

        // return ldLibraryPath;
    }

    private static void readCustomEnv(Map<String, String> envMap) throws IOException {
        File customEnvFile = new File(Tools.DIR_GAME_HOME, "custom_env.txt");
        if (customEnvFile.exists() && customEnvFile.isFile()) {
            BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
            String line;
            while ((line = reader.readLine()) != null) {
                // Not use split() as only split first one
                int index = line.indexOf("=");
                if (index != -1) {
                    envMap.put(line.substring(0, index), line.substring(index + 1));
                } else {
                    // Lines without '=' are comments or malformed; skip.
                    Logger.appendToLog("WARN: Skipping env line without delimiter: " + line);
                }
            }
            reader.close();
        }
    }
    public static void launchJavaVM(final AppCompatActivity activity, final Runtime runtime,
                                    File gameDirectory, final List<String> JVMArgs,
                                    final String userArgsString) throws Throwable {
        launchJavaVM(activity, runtime, gameDirectory, JVMArgs, userArgsString, null);
    }

    public static void launchJavaVM(final AppCompatActivity activity, final Runtime runtime,
                                    File gameDirectory, final List<String> JVMArgs,
                                    final String userArgsString,
                                    @Nullable MinecraftAccount resolvedAccount) throws Throwable {
        // Never re-guess an account when the game launcher already resolved one.
        MinecraftAccount activeAccount = resolvedAccount != null ? resolvedAccount
                : PojavProfile.getCurrentProfileContent(activity, null);
        boolean shouldStopLocalSkinServerAfterLaunch = false;
        boolean isElyBy = activeAccount != null && !activeAccount.isMicrosoft
                && activeAccount.accessToken != null
                && !"0".equals(activeAccount.accessToken)
                && !activeAccount.accessToken.isEmpty();
        // Preparation can fail before VMLauncher (renderer, env, flags or mandatory VM libraries).
        // The launch-owned local skin bridge must be released on those paths too.
        try {
        if (activeAccount != null && !activeAccount.isMicrosoft && !isElyBy) {
            String username = activeAccount.username;
            String uuid = activeAccount.profileId;

            String skinPath = Tools.DIR_DATA + "/skins/" + username + "_skin.png";
            String capePath = Tools.DIR_DATA + "/capes/" + username + "_cape.png";

            File skinFile = new File(skinPath);
            File capeFile = new File(capePath);

            String finalSkinPath = skinFile.exists() ? skinFile.getAbsolutePath() : null;
            String finalCapePath = capeFile.exists() ? capeFile.getAbsolutePath() : null;
            boolean hasCustomTextures = finalSkinPath != null || finalCapePath != null;

            boolean isSlim = false;
            File skinMeta = new File(Tools.DIR_DATA + "/skins/" + username + "_metadata.json");
            if (skinMeta.exists()) {
                try {
                    String metaContent = Tools.read(skinMeta.getAbsolutePath());
                    if (metaContent.contains("slim")) {
                        isSlim = true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            Logger.appendToLog("=== OFFLINE SKIN SYSTEM: PRE-LAUNCH ===");
            Logger.appendToLog("Username: " + username);
            Logger.appendToLog("UUID (from account): " + uuid);
            Logger.appendToLog("UUID (no dashes): " + uuid.replace("-", ""));
            Logger.appendToLog("Skin path: " + skinPath);
            Logger.appendToLog("Skin file exists: " + skinFile.exists());
            if (skinFile.exists()) Logger.appendToLog("Skin file size: " + skinFile.length() + " bytes");
            Logger.appendToLog("Cape path: " + capePath);
            Logger.appendToLog("Cape file exists: " + capeFile.exists());
            if (capeFile.exists()) Logger.appendToLog("Cape file size: " + capeFile.length() + " bytes");
            Logger.appendToLog("Skin model: " + (isSlim ? "ALEX (slim)" : "STEVE (default)"));
            Logger.appendToLog("Metadata file exists: " + skinMeta.exists());
            Logger.appendToLog("Has custom textures: " + hasCustomTextures);

            // Start the bridge for every local/offline account, even when the
            // local player has no custom PNG. Unknown online players and NPC
            // skin names still need Mojang profile/texture proxying.
            net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer.start();
            int serverPort = net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer.getPort();
            shouldStopLocalSkinServerAfterLaunch = serverPort > 0;
            Logger.appendToLog("Yggdrasil skin bridge port: " + serverPort);
            if (hasCustomTextures) {
                net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer.registerProfile(
                        username, uuid, finalSkinPath, finalCapePath, isSlim);
                Logger.appendToLog("Local texture profile registered for: " + username);
            } else {
                Logger.appendToLog("No local PNG; online/NPC proxy bridge remains active.");
            }
            File authlibJar = new File(Tools.DIR_DATA, "authlib-injector/authlib-injector.jar");
            Logger.appendToLog("authlib-injector.jar exists: " + authlibJar.exists());
            Logger.appendToLog("=== OFFLINE SKIN SYSTEM: PRE-LAUNCH COMPLETE ===");
        }

        if (runtime == null) throw new IllegalArgumentException("No Java runtime selected");
        String runtimeHome = MultiRTUtils.getRuntimeHome(runtime.name).getAbsolutePath();

        JREUtils.relocateLibPath(runtime, runtimeHome);

        // Resolve/fallback the renderer before exporting renderer-specific
        // environment variables. Previously a failed Zink/Gallium dlopen left
        // its Mesa variables active even after falling back to Krypton.
        // MG constructor reads config during dlopen. Set the real directory BEFORE loading it.
        // A custom directory is supported upstream: do not impersonate another launcher.
        Os.setenv("MG_DIR_PATH", Tools.DIR_DATA + "/MobileGlues", true);
        final String graphicsLib = loadGraphicsLibrary(activity);
        setJavaEnvironment(activity, runtimeHome);
        List<String> userArgs = getJavaArgs(activity, runtimeHome, userArgsString);

        //Remove arguments that can interfere with the good working of the launcher
        purgeArg(userArgs,"-Xms");
        purgeArg(userArgs,"-Xmx");
        purgeArg(userArgs,"-d32");
        purgeArg(userArgs,"-d64");
        purgeArg(userArgs, "-Xint");
        purgeArg(userArgs, "-XX:+UseTransparentHugePages");
        purgeArg(userArgs, "-XX:+UseLargePagesInMetaspace");
        purgeArg(userArgs, "-XX:+UseLargePages");
        purgeArg(userArgs, "-Dorg.lwjgl.opengl.libname");
        // Don't let the user specify a custom Freetype library (as the user is unlikely to specify a version compiled for Android)
        purgeArg(userArgs, "-Dorg.lwjgl.freetype.libname");
        // Preserve an explicit user processor count. No platform affinity/cluster probe justifies
        // an automatic count, and older Java 8 runtimes may not support ActiveProcessorCount.

        //Add automatically generated args
        // Per-profile RAM was removed (Phase 5) — the global launcher setting rules.
        net.kdt.pojavlaunch.performance.PerformancePolicy perfPolicy =
                net.kdt.pojavlaunch.performance.PerformancePolicy.current();
        int effectiveRam = LauncherPreferences.PREF_RAM_ALLOCATION;
        if (effectiveRam < 256) {
            throw new IllegalArgumentException("Selected Minecraft RAM is invalid: " + effectiveRam + " MB");
        }
        // Exact user selection: warnings happen before launch, never by silently
        // clamping the final JVM heap behind the user's back.
        // Do not commit the whole Xmx heap at startup. On Android, Xms=Xmx plus
        // AlwaysPreTouch competes with the GPU/OS and can trigger LMK or long
        // first-frame stalls. Grow from a modest, aligned initial heap instead.
        RuntimeMemoryBudget.HeapAdvice memoryAdvice = RuntimeMemoryBudget.assess(Tools.getTotalDeviceMemory(activity),
                Tools.getFreeDeviceMemory(activity), Tools.getMaximumRamAllocation(activity));
        if (memoryAdvice.shouldWarn(effectiveRam)) {
            Logger.appendToLog("RAM advisory: selected " + effectiveRam + " MB, conservative Android/native/GPU "
                    + "budget " + memoryAdvice.recommendedMb + " MB (0 means insufficient headroom). "
                    + "User Xmx preserved; this is not a guarantee against Android LMK.");
        } else if (!memoryAdvice.known) {
            Logger.appendToLog("RAM advisory unavailable: unknown memory reading; user Xmx preserved.");
        }
        int initialRam = RuntimeMemoryBudget.initialHeapMb(effectiveRam);
        userArgs.add("-Xms" + initialRam + "M");
        userArgs.add("-Xmx" + effectiveRam + "M");
        Log.i("ProfileLaunch", "Effective JVM RAM: -Xms" + initialRam
                + "M -Xmx" + effectiveRam + "M (mobile adaptive heap)");

        // ── CS PERFORMANCE ENGINE ──
        // Do not purge the user's collector/tuning choices. Defaults add nothing; optional policy
        // flags use the actual selected runtime version and never duplicate an explicit user key.
        String gcVariant = LauncherPreferences.DEFAULT_PREF.getString("developer_gc_variant", "current_cs");
        List<String> policyFlags = "ergonomic_defaults".equals(gcVariant) ? Collections.emptyList()
                : JvmPolicy.flagsFor(perfPolicy, runtime.javaVersion, userArgs);
        userArgs.addAll(policyFlags);
        Log.i("ProfileLaunch", "Runtime Java " + runtime.javaVersion + " automatic JVM experiment flags: " + policyFlags);
        if (LauncherPreferences.DEFAULT_PREF.getBoolean("extremeFrameDiagnostics", false)) {
            File diagnostics = new File(Tools.DIR_DATA, "performance"); diagnostics.mkdirs();
            String gcLog = new File(diagnostics, "gc-" + System.currentTimeMillis() + ".log").getAbsolutePath();
            userArgs.addAll(RuntimeJvmArguments.gcDiagnostics(runtime.javaVersion, gcLog, userArgs));
        }
        // getJavaArgs already supplies the Log4j mitigation. Leave Sodium's safety checks enabled.

        if(LOCAL_RENDERER != null) userArgs.add("-Dorg.lwjgl.opengl.libname=" + graphicsLib);

        // Force LWJGL to use the Freetype library intended for it, instead of using the one
        // that we ship with Java (since it may be older than what's needed)
        userArgs.add("-Dorg.lwjgl.freetype.libname="+ Tools.lwjglNativesDir +"/libfreetype.so");
        // Our spirv-cross is compiled shared, so it gets named shared.
        RuntimeJvmArguments.addDefault(userArgs, "-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared");

        // We don't have jemalloc for our LWJGL so set the allocator to system to avoid error logs
        RuntimeJvmArguments.addDefault(userArgs, "-Dorg.lwjgl.system.allocator=system");

        // Leave processor ergonomics to the selected JVM and Android scheduler.
        // Adds/changes methods for compatibility
        RuntimeJvmArguments.addDefault(userArgs, "-javaagent:"
                + new File(Tools.DIR_DATA,"methods_injector_agent/methods_injector_agent.jar").getAbsolutePath());

        // === STEP 4: Add authlib-injector BEFORE JVMArgs (which contains main class + game args) ===
        File authlibFile = new File(Tools.DIR_DATA, "authlib-injector/authlib-injector.jar");
        if (!authlibFile.exists()) {
            try {
                Tools.copyAssetFile(activity, "components/authlib-injector/authlib-injector.jar", Tools.DIR_DATA + "/authlib-injector", "authlib-injector.jar", true);
            } catch (Throwable t) {
                Log.w("JREUtils", "Could not copy authlib-injector.jar from assets", t);
            }
        }
        boolean isMicrosoftAccount = activeAccount != null && activeAccount.isMicrosoft;
        // Copper/Pojav premium path: do absolutely nothing to Authlib. The
        // Minecraft version's own libraries, environment and Mojang keys stay
        // untouched. Custom injection is isolated to non-Microsoft accounts.
        if (!isMicrosoftAccount) {
            if (isElyBy) {
                String authlibPath = authlibFile.getAbsolutePath();
                if (authlibFile.exists()) {
                    userArgs.add("-javaagent:" + authlibPath + "=https://authserver.ely.by");
                    userArgs.add("-Dauthlibinjector.side=client");
                    userArgs.add("-Dminecraft.api.auth.host=https://authserver.ely.by");
                    userArgs.add("-Dminecraft.api.account.host=https://authserver.ely.by");
                    userArgs.add("-Dminecraft.api.session.host=https://authserver.ely.by");
                    userArgs.add("-Dminecraft.api.services.host=https://authserver.ely.by");
                    Logger.appendToLog("=== ELY.BY AUTHLIB INJECTION ACTIVE ===");
                }
            } else if (net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer.getPort() > 0) {
                int skinPort = net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer.getPort();
                String authlibPath = authlibFile.getAbsolutePath();
                
                userArgs.add("-javaagent:" + authlibPath + "=http://127.0.0.1:" + skinPort);
                userArgs.add("-Dminecraft.api.auth.host=http://127.0.0.1:" + skinPort);
                userArgs.add("-Dminecraft.api.account.host=http://127.0.0.1:" + skinPort);
                userArgs.add("-Dminecraft.api.session.host=http://127.0.0.1:" + skinPort);
                userArgs.add("-Dminecraft.api.services.host=http://127.0.0.1:" + skinPort);
                
                Logger.appendToLog("=== AUTHLIB INJECTION ===");
                Logger.appendToLog("-javaagent:" + authlibPath + "=http://127.0.0.1:" + skinPort);
                Logger.appendToLog("Session host: http://127.0.0.1:" + skinPort);
                Logger.appendToLog("=== AUTHLIB INJECTION COMPLETE ===");
            }
        }

        userArgs.addAll(JVMArgs);
        activity.runOnUiThread(() -> Toast.makeText(activity, activity.getString(R.string.autoram_info_msg,LauncherPreferences.PREF_RAM_ALLOCATION), Toast.LENGTH_SHORT).show());
        System.out.println(JVMArgs);

        initJavaRuntime(runtimeHome);
        JREUtils.setupExitMethod(activity.getApplication());
        JREUtils.initializeHooks();
        chdir(gameDirectory == null ? Tools.DIR_GAME_NEW : gameDirectory.getAbsolutePath());
        userArgs.add(0,"java"); //argv[0] is the program name according to C standard.

            // Log final argument list for debugging
            Logger.appendToLog("=== FINAL JVM ARGS ===");
            for (int i = 0; i < userArgs.size(); i++) {
                Logger.appendToLog("arg[" + i + "]: " + userArgs.get(i));
            }
            Logger.appendToLog("=== END JVM ARGS ===");

            final int exitCode = VMLauncher.launchJVM(userArgs.toArray(new String[0]));
            Logger.appendToLog("Java Exit code: " + exitCode);
            if (exitCode != 0) {
                net.kdt.pojavlaunch.Tools.captureCrashLog();
                net.kdt.pojavlaunch.Tools.routeToCrashScreen(activity, exitCode);
                // Do not fall through to fullyExit(): the launcher process needs
                // enough time to display the dedicated crash-analysis activity.
                return;
            }
        } finally {
            if (shouldStopLocalSkinServerAfterLaunch && net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer.getPort() > 0) {
                net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer.stop();
            }
        }
        // Only hard-exit when the game closed cleanly; crashes are routed to the
        // dedicated crash screen so the user stays inside the launcher.
        Tools.fullyExit();
    }

    /**
     *  Gives an argument list filled with both the user args
     *  and the auto-generated ones (eg. the window resolution).
     * @param ctx The application context
     * @return A list filled with args.
     */
    public static List<String> getJavaArgs(Context ctx, String runtimeHome, String userArgumentsString) {
        List<String> userArguments = parseJavaArguments(userArgumentsString);
        String resolvFile;
        resolvFile = new File(Tools.DIR_DATA,"resolv.conf").getAbsolutePath();

        ArrayList<String> overridableArguments = new ArrayList<>(Arrays.asList(
                "-Djava.home=" + runtimeHome,
                "-Djava.io.tmpdir=" + Tools.DIR_CACHE.getAbsolutePath(),
                "-Djna.boot.library.path=" + NATIVE_LIB_DIR,
                "-Duser.home=" + Tools.DIR_GAME_HOME,
                "-Duser.language=" + System.getProperty("user.language"),
                "-Dos.name=Linux",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Dpojav.path.minecraft=" + Tools.DIR_GAME_NEW,
                "-Dpojav.path.private.account=" + Tools.DIR_ACCOUNT_NEW,
                "-Duser.timezone=" + TimeZone.getDefault().getID(),

                "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
                //LWJGL 3 DEBUG FLAGS
                //"-Dorg.lwjgl.util.Debug=true",
                //"-Dorg.lwjgl.util.DebugFunctions=true",
                //"-Dorg.lwjgl.util.DebugLoader=true",
                // GLFW Stub width height
                "-Dglfwstub.windowWidth=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, LauncherPreferences.PREF_SCALE_FACTOR),
                "-Dglfwstub.windowHeight=" + Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, LauncherPreferences.PREF_SCALE_FACTOR),
                "-Dglfwstub.initEgl=false",
                "-Dext.net.resolvPath=" +resolvFile,
                "-Dlog4j2.formatMsgNoLookups=true", //Log4j RCE mitigation

                "-Dnet.minecraft.clientmodname=" + Tools.APP_NAME,
                "-Dfml.earlyprogresswindow=false", //Forge 1.14+ workaround
                "-Dloader.disable_forked_guis=true",
                "-Djdk.lang.Process.launchMechanism=FORK" // Default is POSIX_SPAWN which requires starting jspawnhelper, which doesn't work on Android
        ));
        if(LauncherPreferences.PREF_ARC_CAPES) {
            overridableArguments.add("-javaagent:"+new File(Tools.DIR_DATA,"arc_dns_injector/arc_dns_injector.jar").getAbsolutePath()+"=23.95.137.176");
        }
        for(String arg : overridableArguments) {
            if (!RuntimeJvmArguments.addDefault(userArguments, arg))
                Log.i("ArgProcessor","Arg skipped: "+arg);
        }

        return userArguments;
    }

    /**
     * Parse and separate java arguments in a user friendly fashion
     * It supports multi line and absence of spaces between arguments
     * The function also supports auto-removal of improper arguments, although it may miss some.
     *
     * @param args The un-parsed argument list.
     * @return Parsed args as an ArrayList
     */
    public static ArrayList<String> parseJavaArguments(String args){
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");
        //For each prefixes, we separate args.
        String[] separators = new String[]{"-XX:-","-XX:+", "-XX:","--", "-D", "-X", "-javaagent:", "-verbose"};
        for(String prefix : separators){
            while (true){
                int start = args.indexOf(prefix);
                if(start == -1) break;
                //Get the end of the current argument by checking the nearest separator
                int end = -1;
                for(String separator: separators){
                    int tempEnd = args.indexOf(separator, start + prefix.length());
                    if(tempEnd == -1) continue;
                    if(end == -1){
                        end = tempEnd;
                        continue;
                    }
                    end = Math.min(end, tempEnd);
                }
                //Fallback
                if(end == -1) end = args.length();

                //Extract it
                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");

                //Check if two args aren't bundled together by mistake
                if(parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if(arraySize > 0){
                        String lastString = parsedArguments.get(arraySize - 1);
                        // Looking for list elements
                        if(lastString.charAt(lastString.length() - 1) == ',' ||
                                parsedSubString.contains(",")){
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                }
                else Log.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
            }
        }
        return parsedArguments;
    }

    /**
     * Open the render library in accordance to the settings.
     * It will fallback if it fails to load the library.
     * @return The name of the loaded library
     */
    public static String loadGraphicsLibrary(){
        return loadGraphicsLibrary(null);
    }

    public static String loadGraphicsLibrary(@Nullable Context context) {
        // AWT/mod installers use this launch path with no game renderer. Preserve that contract:
        // no compatible-renderer probe, native load, or LOCAL_RENDERER mutation for a null selection.
        String requested = LOCAL_RENDERER;
        if (requested == null) return null;
        List<String> compatible;
        if (context != null) {
            Tools.RenderersList list = Tools.getCompatibleRenderers(context);
            compatible = list == null ? Collections.emptyList() : list.rendererIds;
        } else {
            compatible = net.kdt.pojavlaunch.performance.DeviceCapability.current().availableRenderers;
        }
        List<String> candidates = RuntimeRendererRules.candidates(requested, compatible);
        for (String renderer : candidates) {
            String library = RuntimeRendererRules.library(renderer);
            // Only the package's active ABI native directory. No arbitrary LD_LIBRARY_PATH fallback:
            // dlopen success validates loadable ABI/declared dependencies, not all lazy symbols,
            // graphics-context creation or game/mod compatibility.
            File installed = new File(NATIVE_LIB_DIR, library);
            if (!installed.isFile() || !installed.canRead()) {
                Log.w("RENDER_LIBRARY", "Installed library missing: " + renderer + " / " + library);
                continue;
            }
            try { prepareRendererEnvironment(renderer); }
            catch (Throwable e) {
                throw new IllegalStateException("Cannot safely prepare renderer environment: " + renderer, e);
            }
            if (!dlopen(installed.getAbsolutePath())) {
                Log.w("RENDER_LIBRARY", "Library load failed: " + renderer + " (" + installed + ")");
                continue;
            }
            LOCAL_RENDERER = renderer;
            net.kdt.pojavlaunch.performance.PerformancePolicy.reconcileRenderer(renderer);
            if (!renderer.equals(requested)) {
                Logger.appendToLog("Renderer fallback: " + requested + " -> " + renderer
                        + "; compatible installed route, library loaded; game/mod support unverified.");
            }
            return installed.getAbsolutePath();
        }
        // Never pass an unloaded library to LWJGL or silently claim a particular wrapper supports Sodium.
        throw new IllegalStateException("No compatible installed renderer could be loaded (requested "
                + requested + ", attempted " + candidates + ")");
    }

    private static void clearRendererEnvironment() throws ErrnoException {
        for (String key : RENDERER_ENV_KEYS) Os.unsetenv(key);
    }

    private static void prepareRendererEnvironment(String renderer) throws Throwable {
        clearRendererEnvironment();
        Map<String, String> env = RuntimeRendererRules.routingEnvironment(renderer);
        env.put("POJAV_NATIVEDIR", NATIVE_LIB_DIR);
        env.put("MG_DIR_PATH", Tools.DIR_DATA + "/MobileGlues");
        env.put("FORCE_VSYNC", String.valueOf(LauncherPreferences.PREF_FORCE_VSYNC));
        if (PREF_VSYNC_IN_ZINK && renderer.contains("zink")) env.put("POJAV_VSYNC_IN_ZINK", "1");
        if (RuntimeRendererRules.isMesa(renderer)) {
            env.put("MESA_GLSL_CACHE_DIR", Tools.DIR_CACHE.getAbsolutePath());
            env.put("MESA_SHADER_CACHE_DIR", Tools.DIR_CACHE.getAbsolutePath());
        }
        if (renderer.contains("zink") && GLInfoUtils.getGlInfo().isAdreno()
                && !PREF_ZINK_PREFER_SYSTEM_DRIVER) env.put("POJAV_LOAD_TURNIP", "1");
        if ("opengles3_desktopgl_zink_kopper".equals(renderer) && Tools.shouldUseUBWC())
            env.put("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1");
        net.kdt.pojavlaunch.performance.PerformancePolicy policy =
                net.kdt.pojavlaunch.performance.PerformancePolicy.current();
        if (policy != null && policy.game != null) {
            env.putAll(net.kdt.pojavlaunch.performance.RendererPolicy.envFor(policy.mode, policy.device,
                    policy.game.withRenderer(renderer), policy.experimentalJvmFlags, null));
        }
        readCustomEnv(env); // explicit developer overrides stay authoritative, including on fallback
        for (Map.Entry<String, String> entry : env.entrySet()) Os.setenv(entry.getKey(), entry.getValue(), true);
    }

    /**
     * Remove the argument from the list, if it exists
     * If the argument exists multiple times, they will all be removed.
     * @param argList The argument list to purge
     * @param argStart The argument to purge from the list.
     */
    private static void purgeArg(List<String> argList, String argStart) {
        if (argStart.startsWith("-D") || argStart.equals("-Xms") || argStart.equals("-Xmx")) {
            RuntimeJvmArguments.removeKey(argList, argStart);
            return;
        }
        Iterator<String> args = argList.iterator();
        while(args.hasNext()) {
            String arg = args.next();
            if(arg.equals(argStart)) args.remove();
        }
    }
    private static final int EGL_OPENGL_ES_BIT = 0x0001;
    private static final int EGL_OPENGL_ES2_BIT = 0x0004;
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
    @SuppressWarnings("SameParameterValue")
    private static boolean hasExtension(String extensions, String name) {
        int start = extensions.indexOf(name);
        while (start >= 0) {
            // check that we didn't find a prefix of a longer extension name
            int end = start + name.length();
            if (end == extensions.length() || extensions.charAt(end) == ' ') {
                return true;
            }
            start = extensions.indexOf(name, end);
        }
        return false;
    }

    public static int getDetectedVersion() {
        return GLInfoUtils.getGlInfo().glesMajorVersion;
    }
    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeHooks();
    public static native void setupExitMethod(Context context);
    // Obtain AWT screen pixels to render on Android SurfaceView
    public static native int[] renderAWTScreenFrame(/* Object canvas, int width, int height */);

    public static String getLWJGLVersionForMC(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) {
            return "3.3.3";
        }
        try {
            String[] parts = mcVersion.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

            if (major >= 26) {
                return "3.4.1";
            } else if (major >= 1 && minor >= 21) {
                return "3.3.3";
            } else if (major >= 1 && minor >= 17) {
                return "3.3.1";
            } else {
                return "3.2.2";
            }
        } catch (NumberFormatException e) {
            return "3.3.3";
        }
    }

    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }
}
