package net.kdt.pojavlaunch.performance;

/** Advisory Android/native/GPU reserve, not a measurement of GPU use or a heap override. */
public final class RuntimeMemoryBudget {
    private RuntimeMemoryBudget() {}

    public static final class HeapAdvice {
        public final boolean known;
        public final int recommendedMb;

        private HeapAdvice(boolean known, int recommendedMb) {
            this.known = known;
            this.recommendedMb = recommendedMb;
        }

        public boolean shouldWarn(int selectedMb) {
            return known && selectedMb > recommendedMb;
        }

        /** Zero means no launchable recommendation, NOT permission to invent a 256 MB budget. */
        public int suggestedMb() {
            return known && recommendedMb >= 256 ? recommendedMb : 0;
        }
    }

    /** A measured zero is exhausted memory; negative/missing readings are unknown. */
    public static HeapAdvice assess(int totalMb, int availableMb, int addressSpaceCeilingMb) {
        if (totalMb <= 0 || availableMb < 0) return new HeapAdvice(false, 0);
        long reserve = Math.max(768L, Math.min(2048L, totalMb / 4L));
        long budget = Math.min(totalMb - reserve, availableMb - reserve);
        if (addressSpaceCeilingMb >= 0) budget = Math.min(budget, addressSpaceCeilingMb);
        return new HeapAdvice(true, (int) Math.max(0L, (budget / 128L) * 128L));
    }

    /** Legacy numeric API: unknown OR insufficient memory returns 0; use assess() to distinguish. */
    public static int recommendedHeapMb(int totalMb, int availableMb, int addressSpaceCeilingMb) {
        return assess(totalMb, availableMb, addressSpaceCeilingMb).recommendedMb;
    }

    public static int initialHeapMb(int selectedHeapMb) {
        if (selectedHeapMb < 256) throw new IllegalArgumentException("Heap must be at least 256 MB");
        return Math.max(256, (Math.min(1024, selectedHeapMb / 2) / 128) * 128);
    }
}
