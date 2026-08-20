import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { presentConflict } from '@/shared/conflict/conflict-presenter'

/**
 * shared 409 presenter 是 Settings 与 AgentPane 共用的唯一实现；本测试锁定
 * reason 优先级（errors.reason > code > CONFLICT）与 detail 回退，防止两端契约漂移。
 */
describe('shared conflict presenter', () => {
  it('prefers errors.reason over the envelope code', () => {
    const error = new ApiError('conflict', 409, 'version_conflict', {
      reason: 'VERSION_CONFLICT',
      detail: 'expected=0 actual=1',
    })
    expect(presentConflict(error)).toEqual({
      reason: 'VERSION_CONFLICT',
      detail: 'expected=0 actual=1',
    })
  })

  it('falls back to the envelope code when errors.reason is missing', () => {
    const error = new ApiError('conflict', 409, 'version_conflict', { detail: 'stale' })
    expect(presentConflict(error)?.reason).toBe('version_conflict')
  })

  it('falls back to the literal CONFLICT when neither reason nor code exists', () => {
    const error = new ApiError('conflict', 409)
    expect(presentConflict(error)?.reason).toBe('CONFLICT')
  })

  it('uses the error message as detail when errors.detail is missing', () => {
    const error = new ApiError('boom', 409, 'version_conflict')
    expect(presentConflict(error)?.detail).toBe('boom')
  })

  it('returns null for non-409 errors', () => {
    expect(presentConflict(new ApiError('bad', 400, 'validation'))).toBeNull()
    expect(presentConflict(new Error('plain'))).toBeNull()
  })
})
