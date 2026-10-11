import { describe, expect, it } from 'vitest'
import {
  RANGE_STOP_TOLERANCE_MS, coveringRange, evidenceRange, formatMediaTime, rangeLabel, shouldStopAtRangeEnd,
} from '../src/lib/evidencePlayback'
import type { ClassroomReport, GeneratedReportEvidenceRef } from '../src/api/types'

function objective(): ClassroomReport {
  return {
    reportId: 1, status: 'SUCCEEDED', dataMode: 'EXTERNAL', meanHeadUpRate: 0.6, validCoverageRate: 0.8,
    estimatedAttendanceCount: 40, attendanceObservedWindows: 3, attendanceConfidence: 'HIGH',
    summary: null, analysis: null, evidenceStartMs: 0, evidenceEndMs: 60_000, errorMessage: null,
    transcript: [
      { id: 'seg-1', startMs: 1_000, endMs: 5_000, text: '第一段' },
      { id: 'seg-2', startMs: 20_000, endMs: 26_000, text: '第二段' },
    ],
    headUpWindows: [
      { id: 'win-1', startMs: 3_000, endMs: 13_000, quality: 'VALID', qualityReason: null,
        headUpCount: 30, validObservedCount: 40, headUpRate: 0.75 },
    ],
  }
}

const segmentRef: GeneratedReportEvidenceRef = { resultId: 7, itemType: 'SEGMENT', itemId: 'seg-1' }
const windowRef: GeneratedReportEvidenceRef = { resultId: 7, itemType: 'WINDOW', itemId: 'win-1' }

describe('evidenceRange', () => {
  it('解析转写片段与抬头率窗口的区间', () => {
    expect(evidenceRange(objective(), segmentRef)).toEqual({ startMs: 1_000, endMs: 5_000 })
    expect(evidenceRange(objective(), windowRef)).toEqual({ startMs: 3_000, endMs: 13_000 })
  })

  it('引用指向不存在的证据时返回 null，而不是退化成 0 秒', () => {
    expect(evidenceRange(objective(), { resultId: 7, itemType: 'SEGMENT', itemId: 'missing' })).toBeNull()
    expect(evidenceRange(objective(), { resultId: 7, itemType: 'WINDOW', itemId: 'missing' })).toBeNull()
  })
})

describe('coveringRange', () => {
  it('取覆盖全部可解析证据的最小并集区间', () => {
    expect(coveringRange(objective(), [segmentRef, windowRef])).toEqual({ startMs: 1_000, endMs: 13_000 })
  })

  it('忽略不可解析的引用，只按能定位的证据计算', () => {
    expect(coveringRange(objective(), [
      { resultId: 7, itemType: 'SEGMENT', itemId: 'missing' }, segmentRef,
    ])).toEqual({ startMs: 1_000, endMs: 5_000 })
  })

  it('全部无法解析时返回 null，界面据此如实说明不可定位', () => {
    expect(coveringRange(objective(), [])).toBeNull()
    expect(coveringRange(objective(), [{ resultId: 7, itemType: 'WINDOW', itemId: 'missing' }])).toBeNull()
  })
})

describe('shouldStopAtRangeEnd', () => {
  const range = { startMs: 1_000, endMs: 5_000 }

  it('到达区间末尾前继续播放', () => {
    expect(shouldStopAtRangeEnd(4_000, range)).toBe(false)
  })

  it('进入末尾容差或越过末尾即停', () => {
    expect(shouldStopAtRangeEnd(5_000 - RANGE_STOP_TOLERANCE_MS, range)).toBe(true)
    expect(shouldStopAtRangeEnd(5_200, range)).toBe(true)
  })
})

describe('formatMediaTime / rangeLabel', () => {
  it('按 mm:ss 显示，超过一小时带小时位', () => {
    expect(formatMediaTime(0)).toBe('00:00')
    expect(formatMediaTime(83_000)).toBe('01:23')
    expect(formatMediaTime(3_723_000)).toBe('1:02:03')
  })

  it('负值按 0 处理，避免脏数据显示成负时间', () => {
    expect(formatMediaTime(-500)).toBe('00:00')
  })

  it('区间标签同时给出起止与时长', () => {
    expect(rangeLabel({ startMs: 80_000, endMs: 110_000 })).toBe('01:20 – 01:50（30 秒）')
  })
})
