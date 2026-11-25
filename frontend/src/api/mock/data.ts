import type {
  Container,
  EtaRisk,
  EventTimelineItem,
  NotificationTimelineItem,
  Order,
  OrderDetail,
  OrderListItem,
  OrderSummary,
  SnapshotTimelineItem,
  SourceEvent,
  StatsOverview,
  TimelineItem,
  TimelineResponse,
  TrackingHealth,
  TransportStatus,
  Eta
} from '@/api/types'

export const TRANSPORT_STATUSES: TransportStatus[] = [
  'PENDING',
  'BOOKED',
  'EMPTY_RELEASED',
  'EMPTY_PICKED_UP',
  'GATE_IN',
  'LOADED',
  'DEPARTED',
  'TRANSSHIP_DISCHARGED',
  'WAITING_TRANSSHIP',
  'TRANSSHIP_LOADED',
  'ARRIVED_POD',
  'DISCHARGED',
  'AVAILABLE',
  'PICKED_UP',
  'EMPTY_RETURNED',
  'UNKNOWN'
]

export const ETA_RISKS: EtaRisk[] = ['NORMAL', 'DELAYED', 'EARLY', 'NO_ETA']
export const TRACKING_HEALTHS: TrackingHealth[] = ['OK', 'QUERY_FAILED', 'PARSE_ERROR', 'STALE']

function zeroed<K extends string>(keys: readonly K[]): Record<K, number> {
  return keys.reduce(
    (counts, key) => {
      counts[key] = 0
      return counts
    },
    {} as Record<K, number>
  )
}

export function orderSummary(containers: Container[]): OrderSummary {
  const transport = zeroed(TRANSPORT_STATUSES)
  const risk = zeroed(ETA_RISKS)
  const health = zeroed(TRACKING_HEALTHS)
  for (const container of containers) {
    transport[container.transport_status] += 1
    risk[container.eta_risk] += 1
    health[container.tracking_health] += 1
  }
  return {
    transport_status_counts: transport,
    eta_risk_counts: risk,
    tracking_health_counts: health
  }
}

const RISK_WEIGHT: Record<EtaRisk, number> = {
  NO_ETA: 3,
  DELAYED: 2,
  EARLY: 1,
  NORMAL: 0
}

function highestRisk(containers: Container[]): EtaRisk {
  return containers.reduce<EtaRisk>(
    (highest, container) =>
      RISK_WEIGHT[container.eta_risk] > RISK_WEIGHT[highest] ? container.eta_risk : highest,
    'NORMAL'
  )
}

function etaRange(containers: Container[]) {
  const dates = containers
    .filter((container) => container.is_active && container.tracked_eta?.date)
    .map((container) => container.tracked_eta!.date as string)
    .sort()
  return {
    earliest: dates[0] ?? null,
    latest: dates[dates.length - 1] ?? null,
    count: dates.length
  }
}

function eta(
  milestone: 'POD_ARRIVAL' | 'FINAL_DESTINATION',
  date: string | null,
  rawValue: string | null,
  timezone = 'Asia/Shanghai'
): Eta | null {
  if (!date) {
    return null
  }
  return {
    milestone,
    raw_value: rawValue,
    date,
    time: null,
    timezone,
    precision: 'DATE',
    utc_at: null
  }
}

function container(base: Partial<Container> & Pick<Container, 'id' | 'order_id' | 'container_no'>): Container {
  return {
    carrier: 'MSC',
    container_type: '40HC',
    checksum_valid: true,
    tracked_milestone: 'POD_ARRIVAL',
    baseline_snapshot_id: 5001,
    transport_status: 'UNKNOWN',
    eta_risk: 'NO_ETA',
    tracking_health: 'OK',
    tracked_eta: null,
    pol: null,
    pod: null,
    final_destination: null,
    location: null,
    vessel: null,
    voyage: null,
    continue_after_pickup: false,
    tracking_started_at: '2026-11-20T10:00:00+08:00',
    last_attempt_at: '2026-11-25T08:00:00+08:00',
    last_success_at: '2026-11-25T08:01:00+08:00',
    is_active: true,
    error_code: null,
    created_at: '2026-11-20T10:00:00+08:00',
    updated_at: '2026-11-25T08:05:00+08:00',
    ...base
  }
}

