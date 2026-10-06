import type { HarnessSessionEntryDTO } from '@/shared/api/contracts/ai-runtime'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/runtime/payload-json'
import { translate } from '@/shared/i18n'

/**
 * Entry 行的语义分类。它只服务历史树的图标与无障碍标签，不携带执行状态，
 * 也不把 TURN_START/TURN_END 之外的 Entry 重组成虚假的 Turn 结构。
 */
export type HistoryEntryKind =
  | 'root'
  | 'user'
  | 'assistant'
  | 'tool'
  | 'custom'
  | 'notification'
  | 'other'

/**
 * 历史树的一行：一个真实 Entry。图形只表达真实父子关系。
 *
 * - `lane` 是该 Entry 的列；线性链保持同一列，只有真实 sibling 分叉才占用新列；
 * - `gutter` 是这一行下方需要继续画竖线的列（同一份 lane 坐标）；
 * - `startsLane`/`isFork` 描述该行与前后的连接形状，不含目录式深度缩进；
 * - `canFork` 只由 Entry 类型决定（ROOT 或已关闭 TURN_END），与执行状态无关。
 */
export interface HistoryTreeRow {
  entry: HarnessSessionEntryDTO
  kind: HistoryEntryKind
  /** 单行预览：折叠空白并按上限截断；没有可见文本时为占位文案。 */
  preview: string
  /** 小写可见文本，用于搜索匹配；不受 preview 长度限制。 */
  searchText: string
  lane: number
  gutter: boolean[]
  startsLane: boolean
  isFork: boolean
  canFork: boolean
  onActivePath: boolean
  isHead: boolean
}

/** 预览文本在每一行渲染时的最大长度。 */
const PROJECTED_PREVIEW_LIMIT = 220

export interface BuildHistoryTreeOptions {
  /** 当前 head Entry id；用于标记路径与当前位置，不改变图形结构。 */
  headEntryId?: string | null
}

/**
 * 把 Session 的 Entry 列表投影为历史树行。
 *
 * 顺序完全继承服务端响应：先按 server 顺序遍历根，再按 server 顺序遍历子节点；
 * 客户端不基于时间戳或 id 推断顺序，也不隐藏任何 Entry —— 搜索只影响高亮。
 */
export function buildHistoryTree(
  entries: HarnessSessionEntryDTO[],
  options: BuildHistoryTreeOptions = {},
): HistoryTreeRow[] {
  const childrenByParent = new Map<string | null, HarnessSessionEntryDTO[]>()
  const knownIds = new Set(entries.map((entry) => entry.entryId))
  for (const entry of entries) {
    const parentId =
      entry.parentEntryId != null && knownIds.has(entry.parentEntryId)
        ? entry.parentEntryId
        : null
    const siblings = childrenByParent.get(parentId) ?? []
    siblings.push(entry)
    childrenByParent.set(parentId, siblings)
  }

  const ancestry = activeAncestry(entries, options.headEntryId ?? null)
  const headEntryId = options.headEntryId ?? null
  const rows: HistoryTreeRow[] = []
  const laneById = new Map<string, number>()
  /** lane -> 该列下一个待绘制的 Entry id；null 表示该列当前空闲。 */
  const active: Array<string | null> = []
  const walked = new Set<string>()

  function allocateLane(entryId: string): number {
    const free = active.indexOf(null)
    if (free >= 0) {
      active[free] = entryId
      return free
    }
    active.push(entryId)
    return active.length - 1
  }

  function laneOf(entryId: string): number {
    const existing = active.indexOf(entryId)
    return existing >= 0 ? existing : allocateLane(entryId)
  }

  function visit(entry: HarnessSessionEntryDTO): void {
    if (walked.has(entry.entryId)) {
      return
    }
    walked.add(entry.entryId)
    const lane = laneOf(entry.entryId)
    laneById.set(entry.entryId, lane)
    // 本行已绘制：释放该列（若它有子节点，第一个子节点会立刻重新占用同一列）。
    active[lane] = null
    const children = childrenByParent.get(entry.entryId) ?? []
    if (children.length > 0) {
      active[lane] = children[0]!.entryId
      for (let index = 1; index < children.length; index += 1) {
        allocateLane(children[index]!.entryId)
      }
    }
    const parentId = entry.parentEntryId
    rows.push({
      entry,
      kind: historyEntryKind(entry),
      preview: historyEntryPreview(entry),
      searchText: historyEntrySearchText(entry),
      lane,
      gutter: active.map((pending) => pending != null),
      startsLane: parentId != null && laneById.get(parentId) !== lane,
      isFork: children.length > 1,
      canFork: isForkBoundary(entry),
      onActivePath: ancestry.includes(entry.entryId),
      isHead: entry.entryId === headEntryId,
    })
    for (const child of children) {
      visit(child)
    }
  }

  for (const root of childrenByParent.get(null) ?? []) {
    visit(root)
  }
  // 坏的 parent 引用或环不能使原本要展示的 Entry 消失；visited 守卫防止递归循环。
  for (const entry of entries) {
    if (!walked.has(entry.entryId)) {
      visit(entry)
    }
  }
  return rows
}

