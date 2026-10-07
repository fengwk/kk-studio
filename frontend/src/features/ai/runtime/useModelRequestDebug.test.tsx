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

  it('follows invocation and phase while execution stays continuously working', async () => {
    const { result, rerender } = renderHook(
      ({ revision }) => useModelRequestDebug('thread-1', true, revision),
      {
        wrapper,
        initialProps: { revision: 'model-1:READY' },
      },
    )

    await waitFor(() => {
      expect(result.current.debug?.systemInstruction).toBe('You are an assistant.')
    })
    expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(1)

    // 同一调用开始执行，working 仍为 true。
    rerender({ revision: 'model-1:RUNNING' })
    await waitFor(() => {
      expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(2)
    })

    // 下一模型调用开始，working 没有经过 false。
    rerender({ revision: 'model-2:READY' })
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
    const { result } = renderHook(() => useHistoricalRequestPreview('session-1', 'view-1'), { wrapper })

    expect(harnessService.previewHistoricalRequest).not.toHaveBeenCalled()
    expect(result.current.preview).toBeNull()

    act(() => result.current.request('assistant-1'))
    await waitFor(() => {
      expect(result.current.preview?.kind).toBe('HISTORICAL_REQUEST_PREVIEW')
    })
    expect(harnessService.previewHistoricalRequest).toHaveBeenCalledTimes(1)
    expect(harnessService.previewHistoricalRequest).toHaveBeenCalledWith('session-1', 'assistant-1')
    act(() => result.current.request('assistant-1'))
    expect(result.current.preview).toBeNull()
    await waitFor(() => expect(harnessService.previewHistoricalRequest).toHaveBeenCalledTimes(2))

    // 切换到另一条历史 Entry 会发起一次新的只读 GET
    act(() => result.current.request('assistant-2'))
    await waitFor(() => {
      expect(harnessService.previewHistoricalRequest).toHaveBeenCalledTimes(3)
    })

    act(() => result.current.dismiss())
    expect(result.current.preview).toBeNull()
    expect(result.current.entryId).toBeNull()
  })

  // 意图：没有 session 时即使请求也不发 GET（不伪造结果）。
  it('does not fetch without a session id', () => {
    const { result } = renderHook(() => useHistoricalRequestPreview(null, 'view-1'), { wrapper })
    act(() => result.current.request('assistant-1'))
    expect(harnessService.previewHistoricalRequest).not.toHaveBeenCalled()
    expect(result.current.preview).toBeNull()
  })
})
describe('request source fences', () => {
  function setup() {
    vi.clearAllMocks()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    return { client, wrapper }
  }

  it('isolates late invocation results and separates read errors', async () => {
    const { wrapper } = setup()
    let finish!: (value: HarnessModelRequestDebugDTO) => void
    vi.mocked(harnessService.getModelRequestDebug)
      .mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
      .mockRejectedValueOnce(new Error('read failed'))
    const { result, rerender } = renderHook(
      ({ id, enabled }) => useModelRequestDebug(id, enabled, id),
      { wrapper, initialProps: { id: 'old', enabled: true } },
    )
    expect(result.current.loading).toBe(true)
    rerender({ id: 'new', enabled: true })
    await waitFor(() => expect(result.current.error).toBeInstanceOf(Error))
    await act(async () => finish(sampleDebug()))
    expect(result.current.debug).toBeNull()
    rerender({ id: 'new', enabled: false })
    expect(result.current.loading).toBe(false)
    expect(result.current.debug).toBeNull()
  })

  it.each(['agents', 'models', 'providers', 'tools', 'skills', 'mcp-servers'])('refreshes %s only while enabled', async (root) => {
    const { client, wrapper } = setup()
    client.setQueryDefaults([root], { gcTime: Infinity })
    client.setQueryData([root, 'list'], {})
    vi.mocked(harnessService.getModelRequestDebug).mockResolvedValue(sampleDebug())
    const { result, rerender } = renderHook(
      ({ enabled }) => useModelRequestDebug('t', enabled, 'same'),
      { wrapper, initialProps: { enabled: true } },
    )
    await waitFor(() => expect(result.current.debug).not.toBeNull())
    act(() => { void client.invalidateQueries({ queryKey: [root] }) })
    await waitFor(() => expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(2))
    act(() => client.setQueryData(['unrelated'], {}))
    expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(2)
    act(() => client.setQueryData([root, 'list'], { changed: true }))
    await waitFor(() => expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(3))
    rerender({ enabled: false })
    act(() => client.setQueryData([root, 'list'], { changed: 'again' }))
    expect(harnessService.getModelRequestDebug).toHaveBeenCalledTimes(3)
  })

  it.each(['session', 'view', 'entry', 'dismiss'])('rejects late historical results after %s changes', async (change) => {
    const { wrapper } = setup()
    const preview: ProviderRequestPreviewDTO = {
      kind: 'HISTORICAL_REQUEST_PREVIEW', providerType: 'OPENAI', modelName: 'm',
      bodyByteSize: 2, bodyJson: '{}', sourceHeadEntryId: 'head', generatedAt: 'now',
    }
    let finish!: (value: ProviderRequestPreviewDTO) => void
    vi.mocked(harnessService.previewHistoricalRequest)
      .mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
      .mockResolvedValue({ ...preview, bodyJson: '{"new":true}' })
    const { result, rerender } = renderHook(
      ({ session, view }) => useHistoricalRequestPreview(session, view),
      { wrapper, initialProps: { session: 's1', view: 'v1' } },
    )
    act(() => result.current.request('old'))
    expect(result.current.loading).toBe(true)
    if (change === 'session') rerender({ session: 's2', view: 'v1' })
    if (change === 'view') rerender({ session: 's1', view: 'v2' })
    if (change === 'entry') {
      act(() => result.current.request('new'))
      await waitFor(() => expect(result.current.preview?.bodyJson).toContain('new'))
    }
    if (change === 'dismiss') act(() => result.current.dismiss())
    await act(async () => finish(preview))
    expect(result.current.preview?.bodyJson ?? null).toBe(change === 'entry' ? '{"new":true}' : null)
  })

  it('keeps historical read errors explicit and clears on dismiss', async () => {
    const { wrapper } = setup()
    vi.mocked(harnessService.previewHistoricalRequest).mockRejectedValue(new Error('read failed'))
    const { result } = renderHook(() => useHistoricalRequestPreview('s', 'v'), { wrapper })
    act(() => result.current.request('e'))
    await waitFor(() => expect(result.current.error).toBeInstanceOf(Error))
    expect(result.current.loading).toBe(false)
    act(() => result.current.dismiss())
    expect(result.current.error).toBeNull()
  })
})
