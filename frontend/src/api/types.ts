/**
 * Wire types for the public API.
 *
 * Field names are copied from 接口文档_终稿.md §3 and the Java DTOs in
 * `contract/src/main/java/com/shiptrack/contract/dto`. The mock layer returns
 * exactly these shapes, so switching `VITE_API_MODE` to `http` needs no view
 * changes.
 */

export type TransportStatus =
  | 'PENDING'
  | 'BOOKED'
  | 'EMPTY_RELEASED'
  | 'EMPTY_PICKED_UP'
  | 'GATE_IN'
  | 'LOADED'
  | 'DEPARTED'
  | 'TRANSSHIP_DISCHARGED'
  | 'WAITING_TRANSSHIP'
  | 'TRANSSHIP_LOADED'
  | 'ARRIVED_POD'
  | 'DISCHARGED'
  | 'AVAILABLE'
  | 'PICKED_UP'
  | 'EMPTY_RETURNED'
  | 'UNKNOWN'

export type EtaRisk = 'NORMAL' | 'DELAYED' | 'EARLY' | 'NO_ETA'

export type TrackingHealth = 'OK' | 'QUERY_FAILED' | 'PARSE_ERROR' | 'STALE'

export type TrackedMilestone = 'POD_ARRIVAL' | 'FINAL_DESTINATION'

export type EtaPrecision = 'DATE' | 'DATETIME'

/** Lower-case on the wire, per §2.8. Nullable for rows the page does not classify. */
export type EventTimeType = 'planned' | 'actual' | 'cancelled' | null

export type EventType =
  | 'ETA_CHANGED'
  | 'ETA_RECOVERED'
  | 'STATUS_CHANGED'
  | 'STATUS_CORRECTED'
  | 'QUERY_FAILED'
  | 'DATA_STALE'
  | 'QUERY_RECOVERED'

export type AttemptStatus = 'queued' | 'running' | 'success' | 'failed' | 'skipped'

export type NotificationStatus = 'pending' | 'sending' | 'sent' | 'failed' | 'skipped'

export interface Eta {
  milestone: TrackedMilestone
  raw_value: string | null
  date: string | null
  time: string | null
  timezone: string | null
  precision: EtaPrecision
  utc_at: string | null
}

export interface SourceEvent {
  milestone: TransportStatus
  event_time_type: EventTimeType
  occurred_at: string | null
  timezone: string | null
  location: string | null
  vessel: string | null
  voyage: string | null
  raw_text: string
}

export interface EtaRange {
  earliest: string | null
  latest: string | null
  count: number
}

export interface OrderSummary {
  transport_status_counts: Record<TransportStatus, number>
  eta_risk_counts: Record<EtaRisk, number>
  tracking_health_counts: Record<TrackingHealth, number>
}

/** `GET /orders` item (§5.2). */
export interface OrderListItem {
  id: number
  order_no: string
  title?: string
  container_count: number
  summary: OrderSummary
  eta_range: EtaRange
  highest_risk: EtaRisk
  updated_at: string
}

/** Full order object (§3.1); returned by `GET /orders/{id}`. */
export interface Order {
  id: number
  order_no: string
  title: string
  container_count: number
  summary: OrderSummary
  eta_range: EtaRange
  highest_risk: EtaRisk
  archived_at: string | null
  created_at: string
  updated_at: string
}

/** Container object (§3.2). */
export interface Container {
  id: number
  order_id: number
  container_no: string
  carrier: string
  container_type: string
  checksum_valid: boolean
  tracked_milestone: TrackedMilestone
  baseline_snapshot_id: number | null
  transport_status: TransportStatus
  eta_risk: EtaRisk
  tracking_health: TrackingHealth
  tracked_eta: Eta | null
  pol: string | null
  pod: string | null
  final_destination: string | null
  location: string | null
  vessel: string | null
  voyage: string | null
  continue_after_pickup: boolean
  tracking_started_at: string | null
  last_attempt_at: string | null
  last_success_at: string | null
  is_active: boolean
  error_code: string | null
  created_at: string
  updated_at: string
}

/** `GET /orders/{id}` response (§5.3). */
export interface OrderDetail extends Order {
  containers: Container[]
}

export interface PageResponse<T> {
  items: T[]
  page: number
  page_size: number
  total: number
}

export interface TimelineBase {
  type: 'snapshot' | 'event' | 'notification'
  type_rank: number
  id: number
  timeline_at: string
}

export interface SnapshotTimelineItem extends TimelineBase {
  type: 'snapshot'
  run_id: string
  attempt_status: AttemptStatus
  queried_at: string | null
  transport_status: TransportStatus | null
  etas?: Eta[]
  /**
   * The documented example shows `etas`; the mock also includes the carrier
   * trajectory rows because SnapshotDto carries `source_events`. The view
   * degrades to business events when a backend omits this field.
   */
  source_events?: SourceEvent[]
}

export interface EventTimelineItem extends TimelineBase {
  type: 'event'
  event_type: EventType
  event_time_type: EventTimeType
  old_value: Record<string, unknown> | null
  new_value: Record<string, unknown> | null
  from_snapshot_id: number | null
  to_snapshot_id: number | null
  notification_id: number | null
  detected_at: string
}

export interface NotificationTimelineItem extends TimelineBase {
  type: 'notification'
  run_id: string
  status: NotificationStatus
  created_at: string
  sent_at: string | null
}

export type TimelineItem =
  | SnapshotTimelineItem
  | EventTimelineItem
  | NotificationTimelineItem

/** `GET /containers/{id}/timeline` response (§5.12). */
export interface TimelineResponse {
  container_id: number
  items: TimelineItem[]
  page: number
  page_size: number
  total: number
}

export interface StatsDefinitions {
  arriving_soon_days: number
  normal_excludes_arriving_soon: boolean
  tracking_issue_overlaps: boolean
  tracking_issue_includes_no_eta: boolean
}

/** `GET /stats/overview` response (§5.19). */
export interface StatsOverview {
  unit: 'container'
  definitions: StatsDefinitions
  total_active: number
  normal: number
  arriving_soon: number
  delayed: number
  tracking_issue: number
}

/** `GET /orders` query parameters (§5.2). */
export interface OrderListQuery {
  keyword?: string
  transport_status?: TransportStatus | ''
  eta_risk?: EtaRisk | ''
  tracking_health?: TrackingHealth | ''
  include_archived?: boolean
  sort?: 'risk_desc' | 'updated_desc'
  page?: number
  page_size?: number
}

export interface TimelineQuery {
  types?: Array<'snapshot' | 'event' | 'notification'>
  page?: number
  page_size?: number
}

export interface ApiErrorBody {
  error: {
    code: string
    message: string
    request_id?: string
    details?: Record<string, unknown>
  }
}
