package com.shiptrack.collector.msc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops hammering the carrier after repeated source-level failures. When open,
 * queries fail immediately as {@code SOURCE_UNAVAILABLE} and no Chromium page
 * is opened; the breaker closes again after the cooldown.
 */
@Component
public class QueryCircuitBreaker {

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;

    private int consecutiveFailures;
    private Instant openUntil = Instant.MIN;

    @Autowired
    public QueryCircuitBreaker(
            @Value("${collector.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${collector.circuit-breaker.cooldown-seconds:60}") int cooldownSeconds) {
        this(failureThreshold, Duration.ofSeconds(cooldownSeconds), Clock.systemUTC());
    }

    QueryCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public synchronized boolean allow() {
        return !clock.instant().isBefore(openUntil);
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = Instant.MIN;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openUntil = clock.instant().plus(cooldown);
            consecutiveFailures = 0;
        }
    }

    synchronized boolean isOpen() {
        return !allow();
    }
}
