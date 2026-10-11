import type { AxiosInstance } from 'axios'
import { http } from './client'
import type { CourseOfferingDetail, CourseOfferingListItem, MyScheduleView, PageResult } from './types'

export interface OfferingFilters {
  semesterId: number | null
  courseName: string
  courseCode: string
  teacher: string
  sourceScopeCollegeId: number | null
  ownerCollegeId: number | null
  week: number | null
  weekday: number | null
  section: number | null
  location: string
}

export function emptyFilters(): OfferingFilters {
  return {
    semesterId: null,
    courseName: '',
    courseCode: '',
    teacher: '',
    sourceScopeCollegeId: null,
    ownerCollegeId: null,
    week: null,
    weekday: null,
    section: null,
    location: '',
  }
}

export const WEEKDAY_OPTIONS = [1, 2, 3, 4, 5, 6, 7] as const
export const MIN_WEEK = 1
export const MAX_WEEK = 18

/**
 * Mirror of the backend binding rules: text values are trimmed, blank text and absent
 * numbers are simply not sent, and paging always carries page/size.
 */
export function toQueryParams(
  filters: OfferingFilters,
  page: number,
  size: number,
): Record<string, string | number> {
  const params: Record<string, string | number> = { page, size }
  const text = (key: 'courseName' | 'courseCode' | 'teacher' | 'location', value: string): void => {
    const trimmed = value.trim()
    if (trimmed !== '') params[key] = trimmed
  }
  const number = (key: string, value: number | null): void => {
    if (value !== null && Number.isFinite(value)) params[key] = value
  }
  text('courseName', filters.courseName)
  text('courseCode', filters.courseCode)
  text('teacher', filters.teacher)
  text('location', filters.location)
  number('semesterId', filters.semesterId)
  number('sourceScopeCollegeId', filters.sourceScopeCollegeId)
  number('ownerCollegeId', filters.ownerCollegeId)
  number('week', filters.week)
  number('weekday', filters.weekday)
  number('section', filters.section)
  return params
}

export async function searchOfferings(
  params: Record<string, string | number>,
  client: AxiosInstance = http,
): Promise<PageResult<CourseOfferingListItem>> {
  const { data } = await client.get<PageResult<CourseOfferingListItem>>('/course-offerings', { params })
  return data
}

export async function fetchOfferingDetail(
  offeringId: number,
  client: AxiosInstance = http,
): Promise<CourseOfferingDetail> {
  const { data } = await client.get<CourseOfferingDetail>(`/course-offerings/${offeringId}`)
  return data
}

export async function fetchMySchedule(
  client: AxiosInstance = http,
): Promise<MyScheduleView> {
  const { data } = await client.get<MyScheduleView>('/my-schedule')
  return data
}
