package com.shiptrack.api.service;

import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.SnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /health} (§5.20). Always HTTP 200: downstream degradation is
 * reported in the body so monitoring can tell "API alive, source broken".
 */
@Service
public class HealthService {

    private final JdbcTemplate jdbc;
    private final ContainerRepository containers;
    private final SnapshotRepository snapshots;
    private final SchedulerState schedulerState;
    private final AppConfigService config;

    public HealthService(JdbcTemplate jdbc, ContainerRepository containers,
                         SnapshotRepository snapshots, SchedulerState schedulerState,
                         AppConfigService config) {
        this.jdbc = jdbc;
        this.containers = containers;
        this.snapshots = snapshots;
        this.schedulerState = schedulerState;
        this.config = config;
    }

    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean dbOk;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbOk = true;
        } catch (RuntimeException error) {
            dbOk = false;
        }
        OffsetDateTime lastSuccess = containers.latestSuccessAt();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int consecutiveFailures = lastSuccess == null
                ? snapshots.countFailedSince(OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                : snapshots.countFailedSince(lastSuccess);
        String mscStatus;
        if (!dbOk) {
            mscStatus = "down";
        } else if (lastSuccess == null && consecutiveFailures > 0) {
            mscStatus = "down";
        } else if (lastSuccess != null
                && Duration.between(lastSuccess, now).toHours() >= 48) {
            mscStatus = "degraded";
        } else {
            mscStatus = "ok";
        }

        Map<String, Object> msc = new LinkedHashMap<>();
        msc.put("status", mscStatus);
        msc.put("last_success_at", lastSuccess);
        msc.put("consecutive_failures", consecutiveFailures);

        Map<String, Object> scheduler = new LinkedHashMap<>();
        scheduler.put("last_run_at", schedulerState.lastRunAt());
        scheduler.put("next_run_at", schedulerState.nextRunAt());

        body.put("status", dbOk ? "ok" : "down");
        body.put("db", dbOk ? "ok" : "down");
        body.put("msc_source", msc);
        body.put("scheduler", scheduler);
        body.put("feishu_webhook_configured", config.feishuWebhookConfigured());
        return body;
    }
}
