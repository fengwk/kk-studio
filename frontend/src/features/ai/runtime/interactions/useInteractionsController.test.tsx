import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { interactionService } from '@/shared/api/interaction-service'
import { useInteractionsController } from './useInteractionsController'

describe('useInteractionsController', () => {
  let queryClient: QueryClient

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    vi.restoreAllMocks()
  })

  const mockItem1: InteractionDTO = {
    interactionId: 'int-1',
    status: 'WAITING_INPUT',
    threadId: 'th-1',
    sessionId: 'sess-1',
    owner: { type: 'CHAT', chatId: 'chat-1', issueId: null, agentName: null },
    toolCallId: 'call-1',
    toolName: 'ask_user',
    argumentsJson: '{"questions":[]}',
    approvalJson: null,
    createTime: '2026-09-27T00:00:00Z',
  }

  const mockItem2: InteractionDTO = {
    interactionId: 'int-2',
    status: 'WAITING_APPROVAL',
    threadId: 'th-2',
    sessionId: 'sess-2',
    owner: { type: 'ISSUE_AGENT', chatId: null, issueId: 'iss-1', agentName: 'developer' },
    toolCallId: 'call-2',
    toolName: 'bash',
    argumentsJson: '{"command":"ls"}',
    approvalJson: '{"required":true}',
    createTime: '2026-09-27T00:01:00Z',
  }

  it('loads first page and handles cursor-based loadMore', async () => {
    const listSpy = vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({
        items: [mockItem1],
        nextCursor: 'cursor-page-2',
      })
      .mockResolvedValueOnce({
        items: [mockItem2],
        nextCursor: null,
      })

    const { result } = renderHook(() => useInteractionsController(10), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(result.current.items).toHaveLength(1)
    expect(result.current.items[0].interactionId).toBe('int-1')
    expect(result.current.hasMore).toBe(true)

    // 执行 loadMore
    await act(async () => {
      await result.current.loadMore()
    })

    expect(listSpy).toHaveBeenLastCalledWith('cursor-page-2', 10)
    expect(result.current.items).toHaveLength(2)
    expect(result.current.hasMore).toBe(false)
  })

  it('handles refresh by resetting cursor and invalidating queries', async () => {
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({ items: [mockItem1], nextCursor: 'cursor-1' })
      .mockResolvedValueOnce({ items: [mockItem2], nextCursor: null })

    const { result } = renderHook(() => useInteractionsController(10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(result.current.items[0].interactionId).toBe('int-1')

    await act(async () => {
      await result.current.refresh()
    })

    expect(result.current.items[0].interactionId).toBe('int-2')
    expect(result.current.hasMore).toBe(false)
  })
})
