package android.os;
public final class Process {
    public static boolean bit64;
    public static int calls;
    public static boolean is64Bit() {
        calls++;
        if (Build.VERSION.SDK_INT < 23) throw new AssertionError("API23 invoked on API21-22");
        return bit64;
    }
}
