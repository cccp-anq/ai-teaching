import { AxiosError } from 'axios'
import type { AxiosAdapter, AxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { emptyFilters, searchOfferings, toQueryParams } from '../src/api/courses'
import { createHttpClient } from '../src/api/client'
import type { PageResult } from '../src/api/types'

describe('query parameter serialization', () => {
  it('omits every empty condition and keeps only paging', () => {
    expect(toQueryParams(emptyFilters(), 1, 20)).toEqual({ page: 1, size: 20 })
  })

  it('trims text conditions and drops blank ones instead of sending empty strings', () => {
    const filters = { ...emptyFilters(), courseName: '  数据结构 ', teacher: '   ', location: '测试楼A101' }
    expect(toQueryParams(filters, 2, 20)).toEqual({
      page: 2, size: 20, courseName: '数据结构', location: '测试楼A101',
    })
  })

  it('drops null numbers but keeps valid ones including boundary values', () => {
    const filters = { ...emptyFilters(), semesterId: 1, week: 18, weekday: 7, section: 12, sourceScopeCollegeId: null }
    expect(toQueryParams(filters, 1, 50)).toEqual({
      page: 1, size: 50, semesterId: 1, week: 18, weekday: 7, section: 12,
    })
  })

  it('serializes ownerCollegeId independently from sourceScopeCollegeId', () => {
    const both = { ...emptyFilters(), sourceScopeCollegeId: 1, ownerCollegeId: 2 }
    expect(toQueryParams(both, 1, 20)).toEqual({ page: 1, size: 20, sourceScopeCollegeId: 1, ownerCollegeId: 2 })
    expect(toQueryParams({ ...emptyFilters(), ownerCollegeId: 2 }, 1, 20))
      .toEqual({ page: 1, size: 20, ownerCollegeId: 2 })
    expect(toQueryParams({ ...emptyFilters() }, 1, 20)).toEqual({ page: 1, size: 20 })
  })
})

describe('searchOfferings HTTP call', () => {
  it('requests /course-offerings with serialized params and returns the page payload', async () => {
    const page: PageResult<unknown> = { items: [{ offeringId: 101 }], total: 1, page: 1, size: 20 }
    let seen: AxiosRequestConfig | undefined
    const adapter: AxiosAdapter = async config => {
      seen = config
      return { status: 200, statusText: 'OK', data: page, headers: {}, config }
    }
    const client = createHttpClient({ adapter })
    const params = toQueryParams({ ...emptyFilters(), teacher: 'Alpha' }, 1, 20)
    await expect(searchOfferings(params, client)).resolves.toMatchObject({ total: 1 })
    expect(seen?.url).toContain('/course-offerings')
    expect(seen?.params).toMatchObject({ teacher: 'Alpha', page: 1, size: 20 })
  })

  it('keeps a 403 as a structured ApiClientError for the no-permission state', async () => {
    const adapter: AxiosAdapter = async config => {
      throw new AxiosError('forbidden', 'ERR_BAD_REQUEST', config, {}, {
        status: 403, statusText: 'error', data: { code: 'FORBIDDEN' }, headers: {}, config,
      })
    }
    const client = createHttpClient({ adapter })
    await expect(searchOfferings({ page: 1, size: 20 }, client)).rejects.toMatchObject({
      status: 403, code: 'FORBIDDEN',
    })
  })
})
