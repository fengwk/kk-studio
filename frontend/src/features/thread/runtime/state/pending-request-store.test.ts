import { describe, expect, it } from 'vitest'
import type { HarnessThreadCommandBatchDTO } from '@/shared/api/contracts/ai-runtime'
import { createBuiltinExecution } from '@/shared/api/contracts/ai-runtime'
import {
  clearBoundPendingMessage,
  loadBoundPendingMessage,
  saveBoundPendingMessage,
  sameBatchRequestIdentity,
  type BoundPendingMessage,
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
  const threadId = '11111111-2222-4333-8444-555555555555'

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
      execution: createBuiltinExecution('MiniMax', 'default'),
      environmentName: null,
      yoloEnabled: false,
    },
    localDraft: [
      { partId: 'part-2', type: 'text', text: 'bound message content' },
    ],
    unknownOutcome: true,
  }

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
    const anotherRequest: BoundPendingMessage = {
      ...boundPendingMessage,
      request: {
        ...boundBatchRequest,
        commands: [
          {
            type: 'USER_MESSAGE',
            idempotencyKey: 'cmd-bound-unique-2',
            contents: [{ type: 'TEXT', text: 'second' }],
          },
        ],
      },
    }
    expect(() => saveBoundPendingMessage(threadId, anotherRequest, storage)).toThrow(/Conflict/)

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
    saveBoundPendingMessage(threadId, boundPendingMessage)
    expect(loadBoundPendingMessage(threadId)).not.toBeNull()
    expect(clearBoundPendingMessage(threadId)).toBe(true)

    // 空 threadId
    expect(loadBoundPendingMessage('')).toBeNull()
    expect(clearBoundPendingMessage('')).toBe(true)

  })

  it('localStorage getter 抛安全异常时安全返回 false 或 null，绝不向外 throw', () => {
    const originalDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    try {
      Object.defineProperty(globalThis, 'localStorage', {
        get() {
          throw new DOMException('The operation is insecure', 'SecurityError')
        },
        configurable: true,
      })

      // 不传 storage 参数调用，resolveStorage 必须在内部安全兜底并返回 false / null / unavailable
      expect(loadBoundPendingMessage(threadId)).toBeNull()
      expect(clearBoundPendingMessage(threadId)).toBe(true)
      expect(() => saveBoundPendingMessage(threadId, boundPendingMessage)).toThrow(/Storage is unavailable/)
    } finally {
      if (originalDescriptor) {
        Object.defineProperty(globalThis, 'localStorage', originalDescriptor)
      }
    }
  })
})
