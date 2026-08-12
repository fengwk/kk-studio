import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useThreadCommandBatchMutation } from '@/features/ai/runtime/useThreadCommandBatchMutation'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessThreadCommandBatchDTO,
  HarnessThreadCommandDTO,
} from '@/shared/api/contracts/ai-runtime'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    enqueueCommands: vi.fn(),
  },
}))

function command(clientCommandId: string, type: string, content: string) {
  return {
    type,
    clientCommandId,
    contents: [{ type: 'TEXT', text: content }],
  }
}

function batch(): HarnessThreadCommandBatchDTO {
  return {
    expectedHeadEntryId: 'root',
    expectedNextCommandSequence: '1',
    commands: [command('cid-1', 'USER_MESSAGE', 'hello')],
  }
}

function appliedCommand(): HarnessThreadCommandDTO {
  return {
    threadId: 't1',
    sequence: '1',
    type: 'USER_MESSAGE',
    state: 'QUEUED',
    clientCommandId: 'cid-1',
    requestHash: '0123456789abcdef'.repeat(4),
    payloadJson: JSON.stringify({
      message: { role: 'USER', contents: [{ type: 'text', text: 'hello' }] },
    }),
    consumedTurnStartEntryId: null,
    cancelledAt: null,
    createTime: null,
  }
}

describe('useThreadCommandBatchMutation', () => {
  beforeEach(() => {
    vi.mocked(harnessService.enqueueCommands).mockReset()
  })

  it('posts the exact batch and invalidates threads.snapshot(threadId) + chats.all on success', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    vi.mocked(harnessService.enqueueCommands).mockResolvedValue([appliedCommand()])

    const sent = batch()
    const { result } = renderHook(() => useThreadCommandBatchMutation('t1'), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    })

    await act(async () => {
      await result.current.mutateAsync(sent)
    })

    expect(harnessService.enqueueCommands).toHaveBeenCalledWith('t1', sent)
    await waitFor(() => {
      const calls = invalidateSpy.mock.calls.map(([arg]) => arg)
      expect(calls).toContainEqual({ queryKey: queryKeys.threads.snapshot('t1') })
      expect(calls).toContainEqual({ queryKey: queryKeys.chats.all })
    })
  })

  it('does not invalidate queries when the mutation fails', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    vi.mocked(harnessService.enqueueCommands).mockRejectedValueOnce(new Error('network'))

    const { result } = renderHook(() => useThreadCommandBatchMutation('t1'), { wrapper: ({ children }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ) })

    await act(async () => {
      await expect(result.current.mutateAsync(batch())).rejects.toThrow('network')
    })

    expect(invalidateSpy).not.toHaveBeenCalled()
  })
})
