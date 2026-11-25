package com.shiptrack.api.service;

import com.shiptrack.api.error.ApiException;
import com.shiptrack.api.repo.NotificationRecord;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.support.JsonStore;
import com.shiptrack.contract.dto.NotificationDto;
import com.shiptrack.contract.dto.NotificationPayload;
import com.shiptrack.contract.dto.NotificationRetryResponse;
import com.shiptrack.contract.dto.PageResponse;
import com.shiptrack.contract.enums.ErrorCode;
import com.shiptrack.contract.enums.NotificationChannel;
import com.shiptrack.contract.enums.NotificationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read side of §5.13 plus the manual resend of §5.14. The actual delivery is
 * performed by {@link NotificationDispatchService}.
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final JsonStore jsonStore;

    public NotificationService(NotificationRepository notifications, JsonStore jsonStore) {
        this.notifications = notifications;
        this.jsonStore = jsonStore;
    }

    public PageResponse<NotificationDto> list(Long orderId, String status, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        NotificationStatus filter = parseStatus(status);
        long total = notifications.count(orderId, filter);
        List<NotificationDto> items = new ArrayList<>();
        for (NotificationRecord row : notifications.list(orderId, filter, safeSize,
                (safePage - 1) * safeSize)) {
            items.add(toDto(row));
        }
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    public NotificationDto detail(long id) {
        return toDto(require(id));
    }

    /**
     * §5.14: only {@code failed} can be resent; {@code sending} cannot.
     *
     * <p>The conditional update and the read that builds the 202 response share
     * one transaction, so the response always describes this resend's
     * {@code pending} row. A dispatcher cannot claim the row until commit, and
     * a concurrent retry loses the conditional update.</p>
     */
    @Transactional
    public NotificationRetryResponse retry(long id) {
        NotificationRecord current = require(id);
        int updated = notifications.retryFailed(id);
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "仅 failed 状态的通知可以重发，当前为 " + current.status().wireValue());
        }
        NotificationRecord after = require(id);
        return new NotificationRetryResponse(after.id(), after.status(), after.retryCount());
    }

    private NotificationRecord require(long id) {
        return notifications.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "通知不存在：" + id));
    }

    private NotificationStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NotificationStatus.fromWire(value.trim());
        } catch (IllegalArgumentException error) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "status 必须是 pending/sending/sent/failed");
        }
    }

    private NotificationDto toDto(NotificationRecord row) {
        return new NotificationDto(
                row.id(),
                row.orderId(),
                row.notificationKey(),
                row.runId(),
                NotificationChannel.fromWire(row.channel()),
                row.status(),
                row.retryCount(),
                row.feishuMsgId(),
                row.feishuResponseCode(),
                payload(row.payloadJson()),
                row.createdAt(),
                row.sendingAt(),
                row.nextRetryAt(),
                row.sentAt());
    }

    private NotificationPayload payload(String json) {
        Map<String, Object> map = jsonStore.readMap(json);
        if (map == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        Object value = map.get("lines");
        if (value instanceof List<?> list) {
            for (Object row : list) {
                if (row != null) {
                    lines.add(row.toString());
                }
            }
        }
        return new NotificationPayload(
                text(map, "title"),
                lines,
                time(text(map, "queried_at")),
                text(map, "source"));
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException error) {
            return null;
        }
    }
}
