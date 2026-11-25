package com.shiptrack.collector.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseBudgetTest {

    @Test
    void reportsZeroWhenTheLeaseIsSpent() {
        long startedFiveSecondsAgo = System.nanoTime() - 5_000_000_000L;
        LeaseBudget lease = LeaseBudget.startingAt(startedFiveSecondsAgo, 1_000, 0);

        // Zero, not the old 1 ms floor: an expired claim token must not be sent.
        assertThat(lease.timeoutMs(5_000)).isZero();
        assertThat(lease.expired()).isTrue();
    }

    @Test
    void capsTheTimeoutToWhatIsLeft() {
        LeaseBudget lease = LeaseBudget.startingAt(System.nanoTime(), 1_000, 100);

        assertThat(lease.timeoutMs(5_000)).isBetween(800L, 900L);
        assertThat(lease.timeoutMs(50)).isEqualTo(50L);
    }
}
