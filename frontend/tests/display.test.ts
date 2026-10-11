import { describe, expect, it } from 'vitest'
import {
  MISSING,
  activityTypeLabel,
  isHttpLocator,
  syllabusEntryLabel,
  compressWeeks,
  displayText,
  locationText,
  locationTypeTag,
  sectionLabel,
  studentCountLabel,
  syllabusLabel,
  weekdayLabel,
  weekdayLongLabel,
} from '../src/lib/display'
import type { LocationDetail } from '../src/api/types'

describe('display helpers', () => {
  it('maps weekdays 1..7 and falls back to the missing marker', () => {
    expect(weekdayLabel(1)).toBe('周一')
    expect(weekdayLabel(7)).toBe('周日')
    expect(weekdayLabel(0)).toBe(MISSING)
    expect(weekdayLongLabel(1)).toBe('星期一')
  })

  it('compresses stored discrete weeks without inventing weeks', () => {
    expect(compressWeeks([])).toBe(MISSING)
    expect(compressWeeks([1, 2, 3, 4])).toBe('1-4')
    expect(compressWeeks([1, 2, 3, 8])).toBe('1-3, 8')
    expect(compressWeeks([10, 12, 14])).toBe('10, 12, 14')
    expect(compressWeeks([18])).toBe('18')
  })

  it('keeps studentCount 0 as 0 and only null as missing', () => {
    expect(studentCountLabel(0)).toBe('0')
    expect(studentCountLabel(40)).toBe('40')
    expect(studentCountLabel(null)).toBe(MISSING)
  })

  it('renders the syllabus entry per B-02 locator rules', () => {
    expect(syllabusEntryLabel(null, 'MISSING')).toBe('大纲缺失')
    expect(syllabusEntryLabel(null, 'AVAILABLE')).toBe('大纲可用（未提供入口）')
    expect(syllabusEntryLabel('https://demo.example.org/s.pdf', 'AVAILABLE')).toBe('查看课程大纲')
    expect(syllabusEntryLabel('demo:syllabus-001', 'AVAILABLE')).toBe('查看课程大纲')
    expect(isHttpLocator('https://demo.example.org/s.pdf')).toBe(true)
    expect(isHttpLocator('demo:syllabus-001')).toBe(false)
    expect(isHttpLocator(null)).toBe(false)
  })

  it('never invents course codes or owner colleges', () => {
    expect(displayText(null)).toBe(MISSING)
    expect(displayText('  ')).toBe(MISSING)
    expect(displayText('软件学院')).toBe('软件学院')
  })

  it('maps activity types, syllabus status and sections', () => {
    expect(activityTypeLabel('UNDERGRAD_COURSE')).toBe('本科课程')
    expect(activityTypeLabel('PRACTICE')).toBe('实践环节')
    expect(sectionLabel(3, 3)).toBe('第3节')
    expect(sectionLabel(1, 2)).toBe('第1-2节')
    expect(syllabusLabel('AVAILABLE')).toBe('大纲可用')
    expect(syllabusLabel('MISSING')).toBe('大纲缺失')
  })

  it('keeps ONLINE and TBD as normal schedule locations', () => {
    const physical = { locationId: 1, displayName: '测试楼A101', locationGroup: '测试楼A', locationType: 'PHYSICAL' } as LocationDetail
    const online = { locationId: 2, displayName: '线上', locationGroup: '线上', locationType: 'ONLINE' } as LocationDetail
    const tbd = { locationId: 3, displayName: '待定', locationGroup: '其它', locationType: 'TBD' } as LocationDetail
    expect(locationText(physical)).toBe('测试楼A101')
    expect(locationText(online)).toBe('线上（线上）')
    expect(locationText(tbd)).toBe('待定（待定）')
    expect(locationTypeTag('PHYSICAL')).toBeNull()
  })
})
