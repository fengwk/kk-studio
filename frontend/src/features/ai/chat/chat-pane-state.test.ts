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
  it('creates default single layout with eight persistent blank panes', () => {
    const state = loadChatPaneState('chat-1', new MemoryStorage())
    expect(state.layout).toBe('single')
    expect(state.panes).toHaveLength(8)
    expect(state.panes[0].threadId).toBeNull()
    expect(state.focusedPaneId).toBe(state.panes[0].id)
  })

  it('retains pane thread bindings when expanding and shrinking layouts', () => {
    const paneOneBound = updatePaneThread(
      loadChatPaneState('chat-1', new MemoryStorage()),
      'pane-1',
      't1',
    )
    const base = updatePaneThread(paneOneBound, 'pane-8', 't8')
    const expanded = applyChatLayout(base, 'grid-8')
    expect(expanded.panes).toHaveLength(8)
    expect(expanded.panes[0].threadId).toBe('t1')
    expect(expanded.panes[7].threadId).toBe('t8')
    const shrunk = applyChatLayout(expanded, 'single')
    expect(shrunk.panes).toHaveLength(8)
    expect(shrunk.panes[0].threadId).toBe('t1')
    expect(shrunk.panes[7].threadId).toBe('t8')
    expect(applyChatLayout(shrunk, 'grid-8').panes[7].threadId).toBe('t8')
  })

  it('normalizes corrupted storage and persists by chat id', () => {
    const storage = new MemoryStorage()
    storage.setItem('kk-studio.chat-pane.corrupted', JSON.stringify({ layout: 'nope', panes: 'x' }))
    expect(loadChatPaneState('corrupted', storage).layout).toBe('single')
    const state = applyChatLayout(loadChatPaneState('chat-1', storage), 'grid-6')
    saveChatPaneState('chat-1', state, storage)
    const loaded = loadChatPaneState('chat-1', storage)
    expect(loaded.layout).toBe('grid-6')
    expect(loaded.panes).toHaveLength(8)
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
    expect(loadChatPaneState('custom', storage).focusedPaneId).toBe('pane-1')
  })

  it('retains hidden pane bindings and normalizes pane ids to pane-1 through pane-8', () => {
    const storage = new MemoryStorage()
    storage.setItem('kk-studio.chat-pane.hidden', JSON.stringify({
      layout: 'single',
      focusedPaneId: 'pane-1',
      panes: [
        { id: 'old-1', threadId: 't1' },
        { id: 'old-2', threadId: 't2' },
        null,
        null,
        null,
        null,
        null,
        { id: 'old-8', threadId: 't8' },
      ],
    }))
    const state = loadChatPaneState('hidden', storage)
    expect(state.panes.map((pane) => pane.id)).toEqual([
      'pane-1', 'pane-2', 'pane-3', 'pane-4',
      'pane-5', 'pane-6', 'pane-7', 'pane-8',
    ])
    expect(state.panes[1].threadId).toBe('t2')
    expect(state.panes[7].threadId).toBe('t8')
    const expanded = applyChatLayout(state, 'grid-8')
    expect(expanded.panes[1].threadId).toBe('t2')
    expect(expanded.panes[7].threadId).toBe('t8')
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
