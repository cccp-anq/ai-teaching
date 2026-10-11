import type { AxiosInstance } from 'axios'
import { http } from './client'
import type { TeacherComparisonResult } from './types'

export async function fetchTeacherComparison(
  teacherIds: number[],
  client: AxiosInstance = http,
): Promise<TeacherComparisonResult> {
  const { data } = await client.get<TeacherComparisonResult>('/teachers/comparison', {
    params: { teacherIds: teacherIds.join(',') },
  })
  return data
}
