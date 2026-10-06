import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useModelRequestDebug, useHistoricalRequestPreview } from '@/features/ai/runtime/useModelRequestDebug'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessModelRequestDebugDTO,
  ProviderRequestPreviewDTO,
} from '@/shared/api/contracts/ai-runtime'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getModelRequestDebug: vi.fn(),
    previewHistoricalRequest: vi.fn(),
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

describe('useHistoricalRequestPreview', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    })
    vi.mocked(harnessService.previewHistoricalRequest).mockResolvedValue(samplePreview())
  })

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }

  function samplePreview(): ProviderRequestPreviewDTO {
    return {
      kind: 'HISTORICAL_REQUEST_PREVIEW',
      providerType: 'openai',
      modelName: 'm',
      bodyByteSize: 12,
      bodyJson: '{}',
      sourceHeadEntryId: 'head-1',
      generatedAt: '2026-09-21T00:00:00.000Z',
      notice: 'reconstructed under current definitions',
    }
  }

  // 意图：只有显式 request(entryId) 才发 GET；未选中时保持静默（不查询、不写入）。
  it('fetches on demand only and stays silent before any selection', async () => {
    const { result } = renderHook(() => useHistoricalRequestPreview('session-1'), { wrapper })

    expect(harnessService.previewHistoricalRequest).not.toHaveBeenCalled()
    expect(result.current.preview).toBeNull()

    act(() => result.current.request('assistant-1'))
    await waitFor(() => {
      expect(result.current.preview?.kind).toBe('HISTORICAL_REQUEST_PREVIEW')
    })
    expect(harnessService.previewHistoricalRequest).toHaveBeenCalledTimes(1)
    expect(harnessService.previewHistoricalRequest).toHaveBeenCalledWith('session-1', 'assistant-1')

    // 切换到另一条历史 Entry 会发起一次新的只读 GET
    act(() => result.current.request('assistant-2'))
    await waitFor(() => {
      expect(harnessService.previewHistoricalRequest).toHaveBeenCalledTimes(2)
    })

    act(() => result.current.dismiss())
    expect(result.current.preview).toBeNull()
    expect(result.current.entryId).toBeNull()
  })

  // 意图：没有 session 时即使请求也不发 GET（不伪造结果）。
  it('does not fetch without a session id', () => {
    const { result } = renderHook(() => useHistoricalRequestPreview(null), { wrapper })
    act(() => result.current.request('assistant-1'))
    expect(harnessService.previewHistoricalRequest).not.toHaveBeenCalled()
    expect(result.current.preview).toBeNull()
  })
})
