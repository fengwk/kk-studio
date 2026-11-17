import { useCallback, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { useApplicationEvents } from '@/shared/app-events'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { queryKeys } from '@/shared/lib/query-keys'
import type { HarnessThreadTreeNodeDTO } from '@/shared/api/generated/contracts'
import { projectThreadTree } from '@/features/thread/runtime/thread-panel/active-thread-tree'

/**
 * 执行树查询的唯一定义：同一个 bound ID 复用既有 QueryClient key。
 * bound ID 可以是子代理；HTTP 仅 bootstrap 真实执行根，之后同 root 的 WebSocket
 * snapshot 是唯一 live 数据来源；诊断与 bootstrap 失败重试仍使用 HTTP。
 */
export function useActiveThreadTree(boundThreadId: string | null) {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const [snapshotFailedRootId, setSnapshotFailedRootId] = useState<string | null>(null)
  const [liveBoundThreadId, setLiveBoundThreadId] = useState<string | null>(null)
  const isLiveForBoundThread = boundThreadId != null && liveBoundThreadId === boundThreadId
  const treeQuery = useQuery({
    queryKey: queryKeys.threads.tree(boundThreadId ?? ''),
    queryFn: async ({ signal }) => {
      const nodes = await harnessService.getThreadTree(boundThreadId ?? '', { signal })
      // 非法树不进入缓存：React Query 保留上一次成功结果，并单独暴露失败。
      const tree = projectThreadTree(nodes)
      if (tree.root == null) {
        throw new Error('thread tree does not contain an execution root')
      }
      if (!tree.nodesById.has(boundThreadId ?? '')) {
        throw new Error('thread tree does not contain requested bound thread')
      }
      return nodes
    },
    enabled: Boolean(boundThreadId) && !isLiveForBoundThread,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
  })
  const projection = useMemo(() => projectThreadTree(treeQuery.data ?? []), [treeQuery.data])
  const actualRootThreadId = projection.root?.threadId ?? null
  const boundQueryKey = queryKeys.threads.tree(boundThreadId ?? '')
  const requestTreeRefresh = useCallback(() => {
    if (actualRootThreadId != null) {
      applicationEvents.requestResourceRefresh({ kind: 'thread-tree', id: actualRootThreadId })
    }
  }, [applicationEvents, actualRootThreadId])
  useResourceSync(actualRootThreadId == null ? null : { kind: 'thread-tree', id: actualRootThreadId }, {
    // Bootstrap 已提供首个有效树；subscribed/reconnect 的恢复快照由服务端强制推送。
    sync: requestTreeRefresh,
    syncOnSubscribed: false,
    onSubscribed: () => {
      // 可能已有其它显式失效启动的旧 bootstrap 请求；live 连接建立后不能让它覆盖 WS。
      if (boundThreadId != null) {
        setLiveBoundThreadId(boundThreadId)
      }
      void queryClient.cancelQueries({ queryKey: boundQueryKey })
    },
    onError: (code) => {
      if (code === 'TREE_SNAPSHOT_FAILED' && actualRootThreadId != null) {
        setSnapshotFailedRootId(actualRootThreadId)
      }
    },
    onSyncError: () => {
      if (actualRootThreadId != null) {
        setSnapshotFailedRootId(actualRootThreadId)
      }
    },
    onEvent: (name, data) => {
      if (name !== 'snapshot' || boundThreadId == null || data == null || typeof data !== 'object') {
        return
      }
      const nodes = (data as { nodes?: unknown }).nodes
      if (!Array.isArray(nodes)) {
        setSnapshotFailedRootId(actualRootThreadId)
        return
      }
      const typedNodes = nodes as HarnessThreadTreeNodeDTO[]
      let tree
      try {
        tree = projectThreadTree(typedNodes)
      } catch {
        setSnapshotFailedRootId(actualRootThreadId)
        return
      }
      if (tree.root?.threadId !== actualRootThreadId || !tree.nodesById.has(boundThreadId)) {
        setSnapshotFailedRootId(actualRootThreadId)
        return
      }
      // Fence any late bootstrap/refetch response before applying this authoritative live snapshot.
      setLiveBoundThreadId(boundThreadId)
      void queryClient.cancelQueries({ queryKey: boundQueryKey })
      queryClient.setQueryData(boundQueryKey, typedNodes)
      setSnapshotFailedRootId(null)
    },
  })
  const { rows, historyRows, nodesById, root } = projection
  const activeCount = useMemo(
    () => rows.reduce((count, row) => (row.node.status !== 'IDLE' ? count + 1 : count), 0),
    [rows],
  )
  // Thread name/agent identity are facts of the tree snapshot, not duplicated in interaction DTOs.
  const bootstrapFailed = treeQuery.isError && treeQuery.data == null
  const refreshFailed = (treeQuery.isError && treeQuery.data != null)
    || snapshotFailedRootId === actualRootThreadId
  const bootstrapData = treeQuery.data
  const refetchHttp = treeQuery.refetch
  const refetch = useCallback(() => {
    if (bootstrapData == null || actualRootThreadId == null) {
      // The HTTP request is still the bootstrap path until one valid tree has been accepted.
      void refetchHttp()
      return
    }
    requestTreeRefresh()
  }, [bootstrapData, refetchHttp, actualRootThreadId, requestTreeRefresh])

  return {
    rows,
    historyRows,
    root,
    rootThreadId: actualRootThreadId,
    nodesById,
    activeCount,
    isLoading: treeQuery.isLoading,
    isError: bootstrapFailed || refreshFailed,
    refreshFailed,
    refetch,
  }
}
