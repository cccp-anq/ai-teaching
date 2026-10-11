import { describe, expect, it } from 'vitest'
import { classroomJobState } from '../src/views/classroomJobState'

describe('classroom job notices', () => {
  it('does not describe queued or running jobs as unconfigured', () => {
    expect(classroomJobState(['QUEUED', 'RUNNING'])).toEqual({
      waitingForConfiguration: false, processing: true,
    })
  })

  it('keeps the two notices independent when module states differ', () => {
    expect(classroomJobState(['WAITING_EXTERNAL', 'RUNNING'])).toEqual({
      waitingForConfiguration: true, processing: true,
    })
    expect(classroomJobState(['WAITING_EXTERNAL', 'FAILED'])).toEqual({
      waitingForConfiguration: true, processing: false,
    })
  })

  it('stops polling notices after both jobs reach terminal states', () => {
    expect(classroomJobState(['SUCCEEDED', 'FAILED'])).toEqual({
      waitingForConfiguration: false, processing: false,
    })
  })
})
