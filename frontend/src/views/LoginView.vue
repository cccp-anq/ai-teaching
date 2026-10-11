<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchLoginMode, login } from '../api/auth'
import { ApiClientError } from '../api/client'
import { setCurrentUser } from '../stores/session'
import type { CurrentUser } from '../api/types'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const loginMode = ref<'loading' | 'username-only' | 'password'>('loading')
const submitting = ref(false)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    const mode = await fetchLoginMode()
    loginMode.value = mode.usernameOnly ? 'username-only' : 'password'
  } catch {
    error.value = '无法读取登录方式，请刷新页面重试。'
  }
})

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = null
  if (loginMode.value === 'loading') return
  if (username.value.trim() === '' || (loginMode.value === 'password' && password.value === '')) {
    error.value = loginMode.value === 'username-only' ? '请输入用户名。' : '请输入用户名和密码。'
    return
  }
  submitting.value = true
  try {
    const user: CurrentUser = await login(username.value.trim(),
      loginMode.value === 'username-only' ? '' : password.value)
    setCurrentUser(user)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/offerings'
    await router.replace(redirect)
  } catch (cause) {
    error.value = cause instanceof ApiClientError
      ? (cause.status === 401
        ? (loginMode.value === 'username-only' ? '用户名不存在或账号不可用。' : '用户名或密码不正确，或账号不可用。')
        : cause.message)
      : '登录失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="login-page">
    <div class="login-shell">
      <aside class="login-story" aria-label="产品说明">
        <span class="login-kicker">AI JIAO XUE</span>
        <h2>让每一门课<br>都清晰可见</h2>
        <p>聚合课程安排、课堂信息与督导进展，为教学管理提供可信、可追溯的工作视图。</p>
        <div class="login-feature-list" aria-hidden="true">
          <span><b>01</b> 授权开课查询</span>
          <span><b>02</b> 多教师横向比较</span>
          <span><b>03</b> 督导覆盖追踪</span>
        </div>
      </aside>
      <div class="login-panel">
        <div class="login-heading">
          <span class="login-badge" aria-hidden="true">教</span>
          <div>
            <p class="eyebrow">爱教学 · 教学质量工作台</p>
            <h1>登录</h1>
          </div>
        </div>
        <p class="lead">{{ loginMode === 'username-only' ? '输入已有账号的用户名，按该账号的角色进入工作台。' : '欢迎回来。使用系统账号登录，在服务端授权范围内开展工作。' }}</p>
        <form class="login-form" @submit.prevent="submit">
          <div class="field">
            <label for="login-username">用户名</label>
            <input id="login-username" v-model="username" name="username" autocomplete="username" placeholder="请输入用户名" required>
          </div>
          <div v-if="loginMode === 'password'" class="field">
            <label for="login-password">密码</label>
            <input id="login-password" v-model="password" type="password" name="password" autocomplete="current-password" placeholder="请输入密码" required>
          </div>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="login-submit" type="submit" :disabled="submitting || loginMode === 'loading'">{{ submitting ? '正在登录…' : '登录工作台' }}</button>
        </form>
        <p class="login-security">账号权限由服务端统一校验；如无法登录，请联系系统管理员。</p>
      </div>
    </div>
  </section>
</template>
