import { AxiosError, CanceledError } from 'axios'
import type { AxiosAdapter } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { API_BASE_URL, ApiClientError, createHttpClient, normalizeError } from '../src/api/client'

function rejectWith(status?: number, data: unknown = {}, code = 'ERR_BAD_RESPONSE'): AxiosAdapter {
  return async config => {
    throw new AxiosError('internal diagnostics must not reach UI', code, config, {},
      status === undefined ? undefined : { status, statusText: 'error', data, headers: { 'x-request-id': 'trace-header' }, config })
  }
}

describe('HTTP boundary', () => {
  it('uses one API prefix, retains successful responses and sets a bounded timeout', async () => {
    const client = createHttpClient({ adapter: async config => {
      expect(config.baseURL).toBe(API_BASE_URL)
      expect(config.timeout).toBe(15_000)
      return { status: 200, statusText: 'OK', data: { status: 'UP' }, headers: {}, config }
    } })
    expect((await client.get('/system/status')).data).toEqual({ status: 'UP' })
  })

  it('normalizes validation fields and request identity without trusting malformed entries', async () => {
    const client = createHttpClient({ adapter: rejectWith(400, {
      code: 'VALIDATION_FAILED', requestId: 'trace-body', message: 'private server detail',
      fieldErrors: [{ field: 'page', message: 'Invalid value' }, null, { field: 1 }, 'invalid'],
    }) })
    await expect(client.get('/test')).rejects.toMatchObject({
      name: 'ApiClientError', code: 'VALIDATION_FAILED', status: 400, requestId: 'trace-body',
      message: '请求无效，请检查输入。', fieldErrors: [{ field: 'page', message: 'Invalid value' }],
    })
  })

  it.each([401, 403, 404, 500])('handles HTTP %s; only 401 signals unauthenticated', async status => {
    const onUnauthenticated = vi.fn()
    const client = createHttpClient({ onUnauthenticated, adapter: rejectWith(status, '<html>private diagnostics</html>') })
    const error = await client.get('/test').catch((value: unknown) => value)
    expect(error).toBeInstanceOf(ApiClientError)
    expect(error).toMatchObject({ status, code: `HTTP_${status}`, requestId: 'trace-header' })
    expect((error as ApiClientError).message).not.toContain('diagnostics')
    expect(onUnauthenticated).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
  })

  it.each([['ERR_NETWORK', 'NETWORK_ERROR'], ['ECONNABORTED', 'TIMEOUT'], ['ETIMEDOUT', 'TIMEOUT']])(
    'normalizes transport error %s without signaling login', async (input, expected) => {
      const onUnauthenticated = vi.fn()
      const client = createHttpClient({ onUnauthenticated, adapter: rejectWith(undefined, {}, input) })
      await expect(client.get('/test')).rejects.toMatchObject({ code: expected, status: undefined })
      expect(onUnauthenticated).not.toHaveBeenCalled()
    },
  )

  it('distinguishes cancellation and unknown errors', () => {
    expect(normalizeError(new CanceledError()).code).toBe('CANCELED')
    expect(normalizeError(new Error('private detail')).message).not.toContain('private detail')
  })
})
