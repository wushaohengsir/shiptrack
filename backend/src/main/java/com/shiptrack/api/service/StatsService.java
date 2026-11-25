package com.shiptrack.api.service;

import com.shiptrack.contract.dto.StatsDefinitionsDto;
import com.shiptrack.contract.dto.StatsOverviewDto;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.TrackingHealth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * {@code GET /stats/overview} (§5.19). Only active containers count; the four
 * buckets are not additive because tracking_issue overlaps the others.
 */
@Service
public class StatsService {

    private final JdbcTemplate jdbc;
    private final AppConfigService config;

    public StatsService(JdbcTemplate jdbc, AppConfigService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public StatsOverviewDto overview() {
        AppConfigService.AppConfigValues values = config.values();
        LocalDate today = LocalDate.now(ZoneId.of(values.timezone()));
        LocalDate horizon = today.plusDays(values.arrivingSoonDays());

        Counters counters = jdbc.query("""
                        SELECT eta_risk, tracking_health, tracked_eta_date
                        FROM containers WHERE is_active = 1
                        """,
                resultSet -> {
                    Counters result = new Counters();
                    while (resultSet.next()) {
                        result.totalActive++;
                        EtaRisk risk = EtaRisk.valueOf(resultSet.getString("eta_risk"));
                        TrackingHealth health =
                                TrackingHealth.valueOf(resultSet.getString("tracking_health"));
                        LocalDate etaDate = resultSet.getObject("tracked_eta_date", LocalDate.class);
                        boolean arrivingSoon = risk == EtaRisk.NORMAL && etaDate != null
                                && !etaDate.isBefore(today) && !etaDate.isAfter(horizon);
                        if (arrivingSoon) {
                            result.arrivingSoon++;
                        } else if (risk == EtaRisk.NORMAL || risk == EtaRisk.EARLY) {
                            result.normal++;
                        }
                        if (risk == EtaRisk.DELAYED) {
                            result.delayed++;
                        }
                        if (health != TrackingHealth.OK || risk == EtaRisk.NO_ETA) {
                            result.trackingIssue++;
                        }
                    }
                    return result;
                });

        return new StatsOverviewDto(
                "container",
                new StatsDefinitionsDto(values.arrivingSoonDays(), true, true, true),
                counters.totalActive, counters.normal, counters.arrivingSoon,
                counters.delayed, counters.trackingIssue);
    }

    private static final class Counters {
        private int totalActive;
        private int normal;
        private int arrivingSoon;
        private int delayed;
        private int trackingIssue;
    }
}
