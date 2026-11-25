package com.shiptrack.api.service;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory view of the daily scheduler for {@code GET /health}.
 */
@Component
public class SchedulerState {

    private final AtomicReference<OffsetDateTime> lastRunAt = new AtomicReference<>();
    private final AtomicReference<OffsetDateTime> nextRunAt = new AtomicReference<>();

    public OffsetDateTime lastRunAt() {
        return lastRunAt.get();
    }

    public OffsetDateTime nextRunAt() {
        return nextRunAt.get();
    }

    public void recordRun(OffsetDateTime at) {
        lastRunAt.set(at);
    }

    public void plan(OffsetDateTime at) {
        nextRunAt.set(at);
    }
}
