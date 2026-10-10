package net.kdt.pojavlaunch.multirt;

import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;
import static org.apache.commons.io.FileUtils.listFiles;

import android.system.Os;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.NewJREUtil.ExternalRuntime;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class MultiRTUtils {

    private static final HashMap<String,Runtime> sCache = new HashMap<>();

    private static final File RUNTIME_FOLDER = new File(Tools.MULTIRT_HOME);

    public static List<Runtime> getInstalledRuntimes() {
        if(!RUNTIME_FOLDER.exists() && !RUNTIME_FOLDER.mkdirs()) {
            throw new RuntimeException("Failed to create runtime directory");
        }

        ArrayList<Runtime> runtimes = new ArrayList<>();
        File[] files = RUNTIME_FOLDER.listFiles();
        if(files != null) for(File f : files) {
            runtimes.add(read(f.getName()));
        }
        else throw new RuntimeException("The runtime directory does not exist");

        return runtimes;
    }

    /**
     *
     * @return Java versions which are not installed but are present in {@link ExternalRuntime}
     */
    public static List<ExternalRuntime> getRuntimesToDownload() {
        List<ExternalRuntime> runtimesToDownload = new ArrayList<>();
        ExternalRuntime[] downloadableRuntimes = ExternalRuntime.values();
        for (ExternalRuntime downloadableruntime : downloadableRuntimes) {
            if(getExactJreName(downloadableruntime.majorVersion) == null){
                // x86 isn't supported anymore for JRE25
                if (!(getDeviceArchitecture() == Architecture.ARCH_X86 && downloadableruntime.majorVersion >= 21))
                    runtimesToDownload.add(downloadableruntime);
            }
        }
        return runtimesToDownload;
    }

    public static String getExactJreName(int majorVersion) {
        List<Runtime> runtimes = getCompatibleInstalledRuntimes();
        for(Runtime r : runtimes)
            if(r.javaVersion == majorVersion)return r.name;

        return null;
    }

    public static String getNearestJreName(int majorVersion) {
        List<Runtime> runtimes = getCompatibleInstalledRuntimes();
        MathUtils.RankedValue<Runtime> nearestRankedRuntime = MathUtils.findNearestPositive(majorVersion, runtimes, (runtime)->runtime.javaVersion);
        if(nearestRankedRuntime == null) return null;
        Runtime nearestRuntime = nearestRankedRuntime.value;
        if(nearestRuntime == null) return null;
        return nearestRuntime.name;
    }

    public static boolean isCompatible(Runtime runtime) {
        int process = Architecture.getProcessArchitecture();
        return runtime != null && runtime.javaVersion > 0 && process != Architecture.UNSUPPORTED_ARCH
                && Architecture.archAsInt(runtime.arch) == process;
    }

    public static List<Runtime> getCompatibleInstalledRuntimes() {
        List<Runtime> compatible = new ArrayList<>();
        for (Runtime runtime : getInstalledRuntimes()) if (isCompatible(runtime)) compatible.add(runtime);
        return compatible;
    }

    public static void installRuntimeNamed(String nativeLibDir, InputStream runtimeInputStream, String name) throws IOException {
        File dest = RuntimeArchiveExtractor.runtimeDirectory(RUNTIME_FOLDER, name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
        unpack200(nativeLibDir,RUNTIME_FOLDER + "/" + name);
        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
        forceReread(name);
    }

    public static void postPrepare(String name) throws IOException {
        File dest = RuntimeArchiveExtractor.runtimeDirectory(RUNTIME_FOLDER, name);
        if(!dest.exists()) return;
        Runtime runtime = read(name);
        String libFolder = "lib";
        if (RuntimeArchiveExtractor.checkedPath(dest, libFolder + "/" + runtime.arch).exists())
            libFolder = libFolder + "/" + runtime.arch;
        File ftIn = RuntimeArchiveExtractor.checkedPath(dest, libFolder + "/libfreetype.so.6");
        File ftOut = RuntimeArchiveExtractor.checkedPath(dest, libFolder + "/libfreetype.so");
        if (ftIn.exists() && (!ftOut.exists() || ftIn.length() != ftOut.length())) {
            if(!ftIn.renameTo(ftOut)) throw new IOException("Failed to rename freetype");
        }

        // Refresh libraries
        copyDummyNativeLib("libawt_xawt.so", dest, libFolder);
    }

    public static void installRuntimeNamedBinpack(InputStream universalFileInputStream, InputStream platformBinsInputStream, String name, String binpackVersion) throws IOException {
        File dest = RuntimeArchiveExtractor.runtimeDirectory(RUNTIME_FOLDER, name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        installRuntimeNamedNoRemove(universalFileInputStream,dest);
        installRuntimeNamedNoRemove(platformBinsInputStream,dest);

        unpack200(NATIVE_LIB_DIR,RUNTIME_FOLDER + "/" + name);

        File binpack_verfile = new File(dest, "pojav_version");
        FileOutputStream fos = new FileOutputStream(binpack_verfile);
        fos.write(binpackVersion.getBytes());
        fos.close();

        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);

        forceReread(name);
    }


    public static String readInternalRuntimeVersion(String name) {
        try {
            File versionFile = new File(RuntimeArchiveExtractor.runtimeDirectory(RUNTIME_FOLDER, name), "pojav_version");
            if (versionFile.exists()) {
                return Tools.read(versionFile.getAbsolutePath());
            }else{
                return null;
            }
        }catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void removeRuntimeNamed(String name) throws IOException {
        File dest = RuntimeArchiveExtractor.runtimeDirectory(RUNTIME_FOLDER, name);
        if(dest.exists()) {
            FileUtils.deleteDirectory(dest);
            sCache.remove(name);
        }
    }

    public static File getRuntimeHome(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("No Java runtime selected");
        File dest;
        try { dest = RuntimeArchiveExtractor.runtimeDirectory(RUNTIME_FOLDER, name); }
        catch (IOException invalid) { throw new IllegalArgumentException("Invalid runtime selection", invalid); }
        Log.i("MiltiRTUitls", "Dest exists? "+dest.exists());
        if((!dest.exists()) || MultiRTUtils.forceReread(name).versionString == null) throw new RuntimeException("Selected runtime is broken!");
        return dest;
    }

    public static Runtime forceReread(String name) {
        sCache.remove(name);
        return read(name);
    }

    public static Runtime read(String name) {
        if (name == null || name.trim().isEmpty()) return new Runtime(name);
        Runtime returnRuntime = sCache.get(name);
        if(returnRuntime != null) return returnRuntime;
        File release;
        try { release = new File(RuntimeArchiveExtractor.runtimeDirectory(RUNTIME_FOLDER, name), "release"); }
        catch (IOException invalid) { return new Runtime(name); }
        if(!release.exists()) {
            return new Runtime(name);
        }
        try {
            String content = Tools.read(release.getAbsolutePath());
            returnRuntime = Runtime.fromRelease(name, content);
        }catch(IOException e) {
            returnRuntime =  new Runtime(name);
        }
        sCache.put(name, returnRuntime);
        return returnRuntime;
    }

    /**
     * Unpacks all .pack files into .jar Serves only for java 8, as java 9 brought project jigsaw
     * @param nativeLibraryDir The native lib path, required to execute the unpack200 binary
     * @param runtimePath The path to the runtime to walk into
     */
    private static void unpack200(String nativeLibraryDir, String runtimePath) {

        File basePath = new File(runtimePath);
        Collection<File> files = listFiles(basePath, new String[]{"pack"}, true);

        File workdir = new File(nativeLibraryDir);

        ProcessBuilder processBuilder = new ProcessBuilder().directory(workdir);
        for(File jarFile : files){
            try{
                Process process = processBuilder.command("./libunpack200.so", "-r", jarFile.getAbsolutePath(), jarFile.getAbsolutePath().replace(".pack", "")).start();
                process.waitFor();
            }catch (InterruptedException | IOException e) {
                Log.e("MULTIRT", "Failed to unpack the runtime !");
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void copyDummyNativeLib(String name, File dest, String libFolder) throws IOException {
        File fileLib = RuntimeArchiveExtractor.checkedPath(dest, libFolder + "/" + name);
        FileInputStream is = new FileInputStream(new File(NATIVE_LIB_DIR, name));
        FileOutputStream os = new FileOutputStream(fileLib);
        IOUtils.copy(is, os);
        is.close();
        os.close();
    }

    private static void installRuntimeNamedNoRemove(InputStream runtimeInputStream, File dest) throws IOException {
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
    }

    private static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
        RuntimeArchiveExtractor.extract(tarFileInputStream, dest, new RuntimeArchiveExtractor.Links() {
            public void symlink(String target, String linkPath) throws IOException {
                try { Os.symlink(target, linkPath); }
                catch (Exception e) { throw new IOException("Cannot create runtime symlink " + linkPath, e); }
            }
            public void hardlink(String target, String linkPath) throws IOException {
                try { Os.link(target, linkPath); }
                catch (Exception e) { throw new IOException("Cannot create runtime hardlink " + linkPath, e); }
            }
            public String readlink(String path) throws IOException {
                try { return Os.readlink(path); }
                catch (android.system.ErrnoException e) {
                    if (e.errno == android.system.OsConstants.EINVAL || e.errno == android.system.OsConstants.ENOENT)
                        return null;
                    throw new IOException("Cannot inspect runtime path " + path, e);
                }
            }
        }, name -> ProgressLayout.setProgress(ProgressLayout.UNPACK_RUNTIME, 100, R.string.global_unpacking, name));
    }
}
