import {
  isPaneTarget,
  normalizePaneTarget,
  samePaneTarget,
  type PaneTarget,
} from '@/features/chat/runtime/agent-pane/pane-target'

export type ChatLayout =
  | 'single'
  | 'split-2'
  | 'split-3'
  | 'grid-4'
  | 'grid-5'
  | 'grid-6'
  | 'grid-7'
  | 'grid-8'
  | 'grid-9'

export interface ChatPane {
  id: string
  /** 展示目标属于 tab 呈现状态；待确认请求等副作用资料必须留在各自记录中。 */
  target: PaneTarget
}

export interface ChatPaneState {
  layout: ChatLayout
  focusedPaneId: string
  panes: ChatPane[]
}

export interface ChatTabSnapshot {
  version: 1
  presentation: ChatPaneState
}

export interface ChatPaneLoadResult {
  state: ChatPaneState
  /** 本 tab 的 sessionStorage 恢复索引是否写入成功。 */
  saved: boolean
}

export type ChatTabStorageArea = Pick<Storage, 'getItem' | 'setItem'>

export interface ChatTabStorage {
  sessionStorage?: ChatTabStorageArea | null
  localStorage?: ChatTabStorageArea | null
}

export const CHAT_TAB_STATE_VERSION = 1 as const
export const CHAT_TAB_STATE_MAX_BYTES = 64 * 1024
export const CHAT_TAB_SESSION_PREFIX = 'kk-studio.tab-state.v1:'
export const CHAT_TAB_LAST_PREFIX = 'kk-studio.last-state.v1:'

export const CHAT_LAYOUT_CAPACITY: Record<ChatLayout, number> = {
  single: 1,
  'split-2': 2,
  'split-3': 3,
  'grid-4': 4,
  'grid-5': 5,
  'grid-6': 6,
  'grid-7': 7,
  'grid-8': 8,
  'grid-9': 9,
}

const CHAT_PANE_COUNT = 9

export function chatTabSessionStorageKey(chatId: string): string {
  return `${CHAT_TAB_SESSION_PREFIX}chat:${chatId}`
}

export function chatTabLastStorageKey(chatId: string): string {
  return `${CHAT_TAB_LAST_PREFIX}chat:${chatId}`
}

function createPaneId(index: number): string {
  return `pane-${index + 1}`
}

function defaultPaneTarget(): PaneTarget {
  return { kind: 'NEW_SESSION_DRAFT' }
}

function createPane(index: number, target: PaneTarget = defaultPaneTarget()): ChatPane {
  return { id: createPaneId(index), target }
}

