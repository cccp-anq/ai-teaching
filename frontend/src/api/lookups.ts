import type { AxiosInstance } from 'axios'
import { http } from './client'
import type { CollegeLookup, SemesterLookup, TeacherLookup } from './types'

export async function fetchSemesterOptions(client: AxiosInstance = http): Promise<SemesterLookup[]> {
  const { data } = await client.get<SemesterLookup[]>('/lookups/semesters')
  return data
}

export async function fetchCollegeOptions(client: AxiosInstance = http): Promise<CollegeLookup[]> {
  const { data } = await client.get<CollegeLookup[]>('/lookups/colleges')
  return data
}

/** Formal owner colleges (Offering → Course → owner_college_id) inside the caller's authorized dataset. */
export async function fetchOwnerCollegeOptions(client: AxiosInstance = http): Promise<CollegeLookup[]> {
  const { data } = await client.get<CollegeLookup[]>('/lookups/owner-colleges')
  return data
}

/**
 * Teacher display identities inside the caller's authorized dataset only
 * (Authorized Dataset → ScheduleTeacher → Teacher); not a full directory.
 */
export async function fetchTeacherOptions(client: AxiosInstance = http): Promise<TeacherLookup[]> {
  const { data } = await client.get<TeacherLookup[]>('/lookups/teachers')
  return data
}
