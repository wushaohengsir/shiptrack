<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'

import { getContainerTimeline, getOrder } from '@/api/orders'
import type {
  Container,
  Eta,
  EventTimelineItem,
  SnapshotTimelineItem,
  SourceEvent,
  TimelineItem
} from '@/api/types'
import {
  attemptStatusLabel,
  describeValue,
  eventTypeLabel,
  formatDate,
  formatDateTime,
  formatEta,
  healthLabel,
  healthTagType,
  leadingCarrierDate,
  notificationStatusLabel,
  riskLabel,
  riskTagType,
  statusLabel,
  statusTagType,
  timeTypeLabel,
  timeTypeTagType
} from '@/utils/format'

const props = defineProps<{
  containerId: number
  orderId: number | null
  containerNo: string
}>()

const router = useRouter()

const container = ref<Container | null>(null)
const orderNo = ref('')
const orderTitle = ref('')
const items = ref<TimelineItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const contextLoading = ref(false)
const selectedTypes = ref<Array<'snapshot' | 'event' | 'notification'>>([
  'event',
  'snapshot',
  'notification'
])
const timeFilter = ref<'all' | 'planned' | 'actual' | 'cancelled'>('all')

const displayNo = computed(
  () => container.value?.container_no ?? (props.containerNo || `箱号 #${props.containerId}`)
)

const latestSnapshot = computed(
  () => items.value.find((item): item is SnapshotTimelineItem => item.type === 'snapshot')
)
const latestEvent = computed(
  () => items.value.find((item): item is EventTimelineItem => item.type === 'event')
)
const currentStatus = computed(
  () => container.value?.transport_status ?? latestSnapshot.value?.transport_status ?? null
)
const currentEta = computed<Eta | null>(
  () => container.value?.tracked_eta ?? latestSnapshot.value?.etas?.[0] ?? null
)

const visibleItems = computed(() =>
  items.value.filter(
    (item) =>
      item.type !== 'event' ||
      timeFilter.value === 'all' ||
      item.event_time_type === timeFilter.value
  )
)

function nestedEvents(item: TimelineItem): SourceEvent[] {
  if (item.type !== 'snapshot') {
    return []
  }
  const events = item.source_events ?? []
  return timeFilter.value === 'all'
    ? events
    : events.filter((event) => event.event_time_type === timeFilter.value)
}

function snapshotEtas(item: TimelineItem): Eta[] {
  return item.type === 'snapshot' ? item.etas ?? [] : []
}

function itemColor(item: TimelineItem): string {
  if (item.type === 'event') {
    if (item.event_time_type === 'actual') {
      return '#22c55e'
    }
    if (item.event_time_type === 'planned') {
      return '#3b82f6'
    }
    return '#a8abb2'
  }
  return item.type === 'snapshot' ? '#cbd5e1' : '#8b5cf6'
}

function sourceClass(event: SourceEvent): string {
  return `source-${event.event_time_type ?? 'unknown'}`
}

function sourceDate(event: SourceEvent): string {
  return leadingCarrierDate(event.raw_text) ?? formatDate(event.occurred_at)
}

async function loadContext(): Promise<void> {
  if (!props.orderId) {
    return
  }
  contextLoading.value = true
  try {
    const detail = await getOrder(props.orderId)
    orderNo.value = detail.order_no
    orderTitle.value = detail.title
    container.value = detail.containers.find((item) => item.id === props.containerId) ?? null
  } catch {
    container.value = null
  } finally {
    contextLoading.value = false
  }
}

