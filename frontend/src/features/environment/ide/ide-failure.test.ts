import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { failureStatus, ideFailureReason, isUnknownIdeFailure } from '@/features/environment/ide/ide-failure'

describe('IDE structured failure classification', () => {
  it('distinguishes unknown outcomes from unavailable control and ordinary unavailable/conflict failures', () => {
    const outcomeUnknown = new ApiError('unknown', 503, 'unavailable', undefined, 'IDE_OUTCOME_UNKNOWN')
    const controlUnavailable = new ApiError('control unavailable', 503, 'unavailable', undefined, 'IDE_CONTROL_UNAVAILABLE')
    const ordinaryUnavailable = new ApiError('unavailable', 503, 'unavailable', undefined, 'IDE_UNAVAILABLE')
    const conflict = new ApiError('conflict', 409, 'conflict')

    expect(ideFailureReason(controlUnavailable)).toBe('IDE_CONTROL_UNAVAILABLE')
    expect(isUnknownIdeFailure(outcomeUnknown)).toBe(true)
    expect(isUnknownIdeFailure(controlUnavailable)).toBe(false)
    expect(isUnknownIdeFailure(ordinaryUnavailable)).toBe(false)
    expect(isUnknownIdeFailure(conflict)).toBe(false)
    expect(failureStatus(controlUnavailable)).toMatchObject({
      status: 'error',
      message: 'control unavailable',
    })
  })
})
