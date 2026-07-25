import { describe, expect, it } from 'vitest'
import { isThreadActive } from '@/features/ai/useAgentThreadQueries'

describe('isThreadActive', () => {
  it('polls RUNNING / WAITING / RUNNABLE and leaves IDLE quiet', () => {
    // RUNNABLE must stay active so a lost Redis wake cannot freeze the UI mid-activation.
    expect(isThreadActive('RUNNING')).toBe(true)
    expect(isThreadActive('WAITING')).toBe(true)
    expect(isThreadActive('RUNNABLE')).toBe(true)
    expect(isThreadActive('IDLE')).toBe(false)
    expect(isThreadActive(undefined)).toBe(false)
  })
})
