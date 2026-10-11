<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ApiClientError } from '../api/client'
import { signOutSession } from '../composables/signOut'
import { sessionState } from '../stores/session'
import { MISSING } from '../lib/display'

const router = useRouter()
const route = useRoute()
const currentUser = computed(() => sessionState.currentUser)
const isLoginPage = computed(() => route.name === 'login')
const ROLE_LABELS: Record<string, string> = {
  ADMIN: '后台管理员', DIRECTOR: '教研室主任', TEACHER: '任课教师', SUPERVISOR: '教学督导',
}
const roleLabel = computed(() => currentUser.value?.roles.map(role => ROLE_LABELS[role] ?? role).join('、') || MISSING)
const canImport = computed(() => currentUser.value?.roles.includes('ADMIN') ?? false)
const hasPersonalSchedule = computed(() => currentUser.value?.roles.some(role => role === 'TEACHER' || role === 'SUPERVISOR') ?? false)
const hasOwnActions = computed(() => currentUser.value?.roles.includes('TEACHER') ?? false)
// 「报告复核」只对教学督导开放；入口不带 mediaId，落到 /reports/review 的指路提示页，
// 真正的复核页需要从某节课进入（复核对象是具体课堂，不是全局列表）。
const canReviewReports = computed(() => currentUser.value?.roles.includes('SUPERVISOR') ?? false)
const canCompare = computed(() => currentUser.value?.roles.some(role => role === 'ADMIN' || role === 'DIRECTOR') ?? false)
const canSeeCoverage = computed(() => currentUser.value?.roles.some(role => role === 'ADMIN' || role === 'DIRECTOR' || role === 'SUPERVISOR') ?? false)
const userInitial = computed(() => currentUser.value?.username.trim().charAt(0).toUpperCase() || '访')
const logoutPending = ref(false)
const logoutError = ref<string | null>(null)

async function signOut(): Promise<void> {
  if (logoutPending.value) return
  logoutPending.value = true
  logoutError.value = null
  try {
    await signOutSession()
  } catch (cause) {
    logoutError.value = cause instanceof ApiClientError
      ? cause.message
      : '退出登录失败，请稍后重试。'
    return
  } finally {
    logoutPending.value = false
  }
  await router.replace('/login')
}
</script>

<template>
  <a class="skip-link" href="#main">跳转到正文</a>
  <header class="header">
    <RouterLink class="brand" to="/offerings" aria-label="爱教学首页">
      <span class="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 32 32" role="img">
          <path d="M6 8.5 16 4l10 4.5L16 13 6 8.5Z" />
          <path d="M9 12v7.2c0 2.7 3.1 4.8 7 4.8s7-2.1 7-4.8V12" />
          <path d="M26 9v8" />
        </svg>
      </span>
      <span class="brand-copy"><strong>爱教学</strong><small>教学质量工作台</small></span>
    </RouterLink>
    <div class="session-box">
      <template v-if="currentUser !== null">
        <span class="user-avatar" aria-hidden="true">{{ userInitial }}</span>
        <span class="user-meta">
          <strong>{{ currentUser.username }}</strong>
          <small>{{ roleLabel }}</small>
        </span>
        <button type="button" class="secondary" :disabled="logoutPending" @click="signOut">
          {{ logoutPending ? '正在退出…' : '退出登录' }}
        </button>
        <span v-if="logoutError !== null" class="form-error" role="alert">{{ logoutError }}</span>
      </template>
      <template v-else>
        <span class="status">未登录</span>
        <RouterLink to="/login">登录</RouterLink>
      </template>
    </div>
  </header>
  <div class="shell" :class="{ 'login-shell-mode': isLoginPage }">
    <nav aria-label="主导航">
      <div class="nav-intro">
        <span>工作台</span>
        <strong>查课程</strong>
      </div>
      <template v-if="hasPersonalSchedule">
        <p class="nav-label">我的工作</p>
        <RouterLink to="/my-schedule"><span class="nav-icon" aria-hidden="true">日</span>我的课表</RouterLink>
        <RouterLink v-if="hasOwnActions" to="/my-actions"><span class="nav-icon" aria-hidden="true">行</span>我的行动</RouterLink>
        <RouterLink v-if="canReviewReports" to="/reports/review"><span class="nav-icon" aria-hidden="true">核</span>报告复核</RouterLink>
      </template>
      <p class="nav-label">课程信息</p>
      <RouterLink to="/offerings"><span class="nav-icon" aria-hidden="true">查</span>授权开课查询</RouterLink>
      <RouterLink v-if="canCompare" to="/comparison"><span class="nav-icon" aria-hidden="true">比</span>多教师比较</RouterLink>
      <RouterLink v-if="canSeeCoverage" to="/supervision/coverage"><span class="nav-icon" aria-hidden="true">督</span>督导覆盖</RouterLink>
      <template v-if="canImport">
        <p class="nav-label">数据录入</p>
        <RouterLink to="/imports"><span class="nav-icon" aria-hidden="true">录</span>督导评价录入</RouterLink>
      </template>
      <p class="nav-label">系统</p>
      <RouterLink to="/system"><span class="nav-icon" aria-hidden="true">检</span>连接检查</RouterLink>
      <div class="nav-footnote">
        <span class="live-dot" aria-hidden="true"></span>
        <span>服务端权限实时生效</span>
      </div>
    </nav>
    <main id="main" tabindex="-1">
      <p v-if="sessionState.unauthenticated && !isLoginPage" class="notice" role="alert">
        尚未登录或登录状态已失效。<RouterLink to="/login">前往登录</RouterLink>；页面内操作仍以服务端校验结果为准。
      </p>
      <RouterView />
    </main>
  </div>
</template>
