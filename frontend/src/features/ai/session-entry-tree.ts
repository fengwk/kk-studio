import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/payload-json'

export type SessionTreeFilter = 'conversation' | 'all'

export type SessionEntryKind = 'user' | 'assistant' | 'tool' | 'custom' | 'other'

export interface SessionTreeEntry {
  entry: HarnessSessionEntryDTO
  kind: SessionEntryKind
  /** Visual indentation levels. Linear chains remain at the same depth. */
  depth: number
  preview: string
  /** True when this visible Entry has more than one visible child. */
  isBranchPoint: boolean
  /** True when this row is a child of a visible sibling split. */
  hasBranchConnector: boolean
  /** True when this row is the last visible sibling at its projected parent. */
  isLastSibling: boolean
  /** Vertical branch gutters for each indentation level before this row's connector. */
  ancestorConnectors: SessionTreeConnector[]
  /** The nearest visible ancestor after applying filter and search. */
  parentId: string | null
}

export interface SessionTreeConnector {
  /** True when the branch line continues below this row at the indentation level. */
  continues: boolean
}

export interface BranchTarget {
  /** Target head Entry for `PUT /threads/{id}/head`; null when the Entry is not selectable. */
  headEntryId: string | null
  draft: string
}

/** Upper bound for the projected preview rendered in each tree row. */
export const PROJECTED_PREVIEW_LIMIT = 220

/**
 * Builds the compact, filtered visual projection of the immutable Session Entry Tree.
 *
 * Visibility never changes the source tree. Instead, every displayed Entry is attached to its
 * nearest displayed ancestor. This lets filters and searches omit bookkeeping or intermediate
 * Entries without retaining their original indentation. Sibling order is inherited directly from
 * the server response; no timestamp or identifier ordering is inferred on the client.
 */
export function buildSessionEntryTree(
  entries: HarnessSessionEntryDTO[],
  filter: SessionTreeFilter,
  searchTokens: string[] = [],
): SessionTreeEntry[] {
  const entriesById = new Map(entries.map((entry) => [entry.entryId, entry]))
  const visibleEntries = entries.filter((entry) => {
    const kind = sessionEntryKind(entry)
    return matchesSessionTreeFilter(kind, filter) && matchesSessionTreeEntrySearch(entry, searchTokens)
  })
  const visibleIds = new Set(visibleEntries.map((entry) => entry.entryId))
  const visibleParentById = new Map<string, string | null>()
  const visibleChildrenByParent = new Map<string | null, HarnessSessionEntryDTO[]>()

  for (const entry of visibleEntries) {
    const parentId = nearestVisibleParent(entry, entriesById, visibleIds)
    visibleParentById.set(entry.entryId, parentId)
    const siblings = visibleChildrenByParent.get(parentId) ?? []
    siblings.push(entry)
    visibleChildrenByParent.set(parentId, siblings)
  }

  const result: SessionTreeEntry[] = []
  const walked = new Set<string>()
  const roots = visibleChildrenByParent.get(null) ?? []
  const rootBranch = roots.length > 1

  for (let index = 0; index < roots.length; index += 1) {
    visit(
      roots[index]!,
      rootBranch ? 1 : 0,
      rootBranch,
      index === roots.length - 1,
      [],
      rootBranch,
    )
  }

  // Malformed parent references or cycles must not make an otherwise selectable Entry disappear.
  // Such rows become independent roots while the visited guard prevents recursive cycles.
  for (const entry of visibleEntries) {
    if (!walked.has(entry.entryId)) {
      visit(entry, 0, false, true, [], false)
    }
  }

  return result

  function visit(
    entry: HarnessSessionEntryDTO,
    depth: number,
    hasBranchConnector: boolean,
    isLastSibling: boolean,
    inheritedConnectors: SessionTreeConnector[],
    enteredBranch: boolean,
  ): void {
    if (walked.has(entry.entryId)) {
      return
    }
    walked.add(entry.entryId)

    const children = visibleChildrenByParent.get(entry.entryId) ?? []
    const isBranchPoint = children.length > 1
    const ancestorConnectors = connectorsAtDepth(inheritedConnectors, depth)
    result.push({
      entry,
      kind: sessionEntryKind(entry),
      depth,
      preview: sessionEntryPreview(entry),
      isBranchPoint,
      hasBranchConnector,
      isLastSibling,
      ancestorConnectors,
      parentId: visibleParentById.get(entry.entryId) ?? null,
    })

    const childConnectors = ancestorConnectors.map((connector) => ({ ...connector }))
    if (hasBranchConnector && depth > 0) {
      childConnectors[depth - 1] = { continues: !isLastSibling }
    }

    // A branch introduces one visual level. Its first linear response also keeps one additional
    // level so a branch's continuation reads as a subtree instead of collapsing into the sibling.
    const childDepth = isBranchPoint
      ? depth + 1
      : enteredBranch && depth > 0
        ? depth + 1
        : depth
    for (let index = 0; index < children.length; index += 1) {
      visit(
        children[index]!,
        childDepth,
        isBranchPoint,
        index === children.length - 1,
        childConnectors,
        isBranchPoint,
      )
    }
  }
}

