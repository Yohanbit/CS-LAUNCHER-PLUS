package net.kdt.pojavlaunch.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Draft edits are explicit: never publish an old whole-draft snapshot over the game process. */
public class SettingsSaveManager {
    public static final String DRAFT_PREFS_NAME = "pojav_preferences_draft";
    private static boolean sInitialized;
    private static final Set<String> DIRTY_KEYS = new LinkedHashSet<>();

    public static SharedPreferences getDraftPrefs(Context context) {
        return context.getSharedPreferences(DRAFT_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized void initDraft(Context context) {
        if (sInitialized) return;
        SharedSettings.syncIntoPreferences(context);
        SharedPreferences.Editor editor = getDraftPrefs(context).edit().clear();
        for (Map.Entry<String, ?> entry : mainPrefs(context).getAll().entrySet()) {
            putInEditor(editor, entry.getKey(), entry.getValue());
        }
        if (editor.commit()) sInitialized = true;
    }

    public static synchronized void markDirty(String key) {
        if (key != null) DIRTY_KEYS.add(key);
    }

    public static synchronized boolean saveToMain(Context context, String key) {
        markDirty(key);
        return commitChanges(context);
    }

    /** Publish only user-edited keys, including removals, before the preference reload/sync. */
    public static synchronized boolean commitChanges(Context context) {
        if (context == null) return false;
        if (DIRTY_KEYS.isEmpty()) return true;
        Map<String, ?> draft = getDraftPrefs(context).getAll();
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String key : DIRTY_KEYS) changes.put(key, draft.get(key));
        // Preserve unrelated shared edits written while this draft was open.
        SharedSettings.syncIntoPreferences(context);
        if (!SharedSettings.mirrorAll(context, changes)) return false;
        SharedPreferences.Editor editor = mainPrefs(context).edit();
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            putInEditor(editor, entry.getKey(), entry.getValue());
        }
        if (!editor.commit()) return false;
        LauncherPreferences.loadPreferences(context);
        DIRTY_KEYS.clear();
        return true;
    }

    /** A reset must not leave old pending edits or shared settings ready to resurrect. */
    public static synchronized boolean resetLauncherSettings(Context context) {
        if (context == null) return false;
        if (!getDraftPrefs(context).edit().clear().commit()) return false;
        DIRTY_KEYS.clear(); // An explicit reset discards pending edits, even if a later IO step fails.
        if (!mainPrefs(context).edit().clear().commit()) return false;
        if (!SharedSettings.resetSharedKeys(context)) return false;
        sInitialized = false;
        LauncherPreferences.loadPreferences(context);
        return true;
    }

    public static synchronized boolean hasUnsavedChanges(Context context) {
        return !DIRTY_KEYS.isEmpty();
    }

    private static SharedPreferences mainPrefs(Context context) {
        return context.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
    }

    private static void putInEditor(SharedPreferences.Editor editor, String key, Object value) {
        if (value == null) editor.remove(key);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
    }
}
