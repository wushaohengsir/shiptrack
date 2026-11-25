package com.shiptrack.collector.api;

import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TransportStatus;
import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingResultResponse;
import com.shiptrack.contract.internal.TrackingRunDto;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiClientTest {

    private HttpServer server;
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> claimBody = new AtomicReference<>();
    private final AtomicReference<String> reportBody = new AtomicReference<>();
    private volatile boolean dripReport;
    private volatile long dripDelayMs;
    private final java.util.concurrent.atomic.AtomicBoolean dripAborted =
            new java.util.concurrent.atomic.AtomicBoolean();

    private InternalApiClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tracking-runs", exchange ->
                respond(exchange, 200, """
                        {"runs":[{"run_id":"run_1","trigger":"schedule","queued_count":2,
                        "oldest_queued_at":"2026-11-22T08:00:00+08:00"}]}
                        """));
        server.createContext("/internal/tracking-tasks", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            claimBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"run_id":"run_1","trigger":"schedule","items":[
                      {"container_id":10,"container_no":"MSDU7172233","carrier":"MSC",
                       "tracked_milestone":"POD_ARRIVAL","baseline_snapshot_id":5001,
                       "last_success_at":"2026-11-21T08:01:00+08:00",
                       "claim_token":"clm_1","max_retry":2}]}
                    """);
        });
        server.createContext("/internal/tracking-results", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            reportBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (dripReport) {
                drip(exchange, dripDelayMs);
                return;
            }
            respond(exchange, 200, """
                    {"snapshot_id":5003,"attempt_status":"success","events":[],
                     "order_notification":{"state":"not_applicable",
                       "notification_id":null,"status":null}}
                    """);
        });
        server.start();

        CollectorProperties properties = new CollectorProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInternalToken("secret-token");
        client = new InternalApiClient(properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void discoversClaimableRuns() {
        List<TrackingRunDto> runs = client.discoverClaimableRuns(20);

        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().runId()).isEqualTo("run_1");
        assertThat(runs.getFirst().queuedCount()).isEqualTo(2);
        // Jackson normalises a serialized offset to its instant; the moment must survive.
        assertThat(runs.getFirst().oldestQueuedAt().toInstant())
                .isEqualTo(java.time.Instant.parse("2026-11-22T00:00:00Z"));
    }

    @Test
    void claimsWithTheInternalTokenAndParsesTheTask() {
        TrackingTaskClaimResponse claim = client.claimTasks("run_1", 5);

        assertThat(claim.runId()).isEqualTo("run_1");
        assertThat(claim.items()).hasSize(1);
        assertThat(claim.items().getFirst().claimToken()).isEqualTo("clm_1");
        assertThat(claim.items().getFirst().maxRetry()).isEqualTo(2);
        assertThat(token.get()).isEqualTo("secret-token");
        assertThat(claimBody.get()).contains("run_1").contains("5");
    }

    @Test
    void sendsAResultReportAndParsesTheResponse() {
        TrackingResultReport report = new TrackingResultReport(
                "run_1", 10L, "MSDU7172233", "MSC", "clm_1", 0, 7,
                OffsetDateTime.parse("2026-11-22T08:00:35+08:00"), true,
                null, null, "Loaded on vessel", TransportStatus.LOADED,
                List.of(new Eta(TrackedMilestone.POD_ARRIVAL, "Dec 06, 2026",
                        LocalDate.of(2026, 12, 6), null, null, EtaPrecision.DATE, null)),
                List.of(), "Busan, KR", "CAPE SOUNIO", "UK647R",
                "Ningbo, CN", "Vancouver, CA", "Toronto, CA", "msc_web", null);

        TrackingResultResponse response = client.report(report);

        assertThat(response.snapshotId()).isEqualTo(5003);
        assertThat(response.attemptStatus()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(token.get()).isEqualTo("secret-token");
        assertThat(reportBody.get())
                .contains("\"run_id\":\"run_1\"")
                .contains("\"container_id\":10")
                .contains("\"mapping_version\":7")
                .contains("\"transport_status\":\"LOADED\"");
    }

    @Test
    void refusesToStartAReportWithoutAnyLeaseTime() {
        assertThatThrownBy(() -> client.report(sampleReport(), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no lease time");
    }

    @Test
    void boundsTheWholeReportEvenWhenTheResponseDrips() {
        // Four 200 ms chunks = ~800 ms end to end; each individual read is
        // shorter than the budget, so only a total deadline can stop it.
        dripReport = true;
        dripDelayMs = 200;

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.report(sampleReport(), 300))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .hasMessageContaining("did not finish");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(650L);
    }

    @Test
    void boundsHostResolutionInsideTheLeaseBudgetAndSendsNothing() {
        CollectorProperties properties = new CollectorProperties();
        properties.setBaseUrl("http://slow.invalid:" + server.getAddress().getPort());
        properties.setInternalToken("secret-token");
        InternalApiClient slowClient = new InternalApiClient(properties,
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                host -> {
                    // A resolver that outlives the lease: the caller must still
                    // return on time and discard the late address.
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return InetAddress.getByName("127.0.0.1");
                });

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> slowClient.report(sampleReport(), 200))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .hasMessageContaining("did not finish");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs).isLessThan(800L);
        assertThat(reportBody.get())
                .as("a lookup that finishes late must not be used to send")
                .isNull();
    }

    @Test
    void physicallyStopsTheDrippingResponseWhenTheBudgetExpires() throws Exception {
        dripReport = true;
        dripDelayMs = 200;

        assertThatThrownBy(() -> client.report(sampleReport(), 300))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .hasMessageContaining("did not finish");

        // The server keeps writing after the client's budget ended. It must see
        // the connection break instead of writing for the whole body.
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!dripAborted.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(dripAborted)
                .as("the client must close the socket, not leave the server writing")
                .isTrue();
    }

    private static TrackingResultReport sampleReport() {
        return new TrackingResultReport(
                "run_1", 10L, "MSDU7172233", "MSC", "clm_1", 0, 7,
                OffsetDateTime.parse("2026-11-22T08:00:35+08:00"), true,
                null, null, "Loaded on vessel", TransportStatus.LOADED,
                List.of(new Eta(TrackedMilestone.POD_ARRIVAL, "Dec 06, 2026",
                        LocalDate.of(2026, 12, 6), null, null, EtaPrecision.DATE, null)),
                List.of(), "Busan, KR", "CAPE SOUNIO", "UK647R",
                "Ningbo, CN", "Vancouver, CA", "Toronto, CA", "msc_web", null);
    }

    /** Sends the body in chunks to prove a per-read timeout cannot bound the total. */
    private void drip(HttpExchange exchange, long delayMs) throws IOException {
        String body = """
                {"snapshot_id":5003,"attempt_status":"success","events":[],
                 "order_notification":{"state":"not_applicable",
                   "notification_id":null,"status":null}}
                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            int chunk = Math.max(1, bytes.length / 4);
            for (int offset = 0; offset < bytes.length; offset += chunk) {
                int length = Math.min(chunk, bytes.length - offset);
                out.write(bytes, offset, length);
                out.flush();
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException e) {
            // The client gave up when its budget was spent; the broken socket
            // is exactly what this test observes.
            dripAborted.set(true);
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
