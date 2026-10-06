package top.leonx.territory.integration;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepthMathTest {

    private static final double[] DEFAULT = {0.0, 0.5, 1.0};

    private static Set<Long> square(int half) {
        Set<Long> set = new HashSet<>();
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                set.add(key(x, z));
            }
        }
        return set;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int depth(Set<Long> owned, int x, int z, int limit) {
        return DepthMath.depth((cx, cz) -> owned.contains(key(cx, cz)), x, z, limit);
    }

    @Test
    void aLoneChunkIsDepthZero() {
        assertEquals(0, depth(square(0), 0, 0, 4));
    }

    @Test
    void threeByThreeHasOneDeepChunk() {
        Set<Long> land = square(1);
        assertEquals(1, depth(land, 0, 0, 4));
        assertEquals(0, depth(land, 1, 0, 4));
        assertEquals(0, depth(land, 1, 1, 4));
        assertEquals(0, depth(land, -1, 0, 4));
    }

    @Test
    void fiveByFiveGivesThreeLayers() {
        Set<Long> land = square(2);
        assertEquals(2, depth(land, 0, 0, 4));
        assertEquals(1, depth(land, 1, 0, 4));
        assertEquals(1, depth(land, 1, 1, 4));
        assertEquals(1, depth(land, 0, -1, 4));
        assertEquals(0, depth(land, 2, 0, 4));
        assertEquals(0, depth(land, -2, -2, 4));
    }

    @Test
    void depthStopsAtTheLimit() {
        Set<Long> land = square(5);
        assertEquals(5, depth(land, 0, 0, 16));
        assertEquals(2, depth(land, 0, 0, 2));
        assertEquals(1, depth(land, 0, 0, 1));
    }

    @Test
    void theShortestStraightLineDecides() {
        Set<Long> land = square(2);
        land.remove(key(2, 0));
        assertEquals(1, depth(land, 0, 0, 4));
        assertEquals(1, depth(land, 0, 1, 4));
        assertEquals(0, depth(land, 1, 0, 4));
    }

    @Test
    void aMissingDiagonalChunkDoesNotMatter() {
        Set<Long> land = square(2);
        land.remove(key(2, 2));
        land.remove(key(1, 1));
        assertEquals(2, depth(land, 0, 0, 4));
    }

    @Test
    void aHoleBesideTheChunkMakesItDepthZero() {
        Set<Long> land = square(2);
        land.remove(key(1, 0));
        assertEquals(0, depth(land, 0, 0, 4));
    }

    @Test
    void zeroLimitAlwaysGivesZero() {
        assertEquals(0, depth(square(3), 0, 0, 0));
    }

    @Test
    void chanceFollowsTheListAndReusesTheLastEntry() {
        assertEquals(0.0, DepthMath.chanceAt(DEFAULT, 0));
        assertEquals(0.5, DepthMath.chanceAt(DEFAULT, 1));
        assertEquals(1.0, DepthMath.chanceAt(DEFAULT, 2));
        assertEquals(1.0, DepthMath.chanceAt(DEFAULT, 9));
    }

    @Test
    void emptyListNeverProtects() {
        assertEquals(0.0, DepthMath.chanceAt(new double[0], 3));
        assertFalse(DepthMath.survives(new double[0], 3, 0.0));
    }

    @Test
    void chancesAreClampedToZeroAndOne() {
        assertEquals(1.0, DepthMath.chanceAt(new double[]{4.0}, 0));
        assertEquals(0.0, DepthMath.chanceAt(new double[]{-1.0}, 0));
    }

    @Test
    void survivalComparesTheRollToTheChance() {
        assertTrue(DepthMath.survives(DEFAULT, 1, 0.49));
        assertFalse(DepthMath.survives(DEFAULT, 1, 0.5));
        assertFalse(DepthMath.survives(DEFAULT, 0, 0.0));
        assertTrue(DepthMath.survives(DEFAULT, 2, 0.999999));
    }

    @Test
    void limitMatchesTheListLengthWithinBounds() {
        assertEquals(2, DepthMath.limitFor(DEFAULT));
        assertEquals(1, DepthMath.limitFor(new double[]{0.3}));
        assertEquals(1, DepthMath.limitFor(new double[0]));
        assertEquals(DepthMath.MAX_LIMIT, DepthMath.limitFor(new double[40]));
    }
}
