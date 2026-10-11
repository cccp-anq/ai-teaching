import type { LocationDetail, SyllabusStatus } from '../api/types'

/** Unified missing-value marker; never invents business content in its place. */
export const MISSING = '—'

const WEEKDAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const

export function weekdayLabel(weekday: number): string {
  return WEEKDAY_NAMES[weekday - 1] ?? MISSING
}

export function weekdayLongLabel(weekday: number): string {
  const short = weekdayLabel(weekday)
  return short === MISSING ? MISSING : `星期${short.slice(1)}`
}

export function activityTypeLabel(activityType: string): string {
  if (activityType === 'UNDERGRAD_COURSE') return '本科课程'
  if (activityType === 'PRACTICE') return '实践环节'
  return activityType === '' ? MISSING : activityType
}

export function locationTypeTag(locationType: LocationDetail['locationType']): string | null {
  if (locationType === 'ONLINE') return '线上'
  if (locationType === 'TBD') return '待定'
  return null
}

export function locationText(location: LocationDetail): string {
  const tag = locationTypeTag(location.locationType)
  return tag === null ? location.displayName : `${location.displayName}（${tag}）`
}

export function syllabusLabel(status: SyllabusStatus): string {
  return status === 'AVAILABLE' ? '大纲可用' : '大纲缺失'
}

export function sectionLabel(startSection: number, endSection: number): string {
  return startSection === endSection ? `第${startSection}节` : `第${startSection}-${endSection}节`
}

/** studentCount keeps the stored value: 0 renders as 0, only null renders as missing. */
export function studentCountLabel(studentCount: number | null): string {
  return studentCount === null ? MISSING : String(studentCount)
}

/** Syllabus entry display per B-02: HTTPS locators link, approved logical locators label. */
export function syllabusEntryLabel(syllabusUrl: string | null, status: SyllabusStatus): string {
  if (status === 'MISSING') return '大纲缺失'
  return syllabusUrl === null ? '大纲可用（未提供入口）' : '查看课程大纲'
}

export function isHttpLocator(syllabusUrl: string | null): boolean {
  return syllabusUrl !== null && syllabusUrl.toLowerCase().startsWith('https://')
}

export function displayText(value: string | null | undefined): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : MISSING
}

/** Compresses the stored discrete weeks into ranges for display only; never re-derives them. */
export function compressWeeks(weeks: number[]): string {
  if (weeks.length === 0) return MISSING
  const parts: string[] = []
  const first = weeks[0]
  if (first === undefined) return MISSING
  let start = first
  let previous = first
  for (let index = 1; index < weeks.length; index += 1) {
    const current = weeks[index]
    if (current === undefined) break
    if (current !== previous + 1) {
      parts.push(start === previous ? `${start}` : `${start}-${previous}`)
      start = current
    }
    previous = current
  }
  parts.push(start === previous ? `${start}` : `${start}-${previous}`)
  return parts.join(', ')
}
