import type { ClassroomJobStatus } from '../api/types'

export function classroomJobState(statuses: ClassroomJobStatus[]): {
  waitingForConfiguration: boolean
  processing: boolean
} {
  return {
    waitingForConfiguration: statuses.includes('WAITING_EXTERNAL'),
    processing: statuses.some(status => status === 'QUEUED' || status === 'RUNNING'),
  }
}
