<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiClientError } from '../api/client'
import { fetchConfirmedReport, fetchMyNotifications, markNotificationRead } from '../api/reportReview'
import { confirmedSegments, feedbackAnnotations } from '../lib/confirmedFeedback'
import { rangeLabel, shouldStopAtRangeEnd } from '../lib/evidencePlayback'
import type { MediaRange } from '../lib/evidencePlayback'
import type { ConfirmedReportSnapshot, TeacherNotification } from '../api/types'

/**
 * S2-12 教师查看正式复核与片段 + S2-05 通知入口。
 * 只读「督导确认后的冻结版本」：草稿复核不在这里出现，AI 之后重算也不会改变已发布内容。
 */
const notifications = ref<TeacherNotification[]>([])
const selected = ref<TeacherNotification | null>(null)
const snapshot = ref<ConfirmedReportSnapshot | null>(null)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<ApiClientError | null>(null)
const player = ref<HTMLVideoElement | null>(null)
const activeRange = ref<MediaRange | null>(null)
const notice = ref<string | null>(null)

const playbackUrl = computed(() => selected.value === null
  ? null
  : `/api/v1/class-sessions/media/${selected.value.mediaId}/content`)
const unreadCount = computed(() => notifications.value.filter(item => item.state === 'UNREAD').length)
const annotations = computed(() => snapshot.value === null ? [] : feedbackAnnotations(snapshot.value))
const segments = computed(() => snapshot.value === null ? [] : confirmedSegments(snapshot.value))

onMounted(() => { void load() })

async function load(): Promise<void> {
  phase.value = 'loading'
  error.value = null
  try {
    notifications.value = await fetchMyNotifications()
    const first = notifications.value.find(item => item.state === 'UNREAD') ?? notifications.value[0] ?? null
    if (first !== null) await select(first)
    phase.value = 'ready'
  } catch (cause) {
    error.value = cause instanceof ApiClientError ? cause : null
    phase.value = 'error'
  }
}

async function select(notification: TeacherNotification): Promise<void> {
  selected.value = notification
  notice.value = null
  activeRange.value = null
  snapshot.value = await fetchConfirmedReport(notification.mediaId)
  if (notification.state === 'UNREAD') {
    try {
      const updated = await markNotificationRead(notification.notificationId)
      notifications.value = notifications.value.map(item =>
        item.notificationId === updated.notificationId ? updated : item)
      selected.value = updated
    } catch {
      notice.value = '已打开这份反馈，但标记"已读"失败，稍后会自动重试。'
    }
  }
}

async function playSegment(range: MediaRange | null): Promise<void> {
  const element = player.value
  notice.value = null
  if (range === null) {
    notice.value = '这类结论没有自带时间区间，请用进度条定位，或到证据回看页按证据定位。'
    return
  }
  if (element === null) return
  element.currentTime = range.startMs / 1000
  activeRange.value = range
  try {
    await element.play()
  } catch {
    notice.value = '浏览器阻止了自动播放，请手动点击播放器继续。'
  }
}

function onTimeUpdate(): void {
  const element = player.value
  const range = activeRange.value
  if (element === null || range === null) return
  if (shouldStopAtRangeEnd(element.currentTime * 1000, range)) {
    element.pause()
    activeRange.value = null
  }
}
</script>

<template>
  <section class="page feedback-page">
    <p class="eyebrow">帮教师</p>
    <h1>我的反馈</h1>
    <p class="lead">
      这里是督导确认后的正式反馈：每条复核说明都指明了它针对报告中的哪一段。未确认的草稿不会出现在这里。
    </p>

    <div v-if="phase === 'loading'" class="state-note" role="status">正在加载…</div>
    <div v-else-if="phase === 'error'" class="state-error" role="alert">
      {{ error?.message ?? '加载失败，请稍后重试。' }}
    </div>

    <template v-else>
      <div v-if="notifications.length === 0" class="empty-state">
        <h2>暂时没有新的正式反馈</h2>
        <p>督导确认课堂评价后，你会在这里收到通知。</p>
      </div>

      <template v-else>
        <section class="notification-panel">
          <h2>通知（未读 {{ unreadCount }} 条）</h2>
          <ul class="notification-list">
            <li v-for="item in notifications" :key="item.notificationId">
              <button type="button" class="secondary" :class="{ active: selected?.notificationId === item.notificationId }"
                      @click="select(item)">
                <span class="notification-state">{{ item.state === 'UNREAD' ? '未读' : '已读' }}</span>
                课堂 #{{ item.mediaId }} 的正式报告 · {{ item.createdAt }}
              </button>
            </li>
          </ul>
        </section>

        <div v-if="snapshot === null" class="empty-state">
          <h2>这份报告还没有确认版本</h2>
          <p>通知对应的复核可能已被撤回或尚未完成确认，请联系督导核对。</p>
        </div>

        <template v-else>
          <section class="annotation-panel">
            <h2>督导复核说明（{{ annotations.length }} 条）</h2>
            <div v-if="annotations.length === 0" class="state-note">本次确认没有附加复核说明。</div>
            <ul v-else class="annotation-list">
              <li v-for="annotation in annotations" :key="annotation.key">
                <span class="verdict-chip" :class="{ issue: annotation.verdict === 'ISSUE' }">
                  {{ annotation.verdictLabel }}
                </span>
                <strong>{{ annotation.targetLabel }}</strong>
                <p>{{ annotation.note }}</p>
              </li>
            </ul>
          </section>

          <section class="segment-panel">
            <h2>确认版本的内容片段</h2>
            <video ref="player" class="evidence-player" :src="playbackUrl ?? undefined" controls
                   preload="metadata" @timeupdate="onTimeUpdate"></video>
            <p v-if="activeRange !== null" class="state-note" role="status">
              正在播放片段 {{ rangeLabel(activeRange) }}
            </p>
            <p v-if="notice !== null" class="state-note" role="status">{{ notice }}</p>
            <article v-for="segment in segments" :key="segment.key" class="evidence-card">
              <div class="evidence-head">
                <span class="evidence-kind">{{ segment.kind }}</span>
                <strong>{{ segment.title }}</strong>
              </div>
              <p>{{ segment.detail }}</p>
              <button type="button" class="secondary" @click="playSegment(segment.range)">播放该片段</button>
            </article>
          </section>
        </template>
      </template>
    </template>
  </section>
</template>
