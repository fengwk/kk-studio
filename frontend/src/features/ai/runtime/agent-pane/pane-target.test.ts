import { describe, expect, it } from 'vitest'
import {
  clearPaneTarget,
  clearPendingAcceptance,
  isBoundTarget,
  isEntryTarget,
  isNewSessionTarget,
  isPaneTarget,
  loadPaneTarget,
  loadPendingAcceptance,
  normalizePaneTarget,
  savePaneTarget,
  savePendingAcceptance,
  type PendingAcceptance,
  type PaneTarget,
} from '@/features/ai/runtime/agent-pane'
import { createTextPart } from '@/features/ai/composer/composer-parts'

function memoryStorage(): Storage {
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

describe('PaneTarget durable-local FSM', () => {
  it('accepts only the three target states and normalizes malformed storage to NEW_SESSION_DRAFT', () => {
    expect(isPaneTarget({ kind: 'NEW_SESSION_DRAFT' })).toBe(true)
    expect(isPaneTarget({ kind: 'ENTRY_DRAFT', sessionId: 's1', startEntryId: 'e1' })).toBe(true)
    expect(isPaneTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
    expect(isPaneTarget({ kind: 'unknown' })).toBe(false)
    expect(normalizePaneTarget(null)).toEqual({ kind: 'NEW_SESSION_DRAFT' })
    expect(normalizePaneTarget({
      kind: 'ENTRY_DRAFT',
      sessionId: ' s1 ',
      startEntryId: ' e1 ',
    })).toEqual({
      kind: 'ENTRY_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
    })
    expect(normalizePaneTarget({ kind: 'BOUND_THREAD', threadId: '' }))
      .toEqual({ kind: 'NEW_SESSION_DRAFT' })
    expect(isNewSessionTarget({ kind: 'NEW_SESSION_DRAFT' })).toBe(true)
    expect(isEntryTarget({ kind: 'ENTRY_DRAFT', sessionId: 's1', startEntryId: 'e1' })).toBe(true)
    expect(isBoundTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
  })

  it('persists only target, while PendingAcceptance remains a separate sidecar', () => {
    const storage = memoryStorage()
    const owner = { type: 'CHAT' as const, id: 'chat-1' }
    const target: PaneTarget = { kind: 'ENTRY_DRAFT', sessionId: 's1', startEntryId: 'e1' }
    savePaneTarget(owner, 'pane-1', target, storage)
    expect(loadPaneTarget(owner, 'pane-1', storage)).toEqual(target)
    expect(storage.length).toBe(1)
  })

  it('persists an exact pending request independently from the target', () => {
    const storage = memoryStorage()
    const owner = { type: 'CHAT' as const, id: 'chat-1' }
    const pending: PendingAcceptance = {
      owner,
      target: { kind: 'NEW_SESSION_DRAFT' },
      request: {
        owner,
        target: {
          kind: 'NEW_SESSION',
          sessionId: 's1',
          threadId: 't1',
          rootSettings: {
            environment: null,
            agentName: 'assistant',
            model: { providerName: 'p', modelName: 'm', variant: 'v' },
            activeTools: [],
          },
          yoloEnabled: false,
        },
        commands: [{
          type: 'USER_MESSAGE',
          clientCommandId: 'c1',
          contents: [{ type: 'TEXT', text: 'hello' }],
        }],
      },
      branchDraft: {
        environment: null,
        agentName: 'assistant',
        model: { providerName: 'p', modelName: 'm', variant: 'v' },
        activeTools: [],
        yoloEnabled: false,
      },
      composerParts: [createTextPart('hello')],
      generation: 3,
      unknownOutcome: true,
    }
    savePendingAcceptance(owner, 'pane-1', pending, storage)
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toEqual(pending)
    expect(storage.length).toBe(1)
    clearPendingAcceptance(owner, 'pane-1', storage)
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    clearPaneTarget(owner, 'pane-1', storage)
    expect(loadPaneTarget(owner, 'pane-1', storage)).toEqual({ kind: 'NEW_SESSION_DRAFT' })
  })

  it('fails closed when browser storage is unavailable or contains malformed pending data', () => {
    const owner = { type: 'CANVAS' as const, id: 'canvas-1' }
    const malformedStorage = memoryStorage()
    malformedStorage.setItem(
      'kk-studio.agent-pane-acceptance.CANVAS:canvas-1:pane-1',
      JSON.stringify({ target: { kind: 'BOUND_THREAD', threadId: 't1' }, request: {}}),
    )
    expect(loadPendingAcceptance(owner, 'pane-1', malformedStorage)).toBeNull()
    const failingStorage: Storage = {
      get length() {
        return 0
      },
      clear: () => {
        throw new Error('storage unavailable')
      },
      getItem: () => {
        throw new Error('storage unavailable')
      },
      key: () => null,
      removeItem: () => {
        throw new Error('storage unavailable')
      },
      setItem: () => {
        throw new Error('storage unavailable')
      },
    }
    expect(loadPaneTarget(owner, 'pane-1', failingStorage)).toEqual({ kind: 'NEW_SESSION_DRAFT' })
    expect(loadPendingAcceptance(owner, 'pane-1', failingStorage)).toBeNull()
    expect(() => savePaneTarget(owner, 'pane-1', { kind: 'BOUND_THREAD', threadId: 't1' }, failingStorage)).not.toThrow()
    expect(() => clearPaneTarget(owner, 'pane-1', failingStorage)).not.toThrow()
    expect(() => clearPendingAcceptance(owner, 'pane-1', failingStorage)).not.toThrow()
  })
})
