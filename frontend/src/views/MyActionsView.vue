<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiClientError } from '../api/client'
import {
  changeTeacherActionStatus, createTeacherAction, evidenceSummary, fetchActionSuggestions, fetchMyActions,
  nextActionStatuses, suggestionActionDraft, updateTeacherAction,
} from '../api/teacherActions'
import { fetchClassroom } from '../api/classroom'
import { evidenceRefsParam, toEvidenceRefs } from '../lib/evidenceRefs'
import { coveringRange, rangeLabel, shouldStopAtRangeEnd } from '../lib/evidencePlayback'
import type { MediaRange } from '../lib/evidencePlayback'
import type { ClassroomReport } from '../api/types'
import type {
  ActionSuggestion, ClassroomActionSuggestionResult, TeacherAction, TeacherActionStatus,
} from '../api/types'

const route = useRoute()
const mediaId = computed<number | null>(() => {
  const raw = Number(route.query.mediaId)
  return Number.isInteger(raw) && raw > 0 ? raw : null
})

const actions = ref<TeacherAction[]>([])
const suggestions = ref<ClassroomActionSuggestionResult | null>(null)
const phase = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<ApiClientError | null>(null)
const busy = ref(false)
const actionError = ref<string | null>(null)

const selectedSuggestion = ref<ActionSuggestion | null>(null)
const draftTitle = ref('')
const draftContent = ref('')
const manualTitle = ref('')
const manualContent = ref('')

const editingId = ref<number | null>(null)
const editTitle = ref('')
const editContent = ref('')

const STATUS_LABELS: Record<TeacherActionStatus, string> = {
  PLANNED: '已选择', ACTIVE: '进行中', DONE: '已完成', ABANDONED: '已放弃',
}
const TRANSITION_LABELS: Record<TeacherActionStatus, string> = {
  PLANNED: '回到已选择', ACTIVE: '开始实践', DONE: '标记完成', ABANDONED: '放弃',
}
const REASON_MESSAGES: Record<string, string> = {
  REPORT_NOT_READY: '这节课还没有可用的正式报告，暂时无法给出行动建议；你仍可以先自拟行动。',
  NO_INTERPRETATIONS: '报告中暂时没有可转化为行动的建议，仍可阅读原报告后自拟行动。',
}

const openActions = computed(() => actions.value.filter(item => item.status === 'PLANNED' || item.status === 'ACTIVE'))
const closedActions = computed(() => actions.value.filter(item => item.status === 'DONE' || item.status === 'ABANDONED'))

onMounted(() => { void load() })

async function load(): Promise<void> {
  phase.value = 'loading'
  error.value = null
  try {
    actions.value = await fetchMyActions()
    if (mediaId.value !== null) suggestions.value = await fetchActionSuggestions(mediaId.value)
    phase.value = 'ready'
  } catch (cause) {
    error.value = cause instanceof ApiClientError ? cause : null
    phase.value = 'error'
  }
}

function pickSuggestion(suggestion: ActionSuggestion): void {
  const draft = suggestionActionDraft(suggestion)
  selectedSuggestion.value = suggestion
  draftTitle.value = draft.title
  draftContent.value = draft.content
  actionError.value = null
}

