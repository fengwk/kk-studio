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

  it('discards stale loadMore responses and does not reset state across refresh generation fence', async () => {
    // 测试意图：验证 loadMore 在途时触发 refresh，旧代 loadMore 的迟到响应会被 generation fence 丢弃，
    // 不会污染刚刷新的列表和游标，且旧代 finally 不会破坏新状态
    let resolveSlowLoadMore!: (val: { items: InteractionDTO[]; nextCursor: string | null }) => void
    const slowLoadMorePromise = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>((resolve) => {
      resolveSlowLoadMore = resolve
    })

    const initialPage = { items: [mockItem1], nextCursor: 'cursor-page-2' }
    const refreshedPage = { items: [mockItem2], nextCursor: 'cursor-refreshed' }

    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce(initialPage) // 挂载首屏
      .mockImplementationOnce(() => slowLoadMorePromise) // loadMore 挂起
      .mockResolvedValueOnce(refreshedPage) // refresh 重新拉取第一页

    const { result } = renderHook(() => useInteractionsController(10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.items).toHaveLength(1)
    expect(result.current.nextCursor).toBe('cursor-page-2')

    // 触发 loadMore（进入在途状态，Promise 未 resolve）
    let loadMorePromise!: Promise<void>
    act(() => {
      loadMorePromise = result.current.loadMore()
    })
    expect(result.current.isFetchingMore).toBe(true)

    // 此时用户触发 refresh（开启新 generation）
    await act(async () => {
      await result.current.refresh()
    })

    // refresh 已经完成，列表已切换为 refreshedPage
    expect(result.current.items).toHaveLength(1)
    expect(result.current.items[0].interactionId).toBe('int-2')
    expect(result.current.nextCursor).toBe('cursor-refreshed')

    // 此时旧代的 slow loadMore 响应返回
    const staleItem: InteractionDTO = { ...mockItem1, interactionId: 'stale-int-old' }
    await act(async () => {
      resolveSlowLoadMore({ items: [staleItem], nextCursor: 'stale-cursor-old' })
      await loadMorePromise
    })

    // 验证：旧代 items 没有被追加到刷新后的列表中，nextCursor 没有被旧游标覆写
    expect(result.current.items).toHaveLength(1)
    expect(result.current.items[0].interactionId).toBe('int-2')
    expect(result.current.nextCursor).toBe('cursor-refreshed')
  })
})
