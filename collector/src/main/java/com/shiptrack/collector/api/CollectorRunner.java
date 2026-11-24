package com.shiptrack.collector.api;

import com.shiptrack.collector.mapping.StatusMappingSet;
import com.shiptrack.collector.mapping.StatusMappingsLoader;
import com.shiptrack.collector.msc.MscTrackingClient;
import com.shiptrack.collector.msc.TrackingOutcome;
import com.shiptrack.contract.internal.ClaimedTaskDto;
import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingRunDto;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers claimable runs, claims one task at a time, queries the carrier and
 * reports the result. The loop is intentionally sequential: concurrent agents
 * are an API-side concern (claims are atomic), and a single browser keeps the
 * carrier's rate low.
 */
@Component
public class CollectorRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);

    private final CollectorProperties properties;
    private final InternalApiClient api;
    private final MscTrackingClient trackingClient;
    private final StatusMappingsLoader mappingsLoader;
    private final TrackingResultFactory resultFactory;

    private final AtomicBoolean cycling = new AtomicBoolean(false);
    private final AtomicBoolean warnedMissingToken = new AtomicBoolean(false);

    public CollectorRunner(CollectorProperties properties,
                           InternalApiClient api,
                           MscTrackingClient trackingClient,
                           StatusMappingsLoader mappingsLoader,
                           TrackingResultFactory resultFactory) {
        this.properties = properties;
        this.api = api;
        this.trackingClient = trackingClient;
        this.mappingsLoader = mappingsLoader;
        this.resultFactory = resultFactory;
    }

    @Scheduled(
            fixedDelayString = "${collector.poll-interval-ms:30000}",
            initialDelayString = "${collector.poll-initial-delay-ms:15000}")
    public void poll() {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            if (warnedMissingToken.compareAndSet(false, true)) {
                log.warn("INTERNAL_TOKEN is not set; the collector will not claim tasks");
            }
            return;
        }
        if (!cycling.compareAndSet(false, true)) {
            log.debug("Previous collector cycle still running; skipping this tick");
            return;
        }
        try {
            runCycle();
        } catch (RuntimeException e) {
            log.error("Collector cycle failed: {}", e.getMessage(), e);
        } finally {
            cycling.set(false);
        }
    }

    private void runCycle() {
        int processed = 0;
        int maxTasks = Math.max(1, properties.getMaxTasksPerPoll());
        int claimSize = Math.min(20, Math.max(1, properties.getClaimLimit()));

        List<TrackingRunDto> runs = api.discoverClaimableRuns(Math.max(1, properties.getMaxRunsPerPoll()));
        for (TrackingRunDto run : runs) {
            if (processed >= maxTasks) {
                break;
            }
            while (processed < maxTasks) {
                TrackingTaskClaimResponse claim = api.claimTasks(run.runId(), claimSize);
                if (claim.items() == null || claim.items().isEmpty()) {
                    break;
                }
                for (ClaimedTaskDto task : claim.items()) {
                    if (processed >= maxTasks) {
                        break;
                    }
                    processTask(claim.runId(), task);
                    processed++;
                }
            }
        }
        if (processed > 0) {
            log.info("Collector cycle finished, processed {} task(s)", processed);
        }
    }

    private void processTask(String runId, ClaimedTaskDto task) {
        // Fix the mapping version before the first query, per §5.23 / §6.2.
        StatusMappingSet mappings = mappingsLoader.snapshot();

        int retryCount = 0;
        TrackingOutcome outcome = trackingClient.query(task.containerNo());
        while (outcome instanceof TrackingOutcome.Failure failure
                && failure.retryable()
                && retryCount < task.maxRetry()) {
            sleep(properties.getRetryBackoffMs() * (retryCount + 1L));
            retryCount++;
            log.info("Retrying container_id={} ({}/{}) after {}",
                    task.containerId(), retryCount, task.maxRetry(), failure.errorCode());
            outcome = trackingClient.query(task.containerNo());
        }

        OffsetDateTime queriedAt = OffsetDateTime.now(ZoneId.of(properties.getQueryZone()));
        TrackingResultReport report = resultFactory.build(runId, task, mappings, outcome, retryCount, queriedAt);

        try {
            api.report(report);
            log.info("Reported run_id={} container_id={} success={} retry={}",
                    runId, task.containerId(), report.success(), retryCount);
        } catch (RestClientResponseException e) {
            // 409 STALE_CLAIM / CONFLICT means another writer owns the row now;
            // both are terminal for this attempt and must not be retried.
            log.warn("Report rejected run_id={} container_id={} http={} body={}",
                    runId, task.containerId(), e.getStatusCode().value(), firstLine(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            log.warn("Report failed run_id={} container_id={}: {}",
                    runId, task.containerId(), firstLine(e.getMessage()));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("collector interrupted while waiting to retry", e);
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
