import type {
  PageResponse,
  OrderListItem,
  EtaRisk,
  TrackingHealth,
  TransportStatus
} from '@/api/types'
import {
  orderDetail,
  orderListItem,
  orders,
  statsOverview,
  timelineResponse,
  containers
} from './data'

/**
 * Mock backend. It is the only place that knows the fixtures: views call the
 * same `api/orders.ts` functions in both modes, and switching
 * `VITE_API_MODE=http` replaces this file with real HTTP without touching a
 * component.
 */

export class MockHttpError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'MockHttpError'
    this.status = status
    this.code = code
  }
}

type QueryValue = string | number | boolean | null | undefined
type Query = Record<string, QueryValue>

const DEFAULT_PAGE_SIZE = 20

function readString(query: Query, key: string): string | undefined {
  const value = query[key]
  if (value === undefined || value === null) {
    return undefined
  }
  const text = String(value).trim()
  return text === '' ? undefined : text
}

function readNumber(query: Query, key: string, fallback: number): number {
  const value = Number(readString(query, key))
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : fallback
}

function paginate<T>(items: T[], page: number, pageSize: number): PageResponse<T> {
  const safeSize = Math.min(Math.max(pageSize, 1), 100)
  const safePage = Math.max(page, 1)
  const start = (safePage - 1) * safeSize
  return {
    items: items.slice(start, start + safeSize),
    page: safePage,
    page_size: safeSize,
    total: items.length
  }
}

function orderMatches(order: OrderListItem, keyword: string): boolean {
  const needle = keyword.toLowerCase()
  if (order.order_no.toLowerCase().includes(needle)) {
    return true
  }
  return containers.some(
    (container) =>
      container.order_id === order.id && container.container_no.toLowerCase().includes(needle)
  )
}

function riskWeight(risk: OrderListItem['highest_risk']): number {
  switch (risk) {
    case 'NO_ETA':
      return 3
    case 'DELAYED':
      return 2
    case 'EARLY':
      return 1
    default:
      return 0
  }
}

function listOrders(query: Query): PageResponse<OrderListItem> {
  const keyword = readString(query, 'keyword')
  const transportStatus = readString(query, 'transport_status') as TransportStatus | undefined
  const etaRisk = readString(query, 'eta_risk') as EtaRisk | undefined
  const trackingHealth = readString(query, 'tracking_health') as TrackingHealth | undefined
  const sort = readString(query, 'sort') ?? 'risk_desc'
  const page = readNumber(query, 'page', 1)
  const pageSize = readNumber(query, 'page_size', DEFAULT_PAGE_SIZE)

  const filtered = orders
    .filter((order) => order.archived_at === null)
    .map(orderListItem)
    .filter((order) => !keyword || orderMatches(order, keyword))
    .filter(
      (order) =>
        !transportStatus || order.summary.transport_status_counts[transportStatus] > 0
    )
    .filter((order) => !etaRisk || order.summary.eta_risk_counts[etaRisk] > 0)
    .filter(
      (order) => !trackingHealth || order.summary.tracking_health_counts[trackingHealth] > 0
    )

  filtered.sort((left, right) => {
    if (sort === 'updated_desc') {
      return right.updated_at.localeCompare(left.updated_at)
    }
    const byRisk = riskWeight(right.highest_risk) - riskWeight(left.highest_risk)
    if (byRisk !== 0) {
      return byRisk
    }
    const byUpdated = right.updated_at.localeCompare(left.updated_at)
    return byUpdated !== 0 ? byUpdated : right.id - left.id
  })

  return paginate(filtered, page, pageSize)
}

function readTypes(query: Query): Array<'snapshot' | 'event' | 'notification'> | undefined {
  const raw = readString(query, 'types')
  if (!raw) {
    return undefined
  }
  const allowed = new Set(['snapshot', 'event', 'notification'])
  const types = raw
    .split(',')
    .map((type) => type.trim())
    .filter((type): type is 'snapshot' | 'event' | 'notification' => allowed.has(type))
  return types.length > 0 ? types : undefined
}

export async function mockRequest<T>(method: string, path: string, query: Query = {}): Promise<T> {
  // A short latency keeps loading/skeleton states honest in the demo.
  await new Promise((resolve) => setTimeout(resolve, 120))

  if (method !== 'GET') {
    throw new MockHttpError(405, 'METHOD_NOT_ALLOWED', 'Mock 层仅实现 GET 接口')
  }

  if (path === '/orders') {
    return listOrders(query) as T
  }

  const orderMatch = /^\/orders\/(\d+)$/.exec(path)
  if (orderMatch) {
    const detail = orderDetail(Number(orderMatch[1]))
    if (!detail) {
      throw new MockHttpError(404, 'NOT_FOUND', '订单不存在')
    }
    return detail as T
  }

  const timelineMatch = /^\/containers\/(\d+)\/timeline$/.exec(path)
  if (timelineMatch) {
    const page = readNumber(query, 'page', 1)
    const pageSize = Math.min(readNumber(query, 'page_size', DEFAULT_PAGE_SIZE), 100)
    const response = timelineResponse(
      Number(timelineMatch[1]),
      readTypes(query),
      page,
      pageSize
    )
    if (!response) {
      throw new MockHttpError(404, 'NOT_FOUND', '箱号不存在')
    }
    return response as T
  }

  if (path === '/stats/overview') {
    return statsOverview() as T
  }

  throw new MockHttpError(404, 'NOT_FOUND', `Mock 未实现的接口：${path}`)
}