function nearestVisibleParent(
  entry: HarnessSessionEntryDTO,
  entriesById: Map<string, HarnessSessionEntryDTO>,
  visibleIds: Set<string>,
): string | null {
  const visited = new Set<string>([entry.entryId])
  let cursor = entry.parentEntryId ?? null
  while (cursor) {
    if (visibleIds.has(cursor)) {
      return cursor
    }
    if (visited.has(cursor)) {
      return null
    }
    visited.add(cursor)
    cursor = entriesById.get(cursor)?.parentEntryId ?? null
  }
  return null
}

function connectorsAtDepth(connectors: SessionTreeConnector[], depth: number): SessionTreeConnector[] {
  const result = connectors.map((connector) => ({ ...connector }))
  while (result.length < depth) {
    result.push({ continues: false })
  }
  return result
}

function matchesSessionTreeEntrySearch(
  entry: HarnessSessionEntryDTO,
  tokens: string[],
): boolean {
  if (tokens.length === 0) {
    return true
  }
  const haystack = sessionEntrySearchText(entry)
  return tokens.every((token) => haystack.includes(token))
}

export function sessionEntryKind(entry: HarnessSessionEntryDTO): SessionEntryKind {
  const entryType = entry.entryType
  if (entryType === 'CUSTOM_MESSAGE') {
    return 'custom'
  }
  if (entryType !== 'MESSAGE') {
    return 'other'
  }
  const role = getString(asRecord(parsePayload(entry.payloadJson).message).role)
  if (role === 'USER') {
    return 'user'
  }
  if (role === 'ASSISTANT') {
    return 'assistant'
  }
  if (role === 'TOOL') {
    return 'tool'
  }
  return 'other'
}

export function matchesSessionTreeFilter(kind: SessionEntryKind, filter: SessionTreeFilter): boolean {
  switch (filter) {
    case 'conversation':
      return kind === 'user' || kind === 'assistant' || kind === 'custom'
    case 'all':
      return true
  }
}

/**
 * USER/CUSTOM entries rewind the head to their parent and restore their editable source text;
 * every other Entry becomes the head itself.
 */
export function branchTarget(entry: HarnessSessionEntryDTO): BranchTarget {
  const kind = sessionEntryKind(entry)
  if (kind === 'user' || kind === 'custom') {
    return { headEntryId: entry.parentEntryId ?? null, draft: sessionEntryText(entry) }
  }
  return { headEntryId: entry.entryId, draft: '' }
}

/** Full text body for branching drafts. This intentionally keeps thinking blocks: USER/CUSTOM
 * drafts must retain every editable text block, while previews use sessionEntryVisibleText(). */
