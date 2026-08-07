/**
 * Chat 工作区本地状态（浏览器 localStorage）。
 *
 * 只存 UI 姿势与“每个面板绑哪条 Thread”：
 * - layout / focusedPaneId / 列表排序偏好
 * - panes[].threadId（null = 空面板）
 *
 * 不存 Session、Agent、消息等服务端真相。
 */

export type ChatLayout = 'single' | 'split-2' | 'split-3' | 'grid-4' | 'grid-6' | 'grid-8'
export type PaneSortPreference = 'recent' | 'created'

export interface ChatPane {
  id: string
  /** 空面板为 null；绑定后只记 threadId，session 从 Thread 反查。 */
  threadId: string | null
}

export interface ChatPaneState {
  layout: ChatLayout
  focusedPaneId: string
  panes: ChatPane[]
  threadSort: PaneSortPreference
}

const CHAT_LAYOUT_CAPACITY: Record<ChatLayout, number> = {
  single: 1,
  'split-2': 2,
  'split-3': 3,
  'grid-4': 4,
  'grid-6': 6,
  'grid-8': 8,
}

const CHAT_PANE_COUNT = 8
const STORAGE_PREFIX = 'kk-studio.chat-pane.'

function storageKey(chatId: string): string {
  return `${STORAGE_PREFIX}${chatId}`
}

function createPaneId(index: number): string {
  return `pane-${index + 1}`
}

function createEmptyPane(index: number): ChatPane {
  return { id: createPaneId(index), threadId: null }
}

function createDefaultChatPaneState(layout: ChatLayout = 'single'): ChatPaneState {
  const panes = Array.from({ length: CHAT_PANE_COUNT }, (_, index) => createEmptyPane(index))
  return {
    layout,
    focusedPaneId: panes[0]?.id ?? 'pane-1',
    panes,
    threadSort: 'recent',
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object'
}

function parseLayout(value: unknown): ChatLayout {
  if (
    value === 'single'
    || value === 'split-2'
    || value === 'split-3'
    || value === 'grid-4'
    || value === 'grid-6'
    || value === 'grid-8'
  ) {
    return value
  }
  return 'single'
}

function parseSort(value: unknown): PaneSortPreference {
  return value === 'created' ? 'created' : 'recent'
}

function parseThreadId(value: unknown): string | null {
  if (typeof value === 'string' && value.trim()) {
    return value.trim()
  }
  return null
}

function parsePane(value: unknown, index: number): ChatPane {
  if (!isRecord(value)) {
    return createEmptyPane(index)
  }
  const threadId = parseThreadId(value.threadId)
  return { id: createPaneId(index), threadId }
}

function normalizeChatPaneState(raw: unknown): ChatPaneState {
  const defaults = createDefaultChatPaneState()
  if (!isRecord(raw)) {
    return defaults
  }
  const layout = parseLayout(raw.layout)
  const rawPanes = Array.isArray(raw.panes) ? raw.panes : []
  const panes: ChatPane[] = []
  for (let index = 0; index < CHAT_PANE_COUNT; index += 1) {
    panes.push(parsePane(rawPanes[index], index))
  }
  const capacity = CHAT_LAYOUT_CAPACITY[layout]
  const focusedPaneId =
    typeof raw.focusedPaneId === 'string'
    && panes.slice(0, capacity).some((pane) => pane.id === raw.focusedPaneId)
      ? raw.focusedPaneId
      : panes[0].id
  return {
    layout,
    focusedPaneId,
    panes,
    threadSort: parseSort(raw.threadSort),
  }
}

/** 仅修改可见容量；八个持久化的面板绑定保持不变。 */
export function applyChatLayout(state: ChatPaneState, layout: ChatLayout): ChatPaneState {
  if (state.layout === layout) {
    return state
  }
  const panes = Array.from({ length: CHAT_PANE_COUNT }, (_, index) => {
    const existing = state.panes[index]
    return existing
      ? { id: createPaneId(index), threadId: existing.threadId }
      : createEmptyPane(index)
  })
  const capacity = CHAT_LAYOUT_CAPACITY[layout]
  const focusedPaneId = panes.slice(0, capacity).some((pane) => pane.id === state.focusedPaneId)
    ? state.focusedPaneId
    : panes[0].id
  return {
    ...state,
    layout,
    panes,
    focusedPaneId,
  }
}

export function updatePaneThread(
  state: ChatPaneState,
  paneId: string,
  threadId: string | null,
): ChatPaneState {
  const normalized = threadId && threadId.trim() ? threadId.trim() : null
  return {
    ...state,
    panes: state.panes.map((pane) =>
      pane.id === paneId ? { ...pane, threadId: normalized } : pane,
    ),
  }
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

export function loadChatPaneState(chatId: string, storage: Storage = localStorage): ChatPaneState {
  if (!chatId) {
    return createDefaultChatPaneState()
  }
  try {
    const raw = storage.getItem(storageKey(chatId))
    if (!raw) {
      return createDefaultChatPaneState()
    }
    return normalizeChatPaneState(JSON.parse(raw) as unknown)
  } catch {
    return createDefaultChatPaneState()
  }
}

export function saveChatPaneState(
  chatId: string,
  state: ChatPaneState,
  storage: Storage = localStorage,
): void {
  if (!chatId) {
    return
  }
  storage.setItem(storageKey(chatId), JSON.stringify(normalizeChatPaneState(state)))
}

export function isPaneBound(threadId: string | null | undefined): threadId is string {
  return Boolean(threadId && threadId.trim())
}
