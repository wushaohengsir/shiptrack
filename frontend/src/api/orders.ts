import { apiGet, type Query } from './client'
import type {
  OrderDetail,
  OrderListQuery,
  OrderListItem,
  PageResponse,
  StatsOverview,
  TimelineQuery,
  TimelineResponse
} from './types'

function orderQuery(query: OrderListQuery): Query {
  return {
    keyword: query.keyword,
    transport_status: query.transport_status,
    eta_risk: query.eta_risk,
    tracking_health: query.tracking_health,
    include_archived: query.include_archived,
    sort: query.sort,
    page: query.page,
    page_size: query.page_size
  }
}

/** `GET /orders` (§5.2). */
export function getOrders(query: OrderListQuery = {}): Promise<PageResponse<OrderListItem>> {
  return apiGet<PageResponse<OrderListItem>>('/orders', orderQuery(query))
}

/** `GET /orders/{id}` (§5.3). */
export function getOrder(orderId: number): Promise<OrderDetail> {
  return apiGet<OrderDetail>(`/orders/${orderId}`)
}

/** `GET /containers/{id}/timeline` (§5.12). */
export function getContainerTimeline(
  containerId: number,
  query: TimelineQuery = {}
): Promise<TimelineResponse> {
  return apiGet<TimelineResponse>(`/containers/${containerId}/timeline`, {
    types: query.types?.length ? query.types.join(',') : undefined,
    page: query.page,
    page_size: query.page_size
  })
}

/** `GET /stats/overview` (§5.19). */
export function getStatsOverview(): Promise<StatsOverview> {
  return apiGet<StatsOverview>('/stats/overview')
}
