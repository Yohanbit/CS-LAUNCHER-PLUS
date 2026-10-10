package android.os;
public class Process {
    public static final int THREAD_PRIORITY_BACKGROUND = 10;
    public static int myPid() { return -1; }
    public static int getThreadPriority(int tid) { return 0; }
    public static void setThreadPriority(int tid, int priority) {
        throw new AssertionError("Observe-only test must not mutate a thread priority");
    }
}
