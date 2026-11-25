import type {
  AttemptStatus,
  Eta,
  EtaRange,
  EtaRisk,
  EventTimeType,
  EventType,
  NotificationStatus,
  TrackingHealth,
  TransportStatus
} from '@/api/types'

const STATUS_LABELS: Record<TransportStatus, string> = {
  PENDING: '待处理',
  BOOKED: '已订舱',
  EMPTY_RELEASED: '空箱已放',
  EMPTY_PICKED_UP: '空箱已提',
  GATE_IN: '已进港',
  LOADED: '已装船',
  DEPARTED: '已离港',
  TRANSSHIP_DISCHARGED: '中转卸船',
  WAITING_TRANSSHIP: '等待中转',
  TRANSSHIP_LOADED: '中转装船',
  ARRIVED_POD: '已到卸货港',
  DISCHARGED: '已卸船',
  AVAILABLE: '可提货',
  PICKED_UP: '已提箱',
  EMPTY_RETURNED: '空箱已还',
  UNKNOWN: '未知'
}

export const statusLabel = (status: TransportStatus | null | undefined): string =>
  status ? STATUS_LABELS[status] : '—'

export type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export function statusTagType(status: TransportStatus | null | undefined): TagType {
  switch (status) {
    case 'PICKED_UP':
    case 'EMPTY_RETURNED':
      return 'success'
    case 'ARRIVED_POD':
    case 'DISCHARGED':
    case 'AVAILABLE':
      return 'warning'
    case 'DEPARTED':
    case 'LOADED':
    case 'TRANSSHIP_LOADED':
    case 'WAITING_TRANSSHIP':
    case 'TRANSSHIP_DISCHARGED':
      return 'primary'
    case 'UNKNOWN':
      return 'info'
    default:
      return 'info'
  }
}

const RISK_LABELS: Record<EtaRisk, string> = {
  NORMAL: '正常',
  DELAYED: '延误',
  EARLY: '提前',
  NO_ETA: '无 ETA'
}

export const riskLabel = (risk: EtaRisk | null | undefined): string =>
  risk ? RISK_LABELS[risk] : '—'

export function riskTagType(risk: EtaRisk | null | undefined): TagType {
  switch (risk) {
    case 'DELAYED':
      return 'danger'
    case 'EARLY':
      return 'warning'
    case 'NO_ETA':
      return 'info'
    case 'NORMAL':
      return 'success'
    default:
      return 'info'
  }
}

const HEALTH_LABELS: Record<TrackingHealth, string> = {
  OK: '正常',
  QUERY_FAILED: '查询失败',
  PARSE_ERROR: '解析失败',
  STALE: '数据过期'
}

export const healthLabel = (health: TrackingHealth | null | undefined): string =>
  health ? HEALTH_LABELS[health] : '—'

export function healthTagType(health: TrackingHealth | null | undefined): TagType {
  switch (health) {
    case 'OK':
      return 'success'
    case 'QUERY_FAILED':
    case 'PARSE_ERROR':
      return 'danger'
    case 'STALE':
      return 'warning'
    default:
      return 'info'
  }
}

const EVENT_LABELS: Record<EventType, string> = {
  ETA_CHANGED: 'ETA 变更',
  ETA_RECOVERED: 'ETA 恢复',
  STATUS_CHANGED: '状态变更',
  STATUS_CORRECTED: '状态更正',
  QUERY_FAILED: '查询失败',
  DATA_STALE: '数据过期',
  QUERY_RECOVERED: '查询恢复'
}

export const eventTypeLabel = (type: EventType | null | undefined): string =>
  type ? EVENT_LABELS[type] : '—'

const TIME_TYPE_LABELS: Record<NonNullable<EventTimeType>, string> = {
  planned: '计划',
  actual: '实际',
  cancelled: '已取消'
}

export const timeTypeLabel = (type: EventTimeType): string =>
  type ? TIME_TYPE_LABELS[type] : '未标注'

