import { beforeEach, describe, expect, it } from 'vitest'
import {
  MAX_COMPARISON_PICKS,
  addPick,
  clearPicks,
  clearComparisonSession,
  comparisonPickState,
  removePick,
  setComparisonOwner,
} from '../src/stores/comparisonPick'

describe('comparison teacher picks (frontend selection only, 1..5)', () => {
  beforeEach(() => {
    clearComparisonSession()
    setComparisonOwner(1)
  })

  it('adds distinct teachers in order and reports duplicates', () => {
    expect(addPick({ teacherId: 1, displayName: 'Teacher Alpha' })).toBe(true)
    expect(addPick({ teacherId: 2, displayName: 'Teacher Beta' })).toBe(true)
    expect(addPick({ teacherId: 1, displayName: 'Teacher Alpha' })).toBe(false)
    expect(comparisonPickState.picks.map(pick => pick.teacherId)).toEqual([1, 2])
  })

  it('enforces the backend 1..5 contract on the client side as UX only', () => {
    for (let teacherId = 1; teacherId <= MAX_COMPARISON_PICKS; teacherId += 1) {
      expect(addPick({ teacherId, displayName: `T${teacherId}` })).toBe(true)
    }
    expect(addPick({ teacherId: 9, displayName: 'T9' })).toBe(false)
    expect(comparisonPickState.picks).toHaveLength(MAX_COMPARISON_PICKS)
  })

  it('rejects invalid teacher ids', () => {
    expect(addPick({ teacherId: 0, displayName: 'zero' })).toBe(false)
    expect(addPick({ teacherId: -3, displayName: 'negative' })).toBe(false)
    expect(addPick({ teacherId: 1.5, displayName: 'fractional' })).toBe(false)
    expect(comparisonPickState.picks).toHaveLength(0)
  })

  it('removes and clears picks', () => {
    addPick({ teacherId: 1, displayName: 'A' })
    addPick({ teacherId: 2, displayName: 'B' })
    removePick(1)
    expect(comparisonPickState.picks.map(pick => pick.teacherId)).toEqual([2])
    clearPicks()
    expect(comparisonPickState.picks).toHaveLength(0)
  })

  it('keeps picks for the same account and clears them across account changes or logout', () => {
    addPick({ teacherId: 1, displayName: 'A' })
    setComparisonOwner(1)
    expect(comparisonPickState.picks).toHaveLength(1)
    setComparisonOwner(2)
    expect(comparisonPickState.picks).toHaveLength(0)
    addPick({ teacherId: 2, displayName: 'B' })
    clearComparisonSession()
    expect(comparisonPickState.picks).toHaveLength(0)
  })
})
