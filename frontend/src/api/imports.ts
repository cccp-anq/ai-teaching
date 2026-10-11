import type { AxiosInstance } from 'axios'
import { ApiClientError, http } from './client'
import { fetchCsrf } from './auth'
import type { TimetableImportResult } from './types'

/** Raised when the backend answers with a structured TimetableImportResult (409/413/422). */
export class ImportResultFailure extends Error {
  constructor(
    public readonly result: TimetableImportResult,
    public readonly status: number,
  ) {
    super(result.status)
    this.name = 'ImportResultFailure'
  }
}

const IMPORT_STATUSES = new Set(['SUCCESS', 'ALREADY_IMPORTED', 'FAILED', 'CONFLICT'])

/** Shape check; the import error body is a TimetableImportResult, not the shared ApiError. */
export function isImportResult(value: unknown): value is TimetableImportResult {
  if (value === null || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.status === 'string' && IMPORT_STATUSES.has(record.status)
    && typeof record.totalRows === 'number'
    && Array.isArray(record.errors)
}

export async function importTimetable(
  file: File,
  semesterId: number,
  sourceScopeCollegeId: number,
  client: AxiosInstance = http,
): Promise<TimetableImportResult> {
  const token = await fetchCsrf(client)
  const form = new FormData()
  form.append('file', file)
  form.append('semesterId', String(semesterId))
  form.append('sourceScopeCollegeId', String(sourceScopeCollegeId))
  try {
    const { data } = await client.post<TimetableImportResult>('/imports/timetable', form, {
      headers: { [token.headerName]: token.token },
    })
    return data
  } catch (error) {
    if (error instanceof ApiClientError && error.status !== undefined && isImportResult(error.raw)) {
      throw new ImportResultFailure(error.raw, error.status)
    }
    throw error
  }
}
