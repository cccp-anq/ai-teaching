import { describe, expect, it } from 'vitest'
import { confirmedSegments, feedbackAnnotations, verdictLabel } from '../src/lib/confirmedFeedback'
import type { ConfirmedReportSnapshot } from '../src/api/types'

/** tsconfig 开启了 noUncheckedIndexedAccess，这里显式取元素，避免用非空断言掩盖问题。 */
function at<T>(items: T[], index: number): T {
  const item = items[index]
  if (item === undefined) throw new Error(`测试数据缺少下标 ${index}`)
  return item
}

function snapshot(): ConfirmedReportSnapshot {
  return {
    reportId: 9,
    report: {
      topics: [{ startMs: 0, endMs: 20_000, summary: '讲解递归', evidenceRefs: [] }],
      changes: [{ fromWindowId: 'w1', toWindowId: 'w2', startMs: 20_000, endMs: 30_000,
        deltaPercentagePoints: -12.5, evidenceRefs: [] }],
      interpretations: [{ hypothesis: '等待时间偏短', alternatives: [], suggestion: '停顿三秒',
        evidenceRefs: [] }],
      limitations: ['不能推断理解程度'],
    },
    annotations: [
      { targetKind: 'TOPIC', targetIndex: 0, verdict: 'ISSUE', note: '环节划分过粗' },
      { targetKind: 'INTERPRETATION', targetIndex: 0, verdict: 'ACKNOWLEDGED', note: '认可该解释' },
      { targetKind: 'CHANGE', targetIndex: 5, verdict: 'ISSUE', note: '目标已越界' },
    ],
  }
}

describe('confirmedSegments', () => {
  it('把话题、变化、解释统一成可读片段', () => {
    const segments = confirmedSegments(snapshot())
    expect(segments.map(segment => segment.kind)).toEqual(['话题', '变化', '解释'])
    expect(at(segments, 0).title).toBe('00:00 – 00:20')
    expect(at(segments, 0).detail).toBe('讲解递归')
    expect(at(segments, 1).detail).toBe('相对前一窗口变化 -12.5 个百分点')
  })

  it('话题与变化自带可播放区间', () => {
    const segments = confirmedSegments(snapshot())
    expect(at(segments, 0).range).toEqual({ startMs: 0, endMs: 20_000 })
    expect(at(segments, 1).range).toEqual({ startMs: 20_000, endMs: 30_000 })
  })

  it('解释类结论没有自带区间，返回 null 而不是编造一个', () => {
    expect(at(confirmedSegments(snapshot()), 2).range).toBeNull()
  })

  it('区间不合法（end <= start）时同样返回 null，避免播放器跳到无意义位置', () => {
    const broken = snapshot()
    broken.report.topics = [{ startMs: 5_000, endMs: 5_000, summary: '空区间', evidenceRefs: [] }]
    expect(at(confirmedSegments(broken), 0).range).toBeNull()
  })
})

describe('feedbackAnnotations', () => {
  it('把标注对回报告中的具体位置并附上可读结论', () => {
    const annotations = feedbackAnnotations(snapshot())
    expect(annotations).toHaveLength(3)
    expect(at(annotations, 0).verdictLabel).toBe('督导提出异议')
    expect(at(annotations, 0).targetLabel).toBe('话题 1：讲解递归')
    expect(at(annotations, 0).note).toBe('环节划分过粗')
    expect(at(annotations, 1).targetLabel).toBe('解释 1：等待时间偏短')
  })

  it('目标位置在确认版本中不存在时如实说明，而不是显示空白', () => {
    expect(at(feedbackAnnotations(snapshot()), 2).targetLabel).toBe('变化 6（该位置在当前确认版本中不存在）')
  })

  it('verdictLabel 覆盖两种结论', () => {
    expect(verdictLabel('ISSUE')).toBe('督导提出异议')
    expect(verdictLabel('ACKNOWLEDGED')).toBe('督导认可')
  })
})
