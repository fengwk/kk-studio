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

  it('reuses the same state object when the layout does not change', () => {
    const state = loadChatPaneState('chat-1', storage())
    // 切换为当前已生效布局是幂等 no-op：调用方（布局按钮连点）不应触发重渲染。
    expect(applyChatLayout(state, 'single')).toBe(state)
  })

  it('shows exactly the capacity of panes for every layout', () => {
    let state = loadChatPaneState('chat-1', storage())
    const expectations: Array<[Parameters<typeof applyChatLayout>[1], number]> = [
      ['single', 1],
      ['split-2', 2],
      ['split-3', 3],
      ['grid-4', 4],
      ['grid-5', 5],
      ['grid-6', 6],
      ['grid-7', 7],
      ['grid-8', 8],
      ['grid-9', 9],
    ]
    for (const [layout, capacity] of expectations) {
      state = applyChatLayout(state, layout)
      expect(visibleChatPanes(state)).toHaveLength(capacity)
      expect(visibleChatPanes(state)[0]).toEqual({ id: 'pane-1' })
    }
    // 收缩到 single 再扩张：pane 槽位从未丢失。
    expect(state.panes).toHaveLength(9)
  })

  it('drops focus that points at a pane hidden by the smaller layout', () => {
    let state = loadChatPaneState('chat-1', storage())
    state = applyChatLayout(state, 'split-3')
    state = focusPane(state, 'pane-3')
    expect(state.focusedPaneId).toBe('pane-3')
    // 收缩后 pane-3 不再可见：焦点必须回退到第一个可见 pane。
    state = applyChatLayout(state, 'split-2')
    expect(state.focusedPaneId).toBe('pane-1')
  })

  it('ignores focus requests for panes outside the current layout capacity', () => {
    const state = loadChatPaneState('chat-1', storage())
    // 无变化的新对象才表示焦点真正切换；不可见 pane 的请求必须原样返回。
    expect(focusPane(state, 'pane-4')).toBe(state)
    expect(focusPane(state, 'missing')).toBe(state)
  })

  it('falls back to defaults when the persisted value is corrupt or not an object', () => {
    const store = storage()
    store.setItem('kk-studio.chat-pane.chat-1', 'not-json')
    const corrupt = loadChatPaneState('chat-1', store)
    expect(corrupt.layout).toBe('single')
    expect(corrupt.focusedPaneId).toBe('pane-1')

    store.setItem('kk-studio.chat-pane.chat-1', JSON.stringify([1, 2, 3]))
    expect(loadChatPaneState('chat-1', store)).toEqual(corrupt)

    // 数组/字符串等非对象 raw 值同样回退默认值。
    store.setItem('kk-studio.chat-pane.chat-1', '"oops"')
    expect(loadChatPaneState('chat-1', store)).toEqual(corrupt)
  })

  it('normalizes an unknown layout and non-record panes to defaults', () => {
    const store = storage()
    store.setItem(
      'kk-studio.chat-pane.chat-1',
      JSON.stringify({
        layout: 'tiles-16',
        focusedPaneId: 'pane-2',
        panes: ['not-a-pane', null, { id: 'custom' }],
      }),
    )
    const state = loadChatPaneState('chat-1', store)
    expect(state.layout).toBe('single')
    // 焦点 pane 不存在于 single 容量内：回退第一个 pane。
    expect(state.focusedPaneId).toBe('pane-1')
    expect(state.panes[0]).toEqual({ id: 'pane-1' })
    expect(state.panes[1]).toEqual({ id: 'pane-2' })
    // 非 record pane 一律重建为规范 id；panes 始终补齐 9 个槽位。
    expect(state.panes[2]).toEqual({ id: 'pane-3' })
    expect(state.panes).toHaveLength(9)
  })

  it('skips storage entirely for an empty chat id', () => {
    const store = storage()
    expect(loadChatPaneState('', store)).toMatchObject({ layout: 'single', focusedPaneId: 'pane-1' })
    expect(saveChatPaneState('', applyChatLayout(loadChatPaneState('', store), 'split-2'), store)).toBeUndefined()
    expect(store.length).toBe(0)
  })
})
