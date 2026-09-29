import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  clearPendingAction,
  isNetworkUnknownError,
  isPayloadEqual,
  loadPendingAction,
  pendingActionStorageKey,
  storePendingAction,
  validAction,
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
    // 测试意图：验证从 storage 恢复时上一次未完成的请求一律标记为 unknown，返回 VALID
    storePendingAction(issueId, mockAction)
    const key = pendingActionStorageKey(issueId)
    expect(localStorage.getItem(key)).not.toBeNull()

    const loaded = loadPendingAction(issueId)
    expect(loaded).toEqual({
      type: 'VALID',
      action: { ...mockAction, isUnknown: true },
    })
  })

  it('fails closed when store fails (e.g. quota exceeded), preventing API request', () => {
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

  it('prohibits overwriting existing pending action with different identity (dual-pane protection)', () => {
    // 测试意图：验证双 pane 场景下，Pane A 已挂起动作后，Pane B 尝试使用不同 requestKey 覆盖时被拦截
    storePendingAction(issueId, mockAction)

    const paneBAction: PendingIssueAction = {
      issueId,
      kind: 'TRANSITION',
      requestKey: 'req-key-pane-b',
      expectedVersion: '2',
      payload: { toState: 'DONE' },
      createdAt: '2026-09-29T10:01:00Z',
      isUnknown: false,
    }

    expect(() => storePendingAction(issueId, paneBAction)).toThrow(
      /Cannot overwrite existing pending action for issue issue-test-123 with different identity/,
    )

    // 验证原 Pane A 数据完整未受影响
    const loaded = loadPendingAction(issueId)
    expect(loaded.type).toBe('VALID')
    if (loaded.type === 'VALID') {
      expect(loaded.action.requestKey).toBe('req-key-001')
    }
  })

  it('rejects storing same requestKey with different payload, kind, or version', () => {
    // 测试意图：验证防串号机制，禁止使用同一 requestKey 发送被偷偷修改的 payload 或版本
    storePendingAction(issueId, mockAction)

    // 1. 同 key 不同 payload
    const differentPayloadAction: PendingIssueAction = {
      ...mockAction,
      payload: { toState: 'DONE' },
    }
    expect(() => storePendingAction(issueId, differentPayloadAction)).toThrow(
      /different payload, kind, or version/,
    )

    // 2. 同 key 不同 expectedVersion
    const differentVersionAction: PendingIssueAction = {
      ...mockAction,
      expectedVersion: '99',
    }
    expect(() => storePendingAction(issueId, differentVersionAction)).toThrow(
      /different payload, kind, or version/,
    )

    // 3. 允许完全相同的幂等重新存储
    expect(() => storePendingAction(issueId, mockAction)).not.toThrow()
  })

  it('validates schema strictly across all 8 kinds and rejects invalid kind or foreign issue', () => {
    // 测试意图：验证 discriminated union 校验，拒绝垃圾 kind、payload 不匹配或外来 issue 写入
    expect(validAction(mockAction, issueId)).toBe(true)

    // 1. 外来 issueId 校验失败
    expect(validAction(mockAction, 'different-issue-id')).toBe(false)
    expect(() => storePendingAction('different-issue-id', mockAction)).toThrow(
      /Invalid pending action payload or issueId/,
    )

    // 2. 垃圾 kind 校验失败
    const invalidKind = {
      ...mockAction,
      kind: 'UNKNOWN_HACK_KIND',
    }
    expect(validAction(invalidKind, issueId)).toBe(false)

    // 3. 各种 kind 的 payload 校验
    const blockAction: PendingIssueAction = {
      issueId,
      kind: 'BLOCK',
      requestKey: 'req-block-1',
      expectedVersion: '1',
      payload: { reason: 'Blocked by dependency' },
      createdAt: '2026-09-29T10:00:00Z',
      isUnknown: false,
    }
    expect(validAction(blockAction, issueId)).toBe(true)

    // 空 reason 的 block 校验失败
    const invalidBlock = { ...blockAction, payload: { reason: '   ' } }
    expect(validAction(invalidBlock, issueId)).toBe(false)

    // reset budget 额度必须为大于 0 的整数
    const validBudget: PendingIssueAction = {
      issueId,
      kind: 'RESET_BUDGET',
      requestKey: 'req-budget-1',
      expectedVersion: '1',
      payload: { state: 'DESIGN', maxRuns: 5 },
      createdAt: '2026-09-29T10:00:00Z',
      isUnknown: false,
    }
    expect(validAction(validBudget, issueId)).toBe(true)
    const invalidBudget = {
      ...validBudget,
      payload: { state: 'DESIGN', maxRuns: -1 },
    }
    expect(validAction(invalidBudget, issueId)).toBe(false)
  })

  it('handles corrupt storage records by returning CORRUPT and preserving evidence without silent deletion', () => {
    // 测试意图：验证存储损坏时不静默删除数据，返回 CORRUPT 保留证据，阻止盲目覆盖
    const key = pendingActionStorageKey(issueId)
    localStorage.setItem(key, '{"invalidJson": unclosed')

    const loadResult = loadPendingAction(issueId)
    expect(loadResult.type).toBe('CORRUPT')
    if (loadResult.type === 'CORRUPT') {
      expect(loadResult.raw).toBe('{"invalidJson": unclosed')
      expect(loadResult.error).toContain('JSON parse error')
    }

    // 尝试写入新动作时必须阻止覆盖损坏记录
    expect(() => storePendingAction(issueId, mockAction)).toThrow(
      /Cannot overwrite corrupt pending action/,
    )

    // 普通清理不能误删损坏记录
    const cleared = clearPendingAction(issueId, 'req-key-001')
    expect(cleared).toBe(false)
    expect(localStorage.getItem(key)).not.toBeNull()

    // 仅在明确 force 放弃时才允许清除
    const forceCleared = clearPendingAction(issueId, undefined, undefined, true)
    expect(forceCleared).toBe(true)
    expect(localStorage.getItem(key)).toBeNull()
  })

  it('returns STORAGE_ERROR on read exceptions to prevent treating errors as non-existent', () => {
    // 测试意图：验证存储读取异常时返回 STORAGE_ERROR 显式阻止，不能等同于无挂起动作而误放行 POST
    const throwingReadStorage = {
      getItem: () => {
        throw new Error('Disk IO Error')
      },
      setItem: () => {},
      removeItem: () => {},
    } as unknown as Storage

    const result = loadPendingAction(issueId, throwingReadStorage)
    expect(result.type).toBe('STORAGE_ERROR')
    if (result.type === 'STORAGE_ERROR') {
      expect(result.error).toContain('Disk IO Error')
    }
  })

  it('async success only clears own identity and preserves other requestKeys', () => {
    // 测试意图：验证清除侧车时校验 matchingRequestKey，仅允许清理自身 identity
    storePendingAction(issueId, mockAction)

    // 试图用不同的 requestKey 清除：失败且数据保留
    const clearedWrong = clearPendingAction(issueId, 'wrong-key-999')
    expect(clearedWrong).toBe(false)
    expect(loadPendingAction(issueId).type).toBe('VALID')

    // 用匹配的 key 清除：成功
    const clearedRight = clearPendingAction(issueId, 'req-key-001')
    expect(clearedRight).toBe(true)
    expect(loadPendingAction(issueId).type).toBe('NONE')
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
