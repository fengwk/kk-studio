import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { presentConflict } from '@/shared/conflict/conflict-presenter'

/**
 * 验证 shared 409 conflict presenter 按顶层 reason/detail 契约解析，
 * 遵循 reason > code > CONFLICT 优先级与 detail 回退规则。
 */
describe('shared conflict presenter', () => {
  // 测试意图：优先读取顶层 reason 与 detail，保持与后端 problem+json 契约一致
  it('prefers top-level reason and detail over code and message', () => {
    const error = new ApiError(
      'conflict',
      409,
      'conflict',
      undefined,
      'VERSION_CONFLICT',
      'expected=0 actual=1',
    )
    expect(presentConflict(error)).toEqual({
      reason: 'VERSION_CONFLICT',
      detail: 'expected=0 actual=1',
    })
  })

  // 测试意图：缺少顶层 reason 时回退到 code
  it('falls back to code when top-level reason is missing', () => {
    const error = new ApiError('conflict', 409, 'version_conflict', undefined, undefined, 'stale')
    expect(presentConflict(error)?.reason).toBe('version_conflict')
    expect(presentConflict(error)?.detail).toBe('stale')
  })

  // 测试意图：reason 与 code 均缺失时回退到 CONFLICT
  it('falls back to the literal CONFLICT when neither reason nor code exists', () => {
    const error = new ApiError('conflict', 409)
    expect(presentConflict(error)?.reason).toBe('CONFLICT')
  })

  // 测试意图：缺少 detail 时以 error.message 兜底
  it('uses the error message as detail when detail is missing', () => {
    const error = new ApiError('boom', 409, 'version_conflict')
    expect(presentConflict(error)?.detail).toBe('boom')
  })

  // 测试意图：非 409 错误不呈现为 Conflict
  it('returns null for non-409 errors', () => {
    expect(presentConflict(new ApiError('bad', 400, 'validation'))).toBeNull()
    expect(presentConflict(new Error('plain'))).toBeNull()
  })
})
