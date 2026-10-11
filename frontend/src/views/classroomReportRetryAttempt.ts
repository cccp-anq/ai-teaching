const PREFIX = 'ai-teaching:classroom-report-retry:v1'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function slot(userId: number, mediaId: number): string {
  return `${PREFIX}:${userId}:${mediaId}`
}

/** A failed network response is uncertain; keep its key across a page reload. */
export function pendingReportRetryKey(storage: Storage | null, userId: number, mediaId: number): string | null {
  if (storage === null) return null
  try {
    const value = storage.getItem(slot(userId, mediaId))
    return value !== null && UUID.test(value) ? value : null
  } catch { return null }
}

export function rememberReportRetryKey(storage: Storage | null, userId: number, mediaId: number, key: string): void {
  if (storage === null) return
  try { storage.setItem(slot(userId, mediaId), key) } catch { /* Storage may be disabled; the in-memory key remains. */ }
}

export function clearReportRetryKey(storage: Storage | null, userId: number, mediaId: number): void {
  if (storage === null) return
  try { storage.removeItem(slot(userId, mediaId)) } catch { /* Storage may be disabled. */ }
}
