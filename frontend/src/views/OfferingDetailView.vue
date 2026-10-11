<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RouterLink } from 'vue-router'
import { fetchOfferingDetail } from '../api/courses'
import { ApiClientError } from '../api/client'
import { classroomUploadAttempt, fetchClassrooms, uploadClassroomVideo } from '../api/classroom'
import type { ClassroomUploadAttempt } from '../api/classroom'
import { addPick } from '../stores/comparisonPick'
import { sessionState } from '../stores/session'
import { schedulesForClassDate } from '../lib/classroomSchedule'
import {
  MISSING,
  activityTypeLabel,
  compressWeeks,
  displayText,
  isHttpLocator,
  locationText,
  sectionLabel,
  studentCountLabel,
  syllabusEntryLabel,
  weekdayLongLabel,
} from '../lib/display'
import type { ClassroomAnalysis, CourseOfferingDetail } from '../api/types'

type DetailPhase = 'loading' | 'ready' | 'not-visible' | 'unauthorized' | 'forbidden' | 'error'

const route = useRoute()
const router = useRouter()
const detail = ref<CourseOfferingDetail | null>(null)
const phase = ref<DetailPhase>('loading')
const errorMessage = ref<string | null>(null)
const pickMessage = ref<string | null>(null)
const classrooms = ref<ClassroomAnalysis[]>([])
const classroomError = ref<string | null>(null)
const uploadError = ref<string | null>(null)
const uploading = ref(false)
const videoFile = ref<File | null>(null)
const videoDurationMs = ref<number | null>(null)
const selectedScheduleId = ref<number | null>(null)
const sessionDate = ref('')
const sessionTitle = ref('')
const uploadAttempt = ref<ClassroomUploadAttempt | null>(null)
const uploadProgress = ref<number | null>(null)

const offeringId = computed(() => {
  const raw = route.params.offeringId
  const value = typeof raw === 'string' ? raw.trim() : ''
  return /^\d+$/.test(value) ? Number(value) : null
})

const invalidId = computed(() => offeringId.value === null)
const scheduleMatch = computed(() => schedulesForClassDate(
  detail.value?.schedules ?? [], detail.value?.semester.firstWeekDate ?? null, sessionDate.value,
))
watch([sessionDate, detail], () => {
  selectedScheduleId.value = scheduleMatch.value.mode === 'matched'
    ? scheduleMatch.value.schedules[0]?.scheduleId ?? null : null
})
const canUpload = computed(() => {
  const user = sessionState.currentUser
  const id = offeringId.value
  return user !== null && id !== null && (
    (user.roles.includes('ADMIN') && user.authorizationScopes.some(scope =>
      scope.roleCode === 'ADMIN' && scope.scopeType === 'GLOBAL'
      && scope.offeringId === null && scope.collegeId === null && scope.teacherId === null))
    || (user.roles.includes('SUPERVISOR') && user.authorizationScopes.some(scope =>
      scope.roleCode === 'SUPERVISOR' && scope.scopeType === 'OFFERING'
      && scope.offeringId === id && scope.collegeId === null && scope.teacherId === null))
  )
})

onMounted(() => {
  void loadDetail()
})

async function loadDetail(): Promise<void> {
  pickMessage.value = null
  if (offeringId.value === null) {
    phase.value = 'error'
    errorMessage.value = '开课编号不合法，请从查询列表进入详情。'
    return
  }
  phase.value = 'loading'
  try {
    detail.value = await fetchOfferingDetail(offeringId.value)
    phase.value = 'ready'
    void loadClassrooms(offeringId.value)
  } catch (cause) {
    detail.value = null
    if (cause instanceof ApiClientError) {
      if (cause.status === 404) phase.value = 'not-visible'
      else if (cause.status === 401) phase.value = 'unauthorized'
      else if (cause.status === 403) phase.value = 'forbidden'
      else {
        phase.value = 'error'
        errorMessage.value = cause.message
      }
      return
    }
    phase.value = 'error'
    errorMessage.value = '加载开课详情失败，请稍后重试。'
  }
}

