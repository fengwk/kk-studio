import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApplicationEvents } from '@/shared/app-events'
import { createStopRequestId } from '@/features/thread/command-batch-plan'
import {
  projectCompactionBranch,
  type CompactionBranchProjection,
} from '@/features/thread/runtime/state/compaction-branch'
import { harnessService } from '@/shared/api/harness-service'
import { isThreadActiveStatus } from '@/features/thread/runtime/thread-panel/thread-status-format'
import { queryKeys } from '@/shared/lib/query-keys'
import {
  fetchThreadSnapshot,
  requestThreadSnapshotRefresh,
} from '@/features/thread/runtime/state/thread-snapshot-cache'
import { useThreadSnapshotSync } from '@/features/thread/runtime/state/thread-snapshot-sync'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import type { HarnessThreadDTO } from '@/shared/api/generated/contracts'

interface CompactionBranchState {
  thread: HarnessThreadDTO | null
  branch: CompactionBranchProjection | null
  isLoading: boolean
  isError: boolean
  refetch: () => void
  /** 分支仍在执行：决定 Stop 是否可用。 */
  running: boolean
  stop: () => void
  stopPending: boolean
  stopError: unknown
}

/**
 * 压缩执行视图的数据入口：只读取 `afterEntryId` 之后的分支自己的 Entry。
 *
 * 从共享 Thread 快照投影压缩分支；实时变化由 Thread 同步器推送。停止使用当前快照 version
 * 做 CAS，成功后显式刷新权威快照。
 */
export function useCompactionBranch(threadId: string, afterEntryId: string): CompactionBranchState {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const queryKey = queryKeys.threads.snapshot(threadId)
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchThreadSnapshot(queryClient, threadId, { signal }),
    enabled: isCanonicalUuid(threadId) && isCanonicalUuid(afterEntryId),
  })
  const snapshotSync = useThreadSnapshotSync(threadId, query.data != null)
  const stopMutation = useMutation({
    mutationFn: async () => {
      const thread = query.data?.thread
      if (thread == null) {
        throw new Error('compaction branch snapshot is not loaded')
      }
      return harnessService.stopThread(threadId, {
        stopRequestId: createStopRequestId(),
        expectedVersion: thread.version,
      })
    },
    onSettled: () => requestThreadSnapshotRefresh(queryClient, applicationEvents, threadId),
  })
  const snapshot = query.data
  const cutIndex = snapshot?.entries.findIndex((entry) => entry.entryId === afterEntryId) ?? -1
  const branch = snapshot != null && cutIndex >= 0
    ? projectCompactionBranch(snapshot.entries.slice(cutIndex + 1))
    : null
  return {
    thread: query.data?.thread ?? null,
    branch,
    isLoading: query.isLoading,
    // 服务端在游标不是祖先时回落为完整历史；那不能当作分支自己的 Entry 展示。
    isError: query.isError || snapshotSync.error != null || (query.data != null && branch == null),
    refetch: snapshotSync.retry,
    running: isThreadActiveStatus(query.data?.thread.status),
    stop: () => { stopMutation.mutate() },
    stopPending: stopMutation.isPending,
    stopError: stopMutation.error,
  }
}
