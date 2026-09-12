import { describe, expect, it } from 'vitest'
import {
  clearPaneTarget,
  clearPendingAcceptance,
  isBoundTarget,
  isNewThreadTarget,
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
import {
  createResourcePart,
  createTextPart,
} from '@/features/ai/composer/composer-parts'

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
    expect(isPaneTarget({ kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1' })).toBe(true)
    expect(isPaneTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
    expect(isPaneTarget({ kind: 'unknown' })).toBe(false)
    expect(normalizePaneTarget(null)).toEqual({ kind: 'NEW_SESSION_DRAFT' })
    expect(normalizePaneTarget({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: ' s1 ',
      startEntryId: ' e1 ',
    })).toEqual({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
    })
    expect(normalizePaneTarget({ kind: 'BOUND_THREAD', threadId: '' }))
      .toEqual({ kind: 'NEW_SESSION_DRAFT' })
    expect(isNewSessionTarget({ kind: 'NEW_SESSION_DRAFT' })).toBe(true)
    expect(isNewThreadTarget({ kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1' })).toBe(true)
    expect(isBoundTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
  })

  it('rejects hidden creation names and the legacy ENTRY_DRAFT shape via exact own keys', () => {
    // 本地持久化绝不允许创建前草稿/创建 target 携带名称或任何未知字段。
    for (const target of [
      { kind: 'NEW_SESSION_DRAFT', name: 'draft name' },
      { kind: 'NEW_SESSION_DRAFT', threadName: 'draft name' },
      { kind: 'NEW_SESSION_DRAFT', extra: true },
      { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1', threadName: 'hidden' },
      { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1', name: 'hidden' },
      { kind: 'BOUND_THREAD', threadId: 't1', name: 'hidden' },
      { kind: 'BOUND_THREAD', threadId: 't1', sessionName: 'hidden' },
      { kind: 'ENTRY_DRAFT', sessionId: 's1', startEntryId: 'e1' },
    ]) {
      expect(isPaneTarget(target)).toBe(false)
    }
    expect(isPaneTarget({ kind: 'NEW_SESSION_DRAFT' })).toBe(true)
    expect(isPaneTarget({ kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1' })).toBe(true)
    expect(isPaneTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
  })

  it('persists only target, while PendingAcceptance remains a separate sidecar', () => {
    const storage = memoryStorage()
    const owner = { type: 'CHAT' as const, id: 'chat-1' }
    const target: PaneTarget = { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1' }
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
          type: 'NEW_SESSION',
          sessionId: 's1',
          threadId: 't1',
          rootSettings: {
            agentName: 'assistant',
            model: { providerName: 'p', modelName: 'm', variant: 'v' },
          },
          yoloEnabled: false,
        },
        commands: [{
          type: 'USER_MESSAGE',
          idempotencyKey: 'c1',
          contents: [{ type: 'TEXT', text: 'hello' }],
        }],
      },
      branchDraft: {
        agentName: 'assistant',
        model: { providerName: 'p', modelName: 'm', variant: 'v' },
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

  it('rejects pending storage unless owner, commands, generation, and outcome are valid', () => {
    const owner = { type: 'CHAT' as const, id: 'chat-1' }
    const storage = memoryStorage()
    const key = 'kk-studio.agent-pane-acceptance.CHAT:chat-1:pane-1'
    const valid = {
      owner,
      target: { kind: 'NEW_SESSION_DRAFT' },
      request: {
        owner,
        target: {
          type: 'NEW_SESSION',
          sessionId: 's1',
          threadId: 't1',
          rootSettings: {
            agentName: 'assistant',
            model: { providerName: 'p', modelName: 'm', variant: 'v' },
          },
          yoloEnabled: false,
        },
        commands: [{
          type: 'USER_MESSAGE',
          idempotencyKey: 'c1',
          contents: [{ type: 'TEXT', text: 'hello' }],
        }],
      },
      branchDraft: {
        agentName: 'assistant',
        model: { providerName: 'p', modelName: 'm', variant: 'v' },
        yoloEnabled: false,
      },
      composerParts: [createTextPart('hello')],
      generation: 1,
      unknownOutcome: true,
    }
    storage.setItem(key, JSON.stringify(valid))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()

    for (const [field, value] of [
      ['owner', { type: 'CHAT' }],
      ['request', { ...valid.request, commands: [] }],
      ['generation', -1],
      ['unknownOutcome', 'true'],
    ] as const) {
      storage.setItem(key, JSON.stringify({ ...valid, [field]: value }))
      expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    }
    storage.setItem(key, JSON.stringify({
      ...valid,
      request: {
        ...valid.request,
        target: { ...valid.request.target, ...{ ['kind']: 'NEW_SESSION' } },
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // exact own-key 拒绝隐藏创建名称：creation target 绝不携带 name/sessionName/threadName。
    for (const extra of [
      { name: 'hidden session name' },
      { sessionName: 'hidden' },
      { threadName: 'hidden' },
      { extra: 'field' },
    ]) {
      storage.setItem(key, JSON.stringify({
        ...valid,
        request: {
          ...valid.request,
          target: { ...valid.request.target, ...extra },
        },
      }))
      expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    }

    const setRequest = (request: object) => {
      storage.setItem(key, JSON.stringify({ ...valid, request }))
    }
    const setPending = (changes: object) => {
      storage.setItem(key, JSON.stringify({ ...valid, ...changes }))
    }
    const validRequest = valid.request
    for (const target of [
      {
        type: 'NEW_THREAD',
        sessionId: 's1',
        startEntryId: 'e1',
        threadId: 't1',
        yoloEnabled: false,
      },
      {
        type: 'THREAD',
        threadId: 't1',
        expectedHeadEntryId: 'e1',
        expectedNextCommandSequence: '1',
      },
    ]) {
      setRequest({ ...validRequest, target })
      expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()
    }
    for (const command of [
      { type: 'SET_AGENT', idempotencyKey: 'c1', agentName: 'assistant' },
      {
        type: 'SET_MODEL',
        idempotencyKey: 'c1',
        model: { providerName: 'p', modelName: 'm', variant: 'v' },
      },
      {
        type: 'USER_MESSAGE',
        idempotencyKey: 'c1',
        contents: [{ type: 'ATTACHMENT', uploadId: 'u1', filename: 'file.txt' }],
      },
    ]) {
      setRequest({ ...validRequest, commands: [command] })
      expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()
    }

    // 验证拒绝未知命令类型（持久化形状只接受现存 command type 集合）
    for (const rejectedCommand of [
      { type: 'UNKNOWN_COMMAND', idempotencyKey: 'c1' },
      { type: 'UNKNOWN_COMMAND', idempotencyKey: 'c1', value: null },
      { type: 'UNKNOWN_COMMAND', idempotencyKey: 'c1', value: 'proj/sub' },
      { type: 'UNKNOWN_COMMAND', idempotencyKey: 'c1', value: '.', environment: 'local' },
    ]) {
      setRequest({ ...validRequest, commands: [rejectedCommand] })
      expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    }
    setRequest({
      ...validRequest,
      commands: [{
        type: 'USER_MESSAGE',
        idempotencyKey: 'c1',
        contents: [{ type: 'TEXT', text: 1 }],
      }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      commands: [{
        type: 'USER_MESSAGE',
        idempotencyKey: 'c1',
        contents: [{ type: 'ATTACHMENT', uploadId: '', filename: 'file.txt' }],
      }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      commands: [{ type: 'UNKNOWN', idempotencyKey: 'c1' }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      commands: [{ type: 'SET_AGENT', idempotencyKey: 'c1', agentName: ' ' }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      target: {
        type: 'NEW_SESSION',
        sessionId: 's1',
        threadId: 't1',
        rootSettings: { ...validRequest.target.rootSettings, model: null },
        yoloEnabled: false,
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    // exact own-key 拒绝 rootSettings 上的未知字段
    setRequest({
      ...validRequest,
      target: {
        type: 'NEW_SESSION',
        sessionId: 's1',
        threadId: 't1',
        rootSettings: { ...validRequest.target.rootSettings, unexpectedSetting: '.' },
        yoloEnabled: false,
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      target: { type: 'NEW_THREAD', sessionId: 's1', startEntryId: '', threadId: 't1', yoloEnabled: false },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      target: {
        type: 'THREAD',
        threadId: 't1',
        expectedHeadEntryId: '',
        expectedNextCommandSequence: '1',
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setPending({ branchDraft: { ...valid.branchDraft, model: null } })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    // exact own-key 拒绝 branchDraft 上的未知字段
    setPending({ branchDraft: { ...valid.branchDraft, unexpectedSetting: '.' } })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setPending({
      composerParts: [{ partId: 'p1', type: 'attachment', uploadId: 'u1', filename: 'file.txt' }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()
    const resource = createResourcePart('blob-1', 'resource.txt', 'preview')
    setPending({
      request: {
        ...validRequest,
        commands: [{
          type: 'USER_MESSAGE',
          idempotencyKey: 'c1',
          contents: [{
            type: 'RESOURCE',
            blobId: 'blob-1',
            name: 'resource.txt',
            preview: 'preview',
          }],
        }],
      },
      composerParts: [resource],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()
    setPending({
      request: {
        ...validRequest,
        commands: [{
          type: 'USER_MESSAGE',
          idempotencyKey: 'c1',
          contents: [{ type: 'RESOURCE', blobId: 'blob-1', name: '' }],
        }],
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setPending({
      composerParts: [{ ...resource, blobId: '' }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setPending({
      composerParts: [{ partId: 'p1', type: 'attachment', uploadId: '', filename: 'file.txt' }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setPending({ owner: { type: 'OTHER', id: 'chat-1' } })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 验证外层 pending.owner 匹配但 request.owner 指向其他 Chat/Canvas 时 fail-closed 拒绝返回 null
    setPending({
      owner,
      request: {
        ...validRequest,
        owner: { type: 'CHAT', id: 'chat-other' },
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    setPending({
      owner,
      request: {
        ...validRequest,
        owner: { type: 'CANVAS', id: 'canvas-1' },
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    // 持久化 validator 对 branchDraft 与 rootSettings 执行 exact-shape 校验。
    setPending({
      branchDraft: {
        ...valid.branchDraft,
        unexpected: true,
      },
      request: validRequest,
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    setPending({
      branchDraft: valid.branchDraft,
      request: {
        ...validRequest,
        target: {
          ...validRequest.target,
          rootSettings: {
            ...validRequest.target.rootSettings,
            unexpected: true,
          },
        },
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    setPending({
      branchDraft: {
        ...valid.branchDraft,
        unexpected: true,
      },
      request: {
        ...validRequest,
        target: {
          ...validRequest.target,
          rootSettings: {
            ...validRequest.target.rootSettings,
            unexpected: true,
          },
        },
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({ ...validRequest, target: null })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({ ...validRequest, target: { type: 1 } })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({ ...validRequest, target: { type: 'UNKNOWN' } })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      commands: [{ type: 'USER_MESSAGE', idempotencyKey: 'c1', contents: [null] }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      commands: [{ type: 'SET_AGENT', idempotencyKey: '', agentName: 'assistant' }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setRequest({
      ...validRequest,
      commands: [{ type: 'SET_AGENT', agentName: 'assistant' }],
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setPending({ composerParts: [{}] })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    setPending({
      owner: { type: 'CHAT', id: 'different-owner' },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
  })
})
