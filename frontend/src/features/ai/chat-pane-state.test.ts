import { describe, expect, it } from 'vitest'
import {
  applyChatLayout,
  createDefaultChatPaneState,
  focusPane,
  isPaneBound,
  loadChatPaneState,
  normalizeChatPaneState,
  saveChatPaneState,
  sortWithRunningFirst,
  updatePaneTarget,
} from '@/features/ai/chat-pane-state'

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
    const state = createDefaultChatPaneState()
    expect(state.layout).toBe('single')
    expect(state.panes).toHaveLength(1)
    expect(state.panes[0].target).toEqual({})
    expect(state.focusedPaneId).toBe(state.panes[0].id)
  })

  it('retains pane targets when expanding and shrinking layouts', () => {
    const base = updatePaneTarget(createDefaultChatPaneState(), 'pane-1', {
      sessionId: 's1',
      threadId: 't1',
    })
    const expanded = applyChatLayout(base, 'split-3')
    expect(expanded.panes).toHaveLength(3)
    expect(expanded.panes[0].target).toEqual({ sessionId: 's1', threadId: 't1' })
    expect(expanded.panes[1].target).toEqual({})
    const shrunk = applyChatLayout(expanded, 'single')
    expect(shrunk.panes).toHaveLength(1)
    expect(shrunk.panes[0].target).toEqual({ sessionId: 's1', threadId: 't1' })
  })

  it('normalizes corrupted storage and persists by chat id', () => {
    const storage = new MemoryStorage()
    expect(normalizeChatPaneState({ layout: 'nope', panes: 'x' }).layout).toBe('single')
    const state = applyChatLayout(createDefaultChatPaneState(), 'grid-6')
    saveChatPaneState('chat-1', state, storage)
    const loaded = loadChatPaneState('chat-1', storage)
    expect(loaded.layout).toBe('grid-6')
    expect(loaded.panes).toHaveLength(6)
  })

  it('focuses panes and detects bound targets', () => {
    const state = createDefaultChatPaneState('split-2')
    expect(focusPane(state, 'missing').focusedPaneId).toBe(state.focusedPaneId)
    expect(focusPane(state, state.panes[1].id).focusedPaneId).toBe(state.panes[1].id)
    expect(isPaneBound({})).toBe(false)
    expect(isPaneBound({ sessionId: 's', threadId: 't' })).toBe(true)
    expect(loadChatPaneState('')).toEqual(createDefaultChatPaneState())
    expect(normalizeChatPaneState({ layout: 'split-2', focusedPaneId: 'gone', panes: [{ id: 'custom', target: { sessionId: 's' } }] }).focusedPaneId).toBe('custom')
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
