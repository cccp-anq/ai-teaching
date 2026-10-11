import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createHttpClient } from '../src/api/client'
import { fetchSegmentDirectory, saveSegmentOverride, segmentErrorText } from '../src/api/segments'
import type { SegmentDirectoryResponse } from '../src/api/types'

vi.mock('../src/api/auth', () => ({
  fetchCsrf: vi.fn(async () => ({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' })),
}))

function directory(): SegmentDirectoryResponse {
  return {
    reportId: 9,
    mediaId: 41,
    mediaDurationMs: 60_000,
    segments: [{
      key: 'topic-0', index: 0, title: '讲解递归', startMs: 0, endMs: 20_000, durationMs: 20_000,
      evidenceCount: 1, overridden: false, originalTitle: '讲解递归', originalStartMs: 0,
      originalEndMs: 20_000, editedByUserId: null, revision: null, updatedAt: null,
    }],
  }
}

describe('fetchSegmentDirectory', () => {
  it('200 时返回目录，并带上 no-store 之外的私有缓存路径', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: directory(), status: 200, statusText: 'OK', headers: {}, config }
    }
    const client = createHttpClient({ adapter })
    const result = await fetchSegmentDirectory(41, client)

    expect(result?.mediaDurationMs).toBe(60_000)
    expect(requests[0]!.url).toBe('/class-sessions/media/41/segments')
    expect(requests[0]!.method).toBe('get')
  })

  it('204 表示这节课还没有报告版本，映射为 null 让界面渲染空状态', async () => {
    const adapter: AxiosAdapter = async config => (
      { data: '', status: 204, statusText: 'No Content', headers: {}, config }
    )
    const client = createHttpClient({ adapter })
    expect(await fetchSegmentDirectory(41, client)).toBeNull()
  })
})

describe('saveSegmentOverride', () => {
  it('保存修正：路径带片段 key、标题去空白、区间按毫秒提交，并带 CSRF 头', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: directory(), status: 200, statusText: 'OK', headers: {}, config }
    }
    const client = createHttpClient({ adapter })
    const result = await saveSegmentOverride(41, 'topic-0', { title: '  讲解递归（改）  ', startMs: 0, endMs: 18_000 }, client)

    expect(result.reportId).toBe(9)
    const request = requests[0]!
    expect(request.url).toBe('/class-sessions/media/41/segments/topic-0')
    expect(request.method).toBe('put')
    expect(JSON.parse(String(request.data))).toEqual({ title: '讲解递归（改）', startMs: 0, endMs: 18_000 })
    expect(request.headers['X-CSRF-TOKEN']).toBe('csrf-value')
  })
})

describe('segmentErrorText', () => {
  it('已登记的错误码翻成可执行提示', () => {
    expect(segmentErrorText('SEGMENT_OVERLAP', '兜底')).toContain('重叠')
    expect(segmentErrorText('SEGMENT_OUT_OF_MEDIA', '兜底')).toContain('时长')
    expect(segmentErrorText('SEGMENT_UNKNOWN', '兜底')).toContain('刷新')
  })

  it('未知错误码回落到传入的兜底文案', () => {
    expect(segmentErrorText('SOMETHING_ELSE', '兜底文案')).toBe('兜底文案')
  })
})
