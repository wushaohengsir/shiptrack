package com.shiptrack.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end API chain against a real MySQL 8.4 container:
 * create order → refresh → internal claim → report → timeline → stats.
 */
@SpringBootTest
class ApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shiptrack")
            .withUsername("shiptrack")
            .withPassword("shiptrack-test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
            Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.access-token", () -> API_TOKEN);
        registry.add("app.internal-token", () -> INTERNAL_TOKEN);
        registry.add("app.scheduler-enabled", () -> "false");
        registry.add("app.config-path", () -> "/nonexistent/config.yaml");
        registry.add("app.status-mapping-path", () -> "/nonexistent/status-mappings.yaml");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    @Test
    void createListDetailAndValidation() throws Exception {
        String orderNo = "PO-IT-CREATE";
        Map<String, Object> created = createOrder(orderNo, "MSDU7172233", "DEPARTED");
        assertEquals(orderNo, created.get("order_no"));
        assertEquals(1, created.get("container_count"));
        Map<?, ?> summary = (Map<?, ?>) created.get("summary");
        assertEquals(16, ((Map<?, ?>) summary.get("transport_status_counts")).size());
        Map<?, ?> container = (Map<?, ?>) ((List<?>) created.get("containers")).get(0);
        assertEquals("MSDU7172233", container.get("container_no"));
        assertTrue((Boolean) container.get("checksum_valid"));

        MvcResult duplicate = mvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + API_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(orderNo, "MSDU7172233"))))
                .andReturn();
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("DUPLICATE_ORDER_NO", errorCode(duplicate));

        MvcResult checksum = mvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + API_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody("PO-IT-BAD", "MSDU7172234"))))
                .andReturn();
        assertEquals(400, checksum.getResponse().getStatus());
        assertEquals("CONTAINER_CHECKSUM_INVALID", errorCode(checksum));

        MvcResult list = mvc.perform(get("/api/v1/orders")
                        .param("keyword", "MSDU7172233")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(200, list.getResponse().getStatus());
        Map<String, Object> page = body(list);
        assertTrue(((Number) page.get("total")).intValue() >= 1);

        // Regression: an unfiltered list must still build valid SQL (risk sort).
        MvcResult unfiltered = mvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(200, unfiltered.getResponse().getStatus());
        assertTrue(((Number) body(unfiltered).get("total")).intValue() >= 1);
        assertEquals(200, mvc.perform(get("/api/v1/orders")
                        .param("sort", "updated_desc")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn().getResponse().getStatus());

        long orderId = ((Number) created.get("id")).longValue();
        MvcResult detail = mvc.perform(get("/api/v1/orders/" + orderId)
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(200, detail.getResponse().getStatus());
        assertEquals(1, ((List<?>) body(detail).get("containers")).size());
    }

    @Test
    void requiresTokensAndKeepsHealthPublic() throws Exception {
        assertEquals(401, mvc.perform(get("/api/v1/orders")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/v1/orders")
                        .header("Authorization", "Bearer wrong"))
                .andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/internal/tracking-runs"))
                .andReturn().getResponse().getStatus());
        MvcResult health = mvc.perform(get("/api/v1/health")).andReturn();
        assertEquals(200, health.getResponse().getStatus());
        assertEquals("ok", body(health).get("status"));
    }

    // ------------------------------------------------------------------
    // Main chain
    // ------------------------------------------------------------------

    @Test
    void mainChainRefreshClaimReportTimelineStats() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-CHAIN", "MSDU7172233", "DEPARTED");
        long orderId = ((Number) order.get("id")).longValue();
        Map<?, ?> container = (Map<?, ?>) ((List<?>) order.get("containers")).get(0);
        long containerId = ((Number) container.get("id")).longValue();

        String runId = refreshContainer(containerId);
        assertNotNull(runId);

        List<?> claimable = runs();
        assertTrue(claimable.stream().anyMatch(run -> runId.equals(((Map<?, ?>) run).get("run_id"))));

        Map<String, Object> task = claim(runId);
        assertEquals(containerId, ((Number) task.get("container_id")).longValue());
        String claimToken = (String) task.get("claim_token");
        assertNotNull(claimToken);

        // A second claim in the same batch must not hand out the running row again.
        assertTrue(((List<?>) claimResponse(runId).get("items")).isEmpty());

        MvcResult report = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(successReport(runId, containerId, claimToken,
                                "2026-12-07", "DEPARTED"))))
                .andReturn();
        assertEquals(200, report.getResponse().getStatus(), bodyText(report));
        assertEquals("success", body(report).get("attempt_status"));

        MvcResult detail = mvc.perform(get("/api/v1/orders/" + orderId)
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        Map<?, ?> reportedContainer = (Map<?, ?>) ((List<?>) body(detail).get("containers")).get(0);
        assertEquals("DEPARTED", reportedContainer.get("transport_status"));
        assertEquals("2026-12-07", reportedContainer.get("tracked_eta_date") == null
                ? ((Map<?, ?>) reportedContainer.get("tracked_eta")).get("date")
                : reportedContainer.get("tracked_eta_date"));
        assertNotNull(reportedContainer.get("baseline_snapshot_id"));

        MvcResult timeline = mvc.perform(get("/api/v1/containers/" + containerId + "/timeline")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(200, timeline.getResponse().getStatus());
        List<?> items = (List<?>) body(timeline).get("items");
        assertTrue(items.stream().anyMatch(item -> "snapshot".equals(((Map<?, ?>) item).get("type"))));

        MvcResult batch = mvc.perform(get("/api/v1/runs/" + runId)
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        Map<String, Object> batchBody = body(batch);
        assertEquals("completed", batchBody.get("status"));
        assertEquals(1, ((Number) batchBody.get("success")).intValue());

        MvcResult stats = mvc.perform(get("/api/v1/stats/overview")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(200, stats.getResponse().getStatus());
        assertEquals("container", body(stats).get("unit"));

        // Second batch: the ETA moves by two days → ETA_CHANGED + pending notification.
        String runId2 = refreshContainer(containerId);
        Map<String, Object> task2 = claim(runId2);
        String token2 = (String) task2.get("claim_token");
        MvcResult report2 = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(successReport(runId2, containerId, token2,
                                "2026-12-09", "DEPARTED"))))
                .andReturn();
        assertEquals(200, report2.getResponse().getStatus(), bodyText(report2));
        Map<String, Object> report2Body = body(report2);
        List<?> events = (List<?>) report2Body.get("events");
        assertTrue(events.stream().anyMatch(event ->
                "ETA_CHANGED".equals(((Map<?, ?>) event).get("event_type"))));
        Map<?, ?> notification = (Map<?, ?>) report2Body.get("order_notification");
        assertEquals("created", notification.get("state"));

        MvcResult timeline2 = mvc.perform(get("/api/v1/containers/" + containerId + "/timeline")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        List<?> items2 = (List<?>) body(timeline2).get("items");
        assertTrue(items2.stream().anyMatch(item -> "event".equals(((Map<?, ?>) item).get("type"))));
        assertTrue(items2.stream().anyMatch(item ->
                "notification".equals(((Map<?, ?>) item).get("type"))));
    }

    @Test
    void staleClaimIdempotencyAndConflict() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-STALE", "MSDU7172233", "DEPARTED");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);
        String runId = refreshContainer(containerId);
        Map<String, Object> task = claim(runId);
        String token = (String) task.get("claim_token");

        Map<String, Object> wrongToken = successReport(runId, containerId, "clm_wrong",
                "2026-12-07", "DEPARTED");
        MvcResult stale = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(wrongToken)))
                .andReturn();
        assertEquals(409, stale.getResponse().getStatus());
        assertEquals("STALE_CLAIM", errorCode(stale));

        Map<String, Object> report = successReport(runId, containerId, token,
                "2026-12-07", "DEPARTED");
        MvcResult first = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(report)))
                .andReturn();
        assertEquals(200, first.getResponse().getStatus(), "body=" + bodyText(first));
        long snapshotId = ((Number) body(first).get("snapshot_id")).longValue();

        MvcResult repeated = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(report)))
                .andReturn();
        assertEquals(200, repeated.getResponse().getStatus());
        assertEquals(snapshotId, ((Number) body(repeated).get("snapshot_id")).longValue());

        Map<String, Object> changed = successReport(runId, containerId, token,
                "2026-12-07", "ARRIVED_POD");
        MvcResult conflict = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(changed)))
                .andReturn();
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("CONFLICT", errorCode(conflict));

        // A container that is not part of this run gets TASK_NOT_IN_RUN.
        Map<String, Object> other = createOrder("PO-IT-STALE-2", "MSKU7654328", "DEPARTED");
        long otherContainer = firstContainerId(other);
        Map<String, Object> notInRunReport = successReport(runId, otherContainer, token,
                "2026-12-07", "DEPARTED");
        notInRunReport.put("container_no", null);
        MvcResult notInRun = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(notInRunReport)))
                .andReturn();
        assertEquals(409, notInRun.getResponse().getStatus());
        assertEquals("TASK_NOT_IN_RUN", errorCode(notInRun));

        // The order stays readable (archiving keeps history).
        MvcResult archived = mvc.perform(delete("/api/v1/orders/" + orderId)
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(204, archived.getResponse().getStatus());
    }

    @Test
    void failureAndRecoveryDriveHealthAndEvents() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-HEALTH", "MSDU7172233", "DEPARTED");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String run1 = refreshContainer(containerId);
        String token1 = (String) claim(run1).get("claim_token");
        MvcResult failed = report(run1, containerId, token1,
                failureReport(run1, containerId, token1, "TIMEOUT"));
        assertEquals(200, failed.getResponse().getStatus(), bodyText(failed));
        List<?> events = (List<?>) body(failed).get("events");
        assertTrue(events.stream().anyMatch(event ->
                "QUERY_FAILED".equals(((Map<?, ?>) event).get("event_type"))));

        Map<?, ?> afterFailure = containerOf(orderId, containerId);
        assertEquals("QUERY_FAILED", afterFailure.get("tracking_health"));
        assertEquals("TIMEOUT", afterFailure.get("error_code"));

        String run2 = refreshContainer(containerId);
        String token2 = (String) claim(run2).get("claim_token");
        MvcResult recovered = report(run2, containerId, token2,
                successReport(run2, containerId, token2, "2026-12-07", "DEPARTED"));
        assertEquals(200, recovered.getResponse().getStatus(), bodyText(recovered));
        List<?> recoveredEvents = (List<?>) body(recovered).get("events");
        assertTrue(recoveredEvents.stream().anyMatch(event ->
                "QUERY_RECOVERED".equals(((Map<?, ?>) event).get("event_type"))));

        Map<?, ?> afterSuccess = containerOf(orderId, containerId);
        assertEquals("OK", afterSuccess.get("tracking_health"));
        assertEquals(null, afterSuccess.get("error_code"));
    }

    @Test
    void lockedContainerBlocksSingleRefreshAndSkipsInOrderRefresh() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-LOCK", "MSDU7172233", "DEPARTED");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String runId = refreshContainer(containerId);
        claim(runId);

        MvcResult locked = mvc.perform(post("/api/v1/containers/" + containerId + "/refresh")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(423, locked.getResponse().getStatus());
        assertEquals("LOCKED", errorCode(locked));

        MvcResult orderRefresh = mvc.perform(post("/api/v1/orders/" + orderId + "/refresh")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(202, orderRefresh.getResponse().getStatus());
        Map<String, Object> refreshBody = body(orderRefresh);
        String secondRun = (String) refreshBody.get("run_id");
        assertEquals(1, ((Number) refreshBody.get("container_count")).intValue());

        MvcResult result = mvc.perform(get("/api/v1/runs/" + secondRun)
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(1, ((Number) body(result).get("skipped")).intValue());
    }

    @Test
    void updateContainerResetsBaselineWhenMilestoneChanges() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-PATCH", "MSDU7172233", "DEPARTED");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);
        String runId = refreshContainer(containerId);
        String token = (String) claim(runId).get("claim_token");
        report(runId, containerId, token,
                successReport(runId, containerId, token, "2026-12-07", "DEPARTED"));

        MvcResult patched = mvc.perform(patch("/api/v1/containers/" + containerId)
                        .header("Authorization", "Bearer " + API_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tracked_milestone", "FINAL_DESTINATION"))))
                .andReturn();
        assertEquals(200, patched.getResponse().getStatus(), bodyText(patched));
        Map<String, Object> body = body(patched);
        assertEquals(true, body.get("baseline_reset"));
        assertEquals(null, body.get("baseline_snapshot_id"));
        assertEquals("FINAL_DESTINATION", body.get("tracked_milestone"));

        Map<?, ?> detailContainer = containerOf(orderId, containerId);
        assertEquals("FINAL_DESTINATION", detailContainer.get("tracked_milestone"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> createOrder(String orderNo, String containerNo,
                                            String unusedStatus) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + API_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(orderNo, containerNo))))
                .andReturn();
        assertEquals(201, result.getResponse().getStatus(), bodyText(result));
        return body(result);
    }

    private Map<String, Object> orderBody(String orderNo, String containerNo) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("container_no", containerNo);
        container.put("carrier", "MSC");
        container.put("container_type", "40HC");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_no", orderNo);
        body.put("title", "集成测试订单");
        body.put("containers", List.of(container));
        return body;
    }

    private String refreshContainer(long containerId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/containers/" + containerId + "/refresh")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(202, result.getResponse().getStatus(), bodyText(result));
        return (String) body(result).get("run_id");
    }

    private List<?> runs() throws Exception {
        MvcResult result = mvc.perform(get("/internal/tracking-runs")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return (List<?>) body(result).get("runs");
    }

    private Map<String, Object> claim(String runId) throws Exception {
        Map<String, Object> body = claimResponse(runId);
        List<?> items = (List<?>) body.get("items");
        if (!items.isEmpty()) {
            return (Map<String, Object>) items.get(0);
        }
        return Map.of();
    }

    private Map<String, Object> claimResponse(String runId) throws Exception {
        MvcResult result = mvc.perform(post("/internal/tracking-tasks")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("run_id", runId, "limit", 1))))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), bodyText(result));
        return body(result);
    }

    private MvcResult report(String runId, long containerId, String token,
                             Map<String, Object> report) throws Exception {
        return mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(report)))
                .andReturn();
    }

    private Map<String, Object> successReport(String runId, long containerId, String claimToken,
                                              String etaDate, String status) {
        Map<String, Object> eta = new LinkedHashMap<>();
        eta.put("milestone", "POD_ARRIVAL");
        eta.put("raw_value", etaDate);
        eta.put("date", etaDate);
        eta.put("time", null);
        eta.put("timezone", "America/Vancouver");
        eta.put("precision", "DATE");
        eta.put("utc_at", null);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("milestone", "LOADED");
        event.put("event_time_type", "actual");
        event.put("occurred_at", "2026-11-21T16:20:00+08:00");
        event.put("timezone", "Asia/Shanghai");
        event.put("location", "Ningbo, CN");
        event.put("vessel", "CAPE SOUNIO");
        event.put("voyage", "UK647R");
        event.put("raw_text", "Loaded on vessel");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId);
        body.put("container_id", containerId);
        body.put("container_no", "MSDU7172233");
        body.put("carrier", "MSC");
        body.put("claim_token", claimToken);
        body.put("retry_count", 0);
        body.put("mapping_version", 3);
        body.put("queried_at", "2026-11-22T08:00:35+08:00");
        body.put("success", true);
        body.put("error_code", null);
        body.put("error_message", null);
        body.put("raw_status", "Loaded on vessel CAPE SOUNIO/UK647R at Ningbo");
        body.put("transport_status", status);
        body.put("etas", List.of(eta));
        body.put("source_events", List.of(event));
        body.put("location", "Busan, KR");
        body.put("vessel", "CAPE SOUNIO");
        body.put("voyage", "UK647R");
        body.put("pol", "Ningbo, CN");
        body.put("pod", "Vancouver, CA");
        body.put("final_destination", "Toronto, CA");
        body.put("source", "msc_web");
        body.put("raw_payload", Map.of("html_title", "Container Tracking"));
        return body;
    }

    private Map<String, Object> failureReport(String runId, long containerId, String claimToken,
                                              String errorCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId);
        body.put("container_id", containerId);
        body.put("container_no", "MSDU7172233");
        body.put("carrier", "MSC");
        body.put("claim_token", claimToken);
        body.put("retry_count", 2);
        body.put("mapping_version", 3);
        body.put("queried_at", "2026-11-22T08:00:40+08:00");
        body.put("success", false);
        body.put("error_code", errorCode);
        body.put("error_message", "MSC tracking page timeout");
        body.put("source", "msc_web");
        return body;
    }

    private Map<?, ?> containerOf(long orderId, long containerId) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/orders/" + orderId)
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        for (Object item : (List<?>) body(detail).get("containers")) {
            Map<?, ?> container = (Map<?, ?>) item;
            if (((Number) container.get("id")).longValue() == containerId) {
                return container;
            }
        }
        throw new AssertionError("container not found in order detail");
    }

    private long firstContainerId(Map<String, Object> order) {
        Map<?, ?> container = (Map<?, ?>) ((List<?>) order.get("containers")).get(0);
        return ((Number) container.get("id")).longValue();
    }

    private String errorCode(MvcResult result) throws Exception {
        Map<String, Object> body = body(result);
        Map<?, ?> error = (Map<?, ?>) body.get("error");
        return (String) error.get("code");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(MvcResult result) throws Exception {
        return JSON.readValue(bodyText(result), Map.class);
    }

    private String bodyText(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String json(Object value) throws Exception {
        return JSON.writeValueAsString(value);
    }

    private List<Long> ids(List<?> rows) {
        List<Long> ids = new ArrayList<>();
        for (Object row : rows) {
            ids.add(((Number) ((Map<?, ?>) row).get("id")).longValue());
        }
        return ids;
    }
}
