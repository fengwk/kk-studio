export type ChatLayout = 'single' | 'split-2' | 'split-3' | 'grid-6'
export type PaneSortPreference = 'recent' | 'created'

export interface PaneTarget {
  sessionId?: string
  threadId?: string
}

export interface ChatPane {
  id: string
  target: PaneTarget
}

export interface ChatPaneState {
  layout: ChatLayout
  focusedPaneId: string
  panes: ChatPane[]
  sessionSort: PaneSortPreference
  threadSort: PaneSortPreference
}

export const CHAT_LAYOUT_CAPACITY: Record<ChatLayout, number> = {
  single: 1,
  'split-2': 2,
  'split-3': 3,
  'grid-6': 6,
}

const STORAGE_PREFIX = 'kk-studio.chat-pane.'

function storageKey(chatId: string): string {
  return `${STORAGE_PREFIX}${chatId}`
}

function createPaneId(index: number): string {
  return `pane-${index + 1}`
}

export function createEmptyPane(index: number): ChatPane {
  return { id: createPaneId(index), target: {} }
}

export function createDefaultChatPaneState(layout: ChatLayout = 'single'): ChatPaneState {
  const capacity = CHAT_LAYOUT_CAPACITY[layout]
  const panes = Array.from({ length: capacity }, (_, index) => createEmptyPane(index))
  return {
    layout,
    focusedPaneId: panes[0]?.id ?? 'pane-1',
    panes,
    sessionSort: 'recent',
    threadSort: 'recent',
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object'
}

function parseTarget(value: unknown): PaneTarget {
  if (!isRecord(value)) {
    return {}
  }
  const sessionId = typeof value.sessionId === 'string' && value.sessionId.trim() ? value.sessionId : undefined
  const threadId = typeof value.threadId === 'string' && value.threadId.trim() ? value.threadId : undefined
  return { sessionId, threadId }
}

function parseLayout(value: unknown): ChatLayout {
  if (value === 'single' || value === 'split-2' || value === 'split-3' || value === 'grid-6') {
    return value
  }
  return 'single'
}

function parseSort(value: unknown): PaneSortPreference {
  return value === 'created' ? 'created' : 'recent'
}

export function normalizeChatPaneState(raw: unknown): ChatPaneState {
  const defaults = createDefaultChatPaneState()
  if (!isRecord(raw)) {
    return defaults
  }
  const layout = parseLayout(raw.layout)
  const capacity = CHAT_LAYOUT_CAPACITY[layout]
  const rawPanes = Array.isArray(raw.panes) ? raw.panes : []
  const panes: ChatPane[] = []
  for (let index = 0; index < capacity; index += 1) {
    const candidate = rawPanes[index]
    if (isRecord(candidate) && typeof candidate.id === 'string' && candidate.id.trim()) {
      panes.push({ id: candidate.id, target: parseTarget(candidate.target) })
    } else {
      panes.push(createEmptyPane(index))
    }
  }
  const focusedPaneId =
    typeof raw.focusedPaneId === 'string' && panes.some((pane) => pane.id === raw.focusedPaneId)
      ? raw.focusedPaneId
      : panes[0].id
  return {
    layout,
    focusedPaneId,
    panes,
    sessionSort: parseSort(raw.sessionSort),
    threadSort: parseSort(raw.threadSort),
  }
}

/** Resize panes when layout changes, retaining targets where possible. */
export function applyChatLayout(state: ChatPaneState, layout: ChatLayout): ChatPaneState {
  if (state.layout === layout) {
    return state
  }
  const capacity = CHAT_LAYOUT_CAPACITY[layout]
  const panes: ChatPane[] = []
  for (let index = 0; index < capacity; index += 1) {
    const existing = state.panes[index]
    panes.push(existing ? { ...existing, target: { ...existing.target } } : createEmptyPane(index))
  }
  const focusedPaneId = panes.some((pane) => pane.id === state.focusedPaneId)
    ? state.focusedPaneId
    : panes[0].id
  return {
    ...state,
    layout,
    panes,
    focusedPaneId,
  }
}

export function updatePaneTarget(state: ChatPaneState, paneId: string, target: PaneTarget): ChatPaneState {
  return {
    ...state,
    panes: state.panes.map((pane) => (pane.id === paneId ? { ...pane, target: { ...target } } : pane)),
  }
}

export function focusPane(state: ChatPaneState, paneId: string): ChatPaneState {
  if (!state.panes.some((pane) => pane.id === paneId)) {
    return state
  }
  return { ...state, focusedPaneId: paneId }
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

export function saveChatPaneState(chatId: string, state: ChatPaneState, storage: Storage = localStorage): void {
  if (!chatId) {
    return
  }
  storage.setItem(storageKey(chatId), JSON.stringify(normalizeChatPaneState(state)))
}

export function isPaneBound(target: PaneTarget): target is { sessionId: string; threadId: string } {
  return Boolean(target.sessionId && target.threadId)
}

export type Timestamped = {
  createTime?: unknown
  updateTime?: unknown
  status?: string
}

function backendTimeValue(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value.map((part) => Number(part))
    const time = Date.UTC(year, (month || 1) - 1, day || 1, hour, minute, second)
    return Number.isFinite(time) ? time : 0
  }
  return 0
}

function isRunningStatus(status: string | undefined): boolean {
  return status === 'RUNNING' || status === 'WAITING' || status === 'RETRYING'
}

/** Running items first, then user sort preference (recent=updateTime, created=createTime). */
export function sortWithRunningFirst<T extends Timestamped>(
  items: T[],
  sort: PaneSortPreference,
  isRunning: (item: T) => boolean = (item) => isRunningStatus(item.status),
): T[] {
  return [...items].sort((left, right) => {
    const leftRunning = isRunning(left)
    const rightRunning = isRunning(right)
    if (leftRunning !== rightRunning) {
      return leftRunning ? -1 : 1
    }
    const leftTime = backendTimeValue(sort === 'created' ? left.createTime : left.updateTime)
    const rightTime = backendTimeValue(sort === 'created' ? right.createTime : right.updateTime)
    return rightTime - leftTime
  })
}
