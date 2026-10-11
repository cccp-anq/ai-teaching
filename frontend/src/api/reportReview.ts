import type { AxiosInstance } from 'axios'
import { fetchCsrf } from './auth'
import { http } from './client'
import type {
  ClassroomReportReview, ConfirmedReportSnapshot, ReviewAnnotation, TeacherNotification,
} from './types'

/**
 * 实验三 Sprint 2：督导复核（S2-03 标注并记录说明 / S2-04 提交时固定复核快照）
 * 与教师通知（S2-05）。
 *
 * `http` 的 baseURL 已含 `/api/v1`，这里只写资源路径（与 classroom.ts 一致）。
 * 读接口用 204 表示"还没有"：复核未建立、报告未确认，都由调用方渲染空状态。
 */

export async function fetchReportReview(
  mediaId: number,
  client: AxiosInstance = http,
): Promise<ClassroomReportReview | null> {
  const response = await client.get<ClassroomReportReview>(
    `/class-sessions/media/${mediaId}/generated-report/review`)
  return response.status === 204 ? null : response.data
}

export async function saveReportReview(
  mediaId: number,
  annotations: ReviewAnnotation[],
  client: AxiosInstance = http,
): Promise<ClassroomReportReview> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.put<ClassroomReportReview>(
    `/class-sessions/media/${mediaId}/generated-report/review`,
    { annotations },
    { headers: { [csrf.headerName]: csrf.token } },
  )
  return data
}

export async function confirmReportReview(
  mediaId: number,
  client: AxiosInstance = http,
): Promise<ClassroomReportReview> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.post<ClassroomReportReview>(
    `/class-sessions/media/${mediaId}/generated-report/review/confirm`,
    null,
    { headers: { [csrf.headerName]: csrf.token } },
  )
  return data
}

/** 教师读到的确认版本；未确认时服务端返回 204，这里映射为 null。 */
export async function fetchConfirmedReport(
  mediaId: number,
  client: AxiosInstance = http,
): Promise<ConfirmedReportSnapshot | null> {
  const response = await client.get<ConfirmedReportSnapshot>(
    `/class-sessions/media/${mediaId}/confirmed-report`)
  return response.status === 204 ? null : response.data
}

export async function fetchMyNotifications(client: AxiosInstance = http): Promise<TeacherNotification[]> {
  const { data } = await client.get<TeacherNotification[]>('/me/notifications')
  return data
}

export async function markNotificationRead(
  notificationId: number,
  client: AxiosInstance = http,
): Promise<TeacherNotification> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.post<TeacherNotification>(
    `/me/notifications/${notificationId}/read`,
    null,
    { headers: { [csrf.headerName]: csrf.token } },
  )
  return data
}

export interface AnnotationSummary {
  total: number
  issues: number
  acknowledged: number
}

/** 复核进度摘要。未知 verdict 不计入 issues/acknowledged，但计入 total。 */
export function summarizeAnnotations(annotations: readonly ReviewAnnotation[]): AnnotationSummary {
  let issues = 0
  let acknowledged = 0
  for (const annotation of annotations) {
    if (annotation.verdict === 'ISSUE') issues += 1
    else if (annotation.verdict === 'ACKNOWLEDGED') acknowledged += 1
  }
  return { total: annotations.length, issues, acknowledged }
}

/**
 * 能否提交确认：必须已有复核记录、尚未确认、至少一条标注，且每条说明都非空白
 * （与后端"未填说明不得提交"的校验保持一致）。
 */
export function canConfirmReview(review: ClassroomReportReview | null): boolean {
  if (review === null || review.confirmed) return false
  if (review.annotations.length === 0) return false
  return review.annotations.every(annotation => annotation.note.trim().length > 0)
}

const REVIEW_ERROR_MESSAGES: Record<string, string> = {
  REVIEW_NOTE_REQUIRED: '每条标注都必须填写说明。',
  REVIEW_TARGET_INVALID: '标注的目标或结论不合法。',
  REVIEW_TARGET_DUPLICATE: '同一个目标只能标注一次。',
  REVIEW_ALREADY_CONFIRMED: '该报告版本已确认，不能再修改。',
  REVIEW_NOT_FOUND: '还没有保存过复核记录，先保存草稿再确认。',
  REVIEW_ANNOTATION_REQUIRED: '至少需要一条带说明的标注才能确认。',
  REVIEW_REPORT_NOT_READY: '这节课还没有可复核的报告版本。',
}

/** 把服务端错误码翻成可执行的中文提示；未知码回落到通用文案。 */
export function reviewErrorText(code: string, fallback: string): string {
  return REVIEW_ERROR_MESSAGES[code] ?? fallback
}
