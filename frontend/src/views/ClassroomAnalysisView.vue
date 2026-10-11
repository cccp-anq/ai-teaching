<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { fetchClassroom, fetchGeneratedClassroomReport, fetchGeneratedReportProgress, fetchGeneratedReportVersions, recoverExistingTranscription, requestFailedModuleRetry, requestGeneratedReportRetry } from '../api/classroom'
import { ApiClientError } from '../api/client'
import { sessionState } from '../stores/session'
import type { ClassroomAnalysis, ClassroomMedia, GeneratedClassroomReport, GeneratedReportEvidenceRef, GeneratedReportProgressState, GeneratedReportVersion } from '../api/types'
import { classroomJobState } from './classroomJobState'
import { evidenceTimeForReport, objectiveClassroomAnalysis, reportPollLimitReached, shouldPollGeneratedReport } from './classroomReportDisplay'
import { clearReportRetryKey, pendingReportRetryKey, rememberReportRetryKey } from './classroomReportRetryAttempt'
import { clearModuleRetryKey, pendingModuleRetryKey, rememberModuleRetryKey } from './classroomModuleRetryAttempt'
import { clearRecoveryKey, pendingRecoveryKey, rememberRecoveryKey } from './classroomTranscriptionRecoveryAttempt'

const route = useRoute()
const classroom = ref<ClassroomAnalysis | null>(null)
const error = ref<string | null>(null)
const loading = ref(true)
const generatedReport = ref<GeneratedClassroomReport | null>(null)
const generatedReportRestricted = ref(false)
const generatedReportError = ref<string | null>(null)
const generatedReportProgress = ref<GeneratedReportProgressState | null>(null)
const reportVersions = ref<GeneratedReportVersion[] | null>(null)
const versionNextPage = ref<number | null>(null)
const versionLoading = ref(false)
const versionError = ref<string | null>(null)
const reportPolls = ref(0)
const reportPollingPaused = ref(false)
const requestingManualRetry = ref(false)
const manualRetryError = ref<string | null>(null)
const manualRetryKey = ref<string | null>(null)
const requestingModuleRetry = ref(false)
const moduleRetryError = ref<string | null>(null)
const moduleRetryKey = ref<string | null>(null)
const requestingRecovery = ref(false)
const recoveryError = ref<string | null>(null)
const recoveryKey = ref<string | null>(null)
const player = ref<HTMLVideoElement | null>(null)
const transcriptExpanded = ref(false)
let timer: ReturnType<typeof setInterval> | null = null
let loadGeneration = 0
let inFlightSessionId: number | null = null

const sessionId = computed(() => {
  const raw = route.params.classSessionId
  return typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : null
})
const media = computed<ClassroomMedia | null>(() => classroom.value?.media[0] ?? null)
const objectiveAnalysis = computed(() => media.value?.report && generatedReport.value
  ? objectiveClassroomAnalysis(media.value.report, generatedReport.value, media.value.durationMs) : null)
const topicObservations = computed(() => objectiveAnalysis.value?.topicObservations ?? [])
const jobState = computed(() => classroomJobState(media.value?.moduleJobs.map(job => job.status) ?? []))
const waitingForConfiguration = computed(() => jobState.value.waitingForConfiguration)
const processing = computed(() => jobState.value.processing)
const waiting = computed(() => waitingForConfiguration.value || processing.value)
const canRequestManualRetry = computed(() => {
  const user = sessionState.currentUser
  const offeringId = classroom.value?.offeringId
  return user !== null && offeringId !== undefined && user.roles.includes('SUPERVISOR')
    && user.authorizationScopes.some(scope => scope.roleCode === 'SUPERVISOR'
      && scope.scopeType === 'OFFERING' && scope.offeringId === offeringId
      && scope.collegeId === null && scope.teacherId === null)
})
const canRetryFailedModules = computed(() => canRequestManualRetry.value
  && media.value?.report?.dataMode === 'EXTERNAL'
  && media.value.report.status === 'FAILED'
  && media.value.moduleJobs.some(job => job.status === 'FAILED')
  && media.value.moduleJobs.every(job => job.status === 'FAILED' || job.status === 'SUCCEEDED'))
const canRecoverTranscription = computed(() => canRequestManualRetry.value
  && media.value?.report?.dataMode === 'EXTERNAL'
  && media.value.report.status === 'FAILED'
  && media.value.moduleJobs.some(job => job.moduleType === 'TRANSCRIPTION' && job.status === 'FAILED'
    && job.errorCode === 'MODULE_INTEGRATION_FAILED')
  && media.value.moduleJobs.some(job => job.moduleType === 'HEAD_UP' && job.status === 'SUCCEEDED'))

