package com.shiptrack.collector.msc;

import com.shiptrack.collector.browser.BrowserProperties;
import com.shiptrack.collector.browser.BrowserTestSupport;
import com.shiptrack.collector.browser.PlaywrightBrowserSessionFactory;
import com.shiptrack.contract.internal.AgentErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the real probe against local pages so HTTP status, interstitials and
 * selector drift are proven without touching the carrier site.
 * Gated on {@code COLLECTOR_BROWSER_TEST=true}.
 */
class MscProbeBrowserTest {

    private static final String OK_PAGE = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              document.getElementById('status').textContent='Status: Loaded on vessel '+c;
              document.getElementById('result').style.display='block';return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result" style="display:none">
              <div id="status"></div>
              <div class="event">Gate in - 2026-11-21 16:20 CET</div>
            </div>
            </body></html>
            """;

    private static final String CHANGED_PAGE = """
            <html><body>
            <form onsubmit="document.getElementById('changed').style.display='block';return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="changed" style="display:none">
              Tracking MSDU7172233 is shown by a new layout with no status block.
            </div>
            </body></html>
            """;

    private static final String SUBMIT_FORM = """
            <html><body>
            <form onsubmit="%s;return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            </body></html>
            """;

    private static final String LATE_CAPTCHA_PAGE = SUBMIT_FORM.formatted(
            "setTimeout(function(){var d=document.createElement('div');"
                    + "d.textContent='Please verify you are human';"
                    + "document.body.appendChild(d);},300)");

    private static final String LATE_LOGIN_PAGE = SUBMIT_FORM.formatted(
            "setTimeout(function(){var i=document.createElement('input');"
                    + "i.type='password';document.body.appendChild(i);},300)");

    private static final String SLOW_RESULT_PAGE = """
            <html><body>
            <form onsubmit="setTimeout(function(){
              document.getElementById('status').textContent='Status: Loaded on vessel';
              document.getElementById('result').style.display='block';},5000);return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result" style="display:none"><div id="status"></div></div>
            </body></html>
            """;

    /** A background 200 arrives before the real tracking request answers 429. */
    private static final String BACKGROUND_200_THEN_TRACK_429 = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              fetch('/config');
              fetch('/track-429?container='+c);return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            </body></html>
            """;

    /** A background 5xx must not beat the valid tracking result. */
    private static final String BACKGROUND_500_THEN_RESULT = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              fetch('/banner-500');
              document.getElementById('status').textContent='Status: Loaded on vessel '+c;
              document.getElementById('result').style.display='block';return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result" style="display:none"><div id="status"></div></div>
            </body></html>
            """;

    /** A visible block for another container that was never produced by this query. */
    private static final String STALE_OTHER_RESULT = """
            <html><body>
            <form onsubmit="return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MAEU1234567</div>
              <div>Status: Container picked up</div>
            </div>
            </body></html>
            """;

    private HttpServer server;
    private PlaywrightBrowserSessionFactory factory;
    private MscProperties properties;
    private MscProbe probe;

    @BeforeEach
    void start() throws IOException {
        assumeTrue(BrowserTestSupport.enabled(), "set COLLECTOR_BROWSER_TEST=true to run browser tests");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();

        properties = new MscProperties();
        properties.setBaseUrl(baseUrl("/ok"));
        properties.setContainerInputSelector("#container");
        properties.setSubmitSelector("#track");
        properties.setResultSelector("#result");
        properties.setStatusSelector("#status");
        properties.setEventRowSelector(".event");
        properties.setQueryTimeoutMs(3_000);
        properties.setFormWaitMs(2_000);
        properties.setResultWaitMs(2_000);
        properties.setMinQueryIntervalMs(0);
        properties.setQueryJitterMs(0);
        properties.setRateLimitBackoffMs(0);

        factory = new PlaywrightBrowserSessionFactory(new BrowserProperties());
        probe = new MscProbe(factory, properties, new MscResultParser(),
                new MscErrorClassifier(properties),
                new QueryCircuitBreaker(100, Duration.ofSeconds(1), Clock.systemUTC()));
    }

    @AfterEach
    void stop() {
        if (factory != null) {
            factory.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsANormalTrackingPage() {
        TrackingOutcome outcome = probe.query("MSDU7172233");

        assertThat(outcome).isInstanceOf(TrackingOutcome.Success.class);
        ParsedTracking parsed = ((TrackingOutcome.Success) outcome).parsed();
        assertThat(parsed.rawStatus()).contains("Loaded on vessel");
        assertThat(parsed.events()).hasSize(1);
        assertThat(parsed.events().getFirst().occurredAt()).isNotNull();
    }

    @Test
    void classifiesHttp429AsRateLimited() {
        properties.setBaseUrl(baseUrl("/429"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.RATE_LIMITED);
    }

    @Test
    void classifiesHttp5xxAsSourceUnavailable() {
        properties.setBaseUrl(baseUrl("/500"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.SOURCE_UNAVAILABLE);
    }

    @Test
    void classifiesACaptchaPage() {
        properties.setBaseUrl(baseUrl("/captcha"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.CAPTCHA_REQUIRED);
    }

    @Test
    void classifiesAForcedLogin() {
        properties.setBaseUrl(baseUrl("/login"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.LOGIN_REQUIRED);
    }

    @Test
    void reportsAChangedPageAsParseErrorNotTimeout() {
        properties.setBaseUrl(baseUrl("/changed"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.PARSE_ERROR);
    }

    @Test
    void reportsASlowPageAsTimeout() {
        properties.setQueryTimeoutMs(1_000);
        properties.setBaseUrl(baseUrl("/slow"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.TIMEOUT);
    }

    @Test
    void classifiesATrackingRequest429EvenWhenThePageShowsNothing() {
        properties.setBaseUrl(baseUrl("/submit-429"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.RATE_LIMITED);
    }

    @Test
    void classifiesATrackingRequest5xxEvenWhenThePageShowsNothing() {
        properties.setBaseUrl(baseUrl("/submit-500"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.SOURCE_UNAVAILABLE);
    }

    @Test
    void classifiesACaptchaThatOnlyAppearsAfterSubmit() {
        properties.setBaseUrl(baseUrl("/late-captcha"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.CAPTCHA_REQUIRED);
    }

    @Test
    void classifiesALoginThatOnlyAppearsAfterSubmit() {
        properties.setBaseUrl(baseUrl("/late-login"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.LOGIN_REQUIRED);
    }

    @Test
    void reportsAResultThatNeverRendersAsTimeout() {
        properties.setQueryTimeoutMs(1_000);
        properties.setResultWaitMs(10_000);
        properties.setBaseUrl(baseUrl("/slow-result"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.TIMEOUT);
    }

    @Test
    void classifiesTheTracking429EvenWhenABackground200ArrivedFirst() {
        properties.setBaseUrl(baseUrl("/background-429"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.RATE_LIMITED);
    }

    @Test
    void keepsAValidResultEvenWhenABackgroundRequestFailed() {
        properties.setBaseUrl(baseUrl("/background-500"));
        assertThat(probe.query("MSDU7172233")).isInstanceOf(TrackingOutcome.Success.class);
    }

    @Test
    void neverAdoptsAStaleResultForAnotherContainer() {
        properties.setQueryTimeoutMs(1_000);
        properties.setResultWaitMs(500);
        properties.setBaseUrl(baseUrl("/stale-result"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.TIMEOUT);
    }

    private static AgentErrorCode failure(TrackingOutcome outcome) {
        assertThat(outcome).isInstanceOf(TrackingOutcome.Failure.class);
        return ((TrackingOutcome.Failure) outcome).errorCode();
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestURI().getPath()) {
            case "/ok" -> respond(exchange, 200, OK_PAGE);
            case "/changed" -> respond(exchange, 200, CHANGED_PAGE);
            case "/captcha" -> respond(exchange, 200,
                    "<html><body><h1>Please verify you are human</h1></body></html>");
            case "/login" -> respond(exchange, 200,
                    "<html><body><form><input type=\"password\"></form></body></html>");
            case "/429" -> respond(exchange, 429, "too many requests");
            case "/500" -> respond(exchange, 500, "server error");
            case "/submit-429" -> respond(exchange, 200, SUBMIT_FORM.formatted("fetch('/track-429')"));
            case "/submit-500" -> respond(exchange, 200, SUBMIT_FORM.formatted("fetch('/track-500')"));
            case "/background-429" -> respond(exchange, 200, BACKGROUND_200_THEN_TRACK_429);
            case "/background-500" -> respond(exchange, 200, BACKGROUND_500_THEN_RESULT);
            case "/stale-result" -> respond(exchange, 200, STALE_OTHER_RESULT);
            case "/config" -> respond(exchange, 200, "{\"ok\":true}");
            case "/banner-500" -> respond(exchange, 500, "server error");
            case "/track-429" -> respond(exchange, 429, "too many requests");
            case "/track-500" -> respond(exchange, 500, "server error");
            case "/late-captcha" -> respond(exchange, 200, LATE_CAPTCHA_PAGE);
            case "/late-login" -> respond(exchange, 200, LATE_LOGIN_PAGE);
            case "/slow-result" -> respond(exchange, 200, SLOW_RESULT_PAGE);
            case "/slow" -> {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, OK_PAGE);
            }
            default -> respond(exchange, 404, "not found");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
