package com.shiptrack.collector.api;

import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TransportStatus;
import com.shiptrack.contract.internal.TrackingResultReport;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The report path uses a raw TLS socket, so it must perform the HTTPS endpoint
 * identity check itself. The fixture certificate is trusted and valid only for
 * {@code localhost}; a report addressed to {@code 127.0.0.1} must fail the
 * handshake and never reach the handler.
 */
class InternalApiClientTlsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private HttpsServer server;
    private final AtomicInteger reports = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        KeyStore keyStore = load("tls/server.p12");
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/internal/tracking-results", exchange -> {
            reports.incrementAndGet();
            byte[] body = """
                    {"snapshot_id":5003,"attempt_status":"success","events":[],
                     "order_notification":{"state":"not_applicable",
                       "notification_id":null,"status":null}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void acceptsATrustedCertificateForTheConfiguredHostname() throws Exception {
        assertThat(client("localhost").report(sampleReport(), 3_000).snapshotId()).isEqualTo(5003);
        assertThat(reports.get()).isEqualTo(1);
    }

    @Test
    void refusesATrustedCertificateForAnotherHostname() throws Exception {
        assertThatThrownBy(() -> client("127.0.0.1").report(sampleReport(), 3_000))
                .isInstanceOf(RuntimeException.class);

        assertThat(reports)
                .as("a hostname mismatch must fail before the request body is sent")
                .hasValue(0);
    }

    private InternalApiClient client(String host) throws Exception {
        KeyStore trustStore = load("tls/trust.p12");
        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);

        CollectorProperties properties = new CollectorProperties();
        properties.setBaseUrl("https://" + host + ":" + server.getAddress().getPort());
        properties.setInternalToken("secret-token");
        // The certificate covers DNS:localhost only; keep the socket pointed at
        // the local server regardless of which hostname the URL asks to verify.
        return new InternalApiClient(properties, context.getSocketFactory(),
                ignored -> InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
    }

    private static KeyStore load(String resource) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = InternalApiClientTlsTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            store.load(in, PASSWORD);
        }
        return store;
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
}
