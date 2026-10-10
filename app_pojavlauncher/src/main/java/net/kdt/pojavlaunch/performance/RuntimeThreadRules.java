package net.kdt.pojavlaunch.performance;

/** Fail-closed Linux comm matching; never a substring, worker, driver, UI or main-thread match. */
public final class RuntimeThreadRules {
    private RuntimeThreadRules() {}

    public static boolean isGameRenderThread(String comm) {
        return "Render thread".equals(comm) || "Client thread".equals(comm)
                || "Client main thr".equals(comm); // Linux comm's 15-byte truncation
    }

    public static String startTime(String stat) {
        if (stat == null) return null;
        int end = stat.lastIndexOf(')');
        if (end < 0) return null;
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length <= 19) return null;
        String start = fields[19]; // field 22; field 3 follows the closing parenthesis
        for (int i = 0; i < start.length(); i++) if (!Character.isDigit(start.charAt(i))) return null;
        return start.isEmpty() ? null : start;
    }

    /** Do not replace another subsystem's later priority change or touch a recycled TID. */
    public static boolean mayRestore(String ownedIdentity, String actualIdentity,
                                     int ownedPriority, int actualPriority) {
        return ownedIdentity != null && ownedIdentity.equals(actualIdentity)
                && ownedPriority == actualPriority;
    }
}
