import { describe, expect, it } from 'vitest'
import {
  CHAT_TAB_STATE_MAX_BYTES,
  applyChatLayout,
  chatLayoutForPaneCount,
  chatPanePosition,
  chatPaneTarget,
  chatTabLastStorageKey,
  chatTabSessionStorageKey,
  createDefaultChatPaneState,
  focusPane,
  loadChatPaneState,
  parseChatPaneState,
  revealChatPane,
  saveChatPaneState,
  setChatPaneTarget,
  visibleChatPanes,
  type ChatPaneState,
  type ChatTabStorage,
} from '@/features/chat/chat-pane-state'
import type { PaneTarget } from '@/features/chat/runtime/agent-pane'

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

function tabStorage(local: Storage, session: Storage = storage()): ChatTabStorage {
  return { localStorage: local, sessionStorage: session }
}

function bindThread(threadId: string): PaneTarget {
  return { kind: 'BOUND_THREAD', threadId }
}

function snapshotOf(state: ChatPaneState) {
  return { version: 1, presentation: state }
}

function readSnapshot(store: Storage, chatId: string) {
  const raw = store.getItem(chatTabLastStorageKey(chatId))
    ?? store.getItem(chatTabSessionStorageKey(chatId))
  return raw == null ? null : JSON.parse(raw) as ReturnType<typeof snapshotOf>
}

