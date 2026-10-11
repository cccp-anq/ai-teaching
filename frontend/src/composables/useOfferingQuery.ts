import { reactive, readonly, ref } from 'vue'
import {
  emptyFilters,
  searchOfferings,
  toQueryParams,
} from '../api/courses'
import type { OfferingFilters } from '../api/courses'
import { ApiClientError } from '../api/client'
import type { CourseOfferingListItem } from '../api/types'

export type QueryStatus = 'idle' | 'loading' | 'ready' | 'error'

/**
 * Offering list state machine. The server is the only query engine: this composable
 * only serializes the user's chosen combination and renders what the backend returns;
 * it never intersects, unions or filters conditions locally (Same-Schedule invariant).
 */
export function useOfferingQuery(defaultSize = 20) {
  const filters = reactive<OfferingFilters>(emptyFilters())
  const page = ref(1)
  const size = ref(defaultSize)
  const items = ref<CourseOfferingListItem[]>([])
  const total = ref(0)
  const status = ref<QueryStatus>('idle')
  const error = ref<ApiClientError | null>(null)

  async function search(): Promise<void> {
    status.value = 'loading'
    error.value = null
    try {
      const result = await searchOfferings(toQueryParams(filters, page.value, size.value))
      items.value = result.items
      total.value = result.total
      status.value = 'ready'
    } catch (cause) {
      items.value = []
      total.value = 0
      error.value = cause instanceof ApiClientError ? cause : new ApiClientError('请求失败，请稍后重试。', 'UNKNOWN_ERROR')
      status.value = 'error'
    }
  }

  /** Filter changes always go back to the first page so no hidden condition survives. */
  function applyFilters(): Promise<void> {
    page.value = 1
    return search()
  }

  function goToPage(target: number): Promise<void> {
    const bounded = Math.max(1, Math.floor(target))
    if (bounded === page.value && status.value !== 'idle') return Promise.resolve()
    page.value = bounded
    return search()
  }

  /** Clearing resets every field (visible or not) and re-queries the authorized default list. */
  function clearFilters(): Promise<void> {
    Object.assign(filters, emptyFilters())
    page.value = 1
    return search()
  }

  return {
    filters,
    page,
    size,
    items,
    total,
    status,
    error,
    search,
    applyFilters,
    goToPage,
    clearFilters,
  }
}

export type OfferingQuery = ReturnType<typeof useOfferingQuery>

export function readonlyState(query: OfferingQuery) {
  return {
    filters: readonly(query.filters),
    page: readonly(query.page),
    items: readonly(query.items),
    total: readonly(query.total),
    status: readonly(query.status),
    error: readonly(query.error),
  }
}
