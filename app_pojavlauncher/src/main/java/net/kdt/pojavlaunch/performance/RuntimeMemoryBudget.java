package net.kdt.pojavlaunch.performance;

/** Advisory Android/native/GPU reserve, not a measurement of GPU use or a heap override. */
public final class RuntimeMemoryBudget {
    private RuntimeMemoryBudget() {}

    /** Unknown memory returns 0 (no recommendation). Values are MB, rounded down to 128 MB. */
    public static int recommendedHeapMb(int totalMb, int availableMb, int addressSpaceCeilingMb) {
        if (totalMb <= 0 || availableMb <= 0) return 0;
        // An explicit conservative budget, not a guarantee against LMK or driver allocations.
        long reserve = Math.max(768L, Math.min(2048L, totalMb / 4L));
        long budget = Math.min(totalMb - reserve, availableMb - reserve);
        if (addressSpaceCeilingMb > 0) budget = Math.min(budget, addressSpaceCeilingMb);
        return (int) Math.max(0L, (budget / 128L) * 128L);
    }

    public static int initialHeapMb(int selectedHeapMb) {
        if (selectedHeapMb < 256) throw new IllegalArgumentException("Heap must be at least 256 MB");
        return Math.max(256, (Math.min(1024, selectedHeapMb / 2) / 128) * 128);
    }
}
