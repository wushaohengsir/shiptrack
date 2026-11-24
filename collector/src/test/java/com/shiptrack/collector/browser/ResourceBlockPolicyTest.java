package com.shiptrack.collector.browser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceBlockPolicyTest {

    private final ResourceBlockPolicy policy = new ResourceBlockPolicy(
            List.of("image", "media", "font"),
            List.of("Google-Analytics.com", "doubleclick.net"));

    @Test
    void blocksConfiguredResourceTypesCaseInsensitively() {
        assertThat(policy.isBlocked("image", "https://www.msc.com/hero.png")).isTrue();
        assertThat(policy.isBlocked("FONT", "https://www.msc.com/a.woff2")).isTrue();
        assertThat(policy.isBlocked("media", null)).isTrue();
    }

    @Test
    void blocksThirdPartyHostFragments() {
        assertThat(policy.isBlocked("script", "https://www.google-analytics.com/collect")).isTrue();
        assertThat(policy.isBlocked("script", "https://stats.doubleclick.net/x.js")).isTrue();
    }

    @Test
    void keepsDocumentScriptsStylesheetsAndSiteHosts() {
        assertThat(policy.isBlocked("document", "https://www.msc.com/en/track-a-shipment")).isFalse();
        assertThat(policy.isBlocked("script", "https://www.msc.com/app.js")).isFalse();
        assertThat(policy.isBlocked("stylesheet", "https://www.msc.com/site.css")).isFalse();
        assertThat(policy.isBlocked(null, null)).isFalse();
    }
}
