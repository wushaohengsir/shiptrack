<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { List, SwitchButton, TrendCharts } from '@element-plus/icons-vue'

import { apiMode } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeNav = computed(() => (route.path.startsWith('/stats') ? '/stats' : '/orders'))

function logout(): void {
  auth.clear()
  void router.push({ name: 'login' })
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <button class="brand" type="button" @click="router.push({ name: 'orders' })">
        <span class="brand-mark">ST</span>
        <span class="brand-text">
          <strong>船期追踪</strong>
          <small>ShipTrack · MSC 集装箱动态</small>
        </span>
      </button>

      <nav class="app-nav">
        <router-link to="/orders" class="nav-link" :class="{ active: activeNav === '/orders' }">
          <el-icon><List /></el-icon>
          订单列表
        </router-link>
        <router-link to="/stats" class="nav-link" :class="{ active: activeNav === '/stats' }">
          <el-icon><TrendCharts /></el-icon>
          统计概览
        </router-link>
      </nav>

      <div class="app-meta">
        <el-tag :type="apiMode === 'mock' ? 'warning' : 'success'" effect="plain" size="small">
          {{ apiMode === 'mock' ? 'MOCK 数据' : '实时 API' }}
        </el-tag>
        <el-button text size="small" @click="logout">
          <el-icon><SwitchButton /></el-icon>
          退出
        </el-button>
      </div>
    </header>

    <main class="app-main">
      <router-view />
    </main>

    <footer class="app-footer">
      原型骨架 · 数据仅用于界面联调 · 时刻统一按 Asia/Shanghai 展示
    </footer>
  </div>
</template>
