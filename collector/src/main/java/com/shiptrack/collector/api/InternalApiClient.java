package com.shiptrack.collector.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shiptrack.contract.internal.TrackingResultReport;
import com.shiptrack.contract.internal.TrackingResultResponse;
import com.shiptrack.contract.internal.TrackingRunDto;
import com.shiptrack.contract.internal.TrackingRunsResponse;
import com.shiptrack.contract.internal.TrackingTaskClaimRequest;
import com.shiptrack.contract.internal.TrackingTaskClaimResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal client for the two internal endpoints. It holds no database
 * credentials and no business logic; every call carries the internal token.
 */
@Component
public class InternalApiClient implements CollectorApi {

    /** Upper bound on a report response, so a hostile server cannot exhaust memory. */
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_HEADER_LINE = 8_192;
    private static final int MAX_HEADER_COUNT = 100;

    private final RestClient.Builder builder;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final RestClient restClient;
    private final URI baseUri;
    private final String internalToken;
    private final ObjectMapper objectMapper;

    public InternalApiClient(CollectorProperties properties) {
        this.connectTimeoutMs = properties.getConnectTimeoutMs();
        this.readTimeoutMs = properties.getReadTimeoutMs();
        this.baseUri = URI.create(trimTrailingSlash(properties.getBaseUrl()));
        this.internalToken = properties.getInternalToken() == null ? "" : properties.getInternalToken();
        if (internalToken.indexOf('\r') >= 0 || internalToken.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("internal token must not contain line breaks");
        }
        this.objectMapper = defaultMapper();
        this.builder = RestClient.builder()
                .baseUrl(baseUri.toString())
                .defaultHeader("X-Internal-Token", internalToken)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        this.restClient = client(properties.getReadTimeoutMs());
    }

