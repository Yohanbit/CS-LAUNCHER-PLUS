package net.kdt.pojavlaunch.performance;

/** Pure, monotonic-clock hysteresis. Unknown or merely warm signals cannot release shedding. */
public final class RuntimeThermalState {
    public static final long RECOVERY_MS = 60_000L;
    private boolean shed;
    private long coolSince = -1L;

    public boolean update(boolean hot, boolean definitelyCool, long elapsedMs) {
        if (hot) {
            shed = true;
            coolSince = -1L;
        } else if (shed) {
            if (!definitelyCool) coolSince = -1L;
            else if (coolSince < 0L || elapsedMs < coolSince) coolSince = elapsedMs;
            else if (elapsedMs - coolSince >= RECOVERY_MS) {
                shed = false;
                coolSince = -1L;
            }
        }
        return shed;
    }

    public void reset() { shed = false; coolSince = -1L; }
}