watch(sessionId, () => {
  loadGeneration++
  inFlightSessionId = null
  classroom.value = null
  transcriptExpanded.value = false
  generatedReport.value = null
  generatedReportRestricted.value = false
  generatedReportError.value = null
  generatedReportProgress.value = null
  reportVersions.value = null
  versionNextPage.value = null
  versionLoading.value = false
  versionError.value = null
  reportPolls.value = 0
  reportPollingPaused.value = false
  requestingManualRetry.value = false
  manualRetryError.value = null
  manualRetryKey.value = null
  requestingModuleRetry.value = false
  moduleRetryError.value = null
  moduleRetryKey.value = null
  requestingRecovery.value = false
  recoveryError.value = null
  recoveryKey.value = null
  error.value = null
  loading.value = true
  void load()
}, { immediate: true })

onMounted(() => {
  timer = setInterval(() => {
    if (inFlightSessionId !== null) return
    if (waiting.value) { void load(false); return }
    if (!shouldPollGeneratedReport(media.value?.report ?? null, generatedReport.value,
      generatedReportRestricted.value, generatedReportError.value, reportPollingPaused.value,
      generatedReportProgress.value)) return
    if (reportPollLimitReached(reportPolls.value)) {
      reportPollingPaused.value = true
      return
    }
    reportPolls.value++
    void load(false, true)
  }, 5000)
})
onUnmounted(() => { loadGeneration++; if (timer !== null) clearInterval(timer) })

async function load(showLoading = true, reportOnly = false): Promise<void> {
  const targetId = sessionId.value
  if (targetId === null) {
    error.value = '课堂编号不合法。'
    loading.value = false
    return
  }
  if (inFlightSessionId === targetId) return
  const generation = ++loadGeneration
  inFlightSessionId = targetId
  if (showLoading) loading.value = true
  try {
    if (!reportOnly) {
      const nextClassroom = await fetchClassroom(targetId)
      if (generation !== loadGeneration || targetId !== sessionId.value) return
      classroom.value = nextClassroom
    }
    if (media.value?.report?.dataMode === 'EXTERNAL' && !generatedReportRestricted.value
        && generatedReport.value === null) {
      try {
        const nextReport = await fetchGeneratedClassroomReport(media.value.mediaId)
        if (generation !== loadGeneration || targetId !== sessionId.value) return
        generatedReport.value = nextReport
        generatedReportError.value = null
        if (nextReport === null) {
          const progress = await fetchGeneratedReportProgress(media.value.mediaId)
          if (generation !== loadGeneration || targetId !== sessionId.value) return
          generatedReportProgress.value = progress.state
        } else {
          generatedReportProgress.value = 'COMPLETED'
          if (reportVersions.value !== null && !reportVersions.value.some(item => item.current)) {
            reportVersions.value = null
            versionError.value = null
          }
        }
      } catch (cause) {
        if (generation !== loadGeneration || targetId !== sessionId.value) return
        if (cause instanceof ApiClientError && (cause.status === 403 || cause.status === 404)) {
          generatedReportRestricted.value = true
          generatedReportError.value = null
          generatedReportProgress.value = null
        } else {
          generatedReportError.value = cause instanceof ApiClientError ? cause.message : 'AI报告加载失败。'
        }
      }
    }
    if (media.value?.report?.dataMode === 'EXTERNAL' && !generatedReportRestricted.value
        && reportVersions.value === null && versionError.value === null) {
      versionLoading.value = true
      try {
        const versions = await fetchGeneratedReportVersions(media.value.mediaId)
        if (generation !== loadGeneration || targetId !== sessionId.value) return
        reportVersions.value = versions.items
        versionNextPage.value = versions.nextPage
      } catch (cause) {
        if (generation !== loadGeneration || targetId !== sessionId.value) return
        versionError.value = cause instanceof ApiClientError ? cause.message : '报告版本列表加载失败。'
      } finally {
        if (generation === loadGeneration) versionLoading.value = false
      }
    }
    error.value = null
  } catch (cause) {
    if (generation !== loadGeneration || targetId !== sessionId.value) return
    error.value = cause instanceof ApiClientError && cause.status === 404
      ? '课堂不存在或当前账号不可见。'
      : cause instanceof ApiClientError ? cause.message : '课堂分析加载失败。'
  } finally {
    if (generation === loadGeneration) {
      inFlightSessionId = null
      loading.value = false
    }
  }
}

async function retryVersionList(): Promise<void> {
  const targetId = sessionId.value
  const targetMediaId = media.value?.mediaId
  if (targetId === null || targetMediaId === undefined || versionLoading.value) return
  versionError.value = null
  versionLoading.value = true
  try {
    const versions = await fetchGeneratedReportVersions(targetMediaId)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    reportVersions.value = versions.items
    versionNextPage.value = versions.nextPage
  } catch (cause) {
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    versionError.value = cause instanceof ApiClientError ? cause.message : '报告版本列表加载失败。'
  } finally {
    if (targetId === sessionId.value && targetMediaId === media.value?.mediaId) versionLoading.value = false
  }
}

