<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiClientError } from '../api/client'
import { fetchMySchedule } from '../api/courses'
import { compressWeeks, locationText, sectionLabel, weekdayLongLabel } from '../lib/display'
import type { CourseOfferingListItem, MyScheduleView, ScheduleDetail } from '../api/types'

type Purpose = 'ALL' | 'TEACHING' | 'SUPERVISION'
type Phase = 'loading' | 'ready' | 'error'
interface ScheduleRow {
  offering: CourseOfferingListItem
  schedule: ScheduleDetail
  purposes: Array<'TEACHING' | 'SUPERVISION'>
}

const data = ref<MyScheduleView>({ teaching: [], supervision: [] })
const phase = ref<Phase>('loading')
const error = ref<ApiClientError | null>(null)
const purpose = ref<Purpose>('ALL')

const hasTeaching = computed(() => data.value.teaching.length > 0)
const hasSupervision = computed(() => data.value.supervision.length > 0)
const hasBoth = computed(() => hasTeaching.value && hasSupervision.value)

const rows = computed<ScheduleRow[]>(() => {
  const merged = new Map<number, ScheduleRow>()
  const add = (offerings: CourseOfferingListItem[], itemPurpose: 'TEACHING' | 'SUPERVISION'): void => {
    for (const offering of offerings) {
      for (const schedule of offering.schedules) {
        const existing = merged.get(schedule.scheduleId)
        if (existing !== undefined) {
          if (!existing.purposes.includes(itemPurpose)) existing.purposes.push(itemPurpose)
        } else {
          merged.set(schedule.scheduleId, { offering, schedule, purposes: [itemPurpose] })
        }
      }
    }
  }
  add(data.value.teaching, 'TEACHING')
  add(data.value.supervision, 'SUPERVISION')
  return [...merged.values()]
    .filter(row => purpose.value === 'ALL' || row.purposes.includes(purpose.value))
    .sort((a, b) => a.offering.semesterCode.localeCompare(b.offering.semesterCode)
      || a.schedule.weekday - b.schedule.weekday
      || a.schedule.startSection - b.schedule.startSection
      || a.offering.displayName.localeCompare(b.offering.displayName))
})

onMounted(() => { void load() })

async function load(): Promise<void> {
  phase.value = 'loading'
  error.value = null
  try {
    data.value = await fetchMySchedule()
    if (data.value.teaching.length > 0 && data.value.supervision.length === 0) purpose.value = 'TEACHING'
    if (data.value.supervision.length > 0 && data.value.teaching.length === 0) purpose.value = 'SUPERVISION'
    phase.value = 'ready'
  } catch (cause) {
    error.value = cause instanceof ApiClientError ? cause : null
    phase.value = 'error'
  }
}
</script>

<template>
  <section class="page schedule-page">
    <p class="eyebrow">我的工作</p>
    <h1>我的课表</h1>
    <p class="lead">授课安排和督导安排分别来自服务端身份范围；管理者的全量查看权限不会混入本人课表。</p>

    <div v-if="phase === 'loading'" class="state-note" role="status">正在加载我的课表…</div>
    <div v-else-if="phase === 'error'" class="state-error" role="alert">
      {{ error?.message ?? '课表加载失败，请稍后重试。' }}
    </div>

    <template v-else>
      <div v-if="hasBoth" class="purpose-tabs" role="tablist" aria-label="课表用途">
        <button type="button" :class="{ active: purpose === 'ALL' }" @click="purpose = 'ALL'">全部安排</button>
        <button type="button" :class="{ active: purpose === 'TEACHING' }" @click="purpose = 'TEACHING'">授课课表</button>
        <button type="button" :class="{ active: purpose === 'SUPERVISION' }" @click="purpose = 'SUPERVISION'">督导课表</button>
      </div>

      <div v-if="rows.length === 0" class="empty-state">
        <h2>当前没有{{ purpose === 'TEACHING' ? '授课' : purpose === 'SUPERVISION' ? '督导' : '' }}安排</h2>
        <p>若实际工作安排与此不符，请联系管理员核对任课关系或督导责任范围。</p>
      </div>

      <div v-else class="schedule-grid">
        <article v-for="row in rows" :key="row.schedule.scheduleId" class="schedule-card">
          <div class="schedule-card-head">
            <div>
              <span class="weekday">{{ weekdayLongLabel(row.schedule.weekday) }}</span>
              <strong>{{ sectionLabel(row.schedule.startSection, row.schedule.endSection) }}</strong>
            </div>
            <div class="purpose-tags">
              <span v-if="row.purposes.includes('TEACHING')" class="purpose-tag teaching">授课</span>
              <span v-if="row.purposes.includes('SUPERVISION')" class="purpose-tag supervision">督导</span>
            </div>
          </div>
          <h2>{{ row.offering.displayName }}</h2>
          <p>{{ row.offering.semesterName }} · {{ compressWeeks(row.schedule.weeks) }}</p>
          <p>{{ locationText(row.schedule.location) }} · {{ row.schedule.teachers.map(teacher => teacher.displayName).join('、') }}</p>
          <RouterLink :to="`/offerings/detail/${row.offering.offeringId}`">查看开课详情</RouterLink>
        </article>
      </div>
    </template>
  </section>
</template>
