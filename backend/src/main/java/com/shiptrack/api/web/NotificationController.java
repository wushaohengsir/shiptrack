package com.shiptrack.api.web;

import com.shiptrack.api.service.NotificationService;
import com.shiptrack.contract.dto.NotificationDto;
import com.shiptrack.contract.dto.NotificationRetryResponse;
import com.shiptrack.contract.dto.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** §5.13 */
    @GetMapping
    public PageResponse<NotificationDto> list(
            @RequestParam(name = "order_id", required = false) Long orderId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize) {
        return notifications.list(orderId, status, page, pageSize);
    }

    @GetMapping("/{id}")
    public NotificationDto detail(@PathVariable long id) {
        return notifications.detail(id);
    }

    /** §5.14 */
    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NotificationRetryResponse retry(@PathVariable long id) {
        return notifications.retry(id);
    }
}
