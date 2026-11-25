package com.shiptrack.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.api.config.FeishuProperties;
import com.shiptrack.api.repo.NotificationRecord;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.service.AppConfigService;
import com.shiptrack.api.service.FeishuClient;
import com.shiptrack.api.service.FeishuSigner;
import com.shiptrack.api.service.NotificationDispatchService;
import com.shiptrack.api.service.NotificationService;
import com.shiptrack.api.support.JsonStore;
import com.shiptrack.contract.dto.NotificationRetryResponse;
import com.shiptrack.contract.enums.NotificationStatus;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    /**
     * The arrival rule is tested with ordinary status-change notifications
     * switched off; the other notifications are unconditional by §10.3.
     */
    private static final Path CONFIG_FILE = writeConfig();

    private static Path writeConfig() {
        try {
            Path path = Files.createTempFile("shiptrack-it-config", ".yaml");
            Files.writeString(path, "notify_on_status_change: false\n");
            return path;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                String mode = MODE.get();
                int status = 200;
                String body = "{\"code\":0,\"msg\":\"success\"}";
                if (!"ok".equals(mode) && !"slowBody".equals(mode)) {
                    status = 500;
                    body = "{\"code\":19001,\"msg\":\"stub failure\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                if ("slowBody".equals(mode)) {
                    sleep(3000);
                }
                try {
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // The client cancelled after the total deadline expired.
                }
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
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
        registry.add("app.config-path", () -> CONFIG_FILE.toString());
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

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JsonStore jsonStore;

    @Autowired
    private AppConfigService appConfigService;

    @Autowired
    private JdbcTemplate jdbc;

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
        assertTrue(text.contains("【船期变化】PO-IT-NOTIFY"), text);
        assertTrue(text.contains("• MSDU7172233  到港 2026-12-07 → 2026-12-09"
                + "（较上次推迟 2 天；较基线累计推迟 2 天；当前风险 延误）"), text);
        assertTrue(text.contains("航线：Ningbo, CN → Vancouver, CA"), text);
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

    @Test
    void bodyReadTimeoutFailsTheAttemptAndTheNextTickContinues() throws Exception {
        BODIES.clear();
        Map<String, Object> order = createOrder("PO-IT-SLOW-BODY");
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

        MODE.set("slowBody");
        assertEquals(0, dispatcher.dispatchOnce());
        NotificationRecord timedOut = notificationRepository.findById(notificationId).orElseThrow();
        assertEquals("failed", timedOut.status().wireValue());
        assertEquals("请求超时", timedOut.lastError());
        assertNull(timedOut.feishuResponseCode());

        MODE.set("ok");
        sleep(80);
        assertTrue(dispatcher.dispatchOnce() >= 1, "the next tick must still dispatch");
        assertEquals("sent",
                notificationRepository.findById(notificationId).orElseThrow().status().wireValue());
    }

    @Test
    void arrivedPodNotifiesEvenWhenStatusChangeNotificationsAreOff() throws Exception {
        BODIES.clear();
        Map<String, Object> order = createOrder("PO-IT-ARRIVED");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String run1 = refreshContainer(containerId);
        report(run1, containerId, (String) claim(run1).get("claim_token"),
                "2026-12-09", "DEPARTED");
        String run2 = refreshContainer(containerId);
        Map<String, Object> reportBody = report(run2, containerId,
                (String) claim(run2).get("claim_token"), "2026-12-09", "ARRIVED_POD");
        Map<?, ?> notification = (Map<?, ?>) reportBody.get("order_notification");
        assertEquals("created", notification.get("state"),
                "an ARRIVED_POD transition must notify regardless of the status switch");
        String key = "order:" + orderId + ":run:" + run2 + ":channel:feishu";

        assertTrue(dispatcher.dispatchOnce() >= 1);
        String text = sentText(key);
        assertTrue(text.contains("• MSDU7172233  已到港"), text);
        assertFalse(text.contains("已到港（"),
                "a missing actual trajectory must not print the planned ETA: " + text);
        assertTrue(text.contains("航线：Ningbo, CN → Vancouver, CA"), text);
    }

    @Test
    void etaDeltaAcrossMidnightUsesHoursNotCalendarDays() throws Exception {
        BODIES.clear();
        Map<String, Object> order = createOrder("PO-IT-ETA-HOURS");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String run1 = refreshContainer(containerId);
        report(run1, containerId, (String) claim(run1).get("claim_token"),
                dateTimeEta("2026-12-07", "23:30", "America/Vancouver",
                        "2026-12-08T07:30:00Z"),
                List.of(loadedEvent()), "DEPARTED");
        String run2 = refreshContainer(containerId);
        Map<String, Object> reportBody = report(run2, containerId,
                (String) claim(run2).get("claim_token"),
                dateTimeEta("2026-12-09", "00:30", "America/Vancouver",
                        "2026-12-09T08:30:00Z"),
                List.of(loadedEvent()), "DEPARTED");
        assertEquals("created",
                ((Map<?, ?>) reportBody.get("order_notification")).get("state"));
        String key = "order:" + orderId + ":run:" + run2 + ":channel:feishu";

        assertTrue(dispatcher.dispatchOnce() >= 1);
        String text = sentText(key);
        assertTrue(text.contains("到港 2026-12-07 23:30 → 2026-12-09 00:30"), text);
        assertTrue(text.contains("较上次推迟 25 小时"), text);
        assertTrue(text.contains("较基线累计推迟 25 小时"), text);
        assertFalse(text.contains("较上次推迟 2 天"), text);
    }

    @Test
    void etaDeltaUsesUtcAtAcrossTheDstFallBackOverlap() throws Exception {
        BODIES.clear();
        Map<String, Object> order = createOrder("PO-IT-ETA-DST");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        // America/Vancouver falls back on 2026-11-01: 01:30 local happens twice,
        // at 08:30Z (PDT) and 09:30Z (PST). The first ETA is the second 01:30
        // (09:30Z); the second ETA is 01:30 the next day (2026-11-02T09:30Z).
        // Their utc_at differ by 24 hours, but rebuilding the first instant from
        // its wall clock picks the earlier PDT offset (08:30Z), which would make
        // "较上次" 25 hours and disagree with the 24-hour baseline delta.
        String run1 = refreshContainer(containerId);
        report(run1, containerId, (String) claim(run1).get("claim_token"),
                dateTimeEta("2026-11-01", "01:30", "America/Vancouver",
                        "2026-11-01T09:30:00Z"),
                List.of(loadedEvent()), "DEPARTED");
        String run2 = refreshContainer(containerId);
        Map<String, Object> reportBody = report(run2, containerId,
                (String) claim(run2).get("claim_token"),
                dateTimeEta("2026-11-02", "01:30", "America/Vancouver",
                        "2026-11-02T09:30:00Z"),
                List.of(loadedEvent()), "DEPARTED");
        assertEquals("created",
                ((Map<?, ?>) reportBody.get("order_notification")).get("state"));
        String key = "order:" + orderId + ":run:" + run2 + ":channel:feishu";

        assertTrue(dispatcher.dispatchOnce() >= 1);
        String text = sentText(key);
        assertTrue(text.contains("到港 2026-11-01 01:30 → 2026-11-02 01:30"), text);
        assertTrue(text.contains("较上次推迟 24 小时"), text);
        assertTrue(text.contains("较基线累计推迟 24 小时"), text);
        assertFalse(text.contains("较上次推迟 25 小时"),
                "the ambiguous local time must not shift the delta: " + text);
    }

    @Test
    void arrivalDateComesFromTheActualTrajectory() throws Exception {
        BODIES.clear();
        Map<String, Object> order = createOrder("PO-IT-ARRIVAL-DATE");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String run1 = refreshContainer(containerId);
        report(run1, containerId, (String) claim(run1).get("claim_token"),
                "2026-12-09", "DEPARTED");
        String run2 = refreshContainer(containerId);
        report(run2, containerId, (String) claim(run2).get("claim_token"),
                dateEta("2026-12-09"),
                List.of(loadedEvent(),
                        arrivalEvent("2026-12-10T14:30:00-08:00", "America/Vancouver")),
                "ARRIVED_POD");
        String key = "order:" + orderId + ":run:" + run2 + ":channel:feishu";

        assertTrue(dispatcher.dispatchOnce() >= 1);
        String text = sentText(key);
        assertTrue(text.contains("已到港（2026-12-10）"), text);
        assertFalse(text.contains("已到港（2026-12-09）"),
                "the tracked ETA must not be shown as the arrival date: " + text);
    }

    @Test
    void healthDowngradeNotifies() throws Exception {
        BODIES.clear();
        Map<String, Object> order = createOrder("PO-IT-HEALTH");
        long orderId = ((Number) order.get("id")).longValue();
        long containerId = firstContainerId(order);

        String run1 = refreshContainer(containerId);
        report(run1, containerId, (String) claim(run1).get("claim_token"), "2026-12-09");
        String run2 = refreshContainer(containerId);
        Map<String, Object> reportBody = reportFailure(run2, containerId,
                (String) claim(run2).get("claim_token"));
        Map<?, ?> notification = (Map<?, ?>) reportBody.get("order_notification");
        assertEquals("created", notification.get("state"));
        String key = "order:" + orderId + ":run:" + run2 + ":channel:feishu";

        assertTrue(dispatcher.dispatchOnce() >= 1);
        String text = sentText(key);
        assertTrue(text.contains("• MSDU7172233  查询失败，数据可能过期"), text);
    }

    @Test
    void missingConfigFailsTheQueueAndManualResendStillWorks() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-NO-WEBHOOK");
        long orderId = ((Number) order.get("id")).longValue();
        String key = "order:" + orderId + ":run:R-NO-WEBHOOK:channel:feishu";
        long notificationId = notificationRepository.insertPendingIfAbsent(orderId, key,
                "R-NO-WEBHOOK", payloadJson(key));

        FeishuProperties empty = new FeishuProperties();
        empty.setDispatchEnabled(true);
        NotificationDispatchService withoutWebhook = new NotificationDispatchService(
                empty, new FeishuClient(empty), notificationRepository, jsonStore,
                appConfigService);

        assertEquals(0, withoutWebhook.dispatchOnce());
        NotificationRecord failed = notificationRepository.findById(notificationId).orElseThrow();
        assertEquals("failed", failed.status().wireValue());
        assertEquals(NotificationDispatchService.MISSING_CONFIG_ERROR, failed.lastError());
        assertNull(failed.nextRetryAt(), "no automatic backoff while unconfigured");

        MvcResult retried = mvc.perform(post("/api/v1/notifications/" + notificationId + "/retry")
                        .header("Authorization", "Bearer " + API_TOKEN))
                .andReturn();
        assertEquals(202, retried.getResponse().getStatus(), bodyText(retried));
        assertEquals("pending", body(retried).get("status"),
                "§5.14 manual resend must still work");
        jdbc.update("DELETE FROM notifications WHERE id = ?", notificationId);
    }

    @Test
    void disabledDispatchLeavesTheQueuePending() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-DISABLED");
        long orderId = ((Number) order.get("id")).longValue();
        String key = "order:" + orderId + ":run:R-DISABLED:channel:feishu";
        long notificationId = notificationRepository.insertPendingIfAbsent(orderId, key,
                "R-DISABLED", payloadJson(key));

        FeishuProperties disabled = new FeishuProperties();
        disabled.setWebhookUrl("http://127.0.0.1:1/hook");
        disabled.setDispatchEnabled(false);
        NotificationDispatchService off = new NotificationDispatchService(
                disabled, new FeishuClient(disabled), notificationRepository, jsonStore,
                appConfigService);

        assertEquals(0, off.dispatchOnce());
        assertEquals("pending",
                notificationRepository.findById(notificationId).orElseThrow().status().wireValue());
        jdbc.update("DELETE FROM notifications WHERE id = ?", notificationId);
    }

    @Test
    void rateLimitedAttemptIsFailedWithBackoffAndNoHttpCall() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-RATE");
        long orderId = ((Number) order.get("id")).longValue();
        String key = "order:" + orderId + ":run:R-RATE:channel:feishu";
        long notificationId = notificationRepository.insertPendingIfAbsent(orderId, key,
                "R-RATE", payloadJson(key));
        int before = bodiesFor(key).size();

        FeishuProperties limited = new FeishuProperties();
        limited.setWebhookUrl("http://127.0.0.1:" + FEISHU.getAddress().getPort() + "/hook");
        limited.setDispatchEnabled(true);
        limited.setRatePerSecond(0);
        limited.setRatePerMinute(100);
        NotificationDispatchService limitedDispatcher = new NotificationDispatchService(
                limited, new FeishuClient(limited), notificationRepository, jsonStore,
                appConfigService);

        assertEquals(0, limitedDispatcher.dispatchOnce());
        NotificationRecord failed = notificationRepository.findById(notificationId).orElseThrow();
        assertEquals("failed", failed.status().wireValue());
        assertEquals(NotificationDispatchService.RATE_LIMITED_ERROR, failed.lastError());
        assertNotNull(failed.nextRetryAt(), "the excess attempt enters the normal backoff");
        assertEquals(before, bodiesFor(key).size(), "a rejected attempt must not call Feishu");
        jdbc.update("DELETE FROM notifications WHERE id = ?", notificationId);
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-CLAIM");
        long orderId = ((Number) order.get("id")).longValue();
        String key = "order:" + orderId + ":run:R-CLAIM:channel:feishu";
        long notificationId = notificationRepository.insertPendingIfAbsent(orderId, key,
                "R-CLAIM", payloadJson(key));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<NotificationRepository.Claimed>> first =
                    pool.submit(() -> {
                        start.await();
                        return notificationRepository.claim(notificationId);
                    });
            Future<Optional<NotificationRepository.Claimed>> second =
                    pool.submit(() -> {
                        start.await();
                        return notificationRepository.claim(notificationId);
                    });
            start.countDown();
            int winners = (first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertEquals(1, winners, "only one claim may flip pending → sending");
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM notifications WHERE id = ?", notificationId);
        }
    }

    @Test
    void retryResponseAlwaysDescribesThePendingResendUnderConcurrentDispatch() throws Exception {
        Map<String, Object> order = createOrder("PO-IT-RETRY-RACE");
        long orderId = ((Number) order.get("id")).longValue();
        String key = "order:" + orderId + ":run:R-RETRY-RACE:channel:feishu";
        long notificationId = notificationRepository.insertPendingIfAbsent(orderId, key,
                "R-RETRY-RACE", payloadJson(key));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                jdbc.update("""
                        UPDATE notifications
                        SET status = 'failed', retry_count = 0, sending_at = NULL,
                            next_retry_at = NULL, sent_at = NULL
                        WHERE id = ?
                        """, notificationId);
                CountDownLatch start = new CountDownLatch(1);
                Future<NotificationRetryResponse> retried = pool.submit(() -> {
                    start.await();
                    return notificationService.retry(notificationId);
                });
                Future<Integer> dispatched = pool.submit(() -> {
                    start.await();
                    return dispatcher.dispatchOnce();
                });
                start.countDown();
                NotificationRetryResponse response = retried.get(10, TimeUnit.SECONDS);
                dispatched.get(10, TimeUnit.SECONDS);
                assertEquals(NotificationStatus.PENDING, response.status(),
                        "202 must describe this resend, never a concurrent sending/sent");
                assertEquals(1, response.retryCount());
            }
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM notifications WHERE id = ?", notificationId);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String sentText(String notificationKey) throws Exception {
        List<String> sent = bodiesFor(notificationKey);
        assertFalse(sent.isEmpty(), "expected a delivered message for " + notificationKey);
        return JSON.readTree(sent.get(sent.size() - 1)).get("content").get("text").asText();
    }

    private String payloadJson(String key) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "【船期变化】PO-IT");
        payload.put("lines", List.of("MSDU7172233  到港 2026-12-07 → 2026-12-09"));
        payload.put("queried_at", "2026-11-22T08:00:00Z");
        payload.put("source", "MSC");
        return jsonStore.write(payload);
    }

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
        return report(runId, containerId, token, etaDate, "DEPARTED");
    }

    private Map<String, Object> report(String runId, long containerId, String token,
                                       String etaDate, String transportStatus) throws Exception {
        return report(runId, containerId, token, dateEta(etaDate), List.of(loadedEvent()),
                transportStatus);
    }

    private Map<String, Object> report(String runId, long containerId, String token,
                                       Map<String, Object> eta,
                                       List<Map<String, Object>> sourceEvents,
                                       String transportStatus) throws Exception {
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
        payload.put("transport_status", transportStatus);
        payload.put("etas", List.of(eta));
        payload.put("source_events", sourceEvents);
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

    private static Map<String, Object> dateEta(String date) {
        Map<String, Object> eta = new LinkedHashMap<>();
        eta.put("milestone", "POD_ARRIVAL");
        eta.put("raw_value", date);
        eta.put("date", date);
        eta.put("time", null);
        eta.put("timezone", "America/Vancouver");
        eta.put("precision", "DATE");
        eta.put("utc_at", null);
        return eta;
    }

    private static Map<String, Object> dateTimeEta(String date, String time, String zone,
                                                   String utcAt) {
        Map<String, Object> eta = new LinkedHashMap<>();
        eta.put("milestone", "POD_ARRIVAL");
        eta.put("raw_value", date + " " + time);
        eta.put("date", date);
        eta.put("time", time);
        eta.put("timezone", zone);
        eta.put("precision", "DATETIME");
        eta.put("utc_at", utcAt);
        return eta;
    }

    private static Map<String, Object> loadedEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("milestone", "LOADED");
        event.put("event_time_type", "actual");
        event.put("occurred_at", "2026-11-21T16:20:00+08:00");
        event.put("timezone", "Asia/Shanghai");
        event.put("location", "Ningbo, CN");
        event.put("vessel", "CAPE SOUNIO");
        event.put("voyage", "UK647R");
        event.put("raw_text", "Loaded on vessel");
        return event;
    }

    private static Map<String, Object> arrivalEvent(String occurredAt, String zone) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("milestone", "ARRIVED_POD");
        event.put("event_time_type", "actual");
        event.put("occurred_at", occurredAt);
        event.put("timezone", zone);
        event.put("location", "Vancouver, CA");
        event.put("raw_text", "Arrived at POD");
        return event;
    }

    private Map<String, Object> reportFailure(String runId, long containerId, String token)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run_id", runId);
        payload.put("container_id", containerId);
        payload.put("container_no", "MSDU7172233");
        payload.put("carrier", "MSC");
        payload.put("claim_token", token);
        payload.put("retry_count", 0);
        payload.put("mapping_version", 3);
        payload.put("queried_at", "2026-11-22T08:00:35+08:00");
        payload.put("success", false);
        payload.put("error_code", "TIMEOUT");
        payload.put("error_message", "carrier page timed out");
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
