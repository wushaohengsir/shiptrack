package com.shiptrack.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.api.config.FeishuProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §7.5 sender contract against a local stub: signature, success rule
 * (2xx <b>and</b> {@code code == 0}), timeout handling and the 20 KB cap.
 */
class FeishuClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final List<String> BODIES = Collections.synchronizedList(new ArrayList<>());

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            String mode = MODE.get();
            if ("slow".equals(mode)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = 200;
            String body = "{\"code\":0,\"msg\":\"success\"}";
            switch (mode) {
                case "code" -> body = "{\"code\":19001,\"msg\":\"param error\"}";
                case "http500" -> {
                    status = 500;
                    body = "{\"code\":-1,\"msg\":\"stub failure\"}";
                }
                case "plain" -> body = "not-json";
                default -> {
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signatureMatchesTheFixedSpec() {
        // Vector computed independently: HMAC-SHA256(key="1700000000\ntest-secret", message="").
        assertEquals("mbm4Y4oluIPQ00qlBIhX8vAZ0EKv3nw0LuTb91jPL84=",
                FeishuSigner.sign("1700000000", "test-secret"));
    }

    @Test
    void unsignedWhenNoSecretConfigured() throws Exception {
        BODIES.clear();
        MODE.set("ok");
        FeishuClient client = new FeishuClient(properties(2000, ""));

        FeishuClient.FeishuSendResult result = client.sendFirst(List.of("【船期变化】PO-1"));

        assertTrue(result.success());
        JsonNode sent = JSON.readTree(BODIES.get(0));
        assertFalse(sent.has("sign"));
        assertFalse(sent.has("timestamp"));
        assertEquals("text", sent.get("msg_type").asText());
    }

    @Test
    void signsWithTimestampAndSecretWhenConfigured() throws Exception {
        BODIES.clear();
        MODE.set("ok");
        FeishuClient client = new FeishuClient(properties(2000, "stub-secret"));

        assertTrue(client.sendFirst(List.of("【船期变化】PO-1")).success());

        JsonNode sent = JSON.readTree(BODIES.get(0));
        String timestamp = sent.get("timestamp").asText();
        assertNotNull(timestamp);
        assertEquals(FeishuSigner.sign(timestamp, "stub-secret"), sent.get("sign").asText());
        assertEquals("【船期变化】PO-1",
                sent.get("content").get("text").asText());
    }

    @Test
    void nonZeroCodeIsAFailureEvenWhenHttpIs2xx() {
        BODIES.clear();
        MODE.set("code");
        FeishuClient.FeishuSendResult result =
                new FeishuClient(properties(2000, "")).sendFirst(List.of("x"));

        assertFalse(result.success());
        assertEquals(19001, result.responseCode());
        assertTrue(result.error().contains("19001"));
    }

    @Test
    void non2xxIsAFailure() {
        BODIES.clear();
        MODE.set("http500");
        FeishuClient.FeishuSendResult result =
                new FeishuClient(properties(2000, "")).sendFirst(List.of("x"));

        assertFalse(result.success());
        assertEquals(-1, result.responseCode());
        assertTrue(result.error().startsWith("HTTP 500"));
    }

    @Test
    void nonJsonSuccessBodyIsAFailure() {
        BODIES.clear();
        MODE.set("plain");
        FeishuClient.FeishuSendResult result =
                new FeishuClient(properties(2000, "")).sendFirst(List.of("x"));

        assertFalse(result.success());
        assertEquals(200, result.responseCode());
        assertTrue(result.error().contains("缺少 code"));
    }

    @Test
    void timeoutIsAFailureAndLeavesNoCode() {
        BODIES.clear();
        MODE.set("slow");
        FeishuClient.FeishuSendResult result =
                new FeishuClient(properties(150, "")).sendFirst(List.of("x"));

        assertFalse(result.success());
        assertNull(result.responseCode());
        assertEquals("请求超时", result.error());
    }

    @Test
    void trimsToThe20KbLimitDeterministically() throws Exception {
        BODIES.clear();
        MODE.set("ok");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lines.add(String.format("MSKU%07d  到港时间变更 2026-12-04 → 2026-12-06（较上次推迟 2 天）", i));
        }
        Map<String, Object> payload = Map.of(
                "title", "【船期变化】PO-BIG",
                "lines", lines,
                "queried_at", "2026-11-22T08:00:00Z",
                "source", "MSC");
        List<String> candidates = FeishuMessageBuilder.candidates(payload, "order:1:run:x", null);
        assertTrue(candidates.get(0).getBytes(StandardCharsets.UTF_8).length > FeishuClient.MAX_BODY_BYTES);

        assertTrue(new FeishuClient(properties(2000, "")).sendFirst(candidates).success());
        String sent = BODIES.get(0);
        assertTrue(sent.getBytes(StandardCharsets.UTF_8).length <= FeishuClient.MAX_BODY_BYTES);
        assertTrue(JSON.readTree(sent).get("content").get("text").asText()
                .contains("共 400 个箱号"));
    }

    @Test
    void refusesToSendWhenEvenTheTrimmedBodyIsTooLarge() {
        BODIES.clear();
        MODE.set("ok");
        String hugeTitle = "T".repeat(FeishuClient.MAX_BODY_BYTES + 100);
        FeishuClient.FeishuSendResult result = new FeishuClient(properties(2000, ""))
                .sendFirst(List.of(hugeTitle));

        assertFalse(result.success());
        assertTrue(result.tooLarge());
        assertTrue(BODIES.isEmpty());
    }

    private static FeishuProperties properties(long timeoutMs, String secret) {
        FeishuProperties properties = new FeishuProperties();
        properties.setWebhookUrl("http://127.0.0.1:" + port + "/hook");
        properties.setWebhookSecret(secret);
        properties.setRequestTimeoutMs(timeoutMs);
        properties.setConnectTimeoutMs(1000);
        return properties;
    }
}
