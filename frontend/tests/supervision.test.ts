import type { AxiosInstance } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { fetchCoverageView, importSupervisionEvaluations } from '../src/api/supervision'

describe('coverage snapshot API', () => {
  it('loads summary and both drilldowns with one request', async () => {
    const snapshot = {
      summary: { semesterId: null, ownerCollegeId: null, eligible: 2, covered: 1, uncovered: 1, coverageRate: 0.5 },
      covered: [{ offeringId: 1, displayName: 'A', semesterCode: 'S', semesterName: '学期', studentCount: 0, ownerCollegeId: 1 }],
      uncovered: [{ offeringId: 2, displayName: 'B', semesterCode: 'S', semesterName: '学期', studentCount: null, ownerCollegeId: null }],
    }
    const get = vi.fn().mockResolvedValue({ data: snapshot })
    const client = { get } as unknown as AxiosInstance

    await expect(fetchCoverageView({ semesterId: 3, ownerCollegeId: 4 }, client)).resolves.toEqual(snapshot)
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/supervision/coverage/view', {
      params: { semesterId: 3, ownerCollegeId: 4 },
    })
  })
})

describe('supervision text entry API', () => {
  it('posts pasted text and TXT source metadata as JSON with CSRF', async () => {
    const get = vi.fn().mockResolvedValue({
      data: { token: 'token', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' },
    })
    const result = {
      batchId: 9, status: 'SUCCESS', totalRows: 1, successRows: 1,
      failureRows: 0, importedEvaluations: 1, errors: [],
    }
    const post = vi.fn().mockResolvedValue({ data: result })
    const client = { get, post } as unknown as AxiosInstance

    await expect(importSupervisionEvaluations('101 已完成 2026-09-15 10:30', 'daily.txt', client))
      .resolves.toEqual(result)
    expect(post).toHaveBeenCalledWith('/imports/supervision-evaluations', {
      text: '101 已完成 2026-09-15 10:30', sourceName: 'daily.txt',
    }, { headers: { 'X-CSRF-TOKEN': 'token' } })
  })
})
