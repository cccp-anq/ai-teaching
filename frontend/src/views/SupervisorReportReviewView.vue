<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { fetchGeneratedClassroomReport } from '../api/classroom'
import { fetchSegmentDirectory, saveSegmentOverride, segmentErrorText } from '../api/segments'
import { formatMediaTime } from '../lib/evidencePlayback'
import {
  directorySegments, findOverlaps, segmentDirectory, segmentDraftChanged, segmentLabel,
  validateSegmentEdit,
} from '../lib/segmentDirectory'
import type { DirectorySegment, SegmentEditDraft } from '../lib/segmentDirectory'
import { ApiClientError } from '../api/client'
import {
  canConfirmReview, confirmReportReview, fetchReportReview, reviewErrorText, saveReportReview,
  summarizeAnnotations,
} from '../api/reportReview'
import type {
  AnnotationTargetKind, AnnotationVerdict, ClassroomReportReview, GeneratedClassroomReport,
  ReviewAnnotation, SegmentDirectoryResponse,
} from '../api/types'

/**
 * S2-03 标注 AI 问题并记录说明 / S2-04 提交时固定复核快照 / S2-10 修正片段标题与边界。
 *
 * 路由有两条：`/reports/:mediaId/review` 与不带 mediaId 的 `/reports/review`。
 * 复核必须绑定一节课（mediaId），全局导航拿不到它，所以入口指向 `/reports/review`，
 * 由本页渲染"请从课堂进入"的提示，而不是让用户看到一个空壳页面。
 */

const route = useRoute()
const mediaId = computed<number | null>(() => {
  const raw = Number(route.params.mediaId)
  return Number.isInteger(raw) && raw > 0 ? raw : null
})

const report = ref<GeneratedClassroomReport | null>(null)
const review = ref<ClassroomReportReview | null>(null)
const drafts = ref<ReviewAnnotation[]>([])
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<ApiClientError | null>(null)
const actionError = ref<string | null>(null)
const forbidden = ref(false)
const busy = ref(false)

const editingKey = ref<string | null>(null)
const draftVerdict = ref<AnnotationVerdict>('ISSUE')
const draftNote = ref('')

// S2-10：服务端目录（含覆盖）与片段修正的编辑态。没有服务端目录就没有媒体时长，
// 因此这种情况下只读呈现目录，不给编辑入口——规则校验需要课堂总时长。
const directory = ref<SegmentDirectoryResponse | null>(null)
const editingSegmentKey = ref<string | null>(null)
const segmentTitle = ref('')
const segmentStartSeconds = ref('')
const segmentEndSeconds = ref('')

const VERDICT_LABELS: Record<AnnotationVerdict, string> = {
  ISSUE: '有问题', ACKNOWLEDGED: '认可',
}

interface ReviewTarget {
  kind: AnnotationTargetKind
  index: number
  label: string
  body: string
  evidenceCount: number
}

// S2-01 片段目录：按时间排序呈现报告片段，并标出已复核与区间重叠，便于快速选择复核内容。
// S2-10：督导修正过的片段以服务端目录为准（已叠加覆盖），拿不到时退回报告本身的划分。
const segments = computed<DirectorySegment[]>(() => directory.value === null
  ? segmentDirectory(report.value)
  : directorySegments(directory.value))
const canEditSegments = computed(() => !forbidden.value && directory.value !== null)
const mediaDurationMs = computed(() => directory.value?.mediaDurationMs ?? null)
const overlapKeys = computed(() => new Set(
  findOverlaps(segments.value).flatMap(pair => [pair.left.key, pair.right.key])))
const annotatedTopicIndexes = computed(() => new Set(
  (confirmed.value ? review.value?.annotations ?? [] : drafts.value)
    .filter(item => item.targetKind === 'TOPIC')
    .map(item => item.targetIndex)))

const targets = computed<ReviewTarget[]>(() => {
  const value = report.value
  if (value === null) return []
  const result: ReviewTarget[] = []
  value.topics.forEach((topic, index) => result.push({
    kind: 'TOPIC', index, label: `环节 ${index + 1}`, body: topic.summary,
    evidenceCount: topic.evidenceRefs.length,
  }))
  value.changes.forEach((change, index) => result.push({
    kind: 'CHANGE', index, label: `变化 ${index + 1}`,
    body: `${(change.deltaPercentagePoints ?? 0) >= 0 ? '上升' : '下降'} `
      + `${Math.abs(change.deltaPercentagePoints ?? 0).toFixed(1)} 个百分点`,
    evidenceCount: change.evidenceRefs.length,
  }))
  value.interpretations.forEach((entry, index) => result.push({
    kind: 'INTERPRETATION', index, label: `解释 ${index + 1}`,
    body: entry.hypothesis === '' ? entry.suggestion : `${entry.hypothesis} → ${entry.suggestion}`,
    evidenceCount: entry.evidenceRefs.length,
  }))
  return result
})