    /**
     * A report must not outlive the claim lease. {@code timeoutMs} is one
     * absolute budget for connecting, sending the body and reading the whole
     * response. It is enforced on the real transport: the request runs on a
     * plain socket whose close is scheduled at the deadline, and closing the
     * socket physically aborts a read that is still waiting on a dripping
     * server. Returning a timeout therefore leaves no request in flight that
     * could still write after a retry started.
     */
    @Override
    public TrackingResultResponse report(TrackingResultReport report, long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalStateException("no lease time left to report");
        }
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        int effectiveConnect = (int) Math.min(connectTimeoutMs, timeoutMs);
        int effectiveRead = (int) Math.min(readTimeoutMs, timeoutMs);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(report);
        } catch (JsonProcessingException e) {
            throw new RestClientException("could not serialise the report: " + e.getMessage(), e);
        }

        Socket socket = new Socket();
        ScheduledExecutorService watchdog = null;
        ScheduledFuture<?> abort = null;
        try {
            watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "report-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            // Close the socket when the absolute budget expires. Closing the
            // TCP connection is what stops a blocked read, unlike
            // HttpURLConnection.disconnect(), which lets an active read finish.
            Socket target = socket;
            abort = watchdog.schedule(() -> closeQuietly(target),
                    Math.max(0, remainingMs(deadlineNanos)), TimeUnit.MILLISECONDS);

            boolean tls = "https".equalsIgnoreCase(baseUri.getScheme());
            int port = baseUri.getPort() >= 0 ? baseUri.getPort() : (tls ? 443 : 80);
            socket.connect(new InetSocketAddress(baseUri.getHost(), port),
                    Math.max(1, effectiveConnect));
            socket.setSoTimeout(Math.max(1, effectiveRead));
            Socket channel = socket;
            if (tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, baseUri.getHost(), port, true);
                ssl.startHandshake();
                channel = ssl;
            }

            writeRequest(channel, port, tls, body);
            Reply reply = readReply(channel);
            if (reply.status() >= 400) {
                throw new RestClientResponseException("report returned HTTP " + reply.status(),
                        reply.status(), reply.statusText(), reply.headers(),
                        reply.body(), StandardCharsets.UTF_8);
            }
            if (reply.body().length == 0) {
                throw new RestClientException("report returned an empty response");
            }
            return objectMapper.readValue(reply.body(), TrackingResultResponse.class);
        } catch (RestClientException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new RestClientException("report did not finish within " + timeoutMs + "ms", e);
        } catch (IOException e) {
            if (remainingMs(deadlineNanos) <= 0) {
                throw new RestClientException("report did not finish within " + timeoutMs + "ms", e);
            }
            throw new RestClientException("report failed: " + e.getMessage(), e);
        } finally {
            if (abort != null) {
                abort.cancel(false);
            }
            if (watchdog != null) {
                watchdog.shutdownNow();
            }
            closeQuietly(socket);
        }
    }

    private void writeRequest(Socket channel, int port, boolean tls, byte[] body) throws IOException {
        boolean defaultPort = tls ? port == 443 : port == 80;
        String host = baseUri.getHost();
        if (host != null && host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        String hostHeader = defaultPort ? host : host + ":" + port;
        String path = baseUri.getRawPath() == null ? "" : baseUri.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        path = path + "/internal/tracking-results";

        StringBuilder head = new StringBuilder(256);
        head.append("POST ").append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(hostHeader).append("\r\n");
        head.append("Content-Type: ").append(MediaType.APPLICATION_JSON_VALUE).append("\r\n");
        head.append("Accept: ").append(MediaType.APPLICATION_JSON_VALUE).append("\r\n");
        head.append("X-Internal-Token: ").append(internalToken).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");

        OutputStream out = new BufferedOutputStream(channel.getOutputStream(), 8_192);
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static Reply readReply(Socket channel) throws IOException {
        InputStream in = new BufferedInputStream(channel.getInputStream(), 8_192);
        while (true) {
            String statusLine = readLine(in);
            int status = parseStatus(statusLine);
            Map<String, List<String>> headers = readHeaders(in);
            if (status >= 100 && status < 200) {
                // Interim response (100 Continue, 103 Early Hints); the real
                // one follows.
                continue;
            }
            HttpHeaders httpHeaders = new HttpHeaders();
            headers.forEach((name, values) -> httpHeaders.put(name, new ArrayList<>(values)));
            byte[] body = readBody(in, httpHeaders);
            return new Reply(status, reasonOf(statusLine), httpHeaders, body);
        }
    }

    private static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String line = readLine(in);
            if (line.isEmpty()) {
                return headers;
            }
            if (++count > MAX_HEADER_COUNT) {
                throw new IOException("too many response headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
    }

    private static byte[] readBody(InputStream in, HttpHeaders headers) throws IOException {
        String transferEncoding = headers.getFirst("Transfer-Encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in);
        }
        String contentLength = headers.getFirst("Content-Length");
        if (contentLength != null) {
            long length;
            try {
                length = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                throw new IOException("invalid Content-Length: " + contentLength, e);
            }
            if (length < 0 || length > MAX_RESPONSE_BYTES) {
                throw new IOException("unacceptable Content-Length: " + length);
            }
            return readExactly(in, (int) length);
        }
        return readToEnd(in);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) {
                sizeLine = sizeLine.substring(0, semicolon);
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("invalid chunk size: " + sizeLine, e);
            }
            if (size < 0 || out.size() + size > MAX_RESPONSE_BYTES) {
                throw new IOException("chunked response exceeds the size cap");
            }
            if (size == 0) {
                // Trailer headers end at the first empty line.
                while (!readLine(in).isEmpty()) {
                    // Discard trailers.
                }
                return out.toByteArray();
            }
            out.write(readExactly(in, size));
            readLine(in); // trailing CRLF
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("response ended after " + offset + " of " + length + " bytes");
            }
            offset += read;
        }
        return buffer;
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (out.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("response exceeds the size cap");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Reads one CRLF-terminated line as ISO-8859-1; never loops past the cap. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("connection closed while reading a response line");
            }
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (line.size() >= MAX_HEADER_LINE) {
                throw new IOException("response line exceeds the header limit");
            }
            line.write(b);
        }
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("malformed status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("malformed status code: " + statusLine, e);
        }
    }

    private static String reasonOf(String statusLine) {
        String[] parts = statusLine.split(" ", 3);
        return parts.length == 3 ? parts[2] : "";
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private static long remainingMs(long deadlineNanos) {
        return (deadlineNanos - System.nanoTime()) / 1_000_000L;
    }

    private RestClient client(long timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return builder.clone().requestFactory(requestFactory).build();
    }

    @Override
    public List<TrackingRunDto> discoverClaimableRuns(int limit) {
        TrackingRunsResponse response = restClient.get()
                .uri("/internal/tracking-runs?state=claimable&limit={limit}", limit)
                .retrieve()
                .body(TrackingRunsResponse.class);
        return response == null || response.runs() == null ? List.of() : response.runs();
    }

    @Override
    public TrackingTaskClaimResponse claimTasks(String runId, int limit) {
        TrackingTaskClaimResponse response = restClient.post()
                .uri("/internal/tracking-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new TrackingTaskClaimRequest(runId, limit))
                .retrieve()
                .body(TrackingTaskClaimResponse.class);
        if (response == null) {
            throw new IllegalStateException("empty claim response for run " + runId);
        }
        return response;
    }

    @Override
    public TrackingResultResponse report(TrackingResultReport report) {
        return report(report, readTimeoutMs);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8080";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record Reply(int status, String statusText, HttpHeaders headers, byte[] body) {
    }
}
