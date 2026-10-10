package net.kdt.pojavlaunch.performance;
import java.io.*;
import java.util.*;
/** Reversible pacing-only edits. Never changes shaders, distances or image quality. */
public final class ExtremeLaunchOptions {
    /** Legacy vanilla maximum-slider sentinel. Not a guaranteed cross-version Unlimited API. */
    public static final int VANILLA_UNLIMITED_SENTINEL = 260;
    private ExtremeLaunchOptions() { }

    public static int manualLimit(int requested) { return Math.max(10, Math.min(250, requested)); }

    /** A known ordinary options.txt cap, or zero for absent/invalid/Unlimited/unknown semantics. */
    public static int knownFpsLimit(String maxFps) {
        try {
            int value = Integer.parseInt(maxFps);
            return value > 0 && value < VANILLA_UNLIMITED_SENTINEL ? value : 0;
        } catch (RuntimeException invalid) { return 0; }
    }

    /** Pure display hint selection; only rates from the current physical-size mode group are supplied. */
    public static float chooseRefreshRate(float[] rates, int knownLimit) {
        float highest = 0f, sufficient = Float.POSITIVE_INFINITY;
        if (rates == null) return 0f;
        for (float rate : rates) {
            if (Float.isNaN(rate) || Float.isInfinite(rate) || rate <= 0f) continue;
            highest = Math.max(highest, rate);
            if (knownLimit > 0 && rate + 0.01f >= knownLimit) sufficient = Math.min(sufficient, rate);
        }
        return sufficient == Float.POSITIVE_INFINITY ? highest : sufficient;
    }
    public static synchronized void apply(File directory, String mode, int manualFps) throws IOException {
        if (!"original".equals(mode) && !"unlimited".equals(mode) && !"manual".equals(mode)) return;
        File options = new File(directory, "options.txt");
        File backup = new File(directory, "cs-extreme-pacing.properties");
        if ("original".equals(mode) && !backup.exists()) return;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create profile directory");
        List<String> lines = read(options);
        Properties saved = new Properties();
        if (backup.exists()) try (InputStream in = new FileInputStream(backup)) { saved.load(in); }
        if ("original".equals(mode)) {
            if (!backup.exists()) return;
            replace(lines, "maxFps", saved.getProperty("maxFps"));
            replace(lines, "vsync", saved.getProperty("vsync"));
            write(options, lines);
            if (!backup.delete()) throw new IOException("Cannot remove restored pacing backup");
            return;
        }
        if (!"unlimited".equals(mode) && !"manual".equals(mode)) return;
        if (!backup.exists()) {
            for (String key : new String[]{"maxFps", "vsync"}) {
                String value = value(lines, key);
                if (value != null) saved.setProperty(key, value);
            }
            File temp = new File(directory, "cs-extreme-pacing.properties.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                saved.store(out, "Original Minecraft pacing fields; restore with Original mode");
                out.getFD().sync();
            }
            if (!temp.renameTo(backup)) throw new IOException("Cannot save original pacing fields");
        }
        // Vanilla maximum slider value is the UNLIMITED sentinel, not a native 260 FPS cap.
        // Mods/new versions may interpret it differently: validate their render loop on device.
        String fps = "unlimited".equals(mode) ? String.valueOf(VANILLA_UNLIMITED_SENTINEL)
                : String.valueOf(manualLimit(manualFps));
        if (fps.equals(value(lines, "maxFps")) && "false".equals(value(lines, "vsync"))) return;
        replace(lines, "maxFps", fps);
        replace(lines, "vsync", "false");
        write(options, lines);
    }
    private static List<String> read(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        if (file.exists()) try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line; while ((line = in.readLine()) != null) lines.add(line);
        }
        return lines;
    }
    private static String value(List<String> lines, String key) {
        // Minecraft's options reader uses the last occurrence if a file contains duplicate keys.
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.startsWith(key + ":")) return line.substring(key.length() + 1);
        }
        return null;
    }
    private static void replace(List<String> lines, String key, String value) {
        for (int i = lines.size() - 1; i >= 0; i--) if (lines.get(i).startsWith(key + ":")) lines.remove(i);
        if (value != null) lines.add(key + ":" + value);
    }
    private static void write(File file, List<String> lines) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".cs-tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            for (String line : lines) out.write((line + "\n").getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) throw new IOException("Atomic options.txt replacement failed");
    }
}
