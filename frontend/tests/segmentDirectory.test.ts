import { describe, expect, it } from 'vitest'
import {
  directorySegments, findOverlaps, segmentDirectory, segmentDraftChanged, segmentEvidence, segmentLabel,
  validateSegmentEdit,
} from '../src/lib/segmentDirectory'
import type { GeneratedClassroomReport, SegmentDirectoryResponse } from '../src/api/types'

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

  it('边界没变时只校验标题：报告本身重叠也不该挡住改名', () => {
    // AI 报告的片段划分允许互相重叠（校验器只保证证据落在本片段内）。
    const overlapping = [
      { key: 'topic-0', index: 0, title: '讲解递归', range: { startMs: 0, endMs: 20_000 }, durationMs: 20_000, evidenceCount: 1 },
      { key: 'topic-1', index: 1, title: '学生练习', range: { startMs: 10_000, endMs: 60_000 }, durationMs: 50_000, evidenceCount: 1 },
    ]
    expect(validateSegmentEdit(overlapping, 'topic-0', { title: '改名', startMs: 0, endMs: 20_000 }, mediaDurationMs))
      .toEqual({ ok: true, code: null, message: '' })
    // 一旦动了边界，重叠仍然被拒绝
    expect(validateSegmentEdit(overlapping, 'topic-0', { title: '改名', startMs: 0, endMs: 25_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_OVERLAP')
  })

  it('未知片段被拒绝', () => {
    expect(validateSegmentEdit(segments, 'topic-9', { title: '改', startMs: 0, endMs: 10_000 }, mediaDurationMs).code)
      .toBe('SEGMENT_UNKNOWN')
  })
})

describe('directorySegments', () => {
  function directory(): SegmentDirectoryResponse {
    return {
      reportId: 9,
      mediaId: 41,
      mediaDurationMs: 60_000,
      segments: [
        {
          key: 'topic-0', index: 0, title: '讲解递归（改）', startMs: 0, endMs: 18_000, durationMs: 18_000,
          evidenceCount: 1, overridden: true, originalTitle: '讲解递归', originalStartMs: 0,
          originalEndMs: 20_000, editedByUserId: 3, revision: 2, updatedAt: '2026-10-11T10:00:00',
        },
        {
          key: 'topic-1', index: 1, title: '学生练习', startMs: 20_000, endMs: 60_000, durationMs: 40_000,
          evidenceCount: 0, overridden: false, originalTitle: '学生练习', originalStartMs: 20_000,
          originalEndMs: 60_000, editedByUserId: null, revision: null, updatedAt: null,
        },
      ],
    }
  }

  it('保留服务端顺序与覆盖留痕，不再重排', () => {
    const segments = directorySegments(directory())
    expect(segments.map(segment => segment.key)).toEqual(['topic-0', 'topic-1'])
    expect(segments[0]!.title).toBe('讲解递归（改）')
    expect(segments[0]!.overridden).toBe(true)
    expect(segments[0]!.originalTitle).toBe('讲解递归')
    expect(segments[0]!.revision).toBe(2)
    expect(segments[1]!.overridden).toBe(false)
  })

  it('映射后的目录可以直接参与校验', () => {
    const segments = directorySegments(directory())
    expect(validateSegmentEdit(segments, 'topic-1', { title: '改', startMs: 10_000, endMs: 30_000 }, 60_000).code)
      .toBe('SEGMENT_OVERLAP')
    expect(validateSegmentEdit(segments, 'topic-1', { title: '改', startMs: 18_000, endMs: 60_000 }, 60_000))
      .toEqual({ ok: true, code: null, message: '' })
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