export const containers: Container[] = [
  container({
    id: 10,
    order_id: 1,
    container_no: 'MSDU7172233',
    container_type: "40' HIGH CUBE",
    transport_status: 'DEPARTED',
    eta_risk: 'DELAYED',
    tracking_health: 'OK',
    tracked_eta: eta('POD_ARRIVAL', '2026-12-07', 'POD ETA 07/12/2026', 'America/Vancouver'),
    pol: 'Ningbo, CN',
    pod: 'Vancouver, CA',
    final_destination: 'Toronto, CA',
    location: 'BUSAN, KR',
    vessel: 'CAPE SOUNIO',
    voyage: 'UK647R',
    last_success_at: '2026-11-22T08:01:00+08:00',
    updated_at: '2026-11-22T08:05:00+08:00'
  }),
  container({
    id: 11,
    order_id: 1,
    container_no: 'MSCU8842210',
    transport_status: 'TRANSSHIP_LOADED',
    eta_risk: 'NORMAL',
    tracked_eta: eta('POD_ARRIVAL', '2026-12-12', 'POD ETA 12/12/2026', 'America/Vancouver'),
    pol: 'Ningbo, CN',
    pod: 'Vancouver, CA',
    final_destination: 'Toronto, CA',
    location: 'Busan, KR',
    vessel: 'MSC SOFIA',
    voyage: 'GO643N',
    last_success_at: '2026-11-22T08:01:20+08:00',
    updated_at: '2026-11-22T08:05:10+08:00'
  }),
  container({
    id: 12,
    order_id: 1,
    container_no: 'MEDU4471902',
    transport_status: 'LOADED',
    eta_risk: 'NORMAL',
    tracked_eta: eta('POD_ARRIVAL', '2026-11-28', 'POD ETA 28/11/2026', 'America/Vancouver'),
    pol: 'Ningbo, CN',
    pod: 'Vancouver, CA',
    final_destination: 'Toronto, CA',
    location: 'Ningbo, CN',
    vessel: 'MSC SOFIA',
    voyage: 'GO643N',
    last_success_at: '2026-11-22T08:01:40+08:00',
    updated_at: '2026-11-22T08:05:20+08:00'
  }),
  container({
    id: 20,
    order_id: 2,
    container_no: 'TCLU5519037',
    carrier: 'MSC',
    transport_status: 'PICKED_UP',
    eta_risk: 'NORMAL',
    tracked_eta: eta('POD_ARRIVAL', '2026-11-20', 'POD ETA 20/11/2026', 'America/Los_Angeles'),
    pol: 'Shanghai, CN',
    pod: 'Los Angeles, US',
    final_destination: 'Los Angeles, US',
    location: 'Los Angeles, US',
    vessel: 'MSC ANNA',
    voyage: 'UX221A',
    continue_after_pickup: true,
    last_success_at: '2026-11-21T08:01:00+08:00',
    updated_at: '2026-11-21T08:05:00+08:00'
  }),
  container({
    id: 21,
    order_id: 2,
    container_no: 'CMAU7720451',
    transport_status: 'EMPTY_RETURNED',
    eta_risk: 'NORMAL',
    tracked_eta: eta('POD_ARRIVAL', '2026-11-18', 'POD ETA 18/11/2026', 'America/Los_Angeles'),
    pol: 'Shanghai, CN',
    pod: 'Los Angeles, US',
    final_destination: 'Los Angeles, US',
    location: 'Los Angeles, US',
    vessel: 'MSC ANNA',
    voyage: 'UX221A',
    is_active: false,
    last_success_at: '2026-11-19T08:01:00+08:00',
    updated_at: '2026-11-19T08:05:00+08:00'
  }),
  container({
    id: 30,
    order_id: 3,
    container_no: 'OOLU9031122',
    carrier: 'MSC',
    transport_status: 'ARRIVED_POD',
    eta_risk: 'DELAYED',
    tracked_eta: eta('POD_ARRIVAL', '2026-11-24', 'POD ETA 24/11/2026', 'Europe/Berlin'),
    pol: 'Yantian, CN',
    pod: 'Hamburg, DE',
    final_destination: 'Hamburg, DE',
    location: 'Hamburg, DE',
    vessel: 'MSC REEF',
    voyage: 'FE118W',
    last_success_at: '2026-11-25T09:01:00+08:00',
    updated_at: '2026-11-25T09:05:00+08:00'
  }),
  container({
    id: 40,
    order_id: 4,
    container_no: 'COSU6201884',
    carrier: 'MSC',
    transport_status: 'UNKNOWN',
    eta_risk: 'NO_ETA',
    tracking_health: 'PARSE_ERROR',
    pol: 'Qingdao, CN',
    pod: 'Long Beach, US',
    final_destination: 'Long Beach, US',
    location: 'Qingdao, CN',
    error_code: 'PARSE_ERROR',
    last_attempt_at: '2026-11-25T09:00:00+08:00',
    last_success_at: null,
    updated_at: '2026-11-25T09:05:30+08:00'
  }),
  container({
    id: 41,
    order_id: 4,
    container_no: 'FCIU3317788',
    transport_status: 'GATE_IN',
    eta_risk: 'NORMAL',
    tracking_health: 'STALE',
    tracked_eta: eta('POD_ARRIVAL', '2027-01-05', 'POD ETA 05/01/2027', 'America/Los_Angeles'),
    pol: 'Qingdao, CN',
    pod: 'Long Beach, US',
    final_destination: 'Long Beach, US',
    location: 'Qingdao, CN',
    vessel: 'MSC AURORA',
    voyage: 'QA330E',
    last_attempt_at: '2026-11-23T08:00:00+08:00',
    last_success_at: '2026-11-23T08:01:00+08:00',
    updated_at: '2026-11-23T08:05:00+08:00'
  })
]

