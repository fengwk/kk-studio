import { describe, expect, it } from 'vitest'
import {
  applyChatLayout,
  chatLayoutForPaneCount,
  chatPanePosition,
  focusPane,
  loadChatPaneState,
  revealChatPane,
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

  it('safely handles storage write and read exceptions without throwing', () => {
    // 测试意图：验证 Storage 在 setItem 抛出 QuotaExceededError / SecurityError 时 fail-open，不抛出异常破坏页面 effect
    const throwingStore: Storage = {
      length: 0,
      clear: () => {},
      getItem: () => {
        throw new Error('SecurityError: access denied')
      },
      key: () => null,
      removeItem: () => {},
      setItem: () => {
        throw new Error('QuotaExceededError')
      },
    }
    const defaultState = loadChatPaneState('chat-1', throwingStore)
    expect(defaultState.layout).toBe('single')
    expect(defaultState.focusedPaneId).toBe('pane-1')
    expect(() => saveChatPaneState('chat-1', defaultState, throwingStore)).not.toThrow()
  })
})

describe('Chat pane destination routing helpers', () => {
  it('maps pane ids to positions 1..9 and rejects unknown ids', () => {
    expect(chatPanePosition('pane-1')).toBe(1)
    expect(chatPanePosition('pane-9')).toBe(9)
    expect(chatPanePosition('pane-0')).toBeNull()
    expect(chatPanePosition('pane-x')).toBeNull()
    expect(chatPanePosition('thread-1')).toBeNull()
  })

  it('picks the smallest layout that can hold the requested pane count', () => {
    expect(chatLayoutForPaneCount(1)).toBe('single')
    expect(chatLayoutForPaneCount(2)).toBe('split-2')
    expect(chatLayoutForPaneCount(3)).toBe('split-3')
    expect(chatLayoutForPaneCount(4)).toBe('grid-4')
    expect(chatLayoutForPaneCount(9)).toBe('grid-9')
    expect(chatLayoutForPaneCount(0)).toBe('single')
    expect(chatLayoutForPaneCount(12)).toBe('grid-9')
  })

  it('reveals a hidden destination pane by expanding the layout', () => {
    const single = loadChatPaneState('chat-1', storage())
    expect(visibleChatPanes(single).map((pane) => pane.id)).toEqual(['pane-1'])

    const revealed = revealChatPane(single, 'pane-5')
    expect(revealed.layout).toBe('grid-5')
    expect(visibleChatPanes(revealed).map((pane) => pane.id)).toEqual([
      'pane-1',
      'pane-2',
      'pane-3',
      'pane-4',
      'pane-5',
    ])

    // 已在容量内的目标位置与未知位置都不改变布局。
    expect(revealChatPane(revealed, 'pane-2')).toBe(revealed)
    expect(revealChatPane(revealed, 'pane-9').layout).toBe('grid-9')
    const nine = revealChatPane(revealed, 'pane-9')
    expect(revealChatPane(nine, 'unknown')).toBe(nine)
  })
})
