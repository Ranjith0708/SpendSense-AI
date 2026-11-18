package com.finance.analyzer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressTrackerTest {

    private static List<Integer> run(int chunks) {
        ProgressTracker t = new ProgressTracker(chunks);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < chunks; i++) out.add(t.chunkFinished(true));
        return out;
    }

    @Test void tenChunksStepByTen() {
        assertEquals(List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100), run(10));
    }

    @Test void flooredIncrementStillEndsAtExactlyHundred() {
        assertEquals(List.of(33, 66, 100), run(3));
        assertEquals(List.of(14, 28, 42, 56, 70, 84, 100), run(7));
    }

    @Test void singleChunkJumpsToHundred() {
        assertEquals(List.of(100), run(1));
    }

    @Test void neverShowsHundredBeforeTheLastChunkAndNeverGoesBackwards() {
        for (int n : new int[]{2, 5, 99, 100, 101, 150, 1000}) {
            List<Integer> ps = run(n);
            assertEquals(100, ps.get(n - 1), "n=" + n);
            for (int i = 1; i < n; i++) assertTrue(ps.get(i) >= ps.get(i - 1), "n=" + n);
            for (int i = 0; i < n - 1; i++) assertTrue(ps.get(i) < 100, "n=" + n);
        }
    }

    @Test void countsFailures() {
        ProgressTracker t = new ProgressTracker(2);
        t.chunkFinished(false);
        t.chunkFinished(true);
        assertEquals(1, t.failed());
        assertEquals(2, t.completed());
        assertEquals(100, t.percent());
    }

    @Test void rejectsZeroChunks() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressTracker(0));
    }
}
