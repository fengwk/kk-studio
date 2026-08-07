import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/runtime/payload-json'
import { translate } from '@/shared/i18n'

export type SessionTreeFilter = 'conversation' | 'all'

export type SessionEntryKind = 'user' | 'assistant' | 'tool' | 'custom' | 'other'

export interface SessionTreeEntry {
  entry: HarnessSessionEntryDTO
  kind: SessionEntryKind
  /** 视觉缩进层级。线性链保持相同深度。 */
  depth: number
  preview: string
  /** 当该可见 Entry 有超过一个可见子节点时为真。 */
  isBranchPoint: boolean
  /** 当该行是某个可见兄弟 split 的子节点时为真。 */
  hasBranchConnector: boolean
  /** 当该行是其投影父节点下的最后一个可见兄弟时为真。 */
  isLastSibling: boolean
  /** 该行 connector 之前每一缩进层级的纵向 branch gutter。 */
  ancestorConnectors: SessionTreeConnector[]
  /** 应用 filter 和 search 之后的最近可见祖先。 */
  parentId: string | null
}

interface SessionTreeConnector {
  /** 当 branch 线在该缩进层级下方继续延伸时为真。 */
  continues: boolean
}

export interface BranchTarget {
  /** `PUT /threads/{id}/head` 的目标 head Entry；当 Entry 不可选中时为 null。 */
  headEntryId: string | null
  draft: string
}

/** 投影预览文本在每个 tree row 渲染时的最大长度。 */
const PROJECTED_PREVIEW_LIMIT = 220

/**
 * 构建不可变 Session Entry Tree 的紧凑、过滤后视觉投影。
 *
 * 可见性永远不会改变源 tree。每个被展示的 Entry 都会挂到最近的可见祖先下，
 * 这样 filter 和 search 可以省略内部/中间 Entry 而不保留其原始缩进。
 * 兄弟顺序直接继承自服务端响应；不会在客户端基于时间戳或 id 推断顺序。
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

  // 错误的 parent 引用或环不能使原本可选中的 Entry 消失。
  // 这些行会变成独立的根，visited 守卫防止递归循环。
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

    // branch 会引入一层视觉缩进；其首个线性响应也额外保留一层，使 branch 的延续
    // 看起来像子树，而不是折叠为兄弟。
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

function sessionEntryKind(entry: HarnessSessionEntryDTO): SessionEntryKind {
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

function matchesSessionTreeFilter(kind: SessionEntryKind, filter: SessionTreeFilter): boolean {
  switch (filter) {
    case 'conversation':
      return kind === 'user' || kind === 'assistant' || kind === 'custom'
    case 'all':
      return true
  }
}

/**
 * USER/CUSTOM entry 会将 head 回退到其父节点，并恢复可编辑的原文；其他 Entry 自身就是 head。
 */
export function branchTarget(entry: HarnessSessionEntryDTO): BranchTarget {
  if (entry.entryType === 'TURN_START' || entry.entryType === 'TURN_END') {
    // 控制边界不是 branch 目标；branching 会落到其父节点。
    return { headEntryId: entry.parentEntryId ?? null, draft: '' }
  }
  const kind = sessionEntryKind(entry)
  if (kind === 'user' || kind === 'custom') {
    return { headEntryId: entry.parentEntryId ?? null, draft: sessionEntryText(entry) }
  }
  return { headEntryId: entry.entryId, draft: '' }
}

/** branching draft 的完整文本主体。这里有意保留 thinking block：USER/CUSTOM
 * draft 必须保留所有可编辑文本 block，而 preview 使用 sessionEntryVisibleText()。 */
function sessionEntryText(entry: HarnessSessionEntryDTO): string {
  return collectMessageText(messageContents(entry), true).join('\n')
}

/** tree row 使用的单行 preview。显式的 `thinking` block 会被排除。 */
function sessionEntryPreview(entry: HarnessSessionEntryDTO, limit: number = PROJECTED_PREVIEW_LIMIT): string {
  const flat = flattenVisibleText(sessionEntryVisibleText(entry))
  return flat ? truncatePreview(flat, limit) : translate('ai.chat.history.noBody')
}

/** 用于关键词匹配的小写可见文本。search 不受 preview 长度限制。 */
function sessionEntrySearchText(entry: HarnessSessionEntryDTO): string {
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

/** 返回从目标 entry 沿原始 parent 链追溯到 root 的 Entry id 链。 */
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

/** 根据可见 row 解析目标 selection。隐藏目标回溯到最近的可见原始祖先；
 * 如果没有祖先可见，则使用最后一行可见 row。 */
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

/** 将原始 search query 按空白拆分为小写 token，用于 AND 风格匹配。 */
export function parseHistorySearchTokens(query: string): string[] {
  const normalized = query.trim().toLowerCase()
  return normalized
    .split(/\s+/)
    .filter((token) => token.length > 0)
}