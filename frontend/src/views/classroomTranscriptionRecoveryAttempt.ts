const PREFIX = 'ai-teaching:classroom-transcription-recovery:v1'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function slot(userId: number, mediaId: number): string {
  return `${PREFIX}:${userId}:${mediaId}`
}

export function pendingRecoveryKey(storage: Storage | null, userId: number, mediaId: number): string | null {
  if (storage === null) return null
  try {
    const value = storage.getItem(slot(userId, mediaId))
    return value !== null && UUID.test(value) ? value : null
  } catch { return null }
}

export function rememberRecoveryKey(storage: Storage | null, userId: number, mediaId: number, key: string): void {
  if (storage === null) return
  try { storage.setItem(slot(userId, mediaId), key) } catch { /* In-memory key remains available. */ }
}

export function clearRecoveryKey(storage: Storage | null, userId: number, mediaId: number): void {
  if (storage === null) return
  try { storage.removeItem(slot(userId, mediaId)) } catch { /* Storage may be disabled. */ }
}
