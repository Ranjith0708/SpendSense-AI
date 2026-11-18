package com.finance.analyzer.service;

/** Spaces out the start of LLM calls by at least {@code minIntervalMs}. Thread-safe; a no-op when the interval is 0. */
final class RateLimiter {

    private final long minIntervalNanos;
    private long nextSlot = System.nanoTime();

    RateLimiter(long minIntervalMs) {
        this.minIntervalNanos = Math.max(0, minIntervalMs) * 1_000_000L;
    }

    void acquire() throws InterruptedException {
        if (minIntervalNanos == 0) return;
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + minIntervalNanos;
            wait = slot - now;
        }
        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
    }
}
