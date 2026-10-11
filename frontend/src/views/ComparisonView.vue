<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchTeacherComparison } from '../api/comparison'
import { ApiClientError } from '../api/client'
import {
  MAX_COMPARISON_PICKS,
  addPick,
  clearPicks,
  comparisonPickState,
  removePick,
} from '../stores/comparisonPick'
import {
  activityTypeLabel,
  compressWeeks,
  displayText,
  locationText,
  sectionLabel,
  studentCountLabel,
  syllabusLabel,
  weekdayLongLabel,
} from '../lib/display'
import { fetchTeacherOptions } from '../api/lookups'
import type { TeacherLookup } from '../api/types'
import type { TeacherComparisonResult } from '../api/types'

type ComparisonPhase = 'idle' | 'loading' | 'ready' | 'error'

const manualTeacherId = ref('')
const teacherOptions = ref<TeacherLookup[]>([])
const teacherOptionsError = ref<string | null>(null)
const selectedTeacherOption = ref<number | null>(null)

onMounted(() => {
  void loadTeacherOptions()
})

/** Authorized-dataset teacher identities; the manual ID input stays as a fallback. */
async function loadTeacherOptions(): Promise<void> {
  teacherOptionsError.value = null
  try {
    teacherOptions.value = await fetchTeacherOptions()
  } catch {
    teacherOptionsError.value = '授权教师列表加载失败，可改用下方教师编号添加。'
  }
}

function addOptionTeacher(): void {
  errorHint.value = null
  const teacherId = selectedTeacherOption.value
  if (teacherId === null) {
    errorHint.value = '请先选择一位教师。'
    return
  }
  const displayName = teacherOptions.value.find(option => option.teacherId === teacherId)?.displayName
    ?? `教师 ${teacherId}`
  if (picks.value.length >= MAX_COMPARISON_PICKS) {
    errorHint.value = `一次最多比较 ${MAX_COMPARISON_PICKS} 位教师，请先移除一位再添加。`
    return
  }
  if (!addPick({ teacherId, displayName })) {
    errorHint.value = '该教师已在比较列表中。'
    return
  }
  selectedTeacherOption.value = null
}
const phase = ref<ComparisonPhase>('idle')
const result = ref<TeacherComparisonResult | null>(null)
const errorText = ref<string | null>(null)
const errorHint = ref<string | null>(null)

const picks = computed(() => comparisonPickState.picks)
const loading = computed(() => phase.value === 'loading')
const selectionIds = computed(() => picks.value.map(pick => pick.teacherId))
const selectionKey = computed(() => selectionIds.value.join(','))
const comparedSelectionKey = ref<string | null>(null)

function invalidateResult(): void {
  result.value = null
  comparedSelectionKey.value = null
  phase.value = 'idle'
}

function removeSelected(teacherId: number): void {
  removePick(teacherId)
  invalidateResult()
}

function clearSelection(): void {
  clearPicks()
  invalidateResult()
}

function addManualTeacherId(): void {
  const value = manualTeacherId.value.trim()
  errorText.value = null
  if (!/^\d+$/.test(value) || Number(value) <= 0) {
    errorHint.value = '请输入正整数教师编号。教师编号可从开课详情页的“＋比较”获得。'
    return
  }
  if (picks.value.length >= MAX_COMPARISON_PICKS) {
    errorHint.value = `一次最多比较 ${MAX_COMPARISON_PICKS} 位教师，请先移除一位再添加。`
    return
  }
  if (!addPick({ teacherId: Number(value), displayName: `教师 ${value}` })) {
    errorHint.value = '该教师已在比较列表中。'
    return
  }
  invalidateResult()
  manualTeacherId.value = ''
  errorHint.value = null
}

async function compare(): Promise<void> {
  if (loading.value) return
  errorText.value = null
  errorHint.value = null
  if (selectionIds.value.length === 0) {
    phase.value = 'idle'
    errorHint.value = '请先选择至少 1 位教师：可从开课详情页点击“＋比较”，或直接输入教师编号。'
    return
  }
  if (selectionIds.value.length > MAX_COMPARISON_PICKS) {
    errorHint.value = `一次最多比较 ${MAX_COMPARISON_PICKS} 位教师（服务端也会再次校验）。`
    return
  }
  phase.value = 'loading'
  const requestedSelectionKey = selectionKey.value
  try {
    const response = await fetchTeacherComparison([...selectionIds.value])
    if (selectionKey.value !== requestedSelectionKey) {
      invalidateResult()
      return
    }
    result.value = response
    comparedSelectionKey.value = requestedSelectionKey
    phase.value = 'ready'
  } catch (cause) {
    if (selectionKey.value !== requestedSelectionKey) {
      invalidateResult()
      return
    }
    result.value = null
    phase.value = 'error'
    if (cause instanceof ApiClientError) {
      if (cause.status === 400) {
        errorText.value = '比较选择无效。'
        errorHint.value = `当前一次比较支持 1 到 ${MAX_COMPARISON_PICKS} 位教师。`
      } else if (cause.status === 422) {
        errorText.value = '所选教师不存在或不可用。'
        errorHint.value = '请检查教师编号是否来自开课详情页。'
      } else if (cause.status === 401) {
        errorText.value = '尚未登录或登录状态已失效。'
        errorHint.value = '请重新登录后再比较。'
      } else if (cause.status === 403) {
        errorText.value = '当前账号没有访问此功能的权限。'
      } else {
        errorText.value = cause.message
      }
      return
    }
    errorText.value = '比较请求失败，请稍后重试。'
  }
}
</script>

