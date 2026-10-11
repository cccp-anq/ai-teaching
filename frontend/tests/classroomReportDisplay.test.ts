import { describe, expect, it } from 'vitest'
import type { ClassroomReport, GeneratedClassroomReport } from '../src/api/types'
import { evidenceTimeForReport, objectiveClassroomAnalysis, reportPollLimitReached, shouldPollGeneratedReport, topicHeadUpObservations } from '../src/views/classroomReportDisplay'

const objective: ClassroomReport = {
  reportId: 1, status: 'READY', dataMode: 'EXTERNAL', meanHeadUpRate: 0.75,
  validCoverageRate: 0.5, estimatedAttendanceCount: null, attendanceObservedWindows: 0,
  attendanceConfidence: null, summary: null, analysis: null, evidenceStartMs: null,
  evidenceEndMs: null, errorMessage: null,
  transcript: [{ id: 'seg-1', startMs: 1000, endMs: 5000, text: '测试转写' }],
  headUpWindows: [{ id: 'win-1', startMs: 0, endMs: 10_000, quality: 'VALID',
    qualityReason: null, headUpCount: 3, validObservedCount: 4, headUpRate: 0.75 }],
}
const generated: GeneratedClassroomReport = {
  topics: [], changes: [], interpretations: [], limitations: [],
}

describe('classroom report display', () => {
  it('polls only a pending external READY report without an error or pause', () => {
    expect(shouldPollGeneratedReport(objective, null, false, null, false)).toBe(true)
    expect(shouldPollGeneratedReport(objective, null, false, null, false, 'QUEUED')).toBe(true)
    expect(shouldPollGeneratedReport(objective, null, false, null, false, 'PROCESSING')).toBe(true)
    expect(shouldPollGeneratedReport(objective, null, false, null, false, 'COMPLETED')).toBe(true)
    expect(shouldPollGeneratedReport(objective, null, false, null, false, 'PAUSED')).toBe(false)
    expect(shouldPollGeneratedReport(objective, null, false, null, false, 'NOT_QUEUED')).toBe(false)
    expect(shouldPollGeneratedReport(objective, null, false, null, false, 'NEEDS_REVIEW')).toBe(false)
    expect(shouldPollGeneratedReport(objective, generated, false, null, false)).toBe(false)
    expect(shouldPollGeneratedReport(objective, null, true, null, false)).toBe(false)
    expect(shouldPollGeneratedReport(objective, null, false, '加载失败', false)).toBe(false)
    expect(shouldPollGeneratedReport(objective, null, false, null, true)).toBe(false)
    expect(shouldPollGeneratedReport({ ...objective, status: 'WAITING_INPUT' }, null, false, null, false)).toBe(false)
    expect(shouldPollGeneratedReport({ ...objective, dataMode: 'SIMULATED' }, null, false, null, false)).toBe(false)
    expect(reportPollLimitReached(23)).toBe(false)
    expect(reportPollLimitReached(24)).toBe(true)
  })

  it('locates both transcript and window evidence on the media timeline', () => {
    expect(evidenceTimeForReport(objective, { resultId: 9, itemType: 'SEGMENT', itemId: 'seg-1' })).toBe(1000)
    expect(evidenceTimeForReport(objective, { resultId: 10, itemType: 'WINDOW', itemId: 'win-1' })).toBe(0)
    expect(evidenceTimeForReport(objective, { resultId: 9, itemType: 'SEGMENT', itemId: 'missing' })).toBeNull()
    expect(evidenceTimeForReport(null, { resultId: 9, itemType: 'SEGMENT', itemId: 'seg-1' })).toBeNull()
  })

  it('aligns classroom content only with overlapping valid observations', () => {
    const topics: GeneratedClassroomReport = { ...generated, topics: [
      { startMs: 0, endMs: 20_000, summary: '主题一', evidenceRefs: [] },
      { startMs: 20_000, endMs: 30_000, summary: '主题二', evidenceRefs: [] },
    ] }
    expect(topicHeadUpObservations(objective, topics)).toEqual([
      { headUpRate: 0.75, validCoverageRate: 0.5 },
      { headUpRate: null, validCoverageRate: 0 },
    ])
  })

  it('builds phase, range and adjacent-change observations from saved objective windows', () => {
    const report: ClassroomReport = {
      ...objective,
      headUpWindows: [
        { id: 'w-1', startMs: 0, endMs: 10_000, quality: 'VALID', qualityReason: null,
          headUpCount: 2, validObservedCount: 4, headUpRate: 0.5 },
        { id: 'w-2', startMs: 10_000, endMs: 20_000, quality: 'VALID', qualityReason: null,
          headUpCount: 4, validObservedCount: 4, headUpRate: 1 },
        { id: 'w-3', startMs: 20_000, endMs: 30_000, quality: 'LOW_QUALITY', qualityReason: 'BLUR',
          headUpCount: null, validObservedCount: null, headUpRate: null },
        { id: 'w-4', startMs: 30_000, endMs: 40_000, quality: 'VALID', qualityReason: null,
          headUpCount: 1, validObservedCount: 4, headUpRate: 0.25 },
        { id: 'w-5', startMs: 40_000, endMs: 50_000, quality: 'VALID', qualityReason: null,
          headUpCount: 3, validObservedCount: 4, headUpRate: 0.75 },
        { id: 'w-6', startMs: 50_000, endMs: 60_000, quality: 'NO_SUBJECTS', qualityReason: null,
          headUpCount: null, validObservedCount: null, headUpRate: null },
      ],
    }
    const topics: GeneratedClassroomReport = { ...generated, topics: [
      { startMs: 1000, endMs: 5000, summary: '开场主题', evidenceRefs: [] },
      { startMs: 42_000, endMs: 45_000, summary: '收束主题', evidenceRefs: [] },
    ] }
    const analysis = objectiveClassroomAnalysis(report, topics, 60_000)

    expect(analysis.phases.map(phase => ({ rate: phase.headUpRate, coverage: phase.validCoverageRate,
      count: phase.validWindowCount, topics: phase.topicIndexes }))).toEqual([
      { rate: 0.75, coverage: 1, count: 2, topics: [0] },
      { rate: 0.25, coverage: 0.5, count: 1, topics: [] },
      { rate: 0.75, coverage: 0.5, count: 1, topics: [1] },
    ])
    expect(analysis.validWindowCount).toBe(4)
    expect(analysis.totalWindowCount).toBe(6)
    expect(analysis.lowestWindow).toMatchObject({ id: 'w-4', headUpRate: 0.25 })
    expect(analysis.highestWindow).toMatchObject({ id: 'w-2', headUpRate: 1 })
    expect(analysis.largestRise).toMatchObject({ fromWindowId: 'w-1', toWindowId: 'w-2', deltaPercentagePoints: 50 })
    expect(analysis.largestFall).toBeNull()
  })
})
