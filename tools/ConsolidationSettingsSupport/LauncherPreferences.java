package net.kdt.pojavlaunch.prefs;
import android.content.Context;
import android.content.SharedPreferences;
/** Dependency seam reproduces the real loader's sync-first contract, not its runtime parsing. */
public class LauncherPreferences {
    public static SharedPreferences DEFAULT_PREF;
    public static void loadPreferences(Context context) {
        DEFAULT_PREF = context.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
        SharedSettings.syncIntoPreferences(context);
    }
}
