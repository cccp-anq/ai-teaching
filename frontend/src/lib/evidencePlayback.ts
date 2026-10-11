import type { ClassroomReport, GeneratedReportEvidenceRef } from '../api/types'

/**
 * S2-02 证据联动：把报告里的证据引用解析成媒体时间区间，并驱动区间播放。
 * 这里只做纯计算，播放器与网络访问留在视图里。
 */

export interface MediaRange {
  startMs: number
  endMs: number
}

/** 播放到区间末尾就停下的容差：timeupdate 的粒度通常大于 100ms，卡得太死会来回抖动。 */
export const RANGE_STOP_TOLERANCE_MS = 120

/** 把一条证据引用解析为它指向的媒体时间区间；引用无法解析（证据缺失）时返回 null。 */
export function evidenceRange(objective: ClassroomReport, ref: GeneratedReportEvidenceRef): MediaRange | null {
  if (ref.itemType === 'SEGMENT') {
    const segment = objective.transcript.find(entry => entry.id === ref.itemId)
    return segment === undefined ? null : { startMs: segment.startMs, endMs: segment.endMs }
  }
  const window = objective.headUpWindows.find(entry => entry.id === ref.itemId)
  return window === undefined ? null : { startMs: window.startMs, endMs: window.endMs }
}

/**
 * 覆盖一组证据的最小时间区间。任一引用能解析即可播放；全部无法解析时返回 null，
 * 由界面如实说明"该证据在当前媒体中不可定位"，而不是跳到 0 秒误导用户。
 */
export function coveringRange(objective: ClassroomReport, refs: GeneratedReportEvidenceRef[]): MediaRange | null {
  const ranges = refs
    .map(ref => evidenceRange(objective, ref))
    .filter((range): range is MediaRange => range !== null)
  if (ranges.length === 0) return null
  return {
    startMs: Math.min(...ranges.map(range => range.startMs)),
    endMs: Math.max(...ranges.map(range => range.endMs)),
  }
}

/** 区间播放是否应当停下：到达（或越过）区间末尾即停。 */
export function shouldStopAtRangeEnd(currentMs: number, range: MediaRange): boolean {
  return currentMs >= range.endMs - RANGE_STOP_TOLERANCE_MS
}

/** 媒体时间显示为 mm:ss（超过一小时显示 h:mm:ss）。 */
export function formatMediaTime(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const seconds = totalSeconds % 60
  const minutes = Math.floor(totalSeconds / 60) % 60
  const hours = Math.floor(totalSeconds / 3600)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

/** 可读的区间标签，例如 "01:20 – 01:50（30 秒）"。 */
export function rangeLabel(range: MediaRange): string {
  const seconds = Math.max(0, Math.round((range.endMs - range.startMs) / 1000))
  return `${formatMediaTime(range.startMs)} – ${formatMediaTime(range.endMs)}（${seconds} 秒）`
}
