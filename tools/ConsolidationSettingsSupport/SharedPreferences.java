package android.content;
import java.util.Map;
public interface SharedPreferences {
    Map<String, ?> getAll(); boolean contains(String key);
    boolean getBoolean(String key, boolean fallback); int getInt(String key, int fallback);
    String getString(String key, String fallback);
    Editor edit();
    interface Editor {
        Editor putBoolean(String k, boolean v); Editor putInt(String k, int v);
        Editor putLong(String k, long v); Editor putFloat(String k, float v);
        Editor putString(String k, String v); Editor remove(String k); Editor clear();
        boolean commit(); void apply();
    }
}
