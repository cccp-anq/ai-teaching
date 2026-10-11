<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchOwnerCollegeOptions, fetchSemesterOptions } from '../api/lookups'
import { ApiClientError } from '../api/client'
import {
  fetchCoverageView,
} from '../api/supervision'
import type { CoverageFilters } from '../api/supervision'
import type {
  CollegeLookup,
  CoverageOfferingRow,
  SemesterLookup,
  SupervisionCoverageSummary,
} from '../api/types'

type Phase = 'loading' | 'ready' | 'empty-scope' | 'error'

const semesters = ref<SemesterLookup[]>([])
const ownerColleges = ref<CollegeLookup[]>([])
const lookupError = ref<string | null>(null)
const filters = ref<CoverageFilters>({ semesterId: null, ownerCollegeId: null })
const summary = ref<SupervisionCoverageSummary | null>(null)
const covered = ref<CoverageOfferingRow[]>([])
const uncovered = ref<CoverageOfferingRow[]>([])
const phase = ref<Phase>('loading')
const error = ref<ApiClientError | null>(null)
const loading = computed(() => phase.value === 'loading')

const rateText = computed(() => {
  const value = summary.value
  if (value === null || value.coverageRate === null) return null
  // Formatting only: the backend rate is the single source of truth (no recomputation).
  return `${(value.coverageRate * 100).toFixed(1)}%`
})

onMounted(() => {
  void loadLookups()
  void load()
})

async function loadLookups(): Promise<void> {
  lookupError.value = null
  try {
    const [semesterList, ownerList] = await Promise.all([
      fetchSemesterOptions(), fetchOwnerCollegeOptions(),
    ])
    semesters.value = semesterList
    ownerColleges.value = ownerList
  } catch (cause) {
    lookupError.value = cause instanceof ApiClientError ? cause.message : '筛选选项加载失败。'
  }
}

async function load(): Promise<void> {
  phase.value = 'loading'
  error.value = null
  try {
    const snapshot = await fetchCoverageView(filters.value)
    summary.value = snapshot.summary
    covered.value = snapshot.covered
    uncovered.value = snapshot.uncovered
    phase.value = snapshot.summary.eligible === 0 ? 'empty-scope' : 'ready'
  } catch (cause) {
    summary.value = null
    covered.value = []
    uncovered.value = []
    error.value = cause instanceof ApiClientError ? cause : null
    phase.value = 'error'
  }
}
</script>

<template>
  <section class="page">
    <p class="eyebrow">Sprint 3 · EVA-01 / EVA-02 / EVA-03</p>
    <h1>督导覆盖率</h1>
    <p class="lead">
      在授权范围内按学期与课程归属学院查看督导覆盖情况；覆盖率采用需求暂定口径（应督导开课=筛选范围内全部开课），最终口径待业务确认。
    </p>

    <form class="card form-grid" aria-label="覆盖筛选条件" @submit.prevent="load()">
      <div class="field">
        <label for="cov-semester">学期</label>
        <select id="cov-semester" v-model.number="filters.semesterId">
          <option :value="null">全部学期</option>
          <option v-for="semester in semesters" :key="semester.semesterId" :value="semester.semesterId">
            {{ semester.semesterName }}（{{ semester.semesterCode }}）
          </option>
        </select>
      </div>
      <div class="field">
        <label for="cov-owner">课程归属学院</label>
        <select id="cov-owner" v-model.number="filters.ownerCollegeId">
          <option :value="null">全部归属</option>
          <option v-for="college in ownerColleges" :key="college.collegeId" :value="college.collegeId">
            {{ college.collegeName }}（{{ college.collegeCode }}）
          </option>
        </select>
      </div>
      <p v-if="lookupError" class="form-error" role="alert">{{ lookupError }}</p>
      <div class="actions">
        <button type="submit" :disabled="loading">查询覆盖率</button>
      </div>
    </form>

    <div v-if="phase === 'loading'" class="state-note" role="status">正在计算覆盖率…</div>

    <div v-else-if="phase === 'error' && error" class="state-error" role="alert">
      <p>{{ error.message }}</p>
      <p v-if="error.status === 403">当前账号没有访问此功能的权限。</p>
      <p v-if="error.status === 401">
        请先<RouterLink :to="{ path: '/login', query: { redirect: '/supervision/coverage' } }">登录</RouterLink>。
      </p>
    </div>

    <div v-else-if="phase === 'empty-scope'" class="empty-state">
      <h2>筛选范围内没有开课</h2>
      <p>当前筛选条件下无任何开课，覆盖率无法计算（零分母）。请调整学期或学院筛选。</p>
    </div>

    <template v-else-if="summary !== null">
      <section class="card" aria-label="覆盖率汇总">
        <h2>覆盖率汇总</h2>
        <dl class="result-facts">
          <div><dt>应督导开课（暂定口径）</dt><dd>{{ summary.eligible }}</dd></div>
          <div><dt>已覆盖</dt><dd>{{ summary.covered }}</dd></div>
          <div><dt>未覆盖</dt><dd>{{ summary.uncovered }}</dd></div>
          <div><dt>覆盖率</dt><dd>{{ rateText ?? '—' }}</dd></div>
        </dl>
        <p class="fact-note">
          口径说明：已覆盖=至少一条“已完成”督导评价（同一开课多条评价只计一次）；
          {{ rateText === null ? '当前筛选范围内无开课，覆盖率不适用。' : '覆盖率为暂定口径结果，最终管理制度待确认。' }}
        </p>
      </section>

      <section class="card" aria-label="已覆盖开课">
        <h3>已覆盖开课（{{ covered.length }}）</h3>
        <p v-if="covered.length === 0" class="empty-inline" role="status">当前筛选范围内没有已覆盖的开课。</p>
        <div v-else class="table-wrap">
          <table>
            <caption>已覆盖：存在至少一条“已完成”督导评价</caption>
            <thead>
              <tr>
                <th scope="col">课程 / 活动名称</th>
                <th scope="col">学期</th>
                <th scope="col">学生人次</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in covered" :key="row.offeringId">
                <th scope="row">{{ row.displayName }}</th>
                <td>{{ row.semesterName }}</td>
                <td>{{ row.studentCount ?? '—' }}</td>
                <td><RouterLink :to="`/offerings/detail/${row.offeringId}`">查看详情</RouterLink></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card" aria-label="未覆盖开课">
        <h3>未覆盖开课（{{ uncovered.length }}）</h3>
        <p v-if="uncovered.length === 0" class="empty-inline" role="status">当前筛选范围内没有未覆盖的开课。</p>
        <div v-else class="table-wrap">
          <table>
            <caption>未覆盖：暂无“已完成”督导评价（暂定口径）</caption>
            <thead>
              <tr>
                <th scope="col">课程 / 活动名称</th>
                <th scope="col">学期</th>
                <th scope="col">学生人次</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in uncovered" :key="row.offeringId">
                <th scope="row">{{ row.displayName }}</th>
                <td>{{ row.semesterName }}</td>
                <td>{{ row.studentCount ?? '—' }}</td>
                <td><RouterLink :to="`/offerings/detail/${row.offeringId}`">查看详情</RouterLink></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </section>
</template>
