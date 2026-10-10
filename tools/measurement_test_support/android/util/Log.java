package android.util;
public class Log {
    public static final int DEBUG = 3;
    public static boolean trace;
    public static boolean isLoggable(String tag, int level) { return trace; }
    public static int d(String tag, String text) { return 0; }
    public static int i(String tag, String text) { return 0; }
    public static int w(String tag, String text, Throwable error) { return 0; }
}
