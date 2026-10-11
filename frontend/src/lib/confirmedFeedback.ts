import type {
  AnnotationTargetKind, AnnotationVerdict, ConfirmedReportSnapshot, ReviewAnnotation,
} from '../api/types'
import { formatMediaTime } from './evidencePlayback'
import type { MediaRange } from './evidencePlayback'

/**
 * S2-12：把"督导确认后的复核记录"翻译成教师能读的东西。
 * 快照里的报告与标注是冻结的，因此这里只做呈现映射，不做任何再计算。
 */

export interface FeedbackSegment {
  key: string
  kind: '话题' | '变化' | '解释'
  title: string
  detail: string
  /** 话题/变化自带时间区间；解释类结论没有自带区间，需要借助证据引用，故为 null。 */
  range: MediaRange | null
}

export interface FeedbackAnnotation {
  key: string
  verdict: AnnotationVerdict
  verdictLabel: string
  /** 标注指向的报告位置；目标在快照中不存在时如实说明，而不是显示空白。 */
  targetLabel: string
  note: string
}

function rangeOrNull(startMs: number, endMs: number): MediaRange | null {
  return Number.isFinite(startMs) && Number.isFinite(endMs) && endMs > startMs
    ? { startMs, endMs }
    : null
}

/** 已确认报告的分节，统一成"可播放的片段"列表（教师回看正式复核时使用）。 */
export function confirmedSegments(snapshot: ConfirmedReportSnapshot): FeedbackSegment[] {
  const segments: FeedbackSegment[] = []
  snapshot.report.topics.forEach((topic, index) => segments.push({
    key: `topic-${index}`,
    kind: '话题',
    title: `${formatMediaTime(topic.startMs)} – ${formatMediaTime(topic.endMs)}`,
    detail: topic.summary,
    range: rangeOrNull(topic.startMs, topic.endMs),
  }))
  snapshot.report.changes.forEach((change, index) => segments.push({
    key: `change-${index}`,
    kind: '变化',
    title: `${formatMediaTime(change.startMs)} – ${formatMediaTime(change.endMs)}`,
    detail: `相对前一窗口变化 ${change.deltaPercentagePoints.toFixed(1)} 个百分点`,
    range: rangeOrNull(change.startMs, change.endMs),
  }))
  snapshot.report.interpretations.forEach((item, index) => segments.push({
    key: `interpretation-${index}`,
    kind: '解释',
    title: item.hypothesis,
    detail: item.suggestion,
    range: null,
  }))
  return segments
}

export function verdictLabel(verdict: AnnotationVerdict): string {
  return verdict === 'ISSUE' ? '督导提出异议' : '督导认可'
}

function targetLabel(snapshot: ConfirmedReportSnapshot, kind: AnnotationTargetKind, index: number): string {
  const label = { TOPIC: '话题', CHANGE: '变化', INTERPRETATION: '解释' }[kind]
  const report = snapshot.report
  const items = kind === 'TOPIC' ? report.topics : kind === 'CHANGE' ? report.changes : report.interpretations
  const item = items[index]
  if (item === undefined) return `${label} ${index + 1}（该位置在当前确认版本中不存在）`
  if ('summary' in item) return `${label} ${index + 1}：${item.summary}`
  if ('hypothesis' in item) return `${label} ${index + 1}：${item.hypothesis}`
  return `${label} ${index + 1}`
}

/** 逐条复核说明，并把它对回报告中的具体位置。 */
export function feedbackAnnotations(snapshot: ConfirmedReportSnapshot): FeedbackAnnotation[] {
  return snapshot.annotations.map((annotation: ReviewAnnotation, index) => ({
    key: `${annotation.targetKind}-${annotation.targetIndex}-${index}`,
    verdict: annotation.verdict,
    verdictLabel: verdictLabel(annotation.verdict),
    targetLabel: targetLabel(snapshot, annotation.targetKind, annotation.targetIndex),
    note: annotation.note,
  }))
}
