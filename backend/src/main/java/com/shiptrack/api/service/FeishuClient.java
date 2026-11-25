package com.shiptrack.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shiptrack.api.config.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The only place that talks to the Feishu custom-bot webhook (§7.5).
 *
 * <p>Success is HTTP 2xx <em>and</em> {@code code == 0} in the response body.
 * The URL and secret are never logged; error text carries only HTTP/code
 * details. The request body is capped at 20 KB.</p>
 */
@Component
public class FeishuClient {

    public static final int MAX_BODY_BYTES = 20 * 1024;

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    private final FeishuProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public FeishuClient(FeishuProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * Sends the first candidate that fits §7.5's 20 KB request limit. Candidates
     * arrive in the fixed trimming order, so identical input yields identical
     * output.
     */
    public FeishuSendResult sendFirst(List<String> candidates) {
        if (!isConfigured()) {
            return FeishuSendResult.notConfigured();
        }
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String body = null;
        for (String text : candidates) {
            String candidate = buildBody(text, timestamp);
            if (candidate != null && candidate.getBytes(StandardCharsets.UTF_8).length <= MAX_BODY_BYTES) {
                body = candidate;
                break;
            }
        }
        if (body == null) {
            return FeishuSendResult.overLimit();
        }
        return post(body);
    }

    /** Exposed for tests: the exact JSON sent to Feishu. Null when serialisation fails. */
    String buildBody(String text, String timestamp) {
        ObjectNode root = mapper.createObjectNode();
        root.put("msg_type", "text");
        root.putObject("content").put("text", text);
        if (properties.hasSecret()) {
            root.put("timestamp", timestamp);
            root.put("sign", FeishuSigner.sign(timestamp, properties.getWebhookSecret()));
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            log.error("cannot serialise Feishu request body", error);
            return null;
        }
    }

    private FeishuSendResult post(String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getWebhookUrl()))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return interpret(response.statusCode(), response.body());
        } catch (HttpTimeoutException error) {
            return FeishuSendResult.failed(null, "请求超时");
        } catch (IOException error) {
            return FeishuSendResult.failed(null, "网络错误：" + error.getClass().getSimpleName());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return FeishuSendResult.failed(null, "发送被中断");
        }
    }

    private FeishuSendResult interpret(int httpStatus, String responseBody) {
        Integer code = null;
        String message = null;
        String messageId = null;
        try {
            JsonNode node = mapper.readTree(responseBody == null ? "" : responseBody);
            if (node != null && !node.isMissingNode()) {
                if (node.has("code")) {
                    code = node.get("code").asInt();
                } else if (node.has("StatusCode")) {
                    code = node.get("StatusCode").asInt();
                }
                message = node.path("msg").asText(null);
                messageId = node.path("data").path("message_id").asText(null);
            }
        } catch (JsonProcessingException ignored) {
            // A non-JSON body still fails below; the HTTP status is kept as the code.
        }
        boolean ok = httpStatus / 100 == 2 && code != null && code == 0;
        if (ok) {
            return FeishuSendResult.ok(code, messageId);
        }
        Integer recorded = code != null ? code : httpStatus;
        StringBuilder error = new StringBuilder("HTTP ").append(httpStatus);
        if (code != null) {
            error.append(" code ").append(code);
        }
        if (message != null && !message.isBlank()) {
            error.append(' ').append(message);
        }
        if (code == null) {
            error.append("（响应缺少 code）");
        }
        return FeishuSendResult.failed(recorded, error.toString());
    }

    /** Outcome of one delivery attempt. Never carries the URL or secret. */
    public record FeishuSendResult(boolean success, boolean skipped, boolean tooLarge,
                                   Integer responseCode, String messageId, String error) {

        static FeishuSendResult ok(Integer responseCode, String messageId) {
            return new FeishuSendResult(true, false, false, responseCode, messageId, null);
        }

        static FeishuSendResult failed(Integer responseCode, String error) {
            return new FeishuSendResult(false, false, false, responseCode, null, error);
        }

        static FeishuSendResult notConfigured() {
            return new FeishuSendResult(false, true, false, null, null, "Webhook 未配置");
        }

        static FeishuSendResult overLimit() {
            return new FeishuSendResult(false, false, true, null, null,
                    "请求体超过 20 KB，已裁剪仍超限");
        }
    }
}
