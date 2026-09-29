import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  clearPendingAction,
  isNetworkUnknownError,
  isPayloadEqual,
  loadPendingAction,
  pendingActionStorageKey,
  storePendingAction,
  type PendingIssueAction,
} from './pending-action-sidecar'

describe('pending-action-sidecar', () => {
  const issueId = 'issue-test-123'
  const mockAction: PendingIssueAction = {
    issueId,
    kind: 'TRANSITION',
    requestKey: 'req-key-001',
    expectedVersion: '2',
    payload: { toState: 'IN_PROGRESS' },
    createdAt: '2026-09-29T10:00:00Z',
    isUnknown: false,
  }

  beforeEach(() => {
    localStorage.clear()
  })

  it('stores and loads valid pending action, with load recovering as unknown', () => {
    // 测试意图：验证从 storage 恢复时上一次未完成的请求一律标记为 unknown
    storePendingAction(issueId, mockAction)
    const key = pendingActionStorageKey(issueId)
    expect(localStorage.getItem(key)).not.toBeNull()

    const loaded = loadPendingAction(issueId)
    expect(loaded).toEqual({ ...mockAction, isUnknown: true })
  })

  it('fails closed when store fails', () => {
    // 测试意图：验证 storage 写入异常时 fail-closed 抛出异常，阻止没有持久化保障的请求继续发送
    const throwingStorage = {
      getItem: () => null,
      setItem: () => {
        throw new Error('Quota exceeded')
      },
      removeItem: () => {},
    } as unknown as Storage

    expect(() => storePendingAction(issueId, mockAction, throwingStorage)).toThrow(
      /Failed to persist pending action/,
    )
  })

  it('async success only clears own identity and preserves other requestKeys', () => {
    // 测试意图：验证清除侧车时校验 matchingRequestKey，仅允许清理自身 identity
    storePendingAction(issueId, mockAction)

    // 试图用不同的 requestKey 清除：失败且数据保留
    const clearedWrong = clearPendingAction(issueId, 'wrong-key-999')
    expect(clearedWrong).toBe(false)
    expect(loadPendingAction(issueId)).not.toBeNull()

    // 用匹配的 key 清除：成功
    const clearedRight = clearPendingAction(issueId, 'req-key-001')
    expect(clearedRight).toBe(true)
    expect(loadPendingAction(issueId)).toBeNull()
  })

  it('accurately treats 408 and 429 as unknown, but 409 and 400 as non-unknown', () => {
    // 测试意图：验证 408 (Request Timeout) 与 429 (Too Many Requests) 算未知网络结果，而 409/400 算已确定拒绝
    expect(isNetworkUnknownError(new ApiError('Timeout', 408))).toBe(true)
    expect(isNetworkUnknownError(new ApiError('Rate limited', 429))).toBe(true)
    expect(isNetworkUnknownError(new ApiError('Server error', 500))).toBe(true)
    expect(isNetworkUnknownError(new TypeError('Failed to fetch'))).toBe(true)

    expect(isNetworkUnknownError(new ApiError('Conflict', 409))).toBe(false)
    expect(isNetworkUnknownError(new ApiError('Bad request', 400))).toBe(false)
    expect(isNetworkUnknownError(new ApiError('Not found', 404))).toBe(false)
  })

  it('checks payload equality deeply to prevent same key with different payload', () => {
    // 测试意图：验证 payload 深度对比逻辑，保证同一 requestKey 不可发送不同 payload
    expect(isPayloadEqual({ toState: 'IN_PROGRESS' }, { toState: 'IN_PROGRESS' })).toBe(true)
    expect(isPayloadEqual({ toState: 'IN_PROGRESS' }, { toState: 'DONE' })).toBe(false)
    expect(isPayloadEqual({ a: 1, b: { c: 'hello' } }, { a: 1, b: { c: 'hello' } })).toBe(true)
    expect(isPayloadEqual({ a: 1, b: { c: 'hello' } }, { a: 1, b: { c: 'world' } })).toBe(false)
    expect(isPayloadEqual({ a: 1 }, { a: 1, b: 2 })).toBe(false)
  })
})
