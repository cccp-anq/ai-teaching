import { reactive, readonly } from 'vue'

export interface ComparisonPick {
  teacherId: number
  displayName: string
}

export const MAX_COMPARISON_PICKS = 5

const STORAGE_KEY = 'aijiaoxue.comparison.picks'

interface PersistedComparisonPicks {
  ownerUserId: number
  picks: ComparisonPick[]
}

function isPick(value: unknown): value is ComparisonPick {
  if (value === null || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.teacherId === 'number' && Number.isInteger(record.teacherId)
    && typeof record.displayName === 'string'
}

function load(): PersistedComparisonPicks | null {
  try {
    if (typeof sessionStorage === 'undefined') return null
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (raw === null) return null
    const parsed: unknown = JSON.parse(raw)
    if (parsed === null || typeof parsed !== 'object') return null
    const record = parsed as Record<string, unknown>
    if (!Number.isInteger(record.ownerUserId) || !Array.isArray(record.picks)) return null
    return {
      ownerUserId: record.ownerUserId as number,
      picks: record.picks.filter(isPick).slice(0, MAX_COMPARISON_PICKS),
    }
  } catch {
    return null
  }
}

function persist(ownerUserId: number | null, picks: ComparisonPick[]): void {
  try {
    if (typeof sessionStorage === 'undefined') return
    if (ownerUserId === null) {
      sessionStorage.removeItem(STORAGE_KEY)
      return
    }
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ ownerUserId, picks }))
  } catch {
    // Storage unavailable (private mode/SSR): in-session picks still work.
  }
}

const stored = load()
const state = reactive<{ ownerUserId: number | null; picks: ComparisonPick[] }>({
  ownerUserId: stored?.ownerUserId ?? null,
  picks: stored?.picks ?? [],
})

export const comparisonPickState = readonly(state)

/** Binds picks to the authenticated account, clearing data left by another account. */
export function setComparisonOwner(userId: number): void {
  if (!Number.isInteger(userId) || userId <= 0) {
    clearComparisonSession()
    return
  }
  if (state.ownerUserId === userId) return
  state.ownerUserId = userId
  state.picks = []
  persist(state.ownerUserId, state.picks)
}

export function clearComparisonSession(): void {
  state.ownerUserId = null
  state.picks = []
  persist(null, state.picks)
}

/** Adds a pick by teacherId; returns false when already present or the 1..5 limit is reached. */
export function addPick(pick: ComparisonPick): boolean {
  if (!Number.isInteger(pick.teacherId) || pick.teacherId <= 0) return false
  if (state.picks.some(existing => existing.teacherId === pick.teacherId)) return false
  if (state.picks.length >= MAX_COMPARISON_PICKS) return false
  state.picks = [...state.picks, { teacherId: pick.teacherId, displayName: pick.displayName }]
  persist(state.ownerUserId, state.picks)
  return true
}

export function removePick(teacherId: number): void {
  state.picks = state.picks.filter(existing => existing.teacherId !== teacherId)
  persist(state.ownerUserId, state.picks)
}

export function clearPicks(): void {
  state.picks = []
  persist(state.ownerUserId, state.picks)
}
