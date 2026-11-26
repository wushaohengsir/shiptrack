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
        return open(properties.getActionTimeoutMs());
    }

    @Override
    public synchronized BrowserSession open(long timeoutMs) {
        ensureBrowser(timeoutMs);
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(properties.getUserAgent())
                .setViewportSize(properties.getViewportWidth(), properties.getViewportHeight())
                .setLocale(properties.getLocale())
                .setTimezoneId(properties.getTimezone())
                .setExtraHTTPHeaders(Map.of("Accept-Language", properties.getLocale() + ",en;q=0.8")));
        try {
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

    private void ensureBrowser(long timeoutMs) {
        if (browser != null) {
            return;
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(launchOptions(timeoutMs));
        log.info("Chromium launched (headless={}, sandbox=true, version={})",
                properties.isHeadless(), browser.version());
    }

    /**
     * Launch configuration kept in one place so it can be asserted without a
     * browser: the sandbox stays on, and Playwright's own
     * {@code --disable-dev-shm-usage} default is filtered because the container
     * supplies {@code shm_size} instead.
     */
    BrowserType.LaunchOptions launchOptions() {
        return launchOptions(properties.getActionTimeoutMs());
    }

    /**
     * The launch timeout is capped by the caller's remaining query budget, so a
     * cold or hung Chromium start cannot consume more than the 30 s the query
     * is allowed.
     */
    BrowserType.LaunchOptions launchOptions(long timeoutMs) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(properties.isHeadless())
                .setChromiumSandbox(true)
                .setTimeout(timeoutMs <= 0 ? 1 : timeoutMs)
                .setIgnoreDefaultArgs(List.of("--disable-dev-shm-usage"));
        if (properties.isHeadless()) {
            // Playwright's default headless build is the stripped
            // chrome-headless-shell, which msc.com's Akamai rule answers with
            // "Access Denied". The "chromium" channel is the same full
            // Chromium the headed browser uses, in its new headless mode; no
            // stealth patch or fingerprint spoofing is involved.
            options.setChannel("chromium");
        }
        return options;
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
