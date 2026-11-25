package com.shiptrack.api.service;

import com.shiptrack.api.error.ApiException;
import com.shiptrack.api.repo.ContainerRecord;
import com.shiptrack.api.repo.ContainerRepository;
import com.shiptrack.api.repo.OrderFilter;
import com.shiptrack.api.repo.OrderRecord;
import com.shiptrack.api.repo.OrderRepository;
import com.shiptrack.api.support.ContainerNumbers;
import com.shiptrack.contract.dto.AddContainersRequest;
import com.shiptrack.contract.dto.ContainerCreateRequest;
import com.shiptrack.contract.dto.ContainerDto;
import com.shiptrack.contract.dto.CreateOrderRequest;
import com.shiptrack.contract.dto.EtaRange;
import com.shiptrack.contract.dto.OrderDetailResponse;
import com.shiptrack.contract.dto.OrderListItemDto;
import com.shiptrack.contract.dto.OrderSummary;
import com.shiptrack.contract.dto.PageResponse;
import com.shiptrack.contract.dto.UpdateContainerRequest;
import com.shiptrack.contract.dto.UpdateContainerResponse;
import com.shiptrack.contract.dto.UpdateOrderRequest;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.ErrorCode;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ContainerRepository containers;

    public OrderService(OrderRepository orders, ContainerRepository containers) {
        this.orders = orders;
        this.containers = containers;
    }

    @Transactional
    public OrderDetailResponse create(CreateOrderRequest request) {
        if (request == null || request.orderNo() == null || request.orderNo().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "order_no 不能为空");
        }
        if (request.containers() == null || request.containers().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "至少需要一个箱号");
        }
        String orderNo = request.orderNo().trim();
        if (orders.existsByOrderNo(orderNo)) {
            throw new ApiException(ErrorCode.DUPLICATE_ORDER_NO, "订单号已存在：" + orderNo);
        }
        List<PreparedContainer> prepared = prepare(request.containers(),
                Boolean.TRUE.equals(request.forceInvalidContainers()));

        long orderId;
        try {
            orderId = orders.insert(orderNo, request.title());
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DUPLICATE_ORDER_NO, "订单号已存在：" + orderNo);
        }
        for (PreparedContainer container : prepared) {
            containers.insert(orderId, container.containerNo(), container.carrier(),
                    container.containerType(), container.milestone(),
                    container.continueAfterPickup(), container.checksumValid());
        }
        return detail(orderId);
    }

    public PageResponse<OrderListItemDto> list(OrderFilter filter) {
        long total = orders.count(filter);
        int page = Math.max(filter.page(), 1);
        int pageSize = Math.min(Math.max(filter.pageSize(), 1), 100);
        int offset = (page - 1) * pageSize;
        List<Long> ids = orders.findIds(filter, pageSize, offset);
        List<OrderListItemDto> items = new ArrayList<>(ids.size());
        for (long id : ids) {
            OrderRecord order = orders.findById(id).orElseThrow();
            List<ContainerRecord> orderContainers = containers.findByOrderId(id);
            items.add(new OrderListItemDto(
                    order.id(),
                    order.orderNo(),
                    orderContainers.size(),
                    summary(orderContainers),
                    etaRange(orderContainers),
                    highestRisk(orderContainers),
                    order.updatedAt()));
        }
        return new PageResponse<>(items, page, pageSize, total);
    }

    public OrderDetailResponse detail(long orderId) {
        OrderRecord order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单不存在：" + orderId));
        List<ContainerRecord> orderContainers = containers.findByOrderId(orderId);
        List<ContainerDto> dtos = orderContainers.stream().map(ContainerRecord::toDto).toList();
        return new OrderDetailResponse(
                order.id(), order.orderNo(), order.title(), dtos.size(),
                summary(orderContainers), etaRange(orderContainers),
                highestRisk(orderContainers), order.archivedAt(), order.createdAt(),
                order.updatedAt(), dtos);
    }

    @Transactional
    public OrderDetailResponse update(long orderId, UpdateOrderRequest request) {
        OrderRecord order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单不存在：" + orderId));
        if (request == null || (request.orderNo() == null && request.title() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "没有可修改的字段");
        }
        if (request.orderNo() != null && !request.orderNo().isBlank()
                && !request.orderNo().trim().equals(order.orderNo())
                && orders.existsByOrderNo(request.orderNo().trim())) {
            throw new ApiException(ErrorCode.DUPLICATE_ORDER_NO,
                    "订单号已存在：" + request.orderNo().trim());
        }
        try {
            orders.update(orderId, request.orderNo() == null ? null : request.orderNo().trim(),
                    request.title());
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DUPLICATE_ORDER_NO, "订单号已存在");
        }
        return detail(orderId);
    }

    @Transactional
    public void archive(long orderId) {
        orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单不存在：" + orderId));
        orders.archive(orderId);
    }

    @Transactional
    public List<ContainerDto> addContainers(long orderId, AddContainersRequest request) {
        orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单不存在：" + orderId));
        if (request == null || request.containers() == null || request.containers().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "至少需要一个箱号");
        }
        List<PreparedContainer> prepared = prepare(request.containers(),
                Boolean.TRUE.equals(request.forceInvalidContainers()));
        List<ContainerDto> added = new ArrayList<>(prepared.size());
        for (PreparedContainer container : prepared) {
            if (containers.existsByOrderAndNumber(orderId, container.containerNo())) {
                throw new ApiException(ErrorCode.DUPLICATE_CONTAINER,
                        "同一订单内箱号重复：" + container.containerNo());
            }
            long id = containers.insert(orderId, container.containerNo(), container.carrier(),
                    container.containerType(), container.milestone(),
                    container.continueAfterPickup(), container.checksumValid());
            added.add(containers.findById(id).orElseThrow().toDto());
        }
        return added;
    }

    @Transactional
    public UpdateContainerResponse updateContainer(long containerId, UpdateContainerRequest request) {
        ContainerRecord current = containers.findById(containerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "箱号不存在：" + containerId));
        TrackedMilestone milestone = request.trackedMilestone() == null
                ? current.trackedMilestone() : request.trackedMilestone();
        boolean resetBaseline = milestone != current.trackedMilestone();
        String containerType = request.containerType() == null
                ? current.containerType() : request.containerType();
        boolean continueAfterPickup = request.continueAfterPickup() == null
                ? current.continueAfterPickup() : request.continueAfterPickup();
        boolean active = request.active() == null ? current.active() : request.active();
        boolean reactivated = !current.active() && active;

        containers.updateSettings(containerId, milestone, containerType, continueAfterPickup,
                active, resetBaseline, reactivated);
        ContainerRecord updated = containers.findById(containerId).orElseThrow();
        return updated.toUpdateResponse(resetBaseline);
    }

    @Transactional
    public void deactivateContainer(long containerId) {
        containers.findById(containerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "箱号不存在：" + containerId));
        containers.deactivate(containerId);
    }

    public OrderRecord requireOrder(long orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单不存在：" + orderId));
    }

    // ------------------------------------------------------------------
    // Derived order fields (§3.1)
    // ------------------------------------------------------------------

    public static OrderSummary summary(List<ContainerRecord> list) {
        Map<TransportStatus, Integer> transport = new EnumMap<>(TransportStatus.class);
        Map<EtaRisk, Integer> risk = new EnumMap<>(EtaRisk.class);
        Map<TrackingHealth, Integer> health = new EnumMap<>(TrackingHealth.class);
        for (TransportStatus value : TransportStatus.values()) {
            transport.put(value, 0);
        }
        for (EtaRisk value : EtaRisk.values()) {
            risk.put(value, 0);
        }
        for (TrackingHealth value : TrackingHealth.values()) {
            health.put(value, 0);
        }
        for (ContainerRecord container : list) {
            transport.merge(container.transportStatus(), 1, Integer::sum);
            risk.merge(container.etaRisk(), 1, Integer::sum);
            health.merge(container.trackingHealth(), 1, Integer::sum);
        }
        return new OrderSummary(transport, risk, health);
    }

    public static EtaRange etaRange(List<ContainerRecord> list) {
        LocalDate earliest = null;
        LocalDate latest = null;
        int count = 0;
        for (ContainerRecord container : list) {
            LocalDate date = container.trackedEtaDate();
            if (!container.active() || date == null) {
                continue;
            }
            count++;
            if (earliest == null || date.isBefore(earliest)) {
                earliest = date;
            }
            if (latest == null || date.isAfter(latest)) {
                latest = date;
            }
        }
        return new EtaRange(earliest, latest, count);
    }

    /**
     * Composite risk of §3.1 folded into the only field the contract exposes:
     * any data-health issue ranks above NO_ETA, which ranks above DELAYED.
     */
    public static EtaRisk highestRisk(List<ContainerRecord> list) {
        boolean healthIssue = false;
        boolean noEta = false;
        boolean delayed = false;
        boolean early = false;
        boolean normal = false;
        for (ContainerRecord container : list) {
            if (container.trackingHealth() != TrackingHealth.OK) {
                healthIssue = true;
            }
            switch (container.etaRisk()) {
                case NO_ETA -> noEta = true;
                case DELAYED -> delayed = true;
                case EARLY -> early = true;
                case NORMAL -> normal = true;
            }
        }
        if (healthIssue || noEta || list.isEmpty()) {
            return EtaRisk.NO_ETA;
        }
        if (delayed) {
            return EtaRisk.DELAYED;
        }
        if (early) {
            return EtaRisk.EARLY;
        }
        return normal ? EtaRisk.NORMAL : EtaRisk.NO_ETA;
    }

    // ------------------------------------------------------------------
    // Container request preparation
    // ------------------------------------------------------------------

    private List<PreparedContainer> prepare(List<ContainerCreateRequest> requests, boolean forceInvalid) {
        List<PreparedContainer> prepared = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> invalid = new ArrayList<>();
        for (ContainerCreateRequest request : requests) {
            String containerNo = ContainerNumbers.normalize(request.containerNo());
            if (!ContainerNumbers.hasValidFormat(containerNo)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "箱号格式不正确：" + request.containerNo());
            }
            if (!seen.add(containerNo)) {
                throw new ApiException(ErrorCode.DUPLICATE_CONTAINER, "同一订单内箱号重复：" + containerNo);
            }
            boolean checksumValid = ContainerNumbers.hasValidChecksum(containerNo);
            if (!checksumValid && !forceInvalid) {
                invalid.add(containerNo);
            }
            String carrier = request.carrier() == null || request.carrier().isBlank()
                    ? "MSC" : request.carrier().trim().toUpperCase();
            TrackedMilestone milestone = request.trackedMilestone() == null
                    ? TrackedMilestone.POD_ARRIVAL : request.trackedMilestone();
            prepared.add(new PreparedContainer(containerNo, carrier,
                    request.containerType(), milestone,
                    Boolean.TRUE.equals(request.continueAfterPickup()), checksumValid));
        }
        if (!invalid.isEmpty()) {
            throw new ApiException(ErrorCode.CONTAINER_CHECKSUM_INVALID,
                    "箱号校验位不正确：" + String.join(", ", invalid),
                    Map.of("invalid_containers", invalid));
        }
        return prepared;
    }

    private record PreparedContainer(String containerNo, String carrier, String containerType,
                                     TrackedMilestone milestone, boolean continueAfterPickup,
                                     boolean checksumValid) {
    }
}
