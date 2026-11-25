import { describe, expect, it } from 'vitest'

import type { Eta } from '@/api/types'
import {
  describeValue,
  formatEta,
  formatEtaRange,
  leadingCarrierDate,
  riskTagType,
  statusLabel,
  statusTagType,
  timeTypeLabel
} from './format'

describe('format utilities', () => {
  it('never invents a time for a DATE-precision ETA', () => {
    const eta: Eta = {
      milestone: 'POD_ARRIVAL',
      raw_value: 'POD ETA 07/12/2026',
      date: '2026-12-07',
      time: null,
      timezone: 'America/Vancouver',
      precision: 'DATE',
      utc_at: null
    }
    expect(formatEta(eta)).toBe('2026-12-07')
    expect(formatEta(null)).toBe('暂无 ETA')
  })

  it('reads the carrier day-first date', () => {
    expect(leadingCarrierDate('07/12/2026 | Vancouver, CA | Estimated Time of Arrival')).toBe(
      '2026-12-07'
    )
    expect(leadingCarrierDate('21/11/2026 | Busan, KR | Full Transshipment Loaded')).toBe(
      '2026-11-21'
    )
    expect(leadingCarrierDate('no date')).toBeNull()
  })

  it('formats an ETA range without inventing values', () => {
    expect(formatEtaRange({ earliest: null, latest: null, count: 0 })).toBe('暂无 ETA')
    expect(formatEtaRange({ earliest: '2026-12-07', latest: '2026-12-07', count: 2 })).toBe(
      '2026-12-07（2 箱）'
    )
    expect(formatEtaRange({ earliest: '2026-11-28', latest: '2026-12-12', count: 3 })).toBe(
      '2026-11-28 ~ 2026-12-12（3 箱）'
    )
  })

  it('keeps the status and event semantics distinct', () => {
    expect(statusLabel('PICKED_UP')).toBe('已提箱')
    expect(statusTagType('PICKED_UP')).toBe('success')
    expect(statusTagType('TRANSSHIP_LOADED')).toBe('primary')
    expect(statusTagType('ARRIVED_POD')).toBe('warning')
    expect(riskTagType('DELAYED')).toBe('danger')
    expect(timeTypeLabel('planned')).toBe('计划')
    expect(timeTypeLabel(null)).toBe('未标注')
  })

  it('describes event old/new values', () => {
    expect(describeValue({ date: '2026-12-07' })).toBe('2026-12-07')
    expect(describeValue({ transport_status: 'PICKED_UP' })).toBe('已提箱')
    expect(describeValue(null)).toBe('—')
  })
})
