import { act, render, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { ProjectsInvalidationBridge } from '@/features/projects/extensions/projects-extension'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { queryKeys } from '@/shared/lib/query-keys'

const PROJECT_ID = '11111111-2222-4333-8444-555555555555'
const ANOTHER_PROJECT_ID = '99999999-8888-4777-a666-555555555555'

describe('platform invalidation bridges', () => {
  it('connects the global WebSocket resource to TanStack Query invalidations', () => {
    // 测试意图：验证 ProjectsInvalidationBridge 正确订阅 projects WebSocket 资源，
    // 并在接收到 subscribed/event/resync/error 时执行对应 query key 的失效。
    const sockets = new FakeWebSocketHarness()
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          <ProjectsInvalidationBridge />
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    const socket = sockets.openLatest()
    expect(socket.sentMessages()).toEqual([
      { version: 1, type: 'subscribe', resource: { kind: 'projects' } },
    ])

    // 1. subscribed 事件 -> 全量失效 projects.all
    act(() => {
      socket.emitServer({
        type: 'subscribed',
        resource: { kind: 'projects' },
        cursor: '0',
      })
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.all,
    })

    // 2. changed 事件（携带 projectId）-> 精准失效 lists, detail, snapshot, issues
    invalidateSpy.mockClear()
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'projects' },
        name: 'changed',
        data: { projectId: PROJECT_ID },
      })
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.lists(),
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.detail(PROJECT_ID),
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.snapshot(PROJECT_ID),
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.issues(PROJECT_ID),
    })
    expect(invalidateSpy).toHaveBeenCalledTimes(4)

    // 3. resync 事件 -> 全量失效
    invalidateSpy.mockClear()
    act(() => {
      socket.emitServer({
        type: 'resync',
        resource: { kind: 'projects' },
      })
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.all,
    })

    // 4. error 事件 -> 全量失效
    invalidateSpy.mockClear()
    act(() => {
      socket.emitServer({
        type: 'error',
        resource: { kind: 'projects' },
        code: 'RESOURCE_NOT_FOUND',
        message: 'Resource not found',
      })
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.all,
    })
  })

  it('targeted invalidation through the bridge refetches targeted issue query but does not refetch unrelated project active issue query', async () => {
    // 测试意图：验证针对指定 projectId 的 WebSocket changed 事件，
    // 仅使属于该 project 的 active issue query 重新发起请求，不影响其他 project 的 active issue query。
    const sockets = new FakeWebSocketHarness()
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
          refetchOnWindowFocus: false,
        },
      },
    })

    const fetchProj1Issue = vi.fn().mockResolvedValue({ id: 'issue-1', title: 'Issue 1' })
    const fetchProj2Issue = vi.fn().mockResolvedValue({ id: 'issue-2', title: 'Issue 2' })

    function TestConsumer() {
      const q1 = useQuery({
        queryKey: queryKeys.projects.issue(PROJECT_ID, 'issue-1'),
        queryFn: fetchProj1Issue,
      })
      const q2 = useQuery({
        queryKey: queryKeys.projects.issue(ANOTHER_PROJECT_ID, 'issue-2'),
        queryFn: fetchProj2Issue,
      })
      return (
        <div>
          <span>q1:{q1.data?.title}</span>
          <span>q2:{q2.data?.title}</span>
        </div>
      )
    }

    const { findByText } = render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          <ProjectsInvalidationBridge />
          <TestConsumer />
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    const socket = sockets.openLatest()

    // 等待初始查询成功返回
    await findByText('q1:Issue 1')
    await findByText('q2:Issue 2')
    expect(fetchProj1Issue).toHaveBeenCalledTimes(1)
    expect(fetchProj2Issue).toHaveBeenCalledTimes(1)

    // 发送针对 PROJECT_ID 的 changed 事件
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'projects' },
        name: 'changed',
        data: { projectId: PROJECT_ID },
      })
    })

    // PROJECT_ID 的 issue query 应被重新请求（次数变为 2）
    await waitFor(() => expect(fetchProj1Issue).toHaveBeenCalledTimes(2))
    // ANOTHER_PROJECT_ID 的 issue query 绝不能被重新请求（次数仍为 1）
    expect(fetchProj2Issue).toHaveBeenCalledTimes(1)

    // 发送针对 ANOTHER_PROJECT_ID 的 changed 事件
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'projects' },
        name: 'changed',
        data: { projectId: ANOTHER_PROJECT_ID },
      })
    })

    // ANOTHER_PROJECT_ID 的 issue query 重新请求（次数变为 2）
    await waitFor(() => expect(fetchProj2Issue).toHaveBeenCalledTimes(2))
    // PROJECT_ID 的 issue query 不再增加（次数仍为 2）
    expect(fetchProj1Issue).toHaveBeenCalledTimes(2)

    // 发送全量 resync 事件，两者的 issue query 都应被重新请求
    act(() => {
      socket.emitServer({
        type: 'resync',
        resource: { kind: 'projects' },
      })
    })
    await waitFor(() => expect(fetchProj1Issue).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(fetchProj2Issue).toHaveBeenCalledTimes(3))
  })
})
