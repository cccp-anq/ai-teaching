import type { ClassroomReport, GeneratedClassroomReport, GeneratedReportEvidenceRef, GeneratedReportProgressState } from '../api/types'

export const MAX_REPORT_POLLS = 24

export function reportPollLimitReached(attempts: number): boolean {
  return attempts >= MAX_REPORT_POLLS
}

export function shouldPollGeneratedReport(
  objective: ClassroomReport | null,
  generated: GeneratedClassroomReport | null,
  restricted: boolean,
  error: string | null,
  paused: boolean,
  progress: GeneratedReportProgressState | null = null,
): boolean {
  return objective?.status === 'READY' && objective.dataMode === 'EXTERNAL'
    && generated === null && !restricted && error === null && !paused
    && (progress === null || ['QUEUED', 'PROCESSING', 'COMPLETED'].includes(progress))
}

export function evidenceTimeForReport(
  objective: ClassroomReport | null,
  ref: GeneratedReportEvidenceRef,
): number | null {
  if (objective === null) return null
  if (ref.itemType === 'SEGMENT') return objective.transcript.find(item => item.id === ref.itemId)?.startMs ?? null
  return objective.headUpWindows.find(item => item.id === ref.itemId)?.startMs ?? null
}

export interface TopicHeadUpObservation {
  headUpRate: number | null
  validCoverageRate: number
}

export interface PhaseHeadUpObservation extends TopicHeadUpObservation {
  key: 'OPENING' | 'MIDDLE' | 'CLOSING'
  label: string
  startMs: number
  endMs: number
  validWindowCount: number
  topicIndexes: number[]
}

export interface HeadUpWindowObservation {
  id: string
  startMs: number
  endMs: number
  headUpRate: number
}

export interface HeadUpChangeObservation {
  fromWindowId: string
  toWindowId: string
  startMs: number
  endMs: number
  deltaPercentagePoints: number
}

export interface ObjectiveClassroomAnalysis {
  topicObservations: TopicHeadUpObservation[]
  phases: PhaseHeadUpObservation[]
  validWindowCount: number
  totalWindowCount: number
  lowestWindow: HeadUpWindowObservation | null
  highestWindow: HeadUpWindowObservation | null
  largestRise: HeadUpChangeObservation | null
  largestFall: HeadUpChangeObservation | null
}

/** Aligns validated report topics with saved valid head-up windows by media time. */
export function topicHeadUpObservations(
  objective: ClassroomReport,
  generated: GeneratedClassroomReport,
): TopicHeadUpObservation[] {
  return generated.topics.map(topic => {
    const duration = Math.max(0, topic.endMs - topic.startMs)
    let validMs = 0
    let weightedRate = 0
    for (const window of objective.headUpWindows) {
      if (window.quality !== 'VALID' || window.headUpRate === null) continue
      const overlap = Math.max(0, Math.min(topic.endMs, window.endMs) - Math.max(topic.startMs, window.startMs))
      validMs += overlap
      weightedRate += window.headUpRate * overlap
    }
    return {
      headUpRate: validMs > 0 ? weightedRate / validMs : null,
      validCoverageRate: duration > 0 ? Math.min(1, validMs / duration) : 0,
    }
  })
}

function rangeObservation(objective: ClassroomReport, startMs: number, endMs: number): TopicHeadUpObservation & { validWindowCount: number } {
  const duration = Math.max(0, endMs - startMs)
  let validMs = 0
  let weightedRate = 0
  let validWindowCount = 0
  for (const window of objective.headUpWindows) {
    if (window.quality !== 'VALID' || window.headUpRate === null) continue
    const overlap = Math.max(0, Math.min(endMs, window.endMs) - Math.max(startMs, window.startMs))
    if (overlap === 0) continue
    validWindowCount++
    validMs += overlap
    weightedRate += window.headUpRate * overlap
  }
  return {
    headUpRate: validMs > 0 ? weightedRate / validMs : null,
    validCoverageRate: duration > 0 ? Math.min(1, validMs / duration) : 0,
    validWindowCount,
  }
}

/**
 * Recomputes the report's classroom-level observations from persisted evidence.
 * It deliberately returns descriptive measurements only: no attention, learning,
 * teaching-quality or causal label is inferred from a head-up observation.
 */
export function objectiveClassroomAnalysis(
  objective: ClassroomReport,
  generated: GeneratedClassroomReport,
  durationMs: number,
): ObjectiveClassroomAnalysis {
  const safeDuration = Math.max(0, durationMs)
  const boundaries = [0, Math.floor(safeDuration / 3), Math.floor(safeDuration * 2 / 3), safeDuration]
  const phaseDefinitions = [
    { key: 'OPENING' as const, label: '开场阶段' },
    { key: 'MIDDLE' as const, label: '中段阶段' },
    { key: 'CLOSING' as const, label: '收束阶段' },
  ]
  const phases = phaseDefinitions.map((definition, index) => {
    const startMs = boundaries[index] ?? 0
    const endMs = boundaries[index + 1] ?? safeDuration
    const observation = rangeObservation(objective, startMs, endMs)
    return {
      ...definition,
      startMs,
      endMs,
      ...observation,
      topicIndexes: generated.topics.flatMap((topic, topicIndex) =>
        topic.startMs < endMs && startMs < topic.endMs ? [topicIndex] : []),
    }
  })

  const validWindows = objective.headUpWindows
    .filter((window): window is typeof window & { headUpRate: number } =>
      window.quality === 'VALID' && window.headUpRate !== null)
  const byRate = [...validWindows].sort((left, right) => left.headUpRate - right.headUpRate
    || left.startMs - right.startMs)
  const toObservation = (window: typeof validWindows[number] | undefined): HeadUpWindowObservation | null =>
    window === undefined ? null : {
      id: window.id,
      startMs: window.startMs,
      endMs: window.endMs,
      headUpRate: window.headUpRate,
    }

  const changes: HeadUpChangeObservation[] = []
  for (let index = 1; index < objective.headUpWindows.length; index++) {
    const previous = objective.headUpWindows[index - 1]
    const current = objective.headUpWindows[index]
    if (previous === undefined || current === undefined
        || previous.quality !== 'VALID' || current.quality !== 'VALID'
        || previous.headUpRate === null || current.headUpRate === null
        || previous.endMs !== current.startMs) continue
    changes.push({
      fromWindowId: previous.id,
      toWindowId: current.id,
      startMs: previous.startMs,
      endMs: current.endMs,
      deltaPercentagePoints: (current.headUpRate - previous.headUpRate) * 100,
    })
  }

  return {
    topicObservations: topicHeadUpObservations(objective, generated),
    phases,
    validWindowCount: validWindows.length,
    totalWindowCount: objective.headUpWindows.length,
    lowestWindow: toObservation(byRate[0]),
    highestWindow: toObservation(byRate.at(-1)),
    largestRise: changes.filter(change => change.deltaPercentagePoints > 0)
      .sort((left, right) => right.deltaPercentagePoints - left.deltaPercentagePoints)[0] ?? null,
    largestFall: changes.filter(change => change.deltaPercentagePoints < 0)
      .sort((left, right) => left.deltaPercentagePoints - right.deltaPercentagePoints)[0] ?? null,
  }
}
