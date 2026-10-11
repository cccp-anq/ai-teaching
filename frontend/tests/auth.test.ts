import { AxiosError } from 'axios'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  cachedCsrf,
  fetchCurrentUser,
  fetchLoginMode,
  login,
  logout,
} from '../src/api/auth'
import { ApiClientError, createHttpClient } from '../src/api/client'
import type { CurrentUser } from '../src/api/types'

interface Recorded {
  method?: string
  url?: string
  data?: unknown
  headers?: Record<string, unknown>
}

const user: CurrentUser = {
  userId: 1, username: 'admin-test', teacherId: null, roles: ['ADMIN'],
  authorizationScopes: [{ roleCode: 'ADMIN', scopeType: 'GLOBAL', collegeId: null, teacherId: null, offeringId: null }],
}

function respond(config: InternalAxiosRequestConfig, status: number, data: unknown): AxiosAdapter {
  return async () => {
    if (status >= 400) {
      throw new AxiosError('failure', 'ERR_BAD_REQUEST', config, {}, {
        status, statusText: 'error', data, headers: {}, config,
      })
    }
    return { status, statusText: 'OK', data, headers: {}, config }
  }
}

/** Builds a client whose responses are keyed by method+path, recording every request. */
function scripted(steps: Array<{ method: string; endsWith: string; status: number; data: unknown }>) {
  const calls: Recorded[] = []
  const queue = [...steps]
  const adapter: AxiosAdapter = async config => {
    const method = (config.method ?? 'get').toLowerCase()
    const url = config.url ?? ''
    calls.push({ method, url, data: config.data, headers: config.headers })
    const index = queue.findIndex(step => step.method === method && url.endsWith(step.endsWith))
    if (index < 0) throw new Error(`unexpected call ${method} ${url}`)
    const step = queue.splice(index, 1)[0]
    if (step === undefined) throw new Error('scripted step missing')
    return respond(config, step.status, step.data)(config)
  }
  return { calls, client: createHttpClient({ adapter }) }
}

function headerOf(call: Recorded | undefined, name: string): string | undefined {
  const headers = call?.headers
  if (headers === undefined) return undefined
  const direct = headers[name]
  if (typeof direct === 'string') return direct
  const normalized = Object.entries(headers).find(([key]) => key.toLowerCase() === name.toLowerCase())
  return typeof normalized?.[1] === 'string' ? normalized[1] : undefined
}

describe('auth contract (Round 2.3 CSRF rotation)', () => {
  beforeEach(() => {
    // cachedCsrf is module state; reset it by clearing through a successful fetch in each test.
  })

  it('login fetches a fresh token, posts form credentials and rotates the cached token', async () => {
    const { client, calls } = scripted([
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'token-1', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/auth/login', status: 200, data: user },
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'token-2', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
    ])
    await expect(login('admin-test', 'Fixture-Only-2026!', client)).resolves.toMatchObject({ username: 'admin-test' })
    expect(calls).toHaveLength(3)
    const loginCall = calls.find(call => call.url?.endsWith('/auth/login'))
    expect(headerOf(loginCall, 'X-CSRF-TOKEN')).toBe('token-1')
    expect(String(loginCall?.data)).toContain('username=admin-test')
    expect(cachedCsrf()?.token).toBe('token-2')
  })

  it('reads the server login mode and supports an empty password in local username mode', async () => {
    const { client, calls } = scripted([
      { method: 'get', endsWith: '/auth/login-mode', status: 200, data: { usernameOnly: true } },
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'token-1', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/auth/login', status: 200, data: user },
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'token-2', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
    ])
    await expect(fetchLoginMode(client)).resolves.toEqual({ usernameOnly: true })
    await login('admin-test', '', client)
    expect(String(calls.find(call => call.url?.endsWith('/auth/login'))?.data)).toContain('password=')
  })

  it('surfaces invalid credentials as a 401 ApiClientError and never leaks server details', async () => {
    const { client } = scripted([
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'token-1', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/auth/login', status: 401, data: { code: 'INVALID_CREDENTIALS', message: 'internal detail' } },
    ])
    const error = await login('admin-test', 'wrong', client).catch((cause: unknown) => cause)
    expect(error).toBeInstanceOf(ApiClientError)
    expect(error).toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS' })
    expect((error as ApiClientError).message).not.toContain('internal detail')
  })

  it('logout always requests the newest token and sends it as the CSRF header', async () => {
    const { client, calls } = scripted([
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'token-latest', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/auth/logout', status: 204, data: '' },
    ])
    await expect(logout(client)).resolves.toBeUndefined()
    const logoutCall = calls.find(call => call.url?.endsWith('/auth/logout'))
    expect(headerOf(logoutCall, 'X-CSRF-TOKEN')).toBe('token-latest')
  })

  it('fetchCurrentUser returns the trusted CurrentUser payload', async () => {
    const { client } = scripted([
      { method: 'get', endsWith: '/auth/me', status: 200, data: user },
    ])
    await expect(fetchCurrentUser(client)).resolves.toMatchObject({ userId: 1, roles: ['ADMIN'] })
  })
})
