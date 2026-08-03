import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createClientMessageId,
  useAgentThreadMessageMutation,
} from '@/features/ai/runtime/useAgentThreadMessageMutation'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadInputDTO } from '@/shared/api/contracts/ai-runtime'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    submitThreadMessage: vi.fn(),
    submitCustomMessage: vi.fn(),
  },
}))

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useAgentThreadMessageMutation', () => {
  beforeEach(() => {
    vi.mocked(harnessService.submitThreadMessage).mockReset()
    vi.mocked(harnessService.submitCustomMessage).mockReset()
  })

  it('submits with a stable clientMessageId and invalidates thread data on success', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    vi.mocked(harnessService.submitThreadMessage).mockResolvedValue(input('in-1', 'hello'))

    const { result } = renderHook(() => useAgentThreadMessageMutation('t1'), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    })
    await act(async () => {
      await result.current.mutateAsync({
        content: 'hello',
        agentName: 'assistant',
        yoloEnabled: false,
        clientMessageId: 'cid-1',
        expectedExecutionEpoch: 2,
      })
    })

    // The caller-supplied epoch is forwarded verbatim as the CAS fencing token.
    expect(harnessService.submitThreadMessage).toHaveBeenCalledWith('t1', {
      content: 'hello',
      agentName: 'assistant',
      yoloEnabled: false,
      clientMessageId: 'cid-1',
      expectedExecutionEpoch: 2,
    })
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalled())
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.threads.snapshot('t1') })
  })

  it('reuses the same clientMessageId for retries of one submission attempt', async () => {
    vi.mocked(harnessService.submitThreadMessage)
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(input('in-2', 'retry'))

    const { result } = renderHook(() => useAgentThreadMessageMutation('t1'), { wrapper })
    const clientMessageId = createClientMessageId()
    await act(async () => {
      await expect(
        result.current.mutateAsync({
          content: 'retry',
          agentName: 'assistant',
          yoloEnabled: false,
          clientMessageId,
          expectedExecutionEpoch: 5,
        }),
      ).rejects.toThrow('network')
    })
    await act(async () => {
      await result.current.mutateAsync({
        content: 'retry',
        agentName: 'assistant',
        yoloEnabled: false,
        clientMessageId,
        expectedExecutionEpoch: 5,
      })
    })

    expect(harnessService.submitThreadMessage).toHaveBeenNthCalledWith(1, 't1', {
      content: 'retry',
      agentName: 'assistant',
      yoloEnabled: false,
      clientMessageId,
      expectedExecutionEpoch: 5,
    })
    expect(harnessService.submitThreadMessage).toHaveBeenNthCalledWith(2, 't1', {
      content: 'retry',
      agentName: 'assistant',
      yoloEnabled: false,
      clientMessageId,
      expectedExecutionEpoch: 5,
    })
  })

  it('uses the custom-message endpoint and preserves the semantic role', async () => {
    vi.mocked(harnessService.submitCustomMessage).mockResolvedValue(input('custom-1', 'system prompt'))

    const { result } = renderHook(() => useAgentThreadMessageMutation('t1'), { wrapper })
    await act(async () => {
      await result.current.mutateAsync({
        kind: 'CUSTOM_MESSAGE',
        role: 'system',
        content: 'system prompt',
        agentName: 'assistant',
        yoloEnabled: true,
        firstSendContext: null,
        clientMessageId: 'cid-custom',
        expectedExecutionEpoch: 5,
      })
    })

    expect(harnessService.submitCustomMessage).toHaveBeenCalledWith('t1', {
      role: 'system',
      content: 'system prompt',
      agentName: 'assistant',
      yoloEnabled: true,
      clientMessageId: 'cid-custom',
      expectedExecutionEpoch: 5,
    })
  })
})

function input(inputId: string, text: string): HarnessThreadInputDTO {
  return {
    inputId,
    threadId: 't1',
    sequence: 1,
    inputType: 'USER_MESSAGE',
    payloadJson: JSON.stringify({
      message: { role: 'USER', contents: [{ type: 'text', text }] },
      assistantMetadata: null,
    }),
    clientMessageId: 'cid',
    status: 'QUEUED',
    resolvedAt: null,
    createTime: null,
  }
}
