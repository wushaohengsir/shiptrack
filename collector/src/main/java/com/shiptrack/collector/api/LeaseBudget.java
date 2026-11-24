package com.shiptrack.collector.api;

/**
 * Remaining server lease of one claimed task.
 *
 * <p>The deadline is anchored to the moment the claim request started, which is
 * earlier than the server's {@code claimed_at}; the collector therefore always
 * runs on the conservative side of {@code TASK_CLAIM_TIMEOUT} (§5.22). Network
 * calls, carrier queries and retry backoffs are all capped by
 * {@link #timeoutMs(long)}, and once {@link #expired()} is true the claim token
 * must not be used again: the server has reclaimed the task.</p>
 */
final class LeaseBudget {

    /** The server lease, minus the safety margin, expressed on the nano clock. */
    private final long deadlineNanos;

    private LeaseBudget(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    static LeaseBudget startingAt(long claimStartedAtNanos, long leaseDurationMs, long safetyMarginMs) {
        long usableMs = Math.max(0, leaseDurationMs - Math.max(0, safetyMarginMs));
        return new LeaseBudget(claimStartedAtNanos + usableMs * 1_000_000L);
    }

    /** Remaining time in milliseconds; zero or negative means the lease is spent. */
    long remainingMs() {
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(0, remaining);
    }

    boolean expired() {
        return remainingMs() <= 0;
    }

    /** {@code true} when a wait of {@code millis} would still leave lease time. */
    boolean canWait(long millis) {
        return remainingMs() > Math.max(0, millis);
    }

    /** Caps a configured timeout to what is left of the lease. */
    long timeoutMs(long configuredMs) {
        return Math.max(1, Math.min(configuredMs, remainingMs()));
    }
}
