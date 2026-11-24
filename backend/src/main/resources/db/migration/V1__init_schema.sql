-- 船期 / 箱号追踪 MVP · 初始表结构
-- 依据：架构设计文档_终稿.md §6（表、索引、约束）、接口文档_终稿.md §3 / §5.23 / §8
-- 约定：DATETIME(3) 存 UTC（会话 time_zone='+00:00'）；枚举用 VARCHAR + CHECK；
--       自由结构用 JSON；快照引用统一 ON DELETE RESTRICT。

-- ---------------------------------------------------------------------------
-- orders：订单
-- ---------------------------------------------------------------------------
CREATE TABLE orders (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64)     NOT NULL,
    title       VARCHAR(255)    NULL,
    archived_at DATETIME(3)     NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_archived_updated (archived_at, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '订单；archived_at 非空即软删除';

-- ---------------------------------------------------------------------------
-- containers：箱号（当前状态 + 当前监控节点 ETA + 跨批次租约）
-- baseline_snapshot_id 的外键在 tracking_snapshots 建表后追加，避免循环依赖。
-- ---------------------------------------------------------------------------
CREATE TABLE containers (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id              BIGINT UNSIGNED NOT NULL,
    container_no          CHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    carrier               VARCHAR(16)     NOT NULL DEFAULT 'MSC',
    container_type        VARCHAR(16)     NULL,
    checksum_valid        TINYINT      NOT NULL DEFAULT 1 COMMENT 'ISO 6346校验位是否合法：1-合法，0-校验未通过但强制保存',
    tracked_milestone     VARCHAR(32)     NOT NULL DEFAULT 'POD_ARRIVAL',
    baseline_snapshot_id  BIGINT UNSIGNED NULL,
    transport_status      VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    eta_risk              VARCHAR(16)     NOT NULL DEFAULT 'NO_ETA',
    tracking_health       VARCHAR(16)     NOT NULL DEFAULT 'OK',
    tracked_eta_raw_value VARCHAR(255)    NULL,
    tracked_eta_date      DATE            NULL,
    tracked_eta_time      TIME            NULL,
    tracked_eta_timezone  VARCHAR(64)     NULL,
    tracked_eta_precision VARCHAR(16)     NULL,
    tracked_eta_utc_at    DATETIME(3)     NULL,
    pol                   VARCHAR(128)    NULL,
    pod                   VARCHAR(128)    NULL,
    final_destination     VARCHAR(128)    NULL,
    location              VARCHAR(128)    NULL,
    vessel                VARCHAR(128)    NULL,
    voyage                VARCHAR(32)     NULL,
    continue_after_pickup TINYINT      NOT NULL DEFAULT 0,
    tracking_started_at   DATETIME(3)     NULL,
    last_attempt_at       DATETIME(3)     NULL,
    last_success_at       DATETIME(3)     NULL,
    is_active             TINYINT      NOT NULL DEFAULT 1,
    error_code            VARCHAR(64)     NULL,
    lock_run_id           CHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lock_acquired_at      DATETIME(3)     NULL,
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_containers_order_container (order_id, container_no),
    KEY idx_containers_active_order (is_active, order_id),
    KEY idx_containers_lock_run (lock_run_id),
    CONSTRAINT fk_containers_order
        FOREIGN KEY (order_id) REFERENCES orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_containers_container_no
        CHECK (container_no REGEXP '^[A-Z]{4}[0-9]{7}$'),
    CONSTRAINT ck_containers_tracked_milestone
        CHECK (tracked_milestone IN ('POD_ARRIVAL', 'FINAL_DESTINATION')),
    CONSTRAINT ck_containers_transport_status
        CHECK (transport_status IN (
            'PENDING', 'BOOKED', 'EMPTY_RELEASED', 'EMPTY_PICKED_UP', 'GATE_IN',
            'LOADED', 'DEPARTED', 'TRANSSHIP_DISCHARGED', 'WAITING_TRANSSHIP',
            'TRANSSHIP_LOADED', 'ARRIVED_POD', 'DISCHARGED', 'AVAILABLE',
            'PICKED_UP', 'EMPTY_RETURNED', 'UNKNOWN')),
    CONSTRAINT ck_containers_eta_risk
        CHECK (eta_risk IN ('NORMAL', 'DELAYED', 'EARLY', 'NO_ETA')),
    CONSTRAINT ck_containers_tracking_health
        CHECK (tracking_health IN ('OK', 'QUERY_FAILED', 'STALE', 'PARSE_ERROR')),
    CONSTRAINT ck_containers_eta_precision
        CHECK (tracked_eta_precision IS NULL
            OR tracked_eta_precision IN ('DATE', 'DATETIME'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '箱号：当前状态、当前监控节点 ETA、跨批次租约';

-- ---------------------------------------------------------------------------
-- tracking_snapshots：一次查询尝试 + 结果快照（run_id + container_id 唯一）
-- ---------------------------------------------------------------------------
CREATE TABLE tracking_snapshots (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    container_id       BIGINT UNSIGNED NOT NULL,
    run_id             CHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_trigger        VARCHAR(20)     NOT NULL,
    attempt_status     VARCHAR(16)     NOT NULL,
    retry_count        INT UNSIGNED    NOT NULL DEFAULT 0,
    queued_at          DATETIME(3)     NOT NULL,
    claimed_at         DATETIME(3)     NULL,
    claim_token        CHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
    started_at         DATETIME(3)     NULL,
    finished_at        DATETIME(3)     NULL,
    source             VARCHAR(32)     NULL,
    success            TINYINT      NULL,
    transport_status   VARCHAR(32)     NULL,
    raw_status         VARCHAR(2000)   NULL,
    etas               JSON            NULL,
    source_events      JSON            NULL,
    location           VARCHAR(128)    NULL,
    vessel             VARCHAR(128)    NULL,
    voyage             VARCHAR(32)     NULL,
    pol                VARCHAR(128)    NULL,
    pod                VARCHAR(128)    NULL,
    final_destination  VARCHAR(128)    NULL,
    content_hash       CHAR(71) CHARACTER SET ascii COLLATE ascii_bin NULL,
    mapping_version    INT UNSIGNED    NULL,
    error_code         VARCHAR(64)     NULL,
    error_message      VARCHAR(1024)   NULL,
    raw_payload        JSON            NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshots_run_container (run_id, container_id),
    KEY idx_snapshots_container_finished (container_id, finished_at),
    KEY idx_snapshots_status_claimed (attempt_status, claimed_at),
    KEY idx_snapshots_queued (queued_at),
    CONSTRAINT fk_snapshots_container
        FOREIGN KEY (container_id) REFERENCES containers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_snapshots_run_trigger
        CHECK (run_trigger IN ('schedule', 'manual_container', 'manual_order', 'manual_all')),
    CONSTRAINT ck_snapshots_attempt_status
        CHECK (attempt_status IN ('queued', 'running', 'success', 'failed', 'skipped')),
    CONSTRAINT ck_snapshots_transport_status
        CHECK (transport_status IS NULL OR transport_status IN (
            'PENDING', 'BOOKED', 'EMPTY_RELEASED', 'EMPTY_PICKED_UP', 'GATE_IN',
            'LOADED', 'DEPARTED', 'TRANSSHIP_DISCHARGED', 'WAITING_TRANSSHIP',
            'TRANSSHIP_LOADED', 'ARRIVED_POD', 'DISCHARGED', 'AVAILABLE',
            'PICKED_UP', 'EMPTY_RETURNED', 'UNKNOWN')),
    CONSTRAINT ck_snapshots_content_hash
        CHECK (content_hash IS NULL OR content_hash REGEXP '^sha256:[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '查询尝试与结果快照；终态不可改写';

-- ---------------------------------------------------------------------------
-- notifications：通知队列（pending/sending/sent/failed）
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id             BIGINT UNSIGNED NOT NULL,
    notification_key     VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id               CHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel              VARCHAR(16)     NOT NULL DEFAULT 'feishu',
    status               VARCHAR(16)     NOT NULL DEFAULT 'pending',
    retry_count          INT UNSIGNED    NOT NULL DEFAULT 0,
    feishu_msg_id        VARCHAR(64)     NULL,
    feishu_response_code INT             NULL,
    last_error           VARCHAR(1024)   NULL,
    payload              JSON            NOT NULL,
    created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sending_at           DATETIME(3)     NULL,
    next_retry_at        DATETIME(3)     NULL,
    sent_at              DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_key (notification_key),
    KEY idx_notifications_status_retry (status, next_retry_at),
    KEY idx_notifications_order_created (order_id, created_at),
    CONSTRAINT fk_notifications_order
        FOREIGN KEY (order_id) REFERENCES orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notifications_channel
        CHECK (channel IN ('feishu')),
    CONSTRAINT ck_notifications_status
        CHECK (status IN ('pending', 'sending', 'sent', 'failed'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '飞书通知；notification_key 唯一，表即队列';

-- ---------------------------------------------------------------------------
-- tracking_events：业务变化事件；三个快照引用与通知引用均 RESTRICT
-- ---------------------------------------------------------------------------
CREATE TABLE tracking_events (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    container_id         BIGINT UNSIGNED NOT NULL,
    notification_id      BIGINT UNSIGNED NULL,
    event_key            VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type           VARCHAR(32)     NOT NULL,
    field_name           VARCHAR(32)     NULL,
    event_time_type      VARCHAR(16)     NULL,
    from_snapshot_id     BIGINT UNSIGNED NULL,
    to_snapshot_id       BIGINT UNSIGNED NULL,
    baseline_snapshot_id BIGINT UNSIGNED NULL,
    baseline_delta       JSON            NULL,
    risk_before          VARCHAR(16)     NULL,
    risk_after           VARCHAR(16)     NULL,
    old_value            JSON            NULL,
    new_value            JSON            NULL,
    source_event_at      DATETIME(3)     NULL,
    detected_at          DATETIME(3)     NOT NULL,
    source               VARCHAR(32)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_events_event_key (event_key),
    KEY idx_events_container_detected (container_id, detected_at),
    KEY idx_events_notification (notification_id),
    CONSTRAINT fk_events_container
        FOREIGN KEY (container_id) REFERENCES containers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_events_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_events_from_snapshot
        FOREIGN KEY (from_snapshot_id) REFERENCES tracking_snapshots (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_events_to_snapshot
        FOREIGN KEY (to_snapshot_id) REFERENCES tracking_snapshots (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_events_baseline_snapshot
        FOREIGN KEY (baseline_snapshot_id) REFERENCES tracking_snapshots (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_events_event_type
        CHECK (event_type IN (
            'ETA_CHANGED', 'ETA_RECOVERED', 'STATUS_CHANGED', 'STATUS_CORRECTED',
            'QUERY_FAILED', 'DATA_STALE', 'QUERY_RECOVERED')),
    CONSTRAINT ck_events_event_time_type
        CHECK (event_time_type IS NULL
            OR event_time_type IN ('planned', 'actual', 'cancelled')),
    CONSTRAINT ck_events_risk_before
        CHECK (risk_before IS NULL
            OR risk_before IN ('NORMAL', 'DELAYED', 'EARLY', 'NO_ETA')),
    CONSTRAINT ck_events_risk_after
        CHECK (risk_after IS NULL
            OR risk_after IN ('NORMAL', 'DELAYED', 'EARLY', 'NO_ETA'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '业务事件；event_key 唯一，快照引用 RESTRICT';

-- 循环引用：容器当前基线快照。必须在 tracking_snapshots 之后追加。
ALTER TABLE containers
    ADD CONSTRAINT fk_containers_baseline_snapshot
        FOREIGN KEY (baseline_snapshot_id) REFERENCES tracking_snapshots (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;
