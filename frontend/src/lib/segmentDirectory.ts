import type { GeneratedClassroomReport, GeneratedReportEvidenceRef } from '../api/types'
import { formatMediaTime } from './evidencePlayback'
import type { MediaRange } from './evidencePlayback'

/**
 * S2-01 主题片段目录；S2-10 的边界校验规则也在这里，前后端共用同一套错误码，
 * 避免"前端提示合法、服务端拒绝"这种两套口径。
 *
 * 片段目前不是独立实体：它就是生成报告 topics[] 的三元组。目录只做组织与呈现，
 * 不复制一份"片段事实"，否则会与报告产生双份真相。
 */

export interface DirectorySegment {
  key: string
  index: number
  title: string
  range: MediaRange
  durationMs: number
  evidenceCount: number
}

/** 目录按开始时间排序；报告里 topics 的顺序不一定与时间顺序一致。 */
export function segmentDirectory(report: GeneratedClassroomReport | null): DirectorySegment[] {
  if (report === null) return []
  return report.topics
    .map((topic, index) => ({
      key: `topic-${index}`,
      index,
      title: topic.summary,
      range: { startMs: topic.startMs, endMs: topic.endMs },
      durationMs: Math.max(0, topic.endMs - topic.startMs),
      evidenceCount: topic.evidenceRefs.length,
    }))
    .sort((left, right) => left.range.startMs - right.range.startMs
      || left.range.endMs - right.range.endMs
      || left.index - right.index)
}

/** 目录的可读标签，例如 "02｜01:20 – 01:50（30 秒，3 条证据）"。 */
export function segmentLabel(segment: DirectorySegment): string {
  const seconds = Math.round(segment.durationMs / 1000)
  return `${String(segment.index + 1).padStart(2, '0')}｜${formatMediaTime(segment.range.startMs)}`
    + ` – ${formatMediaTime(segment.range.endMs)}（${seconds} 秒，${segment.evidenceCount} 条证据）`
}

export interface OverlapPair {
  left: DirectorySegment
  right: DirectorySegment
}

/** 半开区间 [start,end) 相交即算重叠；用于目录提示，也用于 S2-10 的保存校验。 */
export function findOverlaps(segments: DirectorySegment[]): OverlapPair[] {
  const sorted = [...segments].sort((left, right) => left.range.startMs - right.range.startMs)
  const overlaps: OverlapPair[] = []
  for (let i = 1; i < sorted.length; i++) {
    const previous = sorted[i - 1]
    const current = sorted[i]
    if (previous === undefined || current === undefined) continue
    if (current.range.startMs < previous.range.endMs) overlaps.push({ left: previous, right: current })
  }
  return overlaps
}

export type SegmentEditCode =
  | 'SEGMENT_TITLE_REQUIRED'
  | 'SEGMENT_RANGE_INVALID'
  | 'SEGMENT_OUT_OF_MEDIA'
  | 'SEGMENT_OVERLAP'
  | 'SEGMENT_UNKNOWN'

export interface SegmentEditDraft {
  title: string
  startMs: number
  endMs: number
}

export interface SegmentEditVerdict {
  ok: boolean
  code: SegmentEditCode | null
  message: string
}

const OK: SegmentEditVerdict = { ok: true, code: null, message: '' }

/**
 * 校验一次片段修改。规则与后端保持一致，且**顺序固定**（先标题、再区间、再越界、最后重叠），
 * 这样前后端对同一个输入给出同一个错误码。
 */
export function validateSegmentEdit(
  segments: DirectorySegment[],
  key: string,
  draft: SegmentEditDraft,
  mediaDurationMs: number,
): SegmentEditVerdict {
  const target = segments.find(segment => segment.key === key)
  if (target === undefined) {
    return { ok: false, code: 'SEGMENT_UNKNOWN', message: '要修改的片段不在当前报告版本中。' }
  }
  if (draft.title.trim() === '') {
    return { ok: false, code: 'SEGMENT_TITLE_REQUIRED', message: '片段标题不能为空。' }
  }
  if (draft.title.trim().length > 200) {
    return { ok: false, code: 'SEGMENT_TITLE_REQUIRED', message: '片段标题超过 200 字。' }
  }
  if (!Number.isFinite(draft.startMs) || !Number.isFinite(draft.endMs) || draft.endMs <= draft.startMs) {
    return { ok: false, code: 'SEGMENT_RANGE_INVALID', message: '结束时间必须晚于开始时间。' }
  }
  if (draft.startMs < 0 || draft.endMs > mediaDurationMs) {
    return { ok: false, code: 'SEGMENT_OUT_OF_MEDIA', message: '片段范围超出课堂媒体时长。' }
  }
  const others = segments.filter(segment => segment.key !== key)
  const overlap = findOverlaps([...others, {
    key, index: target.index, title: draft.title, durationMs: draft.endMs - draft.startMs,
    evidenceCount: target.evidenceCount, range: { startMs: draft.startMs, endMs: draft.endMs },
  }]).find(pair => pair.left.key === key || pair.right.key === key)
  if (overlap !== undefined) {
    const other = overlap.left.key === key ? overlap.right : overlap.left
    return { ok: false, code: 'SEGMENT_OVERLAP', message: `与片段 ${other.index + 1} 的时间范围重叠。` }
  }
  return OK
}

/** S2-10 的展示辅助：报告片段与当前草稿的差异摘要（仅用于界面提示，不落库）。 */
export function segmentDraftChanged(segment: DirectorySegment, draft: SegmentEditDraft): boolean {
  return segment.title !== draft.title.trim()
    || segment.range.startMs !== draft.startMs
    || segment.range.endMs !== draft.endMs
}

/** 目录里每条片段可携带的证据引用，供点击目录项时播放区间之外再做证据定位。 */
export function segmentEvidence(report: GeneratedClassroomReport | null, key: string): GeneratedReportEvidenceRef[] {
  if (report === null) return []
  const index = Number(key.replace('topic-', ''))
  const topic = report.topics[index]
  return topic === undefined ? [] : topic.evidenceRefs
}