function order(base: Omit<Order, 'container_count' | 'summary' | 'eta_range' | 'highest_risk'>): Order {
  const own = containers.filter((container) => container.order_id === base.id)
  return {
    ...base,
    container_count: own.length,
    summary: orderSummary(own),
    eta_range: etaRange(own),
    highest_risk: highestRisk(own)
  }
}

export const orders: Order[] = [
  order({
    id: 1,
    order_no: 'PO-001',
    title: '宁波→多伦多 11 月批次',
    archived_at: null,
    created_at: '2026-11-20T10:00:00+08:00',
    updated_at: '2026-11-22T08:05:20+08:00'
  }),
  order({
    id: 2,
    order_no: 'PO-002',
    title: '上海→洛杉矶 12 月批次',
    archived_at: null,
    created_at: '2026-11-14T09:30:00+08:00',
    updated_at: '2026-11-21T08:05:00+08:00'
  }),
  order({
    id: 3,
    order_no: 'PO-003',
    title: '盐田→汉堡 11 月批次',
    archived_at: null,
    created_at: '2026-11-10T16:20:00+08:00',
    updated_at: '2026-11-25T09:05:00+08:00'
  }),
  order({
    id: 4,
    order_no: 'PO-004',
    title: '青岛→长滩 12 月批次',
    archived_at: null,
    created_at: '2026-11-18T11:00:00+08:00',
    updated_at: '2026-11-25T09:05:30+08:00'
  })
]

export function orderDetail(id: number): OrderDetail | null {
  const found = orders.find((item) => item.id === id)
  if (!found) {
    return null
  }
  return {
    ...found,
    containers: containers.filter((container) => container.order_id === id)
  }
}

export function orderListItem(order: Order): OrderListItem {
  return {
    id: order.id,
    order_no: order.order_no,
    container_count: order.container_count,
    summary: order.summary,
    eta_range: order.eta_range,
    highest_risk: order.highest_risk,
    updated_at: order.updated_at
  }
}