export function sessionEntryText(entry: HarnessSessionEntryDTO): string {
  return collectMessageText(messageContents(entry), true).join('\n')
}

/** Single-line preview used by the tree row. Explicit `thinking` blocks are excluded. */
export function sessionEntryPreview(entry: HarnessSessionEntryDTO, limit: number = PROJECTED_PREVIEW_LIMIT): string {
  const flat = flattenVisibleText(sessionEntryVisibleText(entry))
  return flat ? truncatePreview(flat, limit) : '无正文'
}

/** Lower-cased visible text used for keyword matching. Preview limits never apply to search. */
export function sessionEntrySearchText(entry: HarnessSessionEntryDTO): string {
  return flattenVisibleText(sessionEntryVisibleText(entry)).toLowerCase()
}

function messageContents(entry: HarnessSessionEntryDTO): Record<string, unknown>[] {
  return getRecordList(asRecord(parsePayload(entry.payloadJson).message).contents)
}

function sessionEntryVisibleText(entry: HarnessSessionEntryDTO): string {
  return collectMessageText(messageContents(entry), false).join('\n')
}

function collectMessageText(contents: Record<string, unknown>[], includeThinking: boolean): string[] {
  const result: string[] = []
  for (const content of contents) {
    const type = getString(content.type)
    if (type === 'tool_result') {
      result.push(...collectMessageText(getRecordList(content.contents), includeThinking))
      continue
    }
    if (type === 'thinking') {
      if (includeThinking) {
        const text = getString(content.text)
        if (text) {
          result.push(text)
        }
      }
      continue
    }
    if (type === 'text') {
      const text = getString(content.text)
      if (text) {
        result.push(text)
      }
    }
  }
  return result
}

function flattenVisibleText(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

function truncatePreview(text: string, limit: number): string {
  if (limit <= 0 || text.length <= limit) {
    return text
  }
  return `${text.slice(0, Math.max(0, limit - 1))}…`
}

/** Returns the chain of Entry IDs from target to root using raw parent links. */
export function activeAncestry(
  entries: HarnessSessionEntryDTO[],
  targetEntryId: string | null,
): string[] {
  if (!targetEntryId) {
    return []
  }
  const byId = new Map(entries.map((entry) => [entry.entryId, entry]))
  const chain: string[] = []
  const visited = new Set<string>()
  let cursor: string | null = targetEntryId
  while (cursor && !visited.has(cursor)) {
    visited.add(cursor)
    chain.push(cursor)
    cursor = byId.get(cursor)?.parentEntryId ?? null
  }
  return chain
}

export function isOnActivePath(ancestry: string[], entryId: string): boolean {
  return ancestry.includes(entryId)
}

/** Resolves a target selection against visible rows. Hidden targets walk to their closest visible
 * raw ancestor; if no ancestor is visible, the last visible row is used. */
export function resolveSelection(
  rows: SessionTreeEntry[],
  entries: HarnessSessionEntryDTO[],
  targetEntryId: string | null,
): HarnessSessionEntryDTO | null {
  if (rows.length === 0) {
    return null
  }
  const visibleById = new Map(rows.map((row) => [row.entry.entryId, row.entry]))
  if (targetEntryId && visibleById.has(targetEntryId)) {
    return visibleById.get(targetEntryId)!
  }
  const byId = new Map(entries.map((entry) => [entry.entryId, entry]))
  const visited = new Set<string>()
  let cursor = targetEntryId
  while (cursor && !visited.has(cursor)) {
    visited.add(cursor)
    const visible = visibleById.get(cursor)
    if (visible) {
      return visible
    }
    cursor = byId.get(cursor)?.parentEntryId ?? null
  }
  return rows[rows.length - 1]!.entry
}

/** Splits the raw search query into lower-cased whitespace tokens for AND-style matching. */
export function parseHistorySearchTokens(query: string): string[] {
  const normalized = query.trim().toLowerCase()
  return normalized
    .split(/\s+/)
    .filter((token) => token.length > 0)
}