async function loadMoreVersions(): Promise<void> {
  const targetId = sessionId.value
  const targetMediaId = media.value?.mediaId
  const page = versionNextPage.value
  if (targetId === null || targetMediaId === undefined || page === null || versionLoading.value) return
  versionLoading.value = true
  versionError.value = null
  try {
    const versions = await fetchGeneratedReportVersions(targetMediaId, page)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    reportVersions.value = [...(reportVersions.value ?? []), ...versions.items]
    versionNextPage.value = versions.nextPage
  } catch (cause) {
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    versionError.value = cause instanceof ApiClientError ? cause.message : '更多报告版本加载失败。'
  } finally {
    if (targetId === sessionId.value && targetMediaId === media.value?.mediaId) versionLoading.value = false
  }
}

function retryGeneratedReport(): void {
  reportPolls.value = 0
  reportPollingPaused.value = false
  generatedReportError.value = null
  generatedReportProgress.value = null
  void load(false, true)
}

async function requestManualRetry(): Promise<void> {
  const targetId = sessionId.value
  const targetMediaId = media.value?.mediaId
  const targetUserId = sessionState.currentUser?.userId
  if (!canRequestManualRetry.value || targetId === null || targetMediaId === undefined
      || targetUserId === undefined || requestingManualRetry.value) return
  if (!window.confirm('再次生成会提交新的AI报告请求，可能产生额外费用。确定由你发起吗？')) return
  let storage: Storage | null = null
  try { storage = window.sessionStorage } catch { /* Use the in-memory key for this page. */ }
  const key = manualRetryKey.value ?? pendingReportRetryKey(storage, targetUserId, targetMediaId)
    ?? crypto.randomUUID()
  manualRetryKey.value = key
  rememberReportRetryKey(storage, targetUserId, targetMediaId, key)
  requestingManualRetry.value = true
  manualRetryError.value = null
  try {
    await requestGeneratedReportRetry(targetMediaId, key)
    clearReportRetryKey(storage, targetUserId, targetMediaId)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    manualRetryKey.value = null
    reportPolls.value = 0
    reportPollingPaused.value = false
    generatedReportProgress.value = 'QUEUED'
    await load(false, true)
  } catch (cause) {
    if (cause instanceof ApiClientError && cause.status !== undefined && cause.status < 500)
      clearReportRetryKey(storage, targetUserId, targetMediaId)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    if (cause instanceof ApiClientError && cause.status !== undefined && cause.status < 500)
      manualRetryKey.value = null
    manualRetryError.value = cause instanceof ApiClientError ? cause.message : '再次生成请求失败，请稍后重试。'
  } finally {
    if (targetId === sessionId.value && targetMediaId === media.value?.mediaId)
      requestingManualRetry.value = false
  }
}

async function requestModuleRetry(): Promise<void> {
  const targetId = sessionId.value
  const targetMediaId = media.value?.mediaId
  const targetUserId = sessionState.currentUser?.userId
  if (!canRetryFailedModules.value || targetId === null || targetMediaId === undefined
      || targetUserId === undefined || requestingModuleRetry.value) return
  if (!window.confirm('重新分析失败的识别任务会提交新的模块请求，可能产生额外费用。确定由你发起吗？')) return
  let storage: Storage | null = null
  try { storage = window.sessionStorage } catch { /* In-memory key remains available. */ }
  const key = moduleRetryKey.value ?? pendingModuleRetryKey(storage, targetUserId, targetMediaId)
    ?? crypto.randomUUID()
  moduleRetryKey.value = key
  rememberModuleRetryKey(storage, targetUserId, targetMediaId, key)
  requestingModuleRetry.value = true
  moduleRetryError.value = null
  try {
    await requestFailedModuleRetry(targetMediaId, key)
    clearModuleRetryKey(storage, targetUserId, targetMediaId)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    moduleRetryKey.value = null
    await load(false)
  } catch (cause) {
    if (cause instanceof ApiClientError && cause.status !== undefined && cause.status < 500)
      clearModuleRetryKey(storage, targetUserId, targetMediaId)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    if (cause instanceof ApiClientError && cause.status !== undefined && cause.status < 500)
      moduleRetryKey.value = null
    moduleRetryError.value = cause instanceof ApiClientError ? cause.message : '重新分析请求失败，请稍后重试。'
  } finally {
    if (targetId === sessionId.value && targetMediaId === media.value?.mediaId)
      requestingModuleRetry.value = false
  }
}

