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

    /**
     * The visible status says the box is loaded, while an earlier hidden node
     * inside the same block claims another box was picked up.
     */
    private static final String HIDDEN_CONFLICTING_STATUS = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              document.getElementById('status').textContent='Status: Loaded on vessel '+c;
              document.getElementById('result').style.display='block';return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result" style="display:none">
              <div class="hidden-status" style="display:none">
                Status: Container picked up MAEU1234567
              </div>
              <div id="status"></div>
              <div class="event">Gate in - 2026-11-21 16:20 CET</div>
            </div>
            </body></html>
            """;

    /** The same container's old result is already on the page and submitting changes nothing. */
    private static final String STALE_SAME_RESULT = """
            <html><body>
            <form onsubmit="return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MSDU7172233</div>
              <div id="status">Status: Container picked up</div>
            </div>
            </body></html>
            """;

    /** The same container's old result is on the page, but submitting really refreshes it. */
    private static final String FRESH_SAME_RESULT = """
            <html><body>
            <form onsubmit="document.getElementById('status').textContent='Status: Loaded on vessel';
              document.getElementById('event').textContent='Loaded on vessel - 2026-11-22 08:00 UTC';
              return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MSDU7172233</div>
              <div id="status">Status: Container in transit</div>
              <div class="event" id="event"></div>
            </div>
            </body></html>
            """;

    /** A same-container analytics beacon answers 200 while the old result stays. */
    private static final String BEACON_200_WITH_STALE_RESULT = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              fetch('/beacon?container='+c);return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MSDU7172233</div>
              <div id="status">Status: Container picked up</div>
            </div>
            </body></html>
            """;

    /** Only an unrelated clock label inside the result block changes after submit. */
    private static final String TIME_ONLY_UPDATE = """
            <html><body>
            <form onsubmit="document.getElementById('updated').textContent='Updated 10:00:01';return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MSDU7172233</div>
              <div id="status">Status: Container picked up</div>
              <div id="updated">Updated 10:00:00</div>
            </div>
            </body></html>
            """;

    /** The tracking call answers 200 at once; the real render comes 700 ms later. */
    private static final String TRACK_200_BEFORE_RENDER = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              fetch('/track-200?container='+c);
              setTimeout(function(){
                document.getElementById('status').textContent='Status: Loaded on vessel';},700);
              return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MSDU7172233</div>
              <div id="status">Status: Container picked up</div>
            </div>
            </body></html>
            """;

    /**
     * The staged render from the sixth review: the tracking call answers 200,
     * the page first drops an old timeline row, and only 900 ms later changes
     * the status. Deleting a row must not be mistaken for a fresh result, or
     * the probe would publish the old "picked up" status.
     */
    private static final String STAGED_RENDER_DROPS_ROW_FIRST = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              fetch('/track-200?container='+c);
              document.getElementById('old-row').remove();
              setTimeout(function(){
                document.getElementById('status').textContent='Status: Loaded on vessel';},900);
              return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MSDU7172233</div>
              <div id="status">Status: Container picked up</div>
              <div class="event" id="old-row">Gate in - 2026-11-21 16:20 CET</div>
              <div class="event">Loaded on vessel - 2026-11-22 08:00 UTC</div>
            </div>
            </body></html>
            """;

    /**
     * Two pre-existing blocks for the same container. Submitting only swaps
     * their visibility; no request is made and no content changes. The hidden
     * "picked up" block is revealed, which must not count as a fresh result.
     */
    private static final String STAGED_REVEAL_SECOND_BLOCK = """
            <html><body>
            <form onsubmit="document.getElementById('block-a').style.display='none';
              document.getElementById('block-b').style.display='block';return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div class="card" id="block-a">
                <div>Container MSDU7172233</div>
                <div class="status">Status: Loaded on vessel</div>
              </div>
              <div class="card" id="block-b" style="display:none">
                <div>Container MSDU7172233</div>
                <div class="status">Status: Container picked up</div>
              </div>
            </div>
            </body></html>
            """;

    /** A real render replaces the result nodes but leaves the status unchanged. */
    private static final String REAL_RENDER_SAME_STATUS = """
            <html><body>
            <form onsubmit="var r=document.getElementById('result');
              var a=document.createElement('div'); a.textContent='Container MSDU7172233';
              var b=document.createElement('div'); b.id='status';
              b.textContent='Status: Container picked up';
              var u=document.createElement('div'); u.textContent='Updated 10:00:01';
              r.replaceChildren(a,b,u);return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
            <div id="result">
              <div>Container MSDU7172233</div>
              <div id="status">Status: Container picked up</div>
              <div id="updated">Updated 10:00:00</div>
            </div>
            </body></html>
            """;

    /**
     * The calibrated live card structure ({@code msc-flow-tracking__*}); the
     * card is created on submit, as on the real page, so it is a genuine
     * render of this query.
     */
    private static final String LIVE_STRUCTURE_PAGE = """
            <html><body>
            <form onsubmit="var t=document.getElementById('live-template');
              document.getElementById('live-result').appendChild(t.content.cloneNode(true));return false;">
              <input placeholder="Container number" name="trackingNumber">
              <button type="submit">Track</button>
            </form>
            <div id="live-result"></div>
            <template id="live-template">
              <div class="msc-flow-tracking__container">
                <div class="msc-flow-tracking__bar open">
                  <div class="msc-flow-tracking__content">
                    <div class="msc-flow-tracking__cell msc-flow-tracking__cell--one">
                      <div class="msc-flow-tracking__data">
                        <span class="data-heading">Container</span>
                        <span class="data-value">MSDU7172233</span>
                      </div>
                    </div>
                    <div class="msc-flow-tracking__cell msc-flow-tracking__cell--two">
                      <div class="msc-flow-tracking__data">
                        <span class="data-heading">Type</span>
                        <span class="data-value">40' HIGH CUBE</span>
                      </div>
                    </div>
                    <div class="msc-flow-tracking__cell msc-flow-tracking__cell--three">
                      <div class="msc-flow-tracking__data">
                        <span class="data-heading">Latest move</span>
                        <span class="data-value">BUSAN, KR</span>
                      </div>
                    </div>
                    <div class="msc-flow-tracking__cell msc-flow-tracking__cell--four">
                      <div class="msc-flow-tracking__data">
                        <span class="data-heading">POD ETA</span>
                        <span class="data-value">07/12/2026</span>
                      </div>
                    </div>
                  </div>
                </div>
                <div class="msc-flow-tracking__tracking">
                  <div class="msc-flow-tracking__steps">
                    <div class="msc-flow-tracking__port"><div class="msc-flow-tracking__step">
                      <span class="data-value">07/12/2026</span>
                      <span class="data-value">Vancouver,  CA</span>
                      <span class="data-value">Estimated Time of Arrival</span>
                      <span class="data-value">CAPE SOUNIO UK647R</span>
                      <span class="data-value">Deltaport</span>
                    </div></div>
                    <div class="msc-flow-tracking__port"><div class="msc-flow-tracking__step">
                      <span class="data-value">21/11/2026</span>
                      <span class="data-value">Busan,  KR</span>
                      <span class="data-value">Full Transshipment Loaded</span>
                      <span class="data-value">CAPE SOUNIO UK643A</span>
                      <span class="data-value">Hmm psa new-port terminal (hpnt)</span>
                    </div></div>
                    <div class="msc-flow-tracking__port"><div class="msc-flow-tracking__step">
                      <span class="data-value">06/11/2026</span>
                      <span class="data-value">Ningbo,  CN</span>
                      <span class="data-value">Export Loaded on Vessel</span>
                      <span class="data-value">MSC SOFIA GO643N</span>
                      <span class="data-value">Gangji terminal (phase iv)</span>
                    </div></div>
                  </div>
                </div>
              </div>
            </template>
            </body></html>
            """;

    /** The tracking request answers 302 to a final 429 that no longer carries the box number. */
    private static final String REDIRECTED_TRACK_429 = """
            <html><body>
            <form onsubmit="var c=document.getElementById('container').value;
              fetch('/config');
              fetch('/track-redirect?container='+c);return false;">
              <input id="container" name="container">
              <button id="track" type="submit">Track</button>
            </form>
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
    void readsTheCalibratedLiveCardWithTheDefaultSelectors() {
        MscProperties live = new MscProperties();
        live.setBaseUrl(baseUrl("/live-structure"));
        live.setQueryTimeoutMs(5_000);
        live.setFormWaitMs(3_000);
        live.setResultWaitMs(3_000);
        live.setMinQueryIntervalMs(0);
        live.setQueryJitterMs(0);
        MscProbe liveProbe = new MscProbe(factory, live, new MscResultParser(live),
                new MscErrorClassifier(live),
                new QueryCircuitBreaker(100, Duration.ofSeconds(1), Clock.systemUTC()));

        TrackingOutcome outcome = liveProbe.query("MSDU7172233");

        assertThat(outcome).isInstanceOf(TrackingOutcome.Success.class);
        ParsedTracking parsed = ((TrackingOutcome.Success) outcome).parsed();
        assertThat(parsed.rawStatus()).isEqualTo("Full Transshipment Loaded");
        assertThat(parsed.pol()).isEqualTo("Ningbo, CN");
        assertThat(parsed.pod()).isEqualTo("Vancouver, CA");
        assertThat(parsed.location()).isEqualTo("BUSAN, KR");
        assertThat(parsed.etas()).singleElement().satisfies(eta -> {
            assertThat(eta.milestone()).isEqualTo(
                    com.shiptrack.contract.enums.TrackedMilestone.POD_ARRIVAL);
            assertThat(eta.date()).isEqualTo(java.time.LocalDate.of(2026, 12, 7));
        });
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

    @Test
    void ignoresAHiddenConflictingStatusInsideTheResultBlock() {
        properties.setStatusSelector("#status, .hidden-status");
        properties.setBaseUrl(baseUrl("/hidden-status"));
        TrackingOutcome outcome = probe.query("MSDU7172233");
        assertThat(outcome).isInstanceOf(TrackingOutcome.Success.class);
        assertThat(((TrackingOutcome.Success) outcome).parsed().rawStatus())
                .contains("Loaded on vessel");
    }

    @Test
    void neverAdoptsTheSameContainersPreSubmitResult() {
        properties.setQueryTimeoutMs(1_500);
        properties.setResultWaitMs(300);
        properties.setBaseUrl(baseUrl("/stale-same-result"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.TIMEOUT);
    }

    @Test
    void adoptsTheSameContainersResultOnceThePageReallyRefreshesIt() {
        properties.setBaseUrl(baseUrl("/fresh-same-result"));
        TrackingOutcome outcome = probe.query("MSDU7172233");
        assertThat(outcome).isInstanceOf(TrackingOutcome.Success.class);
        assertThat(((TrackingOutcome.Success) outcome).parsed().rawStatus())
                .contains("Loaded on vessel");
    }

    @Test
    void neverAdoptsAStaleResultWhenOnlyASameContainerBeaconSucceeds() {
        properties.setQueryTimeoutMs(1_500);
        properties.setResultWaitMs(300);
        properties.setBaseUrl(baseUrl("/beacon-stale"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.TIMEOUT);
    }

    @Test
    void neverTreatsAnUnrelatedTextChangeAsARefresh() {
        properties.setQueryTimeoutMs(1_500);
        properties.setResultWaitMs(300);
        properties.setBaseUrl(baseUrl("/time-only"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.TIMEOUT);
    }

    @Test
    void waitsForTheRenderEvenWhenTheTrackingCallReturned200Early() {
        properties.setQueryTimeoutMs(3_000);
        properties.setResultWaitMs(3_000);
        properties.setBaseUrl(baseUrl("/track-200-before-render"));
        TrackingOutcome outcome = probe.query("MSDU7172233");

        assertThat(outcome).isInstanceOf(TrackingOutcome.Success.class);
        assertThat(((TrackingOutcome.Success) outcome).parsed().rawStatus())
                .contains("Loaded on vessel");
    }

    @Test
    void acceptsARealRenderThatLeavesTheStatusUnchanged() {
        properties.setBaseUrl(baseUrl("/real-render-same-status"));
        TrackingOutcome outcome = probe.query("MSDU7172233");

        assertThat(outcome).isInstanceOf(TrackingOutcome.Success.class);
        assertThat(((TrackingOutcome.Success) outcome).parsed().rawStatus())
                .contains("Container picked up");
    }

    @Test
    void doesNotPublishTheOldStatusWhenTheStagedRenderDropsARowFirst() {
        properties.setQueryTimeoutMs(4_000);
        properties.setResultWaitMs(3_000);
        properties.setBaseUrl(baseUrl("/staged-row"));
        TrackingOutcome outcome = probe.query("MSDU7172233");

        assertThat(outcome).isInstanceOf(TrackingOutcome.Success.class);
        assertThat(((TrackingOutcome.Success) outcome).parsed().rawStatus())
                .contains("Loaded on vessel")
                .doesNotContain("picked up");
    }

    @Test
    void neverAdoptsAPreExistingBlockThatSubmitOnlyRevealed() {
        properties.setQueryTimeoutMs(1_500);
        properties.setResultWaitMs(700);
        properties.setResultSelector(".card");
        properties.setStatusSelector(".status");
        properties.setBaseUrl(baseUrl("/staged-reveal"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.TIMEOUT);
    }

    @Test
    void followsTheTrackingRedirectToItsFinal429() {
        properties.setBaseUrl(baseUrl("/redirect-429"));
        assertThat(failure(probe.query("MSDU7172233"))).isEqualTo(AgentErrorCode.RATE_LIMITED);
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
            case "/live-structure" -> respond(exchange, 200, LIVE_STRUCTURE_PAGE);
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
            case "/hidden-status" -> respond(exchange, 200, HIDDEN_CONFLICTING_STATUS);
            case "/stale-same-result" -> respond(exchange, 200, STALE_SAME_RESULT);
            case "/fresh-same-result" -> respond(exchange, 200, FRESH_SAME_RESULT);
            case "/redirect-429" -> respond(exchange, 200, REDIRECTED_TRACK_429);
            case "/beacon-stale" -> respond(exchange, 200, BEACON_200_WITH_STALE_RESULT);
            case "/time-only" -> respond(exchange, 200, TIME_ONLY_UPDATE);
            case "/track-200-before-render" -> respond(exchange, 200, TRACK_200_BEFORE_RENDER);
            case "/real-render-same-status" -> respond(exchange, 200, REAL_RENDER_SAME_STATUS);
            case "/staged-row" -> respond(exchange, 200, STAGED_RENDER_DROPS_ROW_FIRST);
            case "/staged-reveal" -> respond(exchange, 200, STAGED_REVEAL_SECOND_BLOCK);
            case "/beacon" -> respond(exchange, 200, "{\"ok\":true}");
            case "/track-200" -> respond(exchange, 200, "{\"ok\":true}");
            case "/track-redirect" -> redirect(exchange, "/track-final");
            case "/track-final" -> respond(exchange, 429, "too many requests");
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

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
