import { defineStore } from 'pinia'

import { getStatsOverview } from '@/api/orders'
import type { StatsOverview } from '@/api/types'

const CACHE_TTL_MS = 30_000

/**
 * The global overview counters are the only server data cached in Pinia
 * (§5.1): both the order list header and the stats page read the same
 * snapshot instead of recomputing from a page of orders.
 */
export const useStatsStore = defineStore('stats', {
  state: () => ({
    overview: null as StatsOverview | null,
    loadedAt: 0,
    loading: false
  }),
  actions: {
    async load(force = false): Promise<StatsOverview> {
      const fresh = Date.now() - this.loadedAt < CACHE_TTL_MS
      if (!force && this.overview && fresh) {
        return this.overview
      }
      this.loading = true
      try {
        this.overview = await getStatsOverview()
        this.loadedAt = Date.now()
        return this.overview
      } finally {
        this.loading = false
      }
    }
  }
})
