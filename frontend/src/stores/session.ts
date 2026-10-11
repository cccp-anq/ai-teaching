import { reactive, readonly } from 'vue'
import type { CurrentUser } from '../api/types'
import { clearComparisonSession, setComparisonOwner } from './comparisonPick'

/**
 * UX-only session state. It mirrors what the server already told us; it is never an
 * authorization boundary and every page still relies on the server-side 401/403.
 */
const state = reactive<{ unauthenticated: boolean; currentUser: CurrentUser | null }>({
  unauthenticated: false,
  currentUser: null,
})

export const sessionState = readonly(state)

export function markUnauthenticated() {
  state.unauthenticated = true
  state.currentUser = null
  clearComparisonSession()
}

export function setCurrentUser(user: CurrentUser | null) {
  state.currentUser = user
  state.unauthenticated = user === null
  if (user === null) clearComparisonSession()
  else setComparisonOwner(user.userId)
}

export function hasRole(role: string): boolean {
  return state.currentUser?.roles.includes(role) ?? false
}
