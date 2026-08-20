import type { PaneTarget } from '@/features/ai/runtime/agent-pane/pane-target'

export type ChatLayout = 'single' | 'split-2' | 'split-3' | 'grid-4' | 'grid-6' | 'grid-8'
export type PaneSortPreference = 'recent' | 'created'

export interface ChatPane {
  id: string
  /** Pane durable-local state is exactly one of the three PaneTarget values. */
  target: PaneTarget
}

export interface ChatPaneState {
  layout: ChatLayout
  focusedPaneId: string
  panes: ChatPane[]
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
  return { id: createPaneId(index), target: { kind: 'NEW_SESSION_DRAFT' } }
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

function parseTarget(value: unknown): PaneTarget {
  if (!isRecord(value) || typeof value.kind !== 'string') {
    return { kind: 'NEW_SESSION_DRAFT' }
  }
  if (value.kind === 'NEW_SESSION_DRAFT') {
    return { kind: value.kind }
  }
  if (
    value.kind === 'ENTRY_DRAFT'
    && typeof value.sessionId === 'string'
    && value.sessionId.trim()
    && typeof value.startEntryId === 'string'
    && value.startEntryId.trim()
  ) {
    return {
      kind: value.kind,
      sessionId: value.sessionId.trim(),
      startEntryId: value.startEntryId.trim(),
    }
  }
  if (value.kind === 'BOUND_THREAD' && typeof value.threadId === 'string' && value.threadId.trim()) {
    return { kind: value.kind, threadId: value.threadId.trim() }
  }
  return { kind: 'NEW_SESSION_DRAFT' }
}

function parsePane(value: unknown, index: number): ChatPane {
  if (!isRecord(value)) {
    return createEmptyPane(index)
  }
  return { id: createPaneId(index), target: parseTarget(value.target) }
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

export function updatePaneTarget(
  state: ChatPaneState,
  paneId: string,
  target: PaneTarget,
): ChatPaneState {
  return {
    ...state,
    panes: state.panes.map((pane) => pane.id === paneId ? { ...pane, target } : pane),
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
    return raw == null ? createDefaultChatPaneState() : normalizeChatPaneState(JSON.parse(raw))
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
