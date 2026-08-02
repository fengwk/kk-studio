import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useHarnessThreadObservability } from '@/features/ai/runtime/useHarnessThreadObservability'

describe('useHarnessThreadObservability', () => {
  it('passively exposes the supplied usage and tool projections', () => {
    const usage = { scopeType: 'thread' } as never
    const tools = [{ id: 'tool-1' }] as never
    const { result } = renderHook(() => useHarnessThreadObservability('thread-1', usage, tools))

    expect(result.current).toEqual({
      usage,
      toolInvocations: tools,
      observabilityError: null,
    })
  })
})
