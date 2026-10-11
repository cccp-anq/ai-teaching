import { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import { createHttpClient } from '../src/api/client'
import { checkBackend } from '../src/api/system'

describe('technical connection check', () => {
  it('uses the shared client and validates backend UP', async () => {
    const client = createHttpClient({ adapter: async config => {
      expect(config.url).toBe('/system/status')
      return { data: { status: 'UP' }, status: 200, statusText: 'OK', headers: {}, config }
    } })
    await expect(checkBackend(client)).resolves.toBeUndefined()
  })
  it.each(['<html>SPA fallback</html>', null, {}, { status: 'DOWN' }])('rejects false-positive success %j', async data => {
    const client = createHttpClient({ adapter: async config => ({ data, status: 200, statusText: 'OK', headers: {}, config }) })
    await expect(checkBackend(client)).rejects.toMatchObject({ code: 'INVALID_STATUS_RESPONSE' })
  })
  it('preserves normalized network failures', async () => {
    const client = createHttpClient({ adapter: async config => { throw new AxiosError('network', 'ERR_NETWORK', config) } })
    await expect(checkBackend(client)).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})
