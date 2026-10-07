import { useCallback, useEffect, useMemo } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { useApplicationEvents } from '@/shared/app-events'
import { queryKeys } from '@/shared/lib/query-keys'
import { projectThreadTree } from '@/features/ai/runtime/thread-panel/active-thread-tree'

/**
 * 执行树查询的唯一定义：同一个 bound ID 复用既有 QueryClient key。
 * bound ID 可以是子代理；订阅按响应真实执行根，回读仍使用调用方自己的 key。
 *
 * 根本地空闲也继续查询（后代可能在处理）；非法树不写入缓存，查询失败不伪装成
 * 最新数据：无数据时给出错误与重试，有旧数据时保留并标记刷新失败。
 *
 * 执行树变化由服务端按真实执行根聚合后推送（子代理写入也会聚合到 root），因此这里没有固定轮询：
 * subscribed（首订与每次重连重订阅）与 resync 都回读，changed 只回读本根。
 */
export function useActiveThreadTree(rootThreadId: string | null) {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const treeQuery = useQuery({
    queryKey: queryKeys.threads.tree(rootThreadId ?? ''),
    queryFn: async () => {
      const nodes = await harnessService.getThreadTree(rootThreadId ?? '')
      // 非法树不进入缓存：React Query 保留上一次成功结果，并单独暴露失败。
      projectThreadTree(nodes)
      return nodes
    },
    enabled: Boolean(rootThreadId),
  })
  const projection = useMemo(() => projectThreadTree(treeQuery.data ?? []), [treeQuery.data])
  const actualRootThreadId = projection.root?.threadId ?? null
  const invalidateTree = useCallback(() => {
    if (rootThreadId == null) {
      return
    }
    void queryClient.invalidateQueries({ queryKey: queryKeys.threads.tree(rootThreadId) })
  }, [queryClient, rootThreadId])
  useEffect(() => {
    if (actualRootThreadId == null) {
      return
    }
    return applicationEvents.subscribe(
      { kind: 'tree', id: actualRootThreadId },
      {
        onSubscribed: invalidateTree,
        onEvent: (name) => {
          if (name === 'changed') {
            invalidateTree()
          }
        },
        onResync: invalidateTree,
      },
    )
  }, [applicationEvents, invalidateTree, actualRootThreadId])
  const { rows, historyRows, nodesById, root } = projection
  const activeCount = useMemo(
    () => rows.reduce((count, row) => (row.node.processing ? count + 1 : count), 0),
    [rows],
  )
  // 执行树是 Thread 名称/代理身份的唯一事实源：交互来源与活跃行都从这里解析，
  // 不在交互 DTO 里再造第二份 agent 名称。
  return {
    rows,
    historyRows,
    root,
    rootThreadId: actualRootThreadId,
    nodesById,
    activeCount,
    isLoading: treeQuery.isLoading,
    isError: treeQuery.isError,
    refreshFailed: treeQuery.isError && treeQuery.data != null,
    refetch: () => void treeQuery.refetch(),
  }
}
