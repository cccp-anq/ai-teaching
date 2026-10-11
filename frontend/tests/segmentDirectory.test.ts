import { describe, expect, it } from 'vitest'
import {
  findOverlaps, segmentDirectory, segmentDraftChanged, segmentEvidence, segmentLabel, validateSegmentEdit,
} from '../src/lib/segmentDirectory'
import type { GeneratedClassroomReport } from '../src/api/types'

function report(): GeneratedClassroomReport {
  return {
    topics: [
      { startMs: 20_000, endMs: 30_000, summary: '第二段', evidenceRefs: [] },
      { startMs: 0, endMs: 20_000, summary: '第一段', evidenceRefs: [{ resultId: 1, itemType: 'SEGMENT', itemId: 'seg-1' }] },
    ],
    changes: [], interpretations: [], limitations: [],
  }
}

describe('segmentDirectory', () => {
  it('按开始时间排序，但保留报告中的原始序号', () => {
    const segments = segmentDirectory(report())
    expect(segments.map(segment => segment.title)).toEqual(['第一段', '第二段'])
    expect(segments.map(segment => segment.index)).toEqual([1, 0])
    expect(segments.map(segment => segment.key)).toEqual(['topic-1', 'topic-0'])
  })

  it('没有报告或没有片段时返回空目录', () => {
    expect(segmentDirectory(null)).toEqual([])
    const empty = report()
    empty.topics = []
    expect(segmentDirectory(empty)).toEqual([])
  })

  it('标签包含序号、区间、时长与证据条数', () => {
    expect(segmentLabel(segmentDirectory(report())[0]!)).toBe('02｜00:00 – 00:20（20 秒，1 条证据）')
  })
})

describe('findOverlaps', () => {
  it('半开区间相交才算重叠（首尾相接不算）', () => {
    const touching = segmentDirectory(report())
    expect(findOverlaps(touching)).toEqual([])
  })

  it('真重叠会被发现', () => {
    const segments = segmentDirectory(report())
    segments[1]!.range = { startMs: 15_000, endMs: 25_000 }
    const overlaps = findOverlaps(segments)
    expect(overlaps).toHaveLength(1)
    // left 是开始时间在前的那个片段，right 是叠进来的那个
    expect(overlaps[0]!.left.title).toBe('第一段')
    expect(overlaps[0]!.right.title).toBe('第二段')
  })
})

describe('validateSegmentEdit', () => {
  const mediaDurationMs = 60_000
  const segments = segmentDirectory(report())

  it('合法修改通过', () => {
    expect(validateSegmentEdit(segments, 'topic-0', { title: '第二段（改）', startMs: 20_000, endMs: 30_000 }, mediaDurationMs))
      .toEqual({ ok: true, code: null, message: '' })
  })

  it('标题为空或过长按同一条规则拒绝', () => {
    expect(validateSegmentEdit(segments, 'topic-0', { title: '   ', startMs: 20_000, endMs: 30_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_TITLE_REQUIRED')
    expect(validateSegmentEdit(segments, 'topic-0', { title: 'x'.repeat(201), startMs: 20_000, endMs: 30_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_TITLE_REQUIRED')
  })

  it('区间倒置或为空被拒绝', () => {
    expect(validateSegmentEdit(segments, 'topic-0', { title: '改', startMs: 30_000, endMs: 20_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_RANGE_INVALID')
    expect(validateSegmentEdit(segments, 'topic-0', { title: '改', startMs: 20_000, endMs: 20_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_RANGE_INVALID')
  })

  it('超出媒体时长被拒绝', () => {
    expect(validateSegmentEdit(segments, 'topic-0', { title: '改', startMs: 20_000, endMs: 70_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_OUT_OF_MEDIA')
    expect(validateSegmentEdit(segments, 'topic-0', { title: '改', startMs: -1, endMs: 10_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_OUT_OF_MEDIA')
  })

  it('与其他片段重叠被拒绝，并指出冲突的是哪一段', () => {
    const verdict = validateSegmentEdit(segments, 'topic-0', { title: '改', startMs: 10_000, endMs: 25_000 }, mediaDurationMs)
    expect(verdict.code).toBe('SEGMENT_OVERLAP')
    expect(verdict.message).toContain('片段 2')
  })

  it('未知片段被拒绝', () => {
    expect(validateSegmentEdit(segments, 'topic-9', { title: '改', startMs: 0, endMs: 10_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_UNKNOWN')
  })
})

describe('segmentDraftChanged / segmentEvidence', () => {
  it('标题或边界任一变化都算改动', () => {
    const segment = segmentDirectory(report())[0]!
    expect(segmentDraftChanged(segment, { title: '第一段', startMs: 0, endMs: 20_000 })).toBe(false)
    expect(segmentDraftChanged(segment, { title: '第一段', startMs: 0, endMs: 19_000 })).toBe(true)
    expect(segmentDraftChanged(segment, { title: '改名', startMs: 0, endMs: 20_000 })).toBe(true)
  })

  it('按 key 取回该片段的证据引用；不存在的 key 返回空', () => {
    expect(segmentEvidence(report(), 'topic-1')).toHaveLength(1)
    expect(segmentEvidence(report(), 'topic-0')).toEqual([])
    expect(segmentEvidence(report(), 'topic-9')).toEqual([])
    expect(segmentEvidence(null, 'topic-0')).toEqual([])
  })
})
