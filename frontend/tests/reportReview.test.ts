import { describe, expect, it } from 'vitest'
import { canConfirmReview, reviewErrorText, summarizeAnnotations } from '../src/api/reportReview'
import type { ClassroomReportReview, ReviewAnnotation } from '../src/api/types'

function annotation(overrides: Partial<ReviewAnnotation> = {}): ReviewAnnotation {
  return { targetKind: 'TOPIC', targetIndex: 0, verdict: 'ISSUE', note: '环节划分过粗', ...overrides }
}

function review(overrides: Partial<ClassroomReportReview> = {}): ClassroomReportReview {
  return {
    reviewId: 1,
    reportId: 7,
    mediaId: 41,
    confirmed: false,
    confirmedAt: null,
    revision: 1,
    annotations: [annotation()],
    ...overrides,
  }
}

describe('summarizeAnnotations', () => {
  it('空数组返回全 0', () => {
    expect(summarizeAnnotations([])).toEqual({ total: 0, issues: 0, acknowledged: 0 })
  })

  it('按 verdict 分别计数', () => {
    const summary = summarizeAnnotations([
      annotation({ targetIndex: 0, verdict: 'ISSUE' }),
      annotation({ targetIndex: 1, verdict: 'ACKNOWLEDGED' }),
      annotation({ targetIndex: 2, verdict: 'ISSUE' }),
    ])
    expect(summary).toEqual({ total: 3, issues: 2, acknowledged: 1 })
  })

  it('混合 verdict 时 total 等于条数', () => {
    const annotations = [
      annotation({ targetKind: 'TOPIC', verdict: 'ACKNOWLEDGED' }),
      annotation({ targetKind: 'CHANGE', verdict: 'ACKNOWLEDGED' }),
      annotation({ targetKind: 'INTERPRETATION', verdict: 'ISSUE' }),
    ]
    const summary = summarizeAnnotations(annotations)
    expect(summary.total).toBe(annotations.length)
    expect(summary.issues + summary.acknowledged).toBe(summary.total)
  })
})

describe('canConfirmReview', () => {
  it('没有复核记录时不能确认', () => {
    expect(canConfirmReview(null)).toBe(false)
  })

  it('没有任何标注时不能确认', () => {
    expect(canConfirmReview(review({ annotations: [] }))).toBe(false)
  })

  it('已确认的复核不能再次确认', () => {
    expect(canConfirmReview(review({ confirmed: true, confirmedAt: '2026-10-11T10:00:00' }))).toBe(false)
  })

  it('说明为纯空白时不能确认', () => {
    expect(canConfirmReview(review({ annotations: [annotation({ note: '   ' })] }))).toBe(false)
    expect(canConfirmReview(review({ annotations: [annotation({ note: '\n\t ' })] }))).toBe(false)
  })

  it('只要有一条说明为空就不能确认', () => {
    const mixed = review({
      annotations: [annotation({ targetIndex: 0, note: '有说明' }), annotation({ targetIndex: 1, note: '' })],
    })
    expect(canConfirmReview(mixed)).toBe(false)
  })

  it('未确认且每条都有说明时可以确认', () => {
    const ready = review({
      annotations: [
        annotation({ targetIndex: 0, note: '环节划分过粗' }),
        annotation({ targetIndex: 1, verdict: 'ACKNOWLEDGED', note: '与课堂观察一致' }),
      ],
    })
    expect(canConfirmReview(ready)).toBe(true)
  })
})

describe('reviewErrorText', () => {
  it('已登记的错误码翻成可执行提示', () => {
    expect(reviewErrorText('REVIEW_NOTE_REQUIRED', '兜底')).toContain('说明')
    expect(reviewErrorText('REVIEW_ALREADY_CONFIRMED', '兜底')).toContain('已确认')
  })

  it('未知错误码回落到传入的兜底文案', () => {
    expect(reviewErrorText('SOMETHING_ELSE', '兜底文案')).toBe('兜底文案')
  })
})
