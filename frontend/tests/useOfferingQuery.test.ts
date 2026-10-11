import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import { ApiClientError } from '../src/api/client'
import type { PageResult, CourseOfferingListItem } from '../src/api/types'

const searchOfferings = vi.fn() as Mock

vi.mock('../src/api/courses', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/api/courses')>()
  return { ...actual, searchOfferings: (...args: unknown[]) => searchOfferings(...args) }
})

const { useOfferingQuery } = await import('../src/composables/useOfferingQuery')

function page(items: Array<Partial<CourseOfferingListItem>>, total = items.length): PageResult<CourseOfferingListItem> {
  return { items: items as CourseOfferingListItem[], total, page: 1, size: 20 }
}

describe('offering query state machine', () => {
  beforeEach(() => {
    searchOfferings.mockReset()
  })

  it('loads the authorized default list and reports an explicit empty state', async () => {
    searchOfferings.mockResolvedValue(page([], 0))
    const query = useOfferingQuery()
    await query.search()
    expect(query.status.value).toBe('ready')
    expect(query.total.value).toBe(0)
    expect(query.items.value).toEqual([])
    expect(query.error.value).toBeNull()
  })

  it('keeps results and total from the same authorized request', async () => {
    searchOfferings.mockResolvedValue(page([{ offeringId: 101 }, { offeringId: 102 }], 2))
    const query = useOfferingQuery()
    await query.search()
    expect(query.items.value.map(item => item.offeringId)).toEqual([101, 102])
    expect(query.total.value).toBe(2)
  })

  it('maps an HTTP error into a structured page state without losing the empty list', async () => {
    searchOfferings.mockRejectedValue(new ApiClientError('无权执行此操作。', 'FORBIDDEN', 403))
    const query = useOfferingQuery()
    await query.search()
    expect(query.status.value).toBe('error')
    expect(query.error.value?.status).toBe(403)
    expect(query.items.value).toEqual([])
    expect(query.total.value).toBe(0)
  })

  it('resets to page 1 whenever filters are applied, and never keeps hidden conditions', async () => {
    searchOfferings.mockResolvedValue(page([], 0))
    const query = useOfferingQuery()
    query.filters.teacher = 'Alpha'
    query.page.value = 3
    await query.applyFilters()
    expect(query.page.value).toBe(1)
    const firstCall = searchOfferings.mock.calls[0]?.[0] as Record<string, unknown>
    expect(firstCall).toMatchObject({ teacher: 'Alpha', page: 1 })

    await query.clearFilters()
    const secondCall = searchOfferings.mock.calls[1]?.[0] as Record<string, unknown>
    expect(secondCall).toMatchObject({ page: 1 })
    expect(secondCall).not.toHaveProperty('teacher')
    expect(query.filters.teacher).toBe('')
  })

  it('navigates pages through the server, never by slicing local results', async () => {
    searchOfferings.mockResolvedValue(page([{ offeringId: 201 }], 41))
    const query = useOfferingQuery()
    await query.search()
    await query.goToPage(2)
    const secondCall = searchOfferings.mock.calls[1]?.[0] as Record<string, unknown>
    expect(secondCall).toMatchObject({ page: 2, size: 20 })
  })
})
