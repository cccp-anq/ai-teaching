import { describe, expect, it } from 'vitest'
import type { ScheduleDetail } from '../src/api/types'
import { schedulesForClassDate } from '../src/lib/classroomSchedule'

const location: ScheduleDetail['location'] = {
  locationId: 1, displayName: '教室', locationGroup: '教学楼', locationType: 'PHYSICAL',
}
function schedule(id: number, weekday: number, startSection: number, weeks: number[]): ScheduleDetail {
  return { scheduleId: id, weekday, startSection, endSection: startSection + 1,
    weeks, rawWeekText: '', location, teachers: [] }
}

describe('classroom schedule selection', () => {
  const rows = [schedule(3, 4, 11, [4]), schedule(2, 4, 9, [1, 2, 3, 4]),
    schedule(1, 6, 5, [3])]

  it('matches both teaching week and weekday, ordering multiple lessons by first section', () => {
    const result = schedulesForClassDate(rows, '2026-08-30', '2026-09-24')
    expect(result.mode).toBe('matched')
    expect(result.schedules.map(row => row.scheduleId)).toEqual([2, 3])
    expect(schedulesForClassDate(rows, '2026-08-30', '2026-09-17').schedules.map(row => row.scheduleId))
      .toEqual([2])
  })

  it('does not select an unrelated schedule or guess from an invalid date', () => {
    expect(schedulesForClassDate(rows, '2026-08-30', '2026-09-25'))
      .toEqual({ mode: 'no-match', schedules: [] })
    expect(schedulesForClassDate(rows, '2026-08-30', '2026-02-30'))
      .toEqual({ mode: 'select-date', schedules: [] })
  })

  it('requires manual choice when the semester calendar is not configured', () => {
    const result = schedulesForClassDate(rows, null, '2026-09-24')
    expect(result.mode).toBe('manual')
    expect(result.schedules.map(row => row.scheduleId)).toEqual([1, 2, 3])
  })
})
