import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useModelRequestDebug } from '@/features/ai/runtime/useModelRequestDebug'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessModelRequestDebugDTO } from '@/shared/api/contracts/ai-runtime'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getModelRequestDebug: vi.fn(),
  },
}))

function sampleDebug(): HarnessModelRequestDebugDTO {
  return {
    kind: 'NEXT_REQUEST_PREVIEW',
    generatedAt: '2026-09-21T00:00:00.000Z',
    model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
    environmentName: null,
    systemInstruction: 'You are an assistant.',
    tools: [
      {
        name: 'read',
        description: 'Read file',
        inputSchemaJson: '{}',
        environmentSupport: 'OPTIONAL',
        requiredEnvironmentId: null,
        provenance: 'builtin:read',
        state: 'SENT',
        filterReason: null,
      },
    ],
    skills: [],
    subagents: [],
    cacheControl: null,
    planningError: null,
    frozenInvocation: null,
  }
}

describe('useModelRequestDebug', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
      },
    })
    vi.mocked(harnessService.getModelRequestDebug).mockResolvedValue(sampleDebug())
  })

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }

  it('queries on enter and refetches on working turn start/end', async () => {
    const { result, rerender } = renderHook(
      ({ working }) => useModelRequestDebug('thread-1', true, working),
      {
        wrapper,
        initialProps: { working: false },
      },
    )

    await waitFor(() => {
      expect(result.current.debug?.systemInstruction).toBe('You are an assistant.')
    })
    expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(1)

    // Turn starts (working: true)
    rerender({ working: true })
    await waitFor(() => {
      expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(2)
    })

    // Turn ends (working: false)
    rerender({ working: false })
    await waitFor(() => {
      expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(3)
    })
  })
})
