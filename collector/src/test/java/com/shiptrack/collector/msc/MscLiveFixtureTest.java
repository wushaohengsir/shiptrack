package com.shiptrack.collector.msc;

import com.shiptrack.collector.mapping.StatusMappingSet;
import com.shiptrack.collector.mapping.StatusMappingsLoader;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TransportStatus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline regression against the live result card captured on 2026-11-25 for
 * MSDU7172233. The snapshot is built with the same calibrated selectors the
 * probe uses, so a selector or parser change that breaks the real page fails
 * here without a browser.
 */
class MscLiveFixtureTest {

    private static final String FIXTURE = "msc/msc_live_MSDU7172233_2026-11-25.html";
    private static final String CONTAINER = "MSDU7172233";

    private final MscResultParser parser = new MscResultParser();

    @Test
    void parsesTheRealLiveCardFields() throws IOException {
        ParsedTracking parsed = parser.parse(fixtureSnapshot(), CONTAINER);

        // No Status: label exists on the live card; the status is the newest
        // event that actually happened, never the planned arrival row.
        assertThat(parsed.rawStatus()).isEqualTo("Full Transshipment Loaded");
        assertThat(parsed.pol()).isEqualTo("Ningbo, CN");
        assertThat(parsed.pod()).isEqualTo("Vancouver, CA");
        assertThat(parsed.location()).isEqualTo("BUSAN, KR");

        assertThat(parsed.etas()).singleElement().satisfies(eta -> {
            assertThat(eta.milestone()).isEqualTo(TrackedMilestone.POD_ARRIVAL);
            assertThat(eta.date()).isEqualTo(LocalDate.of(2026, 12, 7));
        });

        // The transshipment point and the facility name survive in the timeline.
        assertThat(parsed.events()).hasSize(8);
        assertThat(parsed.events()).anySatisfy(event -> {
            assertThat(event.rawText()).contains("Busan, KR");
            assertThat(event.rawText()).contains("Full Transshipment Loaded");
            assertThat(event.rawText()).contains("Hmm psa new-port terminal (hpnt)");
        });
        assertThat(parsed.events().getFirst().eventTimeType()).isEqualTo(EventTimeType.PLANNED);
        assertThat(parsed.events().getFirst().rawText())
                .contains("Estimated Time of Arrival")
                .contains("Deltaport");
    }

