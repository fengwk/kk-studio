import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  notifyProjectsChanged,
  useProjectsInvalidation,
} from './useProjectsInvalidation'

describe('useProjectsInvalidation', () => {
  it('should invalidate specific project queries when projectId is provided', () => {
    // 测试意图：验证单一 Query 失效机制，在接收到携带 projectId 的变更时精确失效 snapshot 与 detail
    const queryClient = new QueryClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { unmount } = renderHook(() => useProjectsInvalidation(), {
      wrapper: ({ children }) =>
        createElement(QueryClientProvider, { client: queryClient }, children),
    })

    notifyProjectsChanged({ projectId: 'p1' })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['projects', 'snapshot', 'p1'],
      exact: true,
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['projects', 'detail', 'p1'],
      exact: true,
    })

    unmount()
  })

  it('should invalidate all project queries when no projectId is provided', () => {
    // 测试意图：验证未提供 projectId 时失效所有 projects query
    const queryClient = new QueryClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { unmount } = renderHook(() => useProjectsInvalidation(), {
      wrapper: ({ children }) =>
        createElement(QueryClientProvider, { client: queryClient }, children),
    })

    notifyProjectsChanged()
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['projects'],
    })

    unmount()
  })

  it('should not invalidate queries after unmounting', () => {
    // 测试意图：验证组件卸载后已注销当前 QueryClient，不会产生悬空回调或重复失效
    const queryClient = new QueryClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { unmount } = renderHook(() => useProjectsInvalidation(), {
      wrapper: ({ children }) =>
        createElement(QueryClientProvider, { client: queryClient }, children),
    })

    unmount()
    notifyProjectsChanged({ projectId: 'p2' })
    expect(invalidateSpy).not.toHaveBeenCalled()
  })
})
