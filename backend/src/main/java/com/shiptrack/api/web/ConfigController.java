package com.shiptrack.api.web;

import com.shiptrack.api.service.AppConfigService;
import com.shiptrack.contract.dto.AppConfigDto;
import com.shiptrack.contract.dto.StatusMappingsDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final AppConfigService config;

    public ConfigController(AppConfigService config) {
        this.config = config;
    }

    @GetMapping
    public AppConfigDto get() {
        return config.get();
    }

    /** Read-only in M3 (writing mappings is deferred to the notification milestone). */
    @GetMapping("/status-mappings")
    public StatusMappingsDto statusMappings() {
        return config.statusMappings();
    }
}
