import { AxiosError } from 'axios'
import type { AxiosAdapter, AxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { ImportResultFailure, importTimetable, isImportResult } from '../src/api/imports'
import { ApiClientError, createHttpClient } from '../src/api/client'
import type { TimetableImportResult } from '../src/api/types'

const successResult: TimetableImportResult = {
  batchId: 7, status: 'SUCCESS', totalRows: 9, successRows: 9, failureRows: 0,
  createdOfferings: 9, reusedOfferings: 0, createdSchedules: 9, reusedSchedules: 0,
  createdTeachers: 9, reusedTeachers: 0, createdLocations: 9, reusedLocations: 0, errors: [],
}

const conflictResult: TimetableImportResult = {
  batchId: null, status: 'CONFLICT', totalRows: 9, successRows: 0, failureRows: 9,
  createdOfferings: 0, reusedOfferings: 0, createdSchedules: 0, reusedSchedules: 0,
  createdTeachers: 0, reusedTeachers: 0, createdLocations: 0, reusedLocations: 0,
  errors: [{ sourceIndex: 3, field: 'p', domain: 'Offering', errorCode: 'SOURCE_OFFERING_STUDENT_COUNT_CONFLICT', reason: '人数不一致' }],
}

function scripted(steps: Array<{ method: string; endsWith: string; status: number; data: unknown }>) {
  const queue = [...steps]
  const calls: AxiosRequestConfig[] = []
  const adapter: AxiosAdapter = async config => {
    const method = (config.method ?? 'get').toLowerCase()
    calls.push(config)
    const index = queue.findIndex(step => step.method === method && (config.url ?? '').endsWith(step.endsWith))
    if (index < 0) throw new Error(`unexpected call ${method} ${config.url}`)
    const step = queue.splice(index, 1)[0]
    if (step === undefined) throw new Error('scripted step missing')
    if (step.status >= 400) {
      throw new AxiosError('failure', 'ERR_BAD_REQUEST', config, {}, {
        status: step.status, statusText: 'error', data: step.data, headers: {}, config,
      })
    }
    return { status: step.status, statusText: 'OK', data: step.data, headers: {}, config }
  }
  return { calls, client: createHttpClient({ adapter }) }
}

const file = new File(['<html></html>'], 'page.html', { type: 'text/html' })

describe('import result shape detection', () => {
  it('accepts structured TimetableImportResult bodies only', () => {
    expect(isImportResult(successResult)).toBe(true)
    expect(isImportResult(conflictResult)).toBe(true)
    expect(isImportResult({ code: 'FORBIDDEN', message: 'x' })).toBe(false)
    expect(isImportResult(null)).toBe(false)
    expect(isImportResult('oops')).toBe(false)
  })
})

describe('importTimetable error mapping', () => {
  it('posts multipart with CSRF and returns a SUCCESS result', async () => {
    const { client, calls } = scripted([
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'tok', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/imports/timetable', status: 200, data: successResult },
    ])
    await expect(importTimetable(file, 1, 1, client)).resolves.toMatchObject({ status: 'SUCCESS', batchId: 7 })
    const post = calls.find(call => (call.url ?? '').endsWith('/imports/timetable'))
    const headers = post?.headers as Record<string, unknown> | undefined
    const csrf = Object.entries(headers ?? {}).find(([key]) => key.toLowerCase() === 'x-csrf-token')
    expect(csrf?.[1]).toBe('tok')
  })

  it('maps a 413 structured body to ImportResultFailure with FILE_TOO_LARGE', async () => {
    const tooLarge: TimetableImportResult = {
      ...successResult, batchId: null, status: 'FAILED', successRows: 0, failureRows: 0,
      errors: [{ sourceIndex: null, field: 'file', domain: 'Parser', errorCode: 'FILE_TOO_LARGE', reason: 'exceeds 5 MiB' }],
    }
    const { client } = scripted([
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'tok', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/imports/timetable', status: 413, data: tooLarge },
    ])
    const failure = await importTimetable(file, 1, 1, client).catch((cause: unknown) => cause)
    expect(failure).toBeInstanceOf(ImportResultFailure)
    expect((failure as ImportResultFailure).status).toBe(413)
    expect((failure as ImportResultFailure).result.errors[0]?.errorCode).toBe('FILE_TOO_LARGE')
  })

  it('maps a 409 structured body to ImportResultFailure with row-level errors', async () => {
    const { client } = scripted([
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'tok', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/imports/timetable', status: 409, data: conflictResult },
    ])
    const failure = await importTimetable(file, 1, 1, client).catch((cause: unknown) => cause)
    expect(failure).toBeInstanceOf(ImportResultFailure)
    expect((failure as ImportResultFailure).result.status).toBe('CONFLICT')
    expect((failure as ImportResultFailure).result.errors[0]?.sourceIndex).toBe(3)
  })

  it('keeps 401/403 as ApiClientError so pages can show login/permission states', async () => {
    const { client } = scripted([
      { method: 'get', endsWith: '/auth/csrf', status: 200, data: { token: 'tok', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' } },
      { method: 'post', endsWith: '/imports/timetable', status: 403, data: { code: 'FORBIDDEN', message: 'Access denied' } },
    ])
    const error = await importTimetable(file, 1, 1, client).catch((cause: unknown) => cause)
    expect(error).toBeInstanceOf(ApiClientError)
    expect(error).not.toBeInstanceOf(ImportResultFailure)
    expect(error).toMatchObject({ status: 403, code: 'FORBIDDEN' })
  })
})
