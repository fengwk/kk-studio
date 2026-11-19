import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { useApplicationEvents } from '@/shared/app-events'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { queryKeys } from '@/shared/lib/query-keys'
import type { HarnessThreadTreeNodeDTO } from '@/shared/api/generated/contracts'
import { projectThreadTree } from '@/features/thread/runtime/thread-panel/active-thread-tree'

/**
 * 执行树查询的唯一定义：同一个 bound ID 复用既有 QueryClient key。
 * HTTP 先确认执行根；随后由 WS 推送，只有 `snapshot-ref` 才触发权威回读。
 * 回读复用同一 query；合法 WS 快照取消在途回读并立即接管缓存。
 */
export function useActiveThreadTree(boundThreadId: string | null) {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const [snapshotFailedRootId, setSnapshotFailedRootId] = useState<string | null>(null)
  const [liveBoundThreadId, setLiveBoundThreadId] = useState<string | null>(null)
  const isLiveForBoundThread = boundThreadId != null && liveBoundThreadId === boundThreadId
  const boundQueryKey = useMemo(() => queryKeys.threads.tree(boundThreadId ?? ''), [boundThreadId])
  const fetchTreeNodes = useCallback(async ({ signal }: { signal: AbortSignal }) => {
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
  }, [boundThreadId])
  const treeQuery = useQuery({
    queryKey: boundQueryKey,
    queryFn: fetchTreeNodes,
    enabled: Boolean(boundThreadId) && !isLiveForBoundThread,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
  })
  const projection = useMemo(() => projectThreadTree(treeQuery.data ?? []), [treeQuery.data])
  const actualRootThreadId = projection.root?.threadId ?? null
  // 异步回读可能跨越多次渲染：最新事实经 ref 读取，避免闭包里的旧 root/bound。
  const latestRef = useRef({ actualRootThreadId })
  useEffect(() => {
    latestRef.current = { actualRootThreadId }
  })
  const overflowRead = useRef<{ pending: boolean } | null>(null)
  const cancelTreeRead = useCallback(() => {
    overflowRead.current = null
    void queryClient.cancelQueries({ queryKey: boundQueryKey, exact: true })
  }, [boundQueryKey, queryClient])
  useEffect(() => () => {
    if (overflowRead.current != null) {
      cancelTreeRead()
    }
  }, [cancelTreeRead])
  const requestTreeRefresh = useCallback(() => {
    const rootThreadId = latestRef.current.actualRootThreadId
    if (rootThreadId != null) {
      if (!applicationEvents.requestResourceRefresh({ kind: 'thread-tree', id: rootThreadId })) {
        setSnapshotFailedRootId(rootThreadId)
      }
    }
  }, [applicationEvents])
  const readOversizedTree = useCallback(async () => {
    if (overflowRead.current != null) {
      overflowRead.current.pending = true
      return
    }
    const read = { pending: false }
    overflowRead.current = read
    const rootThreadId = latestRef.current.actualRootThreadId
    try {
      do {
        read.pending = false
        await queryClient.fetchQuery({
          queryKey: boundQueryKey, queryFn: fetchTreeNodes, staleTime: 0, retry: false,
        })
        if (overflowRead.current !== read) {
          return
        }
        setSnapshotFailedRootId((current) => (current === rootThreadId ? null : current))
        // 新提示可能晚于 GET 取样；在途提示合并为一次尾随读取，不自行重试。
      } while (read.pending)
    } catch {
      if (overflowRead.current === read) {
        setSnapshotFailedRootId(rootThreadId)
      }
    } finally {
      if (overflowRead.current === read) {
        overflowRead.current = null
      }
    }
  }, [boundQueryKey, fetchTreeNodes, queryClient])
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
    // 完整树放不进单帧：只读权威 HTTP，绝不为该提示重订阅或退回轮询。
    onSnapshotRef: () => {
      void readOversizedTree()
    },
    onError: () => {
      if (actualRootThreadId != null) {
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
      cancelTreeRead()
      setLiveBoundThreadId(boundThreadId)
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
    || (actualRootThreadId != null && snapshotFailedRootId === actualRootThreadId)
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
