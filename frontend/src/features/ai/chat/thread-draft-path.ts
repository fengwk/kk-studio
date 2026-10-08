import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'

/** A draft must have one closed Session path. Never render or replay a partial prefix. */
export function threadDraftPath(
  entries: HarnessSessionEntryDTO[],
  sessionId: string,
  startEntryId: string,
): HarnessSessionEntryDTO[] {
  const byId = new Map(entries.map((entry) => [entry.entryId, entry]))
  const path: HarnessSessionEntryDTO[] = []
  const visited = new Set<string>()
  let cursor: string | null = startEntryId
  while (cursor != null) {
    if (visited.has(cursor)) {
      throw new Error('Cyclic thread draft path')
    }
    visited.add(cursor)
    const entry = byId.get(cursor)
    if (entry == null || entry.sessionId !== sessionId) {
      throw new Error('Incomplete thread draft path')
    }
    path.push(entry)
    cursor = entry.parentEntryId
  }
  if (path.at(-1)?.entryType !== 'ROOT') {
    throw new Error('Thread draft path must end at ROOT')
  }
  return path.reverse()
}