async function run(operation: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  actionError.value = null
  try {
    await operation()
  } catch (cause) {
    actionError.value = cause instanceof ApiClientError ? cause.message : '操作失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}

async function adoptSuggestion(): Promise<void> {
  const suggestion = selectedSuggestion.value
  if (suggestion === null) return
  await run(async () => {
    await createTeacherAction({
      mediaId: mediaId.value, sourceKind: 'SUGGESTION', sourceIndex: suggestion.index,
      title: draftTitle.value, content: draftContent.value,
    })
    selectedSuggestion.value = null
    draftTitle.value = ''
    draftContent.value = ''
    actions.value = await fetchMyActions()
  })
}

async function createManual(): Promise<void> {
  await run(async () => {
    await createTeacherAction({
      mediaId: mediaId.value, sourceKind: 'MANUAL', sourceIndex: null,
      title: manualTitle.value, content: manualContent.value,
    })
    manualTitle.value = ''
    manualContent.value = ''
    actions.value = await fetchMyActions()
  })
}

function beginEdit(action: TeacherAction): void {
  editingId.value = action.actionId
  editTitle.value = action.title
  editContent.value = action.content
  actionError.value = null
}

async function saveEdit(action: TeacherAction): Promise<void> {
  await run(async () => {
    const updated = await updateTeacherAction(action.actionId, editTitle.value, editContent.value)
    actions.value = actions.value.map(item => item.actionId === updated.actionId ? updated : item)
    editingId.value = null
  })
}

async function changeStatus(action: TeacherAction, status: TeacherActionStatus): Promise<void> {
  await run(async () => {
    const updated = await changeTeacherActionStatus(action.actionId, status)
    actions.value = actions.value.map(item => item.actionId === updated.actionId ? updated : item)
  })
}

// ---- S2-11：回看建议关联的课堂证据 -------------------------------------------
const player = ref<HTMLVideoElement | null>(null)
const objective = ref<ClassroomReport | null>(null)
const activeRange = ref<MediaRange | null>(null)
const evidenceNotice = ref<string | null>(null)

const playbackUrl = computed(() => mediaId.value === null
  ? null
  : `/api/v1/class-sessions/media/${mediaId.value}/content`)

/** 证据引用要解析成时间必须拿到客观报告；只有 mediaId 时先按 classSessionId 取一次课堂。 */
async function ensureObjective(): Promise<ClassroomReport | null> {
  if (objective.value !== null) return objective.value
  const sessionId = suggestions.value?.classSessionId ?? null
  const currentMediaId = mediaId.value
  if (sessionId === null || currentMediaId === null) return null
  const classroom = await fetchClassroom(sessionId)
  const media = classroom.media.find(item => item.mediaId === currentMediaId) ?? null
  objective.value = media?.report ?? null
  return objective.value
}

async function playSuggestionEvidence(suggestion: ActionSuggestion): Promise<void> {
  evidenceNotice.value = null
  try {
    const report = await ensureObjective()
    const element = player.value
    if (report === null || element === null) {
      evidenceNotice.value = '暂时拿不到这节课的课堂证据，无法定位播放。'
      return
    }
    const refs = toEvidenceRefs(suggestion.evidence)
    const range = coveringRange(report, refs)
    if (range === null) {
      evidenceNotice.value = '这条建议的课堂证据当前无法定位（证据引用缺失），因此不做跳转。'
      return
    }
    element.currentTime = range.startMs / 1000
    activeRange.value = range
    await element.play()
  } catch {
    evidenceNotice.value = '浏览器阻止了自动播放，请手动点击播放器继续。'
  }
}

function onEvidenceTimeUpdate(): void {
  const element = player.value
  const range = activeRange.value
  if (element === null || range === null) return
  if (shouldStopAtRangeEnd(element.currentTime * 1000, range)) {
    element.pause()
    activeRange.value = null
  }
}

/** 供界面显示证据条数之外的定位信息，也可用于复制链接。 */
function suggestionEvidenceParam(suggestion: ActionSuggestion): string {
  return evidenceRefsParam(toEvidenceRefs(suggestion.evidence))
}
</script>

<template>
  <section class="page actions-page">
    <p class="eyebrow">帮教师</p>
    <h1>我的行动</h1>
    <p class="lead">
      行动只对你本人可见。你可以从 AI 行动建议中选一条作为下次尝试，也可以自拟；采用、修改或放弃都由你决定。
    </p>

    <div v-if="phase === 'loading'" class="state-note" role="status">正在加载…</div>
    <div v-else-if="phase === 'error'" class="state-error" role="alert">
      {{ error?.message ?? '加载失败，请稍后重试。' }}
    </div>

    <template v-else>
      <p v-if="actionError !== null" class="form-error" role="alert">{{ actionError }}</p>

      <section v-if="mediaId !== null" class="suggestion-panel">
        <h2>AI 行动建议</h2>
        <video ref="player" class="evidence-player" :src="playbackUrl ?? undefined" controls
               preload="metadata" @timeupdate="onEvidenceTimeUpdate"></video>
        <p v-if="activeRange !== null" class="state-note" role="status">
          正在播放证据片段 {{ rangeLabel(activeRange) }}
        </p>
        <p v-if="evidenceNotice !== null" class="state-note" role="status">{{ evidenceNotice }}</p>
        <p v-if="suggestions !== null && !suggestions.available" class="state-note">
          {{ suggestions.reasonCode !== null ? REASON_MESSAGES[suggestions.reasonCode] ?? '暂无可用建议。' : '暂无可用建议。' }}
        </p>
        <div v-else-if="suggestions !== null && suggestions.suggestions.length > 0" class="suggestion-list">
          <article v-for="suggestion in suggestions.suggestions" :key="suggestion.index" class="suggestion-card">
            <p v-if="suggestion.hypothesis !== null" class="suggestion-hypothesis">
              可能的解释：{{ suggestion.hypothesis }}
            </p>
            <p class="suggestion-body">{{ suggestion.suggestion }}</p>
            <p class="suggestion-evidence">{{ evidenceSummary(suggestion) }}</p>
            <p v-if="suggestion.alternatives.length > 0" class="suggestion-alternatives">
              其他可能：{{ suggestion.alternatives.join('；') }}
            </p>
            <button type="button" class="secondary" :disabled="busy || suggestion.evidence.length === 0"
                    :title="suggestionEvidenceParam(suggestion)" @click="playSuggestionEvidence(suggestion)">
              回看证据
            </button>
            <button type="button" class="secondary" :disabled="busy" @click="pickSuggestion(suggestion)">
              选为下次尝试
            </button>
          </article>
        </div>
        <p v-else class="state-note">暂无可用建议。</p>

        <form v-if="selectedSuggestion !== null" class="draft-form" @submit.prevent="adoptSuggestion">
          <h3>确认这条尝试</h3>
          <label>标题<input v-model="draftTitle" maxlength="200" required></label>
          <label>内容<textarea v-model="draftContent" rows="3" maxlength="2000" required></textarea></label>
          <div class="form-actions">
            <button type="submit" :disabled="busy">保存为我的行动</button>
            <button type="button" class="secondary" :disabled="busy" @click="selectedSuggestion = null">取消</button>
          </div>
        </form>
      </section>

      <section class="manual-panel">
        <h2>自拟一次尝试</h2>
        <form @submit.prevent="createManual">
          <label>标题<input v-model="manualTitle" maxlength="200" required placeholder="例如：提问后停顿三秒"></label>
          <label>内容<textarea v-model="manualContent" rows="3" maxlength="2000" required
            placeholder="写清你下一节课具体要做什么"></textarea></label>
          <div class="form-actions">
            <button type="submit" :disabled="busy">保存为我的行动</button>
          </div>
        </form>
      </section>

      <section class="actions-panel">
        <h2>进行中的行动</h2>
        <div v-if="openActions.length === 0" class="empty-state">
          <p>还没有进行中的行动。可以先从上面的建议里选一条，或自拟一次尝试。</p>
        </div>
        <div v-else class="action-list">
          <article v-for="action in openActions" :key="action.actionId" class="action-card">
            <template v-if="editingId === action.actionId">
              <label>标题<input v-model="editTitle" maxlength="200"></label>
              <label>内容<textarea v-model="editContent" rows="3" maxlength="2000"></textarea></label>
              <div class="form-actions">
                <button type="button" :disabled="busy" @click="saveEdit(action)">保存修改</button>
                <button type="button" class="secondary" :disabled="busy" @click="editingId = null">取消</button>
              </div>
            </template>
            <template v-else>
              <div class="action-head">
                <strong>{{ action.title }}</strong>
                <span class="status-chip">{{ STATUS_LABELS[action.status] }}</span>
                <span v-if="action.sourceKind === 'SUGGESTION'" class="source-chip">来自 AI 建议</span>
              </div>
              <p>{{ action.content }}</p>
              <div class="form-actions">
                <button v-for="next in nextActionStatuses(action.status)" :key="next" type="button"
                  class="secondary" :disabled="busy" @click="changeStatus(action, next)">
                  {{ TRANSITION_LABELS[next] }}
                </button>
                <button type="button" class="secondary" :disabled="busy" @click="beginEdit(action)">调整内容</button>
              </div>
            </template>
          </article>
        </div>

        <template v-if="closedActions.length > 0">
          <h3>历史行动</h3>
          <ul class="closed-list">
            <li v-for="action in closedActions" :key="action.actionId">
              <span class="status-chip">{{ STATUS_LABELS[action.status] }}</span>
              {{ action.title }}
            </li>
          </ul>
        </template>
      </section>
    </template>
  </section>
</template>
