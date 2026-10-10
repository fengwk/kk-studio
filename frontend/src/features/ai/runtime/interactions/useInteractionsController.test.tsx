import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { interactionService } from '@/shared/api/interaction-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { queryKeys } from '@/shared/lib/query-keys'
import { useInteractionsController } from './useInteractionsController'

describe('useInteractionsController', () => {
  let queryClient: QueryClient
  let sockets: FakeWebSocketHarness

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        {children}
      </ApplicationEventProvider>
    </QueryClientProvider>
  )

  beforeEach(() => {
    sockets = new FakeWebSocketHarness()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    vi.restoreAllMocks()
  })

  const mockItem1: InteractionDTO = {
    type: 'INPUT',
    environmentId: null, environmentName: null, waitingCount: null,
    interactionId: 'int-1',
    status: 'WAITING_INPUT',
    threadId: 'th-1',
    rootThreadId: 'th-1',
    sessionId: 'sess-1',
    owner: { type: 'CHAT', chatId: 'chat-1', chatTitle: 'Chat',
      issueId: null, issueTitle: null, agentName: null, rootThreadName: 'Root' },
    toolCallId: 'call-1',
    toolName: 'ask_user',
    argumentsJson: '{"questions":[]}',
    approvalJson: null,
    createTime: '2026-09-27T00:00:00Z',
  }

  const mockItem2: InteractionDTO = {
    type: 'APPROVAL',
    environmentId: null, environmentName: null, waitingCount: null,
    interactionId: 'int-2',
    status: 'WAITING_APPROVAL',
    threadId: 'th-2',
    rootThreadId: 'th-2',
    sessionId: 'sess-2',
    owner: { type: 'ISSUE_AGENT', chatId: null, chatTitle: null,
      issueId: 'iss-1', issueTitle: 'Issue', agentName: 'developer', rootThreadName: 'Root' },
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

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(result.current.items).toHaveLength(1)
    expect(result.current.items[0].interactionId).toBe('int-1')
    expect(result.current.hasMore).toBe(true)

    // 执行 loadMore
    await act(async () => {
      await result.current.loadMore()
    })

    expect(listSpy).toHaveBeenLastCalledWith(null, 'cursor-page-2', 10)
    expect(result.current.items).toHaveLength(2)
    expect(result.current.hasMore).toBe(false)
  })

  it('refetches its own root list on a pending change event and ignores other roots', async () => {
    // 测试意图：待处理变化由服务端按真实根推送——本根事件触发一次权威回读，其他根的事件不触发无谓回读。
    const rootThreadId = '11111111-2222-4333-8444-555555555555'
    const otherRootThreadId = '99999999-2222-4333-8444-555555555555'
    const listSpy = vi
      .spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({ items: [mockItem1], nextCursor: null })
      .mockResolvedValueOnce({ items: [mockItem2], nextCursor: null })

    const { result } = renderHook(() => useInteractionsController(rootThreadId, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.items[0].interactionId).toBe('int-1')
    const socket = sockets.openLatest()

    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'interactions' },
        name: 'changed',
        data: { rootThreadId: otherRootThreadId },
      })
    })
    expect(listSpy).toHaveBeenCalledTimes(1)
    expect(
      queryClient.getQueryState(queryKeys.interactions.list(rootThreadId, null, 10))
        ?.isInvalidated,
    ).toBe(false)

    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'interactions' },
        name: 'changed',
        data: { rootThreadId },
      })
    })

    await waitFor(() => expect(result.current.items[0].interactionId).toBe('int-2'))
    expect(listSpy).toHaveBeenCalledTimes(2)
  })

  it('handles refresh by resetting cursor and invalidating queries', async () => {
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({ items: [mockItem1], nextCursor: 'cursor-1' })
      .mockResolvedValueOnce({ items: [mockItem2], nextCursor: null })

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
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

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
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

  it('discards in-flight loadMore when external invalidation pushes a new initial page and keeps new loadMore usable', async () => {
    // 测试意图：验证当 loadMore 请求在途时，卡片外部操作触发 queryClient.invalidateQueries 导致首屏第一页数据更新，
    // 该第一页更新推进了代际，旧 loadMore 迟到响应被丢弃（旧数据不 append、旧 cursor 不写覆盖），
    // 并且 isFetchingMore 及时恢复为 false，使针对新第一页的下一次 loadMore 能够正常触发执行。
    let resolveSlowLoadMore!: (val: { items: InteractionDTO[]; nextCursor: string | null }) => void
    const slowLoadMorePromise = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>((resolve) => {
      resolveSlowLoadMore = resolve
    })

    const initialPage = { items: [mockItem1], nextCursor: 'cursor-page-2' }
    const externalInvalidatedPage = { items: [{ ...mockItem1, interactionId: 'int-new-first' }], nextCursor: 'cursor-new-page-2' }
    const subsequentPage = { items: [mockItem2], nextCursor: null }

    const listSpy = vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce(initialPage) // 挂载首屏
      .mockImplementationOnce(() => slowLoadMorePromise) // 旧 loadMore 挂起在途
      .mockResolvedValueOnce(externalInvalidatedPage) // 外部 invalidate 触发首屏重新拉取
      .mockResolvedValueOnce(subsequentPage) // 新代后续 loadMore

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.items[0].interactionId).toBe('int-1')
    expect(result.current.nextCursor).toBe('cursor-page-2')

    // 触发 loadMore 进入在途
    let loadMorePromise!: Promise<void>
    act(() => {
      loadMorePromise = result.current.loadMore()
    })
    expect(result.current.isFetchingMore).toBe(true)

    // 外部（如 ApprovalCard/QuestionnaireCard）触发 invalidateQueries
    await act(async () => {
      await queryClient.invalidateQueries()
    })

    // 外部 invalidation 已经完成，首屏已替换为 externalInvalidatedPage
    await waitFor(() => expect(result.current.items[0].interactionId).toBe('int-new-first'))
    expect(result.current.nextCursor).toBe('cursor-new-page-2')
    expect(result.current.isFetchingMore).toBe(false)

    // 此时旧在途 loadMore 返回
    await act(async () => {
      resolveSlowLoadMore({ items: [{ ...mockItem1, interactionId: 'stale-int-old' }], nextCursor: 'stale-cursor-old' })
      await loadMorePromise
    })

    // 验证：旧数据没有 append，游标没有被污染为 stale-cursor-old
    expect(result.current.items).toHaveLength(1)
    expect(result.current.items[0].interactionId).toBe('int-new-first')
    expect(result.current.nextCursor).toBe('cursor-new-page-2')
    expect(result.current.isFetchingMore).toBe(false)

    // 验证新 loadMore 可用：针对新 cursor-new-page-2 进行 loadMore
    await act(async () => {
      await result.current.loadMore()
    })
    expect(listSpy).toHaveBeenLastCalledWith(null, 'cursor-new-page-2', 10)
    expect(result.current.items).toHaveLength(2)
    expect(result.current.items[1].interactionId).toBe('int-2')
    expect(result.current.hasMore).toBe(false)
  })

  it('resets isFetchingMore on refresh so subsequent loadMore is not permanently blocked', async () => {
    // 测试意图：验证 loadMore 在途时触发 refresh，refresh 立即同步重置 isFetchingMore，
    // 避免旧代 finally 栅栏跳过重置导致 isFetchingMore 永久卡在 true。
    let resolveSlowLoadMore!: (val: { items: InteractionDTO[]; nextCursor: string | null }) => void
    const slowLoadMorePromise = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>((resolve) => {
      resolveSlowLoadMore = resolve
    })

    const initialPage = { items: [mockItem1], nextCursor: 'cursor-page-2' }
    const refreshedPage = { items: [mockItem2], nextCursor: 'cursor-refreshed' }

    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce(initialPage)
      .mockImplementationOnce(() => slowLoadMorePromise)
      .mockResolvedValueOnce(refreshedPage)

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    act(() => {
      void result.current.loadMore()
    })
    expect(result.current.isFetchingMore).toBe(true)

    // 触发 refresh
    await act(async () => {
      await result.current.refresh()
    })

    // 验证：refresh 完成后 isFetchingMore 恢复为 false，游标更新
    expect(result.current.isFetchingMore).toBe(false)
    expect(result.current.nextCursor).toBe('cursor-refreshed')

    // 释放旧 loadMore
    await act(async () => {
      resolveSlowLoadMore({ items: [mockItem1], nextCursor: 'old' })
    })
    expect(result.current.isFetchingMore).toBe(false)
  })

  it('guards against duplicate loadMore calls within the same tick using ref guard', async () => {
    // 测试意图：验证同一 tick 内连续同步触发两次 loadMore 时，
    // ref guard 能同步拦截第二次请求，只发出一次网络调用，防止竞态拉取两份分页。
    const initialPage = { items: [mockItem1], nextCursor: 'cursor-page-2' }
    const listSpy = vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce(initialPage)
      .mockResolvedValue({ items: [mockItem2], nextCursor: null })

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    // 同一 tick 内同时发起两次 loadMore
    await act(async () => {
      const p1 = result.current.loadMore()
      const p2 = result.current.loadMore()
      await Promise.all([p1, p2])
    })

    // 首屏 1 次 + loadMore 1 次，共 2 次，第二项 loadMore 必须被 guard 拦下
    expect(listSpy).toHaveBeenCalledTimes(2)
  })

  it('prevents stale loadMore error from polluting new generation state', async () => {
    // 测试意图：验证旧代 loadMore 在途时触发 refresh，随后旧代抛出网络错误，
    // 该 stale error 不会污染新世代的 isError 与 error 状态。
    let rejectSlowLoadMore!: (err: Error) => void
    const slowLoadMorePromise = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>((_, reject) => {
      rejectSlowLoadMore = reject
    })

    const initialPage = { items: [mockItem1], nextCursor: 'cursor-page-2' }
    const refreshedPage = { items: [mockItem2], nextCursor: 'cursor-refreshed' }

    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce(initialPage)
      .mockImplementationOnce(() => slowLoadMorePromise)
      .mockResolvedValue(refreshedPage)

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    let loadMorePromise!: Promise<void>
    act(() => {
      loadMorePromise = result.current.loadMore()
    })

    await act(async () => {
      await result.current.refresh()
    })

    // 让旧 loadMore 抛错
    await act(async () => {
      rejectSlowLoadMore(new Error('Old loadMore network failure'))
      await loadMorePromise.catch(() => undefined)
    })

    // 验证：新世代没有被旧 error 污染
    expect(result.current.isError).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('resets in-flight loadMore when external invalidate returns structurally identical first page', async () => {
    // 测试意图：验证当 React Query structural sharing 导致外部 invalidate 返回 same object（initialPage 引用未变但 dataUpdatedAt 推进）时，
    // 也能正确触发更新并 reset in-flight 状态，且原有已加载的分页不丢失。
    const samePage = { items: [mockItem1], nextCursor: 'cursor-page-2' }
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValue(samePage)

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.items).toHaveLength(1)

    // 触发外部 invalidate
    await act(async () => {
      await queryClient.invalidateQueries()
    })

    expect(result.current.isFetchingMore).toBe(false)
    expect(result.current.items).toHaveLength(1)
  })

  it('same object + 两个延迟 nextPage 乱序真实回归：cursor/flag 严格由新代拥有，旧响应不污染', async () => {
    // 测试意图：验证结构共享下外部 invalidate 返回 same object（引用相同），触发 generation 递增，
    // 此时两个延迟的 loadMore 请求发生乱序返回（旧代在途迟于新代到达），
    // 旧代响应绝不能覆盖新代的 cursor，旧代的 finally 绝不能干扰新代 flag，cursor/flag 严格由新代拥有。
    let resolveSlowLoadMore1!: (val: { items: InteractionDTO[]; nextCursor: string | null }) => void
    const slowLoadMore1Promise = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>((resolve) => {
      resolveSlowLoadMore1 = resolve
    })

    let resolveSlowLoadMore2!: (val: { items: InteractionDTO[]; nextCursor: string | null }) => void
    const slowLoadMore2Promise = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>((resolve) => {
      resolveSlowLoadMore2 = resolve
    })

    const sameFirstPage = { items: [mockItem1], nextCursor: 'cursor-page-2' }

    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce(sameFirstPage) // 挂载首屏
      .mockImplementationOnce(() => slowLoadMore1Promise) // loadMore 1 (旧代)
      .mockResolvedValueOnce(sameFirstPage) // 外部 invalidate 触发 refetch (结构共享 same object)
      .mockImplementationOnce(() => slowLoadMore2Promise) // loadMore 2 (新代)

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.items).toHaveLength(1)
    expect(result.current.nextCursor).toBe('cursor-page-2')

    // 1. 发起旧代 loadMore 1 (在途挂起)
    act(() => {
      void result.current.loadMore()
    })
    expect(result.current.isFetchingMore).toBe(true)

    // 2. 外部触发 invalidate，返回结构共享 same object，推动 dataUpdatedAt 推进 generation
    await act(async () => {
      await queryClient.invalidateQueries()
    })
    // 验证 flag 及时重置，旧代 generation 已被废弃
    await waitFor(() => expect(result.current.isFetchingMore).toBe(false))

    // 3. 在新世代下发起 loadMore 2 (在途挂起)
    act(() => {
      void result.current.loadMore()
    })
    expect(result.current.isFetchingMore).toBe(true)

    // 4. 新代 loadMore 2 先完成返回
    await act(async () => {
      resolveSlowLoadMore2({ items: [mockItem2], nextCursor: 'cursor-page-3-new-generation' })
      await slowLoadMore2Promise
    })

    // 验证新代已成功入库
    expect(result.current.items).toHaveLength(2)
    expect(result.current.items[1].interactionId).toBe('int-2')
    expect(result.current.nextCursor).toBe('cursor-page-3-new-generation')
    expect(result.current.isFetchingMore).toBe(false)

    // 5. 旧代 loadMore 1 迟到返回
    await act(async () => {
      resolveSlowLoadMore1({
        items: [{ ...mockItem1, interactionId: 'stale-int-from-gen1' }],
        nextCursor: 'cursor-stale-gen1',
      })
      await slowLoadMore1Promise
    })

    // 关键断言：游标严格为新代游标，列表不包含旧代 item，isFetchingMore 不受旧代 finally 影响
    expect(result.current.nextCursor).toBe('cursor-page-3-new-generation')
    expect(result.current.items).toHaveLength(2)
    expect(result.current.items.map((it) => it.interactionId)).toEqual(['int-1', 'int-2'])
    expect(result.current.isFetchingMore).toBe(false)
  })

  it('never exposes the previous root items or cursor after the root changes', async () => {
    // 测试意图：过滤范围（root）变化时，旧根的 items/cursor 绝不允许出现在新根里，
    // 新根首帧只能是它自己的第一页（缓存命中时）或空列表，而不是旧根列表。
    const rootA = 'root-a'
    const rootB = 'root-b'
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({ items: [mockItem1], nextCursor: 'cursor-root-a' })
      .mockResolvedValueOnce({ items: [mockItem2], nextCursor: null })

    const { result, rerender } = renderHook(
      ({ rootThreadId }: { rootThreadId: string }) => useInteractionsController(rootThreadId, 10),
      { wrapper, initialProps: { rootThreadId: rootA } },
    )
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.items.map((it) => it.interactionId)).toEqual(['int-1'])
    expect(result.current.nextCursor).toBe('cursor-root-a')

    rerender({ rootThreadId: rootB })

    // 切换后的首帧：没有旧根 item、没有旧根游标，也没有“还有更多”。
    expect(result.current.items).toEqual([])
    expect(result.current.nextCursor).toBeNull()
    expect(result.current.hasMore).toBe(false)

    await waitFor(() => {
      expect(result.current.items.map((it) => it.interactionId)).toEqual(['int-2'])
    })
    expect(result.current.nextCursor).toBeNull()
    expect(interactionService.listInteractions).toHaveBeenLastCalledWith(rootB, null, 10)
  })

  it('fences a late loadMore response from the previous root', async () => {
    // 测试意图：旧根在途的分页响应迟到时，既不能追加进新根的列表，也不能改写新根的
    // 游标或翻页状态——即使新根的第一页还没到。
    const rootA = 'root-a'
    const rootB = 'root-b'
    let resolveStaleLoadMore!: (page: { items: InteractionDTO[]; nextCursor: string | null }) => void
    const staleLoadMore = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>(
      (resolve) => {
        resolveStaleLoadMore = resolve
      },
    )
    let resolveNewRootPage!: (page: { items: InteractionDTO[]; nextCursor: string | null }) => void
    const newRootPage = new Promise<{ items: InteractionDTO[]; nextCursor: string | null }>(
      (resolve) => {
        resolveNewRootPage = resolve
      },
    )
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({ items: [mockItem1], nextCursor: 'cursor-root-a-2' })
      .mockImplementationOnce(() => staleLoadMore)
      .mockImplementationOnce(() => newRootPage)

    const { result, rerender } = renderHook(
      ({ rootThreadId }: { rootThreadId: string }) => useInteractionsController(rootThreadId, 10),
      { wrapper, initialProps: { rootThreadId: rootA } },
    )
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    act(() => {
      void result.current.loadMore()
    })
    await waitFor(() => expect(result.current.isFetchingMore).toBe(true))

    rerender({ rootThreadId: rootB })

    // 新根第一页仍在路上：旧根列表、游标与翻页状态都不外泄。
    expect(result.current.items).toEqual([])
    expect(result.current.nextCursor).toBeNull()
    expect(result.current.hasMore).toBe(false)
    expect(result.current.isFetchingMore).toBe(false)

    await act(async () => {
      resolveStaleLoadMore({
        items: [{ ...mockItem1, interactionId: 'stale-root-a-page-2' }],
        nextCursor: 'cursor-stale-root-a',
      })
      await staleLoadMore
    })

    // 迟到的旧根响应被栅栏拦住：列表仍为空，游标没有被旧根改写。
    expect(result.current.items).toEqual([])
    expect(result.current.nextCursor).toBeNull()
    expect(result.current.isFetchingMore).toBe(false)
    expect(result.current.isError).toBe(false)

    await act(async () => {
      resolveNewRootPage({ items: [mockItem2], nextCursor: null })
      await newRootPage
    })

    await waitFor(() => {
      expect(result.current.items.map((it) => it.interactionId)).toEqual(['int-2'])
    })
    expect(result.current.nextCursor).toBeNull()
    expect(result.current.hasMore).toBe(false)
  })

  it('loadMore catch sets controlled error on active generation without unhandled rejection', async () => {
    // 测试意图：验证 loadMore 发生异常时，调用返回的 Promise 正常 resolve 而非 unhandled rejection，
    // 当前活跃世代正确写入受控 error，便于 UI 呈现错误横幅
    const initialPage = { items: [mockItem1], nextCursor: 'cursor-page-2' }
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce(initialPage)
      .mockRejectedValueOnce(new Error('Network error on loadMore'))

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    // 调用方直接 void 调用，验证不会产生未捕获的 rejection
    await act(async () => {
      await expect(result.current.loadMore()).resolves.toBeUndefined()
    })

    expect(result.current.isError).toBe(true)
    expect(result.current.error?.message).toBe('Network error on loadMore')
    expect(result.current.isFetchingMore).toBe(false)
  })

  it('reads back its own root list on resync and after a reconnect re-subscribe', async () => {
    // 测试意图：resync 与重连后的重新订阅都表示本地分页可能已经不是权威事实，
    // 必须主动回读本根的待处理列表，而不是停在断线前的页。
    const rootThreadId = '11111111-2222-4333-8444-555555555555'
    const listSpy = vi
      .spyOn(interactionService, 'listInteractions')
      .mockResolvedValue({ items: [mockItem1], nextCursor: null })

    const { result } = renderHook(() => useInteractionsController(rootThreadId, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(listSpy).toHaveBeenCalledTimes(1)
    const socket = sockets.openLatest()
    expect(socket.sentMessages()).toEqual([
      { version: 2, type: 'subscribe', resource: { kind: 'interactions' } },
    ])

    act(() => {
      socket.emitServer({ type: 'resync', resource: { kind: 'interactions' } })
    })
    await waitFor(() => expect(listSpy).toHaveBeenCalledTimes(2))

    vi.useFakeTimers()
    try {
      socket.closeWith()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000)
      })
      expect(sockets.sockets).toHaveLength(2)
      // openLatest() 已经完成 open：重连后重新订阅，服务端 ack 触发一次权威回读。
      const reconnected = sockets.openLatest()
      act(() => {
        reconnected.emitServer({
          type: 'subscribed',
          resource: { kind: 'interactions' },
          cursor: '0',
        })
      })
      for (let round = 0; round < 5; round += 1) {
        await act(async () => {
          await vi.advanceTimersByTimeAsync(0)
        })
      }
      expect(listSpy).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  // 环境等待只带 (真实执行根, 冻结环境) 聚合身份，跨页必须按该身份去重；distinct 环境仍是独立条目。
  const environmentWait = (environmentId: string, waitingCount = 1): InteractionDTO => ({
    type: 'ENVIRONMENT_WAIT',
    rootThreadId: 'th-1',
    owner: {
      type: 'CHAT',
      chatId: 'chat-1',
      chatTitle: 'Chat',
      issueId: null,
      issueTitle: null,
      agentName: null,
      rootThreadName: 'Root',
    },
    createTime: '2026-09-27T00:00:00Z',
    interactionId: null,
    status: null,
    threadId: null,
    sessionId: null,
    toolCallId: null,
    toolName: null,
    argumentsJson: null,
    approvalJson: null,
    environmentId,
    environmentName: `env-${environmentId}`,
    waitingCount,
  })

  it('环境等待跨页按 (根, 环境) 去重，不同环境仍各自成项，且不影响人工等待', async () => {
    // 测试意图：环境等待的 interactionId 恒为 null，若按 interactionId 去重会把所有环境等待并成一项；
    // 只有按聚合身份去重才既剔除跨页重叠、又保留不同环境。
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({ items: [mockItem1, environmentWait('env-a', 2)], nextCursor: 'c2' })
      .mockResolvedValueOnce({
        items: [environmentWait('env-a', 3), environmentWait('env-b'), mockItem2],
        nextCursor: null,
      })

    const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    await act(async () => {
      await result.current.loadMore()
    })

    // 人工等待各按 interactionId 保留；env-a 只保留先到的条目，env-b 独立成项。
    expect(result.current.items).toHaveLength(4)
    expect(result.current.items.map((item) => item.type)).toEqual([
      'INPUT',
      'ENVIRONMENT_WAIT',
      'ENVIRONMENT_WAIT',
      'APPROVAL',
    ])
    const environmentWaits = result.current.items.filter(
      (item) => item.type === 'ENVIRONMENT_WAIT',
    )
    expect(environmentWaits.map((item) => item.environmentId)).toEqual(['env-a', 'env-b'])
    expect(
      environmentWaits.find((item) => item.environmentId === 'env-a')?.waitingCount,
    ).toBe(2)
    expect(result.current.hasMore).toBe(false)
  })

  it('按服务端 freshnessAt 只做一次时效对账，不轮询', async () => {
    // 测试意图：环境租约到期没有写事件；控制器必须恰好在服务端给出的时效边界后回读一次，
    // 且同一已消费截止点不再产生第二次回读（无浏览器轮询）。
    vi.useFakeTimers()
    try {
      const base = Date.now()
      const listSpy = vi
        .spyOn(interactionService, 'listInteractions')
        .mockResolvedValue({ items: [mockItem1], nextCursor: null, total: 1,
          freshnessAt: base / 1000 + 5 })

      const { result } = renderHook(() => useInteractionsController(null, 10), { wrapper })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(result.current.isLoading).toBe(false)
      expect(listSpy).toHaveBeenCalledTimes(1)

      // 截止点 + 250ms 宽限之前不回读。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5000)
      })
      expect(listSpy).toHaveBeenCalledTimes(1)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(250)
      })
      expect(listSpy).toHaveBeenCalledTimes(2)

      // 权威数据未给出新截止点 -> 不再排任务。
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60000)
      })
      expect(listSpy).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})
