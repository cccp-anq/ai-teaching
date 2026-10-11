import { logout } from '../api/auth'
import { ApiClientError } from '../api/client'
import { setCurrentUser } from '../stores/session'

/**
 * Ends the local session only after the server confirms logout. A 401 also proves
 * that no authenticated server session remains; network and server failures keep
 * the current local identity so the UI cannot report a false successful logout.
 */
export async function signOutSession(request: () => Promise<void> = logout): Promise<void> {
  try {
    await request()
    setCurrentUser(null)
  } catch (cause) {
    if (cause instanceof ApiClientError && cause.status === 401) {
      setCurrentUser(null)
      return
    }
    throw cause
  }
}
