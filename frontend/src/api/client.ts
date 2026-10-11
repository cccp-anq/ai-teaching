import axios from 'axios'
import type { AxiosAdapter } from 'axios'
import { markUnauthenticated } from '../stores/session'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.trim() || '/api/v1'

export interface FieldError {
  field: string
  message: string
}

export class ApiClientError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status?: number,
    public readonly requestId?: string,
    public readonly fieldErrors: FieldError[] = [],
    public readonly raw?: unknown,
  ) {
    super(message)
    this.name = 'ApiClientError'
  }
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' ? value as Record<string, unknown> : {}
}

export function normalizeError(error: unknown): ApiClientError {
  if (!axios.isAxiosError(error)) return new ApiClientError('请求失败，请稍后重试。', 'UNKNOWN_ERROR')
  if (axios.isCancel(error)) return new ApiClientError('请求已取消。', 'CANCELED')
  const status = error.response?.status
  const data = record(error.response?.data)
  const headerId: unknown = error.response?.headers['x-request-id']
  const requestId = typeof data.requestId === 'string' ? data.requestId
    : typeof headerId === 'string' ? headerId : undefined
  const fieldErrors = Array.isArray(data.fieldErrors) ? data.fieldErrors.filter(
    (field): field is FieldError => typeof record(field).field === 'string' && typeof record(field).message === 'string',
  ) : []
  const message = status === 401 ? '尚未登录或登录状态已失效。'
    : status === 403 ? '无权执行此操作。'
    : status === 400 ? '请求无效，请检查输入。'
    : status === 404 ? '请求的内容不存在。'
    : status !== undefined ? '服务暂时无法处理请求，请稍后重试。'
    : error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' ? '请求超时，请稍后重试。'
    : '无法连接服务，请检查网络。'
  const code = typeof data.code === 'string' ? data.code
    : status !== undefined ? `HTTP_${status}`
    : error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' ? 'TIMEOUT' : 'NETWORK_ERROR'
  return new ApiClientError(message, code, status, requestId, fieldErrors, error.response?.data)
}

export function createHttpClient(options: { onUnauthenticated?: () => void; adapter?: AxiosAdapter } = {}) {
  const client = axios.create({
    baseURL: API_BASE_URL,
    timeout: 15_000,
    withCredentials: true,
    ...(options.adapter ? { adapter: options.adapter } : {}),
  })
  client.interceptors.response.use(response => response, (error: unknown) => {
    const normalized = normalizeError(error)
    if (normalized.status === 401) options.onUnauthenticated?.()
    return Promise.reject(normalized)
  })
  return client
}

export const http = createHttpClient({ onUnauthenticated: markUnauthenticated })
