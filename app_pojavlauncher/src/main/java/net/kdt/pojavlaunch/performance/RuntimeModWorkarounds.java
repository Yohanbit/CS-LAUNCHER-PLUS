package net.kdt.pojavlaunch.performance;

import java.io.*;
import java.util.*;

/** Historical Sodium-family workarounds, explicit opt-in only. Existing user keys always win.
 * Old unowned edits are deliberately not migrated/deleted: their authorship cannot be proven. */
public final class RuntimeModWorkarounds {
    private static final String[] KEYS = {
            "mixin.features.buffer_builder.intrinsics", "mixin.features.chunk_rendering"
    };
    private RuntimeModWorkarounds() {}

    public static void addJvmDefault(List<String> launchArgs, List<String> userArgs, boolean enabled) {
        String flag = "-Dsodium.checks.issue2561=false";
        if (enabled && !RuntimeJvmArguments.hasKey(userArgs, flag))
            RuntimeJvmArguments.addDefault(launchArgs, flag);
    }

    /** Never creates missing mod configs. Backup is exact bytes; ownership is only absent keys
     * added by this implementation. Restore removes only a single, unchanged owned line and
     * preserves unrelated edits, duplicate keys and changed user values. */
    public static synchronized void applyMixinConfig(File config, boolean enabled) throws IOException {
        File state = new File(config.getPath() + ".cs-workarounds.owned");
        File backup = new File(config.getPath() + ".cs-workarounds.backup");
        if (!config.isFile()) return;
        byte[] original = read(config);
        String text = new String(original, "UTF-8");
        Properties owned = new Properties();
        if (state.isFile()) try (InputStream in = new FileInputStream(state)) { owned.load(in); }
        if (!enabled) {
            if (!state.isFile()) return; // never undo inherited/unowned compatibility settings
            String updated = text;
            for (String key : KEYS) if ("false".equals(owned.getProperty(key))) {
                String[] lines = updated.split("(?<=\\n)", -1);
                int occurrences = 0, exact = -1;
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].replaceAll("[\\r\\n]+$", "");
                    Properties single = new Properties();
                    try { single.load(new StringReader(line)); }
                    catch (IllegalArgumentException malformed) { occurrences = 2; break; }
                    if (single.containsKey(key)) occurrences++;
                    if (line.equals(key + "=false")) exact = i;
                }
                if (occurrences == 1 && exact >= 0) {
                    StringBuilder restored = new StringBuilder();
                    for (int i = 0; i < lines.length; i++) if (i != exact) restored.append(lines[i]);
                    updated = restored.toString();
                }
            }
            // If nothing else changed, recover exact original newline/encoding bytes.
            if (backup.isFile()) {
                byte[] saved = read(backup);
                String savedText = new String(saved, "UTF-8");
                if (updated.equals(savedText) || updated.equals(savedText + "\n")) {
                    write(config, saved);
                } else if (!updated.equals(text)) write(config, updated.getBytes("UTF-8"));
            } else if (!updated.equals(text)) write(config, updated.getBytes("UTF-8"));
            if (!state.delete()) throw new IOException("Cannot release workaround ownership: " + state);
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot remove restored backup: " + backup);
            return;
        }
        Properties current = new Properties();
        try { current.load(new StringReader(text)); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed mixin properties: " + config, malformed); }
        StringBuilder additions = new StringBuilder();
        for (String key : KEYS) if (!current.containsKey(key)) {
            additions.append(key).append("=false\n");
            owned.setProperty(key, "false");
        }
        if (additions.length() == 0) return;
        if (!state.isFile()) write(backup, original);
        ByteArrayOutputStream stateBytes = new ByteArrayOutputStream();
        owned.store(stateBytes, "Only keys inserted absent by explicit launcher mod workaround opt-in");
        write(state, stateBytes.toByteArray()); // ownership recorded before touching the config
        write(config, (text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + additions).getBytes("UTF-8"));
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void write(File file, byte[] content) throws IOException {
        File temp = new File(file.getPath() + ".cs-tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(content); out.getFD().sync(); }
        if (!temp.renameTo(file)) {
            temp.delete();
            throw new IOException("Cannot atomically write " + file);
        }
    }
}
