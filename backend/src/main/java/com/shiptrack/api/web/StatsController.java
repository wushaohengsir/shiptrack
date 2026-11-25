package com.shiptrack.api.web;

import com.shiptrack.api.service.StatsService;
import com.shiptrack.contract.dto.StatsOverviewDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/overview")
    public StatsOverviewDto overview() {
        return stats.overview();
    }
}
