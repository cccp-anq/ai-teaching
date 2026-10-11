<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useOfferingQuery } from '../composables/useOfferingQuery'
import { fetchCollegeOptions, fetchOwnerCollegeOptions, fetchSemesterOptions } from '../api/lookups'
import { ApiClientError } from '../api/client'
import { MAX_WEEK, WEEKDAY_OPTIONS } from '../api/courses'
import {
  MISSING,
  activityTypeLabel,
  displayText,
  compressWeeks,
  isHttpLocator,
  locationText,
  sectionLabel,
  studentCountLabel,
  syllabusEntryLabel,
  weekdayLabel,
} from '../lib/display'
import type { CollegeLookup, SemesterLookup } from '../api/types'

const query = useOfferingQuery()
const semesters = ref<SemesterLookup[]>([])
const colleges = ref<CollegeLookup[]>([])
const ownerColleges = ref<CollegeLookup[]>([])
const lookupError = ref<string | null>(null)

const totalPages = computed(() => {
  if (query.total.value <= 0) return 0
  return Math.floor((query.total.value - 1) / query.size.value) + 1
})

onMounted(() => {
  void loadLookups()
  void query.search()
})

async function loadLookups(): Promise<void> {
  lookupError.value = null
  try {
    const [semesterList, collegeList, ownerCollegeList] = await Promise.all([
      fetchSemesterOptions(), fetchCollegeOptions(), fetchOwnerCollegeOptions(),
    ])
    semesters.value = semesterList
    colleges.value = collegeList
    ownerColleges.value = ownerCollegeList
  } catch (cause) {
    lookupError.value = cause instanceof ApiClientError ? cause.message : '筛选选项加载失败。'
  }
}
</script>

