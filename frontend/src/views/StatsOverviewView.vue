<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import { getOrders } from '@/api/orders'
import type { TransportStatus } from '@/api/types'
import StatCard from '@/components/StatCard.vue'
import { useStatsStore } from '@/stores/stats'
import {
  TRANSPORT_STATUS_OPTIONS,
  formatDateTime,
  statusLabel,
  statusTagType
} from '@/utils/format'

const stats = useStatsStore()
const distribution = ref<Array<{ status: TransportStatus; count: number }>>([])
const distributionLoading = ref(false)
const distributionError = ref('')

const overview = computed(() => stats.overview)

const cards = computed(() => {
  const data = stats.overview
  return [
    { label: '在跟踪箱号', value: data?.total_active ?? '—', hint: '仅统计 is_active=true', tone: 'slate' as const },
    { label: '正常', value: data?.normal ?? '—', hint: '不含即将到港', tone: 'green' as const },
    { label: '即将到港', value: data?.arriving_soon ?? '—', hint: `${data?.definitions.arriving_soon_days ?? 7} 天内`, tone: 'blue' as const },
    { label: '延误', value: data?.delayed ?? '—', hint: 'ETA 风险为延误', tone: 'red' as const },
    { label: '数据异常', value: data?.tracking_issue ?? '—', hint: '可与前三类重叠', tone: 'amber' as const }
  ]
})

const buckets = computed(() => {
  const data = stats.overview
  if (!data) {
    return []
  }
  return [
    { label: '正常', value: data.normal, color: '#22c55e', hint: '不含即将到港' },
    { label: '即将到港', value: data.arriving_soon, color: '#3b82f6', hint: `${data.definitions.arriving_soon_days} 天内` },
    { label: '延误', value: data.delayed, color: '#ef4444', hint: 'ETA 风险为 DELAYED' },
    { label: '数据异常', value: data.tracking_issue, color: '#f59e0b', hint: '可与其他类重叠' }
  ]
})

const distributionTotal = computed(() =>
  distribution.value.reduce((sum, item) => sum + item.count, 0)
)

function percent(value: number, total: number): number {
  return total > 0 ? Math.round((value / total) * 100) : 0
}

async function loadOverview(force = false): Promise<void> {
  await stats.load(force).catch(() => undefined)
}

async function loadDistribution(): Promise<void> {
  distributionLoading.value = true
  distributionError.value = ''
  try {
    const response = await getOrders({ page: 1, page_size: 100, sort: 'updated_desc' })
    const counts = new Map<TransportStatus, number>()
    for (const order of response.items) {
      for (const option of TRANSPORT_STATUS_OPTIONS) {
        const count = order.summary.transport_status_counts[option.value] ?? 0
        if (count > 0) {
          counts.set(option.value, (counts.get(option.value) ?? 0) + count)
        }
      }
    }
    distribution.value = [...counts.entries()]
      .map(([status, count]) => ({ status, count }))
      .sort((left, right) => right.count - left.count)
  } catch {
    distribution.value = []
    distributionError.value = '状态分布加载失败'
  } finally {
    distributionLoading.value = false
  }
}

function refresh(): void {
  void loadOverview(true)
  void loadDistribution()
}

onMounted(() => {
  void loadOverview()
  void loadDistribution()
})
</script>

<template>
  <div class="page">
    <section class="page-head">
      <div>
        <h1>统计概览</h1>
        <p>四张统计卡按全局口径统计箱号；状态分布按全部订单汇总。</p>
      </div>
      <el-button :icon="Refresh" :loading="stats.loading" @click="refresh">刷新</el-button>
    </section>

    <section class="stat-strip wide">
      <StatCard
        v-for="card in cards"
        :key="card.label"
        :label="card.label"
        :value="card.value"
        :hint="card.hint"
        :tone="card.tone"
      />
    </section>

    <div class="overview-grid">
      <section class="panel">
        <header class="panel-head">
          <div>
            <h2>ETA 风险分布</h2>
            <p>分母为在跟踪箱号 {{ overview?.total_active ?? 0 }} 个；数据异常与其他类别重叠，四类之和不等于总数。</p>
          </div>
          <span class="muted small">更新于 {{ formatDateTime(overview ? new Date(stats.loadedAt).toISOString() : null) }}</span>
        </header>
        <div class="bar-list">
          <div v-for="bucket in buckets" :key="bucket.label" class="bar-row">
            <div class="bar-meta">
              <span>{{ bucket.label }}</span>
              <span class="muted small">{{ bucket.hint }}</span>
              <strong>{{ bucket.value }}</strong>
            </div>
            <div class="bar-track">
              <div
                class="bar-fill"
                :style="{ width: `${percent(bucket.value, overview?.total_active ?? 0)}%`, background: bucket.color }"
              />
            </div>
            <span class="bar-percent">{{ percent(bucket.value, overview?.total_active ?? 0) }}%</span>
          </div>
        </div>
      </section>

      <section class="panel">
        <header class="panel-head">
          <div>
            <h2>运输状态分布</h2>
            <p>按全部订单的箱号数量汇总，包含已提箱与在途状态。</p>
          </div>
        </header>
        <div v-loading="distributionLoading" class="status-distribution">
          <el-empty v-if="!distributionLoading && distribution.length === 0" :description="distributionError || '暂无数据'" />
          <div v-for="item in distribution" :key="item.status" class="bar-row">
            <div class="bar-meta">
              <el-tag :type="statusTagType(item.status)" effect="light" size="small">
                {{ statusLabel(item.status) }}
              </el-tag>
              <strong>{{ item.count }}</strong>
            </div>
            <div class="bar-track">
              <div
                class="bar-fill"
                :style="{
                  width: `${percent(item.count, distributionTotal)}%`,
                  background: statusTagType(item.status) === 'success' ? '#22c55e' : statusTagType(item.status) === 'warning' ? '#f59e0b' : statusTagType(item.status) === 'danger' ? '#ef4444' : statusTagType(item.status) === 'primary' ? '#3b82f6' : '#94a3b8'
                }"
              />
            </div>
            <span class="bar-percent">{{ percent(item.count, distributionTotal) }}%</span>
          </div>
        </div>
      </section>
    </div>

    <section class="panel definitions">
      <h2>统计口径</h2>
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="正常">ETA 风险为 NORMAL 或 EARLY，且不属于即将到港</el-descriptions-item>
        <el-descriptions-item label="即将到港">
          ETA 风险为 NORMAL，且监控节点 ETA 在未来 {{ overview?.definitions.arriving_soon_days ?? 7 }} 天内
        </el-descriptions-item>
        <el-descriptions-item label="延误">ETA 风险为 DELAYED</el-descriptions-item>
        <el-descriptions-item label="数据异常">
          数据健康不为 OK，或 ETA 风险为 NO_ETA；与其他类别重叠，仅单列统计
        </el-descriptions-item>
      </el-descriptions>
    </section>
  </div>
</template>
