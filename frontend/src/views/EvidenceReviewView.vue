<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiClientError } from '../api/client'
import { fetchClassroom, fetchGeneratedClassroomReport } from '../api/classroom'
import {
  coveringRange, formatMediaTime, rangeLabel, shouldStopAtRangeEnd,
} from '../lib/evidencePlayback'
import type { MediaRange } from '../lib/evidencePlayback'
import type {
  ClassroomAnalysis, ClassroomMedia, GeneratedClassroomReport, GeneratedReportEvidenceRef,
} from '../api/types'

/**
 * S2-02 区间播放与证据联动：点一条证据就跳到它指向的课堂时间，并只播这一段。
 * 播放走既有的 /class-sessions/media/{id}/content（支持 HTTP Range），越权由服务端拒绝。
 */
interface EvidenceEntry {
  key: string
  kind: '话题' | '变化' | '解释'
  title: string
  detail: string
  refs: GeneratedReportEvidenceRef[]
}

const route = useRoute()
const classSessionId = computed(() => Number(route.params.classSessionId))
const requestedMediaId = computed<number | null>(() => {
  const raw = Number(route.query.mediaId)
  return Number.isInteger(raw) && raw > 0 ? raw : null
})

const classroom = ref<ClassroomAnalysis | null>(null)
const media = ref<ClassroomMedia | null>(null)
const report = ref<GeneratedClassroomReport | null>(null)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<ApiClientError | null>(null)
const player = ref<HTMLVideoElement | null>(null)
const activeRange = ref<MediaRange | null>(null)
const activeKey = ref<string | null>(null)
const notice = ref<string | null>(null)

const objective = computed(() => media.value?.report ?? null)

const entries = computed<EvidenceEntry[]>(() => {
  const generated = report.value
  if (generated === null) return []
  const list: EvidenceEntry[] = []
  generated.topics.forEach((topic, index) => list.push({
    key: `topic-${index}`, kind: '话题',
    title: `${formatMediaTime(topic.startMs)} – ${formatMediaTime(topic.endMs)}`,
    detail: topic.summary, refs: topic.evidenceRefs,
  }))
  generated.changes.forEach((change, index) => list.push({
    key: `change-${index}`, kind: '变化',
    title: `${formatMediaTime(change.startMs)} – ${formatMediaTime(change.endMs)}`,
    detail: `相对前一窗口变化 ${change.deltaPercentagePoints.toFixed(1)} 个百分点`,
    refs: change.evidenceRefs,
  }))
  generated.interpretations.forEach((item, index) => list.push({
    key: `interpretation-${index}`, kind: '解释',
    title: item.hypothesis,
    detail: item.suggestion, refs: item.evidenceRefs,
  }))
  return list
})

onMounted(() => { void load() })

async function load(): Promise<void> {
  phase.value = 'loading'
  error.value = null
  try {
    const loaded = await fetchClassroom(classSessionId.value)
    classroom.value = loaded
    const picked = requestedMediaId.value === null
      ? loaded.media[0] ?? null
      : loaded.media.find(item => item.mediaId === requestedMediaId.value) ?? null
    media.value = picked
    report.value = picked === null ? null : await fetchGeneratedClassroomReport(picked.mediaId)
    phase.value = 'ready'
  } catch (cause) {
    error.value = cause instanceof ApiClientError ? cause : null
    phase.value = 'error'
  }
}

function targetMs(range: MediaRange): number {
  return range.startMs / 1000
}

async function playRange(entry: EvidenceEntry): Promise<void> {
  const current = objective.value
  const element = player.value
  notice.value = null
  if (current === null || element === null) return
  const range = coveringRange(current, entry.refs)
  if (range === null) {
    notice.value = '这条结论的课堂证据当前无法定位（证据引用缺失），因此不做跳转。'
    return
  }
  element.currentTime = targetMs(range)
  activeRange.value = range
  activeKey.value = entry.key
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
    activeKey.value = null
  }
}

function stopRangePlayback(): void {
  player.value?.pause()
  activeRange.value = null
  activeKey.value = null
}
</script>

<template>
  <section class="page evidence-page">
    <p class="eyebrow">看课堂</p>
    <h1>证据回看</h1>
    <p class="lead">
      每条结论都标出它依据的课堂证据。点击「播放证据片段」只播放该证据对应的时间区间，播到区间末尾自动停下。
    </p>

    <div v-if="phase === 'loading'" class="state-note" role="status">正在加载课堂与报告…</div>
    <div v-else-if="phase === 'error'" class="state-error" role="alert">
      {{ error?.status === 403 || error?.status === 404
        ? '你看不到这节课的课堂证据（可能不在你的授权范围）。'
        : error?.message ?? '加载失败，请稍后重试。' }}
    </div>

    <template v-else>
      <div v-if="media === null" class="empty-state">
        <h2>这节课还没有可回看的课堂媒体</h2>
        <p>请先上传课堂视频，或从有课堂记录的课程进入。</p>
      </div>

      <template v-else>
        <video ref="player" class="evidence-player" :src="media.playbackUrl" controls
               preload="metadata" @timeupdate="onTimeUpdate"></video>

        <p v-if="activeRange !== null" class="state-note" role="status">
          正在播放证据区间 {{ rangeLabel(activeRange) }}
          <button type="button" class="secondary" @click="stopRangePlayback">停止区间播放</button>
        </p>
        <p v-if="notice !== null" class="state-note" role="status">{{ notice }}</p>

        <div v-if="report === null" class="empty-state">
          <h2>这节课还没有可对照的 AI 报告</h2>
          <p>报告生成后，这里会列出每条结论及其课堂证据。</p>
        </div>

        <div v-else class="evidence-list">
          <article v-for="entry in entries" :key="entry.key" class="evidence-card"
                   :class="{ active: activeKey === entry.key }">
            <div class="evidence-head">
              <span class="evidence-kind">{{ entry.kind }}</span>
              <strong>{{ entry.title }}</strong>
            </div>
            <p>{{ entry.detail }}</p>
            <p class="evidence-meta">
              依据 {{ entry.refs.length }} 条课堂证据
              <span v-if="entry.refs.length === 0">（无）</span>
            </p>
            <button type="button" class="secondary" :disabled="entry.refs.length === 0"
                    @click="playRange(entry)">
              播放证据片段
            </button>
          </article>
        </div>
      </template>
    </template>
  </section>
</template>
