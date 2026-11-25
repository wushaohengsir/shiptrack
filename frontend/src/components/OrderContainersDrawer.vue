<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { getOrder } from '@/api/orders'
import type { OrderDetail } from '@/api/types'
import {
  formatDateTime,
  formatEta,
  formatEtaRange,
  healthLabel,
  healthTagType,
  riskLabel,
  riskTagType,
  statusLabel,
  statusTagType
} from '@/utils/format'

const props = defineProps<{
  modelValue: boolean
  orderId: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const router = useRouter()
const detail = ref<OrderDetail | null>(null)
const loading = ref(false)

watch(
  () => [props.modelValue, props.orderId] as const,
  ([open, orderId]) => {
    if (!open || !orderId) {
      return
    }
    loading.value = true
    detail.value = null
    getOrder(orderId)
      .then((response) => {
        detail.value = response
      })
      .catch(() => {
        detail.value = null
      })
      .finally(() => {
        loading.value = false
      })
  },
  { immediate: true }
)

function onOpenChange(value: boolean): void {
  emit('update:modelValue', value)
}

function openTimeline(containerId: number, containerNo: string): void {
  if (!detail.value) {
    return
  }
  emit('update:modelValue', false)
  void router.push({
    name: 'container-timeline',
    params: { id: containerId },
    query: { orderId: detail.value.id, containerNo }
  })
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="订单箱号"
    size="760px"
    :destroy-on-close="true"
    @update:model-value="onOpenChange"
  >
    <div v-loading="loading" class="drawer-body">
      <template v-if="detail">
        <div class="drawer-order">
          <div>
            <h3>{{ detail.title }}</h3>
            <p>
              <span class="mono">{{ detail.order_no }}</span>
              · {{ detail.container_count }} 个箱号
            </p>
          </div>
          <div class="drawer-order-meta">
            <el-tag :type="riskTagType(detail.highest_risk)" effect="light">
              最高风险：{{ riskLabel(detail.highest_risk) }}
            </el-tag>
            <span>ETA 范围：{{ formatEtaRange(detail.eta_range) }}</span>
          </div>
        </div>

        <el-table :data="detail.containers" size="small" class="drawer-table">
          <el-table-column prop="container_no" label="箱号" min-width="140" />
          <el-table-column label="状态" width="112">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.transport_status)" effect="light" size="small">
                {{ statusLabel(row.transport_status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="监控 ETA" min-width="120">
            <template #default="{ row }">{{ formatEta(row.tracked_eta) }}</template>
          </el-table-column>
          <el-table-column prop="location" label="当前位置" min-width="118" />
          <el-table-column label="风险 / 健康" min-width="150">
            <template #default="{ row }">
              <el-tag :type="riskTagType(row.eta_risk)" size="small" effect="plain">
                {{ riskLabel(row.eta_risk) }}
              </el-tag>
              <el-tag
                :type="healthTagType(row.tracking_health)"
                size="small"
                effect="plain"
                class="ml-4"
              >
                {{ healthLabel(row.tracking_health) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新于" width="146">
            <template #default="{ row }">{{ formatDateTime(row.updated_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="92" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openTimeline(row.id, row.container_no)">
                时间线
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else-if="!loading" description="订单不存在" />
    </div>
  </el-drawer>
</template>
