import { describe, expect, it } from 'vitest'
import {
  applyChatLayout,
  focusPane,
  isPaneBound,
  loadChatPaneState,
  saveChatPaneState,
  sortWithRunningFirst,
  updatePaneThread,
} from '@/features/ai/chat/chat-pane-state'

class MemoryStorage implements Storage {
  private data = new Map<string, string>()
  get length() {
    return this.data.size
  }
  clear() {
    this.data.clear()
  }
  getItem(key: string) {
    return this.data.get(key) ?? null
  }
  key(index: number) {
    return [...this.data.keys()][index] ?? null
  }
  removeItem(key: string) {
    this.data.delete(key)
  }
  setItem(key: string, value: string) {
    this.data.set(key, value)
  }
}

describe('chat-pane-state', () => {
  it('creates default single layout with one blank pane', () => {
    const state = loadChatPaneState('chat-1', new MemoryStorage())
    expect(state.layout).toBe('single')
    expect(state.panes).toHaveLength(1)
    expect(state.panes[0].threadId).toBeNull()
    expect(state.focusedPaneId).toBe(state.panes[0].id)
  })

  it('retains pane thread bindings when expanding and shrinking layouts', () => {
    const base = updatePaneThread(loadChatPaneState('chat-1', new MemoryStorage()), 'pane-1', 't1')
    const expanded = applyChatLayout(base, 'split-3')
    expect(expanded.panes).toHaveLength(3)
    expect(expanded.panes[0].threadId).toBe('t1')
    expect(expanded.panes[1].threadId).toBeNull()
    const shrunk = applyChatLayout(expanded, 'single')
    expect(shrunk.panes).toHaveLength(1)
    expect(shrunk.panes[0].threadId).toBe('t1')
  })

  it('normalizes corrupted storage and persists by chat id', () => {
    const storage = new MemoryStorage()
    storage.setItem('kk-studio.chat-pane.corrupted', JSON.stringify({ layout: 'nope', panes: 'x' }))
    expect(loadChatPaneState('corrupted', storage).layout).toBe('single')
    const state = applyChatLayout(loadChatPaneState('chat-1', storage), 'grid-6')
    saveChatPaneState('chat-1', state, storage)
    const loaded = loadChatPaneState('chat-1', storage)
    expect(loaded.layout).toBe('grid-6')
    expect(loaded.panes).toHaveLength(6)
  })

  it('ignores unsupported nested pane bindings', () => {
    const storage = new MemoryStorage()
    storage.setItem('kk-studio.chat-pane.chat-1', JSON.stringify({
      layout: 'single',
      focusedPaneId: 'pane-1',
      panes: [{ id: 'pane-1', target: { sessionId: 's1', threadId: 't9' } }],
    }))
    const normalized = loadChatPaneState('chat-1', storage)
    expect(normalized.panes[0].threadId).toBeNull()
  })

  it('focuses panes and detects bound thread ids', () => {
    const storage = new MemoryStorage()
    const state = applyChatLayout(loadChatPaneState('chat-1', storage), 'split-2')
    expect(focusPane(state, 'missing').focusedPaneId).toBe(state.focusedPaneId)
    expect(focusPane(state, state.panes[1].id).focusedPaneId).toBe(state.panes[1].id)
    expect(isPaneBound(null)).toBe(false)
    expect(isPaneBound('t')).toBe(true)
    expect(loadChatPaneState('', storage)).toEqual(loadChatPaneState('another', storage))
    storage.setItem('kk-studio.chat-pane.custom', JSON.stringify({
        layout: 'split-2',
        focusedPaneId: 'gone',
        panes: [{ id: 'custom', threadId: null }],
      }))
    expect(loadChatPaneState('custom', storage).focusedPaneId).toBe('custom')
  })

  it('sorts running items first then by preferred time', () => {
    const items = [
      { id: 'a', status: 'IDLE', updateTime: '2026-01-03T00:00:00Z', createTime: '2026-01-01T00:00:00Z' },
      { id: 'b', status: 'RUNNING', updateTime: '2026-01-02T00:00:00Z', createTime: '2026-01-02T00:00:00Z' },
      { id: 'c', status: 'IDLE', updateTime: '2026-01-04T00:00:00Z', createTime: '2026-01-03T00:00:00Z' },
    ]
    expect(sortWithRunningFirst(items, 'recent').map((item) => item.id)).toEqual(['b', 'c', 'a'])
    expect(sortWithRunningFirst(items, 'created').map((item) => item.id)).toEqual(['b', 'c', 'a'])
  })
})
