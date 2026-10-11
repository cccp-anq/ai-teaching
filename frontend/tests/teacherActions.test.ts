import { describe, expect, it } from 'vitest'
import { evidenceSummary, nextActionStatuses, suggestionActionDraft } from '../src/api/teacherActions'
import type { ActionSuggestion } from '../src/api/types'

function suggestion(overrides: Partial<ActionSuggestion> = {}): ActionSuggestion {
  return {
    index: 0,
    hypothesis: null,
    alternatives: [],
    suggestion: '提问后停顿三秒',
    evidence: [],
    ...overrides,
  }
}

describe('suggestionActionDraft', () => {
  it('以建议原文作为标题与正文，并在有依据时附上依据', () => {
    const draft = suggestionActionDraft(suggestion({ hypothesis: '等待时间偏短' }))
    expect(draft.title).toBe('提问后停顿三秒')
    expect(draft.content).toBe('提问后停顿三秒（依据：等待时间偏短）')
  })

  it('没有依据时不产生空括号', () => {
    expect(suggestionActionDraft(suggestion({ hypothesis: '   ' })).content).toBe('提问后停顿三秒')
  })

  it('标题超过 40 字时截断，正文保留完整建议', () => {
    const long = '很长的建议'.repeat(10)
    const draft = suggestionActionDraft(suggestion({ suggestion: long }))
    expect(draft.title).toHaveLength(40)
    expect(draft.title.endsWith('…')).toBe(true)
    expect(draft.content).toBe(long)
  })
})

describe('evidenceSummary', () => {
  it('分别统计转写片段与抬头率窗口', () => {
    const summary = evidenceSummary(suggestion({
      evidence: [
        { itemType: 'SEGMENT', itemId: 'a' },
        { itemType: 'SEGMENT', itemId: 'b' },
        { itemType: 'WINDOW', itemId: 'c' },
      ],
    }))
    expect(summary).toBe('关联证据：2 段课堂转写、1 个抬头率窗口')
  })

  it('没有证据时如实说明，而不是留空', () => {
    expect(evidenceSummary(suggestion())).toBe('暂无关联证据')
  })
})

describe('nextActionStatuses', () => {
  it('与后端的行动状态机一致', () => {
    expect(nextActionStatuses('PLANNED')).toEqual(['ACTIVE', 'ABANDONED'])
    expect(nextActionStatuses('ACTIVE')).toEqual(['DONE', 'ABANDONED'])
    expect(nextActionStatuses('DONE')).toEqual([])
    expect(nextActionStatuses('ABANDONED')).toEqual([])
  })
})