const liveTrajectory: SourceEvent[] = [
  {
    milestone: 'ARRIVED_POD',
    event_time_type: 'planned',
    occurred_at: null,
    timezone: null,
    location: 'Vancouver, CA',
    vessel: 'CAPE SOUNIO',
    voyage: 'UK647R',
    raw_text: '07/12/2026 | Vancouver, CA | Estimated Time of Arrival | CAPE SOUNIO UK647R | Deltaport'
  },
  {
    milestone: 'TRANSSHIP_LOADED',
    event_time_type: 'actual',
    occurred_at: null,
    timezone: null,
    location: 'Busan, KR',
    vessel: 'CAPE SOUNIO',
    voyage: 'UK643A',
    raw_text:
      '21/11/2026 | Busan, KR | Full Transshipment Loaded | CAPE SOUNIO UK643A | Hmm psa new-port terminal (hpnt)'
  },
  {
    milestone: 'WAITING_TRANSSHIP',
    event_time_type: 'actual',
    occurred_at: null,
    timezone: null,
    location: 'Busan, KR',
    vessel: null,
    voyage: null,
    raw_text:
      '14/11/2026 | Busan, KR | Full Transshipment Positioned In | LADEN | Hmm psa new-port terminal (hpnt)'
  },
  {
    milestone: 'WAITING_TRANSSHIP',
    event_time_type: 'actual',
    occurred_at: null,
    timezone: null,
    location: 'Busan, KR',
    vessel: null,
    voyage: null,
    raw_text:
      '14/11/2026 | Busan, KR | Full Transshipment Positioned Out | LADEN | Pusan newport company limited'
  },
  {
    milestone: 'TRANSSHIP_DISCHARGED',
    event_time_type: 'actual',
    occurred_at: null,
    timezone: null,
    location: 'Busan, KR',
    vessel: 'MSC SOFIA',
    voyage: 'GO643N',
    raw_text:
      '07/11/2026 | Busan, KR | Full Transshipment Discharged | MSC SOFIA GO643N | Pusan newport company limited'
  },
  {
    milestone: 'LOADED',
    event_time_type: 'actual',
    occurred_at: null,
    timezone: null,
    location: 'Ningbo, CN',
    vessel: 'MSC SOFIA',
    voyage: 'GO643N',
    raw_text:
      '06/11/2026 | Ningbo, CN | Export Loaded on Vessel | MSC SOFIA GO643N | Gangji terminal (phase iv)'
  },
  {
    milestone: 'GATE_IN',
    event_time_type: 'actual',
    occurred_at: null,
    timezone: null,
    location: 'Ningbo, CN',
    vessel: null,
    voyage: null,
    raw_text: '02/11/2026 | Ningbo, CN | Export received at CY | LADEN | Gangji terminal (phase iv)'
  },
  {
    milestone: 'EMPTY_RELEASED',
    event_time_type: 'actual',
    occurred_at: null,
    timezone: null,
    location: 'Ningbo, CN',
    vessel: null,
    voyage: null,
    raw_text: '21/10/2026 | Ningbo, CN | Empty to Shipper | EMPTY | Gangji terminal (phase iv)'
  }
]

const snapshot = (
  id: number,
  timelineAt: string,
  transportStatus: TransportStatus,
  sourceEvents: SourceEvent[],
  etas: Eta[] = []
): SnapshotTimelineItem => ({
  type: 'snapshot',
  type_rank: 1,
  id,
  timeline_at: timelineAt,
  run_id: 'run_01JBQ8Z6T3K9M2X4V7N1P5R8S0',
  attempt_status: 'success',
  queried_at: timelineAt,
  transport_status: transportStatus,
  etas,
  source_events: sourceEvents
})

const event = (
  id: number,
  timelineAt: string,
  eventType: EventTimelineItem['event_type'],
  eventTimeType: EventTimelineItem['event_time_type'],
  oldValue: Record<string, unknown> | null,
  newValue: Record<string, unknown> | null
): EventTimelineItem => ({
  type: 'event',
  type_rank: 2,
  id,
  timeline_at: timelineAt,
  event_type: eventType,
  event_time_type: eventTimeType,
  old_value: oldValue,
  new_value: newValue,
  from_snapshot_id: null,
  to_snapshot_id: null,
  notification_id: null,
  detected_at: timelineAt
})

const notification = (
  id: number,
  timelineAt: string,
  status: NotificationTimelineItem['status']
): NotificationTimelineItem => ({
  type: 'notification',
  type_rank: 3,
  id,
  timeline_at: timelineAt,
  run_id: 'run_01JBQ8Z6T3K9M2X4V7N1P5R8S0',
  status,
  created_at: timelineAt,
  sent_at: status === 'sent' ? timelineAt : null
})

