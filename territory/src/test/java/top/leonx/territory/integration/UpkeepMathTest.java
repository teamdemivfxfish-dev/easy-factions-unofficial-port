package top.leonx.territory.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpkeepMathTest {

    private static final int[] NONE = {};
    private static final int[] TIERS = {2, 2, 4, 4, 6, 8};

    @Test
    void flatCostIsChunksTimesCost() {
        assertEquals(0L, UpkeepMath.dueValue(0, 4, NONE));
        assertEquals(4L, UpkeepMath.dueValue(1, 4, NONE));
        assertEquals(200L, UpkeepMath.dueValue(50, 4, NONE));
    }

    @Test
    void tiersAddUpChunkByChunk() {
        assertEquals(2L, UpkeepMath.dueValue(1, 4, TIERS));
        assertEquals(4L, UpkeepMath.dueValue(2, 4, TIERS));
        assertEquals(8L, UpkeepMath.dueValue(3, 4, TIERS));
        assertEquals(12L, UpkeepMath.dueValue(4, 4, TIERS));
        assertEquals(26L, UpkeepMath.dueValue(6, 4, TIERS));
    }

    @Test
    void chunksPastTheListCostTheLastTier() {
        assertEquals(26L + 8L, UpkeepMath.dueValue(7, 4, TIERS));
        assertEquals(26L + 8L * 10L, UpkeepMath.dueValue(16, 4, TIERS));
    }

    @Test
    void tiersIgnoreTheFlatCost() {
        assertEquals(UpkeepMath.dueValue(5, 4, TIERS), UpkeepMath.dueValue(5, 99, TIERS));
    }

    @Test
    void flatAffordableIsHeldOverCostCappedAtChunks() {
        assertEquals(0, UpkeepMath.affordableChunks(3L, 10, 4, NONE));
        assertEquals(2, UpkeepMath.affordableChunks(8L, 10, 4, NONE));
        assertEquals(2, UpkeepMath.affordableChunks(11L, 10, 4, NONE));
        assertEquals(10, UpkeepMath.affordableChunks(1000L, 10, 4, NONE));
    }

    @Test
    void tieredAffordableStopsWhereTheNextChunkIsTooDear() {
        assertEquals(0, UpkeepMath.affordableChunks(1L, 6, 4, TIERS));
        assertEquals(1, UpkeepMath.affordableChunks(3L, 6, 4, TIERS));
        assertEquals(2, UpkeepMath.affordableChunks(4L, 6, 4, TIERS));
        assertEquals(3, UpkeepMath.affordableChunks(11L, 6, 4, TIERS));
        assertEquals(5, UpkeepMath.affordableChunks(25L, 6, 4, TIERS));
        assertEquals(6, UpkeepMath.affordableChunks(26L, 6, 4, TIERS));
    }

    @Test
    void affordableNeverExceedsTheChunksOwned() {
        assertEquals(4, UpkeepMath.affordableChunks(100000L, 4, 4, TIERS));
    }

    @Test
    void affordableAndDueAgree() {
        for (int held = 0; held < 80; held++) {
            int n = UpkeepMath.affordableChunks(held, 12, 4, TIERS);
            long paid = UpkeepMath.dueValue(n, 4, TIERS);
            assertEquals(true, paid <= held);
            if (n < 12) {
                assertEquals(true, UpkeepMath.dueValue(n + 1, 4, TIERS) > held);
            }
        }
    }

    @Test
    void minutesCoveredUseTheTieredTotal() {
        assertEquals(1440L, UpkeepMath.minutesFor(200L, 50, 4, NONE, 1440L));
        assertEquals(1440L, UpkeepMath.minutesFor(26L, 6, 4, TIERS, 1440L));
        assertEquals(720L, UpkeepMath.minutesFor(13L, 6, 4, TIERS, 1440L));
    }

    @Test
    void zeroChunksAreShownAsOneChunk() {
        assertEquals(1440L, UpkeepMath.minutesFor(4L, 0, 4, NONE, 1440L));
        assertEquals(1440L, UpkeepMath.minutesFor(2L, 0, 4, TIERS, 1440L));
    }
}