async function requestTranscriptionRecovery(): Promise<void> {
  const targetId = sessionId.value
  const targetMediaId = media.value?.mediaId
  const targetUserId = sessionState.currentUser?.userId
  if (!canRecoverTranscription.value || targetId === null || targetMediaId === undefined
      || targetUserId === undefined || requestingRecovery.value) return
  let storage: Storage | null = null
  try { storage = window.sessionStorage } catch { /* In-memory key remains available. */ }
  const key = recoveryKey.value ?? pendingRecoveryKey(storage, targetUserId, targetMediaId)
    ?? crypto.randomUUID()
  recoveryKey.value = key
  rememberRecoveryKey(storage, targetUserId, targetMediaId, key)
  requestingRecovery.value = true
  recoveryError.value = null
  try {
    await recoverExistingTranscription(targetMediaId, key)
    clearRecoveryKey(storage, targetUserId, targetMediaId)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    recoveryKey.value = null
    await load(false)
  } catch (cause) {
    if (cause instanceof ApiClientError && cause.status !== undefined && cause.status < 500)
      clearRecoveryKey(storage, targetUserId, targetMediaId)
    if (targetId !== sessionId.value || targetMediaId !== media.value?.mediaId) return
    if (cause instanceof ApiClientError && cause.status !== undefined && cause.status < 500)
      recoveryKey.value = null
    recoveryError.value = cause instanceof ApiClientError ? cause.message : '恢复已有转写结果失败，请稍后重试。'
  } finally {
    if (targetId === sessionId.value && targetMediaId === media.value?.mediaId)
      requestingRecovery.value = false
  }
}

function time(ms: number | null): string {
  if (ms === null) return '—'
  const total = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}
function percent(rate: number | null): string {
  return rate === null ? '数据不足' : `${(rate * 100).toFixed(2)}%`
}
function comparedWithOverall(rate: number | null, overall: number | null): string {
  if (rate === null || overall === null) return '无法与全课均值比较'
  const difference = (rate - overall) * 100
  if (Math.abs(difference) < 0.005) return '与全课均值相同'
  return `${difference > 0 ? '高于' : '低于'}全课均值 ${Math.abs(difference).toFixed(2)} 个百分点`
}
function confidenceLabel(confidence: string | null): string {
  return ({ HIGH: '较高', MEDIUM: '中等', LOW: '较低' } as Record<string, string>)[confidence ?? ''] ?? '数据不足'
}
function phaseTopicSummary(indexes: number[]): string {
  const report = generatedReport.value
  if (report === null) return ''
  return indexes.map(index => report.topics[index]?.summary).filter(Boolean).join('；')
}
function seek(ms: number | null): void {
  if (ms === null || player.value === null) return
  player.value.currentTime = ms / 1000
  void player.value.play()
}
function jobLabel(status: string): string {
  return ({ WAITING_EXTERNAL: '等待外部模块', QUEUED: '已排队', RUNNING: '处理中', SUCCEEDED: '已完成', FAILED: '失败' } as Record<string, string>)[status] ?? status
}
function evidenceTime(ref: GeneratedReportEvidenceRef): number | null {
  return evidenceTimeForReport(media.value?.report ?? null, ref)
}
</script>

