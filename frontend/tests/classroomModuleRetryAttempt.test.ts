import { describe, expect, it } from 'vitest'
import { clearModuleRetryKey, pendingModuleRetryKey, rememberModuleRetryKey } from '../src/views/classroomModuleRetryAttempt'
import { clearRecoveryKey, pendingRecoveryKey, rememberRecoveryKey } from '../src/views/classroomTranscriptionRecoveryAttempt'

const retryKey = '11111111-1111-4111-8111-111111111111'
const recoveryKey = '22222222-2222-4222-8222-222222222222'

describe('classroom module operation idempotency', () => {
  it('keeps an uncertain paid module retry key scoped to user and media until cleared', () => {
    const storage = new MapStorage()
    rememberModuleRetryKey(storage, 5, 42, retryKey)
    expect(pendingModuleRetryKey(storage, 5, 42)).toBe(retryKey)
    expect(pendingModuleRetryKey(storage, 6, 42)).toBeNull()
    expect(pendingModuleRetryKey(storage, 5, 43)).toBeNull()
    clearModuleRetryKey(storage, 5, 42)
    expect(pendingModuleRetryKey(storage, 5, 42)).toBeNull()
  })

  it('uses an independent slot for recovery and ignores malformed persisted keys', () => {
    const storage = new MapStorage()
    rememberRecoveryKey(storage, 5, 42, recoveryKey)
    rememberModuleRetryKey(storage, 5, 42, retryKey)
    expect(pendingRecoveryKey(storage, 5, 42)).toBe(recoveryKey)
    clearRecoveryKey(storage, 5, 42)
    storage.setItem('ai-teaching:classroom-transcription-recovery:v1:5:42', 'not-a-uuid')
    expect(pendingRecoveryKey(storage, 5, 42)).toBeNull()
    expect(pendingModuleRetryKey(storage, 5, 42)).toBe(retryKey)
  })
})

class MapStorage implements Storage {
  private readonly values = new Map<string, string>()
  get length(): number { return this.values.size }
  clear(): void { this.values.clear() }
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null }
  removeItem(key: string): void { this.values.delete(key) }
  setItem(key: string, value: string): void { this.values.set(key, value) }
}
