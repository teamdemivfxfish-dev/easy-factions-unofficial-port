package top.leonx.territory.integration;

public final class DepthMath {
    public static final int MAX_LIMIT = 16;
    public static final int DISPLAY_LIMIT = 8;

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private DepthMath() {
    }

    public interface Owned {
        boolean at(int x, int z);
    }

    public static int depth(Owned owned, int x, int z, int limit) {
        int best = Math.max(0, limit);
        for (int dir = 0; dir < 4 && best > 0; dir++) {
            int run = 0;
            while (run < best && owned.at(x + DX[dir] * (run + 1), z + DZ[dir] * (run + 1))) {
                run++;
            }
            best = run;
        }
        return best;
    }

    public static double chanceAt(double[] chances, int depth) {
        if (chances.length == 0) {
            return 0.0;
        }
        double chance = chances[Math.min(Math.max(depth, 0), chances.length - 1)];
        return Math.min(1.0, Math.max(0.0, chance));
    }

    public static boolean survives(double[] chances, int depth, double roll) {
        return roll < chanceAt(chances, depth);
    }

    public static int limitFor(double[] chances) {
        return Math.max(1, Math.min(chances.length - 1, MAX_LIMIT));
    }
}
