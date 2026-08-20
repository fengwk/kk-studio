import { describe, expect, it } from 'vitest'
import {
  applyChatLayout,
  focusPane,
  loadChatPaneState,
  saveChatPaneState,
  updatePaneTarget,
  visibleChatPanes,
} from '@/features/ai/chat/chat-pane-state'

function storage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() {
      return values.size
    },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value),
  }
}

describe('Chat PaneTarget slots', () => {
  it('persists and changes only the three-state target', () => {
    const store = storage()
    let state = loadChatPaneState('chat-1', store)
    state = updatePaneTarget(state, 'pane-1', {
      kind: 'ENTRY_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
    })
    saveChatPaneState('chat-1', state, store)
    expect(loadChatPaneState('chat-1', store).panes[0]?.target).toEqual({
      kind: 'ENTRY_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
    })
  })

  it('keeps target slots through layout changes and focuses only visible panes', () => {
    let state = loadChatPaneState('chat-1', storage())
    state = updatePaneTarget(state, 'pane-2', { kind: 'BOUND_THREAD', threadId: 't2' })
    state = applyChatLayout(state, 'split-2')
    state = focusPane(state, 'pane-2')
    expect(visibleChatPanes(state).map((pane) => pane.id)).toEqual(['pane-1', 'pane-2'])
    expect(state.focusedPaneId).toBe('pane-2')
  })
})