<template>
  <section class="page">
    <RouterLink v-if="classroom !== null" class="classroom-back-link"
      :to="`/offerings/detail/${classroom.offeringId}`">← 返回开课详情</RouterLink>
    <p class="eyebrow">单次课堂 · 客观数据与AI分析</p>
    <h1>{{ classroom?.title ?? '课堂分析' }}</h1>
    <div v-if="loading" class="state-note" role="status">正在加载课堂分析…</div>
    <div v-else-if="error !== null" class="state-error" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="load()">重试</button>
    </div>
    <template v-else-if="classroom !== null && media !== null">
      <section class="card">
        <h2>{{ classroom.sessionDate }} · {{ classroom.title }}</h2>
        <video ref="player" class="classroom-video" controls preload="metadata" :src="media.playbackUrl">
          当前浏览器无法播放该视频。
        </video>
        <dl class="fact-grid">
          <div><dt>文件</dt><dd>{{ media.originalFilename }}</dd></div>
          <div><dt>时长</dt><dd>{{ time(media.durationMs) }}</dd></div>
          <div><dt>大小</dt><dd>{{ (media.byteSize / 1024 / 1024).toFixed(2) }} MiB</dd></div>
          <div><dt>内容摘要</dt><dd class="hash-value">{{ media.sha256 }}</dd></div>
        </dl>
      </section>

      <section class="card">
        <h2>自动处理状态</h2>
        <div class="job-grid">
          <article v-for="job in media.moduleJobs" :key="job.jobId" class="job-item">
            <strong>{{ job.moduleType === 'TRANSCRIPTION' ? '语音转文字' : '抬头率计算' }}</strong>
            <span :class="`status-pill status-${job.status.toLowerCase()}`">{{ jobLabel(job.status) }}</span>
            <small v-if="job.errorMessage">{{ job.errorCode }}：{{ job.errorMessage }}</small>
          </article>
        </div>
        <p v-if="waitingForConfiguration" class="notice" role="status">
          视频已保存。仍有识别模块未配置，对应任务正在等待模块接入；页面每5秒刷新状态。
        </p>
        <p v-if="processing" class="notice" role="status">
          自动处理任务正在排队或运行；页面每5秒刷新状态。
        </p>
        <p v-if="media.report?.dataMode === 'SIMULATED'" class="notice">
          当前全部转写、抬头率和分析均为模拟数据，只用于开发联调，不能作为真实课堂结论。
        </p>
      </section>

      <template v-if="media.report !== null">
        <section class="card">
          <h2>客观数据</h2>
          <dl class="fact-grid">
            <div><dt>有效窗口平均抬头率</dt><dd>{{ percent(media.report.meanHeadUpRate) }}</dd></div>
            <div><dt>有效观测覆盖</dt><dd>{{ percent(media.report.validCoverageRate) }}</dd></div>
            <div><dt>识别出勤人数（视频估计）</dt><dd>{{ media.report.estimatedAttendanceCount == null ? '数据不足' : `${media.report.estimatedAttendanceCount} 人` }}</dd></div>
            <div><dt>应到人数</dt><dd>—</dd></div>
            <div><dt>报告状态</dt><dd>{{ media.report.status }}</dd></div>
            <div><dt>数据类型</dt><dd>{{ media.report.dataMode === 'SIMULATED' ? '模拟联调数据' : '外部模块数据' }}</dd></div>
          </dl>
          <p class="fact-note">识别出勤人数来自匿名抽帧估计，不等同于正式考勤；应到人数尚未提供。</p>
          <div v-if="media.report.headUpWindows.length > 0" class="timeline-chart" aria-label="抬头率时间窗口">
            <button
              v-for="window in media.report.headUpWindows" :key="window.id" type="button"
              class="timeline-window" :class="{ invalid: window.headUpRate === null }"
              :style="{ '--rate': String(window.headUpRate ?? 0) }"
              :title="`${time(window.startMs)}–${time(window.endMs)}：${percent(window.headUpRate)}`"
              @click="seek(window.startMs)"
            >
              <span class="timeline-bar"></span>
              <small>{{ time(window.startMs) }}<br>{{ percent(window.headUpRate) }}</small>
            </button>
          </div>
          <p v-else class="empty-inline">抬头率数据尚未返回。</p>
        </section>

        <section class="card">
          <div class="transcript-heading">
            <h2>带时间戳的转写</h2>
            <button v-if="media.report.transcript.length > 0" type="button" class="transcript-toggle"
              :aria-expanded="transcriptExpanded" aria-controls="classroom-transcript-list"
              @click="transcriptExpanded = !transcriptExpanded">
              {{ transcriptExpanded ? '收起转写' : `展开转写（${media.report.transcript.length} 段）` }}
            </button>
          </div>
          <p v-if="media.report.transcript.length > 0 && !transcriptExpanded" class="empty-inline">
            转写内容已收起，展开后可点击时间戳定位视频。
          </p>
          <ol v-if="media.report.transcript.length > 0" v-show="transcriptExpanded"
            id="classroom-transcript-list" class="transcript-list">
            <li v-for="segment in media.report.transcript" :key="segment.id">
              <button class="time-button" type="button" @click="seek(segment.startMs)">
                {{ time(segment.startMs) }}–{{ time(segment.endMs) }}
              </button>
              <span>{{ segment.text }}</span>
            </li>
          </ol>
          <p v-else class="empty-inline">转写数据尚未返回。</p>
        </section>

        <section v-if="media.report.status !== 'READY' || media.report.summary || media.report.analysis"
          class="card report-card">
          <h2>客观分析摘要</h2>
          <template v-if="media.report.status === 'SUCCEEDED' || media.report.status === 'READY'">
            <template v-if="media.report.summary">
              <h3>课程内容概要</h3>
              <p>{{ media.report.summary }}</p>
            </template>
            <template v-if="media.report.analysis">
              <h3>客观分析</h3>
              <p>{{ media.report.analysis }}</p>
            </template>
            <button v-if="media.report.evidenceStartMs !== null && media.report.evidenceEndMs !== null"
              type="button" @click="seek(media.report.evidenceStartMs)">
              查看证据 {{ time(media.report.evidenceStartMs) }}–{{ time(media.report.evidenceEndMs) }}
            </button>
            <p v-if="!media.report.summary && !media.report.analysis" class="empty-inline">
              暂无独立文字摘要；客观统计和时间窗口见上方。
            </p>
          </template>
          <div v-else-if="media.report.status === 'FAILED'" class="state-error" role="alert">
            <p>{{ media.report.errorMessage ?? '分析失败。' }}</p>
            <template v-if="canRetryFailedModules">
              <template v-if="canRecoverTranscription">
                <p>可读取供应商侧已有的成功转写并写回本课堂；此操作不会提交新的供应商请求。</p>
                <button type="button" :disabled="requestingRecovery" @click="requestTranscriptionRecovery">
                  {{ requestingRecovery ? '正在恢复…' : '恢复已有转写结果' }}
                </button>
                <p v-if="recoveryError !== null" role="alert">{{ recoveryError }}</p>
              </template>
              <p>该课督导可重建失败的识别任务；已成功的任务与旧失败记录会保留。</p>
              <button type="button" :disabled="requestingModuleRetry" @click="requestModuleRetry">
                {{ requestingModuleRetry ? '正在提交…' : '重新分析失败任务' }}
              </button>
              <p v-if="moduleRetryError !== null" role="alert">{{ moduleRetryError }}</p>
            </template>
          </div>
          <p v-else class="empty-inline">两类模块结果齐备后显示客观分析。</p>
        </section>

        <section class="card report-card">
          <h2>AI课堂分析报告（基于客观证据）</h2>
          <p class="fact-note">报告组合已保存的匿名出勤估计、抬头观测和带时间戳转写；AI提取课堂内容，数值由本地证据重新计算。</p>
          <p v-if="generatedReportRestricted" class="empty-inline">当前账号无权查看该课AI报告。</p>
          <div v-else-if="generatedReportError !== null" class="state-error" role="alert">
            {{ generatedReportError }} <button type="button" @click="retryGeneratedReport">重试</button>
          </div>
          <template v-else-if="generatedReport !== null && objectiveAnalysis !== null">
            <section class="classroom-synthesis" aria-label="本次课综合观察">
              <p class="report-kicker">一、课堂概览</p>
              <h3>本次课综合结论</h3>
              <p>本报告从 {{ generatedReport.topics.length }} 个有时间戳的内容主题、
                {{ objectiveAnalysis.validWindowCount }} 个有效抬头窗口和
                {{ media.report.attendanceObservedWindows }} 个匿名人数观测综合描述本次课堂。</p>
              <p>有效窗口的时长加权平均抬头率为 {{ percent(media.report.meanHeadUpRate) }}，有效观测覆盖视频时长
                {{ percent(media.report.validCoverageRate) }}。下方按课堂前、中、后三段和具体内容主题展示差异，所有差异均为画面观测，不解释为注意力或学习效果。</p>
              <dl class="analysis-metric-grid">
                <div><dt>视频时长</dt><dd>{{ time(media.durationMs) }}</dd></div>
                <div><dt>内容主题</dt><dd>{{ generatedReport.topics.length }} 个</dd></div>
                <div><dt>有效抬头窗口</dt><dd>{{ objectiveAnalysis.validWindowCount }} / {{ objectiveAnalysis.totalWindowCount }}</dd></div>
                <div><dt>有效观测覆盖</dt><dd>{{ percent(media.report.validCoverageRate) }}</dd></div>
              </dl>
            </section>

            <section class="analysis-section">
              <p class="report-kicker">二、出勤分析</p>
              <h3>匿名人数观测</h3>
              <p v-if="media.report.estimatedAttendanceCount != null">
                视频抽帧估计本次课堂可见学生 {{ media.report.estimatedAttendanceCount }} 人，来自
                {{ media.report.attendanceObservedWindows }} 个可用人数观测，结果稳定度为{{ confidenceLabel(media.report.attendanceConfidence) }}。
                当前应到人数尚未提供，因此不计算出勤率。
              </p>
              <p v-else>当前没有足够的匿名人数观测形成出勤人数估计；应到人数尚未提供，因此不计算出勤率。</p>
              <p class="fact-note">该人数是多个匿名抽帧中较高可见人数的稳健估计，不识别学生身份，也不作为正式考勤。</p>
            </section>

            <section class="analysis-section">
              <p class="report-kicker">三、课程内容</p>
              <h3>内容进程与同期观测</h3>
              <ol class="analysis-topic-list">
              <li v-for="(topic, index) in generatedReport.topics" :key="index">
                <button type="button" class="time-button" @click="seek(topic.startMs)">
                  {{ time(topic.startMs) }}–{{ time(topic.endMs) }}
                </button>
                {{ topic.summary }}
                <small class="topic-observation" v-if="topicObservations[index]?.headUpRate != null">
                  同期有效窗口抬头率 {{ percent(topicObservations[index]?.headUpRate ?? null) }}，
                  该段有效观测覆盖 {{ percent(topicObservations[index]?.validCoverageRate ?? null) }}；
                  {{ comparedWithOverall(topicObservations[index]?.headUpRate ?? null, media.report.meanHeadUpRate) }}。
                </small>
                <small v-else class="topic-observation">该段没有同期有效抬头观测</small>
                <span v-for="ref in topic.evidenceRefs" :key="`${ref.resultId}-${ref.itemId}`">
                  <button v-if="evidenceTime(ref) !== null" type="button" class="time-button"
                    @click="seek(evidenceTime(ref))">证据 {{ ref.itemId }}</button>
                </span>
              </li>
              </ol>
            </section>

            <section class="analysis-section">
              <p class="report-kicker">四、课堂参与</p>
              <h3>课堂前、中、后段观测</h3>
              <div class="phase-grid">
                <article v-for="phase in objectiveAnalysis.phases" :key="phase.key" class="phase-card">
                  <button type="button" class="phase-time" @click="seek(phase.startMs)">
                    {{ phase.label }} · {{ time(phase.startMs) }}–{{ time(phase.endMs) }}
                  </button>
                  <strong>{{ percent(phase.headUpRate) }}</strong>
                  <span>有效覆盖 {{ percent(phase.validCoverageRate) }} · {{ phase.validWindowCount }} 个窗口</span>
                  <small v-if="phase.topicIndexes.length > 0">
                    同段内容：{{ phaseTopicSummary(phase.topicIndexes) }}
                  </small>
                  <small v-else>该阶段没有被AI主题证据覆盖。</small>
                </article>
              </div>
              <div class="observation-grid">
                <article v-if="objectiveAnalysis.lowestWindow !== null">
                  <span>最低有效窗口</span>
                  <strong>{{ percent(objectiveAnalysis.lowestWindow.headUpRate) }}</strong>
                  <button type="button" class="time-button" @click="seek(objectiveAnalysis.lowestWindow.startMs)">
                    {{ time(objectiveAnalysis.lowestWindow.startMs) }}–{{ time(objectiveAnalysis.lowestWindow.endMs) }}
                  </button>
                </article>
                <article v-if="objectiveAnalysis.highestWindow !== null">
                  <span>最高有效窗口</span>
                  <strong>{{ percent(objectiveAnalysis.highestWindow.headUpRate) }}</strong>
                  <button type="button" class="time-button" @click="seek(objectiveAnalysis.highestWindow.startMs)">
                    {{ time(objectiveAnalysis.highestWindow.startMs) }}–{{ time(objectiveAnalysis.highestWindow.endMs) }}
                  </button>
                </article>
                <article v-if="objectiveAnalysis.largestFall !== null">
                  <span>最大相邻下降</span>
                  <strong>{{ objectiveAnalysis.largestFall.deltaPercentagePoints.toFixed(2) }} 个百分点</strong>
                  <button type="button" class="time-button" @click="seek(objectiveAnalysis.largestFall.startMs)">
                    {{ time(objectiveAnalysis.largestFall.startMs) }}–{{ time(objectiveAnalysis.largestFall.endMs) }}
                  </button>
                </article>
                <article v-if="objectiveAnalysis.largestRise !== null">
                  <span>最大相邻回升</span>
                  <strong>+{{ objectiveAnalysis.largestRise.deltaPercentagePoints.toFixed(2) }} 个百分点</strong>
                  <button type="button" class="time-button" @click="seek(objectiveAnalysis.largestRise.startMs)">
                    {{ time(objectiveAnalysis.largestRise.startMs) }}–{{ time(objectiveAnalysis.largestRise.endMs) }}
                  </button>
                </article>
              </div>
              <p class="fact-note">最高、最低和相邻变化用于定位复核片段；单个窗口不能代表整段课堂，也不直接说明变化原因。</p>
            </section>

            <section class="analysis-section">
              <p class="report-kicker">五、教学复核建议</p>
              <h3>建议结合视频与转写核对</h3>
              <ul class="review-list">
                <li v-if="objectiveAnalysis.lowestWindow !== null">
                  回看 <button type="button" class="time-button" @click="seek(objectiveAnalysis.lowestWindow.startMs)">
                    {{ time(objectiveAnalysis.lowestWindow.startMs) }}–{{ time(objectiveAnalysis.lowestWindow.endMs) }}
                  </button>，结合同期课堂内容、教学活动和镜头条件判断低值背景。
                </li>
                <li v-if="objectiveAnalysis.largestFall !== null">
                  对照最大相邻下降前后的转写与视频，核对是否处于板书、阅读、练习、设备遮挡或教学环节切换。
                </li>
                <li v-if="media.report.estimatedAttendanceCount != null">补充本次课权威应到人数后，再计算视频估算出勤率并核对异常差异。</li>
                <li v-for="(item, index) in generatedReport.interpretations" :key="`interpretation-${index}`">
                  {{ item.suggestion }}
                  <span v-for="ref in item.evidenceRefs" :key="`${ref.resultId}-${ref.itemId}`">
                    <button v-if="evidenceTime(ref) !== null" type="button" class="time-button"
                      @click="seek(evidenceTime(ref))">证据 {{ ref.itemId }}</button>
                  </span>
                </li>
              </ul>
            </section>

            <section class="analysis-section">
              <p class="report-kicker">六、分析局限</p>
              <h3>结论边界</h3>
              <ul>
                <li v-for="(item, index) in generatedReport.limitations" :key="index">{{ item }}</li>
                <li>抬头观测描述画面中可判断的头部状态，不等于注意力、理解程度、学习效果或教学质量。</li>
                <li>课程内容来自自动转写与AI提取，可能受噪声、术语和抽样范围影响。</li>
                <li>内容与抬头数据在同一时间出现只表示时间对应，不构成因果证明。</li>
              </ul>
            </section>
          </template>
          <p v-else-if="media.report.dataMode === 'SIMULATED'" class="empty-inline">
            模拟联调数据不生成真实AI报告。
          </p>
          <p v-else-if="media.report.status === 'FAILED'" class="empty-inline">
            识别任务未完成，AI报告未生成。
          </p>
          <p v-else-if="media.report.status !== 'READY'" class="empty-inline">
            等待两类识别结果齐备并通过校验。
          </p>
          <div v-else-if="generatedReportProgress === 'PAUSED'" class="empty-inline" role="status">
            自动报告生成当前未启用，请稍后手动刷新。
            <button type="button" @click="retryGeneratedReport">刷新报告</button>
          </div>
          <div v-else-if="generatedReportProgress === 'NEEDS_REVIEW'" class="empty-inline" role="status">
            报告生成已暂停，等待人工处理。
            <button type="button" @click="retryGeneratedReport">刷新报告</button>
            <template v-if="canRequestManualRetry">
              <p>该课督导可决定是否再次生成；新的模型请求可能产生额外费用。</p>
              <button type="button" :disabled="requestingManualRetry" @click="requestManualRetry">
                {{ requestingManualRetry ? '正在提交…' : '再次生成AI报告' }}
              </button>
              <p v-if="manualRetryError !== null" class="state-error" role="alert">{{ manualRetryError }}</p>
            </template>
          </div>
          <div v-else-if="generatedReportProgress === 'NOT_QUEUED'" class="empty-inline" role="status">
            当前课堂尚未进入自动报告队列，请联系管理员。
            <button type="button" @click="retryGeneratedReport">刷新报告</button>
          </div>
          <div v-else-if="reportPollingPaused" class="empty-inline" role="status">
            报告仍未生成。自动生成可能尚未启用或需要人工处理，请稍后手动刷新。
            <button type="button" @click="retryGeneratedReport">刷新报告</button>
          </div>
          <p v-else-if="generatedReportProgress === 'QUEUED'" class="empty-inline">报告正在排队生成。</p>
          <p v-else-if="generatedReportProgress === 'PROCESSING'" class="empty-inline">报告正在生成。</p>
          <p v-else class="empty-inline">报告尚未生成；页面会短时查询进度，未启用自动生成时请稍后手动刷新。</p>
          <div v-if="!generatedReportRestricted && media.report.dataMode === 'EXTERNAL'" class="report-versions">
            <h3>报告版本记录</h3>
            <p class="fact-note">仅展示生成时间和版本；旧版正文及证据暂不开放，以免误用当前转写和时间窗口。</p>
            <p v-if="versionLoading && reportVersions === null" role="status">正在加载版本记录…</p>
            <p v-else-if="versionError !== null" class="state-error" role="alert">
              {{ versionError }} <button type="button" @click="retryVersionList">重试版本列表</button>
            </p>
            <p v-else-if="reportVersions?.length === 0" class="empty-inline">尚无已保存的AI报告版本。</p>
            <ol v-else-if="reportVersions !== null">
              <li v-for="item in reportVersions" :key="item.reportId">
                版本记录 #{{ item.reportId }} · {{ item.generatedAt.replace('T', ' ') }}
                <span v-if="item.current">（当前展示）</span><span v-else>（仅记录）</span>
              </li>
            </ol>
            <button v-if="versionNextPage !== null && versionError === null" type="button"
              :disabled="versionLoading" @click="loadMoreVersions">
              {{ versionLoading ? '正在加载…' : '加载更多版本' }}
            </button>
          </div>
        </section>
      </template>
    </template>
  </section>
</template>