/** 只有 ROOT 与已关闭 TURN_END 是可用的手工分叉点；其它 Entry 只能查看。 */
export function isForkBoundary(entry: HarnessSessionEntryDTO): boolean {
  return entry.entryType === 'ROOT' || entry.entryType === 'TURN_END'
}

/** 从目标 Entry 沿原始 parent 链回溯到根的 Entry id 链。 */
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

/** 原始搜索 query 按空白拆分为小写 token，AND 语义。 */
export function historySearchTokens(query: string): string[] {
  const normalized = query.trim().toLowerCase()
  return normalized.split(/\s+/).filter((token) => token.length > 0)
}

/** token 是否命中该行可见文本；未命中不会改变结构，只取消高亮。 */
export function historyRowMatches(row: HistoryTreeRow, tokens: string[]): boolean {
  return tokens.length > 0 && tokens.every((token) => row.searchText.includes(token))
}

export interface HighlightSegment {
  text: string
  match: boolean
}

/** 把一行文本切分为命中/未命中片段；多个 token 的区间合并后从左到右输出。 */
export function highlightSegments(text: string, tokens: string[]): HighlightSegment[] {
  if (tokens.length === 0 || text.length === 0) {
    return text.length === 0 ? [] : [{ text, match: false }]
  }
  const haystack = text.toLowerCase()
  const ranges: Array<[number, number]> = []
  for (const token of tokens) {
    if (token.length === 0) {
      continue
    }
    let from = 0
    for (;;) {
      const index = haystack.indexOf(token, from)
      if (index < 0) {
        break
      }
      ranges.push([index, index + token.length])
      from = index + token.length
    }
  }
  if (ranges.length === 0) {
    return [{ text, match: false }]
  }
  ranges.sort((left, right) => left[0] - right[0])
  const merged: Array<[number, number]> = []
  for (const [start, end] of ranges) {
    const last = merged[merged.length - 1]
    if (last != null && start <= last[1]) {
      last[1] = Math.max(last[1], end)
    } else {
      merged.push([start, end])
    }
  }
  const segments: HighlightSegment[] = []
  let cursor = 0
  for (const [start, end] of merged) {
    if (start > cursor) {
      segments.push({ text: text.slice(cursor, start), match: false })
    }
    segments.push({ text: text.slice(start, end), match: true })
    cursor = end
  }
  if (cursor < text.length) {
    segments.push({ text: text.slice(cursor), match: false })
  }
  return segments
}

function historyEntryKind(entry: HarnessSessionEntryDTO): HistoryEntryKind {
  const entryType = entry.entryType
  if (entryType === 'ROOT') {
    return 'root'
  }
  if (entryType === 'NOTIFICATION') {
    return 'notification'
  }
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

/** 单行预览；显式 thinking 内容不进入历史树。 */
function historyEntryPreview(entry: HarnessSessionEntryDTO): string {
  const flat = flattenVisibleText(historyEntryVisibleText(entry))
  return flat ? truncatePreview(flat, PROJECTED_PREVIEW_LIMIT) : translate('ai.chat.history.noBody')
}

function historyEntrySearchText(entry: HarnessSessionEntryDTO): string {
  return flattenVisibleText(historyEntryVisibleText(entry)).toLowerCase()
}

function messageContents(entry: HarnessSessionEntryDTO): Record<string, unknown>[] {
  return getRecordList(asRecord(parsePayload(entry.payloadJson).message).contents)
}

function historyEntryVisibleText(entry: HarnessSessionEntryDTO): string {
  return collectMessageText(messageContents(entry)).join('\n')
}

function collectMessageText(contents: Record<string, unknown>[]): string[] {
  const result: string[] = []
  for (const content of contents) {
    const type = getString(content.type)
    if (type === 'tool_result') {
      result.push(...collectMessageText(getRecordList(content.contents)))
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
