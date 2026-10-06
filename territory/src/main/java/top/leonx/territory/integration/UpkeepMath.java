package top.leonx.territory.integration;

public final class UpkeepMath {

    private UpkeepMath() {}

    public static long dueValue(int chunks, int flatCost, int[] tiers) {
        if (chunks <= 0) return 0L;
        if (tiers == null || tiers.length == 0) return (long) chunks * Math.max(0, flatCost);
        long sum = 0L;
        for (int i = 0; i < chunks; i++) {
            sum += tiers[Math.min(i, tiers.length - 1)];
        }
        return sum;
    }

    public static int affordableChunks(long held, int chunks, int flatCost, int[] tiers) {
        if (chunks <= 0) return 0;
        if (tiers == null || tiers.length == 0) {
            if (flatCost <= 0) return chunks;
            return (int) Math.min((long) chunks, Math.max(0L, held) / flatCost);
        }
        long left = Math.max(0L, held);
        int count = 0;
        while (count < chunks) {
            int cost = tiers[Math.min(count, tiers.length - 1)];
            if (cost > left) break;
            left -= cost;
            count++;
        }
        return count;
    }

    public static long minutesFor(long value, int chunks, int flatCost, int[] tiers, long intervalMinutes) {
        long due = dueValue(Math.max(1, chunks), flatCost, tiers);
        return due <= 0L ? 0L : value * intervalMinutes / due;
    }
}
