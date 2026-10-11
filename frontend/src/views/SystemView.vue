<script setup lang="ts">
import { ref } from 'vue'
import { checkBackend } from '../api/system'
import { ApiClientError } from '../api/client'

const checking = ref(false)
const result = ref('尚未检查')
async function check() {
  checking.value = true
  result.value = '正在检查…'
  try {
    await checkBackend()
    result.value = '后端可达'
  } catch (error) {
    result.value = error instanceof ApiClientError ? error.message : '检查失败，请稍后重试。'
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <section class="placeholder">
    <p class="eyebrow">开发环境检查</p>
    <h1>后端连接</h1>
    <p>仅检查后端状态接口是否可达，不代表登录、数据库实时就绪或业务功能已完成。</p>
    <button type="button" :disabled="checking" @click="check">检查连接</button>
    <p role="status" aria-live="polite">{{ result }}</p>
  </section>
</template>
