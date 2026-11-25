package com.shiptrack.api.service;

import com.shiptrack.api.config.ApiProperties;
import com.shiptrack.api.config.FeishuProperties;
import com.shiptrack.api.error.ApiException;
import com.shiptrack.contract.dto.AppConfigDto;
import com.shiptrack.contract.dto.StatusMappingDto;
import com.shiptrack.contract.dto.StatusMappingsDto;
import com.shiptrack.contract.enums.ErrorCode;
import com.shiptrack.contract.enums.TransportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code config.yaml} and {@code status-mappings.yaml}. The API never
 * returns the Feishu webhook or its secret, only whether both are configured.
 */
@Service
public class AppConfigService {

    private static final Logger log = LoggerFactory.getLogger(AppConfigService.class);

    private final ApiProperties properties;
    private final FeishuProperties feishu;

    public AppConfigService(ApiProperties properties, FeishuProperties feishu) {
        this.properties = properties;
        this.feishu = feishu;
    }

    public AppConfigDto get() {
        AppConfigValues values = values();
        return new AppConfigDto(
                values.dailyQueryTime(),
                values.timezone(),
                values.etaDelayThresholdHours(),
                values.etaDelayThresholdDays(),
                values.etaEarlyThresholdHours(),
                values.etaEarlyThresholdDays(),
                values.staleAfterHours(),
                values.arrivingSoonDays(),
                values.notifyOnFirstEta(),
                values.notifyOnStatusChange(),
                values.maxRetryPerRun(),
                values.snapshotRetentionDays(),
                values.rawPayloadRetentionDays(),
                feishuWebhookConfigured());
    }

    @SuppressWarnings("unchecked")
    public AppConfigValues values() {
        Map<String, Object> raw = load(Path.of(properties.getConfigPath()));
        return new AppConfigValues(
                string(raw, "daily_query_time", "08:00"),
                string(raw, "timezone", "Asia/Shanghai"),
                integer(raw, "eta_delay_threshold_hours", 24),
                integer(raw, "eta_delay_threshold_days", 1),
                integer(raw, "eta_early_threshold_hours", 24),
                integer(raw, "eta_early_threshold_days", 1),
                integer(raw, "stale_after_hours", 48),
                integer(raw, "arriving_soon_days", 7),
                bool(raw, "notify_on_first_eta", false),
                bool(raw, "notify_on_status_change", true),
                integer(raw, "max_retry_per_run", 2),
                integer(raw, "snapshot_retention_days", 180),
                integer(raw, "raw_payload_retention_days", 30));
    }

    public StatusMappingsDto statusMappings() {
        Path path = Path.of(properties.getStatusMappingPath());
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = load(path);
        if (raw.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "状态映射文件不存在：" + path.toAbsolutePath());
        }
        int version = integer(raw, "version", 1);
        OffsetDateTime updatedAt = null;
        Object updated = raw.get("updated_at");
        if (updated instanceof String text) {
            try {
                updatedAt = OffsetDateTime.parse(text);
            } catch (DateTimeParseException ignored) {
                updatedAt = null;
            }
        }
        List<StatusMappingDto> mappings = new ArrayList<>();
        Object rows = raw.get("mappings");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> entry)) {
                    continue;
                }
                Object status = entry.get("transport_status");
                if (status == null) {
                    continue;
                }
                mappings.add(new StatusMappingDto(
                        entry.get("carrier") == null ? null : entry.get("carrier").toString(),
                        entry.get("match") == null ? null : entry.get("match").toString(),
                        TransportStatus.valueOf(status.toString()),
                        entry.get("priority") instanceof Number number ? number.intValue() : 0));
            }
        }
        return new StatusMappingsDto(version, updatedAt, mappings);
    }

    /**
     * §5.15: {@code true} only when both the URL and the signature secret are
     * configured. The sender itself only needs the URL, but the public flag
     * keeps the documented "fully configured" meaning.
     */
    public boolean feishuWebhookConfigured() {
        return feishu.isConfigured() && feishu.hasSecret();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(input);
            return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (IOException | RuntimeException error) {
            log.warn("cannot read {}: {}", path, error.getMessage());
            return Map.of();
        }
    }

    private static String string(Map<String, Object> raw, String key, String fallback) {
        Object value = raw.get(key);
        return value == null ? fallback : value.toString();
    }

    private static int integer(Map<String, Object> raw, String key, int fallback) {
        Object value = raw.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Map<String, Object> raw, String key, boolean fallback) {
        Object value = raw.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    public record AppConfigValues(
            String dailyQueryTime,
            String timezone,
            int etaDelayThresholdHours,
            int etaDelayThresholdDays,
            int etaEarlyThresholdHours,
            int etaEarlyThresholdDays,
            int staleAfterHours,
            int arrivingSoonDays,
            boolean notifyOnFirstEta,
            boolean notifyOnStatusChange,
            int maxRetryPerRun,
            int snapshotRetentionDays,
            int rawPayloadRetentionDays
    ) {
    }
}
