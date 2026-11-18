package com.finance.analyzer.service;

/**
 * Turns "k of N chunks finished" into a percentage.
 *
 * <p>Each finished chunk adds {@code floor(100 / N)} percent. Because of flooring the sum can fall short of 100
 * (N = 3 gives 33, 66), so the last chunk always reports exactly 100. Until then the value is capped at 99 so the bar
 * never shows 100 % while work remains. When N &gt; 100 the floor would be 0, so the percentage is proportional instead.
 * Thread-safe.
 */
public final class ProgressTracker {

    private final int total;
    private final int increment;
    private int completed;
    private int failed;

    public ProgressTracker(int totalChunks) {
        if (totalChunks < 1) throw new IllegalArgumentException("totalChunks must be >= 1");
        this.total = totalChunks;
        this.increment = 100 / totalChunks;
    }

    /** Records one finished chunk and returns the new percentage. */
    public synchronized int chunkFinished(boolean success) {
        completed++;
        if (!success) failed++;
        return percent();
    }

    public synchronized int percent() {
        if (completed >= total) return 100;
        int p = total <= 100 ? completed * increment : (int) ((long) completed * 100 / total);
        return Math.min(99, p);
    }

    public synchronized int completed() { return completed; }
    public synchronized int failed() { return failed; }
    public int total() { return total; }
}
