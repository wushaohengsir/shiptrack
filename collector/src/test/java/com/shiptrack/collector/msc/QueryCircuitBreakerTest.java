package com.shiptrack.collector.msc;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCircuitBreakerTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    @Test
    void opensAfterTheThresholdAndClosesAfterTheCooldown() {
        MutableClock clock = new MutableClock(NOW);
        QueryCircuitBreaker breaker = new QueryCircuitBreaker(2, Duration.ofSeconds(60), clock);

        breaker.recordFailure();
        assertThat(breaker.allow()).isTrue();
        breaker.recordFailure();
        assertThat(breaker.allow()).isFalse();

        clock.advance(Duration.ofSeconds(61));
        assertThat(breaker.allow()).isTrue();
    }

    @Test
    void successResetsTheFailureCount() {
        QueryCircuitBreaker breaker = new QueryCircuitBreaker(2, Duration.ofSeconds(60),
                new MutableClock(NOW));

        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        assertThat(breaker.allow()).isTrue();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
