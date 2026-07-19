import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/thread-event-payload'

export type SessionTreeFilter = 'default' | 'no-tools' | 'user-only' | 'assistant-only' | 'labeled-only' | 'all'

export type SessionEntryKind = 'user' | 'assistant' | 'tool' | 'custom' | 'label' | 'other'

export interface SessionTreeEntry {
  entry: HarnessSessionEntryDTO
  kind: SessionEntryKind
  depth: number
  preview: string
}

export interface BranchTarget {
  fromEntryId: string | null
  draft: string
}

/**
 * Builds a display projection from the complete Session Entry Tree. The server controls Entry
 * order; children retain the order supplied by the Session query rather than being time-sorted.
 */
export function buildSessionEntryTree(
  entries: HarnessSessionEntryDTO[],
  filter: SessionTreeFilter,
): SessionTreeEntry[] {
  const children = new Map<string | null, HarnessSessionEntryDTO[]>()
  for (const entry of entries) {
    const siblings = children.get(entry.parentEntryId) ?? []
    siblings.push(entry)
    children.set(entry.parentEntryId, siblings)
  }
  const result: SessionTreeEntry[] = []
  const visit = (parentEntryId: string | null, depth: number) => {
    for (const entry of children.get(parentEntryId) ?? []) {
      const kind = sessionEntryKind(entry)
      if (matchesSessionTreeFilter(kind, filter)) {
        result.push({ entry, kind, depth, preview: sessionEntryPreview(entry, kind) })
      }
      visit(entry.entryId, depth + 1)
    }
  }
  visit(null, 0)
  return result
}

export function sessionEntryKind(entry: HarnessSessionEntryDTO): SessionEntryKind {
  const entryType = entry.entryType.toLowerCase()
  if (entryType === 'label') {
    return 'label'
  }
  if (entryType === 'custom_message') {
    return 'custom'
  }
  if (entryType !== 'message') {
    return 'other'
  }
  const role = getString(asRecord(parsePayload(entry.payloadJson).message).role).toLowerCase()
  if (role === 'user') {
    return 'user'
  }
  if (role === 'assistant') {
    return 'assistant'
  }
  if (role === 'tool') {
    return 'tool'
  }
  return 'other'
}

export function matchesSessionTreeFilter(kind: SessionEntryKind, filter: SessionTreeFilter): boolean {
  switch (filter) {
    case 'default':
      return kind === 'user' || kind === 'assistant' || kind === 'custom'
    case 'no-tools':
      return kind !== 'tool'
    case 'user-only':
      return kind === 'user' || kind === 'custom'
    case 'assistant-only':
      return kind === 'assistant'
    case 'labeled-only':
      return kind === 'label'
    case 'all':
      return true
  }
}

/** USER/CUSTOM branches re-submit editable source text from their parent; all others branch here. */
export function branchTarget(entry: HarnessSessionEntryDTO): BranchTarget {
  const kind = sessionEntryKind(entry)
  if (kind === 'user' || kind === 'custom') {
    return { fromEntryId: entry.parentEntryId, draft: sessionEntryText(entry) }
  }
  return { fromEntryId: entry.entryId, draft: '' }
}

function sessionEntryPreview(entry: HarnessSessionEntryDTO, kind: SessionEntryKind): string {
  if (kind === 'label') {
    return getString(parsePayload(entry.payloadJson).label) || 'Label'
  }
  const text = sessionEntryText(entry)
  return text || entry.entryType
}

function sessionEntryText(entry: HarnessSessionEntryDTO): string {
  const payload = parsePayload(entry.payloadJson)
  const message = asRecord(payload.message)
  return getRecordList(message.contents)
    .map((content) => getString(content.text))
    .filter(Boolean)
    .join('\n')
}