const confirmed = computed(() => review.value?.confirmed === true)
const summary = computed(() => summarizeAnnotations(confirmed.value ? review.value?.annotations ?? [] : drafts.value))
const workingReview = computed<ClassroomReportReview>(() => ({
  reviewId: review.value?.reviewId ?? 0,
  reportId: review.value?.reportId ?? 0,
  mediaId: mediaId.value ?? 0,
  confirmed: review.value?.confirmed ?? false,
  confirmedAt: review.value?.confirmedAt ?? null,
  revision: review.value?.revision ?? 0,
  annotations: drafts.value,
}))
const confirmable = computed(() => canConfirmReview(workingReview.value))

onMounted(() => { void load() })

function keyOf(kind: AnnotationTargetKind, index: number): string { return `${kind}#${index}` }

function annotationsFor(kind: AnnotationTargetKind, index: number): ReviewAnnotation[] {
  const source = confirmed.value ? review.value?.annotations ?? [] : drafts.value
  return source.filter(item => item.targetKind === kind && item.targetIndex === index)
}

async function load(): Promise<void> {
  if (mediaId.value === null) { phase.value = 'ready'; return }
  phase.value = 'loading'
  loadError.value = null
  forbidden.value = false
  directory.value = null
  editingSegmentKey.value = null
  try {
    report.value = await fetchGeneratedClassroomReport(mediaId.value)
    try {
      review.value = await fetchReportReview(mediaId.value)
      drafts.value = (review.value?.annotations ?? []).map(item => ({ ...item }))
      directory.value = await fetchSegmentDirectory(mediaId.value)
    } catch (cause) {
      // 非督导读复核底稿会被拒；报告本身仍可只读展示，不能让整页变成错误页。
      if (cause instanceof ApiClientError && cause.status === 403) forbidden.value = true
      else throw cause
    }
    phase.value = 'ready'
  } catch (cause) {
    loadError.value = cause instanceof ApiClientError ? cause : null
    phase.value = 'error'
  }
}

function beginEdit(target: ReviewTarget): void {
  const existing = annotationsFor(target.kind, target.index)[0]
  editingKey.value = keyOf(target.kind, target.index)
  draftVerdict.value = existing?.verdict ?? 'ISSUE'
  draftNote.value = existing?.note ?? ''
  actionError.value = null
}

function cancelEdit(): void { editingKey.value = null }

function submitAnnotation(target: ReviewTarget): void {
  const note = draftNote.value.trim()
  if (note === '') { actionError.value = '请先填写说明：没有说明的标注不构成复核记录。'; return }
  const next = drafts.value.filter(
    item => !(item.targetKind === target.kind && item.targetIndex === target.index))
  next.push({ targetKind: target.kind, targetIndex: target.index, verdict: draftVerdict.value, note })
  drafts.value = next
  editingKey.value = null
  actionError.value = null
}

function removeAnnotation(target: ReviewTarget): void {
  drafts.value = drafts.value.filter(
    item => !(item.targetKind === target.kind && item.targetIndex === target.index))
  actionError.value = null
}