export function createDefaultChatPaneState(layout: ChatLayout = 'single'): ChatPaneState {
  const panes = Array.from({ length: CHAT_PANE_COUNT }, (_, index) => createPane(index))
  return {
    layout,
    focusedPaneId: panes[0]?.id ?? 'pane-1',
    panes,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function hasExactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.length && keys.every((key) => expected.includes(key))
}

function isChatLayout(value: unknown): value is ChatLayout {
  return typeof value === 'string' && Object.hasOwn(CHAT_LAYOUT_CAPACITY, value)
}

export function parseLayout(value: unknown): ChatLayout {
  return isChatLayout(value) ? value : 'single'
}

function parsePane(value: unknown, index: number): ChatPane | null {
  if (
    !isRecord(value)
    || !hasExactKeys(value, ['id', 'target'])
    || value.id !== createPaneId(index)
    || !isPaneTarget(value.target)
  ) {
    return null
  }
  return createPane(index, normalizePaneTarget(value.target))
}

/** 严格解析版本化 Chat 呈现快照；任何未知字段、越界集合或坏目标都使整份快照失效。 */
export function parseChatPaneState(value: unknown): ChatPaneState | null {
  if (!isRecord(value) || !hasExactKeys(value, ['layout', 'focusedPaneId', 'panes'])) {
    return null
  }
  if (!isChatLayout(value.layout)) {
    return null
  }
  if (!Array.isArray(value.panes) || value.panes.length !== CHAT_PANE_COUNT) {
    return null
  }
  const panes: ChatPane[] = []
  for (const [index, rawPane] of value.panes.entries()) {
    const pane = parsePane(rawPane, index)
    if (pane == null) {
      return null
    }
    panes.push(pane)
  }
  const capacity = CHAT_LAYOUT_CAPACITY[value.layout]
  if (
    typeof value.focusedPaneId !== 'string'
    || !panes.slice(0, capacity).some((pane) => pane.id === value.focusedPaneId)
  ) {
    return null
  }
  return {
    layout: value.layout,
    focusedPaneId: value.focusedPaneId,
    panes,
  }
}

function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

function serializeChatTabSnapshot(state: ChatPaneState): string | null {
  const parsed = parseChatPaneState(state)
  if (parsed == null) {
    return null
  }
  const snapshot: ChatTabSnapshot = {
    version: CHAT_TAB_STATE_VERSION,
    presentation: parsed,
  }
  const serialized = JSON.stringify(snapshot)
  return utf8ByteLength(serialized) <= CHAT_TAB_STATE_MAX_BYTES ? serialized : null
}

function parseChatTabSnapshot(raw: string | null): ChatPaneState | null {
  if (raw == null || utf8ByteLength(raw) > CHAT_TAB_STATE_MAX_BYTES) {
    return null
  }
  try {
    const value: unknown = JSON.parse(raw)
    if (
      !isRecord(value)
      || !hasExactKeys(value, ['version', 'presentation'])
      || value.version !== CHAT_TAB_STATE_VERSION
    ) {
      return null
    }
    return parseChatPaneState(value.presentation)
  } catch {
    return null
  }
}

function resolveGlobalStorage(kind: 'sessionStorage' | 'localStorage'): ChatTabStorageArea | null {
  try {
    return globalThis[kind] ?? null
  } catch {
    return null
  }
}

function resolveStorage(storage?: ChatTabStorage): {
  session: ChatTabStorageArea | null
  local: ChatTabStorageArea | null
} {
  const session = storage?.sessionStorage === undefined
    ? resolveGlobalStorage('sessionStorage')
    : storage.sessionStorage
  const local = storage?.localStorage === undefined
    ? resolveGlobalStorage('localStorage')
    : storage.localStorage
  return { session: session ?? null, local: local ?? null }
}

function readSnapshot(storage: ChatTabStorageArea | null, key: string): ChatPaneState | null {
  if (storage == null) {
    return null
  }
  try {
    return parseChatTabSnapshot(storage.getItem(key))
  } catch {
    return null
  }
}

function writeSnapshot(
  storage: ChatTabStorageArea | null,
  key: string,
  snapshot: string,
): boolean {
  if (storage == null) {
    return false
  }
  try {
    storage.setItem(key, snapshot)
    return true
  } catch {
    return false
  }
}

/**
 * 恢复顺序：本 tab 的 sessionStorage、最后一次操作状态、产品默认值。
 * 初始化只补写本 tab 的 sessionStorage，绝不把刷新结果写回 localStorage。
 */
export function loadChatPaneState(
  chatId: string,
  storage?: ChatTabStorage,
): ChatPaneLoadResult {
  if (!chatId) {
    return { state: createDefaultChatPaneState(), saved: false }
  }
  const { session, local } = resolveStorage(storage)
  const sessionKey = chatTabSessionStorageKey(chatId)
  const lastKey = chatTabLastStorageKey(chatId)
  const state = readSnapshot(session, sessionKey)
    ?? readSnapshot(local, lastKey)
    ?? createDefaultChatPaneState()
  const snapshot = serializeChatTabSnapshot(state)
  const saved = snapshot == null ? false : writeSnapshot(session, sessionKey, snapshot)
  return { state, saved }
}

/**
 * 用户事件的唯一提交入口：完整快照写本 tab 的 sessionStorage，剔除运行身份后的
 * 可复用呈现写 localStorage。Chat 快照本身不含运行身份，两份结构一致。
 */
export function saveChatPaneState(
  chatId: string,
  state: ChatPaneState,
  storage?: ChatTabStorage,
): boolean {
  if (!chatId) {
    return false
  }
  const snapshot = serializeChatTabSnapshot(state)
  if (snapshot == null) {
    return false
  }
  const { session, local } = resolveStorage(storage)
  const sessionSaved = writeSnapshot(session, chatTabSessionStorageKey(chatId), snapshot)
  const localSaved = writeSnapshot(local, chatTabLastStorageKey(chatId), snapshot)
  return sessionSaved && localSaved
}

export function chatPaneTarget(state: ChatPaneState, paneId: string): PaneTarget | null {
  return state.panes.find((pane) => pane.id === paneId)?.target ?? null
}

export function setChatPaneTarget(
  state: ChatPaneState,
  paneId: string,
  target: PaneTarget,
): ChatPaneState {
  const index = state.panes.findIndex((pane) => pane.id === paneId)
  if (index < 0) {
    return state
  }
  const normalized = normalizePaneTarget(target)
  if (samePaneTarget(state.panes[index]!.target, normalized)) {
    return state
  }
  const panes = state.panes.slice()
  panes[index] = createPane(index, normalized)
  return { ...state, panes }
}

export function applyChatLayout(state: ChatPaneState, layout: ChatLayout): ChatPaneState {
  if (state.layout === layout) {
    return state
  }
  const panes = Array.from(
    { length: CHAT_PANE_COUNT },
    (_, index) => state.panes[index] ?? createPane(index),
  ).map((pane, index) => createPane(index, pane.target))
  const capacity = CHAT_LAYOUT_CAPACITY[layout]
  const focusedPaneId = panes.slice(0, capacity).some((pane) => pane.id === state.focusedPaneId)
    ? state.focusedPaneId
    : panes[0]?.id ?? 'pane-1'
  return { ...state, layout, panes, focusedPaneId }
}

export function focusPane(state: ChatPaneState, paneId: string): ChatPaneState {
  const capacity = CHAT_LAYOUT_CAPACITY[state.layout]
  if (!state.panes.slice(0, capacity).some((pane) => pane.id === paneId)) {
    return state
  }
  return { ...state, focusedPaneId: paneId }
}

export function visibleChatPanes(state: ChatPaneState): ChatPane[] {
  return state.panes.slice(0, CHAT_LAYOUT_CAPACITY[state.layout])
}

/** 面板序号（1 起）。id 不是 `pane-N` 时返回 null（未知位置）。 */
export function chatPanePosition(paneId: string): number | null {
  const matched = /^pane-(\d+)$/.exec(paneId)
  if (matched == null) {
    return null
  }
  const position = Number(matched[1])
  return Number.isInteger(position) && position >= 1 ? position : null
}

/** 容纳 `count` 个面板的最小布局；count 超出 9 时取 grid-9。 */
export function chatLayoutForPaneCount(count: number): ChatLayout {
  const clamped = Math.min(Math.max(Math.trunc(count), 1), CHAT_PANE_COUNT)
  if (clamped === 1) {
    return 'single'
  }
  if (clamped === 2) {
    return 'split-2'
  }
  if (clamped === 3) {
    return 'split-3'
  }
  return `grid-${clamped}` as ChatLayout
}

/**
 * 让目标面板可见：目标落在当前布局容量之外时把布局扩展到刚好容纳它，
 * 否则保持既有布局（隐藏布局的显露是目标路由的前置条件）。
 */
export function revealChatPane(state: ChatPaneState, paneId: string): ChatPaneState {
  const position = chatPanePosition(paneId)
  if (position == null || position <= CHAT_LAYOUT_CAPACITY[state.layout]) {
    return state
  }
  return applyChatLayout(state, chatLayoutForPaneCount(position))
}