describe('Chat tab presentation storage', () => {
  it('creates nine canonical pane slots with safe draft targets', () => {
    const state = createDefaultChatPaneState()
    expect(state.layout).toBe('single')
    expect(state.focusedPaneId).toBe('pane-1')
    expect(state.panes).toHaveLength(9)
    expect(state.panes[8]).toEqual({ id: 'pane-9', target: { kind: 'NEW_SESSION_DRAFT' } })
  })

  it('initializes from session, then last state, then defaults; only session is written', () => {
    const local = storage()
    const session = storage()
    const state = createDefaultChatPaneState('split-2')
    local.setItem(chatTabLastStorageKey('chat-1'), JSON.stringify(snapshotOf(state)))
    const beforeLocal = local.getItem(chatTabLastStorageKey('chat-1'))

    const loaded = loadChatPaneState('chat-1', tabStorage(local, session))

    expect(loaded.saved).toBe(true)
    expect(loaded.state.layout).toBe('split-2')
    expect(session.getItem(chatTabSessionStorageKey('chat-1'))).not.toBeNull()
    // 初始化只补写本 tab 的恢复索引，不把刷新结果写回全局最后状态。
    expect(local.getItem(chatTabLastStorageKey('chat-1'))).toBe(beforeLocal)
  })

  it('prefers a valid session snapshot over a newer last state', () => {
    const local = storage()
    const session = storage()
    const own = applyChatLayout(createDefaultChatPaneState(), 'split-2')
    session.setItem(chatTabSessionStorageKey('chat-1'), JSON.stringify(snapshotOf(own)))
    local.setItem(
      chatTabLastStorageKey('chat-1'),
      JSON.stringify(snapshotOf(applyChatLayout(createDefaultChatPaneState(), 'grid-4'))),
    )

    expect(loadChatPaneState('chat-1', tabStorage(local, session)).state.layout).toBe('split-2')
  })

  it('falls through an invalid session snapshot to last state, and to defaults when both fail', () => {
    const local = storage()
    const session = storage()
    session.setItem(chatTabSessionStorageKey('chat-1'), '{"version":999,"presentation":{}}')
    local.setItem(
      chatTabLastStorageKey('chat-1'),
      JSON.stringify(snapshotOf(applyChatLayout(createDefaultChatPaneState(), 'split-3'))),
    )
    expect(loadChatPaneState('chat-1', tabStorage(local, session)).state.layout).toBe('split-3')

    session.setItem(chatTabSessionStorageKey('chat-2'), 'not-json')
    local.setItem(chatTabLastStorageKey('chat-2'), '{"version":1,"presentation":{"layout":"grid-4"}}')
    const fallback = loadChatPaneState('chat-2', tabStorage(local, session))
    expect(fallback.state.layout).toBe('single')
    expect(fallback.saved).toBe(true)
    // 损坏来源被本 tab 的默认快照替换，但仍不触碰 localStorage。
    expect(JSON.parse(session.getItem(chatTabSessionStorageKey('chat-2'))!)).toMatchObject({
      version: 1,
      presentation: { layout: 'single' },
    })
  })

  it('dual-writes the exact same bounded snapshot for user commits', () => {
    const local = storage()
    const session = storage()
    let state = loadChatPaneState('chat-1', tabStorage(local, session)).state
    state = applyChatLayout(state, 'split-2')
    state = focusPane(state, 'pane-2')
    state = setChatPaneTarget(state, 'pane-1', bindThread('thread-1'))

    expect(saveChatPaneState('chat-1', state, tabStorage(local, session))).toBe(true)
    const localRaw = local.getItem(chatTabLastStorageKey('chat-1'))
    const sessionRaw = session.getItem(chatTabSessionStorageKey('chat-1'))
    expect(localRaw).toBe(sessionRaw)
    expect(JSON.parse(localRaw!)).toEqual(snapshotOf(state))
    expect(loadChatPaneState('chat-1', tabStorage(local, session)).state).toEqual(state)
  })

  it('keeps pending side-effect records untouched when presentation state switches target', () => {
    const store = storage()
    const pendingKey = 'kk-studio.agent-pane-acceptance.CHAT:chat-1:pane-1'
    store.setItem(pendingKey, '{"opaque":"sidecar"}')
    const state = setChatPaneTarget(createDefaultChatPaneState(), 'pane-1', bindThread('thread-1'))
    expect(saveChatPaneState('chat-1', state, tabStorage(store, store))).toBe(true)
    // 展示目标切换不清理属于原操作对账的独立记录。
    expect(store.getItem(pendingKey)).toBe('{"opaque":"sidecar"}')
  })

  it('ignores legacy presentation keys', () => {
    const local = storage()
    local.setItem('kk-studio.chat-pane.chat-1', JSON.stringify({ layout: 'grid-4', focusedPaneId: 'pane-1', panes: [] }))
    local.setItem('kk-studio.agent-pane-target.CHAT:chat-1:pane-1', JSON.stringify(bindThread('thread-1')))
    const loaded = loadChatPaneState('chat-1', { localStorage: local, sessionStorage: storage() })
    expect(loaded.state.layout).toBe('single')
    expect(chatPaneTarget(loaded.state, 'pane-1')).toEqual({ kind: 'NEW_SESSION_DRAFT' })
  })

  it('rejects malformed, version-mismatched, unknown-field, and out-of-range snapshots', () => {
    const base = snapshotOf(createDefaultChatPaneState())
    const malformed: unknown[] = [
      { version: 2, presentation: base.presentation },
      { ...base, extra: true },
      { version: 1, presentation: { ...base.presentation, extra: true } },
      { version: 1, presentation: { ...base.presentation, layout: 'tiles-16' } },
      { version: 1, presentation: { ...base.presentation, focusedPaneId: 'pane-9' } },
      { version: 1, presentation: { ...base.presentation, panes: base.presentation.panes.slice(0, 8) } },
      {
        version: 1,
        presentation: {
          ...base.presentation,
          panes: [{ ...base.presentation.panes[0], id: 'pane-x' }, ...base.presentation.panes.slice(1)],
        },
      },
      {
        version: 1,
        presentation: {
          ...base.presentation,
          panes: [{ ...base.presentation.panes[0], target: { kind: 'BOUND_THREAD', threadId: 't1', extra: true } },
            ...base.presentation.panes.slice(1)],
        },
      },
      {
        version: 1,
        presentation: {
          ...base.presentation,
          panes: [{ ...base.presentation.panes[0], target: { kind: 'BOUND_THREAD', threadId: '' } },
            ...base.presentation.panes.slice(1)],
        },
      },
    ]
    for (const value of malformed) {
      expect(parseChatPaneState(value)).toBeNull()
    }
    expect(parseChatPaneState(base.presentation)).toEqual(base.presentation)
  })

  it('rejects oversized snapshots before writing either storage', () => {
    const local = storage()
    const session = storage()
    const huge = setChatPaneTarget(
      createDefaultChatPaneState(),
      'pane-1',
      bindThread('x'.repeat(CHAT_TAB_STATE_MAX_BYTES)),
    )
    expect(saveChatPaneState('chat-1', huge, tabStorage(local, session))).toBe(false)
    expect(local.length).toBe(0)
    expect(session.length).toBe(0)

    // 已经越界的来源也不能被读回内存。
    const oversized = JSON.stringify(snapshotOf(huge))
    session.setItem(chatTabSessionStorageKey('chat-1'), oversized)
    expect(loadChatPaneState('chat-1', tabStorage(local, session)).state.layout).toBe('single')
  })

  it('simulates two independent tabs: new tab inherits once, stale refresh keeps its own snapshot', () => {
    const sharedLocal = storage()
    const tabASession = storage()
    const tabBSession = storage()

    let stateA = loadChatPaneState('chat-1', tabStorage(sharedLocal, tabASession)).state
    stateA = applyChatLayout(stateA, 'split-2')
    stateA = setChatPaneTarget(stateA, 'pane-1', bindThread('thread-a'))
    expect(saveChatPaneState('chat-1', stateA, tabStorage(sharedLocal, tabASession))).toBe(true)

    // 新 tab 没有 session 恢复索引，只继承一次最后状态，然后建立自己的索引。
    let stateB = loadChatPaneState('chat-1', tabStorage(sharedLocal, tabBSession)).state
    expect(stateB.layout).toBe('split-2')
    expect(chatPaneTarget(stateB, 'pane-1')).toEqual(bindThread('thread-a'))
    stateB = applyChatLayout(stateB, 'grid-4')
    stateB = setChatPaneTarget(stateB, 'pane-1', bindThread('thread-b'))
    expect(saveChatPaneState('chat-1', stateB, tabStorage(sharedLocal, tabBSession))).toBe(true)

    // tab A 刷新时 session 仍优先；不因另一个 tab 更新了 last state 而改布局或目标。
    const refreshedA = loadChatPaneState('chat-1', tabStorage(sharedLocal, tabASession)).state
    expect(refreshedA.layout).toBe('split-2')
    expect(chatPaneTarget(refreshedA, 'pane-1')).toEqual(bindThread('thread-a'))
  })

  it('keeps in-memory state usable and reports save failure when storage is unavailable', () => {
    const throwing: Storage = {
      length: 0,
      clear: () => {
        throw new Error('SecurityError')
      },
      getItem: () => {
        throw new Error('SecurityError')
      },
      key: () => null,
      removeItem: () => {
        throw new Error('SecurityError')
      },
      setItem: () => {
        throw new Error('QuotaExceededError')
      },
    }
    const loaded = loadChatPaneState('chat-1', tabStorage(throwing, throwing))
    expect(loaded.state.layout).toBe('single')
    expect(loaded.saved).toBe(false)
    expect(() => saveChatPaneState('chat-1', loaded.state, tabStorage(throwing, throwing))).not.toThrow()
    expect(saveChatPaneState('chat-1', loaded.state, tabStorage(throwing, throwing))).toBe(false)
    expect(loadChatPaneState('chat-1', { sessionStorage: null, localStorage: null }).saved).toBe(false)
  })

  it('serializes only the fixed versioned shape', () => {
    const store = storage()
    const state = setChatPaneTarget(createDefaultChatPaneState(), 'pane-2', bindThread('thread-2'))
    expect(saveChatPaneState('chat-1', state, tabStorage(store, store))).toBe(true)
    expect(readSnapshot(store, 'chat-1')).toEqual(snapshotOf(state))
  })
})

