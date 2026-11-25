<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'

import { getOrders } from '@/api/orders'
import type {
  EtaRisk,
  OrderListItem,
  TrackingHealth,
  TransportStatus
} from '@/api/types'
import OrderContainersDrawer from '@/components/OrderContainersDrawer.vue'
import StatCard from '@/components/StatCard.vue'
import { useStatsStore } from '@/stores/stats'
import {
  ETA_RISK_OPTIONS,
  TRACKING_HEALTH_OPTIONS,
  TRANSPORT_STATUS_OPTIONS,
  etaRangeText,
  formatDateTime,
  healthLabel,
  healthTagType,
  relativeTime,
  riskLabel,
  riskTagType,
  statusTagType
} from '@/utils/format'

const router = useRouter()
const stats = useStatsStore()

const filters = reactive({
  keyword: '',
  transport_status: '' as TransportStatus | '',
  eta_risk: '' as EtaRisk | '',
  tracking_health: '' as TrackingHealth | ''
})
const sort = ref<'risk_desc' | 'updated_desc'>('risk_desc')
const page = ref(1)
const pageSize = ref(3)
const rows = ref<OrderListItem[]>([])
const total = ref(0)
const loading = ref(false)
const drawerOpen = ref(false)
const drawerOrderId = ref<number | null>(null)

const statCards = computed(() => {
  const overview = stats.overview
  return [
    { label: '在跟踪箱号', value: overview?.total_active ?? '—', tone: 'slate' as const },
    { label: '正常', value: overview?.normal ?? '—', tone: 'green' as const },
    { label: '即将到港', value: overview?.arriving_soon ?? '—', tone: 'blue' as const },
    { label: '延误', value: overview?.delayed ?? '—', tone: 'red' as const },
    { label: '数据异常', value: overview?.tracking_issue ?? '—', tone: 'amber' as const }
  ]
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await getOrders({
      keyword: filters.keyword.trim() || undefined,
      transport_status: filters.transport_status || undefined,
      eta_risk: filters.eta_risk || undefined,
      tracking_health: filters.tracking_health || undefined,
      sort: sort.value,
      page: page.value,
      page_size: pageSize.value
    })
    rows.value = response.items
    total.value = response.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function reset(): void {
  filters.keyword = ''
  filters.transport_status = ''
  filters.eta_risk = ''
  filters.tracking_health = ''
  sort.value = 'risk_desc'
  page.value = 1
  void load()
}

// Element Plus types table slot rows as DefaultRow; the data is the API item.
function asOrder(raw: unknown): OrderListItem {
  return raw as OrderListItem
}

function openOrder(raw: unknown): void {
  drawerOrderId.value = asOrder(raw).id
  drawerOpen.value = true
}

function statusChips(raw: unknown) {
  const row = asOrder(raw)
  return TRANSPORT_STATUS_OPTIONS.filter(
    (option) => row.summary.transport_status_counts[option.value] > 0
  )
    .slice(0, 4)
    .map((option) => ({
      ...option,
      count: row.summary.transport_status_counts[option.value]
    }))
}

function healthIssues(raw: unknown): number {
  const row = asOrder(raw)
  return (
    row.summary.tracking_health_counts.QUERY_FAILED +
    row.summary.tracking_health_counts.PARSE_ERROR +
    row.summary.tracking_health_counts.STALE
  )
}

function openStats(): void {
  void router.push({ name: 'stats' })
}

watch([page, pageSize, sort], () => {
  void load()
})

onMounted(() => {
  void stats.load().catch(() => undefined)
  void load()
})
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>订单列表</h1>
        <p>按订单号或箱号搜索，查看每个订单的箱号状态与 ETA 范围。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </section>

    <section class="stat-strip" @click="openStats">
      <StatCard
        v-for="card in statCards"
        :key="card.label"
        :label="card.label"
        :value="card.value"
        :tone="card.tone"
        clickable
      />
    </section>

    <section class="panel filter-panel">
      <el-input
        v-model="filters.keyword"
        placeholder="订单号 / 箱号，例如 PO-001 或 MSDU7172233"
        clearable
        class="filter-keyword"
        :prefix-icon="Search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="filters.transport_status" placeholder="运输状态" clearable class="filter-select" @change="search">
        <el-option v-for="option in TRANSPORT_STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-select v-model="filters.eta_risk" placeholder="ETA 风险" clearable class="filter-select" @change="search">
        <el-option v-for="option in ETA_RISK_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-select v-model="filters.tracking_health" placeholder="数据健康" clearable class="filter-select" @change="search">
        <el-option v-for="option in TRACKING_HEALTH_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-select v-model="sort" class="filter-select" @change="search">
        <el-option label="风险优先" value="risk_desc" />
        <el-option label="最近更新" value="updated_desc" />
      </el-select>
      <el-button type="primary" @click="search">筛选</el-button>
      <el-button text @click="reset">重置</el-button>
    </section>

    <section class="panel table-panel">
      <el-table v-loading="loading" :data="rows" row-key="id" size="default" stripe>
        <el-table-column label="订单号" min-width="150">
          <template #default="{ row }">
            <button class="link-button mono" type="button" @click="openOrder(row)">
              {{ row.order_no }}
            </button>
            <div class="muted small">{{ row.container_count }} 个箱号</div>
          </template>
        </el-table-column>
        <el-table-column label="状态分布" min-width="260">
          <template #default="{ row }">
            <div class="chip-row">
              <el-tag
                v-for="chip in statusChips(row)"
                :key="chip.value"
                :type="statusTagType(chip.value)"
                effect="light"
                size="small"
              >
                {{ chip.label }} × {{ chip.count }}
              </el-tag>
              <el-tag v-if="healthIssues(row) > 0" type="danger" effect="plain" size="small">
                数据异常 × {{ healthIssues(row) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="ETA 范围" min-width="210">
          <template #default="{ row }">
            <div class="eta-range-cell">{{ etaRangeText(row.eta_range) }}</div>
            <div class="muted small">{{ row.eta_range.count }} 箱有 ETA</div>
          </template>
        </el-table-column>
        <el-table-column label="最高风险" width="106">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.highest_risk)" effect="light">
              {{ riskLabel(row.highest_risk) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据健康" width="106">
          <template #default="{ row }">
            <el-tag
              v-if="healthIssues(row) > 0"
              :type="healthTagType('QUERY_FAILED')"
              effect="plain"
            >
              有异常
            </el-tag>
            <el-tag v-else :type="healthTagType('OK')" effect="plain">
              {{ healthLabel('OK') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">
            <div>{{ formatDateTime(row.updated_at) }}</div>
            <div class="muted small">{{ relativeTime(row.updated_at) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="112" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openOrder(row)">查看箱号</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的订单，试试清空筛选条件" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[3, 5, 10, 20]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </section>

    <OrderContainersDrawer v-model="drawerOpen" :order-id="drawerOrderId" />
  </div>
</template>
