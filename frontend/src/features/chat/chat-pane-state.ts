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
}

export interface ChatPaneState {
  layout: ChatLayout
  focusedPaneId: string
  panes: ChatPane[]
}

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
const STORAGE_PREFIX = 'kk-studio.chat-pane.'

function storageKey(chatId: string): string {
  return `${STORAGE_PREFIX}${chatId}`
}

function createPaneId(index: number): string {
  return `pane-${index + 1}`
}

function createEmptyPane(index: number): ChatPane {
  return { id: createPaneId(index) }
}

function createDefaultChatPaneState(layout: ChatLayout = 'single'): ChatPaneState {
  const panes = Array.from({ length: CHAT_PANE_COUNT }, (_, index) => createEmptyPane(index))
  return {
    layout,
    focusedPaneId: panes[0]?.id ?? 'pane-1',
    panes,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

export function parseLayout(value: unknown): ChatLayout {
  if (
    value === 'single'
    || value === 'split-2'
    || value === 'split-3'
    || value === 'grid-4'
    || value === 'grid-5'
    || value === 'grid-6'
    || value === 'grid-7'
    || value === 'grid-8'
    || value === 'grid-9'
  ) {
    return value
  }
  return 'single'
}

function parsePane(value: unknown, index: number): ChatPane {
  if (!isRecord(value)) {
    return createEmptyPane(index)
  }
  return { id: createPaneId(index) }
}

function normalizeChatPaneState(raw: unknown): ChatPaneState {
  const defaults = createDefaultChatPaneState()
  if (!isRecord(raw)) {
    return defaults
  }
  const layout = parseLayout(raw.layout)
  const rawPanes = Array.isArray(raw.panes) ? raw.panes : []
  const panes = Array.from(
    { length: CHAT_PANE_COUNT },
    (_, index) => parsePane(rawPanes[index], index),
  )
  const capacity = CHAT_LAYOUT_CAPACITY[layout]
  const focusedPaneId =
    typeof raw.focusedPaneId === 'string'
    && panes.slice(0, capacity).some((pane) => pane.id === raw.focusedPaneId)
      ? raw.focusedPaneId
      : panes[0]?.id ?? 'pane-1'
  return {
    layout,
    focusedPaneId,
    panes,
  }
}

export function applyChatLayout(state: ChatPaneState, layout: ChatLayout): ChatPaneState {
  if (state.layout === layout) {
    return state
  }
  const panes = Array.from(
    { length: CHAT_PANE_COUNT },
    (_, index) => state.panes[index] ?? createEmptyPane(index),
  ).map((pane, index) => ({ ...pane, id: createPaneId(index) }))
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

function resolveStorage(storage?: Storage): Storage | null {
  if (storage !== undefined) {
    return storage
  }
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null
  } catch {
    return null
  }
}

export function loadChatPaneState(chatId: string, storage?: Storage): ChatPaneState {
  if (!chatId) {
    return createDefaultChatPaneState()
  }
  try {
    const store = resolveStorage(storage)
    if (!store) {
      return createDefaultChatPaneState()
    }
    const raw = store.getItem(storageKey(chatId))
    return raw == null ? createDefaultChatPaneState() : normalizeChatPaneState(JSON.parse(raw))
  } catch {
    return createDefaultChatPaneState()
  }
}

export function saveChatPaneState(
  chatId: string,
  state: ChatPaneState,
  storage?: Storage,
): void {
  if (!chatId) {
    return
  }
  try {
    const store = resolveStorage(storage)
    if (!store) {
      return
    }
    store.setItem(storageKey(chatId), JSON.stringify(normalizeChatPaneState(state)))
  } catch {
    // 写入异常（QuotaExceededError、SecurityError 等）fail-open，不从 effect 抛出。
  }
}
