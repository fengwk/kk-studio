import { describe, expect, it } from 'vitest'
import {
  clearBoundPendingMessage,
  clearPaneTarget,
  clearPendingAcceptance,
  isBoundPendingMessageValue,
  isBoundTarget,
  isCommandContent,
  isDraftHistoryTarget,
  isForkSessionTarget,
  isImageTier,
  isNewThreadTarget,
  isNewSessionTarget,
  isPaneTarget,
  loadBoundPendingMessage,
  loadPaneTarget,
  loadPendingAcceptance,
  normalizePaneTarget,
  sameBatchRequestIdentity,
  saveBoundPendingMessage,
  samePaneTarget,
  savePaneTarget,
  savePendingAcceptance,
  targetIdentity,
  type BoundPendingMessage,
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
    expect(isPaneTarget({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
      threadName: 'branch-1',
    })).toBe(true)
    expect(isPaneTarget({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: 's1',
      sourceThreadId: 't-source',
      startEntryId: 'e1',
    })).toBe(true)
    expect(isPaneTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
    expect(isPaneTarget({ kind: 'unknown' })).toBe(false)
    expect(normalizePaneTarget(null)).toEqual({ kind: 'NEW_SESSION_DRAFT' })
    expect(normalizePaneTarget({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: ' s1 ',
      startEntryId: ' e1 ',
      threadName: 'branch-1',
    })).toEqual({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
      threadName: 'branch-1',
    })
    expect(normalizePaneTarget({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: ' s1 ',
      sourceThreadId: ' t-source ',
      startEntryId: ' e1 ',
    })).toEqual({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: 's1',
      sourceThreadId: 't-source',
      startEntryId: 'e1',
    })
    expect(normalizePaneTarget({ kind: 'BOUND_THREAD', threadId: '' }))
      .toEqual({ kind: 'NEW_SESSION_DRAFT' })
    expect(isNewSessionTarget({ kind: 'NEW_SESSION_DRAFT' })).toBe(true)
    expect(isNewThreadTarget({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
      threadName: 'branch-1',
    })).toBe(true)
    expect(isForkSessionTarget({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: 's1',
      sourceThreadId: 't-source',
      startEntryId: 'e1',
    })).toBe(true)
    expect(isDraftHistoryTarget({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
      threadName: 'branch-1',
    })).toBe(true)
    expect(isDraftHistoryTarget({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: 's1',
      sourceThreadId: 't-source',
      startEntryId: 'e1',
    })).toBe(true)
    expect(isDraftHistoryTarget({ kind: 'NEW_SESSION_DRAFT' })).toBe(false)
    expect(isBoundTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
  })

  it('requires a canonical branch name on the local draft and rejects unknown keys', () => {
    // 新建分支的名称是创建 target 的必需事实（进入 creationRequestHash），必须恰好
    // 规范化；不携带名称、名称非规范化或出现未知字段的本地 target 一律拒绝。
    for (const target of [
      { kind: 'NEW_SESSION_DRAFT', name: 'draft name' },
      { kind: 'NEW_SESSION_DRAFT', threadName: 'draft name' },
      { kind: 'NEW_SESSION_DRAFT', extra: true },
      { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1' },
      { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1', threadName: '' },
      { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1', threadName: '  spaced  ' },
      { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1', threadName: 'x'.repeat(257) },
      { kind: 'NEW_THREAD_DRAFT', sessionId: 's1', startEntryId: 'e1', threadName: 'ok', name: 'hidden' },
      { kind: 'FORK_SESSION_DRAFT', sessionId: 's1', startEntryId: 'e1' },
      { kind: 'FORK_SESSION_DRAFT', sessionId: 's1', sourceThreadId: '', startEntryId: 'e1' },
      { kind: 'FORK_SESSION_DRAFT', sessionId: 's1', sourceThreadId: 't1', startEntryId: 'e1', threadName: 'forbidden' },
      { kind: 'BOUND_THREAD', threadId: 't1', name: 'hidden' },
      { kind: 'BOUND_THREAD', threadId: 't1', sessionName: 'hidden' },
      { kind: 'ENTRY_DRAFT', sessionId: 's1', startEntryId: 'e1' },
    ]) {
      expect(isPaneTarget(target)).toBe(false)
    }
    expect(isPaneTarget({ kind: 'NEW_SESSION_DRAFT' })).toBe(true)
    expect(isPaneTarget({ kind: 'BOUND_THREAD', threadId: 't1' })).toBe(true)
    // 合法的 NEW_THREAD_DRAFT：命名与目标位置共同构成持久化形状。
    const draft: PaneTarget = {
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
      threadName: 'branch-1',
    }
    expect(isPaneTarget(draft)).toBe(true)
    expect(normalizePaneTarget(draft)).toEqual(draft)
    // 同一个 start entry 但不同名称是不同的本地草稿目标。
    expect(samePaneTarget(draft, { ...draft, threadName: 'branch-2' })).toBe(false)
    expect(targetIdentity(draft)).toBe('thread-draft:s1:e1:branch-1')
    const forkDraft: PaneTarget = {
      kind: 'FORK_SESSION_DRAFT',
      sessionId: 's1',
      sourceThreadId: 't-source',
      startEntryId: 'e1',
    }
    expect(samePaneTarget(forkDraft, { ...forkDraft })).toBe(true)
    expect(samePaneTarget(forkDraft, { ...forkDraft, startEntryId: 'e2' })).toBe(false)
    expect(targetIdentity(forkDraft)).toBe('fork-session-draft:s1:t-source:e1')
  })

  it('persists only target, while PendingAcceptance remains a separate sidecar', () => {
    const storage = memoryStorage()
    const owner = { type: 'CHAT' as const, chatId: 'chat-1' }
    const target: PaneTarget = {
      kind: 'NEW_THREAD_DRAFT',
      sessionId: 's1',
      startEntryId: 'e1',
      threadName: 'branch-1',
    }
    savePaneTarget(owner, 'pane-1', target, storage)
    expect(loadPaneTarget(owner, 'pane-1', storage)).toEqual(target)
    expect(storage.length).toBe(1)
  })

  it('persists an exact pending request independently from the target', () => {
    const storage = memoryStorage()
    const owner = { type: 'CHAT' as const, chatId: 'chat-1' }
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
            environmentName: null,
            goal: null,
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
        environmentName: null,
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

    const forkPending: PendingAcceptance = {
      ...pending,
      target: {
        kind: 'FORK_SESSION_DRAFT',
        sessionId: 'source-session',
        sourceThreadId: 'source-thread',
        startEntryId: 'cut-entry',
      },
      request: {
        owner,
        target: {
          type: 'NEW_FORKED_SESSION',
          sourceThreadId: 'source-thread',
          startEntryId: 'cut-entry',
          sessionId: 'forked-session',
          threadId: 'forked-thread',
          yoloEnabled: false,
        },
        commands: pending.request.commands,
      },
    }
    savePendingAcceptance(owner, 'pane-1', forkPending, storage)
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toEqual(forkPending)
    // NEW_FORKED_SESSION 绝不接受 rootSettings 或 threadName 等越界字段。
    storage.setItem(
      'kk-studio.agent-pane-acceptance.CHAT:chat-1:pane-1',
      JSON.stringify({
        ...forkPending,
        request: {
          ...forkPending.request,
          target: {
            ...forkPending.request.target,
            threadName: 'forbidden',
          },
        },
      }),
    )
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
  })

  it('isolates ISSUE_AGENT target storage by issue and agent', () => {
    // 测试意图：同一 Issue 的不同 Agent 不得共享绑定线程草稿。
    const owner = { type: 'ISSUE_AGENT' as const, issueId: 'issue-1', agentName: 'coder' }
    const storage = memoryStorage()
    savePaneTarget(owner, 'pane-coordinator', { kind: 'BOUND_THREAD', threadId: 'thread-p1' }, storage)
    expect(loadPaneTarget(owner, 'pane-coordinator', storage)).toEqual({
      kind: 'BOUND_THREAD',
      threadId: 'thread-p1',
    })
    expect(loadPaneTarget({ ...owner, agentName: 'reviewer' }, 'pane-coordinator', storage))
      .toEqual({ kind: 'NEW_SESSION_DRAFT' })
  })

  it('fails closed when browser storage is unavailable or contains malformed pending data', () => {
    const owner = { type: 'CHAT' as const, chatId: 'chat-1' }
    const malformedStorage = memoryStorage()
    malformedStorage.setItem(
      'kk-studio.agent-pane-acceptance.CHAT:chat-1:pane-1',
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
    const owner = { type: 'CHAT' as const, chatId: 'chat-1' }
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
            environmentName: null,
            goal: null,
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
        environmentName: null,
        yoloEnabled: false,
      },
      composerParts: [createTextPart('hello')],
      generation: 1,
      unknownOutcome: true,
    }
    storage.setItem(key, JSON.stringify(valid))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()
    // 测试意图：旧磁盘 DTO 即使其余字段完整，也绝不静默转换为新的 owner wire。
    storage.setItem(key, JSON.stringify({
      ...valid,
      owner: { type: 'CHAT', id: 'chat-1' },
      request: { ...valid.request, owner: { type: 'CHAT', id: 'chat-1' } },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

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
    // NEW_THREAD 创建 target 仍被 PendingAcceptance 接受（创建批次才需要 owner/target）。
    setRequest({
      ...validRequest,
      target: {
        type: 'NEW_THREAD',
        sessionId: 's1',
        startEntryId: 'e1',
        threadId: 't1',
        threadName: 'branch-1',
        yoloEnabled: false,
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()
    // 既有 Thread 的旧 THREAD target 不再属于创建批次，必须 fail-closed 拒绝。
    setRequest({
      ...validRequest,
      target: {
        type: 'THREAD',
        threadId: 't1',
        expectedHeadEntryId: 'e1',
        expectedNextCommandSequence: '1',
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
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
        contents: [{ type: 'ATTACHMENT', uploadId: 'u1' }],
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

    // 外层匹配、内层请求指向其他 owner 时仍拒绝恢复。
    setPending({
      owner,
      request: {
        ...validRequest,
        owner: { type: 'CHAT', chatId: 'chat-other' },
      },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    setPending({
      owner,
      request: {
        ...validRequest,
        owner: { type: 'ISSUE_AGENT', issueId: 'issue-1', agentName: 'coder' },
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
      owner: { type: 'CHAT', chatId: 'different-owner' },
    })
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
  })

  /**
   * 测试意图：锁定 environmentName 持久化 exact-shape 契约。
   * - rootSettings 与 branchDraft 必须严格拥有 exact keys（包含 environmentName），缺少 environmentName 必须 fail-closed 拒绝；
   * - environmentName 允许显式 null 或非空白字符串，空白字符串或未知字段必须被拒绝；
   * - SET_ENVIRONMENT 命令必须显式包含 environmentName（可为 null 或非空白字符串），缺少该属性或空白/非字符串必须被拒绝。
   */
  it('enforces exact-shape persistence for environmentName on branchDraft, rootSettings, and SET_ENVIRONMENT', () => {
    const owner = { type: 'CHAT' as const, chatId: 'chat-1' }
    const storage = memoryStorage()
    const key = 'kk-studio.agent-pane-acceptance.CHAT:chat-1:pane-1'
    const baseValid = {
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
            environmentName: null,
            goal: null,
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
        environmentName: null,
        yoloEnabled: false,
      },
      composerParts: [createTextPart('hello')],
      generation: 1,
      unknownOutcome: true,
    }

    // 1. explicit null accepted for rootSettings & branchDraft
    storage.setItem(key, JSON.stringify(baseValid))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()

    // 2. valid non-blank string environmentName accepted
    const withStringEnv = {
      ...baseValid,
      request: {
        ...baseValid.request,
        target: {
          ...baseValid.request.target,
          rootSettings: {
            ...baseValid.request.target.rootSettings,
            environmentName: 'prod-env',
          },
        },
      },
      branchDraft: {
        ...baseValid.branchDraft,
        environmentName: 'prod-env',
      },
    }
    storage.setItem(key, JSON.stringify(withStringEnv))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()

    // 3. rootSettings without environmentName is rejected (fail-closed)
    const rootSettingsWithoutEnv = {
      agentName: baseValid.request.target.rootSettings.agentName,
      model: baseValid.request.target.rootSettings.model,
      goal: null,
    }
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        target: {
          ...baseValid.request.target,
          rootSettings: rootSettingsWithoutEnv,
        },
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 4. rootSettings with blank environmentName is rejected
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        target: {
          ...baseValid.request.target,
          rootSettings: {
            ...baseValid.request.target.rootSettings,
            environmentName: '   ',
          },
        },
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 5. rootSettings with unknown extra key is rejected
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        target: {
          ...baseValid.request.target,
          rootSettings: {
            ...baseValid.request.target.rootSettings,
            unknownKey: 'invalid',
          },
        },
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 6. branchDraft without environmentName is rejected (fail-closed)
    const branchDraftWithoutEnv = {
      agentName: baseValid.branchDraft.agentName,
      model: baseValid.branchDraft.model,
      yoloEnabled: baseValid.branchDraft.yoloEnabled,
    }
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      branchDraft: branchDraftWithoutEnv,
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 7. branchDraft with blank environmentName is rejected
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      branchDraft: {
        ...baseValid.branchDraft,
        environmentName: '   ',
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 8. branchDraft with unknown extra key is rejected
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      branchDraft: {
        ...baseValid.branchDraft,
        unknownKey: 'invalid',
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 9. SET_ENVIRONMENT command: explicit null accepted
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        commands: [{
          type: 'SET_ENVIRONMENT',
          idempotencyKey: 'cmd-env-1',
          environmentName: null,
        }],
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()

    // 10. SET_ENVIRONMENT command: valid string accepted
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        commands: [{
          type: 'SET_ENVIRONMENT',
          idempotencyKey: 'cmd-env-2',
          environmentName: 'dev-cluster',
        }],
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()

    // 11. SET_ENVIRONMENT command: missing environmentName property rejected
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        commands: [{
          type: 'SET_ENVIRONMENT',
          idempotencyKey: 'cmd-env-3',
        }],
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 12. SET_ENVIRONMENT command: blank environmentName rejected
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        commands: [{
          type: 'SET_ENVIRONMENT',
          idempotencyKey: 'cmd-env-4',
          environmentName: '   ',
        }],
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 13. SET_ENVIRONMENT command: non-string non-null environmentName rejected
    storage.setItem(key, JSON.stringify({
      ...baseValid,
      request: {
        ...baseValid.request,
        commands: [{
          type: 'SET_ENVIRONMENT',
          idempotencyKey: 'cmd-env-5',
          environmentName: 12345,
        }],
      },
    }))
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()

    // 14. command variants reject unknown and cross-variant fields
    for (const command of [
      {
        type: 'SET_ENVIRONMENT',
        idempotencyKey: 'cmd-env-6',
        environmentName: null,
        model: { providerName: 'p', modelName: 'm', variant: 'v' },
      },
      {
        type: 'SET_AGENT',
        idempotencyKey: 'cmd-agent-1',
        agentName: 'assistant',
        unknownKey: true,
      },
      {
        type: 'USER_MESSAGE',
        idempotencyKey: 'cmd-user-1',
        contents: [{ type: 'TEXT', text: 'hello', uploadId: 'unexpected' }],
      },
    ]) {
      storage.setItem(key, JSON.stringify({
        ...baseValid,
        request: {
          ...baseValid.request,
          commands: [command],
        },
      }))
      expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    }

    // 15. environment names must satisfy the same canonical constraints as the backend
    for (const environmentName of [' surrounding ', 'contains/slash', 'x'.repeat(65)]) {
      storage.setItem(key, JSON.stringify({
        ...baseValid,
        branchDraft: {
          ...baseValid.branchDraft,
          environmentName,
        },
      }))
      expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
    }
  })

  describe('BoundPendingMessage durable local storage and imageTier validation', () => {
    const threadId = 't-image-test-1'
    const validPending: BoundPendingMessage = {
      threadId,
      kind: 'MESSAGE',
      goalText: null,
      unknownOutcome: false,
      targetDraft: {
        agentName: 'assistant',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        environmentName: null,
        yoloEnabled: false,
      },
      localDraft: [
        createTextPart('inspect image'),
        {
          type: 'attachment',
          partId: 'part-att-1',
          uploadId: 'upload-img-1',
          filename: 'photo.jpg',
          imageTier: '1080P',
        },
        createResourcePart('blob-res-1', 'chart.png', 'data:image/png;base64,aaa', 'ORIGINAL'),
      ],
      request: {
        // 既有 Thread 的未决写入只携带 CAS 游标与有序命令，绝不携带 owner/target/threadId。
        expectedHeadEntryId: 'h1',
        expectedNextCommandSequence: '2',
        commands: [
          {
            type: 'USER_MESSAGE',
            idempotencyKey: 'cmd-user-img-1',
            contents: [
              { type: 'TEXT', text: 'inspect image' },
              { type: 'ATTACHMENT', uploadId: 'upload-img-1', imageTier: '1080P' },
              {
                type: 'RESOURCE',
                blobId: 'blob-res-1',
                name: 'chart.png',
                preview: 'data:image/png;base64,aaa',
                imageTier: 'ORIGINAL',
              },
            ],
          },
        ],
      },
    }

    it('strictly validates imageTier enum and rejects unknown keys in command contents', () => {
      // 严格枚举：720P | 1080P | ORIGINAL
      expect(isImageTier('720P')).toBe(true)
      expect(isImageTier('1080P')).toBe(true)
      expect(isImageTier('ORIGINAL')).toBe(true)
      expect(isImageTier('480P')).toBe(false)
      expect(isImageTier('2K')).toBe(false)
      expect(isImageTier('4K')).toBe(false)
      expect(isImageTier('')).toBe(false)
      expect(isImageTier(null)).toBe(false)
      expect(isImageTier(undefined)).toBe(false)
      expect(isImageTier(1080)).toBe(false)

      // ATTACHMENT content validation
      expect(isCommandContent({ type: 'ATTACHMENT', uploadId: 'u1' })).toBe(true)
      expect(isCommandContent({ type: 'ATTACHMENT', uploadId: 'u1', imageTier: '720P' })).toBe(true)
      expect(isCommandContent({ type: 'ATTACHMENT', uploadId: 'u1', imageTier: '1080P' })).toBe(true)
      expect(isCommandContent({ type: 'ATTACHMENT', uploadId: 'u1', imageTier: 'ORIGINAL' })).toBe(true)
      expect(isCommandContent({ type: 'ATTACHMENT', uploadId: 'u1', imageTier: 'INVALID' })).toBe(false)
      expect(isCommandContent({ type: 'ATTACHMENT', uploadId: 'u1', extraKey: true })).toBe(false)
      expect(isCommandContent({ type: 'ATTACHMENT', uploadId: '' })).toBe(false)

      // RESOURCE content validation
      expect(isCommandContent({ type: 'RESOURCE', blobId: 'b1', name: 'img.png' })).toBe(true)
      expect(isCommandContent({ type: 'RESOURCE', blobId: 'b1', name: 'img.png', preview: 'data:image' })).toBe(true)
      expect(isCommandContent({ type: 'RESOURCE', blobId: 'b1', name: 'img.png', imageTier: 'ORIGINAL' })).toBe(true)
      expect(isCommandContent({
        type: 'RESOURCE',
        blobId: 'b1',
        name: 'img.png',
        preview: 'data:image',
        imageTier: '720P',
      })).toBe(true)
      expect(isCommandContent({ type: 'RESOURCE', blobId: 'b1', name: 'img.png', imageTier: 'BAD' })).toBe(false)
      expect(isCommandContent({ type: 'RESOURCE', blobId: 'b1', name: 'img.png', unknown: 1 })).toBe(false)
    })

    it('roundtrips bound pending message with imageTier and verifies fail-closed storage', () => {
      const storage = memoryStorage()
      expect(isBoundPendingMessageValue(validPending)).toBe(true)

      // 写入并 roundtrip 读取
      saveBoundPendingMessage(threadId, validPending, storage)
      const loaded = loadBoundPendingMessage(threadId, storage)
      expect(loaded).toBeDefined()
      // 刷新恢复时：因原本 unknownOutcome=false，load 自动将其标为 true，并回写 storage
      expect(loaded?.unknownOutcome).toBe(true)
      expect(loaded?.request.commands[0]?.contents).toHaveLength(3)

      // Fail-closed 存储校验：当存储写入验证失败时抛出错误
      const faultyStorage: Storage = {
        ...memoryStorage(),
        setItem: () => {},
        getItem: () => null, // 模拟存储失败写入不生效
      }
      expect(() => saveBoundPendingMessage(threadId, validPending, faultyStorage)).toThrow(
        /Persistence verification failed/,
      )

      // 非法 shape 拒绝写入并 fail-closed 抛错
      const invalidPending = {
        ...validPending,
        request: {
          ...validPending.request,
          commands: [
            {
              ...validPending.request.commands[0],
              unknownKey: 'forbidden',
            },
          ],
        },
      }
      expect(() => saveBoundPendingMessage(threadId, invalidPending as unknown as BoundPendingMessage, storage)).toThrow(
        /Invalid bound pending message shape/,
      )
    })

    it('preserves pending message across multi-pane when matchingRequest differs', () => {
      const storage = memoryStorage()
      saveBoundPendingMessage(threadId, validPending, storage)

      const differentRequest = {
        ...validPending.request,
        commands: [
          {
            ...validPending.request.commands[0]!,
            idempotencyKey: 'cmd-diff-pane-999',
          },
        ],
      }
      expect(sameBatchRequestIdentity(validPending.request, differentRequest)).toBe(false)

      // 另一个 pane 完成/清理时传入不同 request 身份：不得清理当前 pending，返回 false
      const clearedDiff = clearBoundPendingMessage(threadId, differentRequest, storage)
      expect(clearedDiff).toBe(false)
      expect(loadBoundPendingMessage(threadId, storage)).not.toBeNull()

      // 匹配当前 request 身份：正常清理并返回 true
      const clearedSame = clearBoundPendingMessage(threadId, validPending.request, storage)
      expect(clearedSame).toBe(true)
      expect(loadBoundPendingMessage(threadId, storage)).toBeNull()
    })

    it('rejects overwriting an existing pending message with a different request identity', () => {
      const storage = memoryStorage()
      saveBoundPendingMessage(threadId, validPending, storage)

      const conflictingPending: BoundPendingMessage = {
        ...validPending,
        request: {
          ...validPending.request,
          commands: [
            {
              type: 'USER_MESSAGE',
              idempotencyKey: 'cmd-conflict-key-different',
              contents: [{ type: 'TEXT', text: 'conflict attempt' }],
            },
          ],
        },
      }

      // 拒绝覆盖并抛出 Conflict 错误
      expect(() => saveBoundPendingMessage(threadId, conflictingPending, storage)).toThrow(
        /Conflict: another pending message exists for this thread/,
      )

      // 原 pending 状态完好无损
      const preserved = loadBoundPendingMessage(threadId, storage)
      expect(preserved?.request.commands[0]?.idempotencyKey).toBe('cmd-user-img-1')

      // 相同 request 身份（例如更新 unknownOutcome）允许正常更新
      const updatedUnknown: BoundPendingMessage = {
        ...validPending,
        unknownOutcome: true,
      }
      expect(() => saveBoundPendingMessage(threadId, updatedUnknown, storage)).not.toThrow()
    })

    it('safely handles null storage and throwing storage without uncaught crashes', () => {
      const throwingStorage = {
        getItem: () => { throw new Error('SecurityError') },
        setItem: () => { throw new Error('SecurityError') },
        removeItem: () => { throw new Error('SecurityError') },
      }
      expect(loadBoundPendingMessage(threadId, throwingStorage)).toBeNull()
      expect(() => saveBoundPendingMessage(threadId, validPending, throwingStorage)).toThrow(/SecurityError/)
      expect(clearBoundPendingMessage(threadId, validPending.request, throwingStorage)).toBe(false)
    })
  })
})