async function loadClassrooms(id: number): Promise<void> {
  classroomError.value = null
  try { classrooms.value = await fetchClassrooms(id) }
  catch { classroomError.value = '课堂分析记录加载失败，请稍后重试。' }
}

async function selectVideo(event: Event): Promise<void> {
  uploadError.value = null
  videoDurationMs.value = null
  uploadAttempt.value = null
  const input = event.target as HTMLInputElement
  videoFile.value = input.files?.[0] ?? null
  if (videoFile.value === null) return
  const url = URL.createObjectURL(videoFile.value)
  try {
    videoDurationMs.value = await new Promise<number>((resolve, reject) => {
      const media = document.createElement('video')
      media.preload = 'metadata'
      media.onloadedmetadata = () => Number.isFinite(media.duration) && media.duration > 0
        ? resolve(Math.round(media.duration * 1000)) : reject(new Error('duration'))
      media.onerror = () => reject(new Error('metadata'))
      media.src = url
    })
  } catch {
    videoFile.value = null
    uploadError.value = '无法读取视频时长，请确认文件是有效的 MP4 或 WebM。'
  } finally { URL.revokeObjectURL(url) }
}

async function uploadVideo(): Promise<void> {
  if (!canUpload.value) return
  if (offeringId.value === null || sessionDate.value === '' || selectedScheduleId.value === null
      || !scheduleMatch.value.schedules.some(schedule => schedule.scheduleId === selectedScheduleId.value)
      || videoFile.value === null || videoDurationMs.value === null) {
    uploadError.value = '请先选择实际课堂日期、对应排课和有效视频。'
    return
  }
  uploading.value = true
  uploadProgress.value = 0
  uploadError.value = null
  try {
    const fingerprint = JSON.stringify([
      offeringId.value, selectedScheduleId.value, sessionDate.value, sessionTitle.value.trim(),
      videoDurationMs.value, videoFile.value.name, videoFile.value.type,
      videoFile.value.size, videoFile.value.lastModified,
    ])
    const attempt = classroomUploadAttempt(uploadAttempt.value, fingerprint)
    uploadAttempt.value = attempt
    const result = await uploadClassroomVideo({
      offeringId: offeringId.value, idempotencyKey: attempt.idempotencyKey,
      scheduleId: selectedScheduleId.value,
      sessionDate: sessionDate.value, title: sessionTitle.value.trim(),
      durationMs: videoDurationMs.value, video: videoFile.value,
      onProgress: (loaded, total) => {
        uploadProgress.value = total && total > 0 ? Math.min(99, Math.round(loaded / total * 100)) : null
      },
    })
    await router.push(`/classrooms/${result.classSessionId}`)
  } catch (cause) {
    uploadError.value = cause instanceof ApiClientError
      ? (cause.status === 413 ? '视频超过服务器当前允许大小，请联系管理员确认上传上限。'
        : cause.code === 'NETWORK_ERROR' ? '上传连接中断，请确认服务仍在运行后重试。' : cause.message)
      : '上传失败，请稍后重试。'
  } finally { uploading.value = false; uploadProgress.value = null }
}

function addToComparison(teacherId: number, displayName: string): void {
  if (addPick({ teacherId, displayName })) {
    pickMessage.value = `已把 ${displayName} 加入比较（可在“多教师比较”页查看）。`
    return
  }
  pickMessage.value = `未能加入比较：可能已选择该教师，或已达到 5 位上限。`
}
</script>

