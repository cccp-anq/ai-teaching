import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createHttpClient } from '../src/api/client'
import { classroomUploadAttempt, fetchGeneratedClassroomReport, fetchGeneratedReportProgress, fetchGeneratedReportVersions, recoverExistingTranscription, requestFailedModuleRetry, requestGeneratedReportRetry, uploadClassroomVideo } from '../src/api/classroom'

vi.mock('../src/api/auth', () => ({
  fetchCsrf: vi.fn(async () => ({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' })),
}))

describe('classroom video API', () => {
  it('reuses an upload key only while the request fingerprint is unchanged', () => {
    let sequence = 0
    const createKey = () => `upload-key-${++sequence}`
    const first = classroomUploadAttempt(null, 'request-a', createKey)
    const retry = classroomUploadAttempt(first, 'request-a', createKey)
    const changed = classroomUploadAttempt(retry, 'request-b', createKey)

    expect(retry.idempotencyKey).toBe(first.idempotencyKey)
    expect(changed.idempotencyKey).not.toBe(first.idempotencyKey)
    expect(sequence).toBe(2)
  })

  it('uploads without an overall timeout and sends the normalized title', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return {
        data: { classSessionId: 91, offeringId: 101, scheduleId: 1001, sessionDate: '2026-09-14',
          title: '课堂', createdAt: '2026-09-14T00:00:00', media: [] },
        status: 201, statusText: 'Created', headers: {}, config,
      }
    }
    const client = createHttpClient({ adapter })
    const result = await uploadClassroomVideo({
      offeringId: 101, idempotencyKey: 'upload-request-0001', scheduleId: 1001,
      sessionDate: '2026-09-14', title: ' 课堂 ',
      durationMs: 60_000, video: new File(['video'], 'lesson.mp4', { type: 'video/mp4' }),
    }, client)

    expect(result.classSessionId).toBe(91)
    const request = requests[0]!
    expect(request.url).toBe('/course-offerings/101/class-sessions/video')
    expect(request.method).toBe('post')
    expect(request.timeout).toBe(0)
    expect(request.onUploadProgress).toBeTypeOf('function')
    expect(request.headers.get('X-CSRF-TOKEN')).toBe('csrf-value')
    expect(request.headers.get('Idempotency-Key')).toBe('upload-request-0001')
    const form = request.data as FormData
    expect(form.get('scheduleId')).toBe('1001')
    expect(form.get('sessionDate')).toBe('2026-09-14')
    expect(form.get('durationMs')).toBe('60000')
    expect(form.get('title')).toBe('课堂')
    expect((form.get('video') as File).name).toBe('lesson.mp4')
  })

  it('reads only the generated report endpoint and treats 204 as pending', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    let available = false
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: available ? { topics: [{ summary: '讲解递归' }], changes: [],
        interpretations: [], limitations: ['不能推断理解程度'] } : '',
      status: available ? 200 : 204, statusText: available ? 'OK' : 'No Content', headers: {}, config }
    }
    const client = createHttpClient({ adapter })
    expect(await fetchGeneratedClassroomReport(42, client)).toBeNull()
    available = true
    expect((await fetchGeneratedClassroomReport(42, client))?.topics[0]?.summary).toBe('讲解递归')
    expect(requests.map(request => request.url)).toEqual([
      '/class-sessions/media/42/generated-report', '/class-sessions/media/42/generated-report',
    ])
    expect(requests.every(request => request.method === 'get')).toBe(true)
  })

  it('reads paged report version metadata without a historical body request', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: { items: [{ reportId: 17, generatedAt: '2026-09-24T10:00:00', current: true }],
        nextPage: 2 }, status: 200, statusText: 'OK', headers: {}, config }
    }
    const result = await fetchGeneratedReportVersions(42, 1, createHttpClient({ adapter }))
    expect(result.items[0]?.reportId).toBe(17)
    expect(requests).toHaveLength(1)
    expect(requests[0]?.url).toBe('/class-sessions/media/42/generated-report/versions')
    expect(requests[0]?.params).toEqual({ page: 1 })
    expect(requests[0]?.method).toBe('get')
  })

  it('reads the generated report progress separately', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: { state: 'PAUSED' }, status: 200, statusText: 'OK', headers: {}, config }
    }
    expect(await fetchGeneratedReportProgress(42, createHttpClient({ adapter }))).toEqual({ state: 'PAUSED' })
    expect(requests.map(request => request.url)).toEqual(['/class-sessions/media/42/generated-report/progress'])
    expect(requests[0]?.method).toBe('get')
  })

  it('submits an explicitly confirmed report retry with CSRF and a stable operation key', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: { state: 'QUEUED', replayed: false }, status: 202,
        statusText: 'Accepted', headers: {}, config }
    }
    const result = await requestGeneratedReportRetry(42, 'supervisor-retry-0001', createHttpClient({ adapter }))
    expect(result).toEqual({ state: 'QUEUED', replayed: false })
    expect(requests).toHaveLength(1)
    expect(requests[0]?.url).toBe('/class-sessions/media/42/generated-report/retry')
    expect(requests[0]?.method).toBe('post')
    expect(requests[0]?.data).toBe('{"confirmAdditionalCharge":true}')
    expect(requests[0]?.headers.get('X-CSRF-TOKEN')).toBe('csrf-value')
    expect(requests[0]?.headers.get('Idempotency-Key')).toBe('supervisor-retry-0001')
  })

  it('submits a confirmed module retry with its own idempotency key', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: { state: 'WAITING_INPUT', replayed: false }, status: 202,
        statusText: 'Accepted', headers: {}, config }
    }
    const result = await requestFailedModuleRetry(42, 'module-retry-0001', createHttpClient({ adapter }))
    expect(result).toEqual({ state: 'WAITING_INPUT', replayed: false })
    expect(requests[0]?.url).toBe('/class-sessions/media/42/module-jobs/retry')
    expect(requests[0]?.method).toBe('post')
    expect(requests[0]?.data).toBe('{"confirmAdditionalCharge":true}')
    expect(requests[0]?.headers.get('X-CSRF-TOKEN')).toBe('csrf-value')
    expect(requests[0]?.headers.get('Idempotency-Key')).toBe('module-retry-0001')
  })

  it('recovers an existing transcription with a separate confirmed operation', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const adapter: AxiosAdapter = async config => {
      requests.push(config)
      return { data: { state: 'READY', replayed: false }, status: 202,
        statusText: 'Accepted', headers: {}, config }
    }
    const result = await recoverExistingTranscription(42, 'recovery-action-0001', createHttpClient({ adapter }))
    expect(result).toEqual({ state: 'READY', replayed: false })
    expect(requests[0]?.url).toBe('/class-sessions/media/42/transcription-result/recover')
    expect(requests[0]?.method).toBe('post')
    expect(requests[0]?.data).toBe('{"confirmExistingResult":true}')
    expect(requests[0]?.headers.get('Idempotency-Key')).toBe('recovery-action-0001')
  })
})