<template>
  <section class="page">
    <p class="eyebrow">Sprint 1 · QRY-01/02/03</p>
    <h1>开课查询</h1>
    <p class="lead">按学期、课程、教师与上课时间地点组合查询授权范围内的开课；时间地点类条件必须命中同一条上课安排。</p>

    <form class="card filter-card form-grid" aria-label="开课筛选条件" @submit.prevent="query.applyFilters()">
      <div class="form-heading">
        <div>
          <span class="section-kicker">FILTERS</span>
          <h2>组合筛选</h2>
        </div>
        <p>课程条件与时间地点条件可叠加使用</p>
      </div>
      <div class="field">
        <label for="f-semester">学期</label>
        <select id="f-semester" v-model.number="query.filters.semesterId">
          <option :value="null">全部学期</option>
          <option v-for="semester in semesters" :key="semester.semesterId" :value="semester.semesterId">
            {{ semester.semesterName }}（{{ semester.semesterCode }}）
          </option>
        </select>
      </div>
      <div class="field">
        <label for="f-course-name">课程名称</label>
        <input id="f-course-name" v-model="query.filters.courseName" type="text" placeholder="名称片段，不区分拉丁字母大小写">
      </div>
      <div class="field">
        <label for="f-course-code">课程号</label>
        <input id="f-course-code" v-model="query.filters.courseCode" type="text" placeholder="正式课程号，可为空">
      </div>
      <div class="field">
        <label for="f-teacher">教师姓名</label>
        <input id="f-teacher" v-model="query.filters.teacher" type="text" placeholder="教师姓名片段">
      </div>
      <div class="field">
        <label for="f-college">来源学院（数据范围）</label>
        <select id="f-college" v-model.number="query.filters.sourceScopeCollegeId">
          <option :value="null">全部来源</option>
          <option v-for="college in colleges" :key="college.collegeId" :value="college.collegeId">
            {{ college.collegeName }}（{{ college.collegeCode }}）
          </option>
        </select>
      </div>
      <div class="field">
        <label for="f-owner-college">课程归属学院</label>
        <select id="f-owner-college" v-model.number="query.filters.ownerCollegeId">
          <option :value="null">全部归属</option>
          <option v-for="college in ownerColleges" :key="college.collegeId" :value="college.collegeId">
            {{ college.collegeName }}（{{ college.collegeCode }}）
          </option>
        </select>
      </div>
      <div class="field">
        <label for="f-week">周次</label>
        <select id="f-week" v-model.number="query.filters.week">
          <option :value="null">全部周次</option>
          <option v-for="week in MAX_WEEK" :key="week" :value="week">第{{ week }}周</option>
        </select>
      </div>
      <div class="field">
        <label for="f-weekday">星期</label>
        <select id="f-weekday" v-model.number="query.filters.weekday">
          <option :value="null">全部星期</option>
          <option v-for="day in WEEKDAY_OPTIONS" :key="day" :value="day">{{ weekdayLabel(day) }}</option>
        </select>
      </div>
      <div class="field">
        <label for="f-section">节次</label>
        <input id="f-section" v-model.number="query.filters.section" type="number" min="1" step="1" placeholder="上课节次">
      </div>
      <div class="field">
        <label for="f-location">上课地点</label>
        <input id="f-location" v-model="query.filters.location" type="text" placeholder="地点名称片段">
      </div>
      <p v-if="lookupError" class="form-error" role="alert">{{ lookupError }}</p>
      <div class="actions">
        <button type="submit" :disabled="query.status.value === 'loading'">查询</button>
        <button type="button" class="secondary" :disabled="query.status.value === 'loading'" @click="query.clearFilters()">
          清空筛选
        </button>
      </div>
    </form>

    <div v-if="query.status.value === 'loading'" class="state-note" role="status">正在查询…</div>

    <div v-if="query.status.value === 'error' && query.error.value" class="state-error" role="alert">
      <p>{{ query.error.value.message }}</p>
      <p v-if="query.error.value.status === 403">当前账号没有访问此功能的权限。</p>
      <p v-if="query.error.value.status === 401">
        请先<RouterLink :to="{ path: '/login', query: { redirect: '/offerings' } }">登录</RouterLink>后查询。
      </p>
    </div>

    <div v-if="query.status.value === 'ready' && query.total.value === 0" class="empty-state">
      <h2>未找到符合当前筛选条件的开课</h2>
      <p>可以调整条件，或清空筛选恢复授权范围内的完整列表。</p>
      <button type="button" @click="query.clearFilters()">清空筛选</button>
    </div>

    <section v-if="query.status.value === 'ready' && query.total.value > 0" class="card result-section">
      <div class="result-toolbar">
        <div><span class="section-kicker">RESULTS</span><h2>查询结果</h2></div>
        <strong>{{ query.total.value }} 条开课</strong>
      </div>
      <div class="table-wrap">
      <table>
        <caption>每条为一门开课，不因多条上课安排而重复</caption>
        <thead>
          <tr>
            <th scope="col">课程 / 活动名称</th>
            <th scope="col">课程号</th>
            <th scope="col">类型</th>
            <th scope="col">班级</th>
            <th scope="col">学期</th>
            <th scope="col">归属学院</th>
            <th scope="col">教师</th>
            <th scope="col">全部上课安排</th>
            <th scope="col">学生人次</th>
            <th scope="col">大纲</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in query.items.value" :key="item.offeringId">
            <th scope="row">{{ item.displayName }}</th>
            <td>{{ item.courseCode ?? MISSING }}</td>
            <td>{{ activityTypeLabel(item.activityType) }}</td>
            <td>{{ displayText(item.classGroupText) }}</td>
            <td>{{ item.semesterName }}</td>
            <td>{{ item.ownerCollege?.collegeName ?? MISSING }}</td>
            <td>{{ item.teacherNames.join('、') || MISSING }}</td>
            <td>
              <ul v-if="item.schedules.length > 0" class="compact-list">
                <li v-for="schedule in item.schedules" :key="schedule.scheduleId">
                  {{ weekdayLabel(schedule.weekday) }} {{ sectionLabel(schedule.startSection, schedule.endSection) }}；
                  第{{ compressWeeks(schedule.weeks) }}周；{{ locationText(schedule.location) }}
                </li>
              </ul>
              <span v-else>{{ MISSING }}</span>
            </td>
            <td>{{ studentCountLabel(item.studentCount) }}</td>
            <td>
              <a v-if="isHttpLocator(item.syllabusUrl)" :href="item.syllabusUrl ?? undefined"
                 target="_blank" rel="noopener noreferrer">
                {{ syllabusEntryLabel(item.syllabusUrl, item.syllabusStatus) }}
              </a>
              <span v-else>{{ syllabusEntryLabel(item.syllabusUrl, item.syllabusStatus) }}</span>
            </td>
            <td>
              <RouterLink :to="`/offerings/detail/${item.offeringId}`">查看详情</RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <nav class="pager" aria-label="分页">
        <button type="button" :disabled="query.page.value <= 1"
                @click="query.goToPage(query.page.value - 1)">上一页</button>
        <span>第 {{ query.page.value }} / {{ totalPages }} 页</span>
        <button type="button" :disabled="query.page.value >= totalPages"
                @click="query.goToPage(query.page.value + 1)">下一页</button>
      </nav>
    </section>
  </section>
</template>
