<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: number | string
    hint?: string
    tone?: 'slate' | 'green' | 'blue' | 'red' | 'amber' | 'violet'
    clickable?: boolean
  }>(),
  {
    hint: '',
    tone: 'slate',
    clickable: false
  }
)
</script>

<template>
  <div class="stat-card" :class="[`tone-${tone}`, { clickable }]">
    <span class="stat-label">{{ label }}</span>
    <strong class="stat-value">{{ value }}</strong>
    <span v-if="hint" class="stat-hint">{{ hint }}</span>
  </div>
</template>
