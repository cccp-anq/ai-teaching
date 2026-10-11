import type { AxiosInstance } from 'axios'
import { fetchCsrf } from './auth'
import { http } from './client'
import type { ClassroomAnalysis, GeneratedClassroomReport, GeneratedReportProgress, GeneratedReportVersions,
  GeneratedReportProgressState, ClassroomReport } from './types'

export interface UploadClassroomVideoInput {
  offeringId: number
  idempotencyKey: string
  scheduleId: number
  sessionDate: string
  title: string
  durationMs: number
  video: File
  onProgress?: (loaded: number, total: number | undefined) => void
}

export interface ClassroomUploadAttempt {
  fingerprint: string
  idempotencyKey: string
}

export function classroomUploadAttempt(
  current: ClassroomUploadAttempt | null,
  fingerprint: string,
  createKey: () => string = () => crypto.randomUUID(),
): ClassroomUploadAttempt {
  return current?.fingerprint === fingerprint ? current : { fingerprint, idempotencyKey: createKey() }
}

export async function uploadClassroomVideo(
  input: UploadClassroomVideoInput,
  client: AxiosInstance = http,
): Promise<ClassroomAnalysis> {
  const csrf = await fetchCsrf(client)
  const form = new FormData()
  form.append('scheduleId', String(input.scheduleId))
  form.append('sessionDate', input.sessionDate)
  form.append('title', input.title.trim())
  form.append('durationMs', String(input.durationMs))
  form.append('video', input.video)
  const { data } = await client.post<ClassroomAnalysis>(
    `/course-offerings/${input.offeringId}/class-sessions/video`, form,
    {
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': input.idempotencyKey },
      timeout: 0,
      onUploadProgress: event => input.onProgress?.(event.loaded, event.total),
    },
  )
  return data
}

export async function fetchClassrooms(
  offeringId: number,
  client: AxiosInstance = http,
): Promise<ClassroomAnalysis[]> {
  const { data } = await client.get<ClassroomAnalysis[]>(`/course-offerings/${offeringId}/class-sessions`)
  return data
}

export async function fetchClassroom(
  classSessionId: number,
  client: AxiosInstance = http,
): Promise<ClassroomAnalysis> {
  const { data } = await client.get<ClassroomAnalysis>(`/class-sessions/${classSessionId}`)
  return data
}

export async function fetchGeneratedClassroomReport(
  mediaId: number,
  client: AxiosInstance = http,
): Promise<GeneratedClassroomReport | null> {
  const response = await client.get<GeneratedClassroomReport>(`/class-sessions/media/${mediaId}/generated-report`)
  return response.status === 204 ? null : response.data
}

export async function fetchGeneratedReportProgress(
  mediaId: number,
  client: AxiosInstance = http,
): Promise<GeneratedReportProgress> {
  const { data } = await client.get<GeneratedReportProgress>(`/class-sessions/media/${mediaId}/generated-report/progress`)
  return data
}

export async function fetchGeneratedReportVersions(
  mediaId: number,
  page = 0,
  client: AxiosInstance = http,
): Promise<GeneratedReportVersions> {
  const { data } = await client.get<GeneratedReportVersions>(
    `/class-sessions/media/${mediaId}/generated-report/versions`, { params: { page } },
  )
  return data
}

export interface GeneratedReportRetryResult {
  state: GeneratedReportProgressState
  replayed: boolean
}

export async function requestGeneratedReportRetry(
  mediaId: number,
  idempotencyKey: string,
  client: AxiosInstance = http,
): Promise<GeneratedReportRetryResult> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.post<GeneratedReportRetryResult>(
    `/class-sessions/media/${mediaId}/generated-report/retry`,
    { confirmAdditionalCharge: true },
    { headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': idempotencyKey } },
  )
  return data
}

export interface ModuleRetryResult {
  state: ClassroomReport['status']
  replayed: boolean
}

export interface TranscriptionRecoveryResult {
  state: ClassroomReport['status']
  replayed: boolean
}

export async function recoverExistingTranscription(
  mediaId: number,
  idempotencyKey: string,
  client: AxiosInstance = http,
): Promise<TranscriptionRecoveryResult> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.post<TranscriptionRecoveryResult>(
    `/class-sessions/media/${mediaId}/transcription-result/recover`,
    { confirmExistingResult: true },
    { headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': idempotencyKey } },
  )
  return data
}

export async function requestFailedModuleRetry(
  mediaId: number,
  idempotencyKey: string,
  client: AxiosInstance = http,
): Promise<ModuleRetryResult> {
  const csrf = await fetchCsrf(client)
  const { data } = await client.post<ModuleRetryResult>(
    `/class-sessions/media/${mediaId}/module-jobs/retry`,
    { confirmAdditionalCharge: true },
    { headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': idempotencyKey } },
  )
  return data
}
