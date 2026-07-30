import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useHarnessThreadObservability } from '@/features/ai/runtime/useHarnessThreadObservability'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    setThreadYolo: vi.fn(),
  },
}))

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useHarnessThreadObservability', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reuses one generated clientMessageId and body for an automatic YOLO retry', async () => {
    vi.mocked(harnessService.setThreadYolo)
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValueOnce({} as never)
    const { result } = renderHook(
      () => useHarnessThreadObservability('thread-1', undefined, []),
      { wrapper },
    )

    act(() => result.current.setYolo(true, 4))

    await waitFor(() => expect(harnessService.setThreadYolo).toHaveBeenCalledTimes(2))
    const firstRequest = vi.mocked(harnessService.setThreadYolo).mock.calls[0][1]
    const secondRequest = vi.mocked(harnessService.setThreadYolo).mock.calls[1][1]
    expect(firstRequest).toEqual(secondRequest)
    expect(firstRequest).toEqual({
      yoloEnabled: true,
      clientMessageId: expect.any(String),
      expectedExecutionEpoch: 4,
    })
  })
})
