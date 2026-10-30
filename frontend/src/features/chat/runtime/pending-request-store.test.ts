import { describe, expect, it } from 'vitest'
import type {
  HarnessCommandBatchDTO,
  HarnessThreadCommandBatchDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import {
  clearBoundPendingMessage,
  clearPendingAcceptance,
  inspectPendingAcceptanceSlot,
  isBoundPendingMessageValue,
  isPendingAcceptanceValue,
  loadBoundPendingMessage,
  loadPendingAcceptance,
  saveBoundPendingMessage,
  savePendingAcceptance,
  sameBatchRequestIdentity,
  samePendingAcceptanceIdentity,
  type BoundPendingMessage,
  type PendingAcceptance,
} from './pending-request-store'

function memoryStorage(): Storage {
  const store = new Map<string, string>()
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => store.set(key, value),
    removeItem: (key: string) => { store.delete(key) },
    clear: () => store.clear(),
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    get length() {
      return store.size
    },
  }
}

describe('pending-request-store', () => {
  const owner: AgentRuntimeOwnerDTO = { type: 'CHAT', chatId: 'chat-001' }
  const threadId = '11111111-2222-4333-8444-555555555555'

  const acceptanceRequest: HarnessCommandBatchDTO = {
    owner,
    target: {
      type: 'NEW_SESSION',
      sessionId: 'sess-1',
      threadId: 'thread-1',
      rootSettings: {
        agentName: 'assistant',
        model: { modelName: 'MiniMax', variant: 'default' },
        environmentName: null,
        goal: null,
      },
      yoloEnabled: false,
    },
    commands: [
      {
        type: 'USER_MESSAGE',
        idempotencyKey: 'cmd-acc-1',
        contents: [{ type: 'TEXT', text: 'initial acceptance prompt' }],
      },
    ],
  }

  const pendingAcceptance: PendingAcceptance = {
    owner,
    target: { kind: 'NEW_SESSION_DRAFT' },
    request: acceptanceRequest,
    branchDraft: {
      agentName: 'assistant',
      model: { modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
      yoloEnabled: false,
    },
    composerParts: [
      { partId: 'part-1', type: 'text', text: 'initial acceptance prompt' },
    ],
    generation: 1,
    unknownOutcome: true,
  }

  const boundBatchRequest: HarnessThreadCommandBatchDTO = {
    commands: [
      {
        type: 'USER_MESSAGE',
        idempotencyKey: 'cmd-bound-1',
        contents: [{ type: 'TEXT', text: 'bound message content' }],
      },
    ],
  }

  const boundPendingMessage: BoundPendingMessage = {
    threadId,
    request: boundBatchRequest,
    targetDraft: {
      agentName: 'assistant',
      model: { modelName: 'MiniMax', variant: 'default' },
      environmentName: null,
      yoloEnabled: false,
    },
    kind: 'MESSAGE',
    localDraft: [
      { partId: 'part-2', type: 'text', text: 'bound message content' },
    ],
    goalText: null,
    unknownOutcome: true,
  }

  it('正确识别与校验 acceptance 和 bound message 两种 discriminated variant', () => {
    expect(isPendingAcceptanceValue(pendingAcceptance)).toBe(true)
    expect(isBoundPendingMessageValue(boundPendingMessage)).toBe(true)

    // 交叉校验均应拒绝
    expect(isPendingAcceptanceValue(boundPendingMessage)).toBe(false)
    expect(isBoundPendingMessageValue(pendingAcceptance)).toBe(false)
  })

  it('acceptance 槽位隔离持久化与精确身份对比', () => {
    const storage = memoryStorage()
    expect(savePendingAcceptance(owner, 'pane-1', pendingAcceptance, storage)).toBe(true)

    const loaded = loadPendingAcceptance(owner, 'pane-1', storage)
    expect(loaded).toEqual(pendingAcceptance)
    expect(samePendingAcceptanceIdentity(loaded!, pendingAcceptance)).toBe(true)

    // 不同 owner 或不同 pane 隔离
    const otherOwner: AgentRuntimeOwnerDTO = { type: 'CHAT', chatId: 'chat-002' }
    expect(loadPendingAcceptance(otherOwner, 'pane-1', storage)).toBeNull()
    expect(loadPendingAcceptance(owner, 'pane-2', storage)).toBeNull()

    // 禁止同一槽位被不同请求身份覆盖（防重复拥有/冲突）
    const conflictingPending: PendingAcceptance = {
      ...pendingAcceptance,
      request: {
        ...acceptanceRequest,
        commands: [
          {
            type: 'USER_MESSAGE',
            idempotencyKey: 'cmd-acc-diff',
            contents: [{ type: 'TEXT', text: 'different' }],
          },
        ],
      },
    }
    expect(savePendingAcceptance(owner, 'pane-1', conflictingPending, storage)).toBe(false)

    // 只有匹配 expected 身份才允许清除
    expect(clearPendingAcceptance(owner, 'pane-1', conflictingPending, storage)).toBe(false)
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).not.toBeNull()

    expect(clearPendingAcceptance(owner, 'pane-1', pendingAcceptance, storage)).toBe(true)
    expect(loadPendingAcceptance(owner, 'pane-1', storage)).toBeNull()
  })

  it('bound message 槽位独立持久化、冲突防护与 unknown 重放恢复', () => {
    const storage = memoryStorage()
    saveBoundPendingMessage(threadId, boundPendingMessage, storage)

    const loaded = loadBoundPendingMessage(threadId, storage)
    expect(loaded).toEqual(boundPendingMessage)

    // 禁止不同 request 身份覆盖既有未决记录
    const conflictingBound: BoundPendingMessage = {
      ...boundPendingMessage,
      request: {
        ...boundBatchRequest,
        commands: [
          {
            type: 'USER_MESSAGE',
            idempotencyKey: 'cmd-bound-conflict',
            contents: [{ type: 'TEXT', text: 'other' }],
          },
        ],
      },
    }
    expect(() => saveBoundPendingMessage(threadId, conflictingBound, storage)).toThrow(
      /Conflict: another pending message exists for this thread/,
    )

    // 刷新时未标 unknownOutcome 的记录自动补齐 unknownOutcome: true
    const unacknowledged: BoundPendingMessage = {
      ...boundPendingMessage,
      unknownOutcome: false,
    }
    storage.setItem(`kk-studio.agent-thread-pending.${threadId}`, JSON.stringify(unacknowledged))
    const recovered = loadBoundPendingMessage(threadId, storage)
    expect(recovered?.unknownOutcome).toBe(true)

    // 清除时比对 matchingRequest
    expect(clearBoundPendingMessage(threadId, conflictingBound.request, storage)).toBe(false)
    expect(loadBoundPendingMessage(threadId, storage)).not.toBeNull()

    expect(clearBoundPendingMessage(threadId, boundPendingMessage.request, storage)).toBe(true)
    expect(loadBoundPendingMessage(threadId, storage)).toBeNull()
  })

  it('同一 pending 请求禁止重复拥有 / 重复持久化验证', () => {
    const storage = memoryStorage()

    // 1. 同一 bound pending 请求重复保存同一 identity 是幂等的
    saveBoundPendingMessage(threadId, boundPendingMessage, storage)
    expect(() => saveBoundPendingMessage(threadId, boundPendingMessage, storage)).not.toThrow()

    // 2. 但异请求身份必须抛出 Conflict 错误
    const anotherRequest = {
      ...boundPendingMessage,
      request: {
        ...boundBatchRequest,
        commands: [
          {
            type: 'USER_MESSAGE' as const,
            idempotencyKey: 'cmd-bound-unique-2',
            contents: [{ type: 'TEXT' as const, text: 'second' }],
          },
        ],
      },
    }
    expect(() => saveBoundPendingMessage(threadId, anotherRequest, storage)).toThrow(/Conflict/)

    // 3. 槽位状态检查
    const inspected = inspectPendingAcceptanceSlot(owner, 'pane-empty', storage)
    expect(inspected.status).toBe('empty')
  })

  it('sameBatchRequestIdentity 准确比对 commands 幂等键', () => {
    const reqA = { commands: [{ idempotencyKey: 'k1' }, { idempotencyKey: 'k2' }] }
    const reqB = { commands: [{ idempotencyKey: 'k1' }, { idempotencyKey: 'k2' }] }
    const reqC = { commands: [{ idempotencyKey: 'k1' }] }
    const reqD = { commands: [{ idempotencyKey: 'k1' }, { idempotencyKey: 'k3' }] }

    expect(sameBatchRequestIdentity(reqA, reqB)).toBe(true)
    expect(sameBatchRequestIdentity(reqA, reqC)).toBe(false)
    expect(sameBatchRequestIdentity(reqA, reqD)).toBe(false)
  })

  it('acceptance 目标匹配过滤、损坏数据与不可用 storage 防护', () => {
    const storage = memoryStorage()
    savePendingAcceptance(owner, 'pane-1', pendingAcceptance, storage)

    // expectedTarget 匹配时返回，不匹配时返回 null
    const matched = loadPendingAcceptance(owner, 'pane-1', storage, { kind: 'NEW_SESSION_DRAFT' })
    expect(matched).not.toBeNull()

    const mismatched = loadPendingAcceptance(owner, 'pane-1', storage, { kind: 'BOUND_THREAD', threadId: 'other-id' })
    expect(mismatched).toBeNull()

    // 槽位损坏（坏 JSON）
    storage.setItem(`kk-studio.agent-pane-acceptance.${owner.type === 'CHAT' ? `CHAT:${owner.chatId}` : ''}:pane-1`, 'not-json')
    const corruptedSlot = inspectPendingAcceptanceSlot(owner, 'pane-1', storage)
    expect(corruptedSlot.status).toBe('invalid')

    // 尝试写入损坏槽位直接拒绝
    expect(savePendingAcceptance(owner, 'pane-1', pendingAcceptance, storage)).toBe(false)

    // 存储不可用（抛错）
    const throwingStorage: Storage = {
      getItem: () => { throw new Error('QuotaExceeded') },
      setItem: () => { throw new Error('QuotaExceeded') },
      removeItem: () => { throw new Error('QuotaExceeded') },
      clear: () => {},
      key: () => null,
      length: 0,
    }
    expect(inspectPendingAcceptanceSlot(owner, 'pane-1', throwingStorage).status).toBe('unavailable')
    expect(savePendingAcceptance(owner, 'pane-1', pendingAcceptance, throwingStorage)).toBe(false)
    expect(clearPendingAcceptance(owner, 'pane-1', pendingAcceptance, throwingStorage)).toBe(false)
  })

  it('bound message 边界校验、损坏清理与回读验证', () => {
    const storage = memoryStorage()

    // 1. threadId 为空或非法 shape
    expect(() => saveBoundPendingMessage('', boundPendingMessage, storage)).toThrow(/threadId is required/)
    expect(() => saveBoundPendingMessage(threadId, {} as unknown as BoundPendingMessage, storage)).toThrow(/Invalid bound pending message shape/)

    // 2. loadBoundPendingMessage 遇到非法 JSON 或不匹配数据自动清理
    const key = `kk-studio.agent-thread-pending.${threadId}`
    storage.setItem(key, JSON.stringify({ invalid: 'shape', threadId }))
    expect(loadBoundPendingMessage(threadId, storage)).toBeNull()
    expect(storage.getItem(key)).toBeNull()

    // 3. 回读校验失败防护
    const faultyStorage: Storage = {
      ...storage,
      getItem: (k: string) => {
        if (k === key) return 'tampered'
        return storage.getItem(k)
      },
    }
    expect(() => saveBoundPendingMessage(threadId, boundPendingMessage, faultyStorage)).toThrow(
      /Persistence verification failed/,
    )

    // 4. clear 遇到坏数据或存储异常
    storage.setItem(key, 'bad-json')
    expect(clearBoundPendingMessage(threadId, boundPendingMessage.request, storage)).toBe(true)
    // key 不存在时 clear 匹配请求直接返回 true
    expect(clearBoundPendingMessage('non-existent-thread', boundPendingMessage.request, storage)).toBe(true)

    const throwingStorage: Storage = {
      getItem: () => { throw new Error('SecurityError') },
      setItem: () => { throw new Error('SecurityError') },
      removeItem: () => { throw new Error('SecurityError') },
      clear: () => {},
      key: () => null,
      length: 0,
    }
    expect(clearBoundPendingMessage(threadId, undefined, throwingStorage)).toBe(false)
  })

  it('默认 globalThis.localStorage 路径与空键防线', () => {
    localStorage.clear()

    // 默认 storage 路径
    expect(savePendingAcceptance(owner, 'pane-default', pendingAcceptance)).toBe(true)
    expect(loadPendingAcceptance(owner, 'pane-default')).not.toBeNull()
    expect(clearPendingAcceptance(owner, 'pane-default', pendingAcceptance)).toBe(true)

    saveBoundPendingMessage(threadId, boundPendingMessage)
    expect(loadBoundPendingMessage(threadId)).not.toBeNull()
    expect(clearBoundPendingMessage(threadId)).toBe(true)

    // 空 threadId
    expect(loadBoundPendingMessage('')).toBeNull()
    expect(clearBoundPendingMessage('')).toBe(true)

    // 不匹配的 owner 保存拒绝
    const mismatchOwner: AgentRuntimeOwnerDTO = { type: 'CHAT', chatId: 'chat-other' }
    expect(savePendingAcceptance(mismatchOwner, 'pane-1', pendingAcceptance, memoryStorage())).toBe(false)
  })
})
