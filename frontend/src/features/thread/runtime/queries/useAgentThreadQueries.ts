import { useContext, useMemo } from 'react'
import { AccountContext } from '@/features/account/account-context'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useThreadSnapshotSync } from '@/features/thread/runtime/state/thread-snapshot-sync'
import { fetchThreadSnapshot, isThreadSnapshotUnavailable } from '@/features/thread/runtime/state/thread-snapshot-cache'

export function useAgentThreadQueries(threadId: string) {
  const queryClient = useQueryClient()
  const account = useContext(AccountContext)
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
    enabled: account?.user?.role === 'ADMIN',
  })
  const snapshotQuery = useQuery({
    queryKey: queryKeys.threads.snapshot(threadId),
    queryFn: ({ signal }) => fetchThreadSnapshot(queryClient, threadId, { signal }),
    enabled: (query) => Boolean(threadId) && !isThreadSnapshotUnavailable(query),
  })
  const snapshot = snapshotQuery.data
  const thread = snapshot?.thread
  const sessionId = thread?.sessionId ?? ''

  // 为派生数组提供稳定的身份，避免依赖它们的 hook 在每次渲染时都重跑。
  const models = useMemo(
    () => modelsQuery.data ?? [],
    [modelsQuery.data],
  )
  const entries = useMemo(() => snapshot?.entries ?? [], [snapshot])
  const queuedCommands = useMemo(() => snapshot?.queuedCommands ?? [], [snapshot])
  const toolInvocations = useMemo(() => snapshot?.toolInvocations ?? [], [snapshot])
  const activeAssistantOutput = useMemo(
    () => snapshot?.activeAssistantOutput ?? null,
    [snapshot],
  )
  const generationFailures = useMemo(
    () => snapshot?.generationFailures ?? [],
    [snapshot],
  )
  const stopReceipts = useMemo(() => snapshot?.stopReceipts ?? [], [snapshot])
  // 未确认退出事实是后端快照的必需字段：只在快照尚未加载时为空，不静默兜底缺失契约。
  const unconfirmedExecutions = snapshot == null ? [] : snapshot.unconfirmedExecutions
  const threadSnapshotSync = useThreadSnapshotSync(threadId, snapshot != null)

  return {
    modelsQuery,
    snapshotQuery,
    unavailable: threadSnapshotSync.unavailable,
    snapshotSyncError: threadSnapshotSync.error,
    retrySnapshotSync: threadSnapshotSync.retry,
    models,
    thread,
    sessionId,
    entries,
    queuedCommands,
    activeAssistantOutput,
    toolInvocations,
    generationFailures,
    stopReceipts,
    unconfirmedExecutions,
  }
}
