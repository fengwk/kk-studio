import { describe, expect, it } from 'vitest'
import {
  applyChatLayout,
  focusPane,
  loadChatPaneState,
  saveChatPaneState,
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

describe('Chat pane layout state', () => {
  it('persists layout and focus without duplicating PaneTarget storage', () => {
    const store = storage()
    let state = loadChatPaneState('chat-1', store)
    state = applyChatLayout(state, 'split-2')
    state = focusPane(state, 'pane-2')
    saveChatPaneState('chat-1', state, store)
    expect(loadChatPaneState('chat-1', store)).toMatchObject({
      layout: 'split-2',
      focusedPaneId: 'pane-2',
    })
    expect(loadChatPaneState('chat-1', store).panes[0]).toEqual({ id: 'pane-1' })
  })

  it('keeps pane slots through layout changes and focuses only visible panes', () => {
    let state = loadChatPaneState('chat-1', storage())
    state = applyChatLayout(state, 'split-2')
    state = focusPane(state, 'pane-2')
    expect(visibleChatPanes(state).map((pane) => pane.id)).toEqual(['pane-1', 'pane-2'])
    expect(state.focusedPaneId).toBe('pane-2')
  })
})
