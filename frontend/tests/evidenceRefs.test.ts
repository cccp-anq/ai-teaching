import { describe, expect, it } from 'vitest'
import { evidenceRefsParam, parseEvidenceRefs, toEvidenceRefs } from '../src/lib/evidenceRefs'

describe('toEvidenceRefs', () => {
  it('把建议里的证据补成报告证据引用（resultId 占位 0）', () => {
    expect(toEvidenceRefs([
      { itemType: 'SEGMENT', itemId: 'seg-1' },
      { itemType: 'WINDOW', itemId: 'win-2' },
    ])).toEqual([
      { resultId: 0, itemType: 'SEGMENT', itemId: 'seg-1' },
      { resultId: 0, itemType: 'WINDOW', itemId: 'win-2' },
    ])
  })

  it('没有证据时返回空数组', () => {
    expect(toEvidenceRefs([])).toEqual([])
  })
})

describe('evidenceRefsParam / parseEvidenceRefs', () => {
  it('编解码可以往返', () => {
    const refs = toEvidenceRefs([{ itemType: 'SEGMENT', itemId: 'seg-3' }, { itemType: 'WINDOW', itemId: 'w-9' }])
    expect(evidenceRefsParam(refs)).toBe('SEGMENT:seg-3,WINDOW:w-9')
    expect(parseEvidenceRefs(evidenceRefsParam(refs))).toEqual(refs)
  })

  it('忽略非法项，而不是产生指向错误位置的引用', () => {
    expect(parseEvidenceRefs('SEGMENT:seg-1, ,BADTYPE:x,:noid,SEGMENT:,WINDOW:win-1')).toEqual([
      { resultId: 0, itemType: 'SEGMENT', itemId: 'seg-1' },
      { resultId: 0, itemType: 'WINDOW', itemId: 'win-1' },
    ])
  })

  it('id 里带冒号时保留完整 id（只按第一个冒号切分）', () => {
    expect(parseEvidenceRefs('SEGMENT:a:b')).toEqual([{ resultId: 0, itemType: 'SEGMENT', itemId: 'a:b' }])
  })

  it('空值与非法类型返回空数组', () => {
    expect(parseEvidenceRefs(undefined)).toEqual([])
    expect(parseEvidenceRefs('')).toEqual([])
    expect(parseEvidenceRefs(42)).toEqual([])
    expect(parseEvidenceRefs('SEGMENT, WINDOW')).toEqual([])
  })
})
