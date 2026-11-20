import { useMemo } from 'react'
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
  isThreadSnapshotUnavailable,
  requestThreadSnapshotRefresh,
} from '@/features/thread/runtime/state/thread-snapshot-cache'
import { useThreadSnapshotSync } from '@/features/thread/runtime/state/thread-snapshot-sync'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { useHarnessThreadRealtime } from '@/features/thread/runtime/thread-realtime/useHarnessThreadRealtime'
import { buildThreadTimeline } from '@/features/thread/runtime/thread-timeline/thread-timeline'
import type { DialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { HarnessThreadDTO, ToolInvocationDTO } from '@/shared/api/generated/contracts'

const NO_TOOLS: ToolInvocationDTO[] = []

interface CompactionBranchState {
  thread: HarnessThreadDTO | null
  branch: CompactionBranchProjection | null
  messages: DialogueMessage[]
  isLoading: boolean
  isError: boolean
  unavailable: boolean
  refetch: () => void
  /** 分支仍在执行：决定 Stop 是否可用。 */
  running: boolean
  stop: () => void
  stopPending: boolean
  stopError: unknown
}

/**
 * 压缩执行视图的数据入口：只投影 `afterEntryId` 之后的分支自己的 Entry。
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
    enabled: (query) => isCanonicalUuid(threadId) && isCanonicalUuid(afterEntryId)
      && !isThreadSnapshotUnavailable(query),
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
  const entries = snapshot?.entries
  const branch = useMemo(() => {
    const cutIndex = entries?.findIndex((entry) => entry.entryId === afterEntryId) ?? -1
    return entries != null && cutIndex >= 0
      ? projectCompactionBranch(entries.slice(cutIndex + 1))
      : null
  }, [afterEntryId, entries])
  const output = !snapshotSync.unavailable && branch != null ? snapshot?.activeAssistantOutput ?? null : null
  const { modelStream } = useHarnessThreadRealtime(
    threadId,
    !snapshotSync.unavailable && branch != null,
    snapshot?.thread.version,
    output,
    NO_TOOLS,
    snapshot?.generationFailures,
  )
  // 持久正文接管时立即移除上一帧；禁用的工具和内部指令不进入对话。
  const stream = modelStream != null && output != null && output.resultEntryId == null
    && modelStream.invocationId === output.generationId && modelStream.attempt === output.attempt
    && modelStream.status !== 'error'
    ? modelStream : null
  const messages = buildThreadTimeline(branch?.assistantEntries ?? [], [], [], stream, null,
    snapshot?.generationFailures, output).messages.filter((message) =>
    message.role === 'assistant' && message.text.trim() !== '')
  return {
    thread: query.data?.thread ?? null,
    branch,
    messages,
    isLoading: query.isLoading,
    // 找不到切点时，不把完整历史当成分支内容。
    isError: query.isError || snapshotSync.error != null || (query.data != null && branch == null),
    unavailable: snapshotSync.unavailable,
    refetch: snapshotSync.retry,
    running: !snapshotSync.unavailable && isThreadActiveStatus(query.data?.thread.status),
    stop: () => { stopMutation.mutate() },
    stopPending: stopMutation.isPending,
    stopError: stopMutation.error,
  }
}
