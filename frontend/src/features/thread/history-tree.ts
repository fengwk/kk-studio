import { markdownPreview, type PreviewCodeLine } from '@/features/thread/markdown-preview'
import { asRecord, getRecordList } from '@/features/thread/runtime/thread-timeline/payload-json'
import { getString } from '@/shared/lib/value'
import { FORK_NOTICE_TEXT } from '@/features/thread/runtime/thread-timeline/entry-event-projection'
import { translate } from '@/shared/i18n'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

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
  /** 折叠空白后的完整可见文本；只在命中超出 preview 上限时从这里截取搜索预览。 */
  text: string
  /** `text` 的小写形式，用于搜索匹配。 */
  searchText: string
  /** 单行全文里仍可定位的代码行范围。 */
  codeLines: PreviewCodeLine[]
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

/** 预览截断时保留的字符数：最后一位换成省略号。 */
const PROJECTED_PREVIEW_KEPT = PROJECTED_PREVIEW_LIMIT - 1

/** 从全文截取命中窗口时，命中前保留的上下文字数；窄行仍给长命中词留出位置。 */
const HIT_LEAD = 6

/** 以空格分词的拉丁词字符；中文等不以空格分词的文字按字截取即可。 */
const LATIN_WORD_CHAR = /[\p{Script=Latin}\p{N}]/u

export interface BuildHistoryTreeOptions {
  /** 当前 head Entry id；用于标记路径与当前位置，不改变图形结构。 */
  headEntryId?: string | null
  mermaidLabel?: string
  noBodyLabel?: string
}

/** 按父 Entry 分组的子节点索引；未知或缺失的父引用归到根组。 */
function childrenIndex(entries: HarnessSessionEntryDTO[]): Map<string | null, HarnessSessionEntryDTO[]> {
  const knownIds = new Set(entries.map((entry) => entry.entryId))
  const childrenByParent = new Map<string | null, HarnessSessionEntryDTO[]>()
  for (const entry of entries) {
    const parentId =
      entry.parentEntryId != null && knownIds.has(entry.parentEntryId)
        ? entry.parentEntryId
        : null
    const siblings = childrenByParent.get(parentId) ?? []
    siblings.push(entry)
    childrenByParent.set(parentId, siblings)
  }
  return childrenByParent
}

/** 为待绘制的 sibling 占用第一个空闲列，没有空列则追加。 */
function allocateLane(active: Array<string | null>, entryId: string): number {
  const free = active.indexOf(null)
  if (free >= 0) {
    active[free] = entryId
    return free
  }
  active.push(entryId)
  return active.length - 1
}

/** 当前 Entry 已有待绘制列时复用它，否则分配新列。 */
function laneOf(active: Array<string | null>, entryId: string): number {
  const existing = active.indexOf(entryId)
  return existing >= 0 ? existing : allocateLane(active, entryId)
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
  const childrenByParent = childrenIndex(entries)
  const ancestry = new Set(activeAncestry(entries, options.headEntryId ?? null))
  const headEntryId = options.headEntryId ?? null
  const mermaidLabel = options.mermaidLabel ?? translate('shared.mermaidDiagram')
  const noBodyLabel = options.noBodyLabel ?? translate('ai.chat.history.noBody')
  const rows: HistoryTreeRow[] = []
  const laneById = new Map<string, number>()
  /** lane -> 该列下一个待绘制的 Entry id；null 表示该列当前空闲。 */
  const active: Array<string | null> = []
  const walked = new Set<string>()

  function visit(entry: HarnessSessionEntryDTO): void {
    if (walked.has(entry.entryId)) {
      return
    }
    walked.add(entry.entryId)
    const lane = laneOf(active, entry.entryId)
    laneById.set(entry.entryId, lane)
    // 本行已绘制：释放该列（若它有子节点，第一个子节点会立刻重新占用同一列）。
    active[lane] = null
    const children = childrenByParent.get(entry.entryId) ?? []
    if (children.length > 0) {
      active[lane] = children[0]!.entryId
      for (let index = 1; index < children.length; index += 1) {
        allocateLane(active, children[index]!.entryId)
      }
    }
    const parentId = entry.parentEntryId
    const codeLines: PreviewCodeLine[] = []
    const text = markdownPreview(historyEntryVisibleText(entry), { codeLines, mermaidLabel })
    rows.push({
      entry,
      kind: historyEntryKind(entry),
      preview: text ? truncatePreview(text, PROJECTED_PREVIEW_LIMIT) : noBodyLabel,
      text,
      searchText: text.toLowerCase(),
      codeLines,
      lane,
      gutter: active.map((pending) => pending != null),
      startsLane: parentId != null && laneById.get(parentId) !== lane,
      isFork: children.length > 1,
      canFork: isForkBoundary(entry),
      onActivePath: ancestry.has(entry.entryId),
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

/**
 * 搜索时的行预览：首个命中完整落在预览保留的字符内时沿用普通预览，由行内布局保证命中优先显示；
 * 命中超出或跨过预览末位时才从全文命中处前少量上下文开始截取（以 … 开头并按预览上限截断），
 * 用户也能看到这一行为什么匹配。没有命中同样沿用普通预览。
 */
export function historyRowSearchPreview(row: HistoryTreeRow, tokens: string[]): string {
  if (row.text.length <= PROJECTED_PREVIEW_LIMIT) {
    return row.preview
  }
  let hitStart = Infinity
  let hitEnd = 0
  for (const token of tokens) {
    if (token.length === 0) {
      continue
    }
    const index = row.searchText.indexOf(token)
    if (index >= 0 && index < hitStart) {
      hitStart = index
      hitEnd = index + token.length
    }
  }
  if (hitEnd <= PROJECTED_PREVIEW_KEPT) {
    return row.preview
  }
  const codeLine = row.codeLines.find(({ start, end }) => start <= hitStart && hitStart < end)
  return windowAroundHit(row.text, hitStart, codeLine?.start ?? 0)
}

/**
 * 从全文命中处前少量上下文开始截取：起点落在拉丁词中间时从该词之后的空格开始，不截半个词；
 * 不从代理对中间切开；结果以 … 开头并按预览上限截断。
 */
function windowAroundHit(text: string, hitIndex: number, lineStart: number): string {
  let start = Math.max(lineStart, hitIndex - HIT_LEAD)
  if (/[\uDC00-\uDFFF]/.test(text[start] ?? '')) {
    start -= 1
  } else if (LATIN_WORD_CHAR.test(text[start - 1] ?? '') && LATIN_WORD_CHAR.test(text[start] ?? '')) {
    const space = text.indexOf(' ', start)
    if (space >= 0 && space < hitIndex) {
      start = space + 1
    }
  }
  return truncatePreview(`…${text.slice(start).trimStart()}`, PROJECTED_PREVIEW_LIMIT)
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
  const role = getString(asRecord(asRecord(entry.payload).message).role)
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

function messageContents(entry: HarnessSessionEntryDTO): Record<string, unknown>[] {
  return getRecordList(asRecord(asRecord(entry.payload).message).contents)
}

/** 只收集可见正文；显式 thinking 内容不进入历史树。 */
function historyEntryVisibleText(entry: HarnessSessionEntryDTO): string {
  if (entry.entryType === 'FORK') {
    return FORK_NOTICE_TEXT
  }
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

function truncatePreview(text: string, limit: number): string {
  if (limit <= 0 || text.length <= limit) {
    return text
  }
  return `${text.slice(0, Math.max(0, limit - 1))}…`
}
