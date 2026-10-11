import type { ActionSuggestionEvidence, GeneratedReportEvidenceRef } from '../api/types'

/**
 * S2-11 从「AI 行动建议」跳到「证据回看」需要把证据引用放进 URL。
 * 建议里的证据只有 itemType + itemId；解析时间时 resultId 并不参与，故填 0 占位。
 */

export function toEvidenceRefs(items: ActionSuggestionEvidence[]): GeneratedReportEvidenceRef[] {
  return items.map(item => ({ resultId: 0, itemType: item.itemType, itemId: item.itemId }))
}

/** 编码成 URL 参数，例如 "SEGMENT:seg-1,WINDOW:win-1"。 */
export function evidenceRefsParam(refs: GeneratedReportEvidenceRef[]): string {
  return refs.map(ref => `${ref.itemType}:${ref.itemId}`).join(',')
}

/**
 * 解析 URL 参数。非法项被忽略，而不是产生一条指向错误位置的引用——
 * 宁可少跳一条证据，也不要把教师带到错误的课堂时间。
 */
export function parseEvidenceRefs(value: unknown): GeneratedReportEvidenceRef[] {
  if (typeof value !== 'string' || value.trim() === '') return []
  const refs: GeneratedReportEvidenceRef[] = []
  for (const raw of value.split(',')) {
    const trimmed = raw.trim()
    if (trimmed === '') continue
    const separator = trimmed.indexOf(':')
    if (separator <= 0) continue
    const itemType = trimmed.slice(0, separator)
    const itemId = trimmed.slice(separator + 1)
    if (itemType !== 'SEGMENT' && itemType !== 'WINDOW') continue
    if (itemId === '') continue
    refs.push({ resultId: 0, itemType, itemId })
  }
  return refs
}