async function run(operation: () => Promise<void>,
                   describeError: (code: string, fallback: string) => string = reviewErrorText): Promise<void> {
  if (busy.value) return
  busy.value = true
  actionError.value = null
  try {
    await operation()
  } catch (cause) {
    actionError.value = cause instanceof ApiClientError
      ? describeError(cause.code, cause.message)
      : '操作失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}

/** 毫秒 → 秒的输入值：去掉多余的 0，60_000 显示为 "60"、18_500 显示为 "18.5"。 */
function seconds(ms: number): string {
  return String(Number((ms / 1000).toFixed(3)))
}

const segmentDraft = computed<SegmentEditDraft>(() => ({
  title: segmentTitle.value,
  startMs: Math.round(Number(segmentStartSeconds.value) * 1000),
  endMs: Math.round(Number(segmentEndSeconds.value) * 1000),
}))

const editingSegment = computed<DirectorySegment | null>(
  () => segments.value.find(segment => segment.key === editingSegmentKey.value) ?? null)

const segmentDraftValid = computed(() => segmentStartSeconds.value.trim() !== ''
  && segmentEndSeconds.value.trim() !== ''
  && Number.isFinite(Number(segmentStartSeconds.value))
  && Number.isFinite(Number(segmentEndSeconds.value)))

const segmentChanged = computed(() => {
  const target = editingSegment.value
  return target !== null && segmentDraftValid.value && segmentDraftChanged(target, segmentDraft.value)
})

function beginSegmentEdit(segment: DirectorySegment): void {
  editingSegmentKey.value = segment.key
  segmentTitle.value = segment.title
  segmentStartSeconds.value = seconds(segment.range.startMs)
  segmentEndSeconds.value = seconds(segment.range.endMs)
  actionError.value = null
}

function cancelSegmentEdit(): void { editingSegmentKey.value = null }

async function saveSegment(segment: DirectorySegment): Promise<void> {
  const media = mediaId.value
  const duration = mediaDurationMs.value
  if (media === null || duration === null) return
  if (!segmentDraftValid.value) { actionError.value = '请填写开始与结束时间（秒）。'; return }
  // 前端只做提示，服务端会再强制一遍（绕过前端才是常态）。
  const verdict = validateSegmentEdit(segments.value, segment.key, segmentDraft.value, duration)
  if (!verdict.ok) { actionError.value = verdict.message; return }
  await run(async () => {
    directory.value = await saveSegmentOverride(media, segment.key, segmentDraft.value)
    editingSegmentKey.value = null
  }, segmentErrorText)
}

async function save(): Promise<void> {
  if (mediaId.value === null) return
  await run(async () => {
    review.value = await saveReportReview(mediaId.value as number, drafts.value)
    drafts.value = review.value.annotations.map(item => ({ ...item }))
  })
}

async function confirm(): Promise<void> {
  if (mediaId.value === null) return
  await run(async () => {
    // 先落草稿再确认：确认读的是服务端已保存的标注，未保存的编辑不会被漏掉。
    await saveReportReview(mediaId.value as number, drafts.value)
    review.value = await confirmReportReview(mediaId.value as number)
    drafts.value = review.value.annotations.map(item => ({ ...item }))
  })
}
</script>

<template>
  <section class="page review-page">
    <p class="eyebrow">看课堂</p>
    <h1>报告复核</h1>
    <p class="lead">
      逐条核对 AI 结论：认定有问题或予以认可，都要写下说明。确认提交后本版报告即被冻结，
      教师看到的正是这一版，之后 AI 重算不会改变它。
    </p>

    <div v-if="mediaId === null" class="empty-state">
      <h2>请先选择一节课</h2>
      <p>报告复核针对具体课堂，请从课堂分析页进入某节课后打开复核。</p>
    </div>

    <div v-else-if="phase === 'loading'" class="state-note" role="status">正在加载复核状态…</div>
    <div v-else-if="phase === 'error'" class="state-error" role="alert">
      {{ loadError?.message ?? '复核状态加载失败，请稍后重试。' }}
    </div>

    <template v-else>
      <div v-if="report === null" class="empty-state">
        <h2>这节课还没有可复核的报告</h2>
        <p>AI 报告生成后才会进入复核；当前没有可用版本，因此无法标注。</p>
      </div>

      <template v-else>
        <div v-if="forbidden" class="state-note" role="status">
          复核底稿仅教学督导可查看；你当前只能阅读 AI 报告，不能标注。
        </div>

        <div v-if="confirmed" class="review-banner" role="status">
          <strong>已确认不可修改</strong>
          <span v-if="review?.confirmedAt">确认时间：{{ review.confirmedAt }}</span>
          <span>教师看到的即为此版本，后续 AI 重算不会改变它。</span>
        </div>

        <p v-if="!forbidden" class="review-summary">
          已标注 {{ summary.total }} 条：认定有问题 {{ summary.issues }} 条 · 认可 {{ summary.acknowledged }} 条
        </p>
        <p v-if="actionError !== null" class="form-error" role="alert">{{ actionError }}</p>

        <section class="segment-directory">
          <h2>片段目录</h2>
          <p v-if="segments.length === 0" class="state-note">这份报告没有可用的主题片段。</p>
          <template v-else>
            <p v-if="canEditSegments" class="hint">
              督导可修正片段的标题与起止边界。修正不改报告本体：报告仍是不可变事实，
              教师读到的仍是冻结版本，每处修正都记录修改人、修订号与时间。
            </p>
            <ul class="segment-list">
              <li v-for="segment in segments" :key="segment.key">
                <div class="segment-row">
                  <span class="segment-order">{{ segmentLabel(segment) }}</span>
                  <span class="segment-title">{{ segment.title }}</span>
                  <span v-if="segment.overridden" class="segment-annotated">
                    已修正（原为「{{ segment.originalTitle }}」
                    {{ formatMediaTime(segment.originalStartMs ?? 0) }} –
                    {{ formatMediaTime(segment.originalEndMs ?? 0) }}）
                  </span>
                  <span v-if="annotatedTopicIndexes.has(segment.index)" class="segment-annotated">已有复核结论</span>
                  <span v-if="overlapKeys.has(segment.key)" class="verdict-chip issue">区间与其他片段重叠</span>
                  <button v-if="canEditSegments && editingSegmentKey !== segment.key" type="button"
                          class="secondary" :disabled="busy" @click="beginSegmentEdit(segment)">修正片段</button>
                </div>

                <form v-if="editingSegmentKey === segment.key" class="annotation-form"
                      @submit.prevent="saveSegment(segment)">
                  <label>标题
                    <input v-model="segmentTitle" maxlength="200"
                           placeholder="例如：提问与追问环节" />
                  </label>
                  <label>开始（秒）
                    <input v-model="segmentStartSeconds" type="number" min="0" step="0.1" />
                  </label>
                  <label>结束（秒）
                    <input v-model="segmentEndSeconds" type="number" min="0" step="0.1" />
                  </label>
                  <p class="hint">
                    课堂总时长 {{ formatMediaTime(mediaDurationMs ?? 0) }}；越界或与其他片段重叠会被服务端拒绝。
                  </p>
                  <div class="form-actions">
                    <button type="submit" :disabled="busy || !segmentChanged">保存片段修正</button>
                    <button type="button" class="secondary" :disabled="busy" @click="cancelSegmentEdit">取消</button>
                    <span v-if="!segmentChanged" class="hint">标题或边界尚未改动。</span>
                  </div>
                </form>
              </li>
            </ul>
          </template>
        </section>

        <div class="target-list">
          <article v-for="target in targets" :key="keyOf(target.kind, target.index)" class="target-card">
            <div class="target-head">
              <span class="target-kind">{{ target.label }}</span>
              <span class="target-evidence">证据 {{ target.evidenceCount }} 条</span>
            </div>
            <p class="target-body">{{ target.body }}</p>

            <ul v-if="annotationsFor(target.kind, target.index).length > 0" class="annotation-list">
              <li v-for="annotation in annotationsFor(target.kind, target.index)"
                  :key="keyOf(annotation.targetKind, annotation.targetIndex)">
                <span class="verdict-chip" :class="annotation.verdict === 'ISSUE' ? 'issue' : 'ok'">
                  {{ VERDICT_LABELS[annotation.verdict] }}
                </span>
                {{ annotation.note }}
                <button v-if="!confirmed && !forbidden" type="button" class="secondary"
                        :disabled="busy" @click="removeAnnotation(target)">删除</button>
              </li>
            </ul>

            <template v-if="!confirmed && !forbidden">
              <form v-if="editingKey === keyOf(target.kind, target.index)" class="annotation-form"
                    @submit.prevent="submitAnnotation(target)">
                <label>结论
                  <select v-model="draftVerdict">
                    <option value="ISSUE">有问题</option>
                    <option value="ACKNOWLEDGED">认可</option>
                  </select>
                </label>
                <label>说明（必填）
                  <textarea v-model="draftNote" rows="2" maxlength="1000"
                            placeholder="写清判断依据，例如：环节划分过粗，建议按提问切分"></textarea>
                </label>
                <div class="form-actions">
                  <button type="submit" :disabled="busy">保存这条标注</button>
                  <button type="button" class="secondary" :disabled="busy" @click="cancelEdit">取消</button>
                </div>
              </form>
              <button v-else type="button" class="secondary" :disabled="busy" @click="beginEdit(target)">
                {{ annotationsFor(target.kind, target.index).length > 0 ? '修改标注' : '添加标注' }}
              </button>
            </template>
          </article>
        </div>

        <div v-if="!confirmed && !forbidden" class="form-actions review-actions">
          <button type="button" :disabled="busy" @click="save">保存草稿</button>
          <button type="button" :disabled="busy || !confirmable" @click="confirm">确认并冻结版本</button>
          <span v-if="!confirmable" class="hint">至少需要一条带说明的标注才能确认。</span>
        </div>
      </template>
    </template>
  </section>
</template>
