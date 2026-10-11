import type { AxiosInstance } from 'axios'
import { fetchCsrf } from './auth'
import { http } from './client'
import type { ActionSuggestion, ClassroomActionSuggestionResult, TeacherAction, TeacherActionStatus } from './types'

/**
 * Sprint 2 帮教师链路（S2-06/07/08/09/14）。
 * 所有请求都不携带所有者标识：服务端按当前登录用户收敛，越权标识与不存在同义。
 */

export async function fetchActionSuggestions(
  mediaId: number,
  client: AxiosInstance = http,
): Promise<ClassroomActionSuggestionResult> {
  const { data } = await client.get<ClassroomActionSuggestionResult>(
    `/class-sessions/media/${mediaId}/action-suggestions`)
  return data
}

export async function fetchMyActions(client: AxiosInstance = http): Promise<TeacherAction[]> {
  const { data } = await client.get<TeacherAction[]>('/teacher-actions')
  return data
}

export interface CreateTeacherActionInput {
  mediaId: number | null
  sourceKind: 'SUGGESTION' | 'MANUAL'
  sourceIndex: number | null
  title: string
  content: string
}

export async function createTeacherAction(
  input: CreateTeacherActionInput,
  client: AxiosInstance = http,
): Promise<TeacherAction> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.post<TeacherAction>('/teacher-actions', input,
    { headers: { [csrf.headerName]: csrf.token } })
  return data
}

export async function updateTeacherAction(
  actionId: number,
  title: string,
  content: string,
  client: AxiosInstance = http,
): Promise<TeacherAction> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.put<TeacherAction>(`/teacher-actions/${actionId}`, { title, content },
    { headers: { [csrf.headerName]: csrf.token } })
  return data
}

export async function changeTeacherActionStatus(
  actionId: number,
  status: TeacherActionStatus,
  client: AxiosInstance = http,
): Promise<TeacherAction> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.post<TeacherAction>(`/teacher-actions/${actionId}/status`, { status },
    { headers: { [csrf.headerName]: csrf.token } })
  return data
}

/** 把一条 AI 建议变成可直接编辑的行动草稿；标题过长时截断，正文保留完整建议与依据。 */
export function suggestionActionDraft(suggestion: ActionSuggestion): { title: string; content: string } {
  const text = suggestion.suggestion.trim()
  const title = text.length > 40 ? `${text.slice(0, 39)}…` : text
  const basis = suggestion.hypothesis === null || suggestion.hypothesis.trim() === ''
    ? '' : `（依据：${suggestion.hypothesis.trim()}）`
  return { title, content: `${text}${basis}` }
}

/** 证据来源摘要：让「查看建议的评价来源」在界面上可核对（S2-07）。 */
export function evidenceSummary(suggestion: ActionSuggestion): string {
  const segments = suggestion.evidence.filter(item => item.itemType === 'SEGMENT').length
  const windows = suggestion.evidence.filter(item => item.itemType === 'WINDOW').length
  if (segments === 0 && windows === 0) return '暂无关联证据'
  const parts: string[] = []
  if (segments > 0) parts.push(`${segments} 段课堂转写`)
  if (windows > 0) parts.push(`${windows} 个抬头率窗口`)
  return `关联证据：${parts.join('、')}`
}

/** 某条行动当前允许的下一步状态；与后端 ALLOWED_TRANSITIONS 保持一致。 */
export function nextActionStatuses(status: TeacherActionStatus): TeacherActionStatus[] {
  if (status === 'PLANNED') return ['ACTIVE', 'ABANDONED']
  if (status === 'ACTIVE') return ['DONE', 'ABANDONED']
  return []
}
