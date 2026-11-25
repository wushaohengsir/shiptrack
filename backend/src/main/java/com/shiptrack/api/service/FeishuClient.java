package com.shiptrack.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shiptrack.api.config.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /**
     * Strict parsing for the response body: exact decimals (a value like
     * {@code 1e-400} must stay non-zero instead of underflowing to 0.0) and no
     * trailing content after the JSON document ({@code {"code":0}garbage} is
     * not a success).
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
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
        // §7.5: the request timeout only covers the response head on some JDKs.
        // A dedicated deadline around the whole future also bounds the body read,
        // and an expired attempt is cancelled so it cannot keep a dispatcher busy.
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = future.get(properties.getRequestTimeoutMs(),
                    TimeUnit.MILLISECONDS);
            return interpret(response.statusCode(), response.body());
        } catch (TimeoutException error) {
            future.cancel(true);
            return FeishuSendResult.failed(null, "请求超时");
        } catch (ExecutionException error) {
            future.cancel(true);
            Throwable cause = error.getCause();
            if (cause instanceof HttpTimeoutException) {
                return FeishuSendResult.failed(null, "请求超时");
            }
            String type = cause == null ? "未知" : cause.getClass().getSimpleName();
            return FeishuSendResult.failed(null, "网络错误：" + type);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return FeishuSendResult.failed(null, "发送被中断");
        }
    }

    /**
     * §7.5 success rule: HTTP 2xx <b>and</b> a JSON numeric {@code code == 0}.
     * Missing, null, blank, string-typed or {@code StatusCode}-only responses
     * are failures, as are bodies with trailing content after the JSON document
     * and decimals that are not exactly zero. The upstream {@code msg} is
     * deliberately ignored: error text is a fixed local description of the HTTP
     * status and numeric code.
     */
    private FeishuSendResult interpret(int httpStatus, String responseBody) {
        boolean httpOk = httpStatus / 100 == 2;
        JsonNode node = null;
        try {
            node = mapper.readTree(responseBody == null ? "" : responseBody);
        } catch (JsonProcessingException ignored) {
            // A non-JSON body still fails below; the HTTP status is kept as the code.
        }
        JsonNode codeNode = node == null || node.isMissingNode() ? null : node.get("code");
        boolean numeric = codeNode != null && codeNode.isNumber();
        boolean zero = numeric && codeNode.decimalValue().compareTo(BigDecimal.ZERO) == 0;
        String codeText = numeric ? codeNode.asText() : null;
        String messageId = node == null ? null
                : textOrNull(node.path("data").path("message_id"));

        if (httpOk && zero) {
            return FeishuSendResult.ok(0, messageId);
        }

        StringBuilder error = new StringBuilder("HTTP ").append(httpStatus);
        if (codeText != null) {
            error.append(" code ").append(codeText);
        }
        if (!httpOk) {
            error.append("（非 2xx 响应）");
        } else if (!numeric) {
            error.append("（响应缺少数值型 code）");
        } else {
            error.append("（code 非 0）");
        }
        return FeishuSendResult.failed(recordedCode(httpStatus, numeric, codeNode),
                error.toString());
    }

    /**
     * Records the exact code only when it is an integer that fits an int; a
     * fractional or out-of-range code keeps the HTTP status instead of being
     * silently truncated (e.g. {@code 1e-400} must not be stored as 0).
     */
    private static Integer recordedCode(int httpStatus, boolean numeric, JsonNode codeNode) {
        if (!numeric) {
            return httpStatus;
        }
        try {
            return codeNode.decimalValue().intValueExact();
        } catch (ArithmeticException error) {
            return httpStatus;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
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
