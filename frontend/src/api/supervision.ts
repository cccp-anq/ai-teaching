import type { AxiosInstance } from 'axios'
import { http } from './client'
import { fetchCsrf } from './auth'
import { ApiClientError } from './client'
import type {
  CoverageOfferingRow,
  SupervisionCoverageSummary,
  SupervisionCoverageView,
  SupervisionImportResult,
} from './types'

const SUPERVISION_STATUSES = new Set(['SUCCESS', 'ALREADY_IMPORTED', 'REJECTED'])

export function isSupervisionResult(value: unknown): value is SupervisionImportResult {
  if (value === null || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.status === 'string' && SUPERVISION_STATUSES.has(record.status)
    && typeof record.importedEvaluations === 'number'
    && Array.isArray(record.errors)
}

export class SupervisionImportFailure extends Error {
  constructor(
    public readonly result: SupervisionImportResult,
    public readonly status: number,
  ) {
    super(result.status)
    this.name = 'SupervisionImportFailure'
  }
}

export async function importSupervisionEvaluations(
  text: string,
  sourceName = 'manual-input.txt',
  client: AxiosInstance = http,
): Promise<SupervisionImportResult> {
  const token = await fetchCsrf(client)
  try {
    const { data } = await client.post<SupervisionImportResult>('/imports/supervision-evaluations', { text, sourceName }, {
      headers: { [token.headerName]: token.token },
    })
    return data
  } catch (error) {
    if (error instanceof ApiClientError && error.status !== undefined && isSupervisionResult(error.raw)) {
      throw new SupervisionImportFailure(error.raw, error.status)
    }
    throw error
  }
}

export interface CoverageFilters {
  semesterId: number | null
  ownerCollegeId: number | null
}

export function coverageQuery(filters: CoverageFilters): Record<string, string | number> {
  const params: Record<string, string | number> = {}
  if (filters.semesterId !== null) params.semesterId = filters.semesterId
  if (filters.ownerCollegeId !== null) params.ownerCollegeId = filters.ownerCollegeId
  return params
}

export async function fetchCoverageSummary(
  filters: CoverageFilters,
  client: AxiosInstance = http,
): Promise<SupervisionCoverageSummary> {
  const { data } = await client.get<SupervisionCoverageSummary>('/supervision/coverage', { params: coverageQuery(filters) })
  return data
}

export async function fetchCoverageView(
  filters: CoverageFilters,
  client: AxiosInstance = http,
): Promise<SupervisionCoverageView> {
  const { data } = await client.get<SupervisionCoverageView>('/supervision/coverage/view', {
    params: coverageQuery(filters),
  })
  return data
}

export async function fetchCoverageDrilldown(
  covered: boolean,
  filters: CoverageFilters,
  client: AxiosInstance = http,
): Promise<CoverageOfferingRow[]> {
  const path = covered ? '/supervision/coverage/covered' : '/supervision/coverage/uncovered'
  const { data } = await client.get<CoverageOfferingRow[]>(path, { params: coverageQuery(filters) })
  return data
}
