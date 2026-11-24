package com.shiptrack.collector.api;

import com.shiptrack.collector.mapping.StatusMappingsLoader;
import com.shiptrack.collector.msc.MscPageSnapshot;
import com.shiptrack.collector.msc.ParsedTracking;
import com.shiptrack.collector.msc.TrackingOutcome;
import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.RunTrigger;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.internal.ClaimedTaskDto;
import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingResultResponse;
import com.shiptrack.contract.internal.TrackingRunDto;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void claimsAndProcessesExactlyOneTaskAtATime() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"), task(11L, "MSKU1234567", "clm_2"));
        AtomicInteger queries = new AtomicInteger();

        CollectorRunner runner = runner(api, 1, containerNo -> {
            queries.incrementAndGet();
            return success();
        });
        runner.poll();

        assertThat(queries).hasValue(1);
        assertThat(api.claimLimits).containsExactly(1);
        assertThat(api.remaining()).isEqualTo(1);
    }

    @Test
    void retriesTransientReportFailuresWithTheSameClaimToken() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"));
        api.failReportsWith(httpError(503, "unavailable"), 1);

        runner(api, 1, containerNo -> success()).poll();

        assertThat(api.reports).hasSize(2);
        assertThat(api.reports).allSatisfy(report -> {
            assertThat(report.claimToken()).isEqualTo("clm_1");
            assertThat(report.retryCount()).isZero();
            assertThat(report.queriedAt()).isEqualTo(api.reports.getFirst().queriedAt());
        });
    }

    @Test
    void retriesWhenTheReportResponseIsLost() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"));
        api.failReportsWith(new RestClientException("connection reset"), 1);

        runner(api, 1, containerNo -> success()).poll();

        assertThat(api.reports).hasSize(2);
        assertThat(api.reports).allSatisfy(report ->
                assertThat(report.claimToken()).isEqualTo("clm_1"));
    }

    @Test
    void doesNotRetryATerminalConflict() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"));
        api.failReportsWith(httpError(409, "stale claim"), 5);

        runner(api, 1, containerNo -> success()).poll();

        assertThat(api.reports).hasSize(1);
    }

    @Test
    void stopsRetryingAReportWhenTheBackoffWouldOutliveTheLease() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"));
        api.failReportsWith(httpError(503, "unavailable"), 5);

        runner(api, 1, containerNo -> success(), properties -> {
            properties.setLeaseDurationMs(500);
            properties.setLeaseSafetyMarginMs(0);
            properties.setReportBackoffMs(10_000);
        }).poll();

        // One attempt with the original token, then the lease is too short to
        // wait: the task is left for the server's reclaim instead of using a
        // stale token.
        assertThat(api.reports).hasSize(1);
        assertThat(api.reportTimeouts).singleElement()
                .satisfies(timeout -> assertThat(timeout).isBetween(1L, 600L));
    }

    @Test
    void stopsQueryRetriesWhenTheBackoffWouldOutliveTheLease() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"));
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();

        runner(api, 1, containerNo -> {
            queries.incrementAndGet();
            return new TrackingOutcome.Failure(com.shiptrack.contract.internal.AgentErrorCode.TIMEOUT,
                    "slow carrier");
        }, properties -> {
            properties.setLeaseDurationMs(120);
            properties.setLeaseSafetyMarginMs(0);
            properties.setRetryBackoffMs(10_000);
        }).poll();

        assertThat(queries).hasValue(1);
        assertThat(api.reports).hasSize(1);
        assertThat(api.reports.getFirst().retryCount()).isZero();
    }

    @Test
    void doesNotReportWhenTheLeaseExpiresDuringTheQuery() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"));

        runner(api, 1, containerNo -> {
            sleep(250);
            return success();
        }, properties -> {
            properties.setLeaseDurationMs(100);
            properties.setLeaseSafetyMarginMs(0);
        }).poll();

        // The token is already stale on the server side; no query result is
        // written and the normal claim path will hand the task out again.
        assertThat(api.reports).isEmpty();
    }

    @Test
    void doesNotQueryAtAllWhenTheLeaseIsAlreadyGone() throws IOException {
        FakeApi api = new FakeApi(task(10L, "MSDU7172233", "clm_1"));
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();

        runner(api, 1, containerNo -> {
            queries.incrementAndGet();
            return success();
        }, properties -> {
            properties.setLeaseDurationMs(0);
            properties.setLeaseSafetyMarginMs(0);
        }).poll();

        assertThat(queries).hasValue(0);
        assertThat(api.reports).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private CollectorRunner runner(CollectorApi api, int maxTasks, com.shiptrack.collector.msc.MscTrackingClient client)
            throws IOException {
        return runner(api, maxTasks, client, properties -> {
        });
    }

    private CollectorRunner runner(CollectorApi api, int maxTasks,
                                   com.shiptrack.collector.msc.MscTrackingClient client,
                                   java.util.function.Consumer<CollectorProperties> customizer)
            throws IOException {
        Path mapping = tempDir.resolve("status-mappings.yaml");
        Files.writeString(mapping, """
                version: 1
                updated_at: "2026-11-24T20:00:00+08:00"
                mappings:
                  - carrier: MSC
                    match: "Gate in"
                    transport_status: GATE_IN
                    priority: 90
                """);
        StatusMappingsLoader loader = new StatusMappingsLoader(mapping.toString());
        loader.loadOnStartup();

        CollectorProperties properties = new CollectorProperties();
        properties.setInternalToken("test-token");
        properties.setMaxTasksPerPoll(maxTasks);
        properties.setEmptyRunBackoffMs(0);
        properties.setEmptyClaimBackoffMs(0);
        properties.setReportMaxAttempts(3);
        properties.setReportBackoffMs(0);
        customizer.accept(properties);

        return new CollectorRunner(properties, api, client, loader, new TrackingResultFactory());
    }

    private static ClaimedTaskDto task(long id, String containerNo, String token) {
        return new ClaimedTaskDto(id, containerNo, "MSC", TrackedMilestone.POD_ARRIVAL,
                5001L, null, token, 2);
    }

    private static TrackingOutcome.Success success() {
        ParsedTracking parsed = new ParsedTracking(
                "Gate in", null, null, null, null, null, null, List.of(), List.of());
        return new TrackingOutcome.Success(parsed, new MscPageSnapshot(
                "Container Tracking", "https://www.msc.com/track", "Status: Gate in", null, List.of(), false));
    }

    private static RestClientResponseException httpError(int status, String message) {
        return new RestClientResponseException(message, status, message,
                new HttpHeaders(), "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static final class FakeApi implements CollectorApi {

        private final Deque<ClaimedTaskDto> tasks;
        private final List<Integer> claimLimits = new ArrayList<>();
        private final List<TrackingResultReport> reports = new ArrayList<>();
        private final List<Long> reportTimeouts = new ArrayList<>();
        private RuntimeException reportFailure;
        private int failureBudget;

        private FakeApi(ClaimedTaskDto... tasks) {
            this.tasks = new ArrayDeque<>(List.of(tasks));
        }

        void failReportsWith(RuntimeException failure, int times) {
            this.reportFailure = failure;
            this.failureBudget = times;
        }

        int remaining() {
            return tasks.size();
        }

        @Override
        public List<TrackingRunDto> discoverClaimableRuns(int limit) {
            return List.of(new TrackingRunDto("run_1", RunTrigger.SCHEDULE, tasks.size(),
                    OffsetDateTime.parse("2026-11-24T20:00:00+08:00")));
        }

        @Override
        public TrackingTaskClaimResponse claimTasks(String runId, int limit) {
            claimLimits.add(limit);
            ClaimedTaskDto next = tasks.poll();
            return new TrackingTaskClaimResponse(runId, RunTrigger.SCHEDULE,
                    next == null ? List.of() : List.of(next));
        }

        @Override
        public TrackingResultResponse report(TrackingResultReport report) {
            reports.add(report);
            if (reportFailure != null && failureBudget > 0) {
                failureBudget--;
                throw reportFailure;
            }
            return new TrackingResultResponse(1L, AttemptStatus.SUCCESS, List.of(), null);
        }

        @Override
        public TrackingResultResponse report(TrackingResultReport report, long timeoutMs) {
            reportTimeouts.add(timeoutMs);
            return report(report);
        }
    }
}