    @Test
    void theRealStatusMappingsNeverTurnTheTransshipmentIntoAPickup() throws IOException {
        ParsedTracking parsed = parser.parse(fixtureSnapshot(), CONTAINER);
        TransportStatus status = shippedMappings().normalize("MSC", parsed.rawStatus());

        assertThat(status).isEqualTo(TransportStatus.TRANSSHIP_LOADED);
        assertThat(status).isNotEqualTo(TransportStatus.PICKED_UP);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Full Transshipment Loaded",
            "Full Transshipment Discharged",
            "Full Transshipment Positioned In",
            "Export Loaded on Vessel",
            "Export received at CY",
            "Empty to Shipper",
            "Discharged"
    })
    void shipmentMovementsNeverBecomeAPickup(String description) throws IOException {
        ParsedTracking parsed = parser.parse(
                flowSnapshot(description, "22/11/2026", "Busan, KR"), CONTAINER);

        assertThat(parsed.rawStatus()).isEqualTo(description);
        TransportStatus status = shippedMappings().normalize("MSC", parsed.rawStatus());
        assertThat(status)
                .as("'%s' must not stop tracking as PICKED_UP", description)
                .isNotEqualTo(TransportStatus.PICKED_UP);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Container picked up",
            "Container picked up by consignee",
            "Full container picked up",
            "Import full gate out"
    })
    void realPickupWordingStillReachesPickedUp(String description) throws IOException {
        ParsedTracking parsed = parser.parse(
                flowSnapshot(description, "22/11/2026", "Vancouver, CA"), CONTAINER);

        TransportStatus status = shippedMappings().normalize("MSC", parsed.rawStatus());
        assertThat(status).isEqualTo(TransportStatus.PICKED_UP);
    }

    @Test
    void anEmptyBoxPickupIsNotTheLoadedContainerPickup() {
        assertThat(StatusWording.isPickupEvidence("Empty container picked up")).isFalse();
        assertThat(StatusWording.isPickupEvidence("Empty pick up at Ningbo")).isFalse();
        assertThat(StatusWording.isPickupEvidence("Container picked up")).isTrue();
        assertThat(StatusWording.isPickupEvidence("Full Transshipment Loaded")).isFalse();
        assertThat(StatusWording.isPickupEvidence("Empty to Shipper")).isFalse();
    }

    @Test
    void aPageWithoutAnyClassifiableEventKeepsTheLatestMoveAndAnInTransitStatus() {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Container", CONTAINER);
        summary.put("Latest move", "BUSAN, KR");
        MscPageSnapshot snapshot = new MscPageSnapshot(
                "MSC Tracking", "https://www.msc.com/en/track-a-shipment",
                "Container MSDU7172233 Latest move BUSAN, KR", null, List.of(), false,
                "Container MSDU7172233\nLatest move BUSAN, KR", false, summary, List.of());

        ParsedTracking parsed = parser.parse(snapshot, CONTAINER);

        assertThat(parsed.rawStatus()).isEqualTo(FlowTracking.IN_TRANSIT);
        assertThat(parsed.location()).isEqualTo("BUSAN, KR");
        assertThat(parsed.etas()).isEmpty();
    }

    private static MscPageSnapshot fixtureSnapshot() throws IOException {
        String html;
        try (InputStream in = MscLiveFixtureTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IOException("fixture not found: " + FIXTURE);
            }
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Document document = Jsoup.parse(html, "");
        MscProperties properties = new MscProperties();

        Element block = document.selectFirst(properties.getResultSelector());
        String blockText = block == null ? null : block.text();

        Map<String, String> summary = new LinkedHashMap<>();
        for (Element cell : document.select(properties.getSummarySelector())) {
            String heading = textOf(cell.selectFirst(properties.getSummaryHeadingSelector()));
            String value = textOf(cell.selectFirst(properties.getSummaryValueSelector()));
            if (heading != null && value != null) {
                summary.put(heading, value);
            }
        }

        List<List<String>> rows = new ArrayList<>();
        for (Element row : document.select(properties.getEventRowSelector())) {
            List<String> cells = new ArrayList<>();
            Elements values = row.select(properties.getSummaryValueSelector());
            if (!values.isEmpty()) {
                for (Element value : values) {
                    cells.add(value.text());
                }
            } else {
                cells.add(row.text());
            }
            rows.add(cells);
        }

        return new MscPageSnapshot(
                "MSC Tracking", "https://www.msc.com/en/track-a-shipment",
                document.body().text(), null, List.of(), false,
                blockText, false, summary, rows);
    }

    /** A minimal calibrated card with one movement row besides the arrival. */
    private static MscPageSnapshot flowSnapshot(String description, String date, String location) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Container", CONTAINER);
        summary.put("Type", "40' HIGH CUBE");
        summary.put("Latest move", location);
        summary.put("POD ETA", "07/12/2026");
        List<List<String>> rows = List.of(
                List.of("07/12/2026", "Vancouver, CA", "Estimated Time of Arrival",
                        "CAPE SOUNIO UK647R", "Deltaport"),
                List.of(date, location, description, "CAPE SOUNIO UK643A", "Terminal"));
        String block = "Container " + CONTAINER + "\nLatest move " + location
                + "\nPOD ETA 07/12/2026";
        return new MscPageSnapshot(
                "MSC Tracking", "https://www.msc.com/en/track-a-shipment",
                block, null, List.of(), false, block, false, summary, rows);
    }

    private static String textOf(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.text().replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    private static StatusMappingSet shippedMappings() throws IOException {
        StatusMappingsLoader loader = new StatusMappingsLoader(shippedMappingFile().toString());
        loader.loadOnStartup();
        return loader.snapshot();
    }

    private static Path shippedMappingFile() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 5 && directory != null; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve("config").resolve("status-mappings.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("config/status-mappings.yaml not found from "
                + System.getProperty("user.dir"));
    }
}
