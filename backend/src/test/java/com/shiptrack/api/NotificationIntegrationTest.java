package com.shiptrack.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.api.repo.NotificationRecord;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.service.FeishuSigner;
import com.shiptrack.api.service.NotificationDispatchService;
import com.sun.net.httpserver.HttpServer;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * §5.13/§5.14 + §7 end to end against a real MySQL and a local Feishu stub:
 * report → pending notification → dispatch → sent / failed + backoff →
 * manual retry. No real webhook is contacted.
 */
@SpringBootTest
class NotificationIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final String SECRET = "it-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final List<String> BODIES = Collections.synchronizedList(new ArrayList<>());
    private static final HttpServer FEISHU = startStub();
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shiptrack")
            .withUsername("shiptrack")
            .withPassword("shiptrack-test");

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                int status = 200;
                String body = "{\"code\":0,\"msg\":\"success\"}";
                if (!"ok".equals(MODE.get())) {
                    status = 500;
                    body = "{\"code\":19001,\"msg\":\"stub failure\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
        registry.add("app.feishu.webhook-url",
                () -> "http://127.0.0.1:" + FEISHU.getAddress().getPort() + "/hook");
        registry.add("app.feishu.webhook-secret", () -> SECRET);
        registry.add("app.feishu.dispatch-enabled", () -> "true");
        // Keep the scheduler out of the way: the tests drive dispatchOnce().
        registry.add("app.feishu.dispatch-interval-ms", () -> "3600000");
        registry.add("app.feishu.dispatch-initial-delay-ms", () -> "3600000");
        registry.add("app.feishu.request-timeout-ms", () -> "2000");
        registry.add("app.feishu.first-retry-backoff-ms", () -> "30");
        registry.add("app.feishu.second-retry-backoff-ms", () -> "30");
        registry.add("app.feishu.rate-per-second", () -> "100");
        registry.add("app.feishu.rate-per-minute", () -> "1000");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationDispatchService dispatcher;

    @Autowired
    private NotificationRepository notificationRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        MODE.set("ok");
    }

    @Test
    void reportQueuesPendingThenDispatchSendsExactlyOnce() throws Exception {
        BODIES.clear();
        Map<String, Object> order = createOrder("PO-IT-NOTIFY");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String run1 = refreshContainer(containerId);
        String token1 = (String) claim(run1).get("claim_token");
        report(run1, containerId, token1, "2026-12-07");

        String run2 = refreshContainer(containerId);
        String token2 = (String) claim(run2).get("claim_token");
        Map<String, Object> reportBody = report(run2, containerId, token2, "2026-12-09");
        Map<?, ?> notification = (Map<?, ?>) reportBody.get("order_notification");
        assertEquals("created", notification.get("state"));
        long notificationId = ((Number) notification.get("notification_id")).longValue();
        String key = "order:" + orderId + ":run:" + run2 + ":channel:feishu";

        MvcResult listed = mvc.perform(get("/api/v1/notifications")
                        .param("order_id", Long.toString(orderId))
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(200, listed.getResponse().getStatus());
        Map<String, Object> page = body(listed);
        assertEquals(1, ((Number) page.get("total")).intValue());
        Map<?, ?> item = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertEquals(notificationId, ((Number) item.get("id")).longValue());
        assertEquals("pending", item.get("status"));
        assertEquals("feishu", item.get("channel"));
        assertEquals(key, item.get("notification_key"));
        assertNull(item.get("feishu_response_code"));
        Map<?, ?> payload = (Map<?, ?>) item.get("payload");
        assertEquals("【船期变化】PO-IT-NOTIFY", payload.get("title"));
        assertFalse(((List<?>) payload.get("lines")).isEmpty());

        assertTrue(dispatcher.dispatchOnce() >= 1);

        MvcResult detail = mvc.perform(get("/api/v1/notifications/" + notificationId)
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(200, detail.getResponse().getStatus());
        Map<String, Object> after = body(detail);
        assertEquals("sent", after.get("status"));
        assertEquals(0, ((Number) after.get("feishu_response_code")).intValue());
        assertNotNull(after.get("sent_at"));

        List<String> sent = bodiesFor(key);
        assertEquals(1, sent.size(), "同一通知只能发送一次");
        JsonNode request = JSON.readTree(sent.get(0));
        String timestamp = request.get("timestamp").asText();
        assertEquals(FeishuSigner.sign(timestamp, SECRET), request.get("sign").asText());
        String text = request.get("content").get("text").asText();
        assertTrue(text.contains("• MSDU7172233"));
        assertTrue(text.contains("通知标识：" + key));
        assertTrue(text.contains("数据来源：MSC"));
        assertTrue(request.toString().getBytes(StandardCharsets.UTF_8).length
                <= 20 * 1024);

        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals(1, bodiesFor(key).size(), "重跑派发不会重复发送");
    }

    @Test
    void failureRetriesTwiceThenManualRetryAndConflict() throws Exception {
        BODIES.clear();
        MODE.set("http500");
        Map<String, Object> order = createOrder("PO-IT-NOTIFY-FAIL");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String run1 = refreshContainer(containerId);
        report(run1, containerId, (String) claim(run1).get("claim_token"), "2026-12-07");
        String run2 = refreshContainer(containerId);
        Map<String, Object> reportBody = report(run2, containerId,
                (String) claim(run2).get("claim_token"), "2026-12-09");
        long notificationId = ((Number) ((Map<?, ?>) reportBody.get("order_notification"))
                .get("notification_id")).longValue();
        String key = "order:" + orderId + ":run:" + run2 + ":channel:feishu";

        dispatcher.dispatchOnce();
        NotificationRecord firstFailure = notificationRepository.findById(notificationId).orElseThrow();
        assertEquals("failed", firstFailure.status().wireValue());
        assertEquals(0, firstFailure.retryCount());
        assertNotNull(firstFailure.nextRetryAt());
        assertTrue(firstFailure.lastError().startsWith("HTTP 500"));

        Thread.sleep(60);
        dispatcher.dispatchOnce();
        NotificationRecord secondFailure = notificationRepository.findById(notificationId).orElseThrow();
        assertEquals(1, secondFailure.retryCount());

        Thread.sleep(60);
        dispatcher.dispatchOnce();
        NotificationRecord thirdFailure = notificationRepository.findById(notificationId).orElseThrow();
        assertEquals(2, thirdFailure.retryCount());
        assertNull(thirdFailure.nextRetryAt(), "自动重试最多两次");
        assertEquals(3, bodiesFor(key).size());

        dispatcher.dispatchOnce();
        assertEquals(3, bodiesFor(key).size(), "到达上限后不再自动发送");

        MvcResult failedList = mvc.perform(get("/api/v1/notifications")
                        .param("order_id", Long.toString(orderId))
                        .param("status", "failed")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(1, ((Number) body(failedList).get("total")).intValue());

        MvcResult retried = mvc.perform(post("/api/v1/notifications/" + notificationId + "/retry")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(202, retried.getResponse().getStatus(), bodyText(retried));
        Map<String, Object> retriedBody = body(retried);
        assertEquals("pending", retriedBody.get("status"));
        assertEquals(3, ((Number) retriedBody.get("retry_count")).intValue());

        MODE.set("ok");
        assertTrue(dispatcher.dispatchOnce() >= 1);
        NotificationRecord sent = notificationRepository.findById(notificationId).orElseThrow();
        assertEquals("sent", sent.status().wireValue());
        assertEquals(4, bodiesFor(key).size());

        MvcResult conflict = mvc.perform(post("/api/v1/notifications/" + notificationId + "/retry")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("CONFLICT", errorCode(conflict));

        MvcResult invalid = mvc.perform(get("/api/v1/notifications")
                        .param("status", "unknown")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(400, invalid.getResponse().getStatus());
        assertEquals("VALIDATION_ERROR", errorCode(invalid));

        assertEquals(404, mvc.perform(get("/api/v1/notifications/9999999")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<String> bodiesFor(String notificationKey) throws Exception {
        List<String> matches = new ArrayList<>();
        synchronized (BODIES) {
            for (String body : BODIES) {
                JsonNode node = JSON.readTree(body);
                if (node.path("content").path("text").asText("").contains(notificationKey)) {
                    matches.add(body);
                }
            }
        }
        return matches;
    }

    private Map<String, Object> createOrder(String orderNo) throws Exception {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("container_no", "MSDU7172233");
        container.put("carrier", "MSC");
        container.put("container_type", "40HC");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_no", orderNo);
        payload.put("title", "通知集成测试订单");
        payload.put("containers", List.of(container));
        MvcResult result = mvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + API_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andReturn();
        assertEquals(201, result.getResponse().getStatus(), bodyText(result));
        return body(result);
    }

    private String refreshContainer(long containerId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/containers/" + containerId + "/refresh")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(202, result.getResponse().getStatus(), bodyText(result));
        return (String) body(result).get("run_id");
    }

    private Map<String, Object> claim(String runId) throws Exception {
        MvcResult result = mvc.perform(post("/internal/tracking-tasks")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("run_id", runId, "limit", 1))))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), bodyText(result));
        List<?> items = (List<?>) body(result).get("items");
        assertFalse(items.isEmpty(), "no claimable task in " + runId);
        return (Map<String, Object>) items.get(0);
    }

    private Map<String, Object> report(String runId, long containerId, String token,
                                       String etaDate) throws Exception {
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run_id", runId);
        payload.put("container_id", containerId);
        payload.put("container_no", "MSDU7172233");
        payload.put("carrier", "MSC");
        payload.put("claim_token", token);
        payload.put("retry_count", 0);
        payload.put("mapping_version", 3);
        payload.put("queried_at", "2026-11-22T08:00:35+08:00");
        payload.put("success", true);
        payload.put("raw_status", "Loaded on vessel CAPE SOUNIO/UK647R at Ningbo");
        payload.put("transport_status", "DEPARTED");
        payload.put("etas", List.of(eta));
        payload.put("source_events", List.of(event));
        payload.put("location", "Busan, KR");
        payload.put("vessel", "CAPE SOUNIO");
        payload.put("voyage", "UK647R");
        payload.put("pol", "Ningbo, CN");
        payload.put("pod", "Vancouver, CA");
        payload.put("final_destination", "Toronto, CA");
        payload.put("source", "msc_web");

        MvcResult result = mvc.perform(post("/internal/tracking-results")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), bodyText(result));
        return body(result);
    }

    private long firstContainerId(Map<String, Object> order) {
        Map<?, ?> container = (Map<?, ?>) ((List<?>) order.get("containers")).get(0);
        return ((Number) container.get("id")).longValue();
    }

    private String errorCode(MvcResult result) throws Exception {
        Map<?, ?> error = (Map<?, ?>) body(result).get("error");
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
}
