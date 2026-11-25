import { describe, expect, it } from 'vitest'

import type { OrderListItem, PageResponse, StatsOverview, TimelineResponse } from '@/api/types'
import { mockRequest } from './index'

describe('mock backend', () => {
  it('paginates orders with the documented envelope', async () => {
    const page = await mockRequest<PageResponse<OrderListItem>>('GET', '/orders', {
      page: 1,
      page_size: 3
    })
    expect(page.total).toBe(4)
    expect(page.page).toBe(1)
    expect(page.page_size).toBe(3)
    expect(page.items).toHaveLength(3)
    // risk_desc: NO_ETA first, then DELAYED by updated_at.
    expect(page.items[0].order_no).toBe('PO-004')
    expect(page.items[0].highest_risk).toBe('NO_ETA')
    expect(page.items[1].order_no).toBe('PO-003')
  })

  it('filters by container number and by transport status', async () => {
    const byContainer = await mockRequest<PageResponse<OrderListItem>>('GET', '/orders', {
      keyword: 'MSDU7172233'
    })
    expect(byContainer.total).toBe(1)
    expect(byContainer.items[0].order_no).toBe('PO-001')

    const pickedUp = await mockRequest<PageResponse<OrderListItem>>('GET', '/orders', {
      transport_status: 'PICKED_UP'
    })
    expect(pickedUp.items.map((item) => item.order_no)).toEqual(['PO-002'])
  })

  it('returns a zero-filled status summary for every order', async () => {
    const page = await mockRequest<PageResponse<OrderListItem>>('GET', '/orders', {
      keyword: 'PO-001'
    })
    const summary = page.items[0].summary
    expect(summary.transport_status_counts.PENDING).toBe(0)
    expect(summary.transport_status_counts.DEPARTED).toBe(1)
    expect(summary.transport_status_counts.TRANSSHIP_LOADED).toBe(1)
    expect(summary.eta_risk_counts.DELAYED).toBe(1)
    expect(Object.keys(summary.transport_status_counts)).toHaveLength(16)
  })

  it('returns the live container timeline newest-first with the documented sort keys', async () => {
    const response = await mockRequest<TimelineResponse>('GET', '/containers/10/timeline', {
      page: 1,
      page_size: 20
    })
    expect(response.container_id).toBe(10)
    expect(response.total).toBe(7)
    expect(response.items[0].type).toBe('notification')
    expect(response.items[1].type).toBe('event')
    expect(response.items[1]).toMatchObject({ event_type: 'ETA_CHANGED', event_time_type: 'planned' })
    expect(response.items[2].type).toBe('snapshot')

    const snapshot = response.items[2]
    if (snapshot.type !== 'snapshot') {
      throw new Error('expected a snapshot item')
    }
    expect(snapshot.etas?.[0]?.date).toBe('2026-12-07')
    expect(snapshot.source_events?.[0]?.raw_text).toContain('Vancouver, CA')
  })

  it('filters timeline types', async () => {
    const response = await mockRequest<TimelineResponse>('GET', '/containers/10/timeline', {
      types: 'event'
    })
    expect(response.total).toBe(3)
    expect(response.items.every((item) => item.type === 'event')).toBe(true)
  })

  it('returns the overview counters', async () => {
    const overview = await mockRequest<StatsOverview>('GET', '/stats/overview')
    expect(overview.unit).toBe('container')
    expect(overview.total_active).toBe(7)
    expect(overview.normal + overview.arriving_soon + overview.delayed).toBe(6)
    expect(overview.tracking_issue).toBe(2)
  })

  it('rejects an unknown endpoint with a typed 404', async () => {
    await expect(mockRequest('GET', '/not-implemented')).rejects.toMatchObject({
      status: 404,
      code: 'NOT_FOUND'
    })
  })
})
