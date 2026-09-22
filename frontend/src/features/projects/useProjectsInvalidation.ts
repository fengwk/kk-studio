import { useContext, useEffect, useRef } from 'react'
import { QueryClientContext, type QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ProjectsChangedEventPayload } from './types'

let activeQueryClient: QueryClient | null = null
type InvalidationCallback = (payload?: ProjectsChangedEventPayload) => void
const activeCallbacks = new Set<InvalidationCallback>()

/**
 * 注册当前活动的 QueryClient，用于在接收到项目变更通知时执行精准的 Query 失效。
 */
export function registerProjectsQueryClient(client: QueryClient | null): void {
  activeQueryClient = client
}

/**
 * 项目变更通知入口（由 SSE 扩展桥接调用）。
 * 彻底废除 window 自定义事件，统一直连 TanStack Query 精确失效并通知活跃监听器。
 */
export function notifyProjectsChanged(payload?: ProjectsChangedEventPayload): void {
  if (activeQueryClient) {
    if (payload?.projectId) {
      void activeQueryClient.invalidateQueries({
        queryKey: queryKeys.projects.snapshot(payload.projectId),
        exact: true,
      })
      void activeQueryClient.invalidateQueries({
        queryKey: queryKeys.projects.detail(payload.projectId),
        exact: true,
      })
    } else {
      void activeQueryClient.invalidateQueries({
        queryKey: queryKeys.projects.all,
      })
    }
  }

  for (const cb of activeCallbacks) {
    try {
      cb(payload)
    } catch {
      // Ignore callback errors during broadcast
    }
  }
}

/**
 * Hook: 确保活动页面将自身的 QueryClient 注册至 SSE invalidation 通道，并可订阅变更回调。
 */
export function useProjectsInvalidation(
  onInvalidate?: (payload?: ProjectsChangedEventPayload) => void,
): void {
  const queryClient = useContext(QueryClientContext)
  const listenerRef = useRef(onInvalidate)
  useEffect(() => {
    listenerRef.current = onInvalidate
  }, [onInvalidate])

  useEffect(() => {
    if (queryClient) {
      registerProjectsQueryClient(queryClient)
    }
    const callback: InvalidationCallback = (payload) => {
      listenerRef.current?.(payload)
    }
    activeCallbacks.add(callback)
    return () => {
      activeCallbacks.delete(callback)
      if (queryClient && activeQueryClient === queryClient) {
        registerProjectsQueryClient(null)
      }
    }
  }, [queryClient])
}
