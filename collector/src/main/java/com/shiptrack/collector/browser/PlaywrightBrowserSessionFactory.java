package com.shiptrack.collector.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Keeps one Chromium process for the collector lifetime and opens an isolated
 * context per query. Contexts are cheap and dispose their cookies, which keeps
 * one container query from leaking state into the next.
 */
@Component
public class PlaywrightBrowserSessionFactory implements BrowserSessionFactory {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserSessionFactory.class);

    /**
     * The site's scripts check for the most common automation marker. The flag
     * and the init script only restore the normal value a regular Chrome reports.
     */
    private static final List<String> LAUNCH_ARGS = List.of(
            "--disable-blink-features=AutomationControlled",
            "--disable-dev-shm-usage");

    private static final String INIT_SCRIPT =
            "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });";

    private final BrowserProperties properties;
    private final ResourceBlockPolicy blockPolicy;

    private Playwright playwright;
    private Browser browser;

    public PlaywrightBrowserSessionFactory(BrowserProperties properties) {
        this.properties = properties;
        this.blockPolicy = new ResourceBlockPolicy(
                properties.getBlockedResourceTypes(), properties.getBlockedHosts());
    }

    @Override
    public synchronized BrowserSession open() {
        ensureBrowser();
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(properties.getUserAgent())
                .setViewportSize(properties.getViewportWidth(), properties.getViewportHeight())
                .setLocale(properties.getLocale())
                .setTimezoneId(properties.getTimezone())
                .setExtraHTTPHeaders(Map.of("Accept-Language", properties.getLocale() + ",en;q=0.8")));
        try {
            context.addInitScript(INIT_SCRIPT);
            context.route("**/*", route -> {
                if (blockPolicy.isBlocked(route.request().resourceType(), route.request().url())) {
                    route.abort();
                } else {
                    route.resume();
                }
            });
            Page page = context.newPage();
            page.setDefaultTimeout(properties.getActionTimeoutMs());
            page.setDefaultNavigationTimeout(properties.getNavigationTimeoutMs());
            return new Session(context);
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private void ensureBrowser() {
        if (browser != null) {
            return;
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(properties.isHeadless())
                .setArgs(LAUNCH_ARGS));
        log.info("Chromium launched (headless={}, version={})",
                properties.isHeadless(), browser.version());
    }

    @Override
    @jakarta.annotation.PreDestroy
    public synchronized void close() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    private record Session(BrowserContext context) implements BrowserSession {

        @Override
        public Page page() {
            return context.pages().getFirst();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
