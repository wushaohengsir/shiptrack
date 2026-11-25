<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Key, Ship } from '@element-plus/icons-vue'

import { apiMode } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const token = ref('')
const error = ref('')

function submit(): void {
  if (!token.value.trim()) {
    error.value = '请输入访问令牌'
    return
  }
  error.value = ''
  auth.setToken(token.value)
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/orders'
  void router.push(redirect)
}

function useDemoToken(): void {
  token.value = 'mock-demo-token'
  submit()
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-head">
        <span class="brand-mark large">ST</span>
        <div>
          <h1>船期追踪</h1>
          <p>输入访问令牌后查看订单与箱号动态</p>
        </div>
      </div>

      <el-alert
        v-if="apiMode === 'mock'"
        type="info"
        :closable="false"
        show-icon
        title="当前为 MOCK 模式"
        description="未接后端；任意非空令牌都可进入，或直接使用演示令牌。"
      />

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="访问令牌" :error="error">
          <el-input
            v-model="token"
            type="password"
            show-password
            placeholder="Access token"
            size="large"
            @keyup.enter="submit"
          >
            <template #prefix>
              <el-icon><Key /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-button type="primary" size="large" class="login-submit" @click="submit">
          <el-icon><Ship /></el-icon>
          进入系统
        </el-button>
        <el-button v-if="apiMode === 'mock'" text class="login-demo" @click="useDemoToken">
          使用演示令牌
        </el-button>
      </el-form>

      <p class="login-foot">令牌只保存在当前标签页（sessionStorage），关闭即失效。</p>
    </div>
  </div>
</template>