<template>
  <section class="page">
    <p class="eyebrow">Sprint 1 · DET-01</p>
    <h1>开课详情</h1>

    <p v-if="invalidId" class="state-error" role="alert">
      开课编号不合法，请从<RouterLink to="/offerings">开课查询列表</RouterLink>进入详情。
    </p>

    <div v-else-if="phase === 'loading'" class="state-note" role="status">正在加载开课详情…</div>

    <div v-else-if="phase === 'not-visible'" class="state-error" role="alert">
      <p>该开课不存在或当前账号不可见。</p>
      <p><RouterLink to="/offerings">返回开课查询</RouterLink></p>
    </div>

    <div v-else-if="phase === 'unauthorized'" class="state-error" role="alert">
      <p>尚未登录或登录状态已失效。</p>
      <p><RouterLink :to="{ path: '/login', query: { redirect: route.fullPath } }">重新登录</RouterLink></p>
    </div>

    <div v-else-if="phase === 'forbidden'" class="state-error" role="alert">
      <p>当前账号没有访问此功能的权限。</p>
    </div>

    <div v-else-if="phase === 'error'" class="state-error" role="alert">
      <p>{{ errorMessage ?? '加载失败，请稍后重试。' }}</p>
      <button type="button" @click="loadDetail">重试</button>
    </div>

    <template v-else-if="detail !== null">
      <section class="card" aria-label="开课基本信息">
        <h2>{{ detail.displayName }}</h2>
        <dl class="fact-grid">
          <div><dt>类型</dt><dd>{{ activityTypeLabel(detail.activityType) }}</dd></div>
          <div><dt>学期</dt><dd>{{ detail.semester.semesterName }}（{{ detail.semester.semesterCode }}）</dd></div>
          <div><dt>课程号</dt><dd>{{ displayText(detail.courseCode) }}</dd></div>
          <div><dt>课程名称（正式）</dt><dd>{{ displayText(detail.courseName) }}</dd></div>
          <div><dt>班级</dt><dd>{{ displayText(detail.classGroupText) }}</dd></div>
          <div><dt>学生人次</dt><dd>{{ studentCountLabel(detail.studentCount) }}</dd></div>
          <div class="fact-description"><dt>课程简介</dt><dd>{{ detail.description?.trim() || '暂无课程简介' }}</dd></div>
          <div><dt>数据来源学院</dt><dd>{{ displayText(detail.sourceScopeCollege?.collegeName) }}</dd></div>
          <div><dt>课程归属学院</dt><dd>{{ displayText(detail.ownerCollege?.collegeName) }}</dd></div>
          <div>
            <dt>课程大纲</dt>
            <dd>
              <template v-if="detail.syllabusStatus === 'AVAILABLE' && isHttpLocator(detail.syllabusUrl)">
                <a :href="detail.syllabusUrl ?? '#'" target="_blank" rel="noopener noreferrer">查看课程大纲</a>
              </template>
              <template v-else>{{ syllabusEntryLabel(detail.syllabusUrl, detail.syllabusStatus) }}</template>
            </dd>
          </div>
        </dl>
        <p class="fact-note">
          开课教师（按安排汇总）：{{ detail.teachers.map(teacher => teacher.displayName).join('、') || MISSING }}
        </p>
      </section>

      <section class="card" aria-label="上课安排">
        <h3>上课安排（{{ detail.schedules.length }} 条，每条为一条时间—地点—教师事实）</h3>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">周次</th>
                <th scope="col">星期</th>
                <th scope="col">节次</th>
                <th scope="col">地点</th>
                <th scope="col">该安排的教师</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="schedule in detail.schedules" :key="schedule.scheduleId">
                <td>
                  {{ compressWeeks(schedule.weeks) }}
                  <span v-if="schedule.rawWeekText" class="muted">（原始：{{ schedule.rawWeekText }}）</span>
                </td>
                <td>{{ weekdayLongLabel(schedule.weekday) }}</td>
                <td>{{ sectionLabel(schedule.startSection, schedule.endSection) }}</td>
                <td>{{ locationText(schedule.location) }}</td>
                <td>
                  <span v-for="teacher in schedule.teachers" :key="teacher.teacherId" class="teacher-chip">
                    {{ teacher.displayName }}
                    <button
                      v-if="sessionState.currentUser !== null"
                      type="button"
                      class="chip-add"
                      :aria-label="`把 ${teacher.displayName} 加入多教师比较`"
                      @click="addToComparison(teacher.teacherId, teacher.displayName)"
                    >＋比较</button>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="pickMessage !== null" class="state-note" role="status">{{ pickMessage }}</p>
        <p class="fact-note">
          提示：同一安排内多位教师为共同授课；不同安排可以使用不同教师。选择教师后前往
          <RouterLink to="/comparison">多教师比较</RouterLink>页面查看。
        </p>
      </section>

      <section class="card" aria-label="课堂视频与分析">
        <h3>课堂视频与AI分析</h3>
        <p v-if="canUpload" class="fact-note">上传某次课堂的视频后，系统会自动建立转写、抬头率和分析任务。真实识别模块尚未配置时，任务会明确显示“等待外部模块”，不会生成虚假真实报告。</p>
        <form v-if="canUpload" class="form-grid" @submit.prevent="uploadVideo">
          <div class="field">
            <label for="classroom-date">实际课堂日期</label>
            <input id="classroom-date" v-model="sessionDate" type="date" required>
          </div>
          <div class="field">
            <label for="classroom-schedule">对应排课</label>
            <select id="classroom-schedule" v-model="selectedScheduleId"
                    :disabled="scheduleMatch.mode === 'select-date' || scheduleMatch.mode === 'no-match'" required>
              <option :value="null" disabled>请选择对应排课</option>
              <option v-for="schedule in scheduleMatch.schedules" :key="schedule.scheduleId" :value="schedule.scheduleId">
                {{ compressWeeks(schedule.weeks) }} · {{ weekdayLongLabel(schedule.weekday) }} · {{ sectionLabel(schedule.startSection, schedule.endSection) }} · {{ locationText(schedule.location) }}
              </option>
            </select>
            <span v-if="scheduleMatch.mode === 'matched' && scheduleMatch.schedules.length > 1" class="muted">
              当天有 {{ scheduleMatch.schedules.length }} 节排课，已默认选择第一节；可在下拉列表中调整。
            </span>
            <span v-else-if="scheduleMatch.mode === 'no-match'" class="form-error" role="status">该日期没有匹配的排课，请更换日期。</span>
            <span v-else-if="scheduleMatch.mode === 'manual'" class="muted">该学期未配置有效首周日期，无法自动判断周次，请手动选择排课。</span>
          </div>
          <div class="field">
            <label for="classroom-title">课堂标题（可选）</label>
            <input id="classroom-title" v-model="sessionTitle" maxlength="255" placeholder="例如：递归与函数调用">
          </div>
          <div class="field">
            <label for="classroom-video">课堂视频</label>
            <input id="classroom-video" type="file" accept="video/mp4,video/webm,.mp4,.webm" required @change="selectVideo">
            <span class="muted">支持 MP4、WebM；允许大小以当前服务器配置为准。</span>
            <span v-if="videoDurationMs !== null" class="muted">已读取时长：{{ Math.round(videoDurationMs / 1000) }} 秒</span>
            <span v-if="uploading" class="muted" role="status">
              {{ uploadProgress === null ? '正在上传，已发送字节数由浏览器持续更新…' : `正在上传：${uploadProgress}%` }}
            </span>
          </div>
          <p v-if="uploadError !== null" class="form-error" role="alert">{{ uploadError }}</p>
          <div class="actions"><button type="submit" :disabled="uploading || selectedScheduleId === null">{{ uploading ? '正在上传…' : '上传并开始分析' }}</button></div>
        </form>
        <div v-if="classroomError !== null" class="state-error" role="alert">{{ classroomError }}</div>
        <p v-else-if="classrooms.length === 0" class="empty-inline">尚未上传课堂视频。</p>
        <ul v-else class="classroom-list">
          <li v-for="classroom in classrooms" :key="classroom.classSessionId">
            <RouterLink :to="`/classrooms/${classroom.classSessionId}`">{{ classroom.sessionDate }} · {{ classroom.title }}</RouterLink>
            <span class="muted">{{ classroom.media[0]?.report?.status ?? 'WAITING_INPUT' }}</span>
          </li>
        </ul>
      </section>

    </template>
  </section>
</template>
