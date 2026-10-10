package android.content;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
/** Each Context owns a separate process-like preferences cache; filesDir is shared. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final File directory;
    private final Map<String, MemoryPrefs> stores = new HashMap<>();
    public Context(File directory) { this.directory = directory; }
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { return directory; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return stores.computeIfAbsent(name, n -> new MemoryPrefs());
    }
    public static class MemoryPrefs implements SharedPreferences {
        public boolean failCommit;
        private final Map<String, Object> values = new LinkedHashMap<>();
        public Map<String, ?> getAll() { return new LinkedHashMap<>(values); }
        public boolean contains(String key) { return values.containsKey(key); }
        public boolean getBoolean(String k, boolean f) { return contains(k) ? (Boolean) values.get(k) : f; }
        public int getInt(String k, int f) { return contains(k) ? (Integer) values.get(k) : f; }
        public String getString(String k, String f) { return contains(k) ? (String) values.get(k) : f; }
        public Editor edit() {
            return new Editor() {
                final Map<String,Object> patch = new LinkedHashMap<>(); boolean clear;
                public Editor putBoolean(String k, boolean v) { patch.put(k,v); return this; }
                public Editor putInt(String k, int v) { patch.put(k,v); return this; }
                public Editor putLong(String k, long v) { patch.put(k,v); return this; }
                public Editor putFloat(String k, float v) { patch.put(k,v); return this; }
                public Editor putString(String k, String v) { patch.put(k,v); return this; }
                public Editor remove(String k) { patch.put(k,null); return this; }
                public Editor clear() { clear=true; return this; }
                public boolean commit() {
                    if (failCommit) return false;
                    if (clear) values.clear();
                    patch.forEach((k,v) -> { if (v==null) values.remove(k); else values.put(k,v); });
                    return true;
                }
                public void apply() { commit(); }
            };
        }
    }
}
