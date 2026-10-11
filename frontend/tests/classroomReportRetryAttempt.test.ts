import { describe, expect, it } from 'vitest'
import { clearReportRetryKey, pendingReportRetryKey, rememberReportRetryKey } from '../src/views/classroomReportRetryAttempt'

function memoryStorage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() { return values.size },
    clear: () => values.clear(),
    getItem: key => values.get(key) ?? null,
    key: index => [...values.keys()][index] ?? null,
    removeItem: key => { values.delete(key) },
    setItem: (key, value) => { values.set(key, value) },
  }
}

describe('chargeable report retry key', () => {
  it('keeps an uncertain key for the same user and media only', () => {
    const storage = memoryStorage()
    const key = '15830ee3-c59b-4f7a-b7f5-f14892bf0d0e'
    rememberReportRetryKey(storage, 5, 44, key)
    expect(pendingReportRetryKey(storage, 5, 44)).toBe(key)
    expect(pendingReportRetryKey(storage, 6, 44)).toBeNull()
    expect(pendingReportRetryKey(storage, 5, 45)).toBeNull()
    clearReportRetryKey(storage, 5, 44)
    expect(pendingReportRetryKey(storage, 5, 44)).toBeNull()
  })

  it('ignores corrupt or unavailable browser storage', () => {
    const storage = memoryStorage()
    rememberReportRetryKey(storage, 5, 44, 'not-a-uuid')
    expect(pendingReportRetryKey(storage, 5, 44)).toBeNull()
    expect(pendingReportRetryKey(null, 5, 44)).toBeNull()
  })
})