const eta7Dec = eta('POD_ARRIVAL', '2026-12-07', 'POD ETA 07/12/2026', 'America/Vancouver')
const eta4Dec = eta('POD_ARRIVAL', '2026-12-04', 'POD ETA 04/12/2026', 'America/Vancouver')
const eta2Dec = eta('POD_ARRIVAL', '2026-12-02', 'POD ETA 02/12/2026', 'America/Vancouver')

const liveTimeline: TimelineItem[] = [
  notification(100, '2026-11-22T08:01:05+08:00', 'sent'),
  event(9002, '2026-11-22T08:00:35+08:00', 'ETA_CHANGED', 'planned', { date: '2026-12-04' }, { date: '2026-12-07' }),
  snapshot(5003, '2026-11-22T08:00:35+08:00', 'DEPARTED', liveTrajectory, eta7Dec ? [eta7Dec] : []),
  event(
    9001,
    '2026-11-21T10:12:00+08:00',
    'STATUS_CHANGED',
    'actual',
    { transport_status: 'DEPARTED' },
    { transport_status: 'TRANSSHIP_LOADED' }
  ),
  snapshot(5002, '2026-11-21T10:11:40+08:00', 'TRANSSHIP_LOADED', liveTrajectory.slice(1), eta4Dec ? [eta4Dec] : []),
  event(9000, '2026-11-19T07:30:00+08:00', 'ETA_CHANGED', 'cancelled', { date: '2026-12-02' }, { date: '2026-12-04' }),
  snapshot(5001, '2026-11-19T07:29:50+08:00', 'DEPARTED', liveTrajectory.slice(4), eta2Dec ? [eta2Dec] : [])
]

function syntheticTimeline(container: Container): TimelineItem[] {
  const at = container.last_success_at ?? container.updated_at
  const items: TimelineItem[] = []
  const notes: NotificationTimelineItem[] = []
  if (container.transport_status === 'PICKED_UP') {
    notes.push(notification(200 + container.id, at, 'sent'))
  }
  items.push(...notes)
  if (container.tracked_eta?.date) {
    items.push(
      event(
        300 + container.id,
        at,
        'ETA_CHANGED',
        'planned',
        { date: '2026-11-18' },
        { date: container.tracked_eta.date }
      )
    )
  }
  items.push(
    snapshot(
      400 + container.id,
      at,
      container.transport_status,
      container.tracked_eta?.date
        ? [
            {
              milestone: 'ARRIVED_POD',
              event_time_type: 'planned',
              occurred_at: null,
              timezone: null,
              location: container.pod,
              vessel: container.vessel,
              voyage: container.voyage,
              raw_text: `${container.tracked_eta.date} | ${container.pod ?? '-'} | Estimated Time of Arrival`
            }
          ]
        : [],
      container.tracked_eta ? [container.tracked_eta] : []
    )
  )
  return items
}

const timelines = new Map<number, TimelineItem[]>(
  containers.map((container) => [
    container.id,
    container.id === 10 ? liveTimeline : syntheticTimeline(container)
  ])
)

function compareTimeline(left: TimelineItem, right: TimelineItem): number {
  const byTime = right.timeline_at.localeCompare(left.timeline_at)
  if (byTime !== 0) {
    return byTime
  }
  if (right.type_rank !== left.type_rank) {
    return right.type_rank - left.type_rank
  }
  return right.id - left.id
}

export function timelineResponse(
  containerId: number,
  types: Array<'snapshot' | 'event' | 'notification'> | undefined,
  page: number,
  pageSize: number
): TimelineResponse | null {
  const all = timelines.get(containerId)
  if (!all) {
    return null
  }
  const selected = types && types.length > 0 ? new Set(types) : null
  const filtered = all
    .filter((item) => !selected || selected.has(item.type))
    .sort(compareTimeline)
  const start = (page - 1) * pageSize
  return {
    container_id: containerId,
    items: filtered.slice(start, start + pageSize),
    page,
    page_size: pageSize,
    total: filtered.length
  }
}

export function statsOverview(): StatsOverview {
  return {
    unit: 'container',
    definitions: {
      arriving_soon_days: 7,
      normal_excludes_arriving_soon: true,
      tracking_issue_overlaps: true,
      tracking_issue_includes_no_eta: true
    },
    total_active: 7,
    normal: 3,
    arriving_soon: 1,
    delayed: 2,
    tracking_issue: 2
  }
}
