import { http, ApiClientError } from './client'
import type { AxiosInstance } from 'axios'

export async function checkBackend(client: AxiosInstance = http): Promise<void> {
  const response = await client.get<unknown>('/system/status')
  const data = response.data
  if (response.status !== 200 || data === null || typeof data !== 'object' || !('status' in data) || data.status !== 'UP') {
    throw new ApiClientError('状态接口响应无效，请检查 API 代理配置。', 'INVALID_STATUS_RESPONSE')
  }
}