export function timeTypeTagType(type: EventTimeType): TagType {
  switch (type) {
    case 'actual':
      return 'success'
    case 'planned':
      return 'primary'
    case 'cancelled':
      return 'info'
    default:
      return 'info'
  }
}

const NOTIFICATION_LABELS: Record<NotificationStatus, string> = {
  pending: '待发送',
  sending: '发送中',
  sent: '已发送',
  failed: '发送失败',
  skipped: '已跳过'
}

export const notificationStatusLabel = (status: NotificationStatus | null | undefined): string =>
  status ? NOTIFICATION_LABELS[status] : '—'

const ATTEMPT_LABELS: Record<AttemptStatus, string> = {
  queued: '排队中',
  running: '查询中',
  success: '成功',
  failed: '失败',
  skipped: '跳过'
}

export const attemptStatusLabel = (status: AttemptStatus | null | undefined): string =>
  status ? ATTEMPT_LABELS[status] : '—'

const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
})

/** ISO instant → `YYYY-MM-DD HH:mm` in Asia/Shanghai (§5.2 of the stack doc). */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const parts = Object.fromEntries(
    dateTimeFormatter.formatToParts(date).map((part) => [part.type, part.value])
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

/** Date-only values are printed as-is; full instants are converted to Shanghai. */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return value
  }
  return formatDateTime(value).slice(0, 10)
}

/** A DATE-precision ETA never gets an invented time (§3.3). */
export function formatEta(eta: Eta | null | undefined): string {
  if (!eta || !eta.date) {
    return '暂无 ETA'
  }
  if (eta.precision === 'DATE') {
    return eta.date
  }
  const time = eta.time ? eta.time.slice(0, 5) : ''
  return `${eta.date} ${time}${eta.timezone ? ` ${eta.timezone}` : ''}`.trim()
}

export function etaRangeText(range: EtaRange | null | undefined): string {
  if (!range || range.count === 0 || !range.earliest) {
    return '暂无 ETA'
  }
  return range.earliest === range.latest ? range.earliest : `${range.earliest} ~ ${range.latest}`
}

export function formatEtaRange(range: EtaRange | null | undefined): string {
  const text = etaRangeText(range)
  return text === '暂无 ETA' ? text : `${text}（${range?.count ?? 0} 箱）`
}

/** `21/11/2026 …` → `2026-11-21` (the carrier renders day-first dates). */
export function leadingCarrierDate(rawText: string): string | null {
  const match = /(\d{1,2})\/(\d{1,2})\/(\d{4})/.exec(rawText)
  if (!match) {
    return null
  }
  const [, day, month, year] = match
  return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`
}

export function relativeTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) {
    return value
  }
  const minutes = Math.round((Date.now() - time) / 60_000)
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.round(minutes / 60)
  if (hours < 48) {
    return `${hours} 小时前`
  }
  return `${Math.round(hours / 24)} 天前`
}

export function describeValue(value: Record<string, unknown> | null | undefined): string {
  if (!value || Object.keys(value).length === 0) {
    return '—'
  }
  if ('date' in value && value.date) {
    return String(value.date)
  }
  if ('transport_status' in value && value.transport_status) {
    return statusLabel(value.transport_status as TransportStatus)
  }
  return Object.entries(value)
    .map(([key, item]) => `${key}: ${String(item)}`)
    .join('，')
}

export const TRANSPORT_STATUS_OPTIONS: Array<{ value: TransportStatus; label: string }> = (
  Object.keys(STATUS_LABELS) as TransportStatus[]
).map((value) => ({ value, label: STATUS_LABELS[value] }))

export const ETA_RISK_OPTIONS: Array<{ value: EtaRisk; label: string }> = (
  Object.keys(RISK_LABELS) as EtaRisk[]
).map((value) => ({ value, label: RISK_LABELS[value] }))

export const TRACKING_HEALTH_OPTIONS: Array<{ value: TrackingHealth; label: string }> = (
  Object.keys(HEALTH_LABELS) as TrackingHealth[]
).map((value) => ({ value, label: HEALTH_LABELS[value] }))
