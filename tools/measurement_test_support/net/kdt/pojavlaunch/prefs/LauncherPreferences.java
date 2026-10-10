package net.kdt.pojavlaunch.prefs;
public class LauncherPreferences {
    public static final Preferences DEFAULT_PREF = new Preferences();
    public static int PREF_SCALE_FACTOR = 100;
    public static class Preferences {
        public volatile boolean diagnostics;
        public boolean getBoolean(String key, boolean fallback) { return diagnostics; }
    }
}