describe('Chat pane layout helpers', () => {
  it('keeps pane targets through layout changes and focuses only visible panes', () => {
    let state = setChatPaneTarget(createDefaultChatPaneState(), 'pane-2', bindThread('thread-2'))
    state = applyChatLayout(state, 'split-2')
    expect(visibleChatPanes(state).map((pane) => pane.id)).toEqual(['pane-1', 'pane-2'])
    expect(chatPaneTarget(state, 'pane-2')).toEqual(bindThread('thread-2'))
    state = focusPane(state, 'pane-2')
    expect(state.focusedPaneId).toBe('pane-2')
  })

  it('leaves state unchanged for idempotent layout and target commits', () => {
    const state = setChatPaneTarget(createDefaultChatPaneState(), 'pane-1', bindThread('thread-1'))
    expect(applyChatLayout(state, 'single')).toBe(state)
    expect(setChatPaneTarget(state, 'pane-1', bindThread('thread-1'))).toBe(state)
    expect(setChatPaneTarget(state, 'unknown', bindThread('thread-2'))).toBe(state)
    expect(focusPane(state, 'pane-4')).toBe(state)
  })

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
    const single = createDefaultChatPaneState()
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
    expect(revealChatPane(revealed, 'pane-2')).toBe(revealed)
    const nine = revealChatPane(revealed, 'pane-9')
    expect(nine.layout).toBe('grid-9')
    expect(revealChatPane(nine, 'unknown')).toBe(nine)
  })
})
