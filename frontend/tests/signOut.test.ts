import { beforeEach, describe, expect, it } from 'vitest'
import { ApiClientError } from '../src/api/client'
import { signOutSession } from '../src/composables/signOut'
import { addPick, comparisonPickState } from '../src/stores/comparisonPick'
import { sessionState, setCurrentUser } from '../src/stores/session'
import type { CurrentUser } from '../src/api/types'

const user: CurrentUser = {
  userId: 1,
  username: 'admin-test',
  teacherId: null,
  roles: ['ADMIN'],
  authorizationScopes: [
    { roleCode: 'ADMIN', scopeType: 'GLOBAL', collegeId: null, teacherId: null, offeringId: null },
  ],
}

describe('confirmed session logout', () => {
  beforeEach(() => {
    setCurrentUser(user)
    addPick({ teacherId: 1, displayName: 'Teacher Alpha' })
  })

  it('clears identity and account-owned comparison picks after server success', async () => {
    await signOutSession(async () => undefined)
    expect(sessionState.currentUser).toBeNull()
    expect(comparisonPickState.picks).toHaveLength(0)
  })

  it('treats 401 as an already-ended server session', async () => {
    await signOutSession(async () => {
      throw new ApiClientError('expired', 'UNAUTHENTICATED', 401)
    })
    expect(sessionState.currentUser).toBeNull()
    expect(comparisonPickState.picks).toHaveLength(0)
  })

  it('preserves identity and picks when the server outcome is unknown', async () => {
    await expect(signOutSession(async () => {
      throw new ApiClientError('network', 'NETWORK_ERROR')
    })).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(sessionState.currentUser?.userId).toBe(user.userId)
    expect(comparisonPickState.picks).toHaveLength(1)
  })
})
