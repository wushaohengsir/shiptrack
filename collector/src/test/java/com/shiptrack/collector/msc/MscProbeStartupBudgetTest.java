package com.shiptrack.collector.msc;

import com.shiptrack.collector.browser.BrowserSession;
import com.shiptrack.collector.browser.BrowserSessionFactory;
import com.shiptrack.contract.internal.AgentErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser startup shares the query deadline. A driver that hangs must not hold
 * the caller past it, even though the open call itself cannot be cancelled.
 */
class MscProbeStartupBudgetTest {

    @Test
    void returnsWhenBrowserStartupOutlivesTheBudget() {
        SlowFactory factory = new SlowFactory(5_000);
        MscProperties properties = new MscProperties();
        properties.setMinQueryIntervalMs(0);
        properties.setQueryJitterMs(0);
        properties.setRateLimitBackoffMs(0);
        MscProbe probe = new MscProbe(factory, properties, new MscResultParser(),
                new MscErrorClassifier(properties),
                new QueryCircuitBreaker(100, Duration.ofSeconds(1), Clock.systemUTC()));

        long startedAt = System.nanoTime();
        TrackingOutcome outcome = probe.query("MSDU7172233", 300);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(outcome).isInstanceOf(TrackingOutcome.Failure.class);
        assertThat(((TrackingOutcome.Failure) outcome).errorCode()).isEqualTo(AgentErrorCode.TIMEOUT);
        assertThat(elapsedMs).isLessThan(1_500L);
        // The remaining budget was handed to the factory, not the full timeout.
        assertThat(factory.requestedTimeoutMs).isBetween(1L, 300L);
    }

    @Test
    void closesASessionThatArrivesAfterTheBudgetExpired() throws Exception {
        // A driver that ignores interruption returns a real session 400 ms
        // after the 150 ms budget. The worker must still close it.
        LateFactory factory = new LateFactory(400);
        MscProperties properties = new MscProperties();
        properties.setMinQueryIntervalMs(0);
        properties.setQueryJitterMs(0);
        properties.setRateLimitBackoffMs(0);
        MscProbe probe = new MscProbe(factory, properties, new MscResultParser(),
                new MscErrorClassifier(properties),
                new QueryCircuitBreaker(100, Duration.ofSeconds(1), Clock.systemUTC()));

        TrackingOutcome outcome = probe.query("MSDU7172233", 150);

        assertThat(((TrackingOutcome.Failure) outcome).errorCode()).isEqualTo(AgentErrorCode.TIMEOUT);
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!factory.sessionClosed() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(factory.sessionClosed())
                .as("the late session must be closed by the worker thread")
                .isTrue();
    }

    private static final class LateFactory implements BrowserSessionFactory {

        private final long delayMs;
        private volatile BrowserSession session;
        private volatile boolean closed;

        private LateFactory(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public BrowserSession open() {
            return open(30_000);
        }

        @Override
        public BrowserSession open(long timeoutMs) {
            long end = System.nanoTime() + delayMs * 1_000_000L;
            while (System.nanoTime() < end) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    break;
                }
                try {
                    Thread.sleep(left);
                } catch (InterruptedException ignored) {
                    // Deliberately ignore: the test proves late cleanup.
                }
            }
            BrowserSession late = new BrowserSession() {
                @Override
                public com.microsoft.playwright.Page page() {
                    return null;
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
            session = late;
            return late;
        }

        boolean sessionClosed() {
            return closed;
        }

        @Override
        public void close() {
        }
    }

    private static final class SlowFactory implements BrowserSessionFactory {

        private final long sleepMs;
        private volatile long requestedTimeoutMs = -1;

        private SlowFactory(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public BrowserSession open() {
            return open(30_000);
        }

        @Override
        public BrowserSession open(long timeoutMs) {
            requestedTimeoutMs = timeoutMs;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("startup interrupted", e);
            }
            throw new UnsupportedOperationException("a hung startup must never reach the page");
        }

        @Override
        public void close() {
        }
    }
}
