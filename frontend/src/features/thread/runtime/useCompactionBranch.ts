import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createStopRequestId } from '@/features/thread/command-batch-plan'
import {
  projectCompactionBranch,
  type CompactionBranchProjection,
} from '@/features/thread/runtime/compaction-branch'
import { isThreadActiveStatus } from '@/features/thread/runtime/thread-panel/thread-status-format'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import type { HarnessThreadDTO } from '@/shared/api/generated/contracts'

const RUNNING_POLL_MILLIS = 2000

export interface CompactionBranchState {
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
 * 运行中按固定间隔重新读取，直到分支结束；停止是唯一写操作，使用当前快照 version
 * 做 CAS，成功后重新读取该分支。
 */
export function useCompactionBranch(threadId: string, afterEntryId: string): CompactionBranchState {
  const queryClient = useQueryClient()
  const queryKey = queryKeys.threads.compactionBranch(threadId, afterEntryId)
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => harnessService.getThreadSnapshot(threadId, { afterEntryId, signal }),
    enabled: isCanonicalUuid(threadId) && isCanonicalUuid(afterEntryId),
    refetchInterval: (current) =>
      isThreadActiveStatus(current.state.data?.thread.status) ? RUNNING_POLL_MILLIS : false,
  })
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
    onSettled: () => queryClient.invalidateQueries({ queryKey }),
  })
  const branch = query.data?.baseEntryId === afterEntryId
    ? projectCompactionBranch(query.data.entries)
    : null
  return {
    thread: query.data?.thread ?? null,
    branch,
    isLoading: query.isLoading,
    // 服务端在游标不是祖先时回落为完整历史；那不能当作分支自己的 Entry 展示。
    isError: query.isError || (query.data != null && branch == null),
    refetch: () => { void query.refetch() },
    running: isThreadActiveStatus(query.data?.thread.status),
    stop: () => { stopMutation.mutate() },
    stopPending: stopMutation.isPending,
    stopError: stopMutation.error,
  }
}
