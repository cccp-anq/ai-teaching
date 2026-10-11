import type { AxiosInstance } from 'axios'
import { fetchCsrf } from './auth'
import { http } from './client'
import type { SegmentDirectoryResponse, SegmentOverrideDraft } from './types'

/**
 * 实验三 Sprint 2：督导修正片段划分（S2-10）。
 *
 * 报告是不可变事实，所以修正不写回报告，而是存一层绑定到报告版本的覆盖：
 * `GET .../segments` 拿到的目录已经叠加了覆盖，`PUT .../segments/{key}` 保存一条修正。
 * 没有可用报告版本时 GET 返回 204，调用方渲染空状态。
 */

export async function fetchSegmentDirectory(
  mediaId: number,
  client: AxiosInstance = http,
): Promise<SegmentDirectoryResponse | null> {
  const response = await client.get<SegmentDirectoryResponse>(
    `/class-sessions/media/${mediaId}/segments`)
  return response.status === 204 ? null : response.data
}

export async function saveSegmentOverride(
  mediaId: number,
  segmentKey: string,
  draft: SegmentOverrideDraft,
  client: AxiosInstance = http,
): Promise<SegmentDirectoryResponse> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.put<SegmentDirectoryResponse>(
    `/class-sessions/media/${mediaId}/segments/${encodeURIComponent(segmentKey)}`,
    { title: draft.title.trim(), startMs: draft.startMs, endMs: draft.endMs },
    { headers: { [csrf.headerName]: csrf.token } },
  )
  return data
}

const SEGMENT_ERROR_MESSAGES: Record<string, string> = {
  SEGMENT_TITLE_REQUIRED: '片段标题不能为空，且不超过 200 字。',
  SEGMENT_RANGE_INVALID: '结束时间必须晚于开始时间。',
  SEGMENT_OUT_OF_MEDIA: '片段范围超出课堂媒体时长。',
  SEGMENT_OVERLAP: '该区间与相邻片段重叠，请调整边界。',
  SEGMENT_UNKNOWN: '要修改的片段已不在当前报告版本中，请刷新后重试。',
  SEGMENT_REPORT_NOT_READY: '这节课还没有可修正片段划分的报告版本。',
}

/** 把服务端错误码翻成可执行的中文提示；未知码回落到通用文案。 */
export function segmentErrorText(code: string, fallback: string): string {
  return SEGMENT_ERROR_MESSAGES[code] ?? fallback
}