async function loadTimeline(): Promise<void> {
  loading.value = true
  try {
    const response = await getContainerTimeline(props.containerId, {
      types: selectedTypes.value,
      page: page.value,
      page_size: pageSize.value
    })
    items.value = response.items
    total.value = response.total
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onTypeChange(value: Array<string | number | boolean>): void {
  const allowed = value.filter(
    (item): item is 'snapshot' | 'event' | 'notification' =>
      item === 'snapshot' || item === 'event' || item === 'notification'
  )
  if (allowed.length === 0) {
    selectedTypes.value = ['event', 'snapshot', 'notification']
    return
  }
  selectedTypes.value = allowed
  page.value = 1
  void loadTimeline()
}

function refresh(): void {
  void loadContext()
  void loadTimeline()
}

function back(): void {
  void router.push({ name: 'orders' })
}

watch(
  () => props.containerId,
  () => {
    page.value = 1
    container.value = null
    items.value = []
    void loadContext()
    void loadTimeline()
  },
  { immediate: true }
)

watch(page, () => {
  void loadTimeline()
})
</script>

<template>
  <div class="page">
    <section class="page-head timeline-head">
      <div class="timeline-title">
        <el-button :icon="ArrowLeft" circle text @click="back" />
        <div>
          <h1 class="mono">{{ displayNo }}</h1>
          <p v-if="orderNo">
            <router-link class="inline-link" :to="{ name: 'orders' }">{{ orderNo }}</router-link>
            · {{ orderTitle }}
          </p>
          <p v-else>箱号时间线 · 查询快照 / 业务事件 / 通知</p>
        </div>
      </div>
      <el-button :icon="Refresh" :loading="loading || contextLoading" @click="refresh">刷新</el-button>
    </section>

    <section v-if="container" class="container-summary panel">
      <div class="summary-tags">
        <el-tag :type="statusTagType(currentStatus)" effect="dark" size="large">
          {{ statusLabel(currentStatus) }}
        </el-tag>
        <el-tag :type="riskTagType(container.eta_risk)" effect="light">ETA：{{ riskLabel(container.eta_risk) }}</el-tag>
        <el-tag :type="healthTagType(container.tracking_health)" effect="light">
          数据：{{ healthLabel(container.tracking_health) }}
        </el-tag>
        <el-tag v-if="!container.is_active" type="info" effect="plain">已停止跟踪</el-tag>
      </div>
      <div class="summary-grid">
        <div>
          <span>监控 ETA</span>
          <strong class="eta-strong">{{ formatEta(currentEta) }}</strong>
          <small>{{ container.tracked_milestone === 'POD_ARRIVAL' ? '卸货港到港' : '最终目的地' }}</small>
        </div>
        <div>
          <span>航线</span>
          <strong>{{ container.pol || '—' }} → {{ container.pod || '—' }}</strong>
          <small>最终目的地：{{ container.final_destination || '—' }}</small>
        </div>
        <div>
          <span>当前位置</span>
          <strong>{{ container.location || '—' }}</strong>
          <small>船名/航次：{{ container.vessel || '—' }} / {{ container.voyage || '—' }}</small>
        </div>
        <div>
          <span>最后成功查询</span>
          <strong>{{ formatDateTime(container.last_success_at) }}</strong>
          <small>箱型：{{ container.container_type }} · {{ container.carrier }}</small>
        </div>
      </div>
    </section>

    <section class="latest-strip panel">
      <div>
        <span>最新状态</span>
        <el-tag :type="statusTagType(currentStatus)" effect="light">
          {{ statusLabel(currentStatus) }}
        </el-tag>
      </div>
      <div>
        <span>最新动态</span>
        <strong>{{ latestEvent ? eventTypeLabel(latestEvent.event_type) : '暂无业务事件' }}</strong>
        <small v-if="latestEvent">{{ formatDateTime(latestEvent.timeline_at) }}</small>
      </div>
      <div>
        <span>最近快照</span>
        <strong>{{ latestSnapshot ? formatDateTime(latestSnapshot.timeline_at) : '—' }}</strong>
        <small v-if="latestSnapshot">{{ attemptStatusLabel(latestSnapshot.attempt_status) }}</small>
      </div>
      <div>
        <span>共</span>
        <strong>{{ total }}</strong>
        <small>条时间线记录</small>
      </div>
    </section>

    <section class="panel timeline-panel">
      <header class="panel-head filters">
        <div class="filter-group">
          <span class="filter-label">记录类型</span>
          <el-checkbox-group
            v-model="selectedTypes"
            size="small"
            @change="onTypeChange"
          >
            <el-checkbox value="event">业务事件</el-checkbox>
            <el-checkbox value="snapshot">查询快照</el-checkbox>
            <el-checkbox value="notification">通知</el-checkbox>
          </el-checkbox-group>
        </div>
        <div class="filter-group">
          <span class="filter-label">事件语义</span>
          <el-radio-group v-model="timeFilter" size="small">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="planned">计划</el-radio-button>
            <el-radio-button value="actual">实际</el-radio-button>
            <el-radio-button value="cancelled">已取消</el-radio-button>
          </el-radio-group>
        </div>
      </header>

      <div v-loading="loading" class="timeline-body">
        <el-empty v-if="!loading && visibleItems.length === 0" description="当前筛选下没有时间线记录" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="item in visibleItems"
            :key="`${item.type}-${item.id}`"
            :timestamp="formatDateTime(item.timeline_at)"
            placement="top"
            :color="itemColor(item)"
            :hollow="item.type === 'snapshot' || (item.type === 'event' && item.event_time_type === 'cancelled')"
          >
            <article v-if="item.type === 'event'" class="timeline-card">
              <header>
                <strong>{{ eventTypeLabel(item.event_type) }}</strong>
                <el-tag :type="timeTypeTagType(item.event_time_type)" size="small" effect="light">
                  {{ timeTypeLabel(item.event_time_type) }}
                </el-tag>
              </header>
              <p class="event-value">
                <span class="muted">{{ describeValue(item.old_value) }}</span>
                <span class="arrow">→</span>
                <strong>{{ describeValue(item.new_value) }}</strong>
              </p>
              <p v-if="item.event_time_type === 'cancelled'" class="cancelled-note">
                已取消：该变化不改变当前状态。
              </p>
              <p class="muted small">检测于 {{ formatDateTime(item.detected_at) }}</p>
            </article>

            <article v-else-if="item.type === 'snapshot'" class="timeline-card">
              <header>
                <strong>查询快照</strong>
                <el-tag :type="statusTagType(item.transport_status)" size="small" effect="light">
                  {{ statusLabel(item.transport_status) }}
                </el-tag>
                <el-tag type="info" size="small" effect="plain">
                  {{ attemptStatusLabel(item.attempt_status) }}
                </el-tag>
              </header>
              <p v-if="snapshotEtas(item).length" class="snapshot-eta">
                <span class="muted">ETA：</span>
                <strong
                  v-for="eta in snapshotEtas(item)"
                  :key="`${eta.milestone}-${eta.date}`"
                  class="eta-chip"
                >
                  {{ formatEta(eta) }}
                </strong>
              </p>
              <p class="muted small">查询于 {{ formatDateTime(item.queried_at) }}</p>

              <div v-if="nestedEvents(item).length" class="source-events">
                <span class="source-title">船司轨迹</span>
                <ul>
                  <li v-for="(event, index) in nestedEvents(item)" :key="index" :class="sourceClass(event)">
                    <span class="source-date mono">{{ sourceDate(event) }}</span>
                    <el-tag :type="timeTypeTagType(event.event_time_type)" size="small" effect="plain">
                      {{ timeTypeLabel(event.event_time_type) }}
                    </el-tag>
                    <span class="source-location">{{ event.location || '—' }}</span>
                    <span class="source-text">{{ event.raw_text }}</span>
                  </li>
                </ul>
              </div>
            </article>

            <article v-else-if="item.type === 'notification'" class="timeline-card">
              <header>
                <strong>通知</strong>
                <el-tag
                  :type="item.status === 'sent' ? 'success' : item.status === 'failed' ? 'danger' : 'info'"
                  size="small"
                  effect="light"
                >
                  {{ notificationStatusLabel(item.status) }}
                </el-tag>
              </header>
              <p class="muted small">创建于 {{ formatDateTime(item.created_at) }}</p>
              <p v-if="item.sent_at" class="muted small">发送于 {{ formatDateTime(item.sent_at) }}</p>
            </article>
          </el-timeline-item>
        </el-timeline>

        <div v-if="total > pageSize" class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            background
          />
        </div>
      </div>
    </section>
  </div>
</template>