<template>
  <section class="page">
    <p class="eyebrow">Sprint 1 · CMP-01</p>
    <h1>多教师开课比较</h1>
    <p class="lead">
      在授权范围内并排查看多位教师的开课与实际上课安排；一次比较 1～{{ MAX_COMPARISON_PICKS }} 位教师（当前阶段限制，非永久规则）。
    </p>

    <section class="card" aria-label="教师选择">
      <h2>已选教师（{{ picks.length }}/{{ MAX_COMPARISON_PICKS }}）</h2>
      <p class="fact-note">
        教师来自开课详情页：在开课查询中打开任一开课详情，点击教师旁的“＋比较”即可加入；也可在下方直接输入教师编号。
      </p>
      <p v-if="picks.length === 0" class="state-note" role="status">
        尚未选择教师。请先<RouterLink to="/offerings">查询开课</RouterLink>并从详情页加入教师。
      </p>
      <ul v-else class="pick-list">
        <li v-for="pick in picks" :key="pick.teacherId">
          <span>{{ pick.displayName }}（编号 {{ pick.teacherId }}）</span>
          <button type="button" class="secondary" :disabled="loading" @click="removeSelected(pick.teacherId)">移除</button>
        </li>
      </ul>
      <div class="manual-add">
        <label for="teacher-option">从授权教师列表选择</label>
        <select id="teacher-option" v-model.number="selectedTeacherOption" :disabled="loading">
          <option :value="null" disabled>请选择教师</option>
          <option v-for="option in teacherOptions" :key="option.teacherId" :value="option.teacherId">
            {{ option.displayName }}（编号 {{ option.teacherId }}）
          </option>
        </select>
        <button type="button" :disabled="loading || teacherOptions.length === 0" @click="addOptionTeacher">添加</button>
        <button type="button" class="secondary" :disabled="loading || picks.length === 0" @click="clearSelection">清空选择</button>
      </div>
      <p v-if="teacherOptionsError !== null" class="fact-note">{{ teacherOptionsError }}</p>
      <div class="manual-add">
        <label for="manual-teacher-id">教师编号（回退方式）</label>
        <input id="manual-teacher-id" v-model="manualTeacherId" :disabled="loading" type="text" inputmode="numeric" placeholder="正整数编号">
        <button type="button" :disabled="loading" @click="addManualTeacherId">添加</button>
      </div>
      <p v-if="errorHint !== null" class="form-error" role="alert">{{ errorHint }}</p>
      <div class="actions">
        <button type="button" :disabled="loading" @click="compare">
          {{ loading ? '正在比较…' : '开始比较' }}
        </button>
      </div>
    </section>

    <div v-if="phase === 'error'" class="state-error" role="alert">
      <p>{{ errorText ?? '比较失败，请稍后重试。' }}</p>
      <p v-if="errorHint !== null">{{ errorHint }}</p>
    </div>

    <template v-if="phase === 'ready' && result !== null && comparedSelectionKey === selectionKey">
      <section
        v-for="teacher in result.teachers"
        :key="teacher.teacherId"
        class="card"
        :aria-label="`${teacher.displayName} 的开课比较`"
      >
        <h2>{{ teacher.displayName }}</h2>
        <p v-if="teacher.offerings.length === 0" class="empty-inline" role="status">
          当前授权范围内没有该教师的开课。
        </p>
        <details
          v-for="offering in teacher.offerings"
          :key="offering.offeringId"
          class="offering-block"
          :open="teacher.offerings.length <= 3"
        >
          <summary>
            <strong>{{ offering.displayName }}</strong>
            <span class="muted">
              {{ activityTypeLabel(offering.activityType) }} · {{ offering.semester.semesterName }} ·
              学生人次 {{ studentCountLabel(offering.studentCount) }} · {{ syllabusLabel(offering.syllabusStatus) }}
            </span>
            <RouterLink
              class="detail-link"
              :to="`/offerings/detail/${offering.offeringId}`"
              @click.stop
            >查看详情</RouterLink>
          </summary>
          <p class="fact-note">
            班级：{{ displayText(offering.classGroupText) }}；课程简介：{{ offering.description?.trim() || '暂无课程简介' }}
          </p>
          <p class="fact-note">该教师实际参与 {{ offering.teachingSchedules.length }} 条安排：</p>
          <p v-if="offering.teachingSchedules.length === 0" class="empty-inline">此开课在授权范围内没有该教师的安排。</p>
          <ul v-else class="schedule-list">
            <li v-for="schedule in offering.teachingSchedules" :key="schedule.scheduleId">
              {{ compressWeeks(schedule.weeks) }}周 · {{ weekdayLongLabel(schedule.weekday) }} ·
              {{ sectionLabel(schedule.startSection, schedule.endSection) }} ·
              {{ locationText(schedule.location) }} ·
              授课：{{ schedule.teachers.map(teacherDetail => teacherDetail.displayName).join(' + ') }}
            </li>
          </ul>
        </details>
      </section>
      <p class="fact-note">
        说明：每位教师只列出其通过实际任课关系参与的安排；同一条安排上的共同授课教师完整保留，不会把其他教师的安排串入。
      </p>
    </template>
  </section>
</template>
