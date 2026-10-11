import type { AxiosInstance } from 'axios'
import { http } from './client'
import type { CurrentUser, CsrfToken } from './types'

/**
 * Round 2.3 session/CSRF contract: the token lives in the server session, POST login
 * and POST logout require it as a request header, and it rotates on login — the cached
 * token must be refreshed before any later mutating call.
 */

let cached: CsrfToken | null = null

export async function fetchCsrf(client: AxiosInstance = http): Promise<CsrfToken> {
  const { data } = await client.get<CsrfToken>('/auth/csrf')
  cached = data
  return data
}

export function cachedCsrf(): CsrfToken | null {
  return cached
}

export async function fetchLoginMode(client: AxiosInstance = http): Promise<{ usernameOnly: boolean }> {
  const { data } = await client.get<{ usernameOnly: boolean }>('/auth/login-mode')
  return data
}

export async function login(username: string, password: string, client: AxiosInstance = http): Promise<CurrentUser> {
  const token = await fetchCsrf(client)
  const form = new URLSearchParams()
  form.set('username', username)
  form.set('password', password)
  const { data } = await client.post<CurrentUser>('/auth/login', form, {
    headers: { [token.headerName]: token.token },
  })
  await fetchCsrf(client)
  return data
}

export async function logout(client: AxiosInstance = http): Promise<void> {
  const token = await fetchCsrf(client)
  await client.post('/auth/logout', null, { headers: { [token.headerName]: token.token } })
}

export async function fetchCurrentUser(client: AxiosInstance = http): Promise<CurrentUser> {
  const { data } = await client.get<CurrentUser>('/auth/me')
  return data
}
