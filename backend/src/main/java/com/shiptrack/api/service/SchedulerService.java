package com.shiptrack.api.service;

import com.shiptrack.api.config.ApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal §8.1 scheduler: one daily batch at {@code daily_query_time} in the
 * configured time zone, serialised across instances by a MySQL named lock.
 * Disabled unless {@code app.scheduler-enabled=true}.
 */
@Service
@Profile("!migrate")
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);
    private static final String LOCK_NAME = "shiptrack_daily_scheduler";

    private final ApiProperties properties;
    private final AppConfigService config;
    private final RunService runService;
    private final SchedulerState state;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile LocalDate lastRunDate;

    public SchedulerService(ApiProperties properties, AppConfigService config,
                            RunService runService, SchedulerState state, JdbcTemplate jdbc) {
        this.properties = properties;
        this.config = config;
        this.runService = runService;
        this.state = state;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${app.scheduler-check-interval-ms:30000}",
            initialDelayString = "${app.scheduler-initial-delay-ms:30000}")
    public void tick() {
        AppConfigService.AppConfigValues values = config.values();
        ZoneId zone = ZoneId.of(values.timezone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        LocalTime daily = parseTime(values.dailyQueryTime());
        ZonedDateTime todayRun = today.atTime(daily).atZone(zone);
        LocalDate nextDate = now.isBefore(todayRun) ? today : today.plusDays(1);
        state.plan(nextDate.atTime(daily).atZone(zone).toOffsetDateTime());

        if (!properties.isSchedulerEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (now.isBefore(todayRun) || today.equals(lastRunDate)) {
                return;
            }
            if (!acquireLock()) {
                return;
            }
            try {
                RunService.RunOutcome outcome = runService.scheduledRun();
                lastRunDate = today;
                state.recordRun(OffsetDateTime.now(zone));
                log.info("daily run {} created={} rows={}",
                        outcome.runId(), outcome.created(), outcome.containerCount());
            } finally {
                releaseLock();
            }
        } catch (RuntimeException error) {
            log.error("daily scheduler run failed", error);
        } finally {
            running.set(false);
        }
    }

    private LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException error) {
            return LocalTime.of(8, 0);
        }
    }

    private boolean acquireLock() {
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, LOCK_NAME);
        return acquired != null && acquired == 1;
    }

    private void releaseLock() {
        jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK_NAME);
    }
}
