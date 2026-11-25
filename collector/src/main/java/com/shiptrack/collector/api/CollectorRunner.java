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
    private final CollectorApi api;
    private final MscTrackingClient trackingClient;
    private final StatusMappingsLoader mappingsLoader;
    private final TrackingResultFactory resultFactory;

    private final AtomicBoolean cycling = new AtomicBoolean(false);
    private final AtomicBoolean warnedMissingToken = new AtomicBoolean(false);

    public CollectorRunner(CollectorProperties properties,
                           CollectorApi api,
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
        int maxRuns = Math.min(50, Math.max(1, properties.getMaxRunsPerPoll()));

        List<TrackingRunDto> runs = api.discoverClaimableRuns(maxRuns);
        if (runs.isEmpty()) {
            sleep(properties.getEmptyRunBackoffMs());
            return;
        }
        for (TrackingRunDto run : runs) {
            while (processed < maxTasks) {
                // §3.2: always claim exactly one task, process and report it,
                // then claim the next. Claiming a batch and walking away would
                // hold leases for tasks this cycle never runs.
                // The lease clock starts before the claim request: the server
                // writes `claimed_at` later, so this is the conservative bound.
                long claimStartedAt = System.nanoTime();
                TrackingTaskClaimResponse claim = api.claimTasks(run.runId(), 1);
                if (claim == null || claim.items() == null || claim.items().isEmpty()) {
                    sleep(properties.getEmptyClaimBackoffMs());
                    break;
                }
                LeaseBudget lease = LeaseBudget.startingAt(claimStartedAt,
                        properties.getLeaseDurationMs(), properties.getLeaseSafetyMarginMs());
                ClaimedTaskDto task = claim.items().getFirst();
                processTask(claim.runId() == null ? run.runId() : claim.runId(), task, lease);
                processed++;
            }
            if (processed >= maxTasks) {
                break;
            }
        }
        if (processed > 0) {
            log.info("Collector cycle finished, processed {} task(s)", processed);
        }
    }

    private void processTask(String runId, ClaimedTaskDto task, LeaseBudget lease) {
        if (lease.expired()) {
            log.warn("Lease already spent before container_id={}; leaving it for reclaim",
                    task.containerId());
            return;
        }
        // §3.2: check the mount and fix the mapping snapshot before the query.
        // The same snapshot and version are used for the whole task.
        StatusMappingSet mappings = mappingsLoader.refreshAndGet();

        int retryCount = 0;
        TrackingOutcome outcome = trackingClient.query(task.containerNo(), lease.remainingMs());
        while (outcome instanceof TrackingOutcome.Failure failure
                && failure.retryable()
                && retryCount < task.maxRetry()) {
            long backoff = properties.getRetryBackoffMs() * (retryCount + 1L);
            if (!lease.canWait(backoff)) {
                log.warn("Lease too short for retry backoff (container_id={}, remaining={}ms); "
                        + "reporting the last outcome", task.containerId(), lease.remainingMs());
                break;
            }
            sleep(backoff);
            if (lease.expired()) {
                log.warn("Lease expired during retry backoff for container_id={}; "
                        + "leaving it for reclaim", task.containerId());
                return;
            }
            retryCount++;
            log.info("Retrying container_id={} ({}/{}) after {}",
                    task.containerId(), retryCount, task.maxRetry(), failure.errorCode());
            outcome = trackingClient.query(task.containerNo(), lease.remainingMs());
        }

        if (lease.expired()) {
            // The server has already reclaimed the row; the token is stale and
            // must not be used. The normal claim loop will pick the task up.
            log.warn("Lease expired before reporting container_id={}; leaving it for reclaim",
                    task.containerId());
            return;
        }

        OffsetDateTime queriedAt = OffsetDateTime.now(ZoneId.of(properties.getQueryZone()));
        TrackingResultReport report = resultFactory.build(runId, task, mappings, outcome, retryCount, queriedAt);

        reportWithRetry(runId, task, report, lease);
    }

    /**
     * §11: a transient API failure must not throw away a result the carrier was
     * already queried for. Retry the same body, keeping {@code claim_token},
     * {@code queried_at} and {@code retry_count}, within the lease.
     * 409/4xx are terminal: another writer owns the row or the request is invalid.
     */
    private void reportWithRetry(String runId, ClaimedTaskDto task, TrackingResultReport report,
                                 LeaseBudget lease) {
        int maxAttempts = Math.max(1, properties.getReportMaxAttempts());
        long backoff = Math.max(0, properties.getReportBackoffMs());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Check the lease immediately before every send, including retries:
            // once it is gone the claim token is stale and must not leave the
            // process.
            long budget = lease.timeoutMs(properties.getReadTimeoutMs());
            if (budget <= 0 || lease.expired()) {
                log.warn("Lease expired before report attempt {} for container_id={}; "
                        + "task will be reclaimed", attempt, task.containerId());
                return;
            }
            try {
                api.report(report, budget);
                log.info("Reported run_id={} container_id={} success={} retry={}",
                        runId, task.containerId(), report.success(), report.retryCount());
                return;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (!isTransient(status) || attempt == maxAttempts) {
                    // 409 STALE_CLAIM / CONFLICT and other 4xx are terminal.
                    log.warn("Report rejected run_id={} container_id={} http={} attempt={} body={}",
                            runId, task.containerId(), status, attempt,
                            firstLine(e.getResponseBodyAsString()));
                    return;
                }
                log.warn("Transient report failure run_id={} container_id={} http={}, retrying {}/{}",
                        runId, task.containerId(), status, attempt, maxAttempts);
            } catch (RestClientException e) {
                if (attempt == maxAttempts) {
                    log.error("Report failed run_id={} container_id={} after {} attempts: {}",
                            runId, task.containerId(), attempt, firstLine(e.getMessage()));
                    return;
                }
                log.warn("Transient report failure run_id={} container_id={} ({}), retrying {}/{}",
                        runId, task.containerId(), firstLine(e.getMessage()), attempt, maxAttempts);
            }
            long wait = backoff * attempt;
            if (!lease.canWait(wait)) {
                log.warn("Lease too short for report retry backoff (container_id={}, remaining={}ms); "
                        + "task will be reclaimed", task.containerId(), lease.remainingMs());
                return;
            }
            sleep(wait);
        }
    }

    private static boolean isTransient(int status) {
        return status >= 500 || status == 408 || status == 429;
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
