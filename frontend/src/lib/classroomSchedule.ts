import type { ScheduleDetail } from '../api/types'

export type ScheduleMatchMode = 'select-date' | 'matched' | 'no-match' | 'manual'

export interface ScheduleMatch {
  mode: ScheduleMatchMode
  schedules: ScheduleDetail[]
}

const DAY_MS = 24 * 60 * 60 * 1000

function utcDate(value: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? null : date
}

function byFirstSection(a: ScheduleDetail, b: ScheduleDetail): number {
  return a.startSection - b.startSection || a.endSection - b.endSection || a.scheduleId - b.scheduleId
}

/** DEC-015: firstWeekDate is a Sunday; weekday is Monday=1 through Sunday=7. */
export function schedulesForClassDate(
  schedules: ScheduleDetail[], firstWeekDate: string | null, selectedDate: string,
): ScheduleMatch {
  const date = utcDate(selectedDate)
  if (date === null) return { mode: 'select-date', schedules: [] }
  const firstWeek = firstWeekDate === null ? null : utcDate(firstWeekDate)
  if (firstWeek === null || firstWeek.getUTCDay() !== 0) {
    return { mode: 'manual', schedules: [...schedules].sort(byFirstSection) }
  }
  const week = Math.floor((date.getTime() - firstWeek.getTime()) / (7 * DAY_MS)) + 1
  const weekday = date.getUTCDay() || 7
  const matches = schedules.filter(schedule => schedule.weekday === weekday
    && schedule.weeks.includes(week)).sort(byFirstSection)
  return { mode: matches.length > 0 ? 'matched' : 'no-match', schedules: matches }
}
